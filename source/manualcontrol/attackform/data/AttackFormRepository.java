package manualcontrol.attackform.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import common.CommonStatic;
import common.battle.data.CustomEntity;
import common.io.json.JsonDecoder;
import common.io.json.JsonEncoder;
import common.pack.PackData;
import common.pack.Source;
import common.util.anim.AnimCI;
import common.util.unit.Enemy;
import common.util.unit.Form;
import common.util.unit.Unit;
import manualcontrol.attackform.AttackFormFeature;
import manualcontrol.attackform.AttackFormLog;
import manualcontrol.attackform.editor.AttackFormBinding;
import manualcontrol.attackform.editor.AttackFormEditorHooks;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public final class AttackFormRepository {

    public static final String PATH = "attack_forms/index.json";
    private static final String FEATURE = "switch_attack_form";
    private static final int MAX_RECORDS = 10000;
    private static final int MAX_INPUT_BYTES = 16 * 1024 * 1024;
    private static final int MAX_JSON_ARRAY_ITEMS = 100000;
    private static final int MAX_JSON_NODES = 1000000;
    private static final ThreadLocal<SaveTransaction> STAGED_SAVE =
            new ThreadLocal<SaveTransaction>();
    private static final Map<PackData.UserPack, AttackFormSidecar> PENDING_BACKUPS =
            Collections.synchronizedMap(new WeakHashMap<PackData.UserPack, AttackFormSidecar>());
    private static final Map<PackData.UserPack, String> LOAD_FAILURES =
            Collections.synchronizedMap(new WeakHashMap<PackData.UserPack, String>());

    private AttackFormRepository() {}

    public static void beforePackSave(Object sourceValue, Object packValue, boolean auto) {

        STAGED_SAVE.remove();
        if (!AttackFormFeature.isReady()
                || !(sourceValue instanceof Source.Workspace)
                || !(packValue instanceof PackData.UserPack)) return;
        PackData.UserPack pack = (PackData.UserPack) packValue;
        boolean editorSuspended = false;
        try {
            AttackFormEditorHooks.beforePackSave(pack);

            editorSuspended = true;
            requireLoadSafe(pack);
            Source.Workspace source = (Source.Workspace) sourceValue;
            File target = sidecarFile(source.id, auto);

            recoverPending(source.id, auto);
            validatePack(pack);
            AttackFormSidecar document = snapshot(pack);

            if (document.entities.length == 0 && !target.exists()) return;
            String digest = stage(pendingFile(target), document);
            STAGED_SAVE.set(new SaveTransaction(target.getPath(), digest));
        } catch (Throwable error) {
            if (editorSuspended) AttackFormEditorHooks.afterPackSave(pack);
            AttackFormLog.error("sidecar staging failed; Save blocked before pack.json", error);
            throw new IllegalStateException("Switch Attack Form sidecar could not be staged", error);
        }
    }

    public static void onPackLoaded(Object value) {
        if (!AttackFormFeature.isReady() || !(value instanceof PackData.UserPack)) return;
        PackData.UserPack pack = (PackData.UserPack) value;
        try {
            AttackFormSidecar document = PENDING_BACKUPS.remove(pack);
            if (document == null && LOAD_FAILURES.containsKey(pack)) return;
            if (document == null) {
                recoverPending(pack, false);
                document = read(pack);
            }
            if (document == null) return;
            LoadResult result = attach(pack, document);
            if (!result.rejected && result.orphaned == 0) LOAD_FAILURES.remove(pack);
            else markLoadFailure(pack, result.reason());
            if (result.repairedRates && !result.rejected)
                AttackFormLog.info("repaired corrupt sidecar Rates to even defaults");
            AttackFormLog.info("sidecar loaded pack=" + pack.getSID()
                    + " attached=" + result.attached + " orphaned=" + result.orphaned
                    + (result.rejected ? " rejected=true" : ""));
        } catch (Throwable error) {
            markLoadFailure(pack, "sidecar load failed: " + error.getClass().getSimpleName());
            AttackFormLog.error("sidecar ignored for pack=" + safePackId(pack), error);
        }
    }

    public static synchronized void onPackSaved(Object sourceValue, Object packValue,
                                                boolean auto) {
        if (!(sourceValue instanceof Source.Workspace)
                || !(packValue instanceof PackData.UserPack)) return;
        SaveTransaction staged = STAGED_SAVE.get();
        if (staged == null) {
            AttackFormEditorHooks.afterPackSave(packValue);
            return;
        }
        try {
            Source.Workspace source = (Source.Workspace) sourceValue;
            PackData.UserPack pack = (PackData.UserPack) packValue;
            File target = sidecarFile(source.id, auto);
            if (!staged.targetPath.equals(target.getPath()))
                throw new IllegalStateException("staged sidecar does not match this Save");
            File pending = pendingFile(target);
            if (!pending.isFile() || !staged.digest.equals(sha256(pending)))
                throw new IllegalStateException("staged sidecar changed during stock Save");
            markReady(target, staged.digest);
            commit(pendingFile(target), target);
            if (!staged.digest.equals(sha256(target)))
                throw new IllegalStateException("committed sidecar failed integrity check");
            clearReady(target);
            AttackFormLog.debug(auto ? "autosave" : "save",
                    "pack=" + pack.getSID() + " sidecar=committed");
        } catch (Exception error) {
            AttackFormLog.error("sidecar commit failed; Save reported as failed", error);
            throw new IllegalStateException("Switch Attack Form sidecar could not be committed", error);
        } finally {
            STAGED_SAVE.remove();
            AttackFormEditorHooks.afterPackSave(packValue);
        }
    }

    public static synchronized void onPackSaveAborted(Object sourceValue, Object packValue,
                                                      boolean auto) {
        SaveTransaction staged = STAGED_SAVE.get();
        try {

            if (staged != null) {
                File target = new File(staged.targetPath);
                File pending = pendingFile(target);
                if (!readyFile(target).exists() && pending.isFile()
                        && staged.digest.equals(sha256(pending))) {
                    Files.deleteIfExists(pending.toPath());
                    AttackFormLog.debug(auto ? "autosave-abort" : "save-abort",
                            "discarded exact uncommitted sidecar stage");
                }
            }
        } catch (Throwable error) {

            AttackFormLog.error("could not discard aborted sidecar stage", error);
        } finally {
            STAGED_SAVE.remove();
            AttackFormEditorHooks.afterPackSave(packValue);
        }
    }

    public static void onBackupPackCreated(Object value, String workspaceId) {
        if (!AttackFormFeature.isReady() || !(value instanceof PackData.UserPack)) return;
        PackData.UserPack pack = (PackData.UserPack) value;
        try {
            recoverPending(workspaceId, true);
            File file = sidecarFile(workspaceId, true);
            AttackFormSidecar document = read(file);
            if (document != null) PENDING_BACKUPS.put(pack, document);
        } catch (Throwable error) {
            markLoadFailure(pack, "autosave sidecar load failed: "
                    + error.getClass().getSimpleName());
            AttackFormLog.error("autosave sidecar ignored for pack=" + safe(workspaceId), error);
        }
    }

    static AttackFormSidecar snapshot(PackData.UserPack pack) {
        AttackFormSidecar document = new AttackFormSidecar();
        document.packId = safePackId(pack);
        if (!notEmpty(document.packId))
            throw new IllegalStateException("pack identity is missing");
        ArrayList<AttackFormSidecar.Entry> entries = new ArrayList<AttackFormSidecar.Entry>();

        for (Unit unit : pack.units) {
            if (unit == null || unit.forms == null) continue;
            for (Form form : unit.forms) {
                if (form == null || !(form.du instanceof CustomEntity)) continue;
                append(entries, "unit", unit.id == null ? -1 : unit.id.id, form.fid,
                        form.anim, (CustomEntity) form.du, document.packId);
            }
        }
        for (Enemy enemy : pack.enemies) {
            if (enemy == null || !(enemy.de instanceof CustomEntity)) continue;
            append(entries, "enemy", enemy.id == null ? -1 : enemy.id.id, -1,
                    enemy.anim, (CustomEntity) enemy.de, document.packId);
        }

        document.entities = entries.toArray(new AttackFormSidecar.Entry[entries.size()]);
        return document;
    }

    static String encode(AttackFormSidecar document) throws Exception {
        JsonElement encoded = JsonEncoder.encode(document);
        if (encoded == null || !encoded.isJsonObject())
            throw new IllegalStateException("sidecar encoder returned no object");
        return encoded.toString();
    }

    static AttackFormSidecar decode(JsonElement element) throws Exception {
        validateEnvelope(element);
        AttackFormSidecar document = (AttackFormSidecar) JsonDecoder.decode(
                element, AttackFormSidecar.class, null);
        if (document == null || !FEATURE.equals(document.feature))
            throw new IllegalArgumentException("not a Switch Attack Form sidecar");
        if (document.entities == null) document.entities = new AttackFormSidecar.Entry[0];
        if (document.entities.length > MAX_RECORDS)
            throw new IllegalArgumentException("too many attack-form records");
        if (!notEmpty(document.packId))
            throw new IllegalArgumentException("sidecar pack identity is missing");
        return document;
    }

    private static void validateEnvelope(JsonElement element) {
        if (element == null || !element.isJsonObject())
            throw new IllegalArgumentException("sidecar root must be an object");
        JsonObject root = element.getAsJsonObject();
        JsonElement feature = root.get("feature");
        JsonElement packId = root.get("packId");
        JsonElement entities = root.get("entities");
        if (feature == null || !feature.isJsonPrimitive()
                || !FEATURE.equals(feature.getAsString()))
            throw new IllegalArgumentException("not a Switch Attack Form sidecar");
        if (packId == null || !packId.isJsonPrimitive()
                || !notEmpty(packId.getAsString()))
            throw new IllegalArgumentException("sidecar pack identity is missing");
        if (entities == null || !entities.isJsonArray())
            throw new IllegalArgumentException("sidecar entity list is missing");
        if (entities.getAsJsonArray().size() > MAX_RECORDS)
            throw new IllegalArgumentException("too many attack-form records");

        ArrayList<JsonElement> pending = new ArrayList<JsonElement>();
        pending.add(element);
        int nodes = 0;
        while (!pending.isEmpty()) {
            JsonElement next = pending.remove(pending.size() - 1);
            if (++nodes > MAX_JSON_NODES)
                throw new IllegalArgumentException("attack-form sidecar is too complex");
            if (next == null || next.isJsonNull() || next.isJsonPrimitive()) continue;
            if (next.isJsonArray()) {
                JsonArray array = next.getAsJsonArray();
                if (array.size() > MAX_JSON_ARRAY_ITEMS)
                    throw new IllegalArgumentException("sidecar array exceeds safety limit");
                for (JsonElement child : array) pending.add(child);
            } else {
                for (Map.Entry<String, JsonElement> child
                        : next.getAsJsonObject().entrySet()) pending.add(child.getValue());
            }
        }
    }

    private static void append(ArrayList<AttackFormSidecar.Entry> entries, String kind,
                               int entityId, int formId, Object animation,
                               CustomEntity entity, String packId) {
        AttackFormSet live = AttackFormStore.get(entity);
        if (live == null || !live.isMeaningful()) return;
        AttackFormSet copy = live.deepCopy();
        copy.synchronizePrimary(entity);
        AttackFormValidation validation = copy.validateForSave();
        if (!validation.valid)
            throw new IllegalStateException("invalid attack forms at " + kind + "/" + entityId
                    + ": " + validation.message);

        AttackFormSidecar.Entry entry = new AttackFormSidecar.Entry();
        entry.kind = kind;
        entry.entityId = entityId;
        entry.formId = formId;
        entry.data = copy;
        Anchor anchor = anchor(animation, packId);
        entry.animationPack = anchor.pack;
        entry.animationId = anchor.id;
        entry.animationBase = anchor.base;
        if (!anchor.complete())
            throw new IllegalStateException("missing animation identity for "
                    + kind + "/" + entityId);
        if (entries.size() >= MAX_RECORDS)
            throw new IllegalStateException("too many attack-form records to save");
        entries.add(entry);
    }

    private static LoadResult attach(PackData.UserPack pack, AttackFormSidecar document) {
        LoadResult result = new LoadResult();
        String packId = safePackId(pack);
        if (!notEmpty(packId) || !document.packId.equals(packId)) {
            result.rejected = true;
            result.orphaned = document.entities.length;
            AttackFormLog.info("sidecar pack id mismatch expected=" + packId
                    + " actual=" + safe(document.packId));
            return result;
        }

        Set<String> keys = new HashSet<String>();
        IdentityHashMap<CustomEntity, Boolean> targets = new IdentityHashMap<CustomEntity, Boolean>();
        ArrayList<Attachment> attachments = new ArrayList<Attachment>();
        for (AttackFormSidecar.Entry entry : document.entities) {
            if (entry == null || entry.entityId < 0 || !hasAnchor(entry)
                    || ("unit".equals(entry.kind) && entry.formId < 0)
                    || (!("unit".equals(entry.kind)) && !("enemy".equals(entry.kind)))
                    || !keys.add(entry.key()) || entry.data == null) {
                result.orphaned++;
                continue;
            }
            if (!entry.data.validateStructureForLoad().valid) {
                result.orphaned++;
                continue;
            }
            if (entry.data.repairRatesOnly()) result.repairedRates = true;
            if (!entry.data.isMeaningful() || !entry.data.validateForSave().valid) {
                result.orphaned++;
                continue;
            }
            CustomEntity entity;
            try {
                entity = resolve(pack, entry, packId);
            } catch (Throwable malformedOwner) {
                result.orphaned++;
                continue;
            }
            if (entity == null || !(entity instanceof AttackFormCarrier)
                    || targets.put(entity, Boolean.TRUE) != null) {
                result.orphaned++;
                continue;
            }
            AttackFormSet prepared = entry.data.deepCopy();
            prepared.synchronizePrimary(entity);
            attachments.add(new Attachment(entity, prepared));
        }

        if (result.orphaned > 0) {
            result.rejected = true;
            return result;
        }
        if (!AttackFormFeature.isReady()) {
            result.rejected = true;
            return result;
        }
        for (Attachment attachment : attachments) {
            ((AttackFormCarrier) attachment.entity).attackForm$set(attachment.data);
            result.attached++;
        }
        return result;
    }

    private static CustomEntity resolve(PackData.UserPack pack,
                                        AttackFormSidecar.Entry entry, String packId) {
        if ("unit".equals(entry.kind)) {
            Unit unit = pack.units.getRaw(entry.entityId);
            if (unit == null || unit.forms == null) return null;
            Form direct = null;
            for (Form form : unit.forms)
                if (form != null && form.fid == entry.formId) {
                    direct = form;
                    break;
                }
            if (matches(direct, entry, packId)) return entity(direct);
            if (!hasAnchor(entry)) return null;

            Form candidate = null;
            for (Form form : unit.forms) {
                if (!matches(form, entry, packId)) continue;
                if (candidate != null) return null;
                candidate = form;
            }
            return entity(candidate);
        }
        if ("enemy".equals(entry.kind)) {
            Enemy enemy = pack.enemies.getRaw(entry.entityId);
            if (enemy == null || !matches(enemy.anim, entry, packId)
                    || !(enemy.de instanceof CustomEntity)) return null;
            return (CustomEntity) enemy.de;
        }
        return null;
    }

    private static CustomEntity entity(Form form) {
        return form != null && form.du instanceof CustomEntity
                ? (CustomEntity) form.du : null;
    }

    private static boolean matches(Form form, AttackFormSidecar.Entry entry, String packId) {
        return form != null && form.du instanceof CustomEntity
                && matches(form.anim, entry, packId);
    }

    private static boolean matches(Object animation, AttackFormSidecar.Entry entry, String packId) {
        if (!hasAnchor(entry)) return false;
        Anchor actual = anchor(animation, packId);
        return safe(entry.animationPack).equals(actual.pack)
                && safe(entry.animationId).equals(actual.id)
                && safe(entry.animationBase).equals(actual.base);
    }

    private static Anchor anchor(Object animation, String packId) {
        if (!(animation instanceof AnimCI) || ((AnimCI) animation).id == null)
            return new Anchor("", "", "");
        Source.ResourceLocation location = ((AnimCI) animation).id;
        String owner = safe(location.pack);
        if ("_local".equals(owner) || owner.equals(packId)) owner = "$self";
        String id = safe(location.id);
        if (id.startsWith("_mapped_")) id = id.substring("_mapped_".length());
        return new Anchor(owner, id, location.base == null ? "" : location.base.toString());
    }

    private static AttackFormSidecar read(PackData.UserPack pack) throws Exception {
        if (pack.source == null) return null;
        if (pack.source instanceof Source.Workspace)
            return read(sidecarFile(((Source.Workspace) pack.source).id, false));
        if (!contains(pack.source, "attack_forms", "index.json")) return null;
        InputStream input = pack.source.streamFile(PATH);
        if (input == null)
            throw new IOException("listed attack-form sidecar could not be opened");
        try {
            return decode(parseLimited(input));
        } finally {
            input.close();
        }
    }

    private static AttackFormSidecar read(File file) throws Exception {
        if (file == null || !file.exists()) return null;
        if (!file.isFile())
            throw new IOException("attack-form sidecar path is not a file");
        if (file.length() > MAX_INPUT_BYTES)
            throw new IllegalArgumentException("attack-form sidecar exceeds size limit");
        InputStream input = new FileInputStream(file);
        try {
            return decode(parseLimited(input));
        } finally {
            input.close();
        }
    }

    private static boolean contains(Source source, String directory, String name) {
        for (String path : new String[]{directory, "./" + directory}) {
            String[] files = source.listFile(path);
            if (files != null)
                for (String file : files) if (name.equals(file)) return true;
        }
        return false;
    }

    private static synchronized String stage(File target, AttackFormSidecar document)
            throws Exception {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs())
            throw new IllegalStateException("could not create " + parent);
        byte[] payload = encodeForWrite(document);
        String digest = sha256(payload);
        boolean claimed = false;
        try {
            Files.createFile(target.toPath());
            claimed = true;
            FileOutputStream output = new FileOutputStream(target, false);
            try {
                output.write(payload);
                output.flush();
                output.getFD().sync();
            } finally {
                output.close();
            }
            if (!digest.equals(sha256(target)))
                throw new IllegalStateException("staged sidecar failed integrity check");
            return digest;
        } catch (Exception error) {

            if (claimed) try { Files.deleteIfExists(target.toPath()); } catch (Exception ignored) {}
            throw error;
        }
    }

    private static synchronized void commit(File pending, File target) throws Exception {
        if (pending == null || !pending.isFile())
            throw new IllegalStateException("staged sidecar is missing");
        backupCurrent(target);
        try {
            Files.move(pending.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ignored) {
            Files.move(pending.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void backupCurrent(File target) throws Exception {
        if (target == null || !target.isFile()) return;
        File backup = backupFile(target);
        File parent = backup.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs())
            throw new IllegalStateException("could not create " + parent);
        File temp = new File(parent, backup.getName() + ".tmp");
        try {
            Files.copy(target.toPath(), temp.toPath(), StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temp.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception ignored) {
                Files.move(temp.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            try { Files.deleteIfExists(temp.toPath()); } catch (Exception ignored) {}
        }
    }

    private static byte[] encodeForWrite(AttackFormSidecar document) throws Exception {
        if (document == null || document.entities == null)
            throw new IllegalArgumentException("sidecar document is incomplete");
        if (document.entities.length > MAX_RECORDS)
            throw new IllegalArgumentException("too many attack-form records");
        byte[] payload = encode(document).getBytes(StandardCharsets.UTF_8);
        if (payload.length > MAX_INPUT_BYTES)
            throw new IllegalArgumentException("attack-form sidecar exceeds size limit");
        return payload;
    }

    private static JsonElement parseLimited(InputStream input) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        for (int read; (read = input.read(buffer)) >= 0; ) {
            total += read;
            if (total > MAX_INPUT_BYTES)
                throw new IllegalArgumentException("attack-form sidecar exceeds size limit");
            bytes.write(buffer, 0, read);
        }
        Reader reader = new InputStreamReader(
                new ByteArrayInputStream(bytes.toByteArray()), StandardCharsets.UTF_8);
        return JsonParser.parseReader(reader);
    }

    private static File pendingFile(File target) {
        return new File(target.getParentFile(), target.getName() + ".pending");
    }

    private static File readyFile(File target) {
        return new File(target.getParentFile(), target.getName() + ".ready");
    }

    private static File backupFile(File target) {
        String name = target.getName();
        if (name.endsWith(".json")) name = name.substring(0, name.length() - 5);
        return new File(new File(target.getParentFile(), "recovery"),
                name + ".backup.json");
    }

    private static void markReady(File target, String expectedDigest) throws Exception {
        File pending = pendingFile(target);
        if (!pending.isFile())
            throw new IllegalStateException("cannot confirm a missing staged sidecar");
        if (!expectedDigest.equals(sha256(pending)))
            throw new IllegalStateException("cannot confirm a changed staged sidecar");
        File marker = readyFile(target);
        File parent = marker.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs())
            throw new IllegalStateException("could not create " + parent);
        File temp = new File(parent, marker.getName() + ".tmp");
        try {
            Files.write(temp.toPath(), expectedDigest.getBytes(StandardCharsets.US_ASCII));
            try {
                Files.move(temp.toPath(), marker.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception ignored) {
                Files.move(temp.toPath(), marker.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            try { Files.deleteIfExists(temp.toPath()); } catch (Exception ignored) {}
        }
    }

    private static void clearReady(File target) {
        try {
            Files.deleteIfExists(readyFile(target).toPath());
        } catch (Exception error) {
            AttackFormLog.error("committed sidecar ready-marker cleanup failed", error);
        }
    }

    private static void recoverPending(PackData.UserPack pack, boolean auto) throws Exception {
        if (pack != null && pack.source instanceof Source.Workspace)
            recoverPending(((Source.Workspace) pack.source).id, auto);
    }

    private static synchronized void recoverPending(String workspaceId, boolean auto)
            throws Exception {
        File target = sidecarFile(workspaceId, auto);
        File marker = readyFile(target);
        File pending = pendingFile(target);
        String expected = marker.isFile() ? readMarker(marker) : null;
        if (marker.isFile() && pending.isFile()) {
            if (!expected.equals(sha256(pending)))
                throw new IllegalStateException("staged sidecar does not match commit marker");
            commit(pending, target);
            if (!expected.equals(sha256(target)))
                throw new IllegalStateException("recovered sidecar failed integrity check");
            AttackFormLog.info("recovered committed attack-form sidecar journal for "
                    + safe(workspaceId));
            clearReady(target);
            return;
        }
        if (marker.isFile() && target.isFile()) {

            if (!expected.equals(sha256(target)))
                throw new IllegalStateException("commit marker does not match current sidecar");
            clearReady(target);
            return;
        }
        if (marker.isFile())
            throw new IllegalStateException("sidecar journal marker has no document");
        if (pending.isFile())
            throw new IllegalStateException("unresolved staged sidecar has no commit marker");
    }

    private static String readMarker(File marker) throws Exception {
        byte[] bytes = Files.readAllBytes(marker.toPath());
        String value = new String(bytes, StandardCharsets.US_ASCII).trim();
        if (!value.matches("[0-9a-f]{64}"))
            throw new IllegalStateException("invalid sidecar commit marker");
        return value;
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        InputStream input = new FileInputStream(file);
        try {
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) >= 0; )
                digest.update(buffer, 0, read);
        } finally {
            input.close();
        }
        return hex(digest.digest());
    }

    private static String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(bytes);
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        final char[] digits = "0123456789abcdef".toCharArray();
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 255;
            out[i * 2] = digits[value >>> 4];
            out[i * 2 + 1] = digits[value & 15];
        }
        return new String(out);
    }

    private static File sidecarFile(String workspaceId, boolean auto) throws Exception {
        if (CommonStatic.ctx == null) throw new IllegalStateException("BCU context is unavailable");
        String id = safe(workspaceId);
        File root = CommonStatic.ctx.getWorkspaceFile(".").getCanonicalFile();
        File target = auto
                ? CommonStatic.ctx.getWorkspaceFile("./_autosave/attack_forms_" + fileId(id) + ".json")
                : CommonStatic.ctx.getWorkspaceFile("./" + id + "/" + PATH);
        File canonical = target.getCanonicalFile();
        String rootPath = root.getPath();
        String targetPath = canonical.getPath();
        if (!targetPath.equals(rootPath)
                && !targetPath.startsWith(rootPath + File.separator))
            throw new SecurityException("sidecar path escaped workspace");
        return canonical;
    }

    private static String fileId(String value) {
        String cleaned = safe(value).replaceAll("[^0-9A-Za-z._-]", "_");
        return cleaned.isEmpty() ? "unknown" : cleaned;
    }

    private static void requireSaveable(AttackFormSet set, Object animation, String owner) {
        if (set == null) return;
        AttackFormValidation validation = set.validateForSave();
        if (!validation.valid)
            throw new IllegalStateException("Switch Attack Form cannot save " + owner
                    + ": " + validation.message);
        if (!set.isMeaningful()) return;
        if (!(animation instanceof AnimCI))
            throw new IllegalStateException("Switch Attack Form cannot save " + owner
                    + ": a unit or enemy animation is required");
        AttackFormBinding.Report binding = AttackFormBinding.resolve(set, (AnimCI) animation);
        if (!binding.valid)
            throw new IllegalStateException("Switch Attack Form cannot save " + owner
                    + ": " + binding.message);
    }

    private static void requireLoadSafe(PackData.UserPack pack) {
        String failure = LOAD_FAILURES.get(pack);
        if (failure != null)
            throw new IllegalStateException("Switch Attack Form cannot overwrite unresolved "
                    + "sidecar data for pack " + safePackId(pack) + ": " + failure);
    }

    private static void markLoadFailure(PackData.UserPack pack, String reason) {
        if (pack != null) LOAD_FAILURES.put(pack, safe(reason));
    }

    private static void validatePack(PackData.UserPack pack) {
        for (Unit unit : pack.units) {
            if (unit == null || unit.forms == null) continue;
            for (Form form : unit.forms) {
                if (form == null || !(form.du instanceof CustomEntity)) continue;
                requireSaveable(AttackFormStore.get(form.du), form.anim,
                        "unit/" + (unit.id == null ? -1 : unit.id.id) + "/form/" + form.fid);
            }
        }
        for (Enemy enemy : pack.enemies) {
            if (enemy == null || !(enemy.de instanceof CustomEntity)) continue;
            requireSaveable(AttackFormStore.get(enemy.de), enemy.anim,
                    "enemy/" + (enemy.id == null ? -1 : enemy.id.id));
        }
    }

    private static String safePackId(PackData.UserPack pack) {
        try { return safe(pack == null ? null : pack.getSID()); }
        catch (Throwable ignored) { return ""; }
    }

    private static boolean notEmpty(String value) {
        return value != null && !value.isEmpty();
    }

    private static boolean hasAnchor(AttackFormSidecar.Entry entry) {
        return entry != null && notEmpty(entry.animationPack) && notEmpty(entry.animationId)
                && notEmpty(entry.animationBase);
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ');
    }

    private static final class Anchor {
        final String pack;
        final String id;
        final String base;

        Anchor(String pack, String id, String base) {
            this.pack = pack;
            this.id = id;
            this.base = base;
        }

        boolean complete() {
            return !pack.isEmpty() && !id.isEmpty() && !base.isEmpty();
        }
    }

    private static final class Attachment {
        final CustomEntity entity;
        final AttackFormSet data;

        Attachment(CustomEntity entity, AttackFormSet data) {
            this.entity = entity;
            this.data = data;
        }
    }

    private static final class SaveTransaction {
        final String targetPath;
        final String digest;

        SaveTransaction(String targetPath, String digest) {
            this.targetPath = targetPath;
            this.digest = digest;
        }
    }

    private static final class LoadResult {
        int attached;
        int orphaned;
        boolean rejected;
        boolean repairedRates;

        String reason() {
            if (orphaned > 0) return orphaned + " unresolved sidecar record(s)";
            return "sidecar transaction was rejected";
        }
    }
}
