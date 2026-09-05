package manualcontrol.attackform.animator;

import common.CommonStatic;
import common.pack.Source;
import common.system.files.FileData;
import common.util.anim.AnimCE;
import common.util.anim.AnimCI;
import common.util.anim.MaAnim;
import common.util.anim.Part;
import manualcontrol.attackform.AttackFormLog;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Queue;

final class AttackAnimationRepository {

    static final int MAX_TIMELINE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_PARTS = 100000;
    private static final int MAX_MOVES_PER_PART = 100000;
    private static final int MAX_TOTAL_MOVES = 200000;
    private static final int MAX_LINES = MAX_PARTS * 2 + MAX_TOTAL_MOVES + 3;
    private static final int MAX_LINE_CHARS = 65536;
    private static final int MAX_MANIFEST_BYTES = 2048;
    private static final String MANIFEST_NAME = "maanim_attack_forms.txt";
    private static final String MANIFEST_MAGIC = "switch-attack-animation-v1";
    private static final String TRANSACTION_DIRECTORY = ".maanim_attack_forms.pending";
    private static final String LOCK_NAME = ".maanim_attack_forms.lock";
    private static final String STAGED_MANIFEST = "manifest";
    private static final String READY_MARKER = "ready";
    private static volatile Field sourceLoaderField;

    private AttackAnimationRepository() {}

    static MaAnim[] load(AnimCI animation) throws Exception {
        requireEligible(animation);
        Object rawLoader = rawLoader(animation);
        File workspace = workspaceDirectory(animation, rawLoader);
        if (workspace != null && new File(workspace, TRANSACTION_DIRECTORY).exists()) {
            SaveLock lock = acquire(workspace);
            try {
                recoverLocked(workspace);
            } finally {
                lock.close();
            }
        }

        rejectUnexpectedNames(listNames(animation, rawLoader, workspace));

        if (loadFile(rawLoader, animation, fileName(6)) != null)
            throw new IllegalStateException("attack6 exceeds the attack5 limit");

        FileData manifestData = loadFile(rawLoader, animation, MANIFEST_NAME);
        Manifest manifest = manifestData == null ? null
                : parseManifest(readBytes(manifestData, MAX_MANIFEST_BYTES, MANIFEST_NAME));
        ArrayList<MaAnim> loaded = new ArrayList<MaAnim>();
        boolean gap = false;
        for (int attack = 2; attack <= 5; attack++) {
            FileData data = loadFile(rawLoader, animation, fileName(attack));
            int index = attack - 2;
            boolean expected = manifest != null && index < manifest.count;
            if (data == null) {
                if (expected)
                    throw new IllegalStateException(fileName(attack)
                            + " is missing from the committed generation");
                gap = true;
                continue;
            }
            if (manifest != null && !expected)
                throw new IllegalStateException(fileName(attack)
                        + " is outside the committed generation");
            if (manifest == null && gap)
                throw new IllegalStateException("extra attack timelines must be contiguous");
            byte[] payload = readBytes(data, MAX_TIMELINE_BYTES, fileName(attack));
            if (manifest != null && !manifest.hashes[index].equals(sha256(payload)))
                throw new IllegalStateException(fileName(attack)
                        + " failed committed-generation integrity validation");
            loaded.add(parseTimeline(payload, fileName(attack)));
        }
        if (manifest != null && loaded.size() != manifest.count)
            throw new IllegalStateException("committed attack timeline count changed");
        return loaded.toArray(new MaAnim[loaded.size()]);
    }

    static void save(Source.ResourceLocation target, MaAnim[] extras) throws Exception {
        SaveTransaction transaction = null;
        try {
            transaction = stage(target, extras);
            if (transaction == null) return;
            commit(transaction);
            transaction = null;
        } finally {
            if (transaction != null) abort(transaction);
        }
    }

    static SaveTransaction stage(Source.ResourceLocation target, MaAnim[] extras)
            throws Exception {
        if (extras == null || extras.length > AttackAnimationIndex.MAX_EXTRA_ATTACKS)
            throw new IllegalArgumentException("extra attack timeline count must be 0..4");
        requireEligible(target);
        File directory = animationDirectory(target);
        if (!directory.exists() && !directory.mkdirs())
            throw new IllegalStateException("could not create animation directory " + directory);

        SaveLock lock = acquire(directory);
        File transactionDirectory = new File(directory, TRANSACTION_DIRECTORY);
        boolean created = false;
        try {
            recoverLocked(directory);
            rejectUnexpectedNames(directory.list());
            if (extras.length == 0 && !hasExtensionData(directory)) {
                lock.close();
                return null;
            }

            byte[][] payloads = new byte[extras.length][];
            String[] hashes = new String[extras.length];
            for (int i = 0; i < extras.length; i++) {
                validate(extras[i], fileName(i + 2));
                payloads[i] = encode(extras[i]);

                parseTimeline(payloads[i], fileName(i + 2));
                hashes[i] = sha256(payloads[i]);
            }
            Manifest manifest = new Manifest(extras.length, hashes);
            byte[] manifestPayload = encodeManifest(manifest);

            Files.createDirectory(transactionDirectory.toPath());
            created = true;
            for (int i = 0; i < payloads.length; i++)
                writeFresh(new File(transactionDirectory, fileName(i + 2)), payloads[i]);
            writeFresh(new File(transactionDirectory, STAGED_MANIFEST), manifestPayload);
            validateStaged(transactionDirectory, manifest);
            return new SaveTransaction(directory, transactionDirectory, manifest, lock);
        } catch (Throwable error) {
            if (created) {
                try { cleanupTransaction(transactionDirectory); }
                catch (Throwable cleanup) { error.addSuppressed(cleanup); }
            }
            lock.close();
            if (error instanceof Exception) throw (Exception) error;
            if (error instanceof Error) throw (Error) error;
            throw new IllegalStateException(error);
        }
    }

    static void commit(SaveTransaction transaction) throws Exception {
        requireOpen(transaction);
        try {
            validateStaged(transaction.transactionDirectory, transaction.manifest);
            byte[] manifest = Files.readAllBytes(
                    new File(transaction.transactionDirectory, STAGED_MANIFEST).toPath());
            writeFresh(new File(transaction.transactionDirectory, READY_MARKER),
                    sha256(manifest).getBytes(StandardCharsets.US_ASCII));
            transaction.ready = true;
            rollForward(transaction.directory, transaction.transactionDirectory);
            cleanupTransaction(transaction.transactionDirectory);
        } finally {
            transaction.close();
        }
    }

    static void abort(SaveTransaction transaction) {
        if (transaction == null || transaction.closed) return;
        try {
            if (!transaction.ready) cleanupTransaction(transaction.transactionDirectory);
        } catch (Throwable error) {
            AttackFormLog.error("extra attack stage cleanup failed", error);
        } finally {
            transaction.close();
        }
    }

    private static void recoverLocked(File directory) throws Exception {
        File transaction = new File(directory, TRANSACTION_DIRECTORY);
        if (!transaction.exists()) return;
        requireTransactionDirectory(directory, transaction);
        File ready = new File(transaction, READY_MARKER);
        if (!ready.isFile()) {

            cleanupTransaction(transaction);
            AttackFormLog.info("discarded uncommitted extra-attack animation stage");
            return;
        }
        rollForward(directory, transaction);
        cleanupTransaction(transaction);
        AttackFormLog.info("recovered committed extra-attack animation transaction");
    }

    private static void rollForward(File directory, File transaction) throws Exception {
        requireTransactionDirectory(directory, transaction);
        byte[] manifestPayload = readSmallFile(new File(transaction, STAGED_MANIFEST),
                MAX_MANIFEST_BYTES, "staged manifest");
        Manifest manifest = parseManifest(manifestPayload);
        byte[] ready = readSmallFile(new File(transaction, READY_MARKER), 128,
                "ready marker");
        if (!sha256(manifestPayload).equals(
                new String(ready, StandardCharsets.US_ASCII).trim()))
            throw new IllegalStateException("extra attack ready marker changed");
        validateStaged(transaction, manifest);

        for (int i = 0; i < manifest.count; i++) {
            int attack = i + 2;
            atomicReplace(new File(transaction, fileName(attack)),
                    new File(directory, fileName(attack)), transaction);
            fault("after-attack" + attack);
        }
        for (int attack = manifest.count + 2; attack <= 5; attack++) {
            Files.deleteIfExists(new File(directory, fileName(attack)).toPath());
            fault("after-delete-attack" + attack);
        }

        if (manifest.count == 0)
            Files.deleteIfExists(new File(directory, MANIFEST_NAME).toPath());
        else
            atomicReplace(new File(transaction, STAGED_MANIFEST),
                    new File(directory, MANIFEST_NAME), transaction);
        fault("after-manifest");
        verifyWorkspaceGeneration(directory, manifest);
    }

    private static void validateStaged(File transaction, Manifest manifest) throws Exception {
        for (int i = 0; i < manifest.count; i++) {
            File file = new File(transaction, fileName(i + 2));
            byte[] payload = readSmallFile(file, MAX_TIMELINE_BYTES, file.getName());
            if (!manifest.hashes[i].equals(sha256(payload)))
                throw new IllegalStateException(file.getName() + " staged hash changed");
            parseTimeline(payload, file.getName());
        }
    }

    private static void verifyWorkspaceGeneration(File directory, Manifest manifest)
            throws Exception {
        for (int attack = 2; attack <= 5; attack++) {
            File file = new File(directory, fileName(attack));
            int index = attack - 2;
            if (index >= manifest.count) {
                if (file.exists())
                    throw new IllegalStateException(file.getName() + " survived generation commit");
                continue;
            }
            byte[] payload = readSmallFile(file, MAX_TIMELINE_BYTES, file.getName());
            if (!manifest.hashes[index].equals(sha256(payload)))
                throw new IllegalStateException(file.getName() + " commit hash mismatch");
        }
        File manifestFile = new File(directory, MANIFEST_NAME);
        if (manifest.count == 0) {
            if (manifestFile.exists())
                throw new IllegalStateException("empty animation generation kept a manifest");
        } else {
            Manifest committed = parseManifest(readSmallFile(manifestFile,
                    MAX_MANIFEST_BYTES, MANIFEST_NAME));
            if (!manifest.equals(committed))
                throw new IllegalStateException("committed animation manifest changed");
        }
    }

    private static void atomicReplace(File source, File target, File transaction)
            throws Exception {
        if (!source.isFile()) throw new IllegalStateException("missing staged " + source.getName());
        File pending = new File(transaction, "commit_" + target.getName() + ".tmp");
        Files.copy(source.toPath(), pending.toPath(), StandardCopyOption.REPLACE_EXISTING);
        FileOutputStream sync = new FileOutputStream(pending, true);
        try { sync.getFD().sync(); } finally { sync.close(); }
        try {
            Files.move(pending.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ignored) {
            Files.move(pending.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static MaAnim parseTimeline(byte[] payload, String owner) throws Exception {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(payload)).toString();
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException(owner + " is not valid UTF-8", error);
        }
        Queue<String> lines = new LinkedList<String>();
        BufferedReader reader = new BufferedReader(new StringReader(text));
        for (String line; (line = reader.readLine()) != null; ) {
            if (line.length() > MAX_LINE_CHARS)
                throw new IllegalArgumentException(owner + " contains an oversized line");
            lines.add(line);
            if (lines.size() > MAX_LINES)
                throw new IllegalArgumentException(owner + " contains too many lines");
        }
        preflight(new LinkedList<String>(lines), owner);
        MaAnim timeline = new MaAnim(lines, false);
        while (!lines.isEmpty()) {
            String trailing = lines.poll();
            if (trailing != null && !trailing.trim().isEmpty())
                throw new IllegalArgumentException(owner + " contains trailing data");
        }
        validate(timeline, owner);
        return timeline;
    }

    private static void preflight(Queue<String> lines, String owner) {
        if (!"[maanim]".equals(poll(lines, owner, "header")))
            throw new IllegalArgumentException(owner + " has an invalid header");
        if (!"1".equals(poll(lines, owner, "version").trim()))
            throw new IllegalArgumentException(owner + " has an unsupported version");
        int parts = boundedInt(poll(lines, owner, "part count"), 0, MAX_PARTS,
                owner + " part count");
        int totalMoves = 0;
        for (int part = 0; part < parts; part++) {
            String[] header = poll(lines, owner, "part " + part).split(",", -1);
            if (header.length != 6)
                throw new IllegalArgumentException(owner + " part " + part
                        + " must contain five fields and one name");
            for (int field = 0; field < 5; field++)
                integer(header[field], owner + " part " + part + " field " + field);
            int moves = boundedInt(poll(lines, owner, "part " + part + " move count"),
                    0, MAX_MOVES_PER_PART, owner + " part " + part + " move count");
            totalMoves += moves;
            if (totalMoves > MAX_TOTAL_MOVES)
                throw new IllegalArgumentException(owner + " has too many total moves");
            for (int move = 0; move < moves; move++) {
                String[] values = poll(lines, owner, "part " + part + " move " + move)
                        .split(",", -1);
                if (values.length < 4)
                    throw new IllegalArgumentException(owner + " part " + part
                            + " move " + move + " has fewer than four fields");
                for (int field = 0; field < 4; field++)
                    integer(values[field], owner + " move field " + field);
                for (int field = 4; field < values.length; field++)
                    if (!values[field].trim().isEmpty())
                        throw new IllegalArgumentException(owner + " move has trailing fields");
            }
        }
        while (!lines.isEmpty())
            if (!lines.poll().trim().isEmpty())
                throw new IllegalArgumentException(owner + " contains trailing data");
    }

    private static String poll(Queue<String> lines, String owner, String item) {
        String value = lines.poll();
        if (value == null)
            throw new IllegalArgumentException(owner + " is missing " + item);
        return value;
    }

    private static int boundedInt(String value, int min, int max, String owner) {
        int parsed = integer(value, owner);
        if (parsed < min || parsed > max)
            throw new IllegalArgumentException(owner + " is outside " + min + ".." + max);
        return parsed;
    }

    private static int integer(String value, String owner) {
        String trimmed = value == null ? "" : value.trim();
        if (!trimmed.matches("[-+]?\\d{1,11}"))
            throw new IllegalArgumentException(owner + " is not an integer");
        long parsed;
        try { parsed = Long.parseLong(trimmed); }
        catch (NumberFormatException error) {
            throw new IllegalArgumentException(owner + " is not an integer", error);
        }
        if (parsed < Integer.MIN_VALUE || parsed > Integer.MAX_VALUE)
            throw new IllegalArgumentException(owner + " exceeds integer range");
        return (int) parsed;
    }

    private static byte[] encode(MaAnim timeline) throws Exception {
        BoundedOutputStream bytes = new BoundedOutputStream(MAX_TIMELINE_BYTES);
        PrintStream output = new PrintStream(bytes, false, StandardCharsets.UTF_8.name());
        try {
            timeline.write(output);
            output.flush();
            if (output.checkError())
                throw new IllegalStateException("extra attack timeline exceeds the size limit");
        } finally {
            output.close();
        }
        return bytes.toByteArray();
    }

    private static void validate(MaAnim timeline, String owner) {
        if (timeline == null || timeline.parts == null || timeline.n != timeline.parts.length)
            throw new IllegalArgumentException(owner + " has invalid part data");
        if (timeline.n < 0 || timeline.n > MAX_PARTS)
            throw new IllegalArgumentException(owner + " has too many parts");
        int totalMoves = 0;
        for (int i = 0; i < timeline.parts.length; i++) {
            Part part = timeline.parts[i];
            if (part == null || part.ints == null || part.ints.length != 5
                    || part.moves == null || part.n != part.moves.length)
                throw new IllegalArgumentException(owner + " part " + i + " is malformed");
            if (part.name == null || part.name.length() > MAX_LINE_CHARS
                    || part.name.indexOf('\n') >= 0 || part.name.indexOf('\r') >= 0
                    || part.name.indexOf(',') >= 0)
                throw new IllegalArgumentException(owner + " part " + i + " has an invalid name");
            if (part.moves.length > MAX_MOVES_PER_PART)
                throw new IllegalArgumentException(owner + " part " + i + " has too many moves");
            totalMoves += part.moves.length;
            if (totalMoves > MAX_TOTAL_MOVES)
                throw new IllegalArgumentException(owner + " has too many total moves");
            for (int move = 0; move < part.moves.length; move++)
                if (part.moves[move] == null || part.moves[move].length != 4)
                    throw new IllegalArgumentException(owner + " part " + i
                            + " move " + move + " is malformed");
        }
        timeline.validate();
    }

    private static Object rawLoader(AnimCI animation) throws Exception {
        Object keeper = animation.loader;
        if (!(keeper instanceof AnimCI.AnimCIKeeper))
            throw new IllegalStateException("unsupported animation keeper");
        Object value = ((AnimCI.AnimCIKeeper) keeper).loader;
        if (!(value instanceof Source.SourceAnimLoader))
            throw new IllegalStateException("unsupported animation loader "
                    + (value == null ? "null" : value.getClass().getName()));
        Field field = sourceLoaderField;
        if (field == null) {
            synchronized (AttackAnimationRepository.class) {
                field = sourceLoaderField;
                if (field == null) {
                    field = Source.SourceAnimLoader.class.getDeclaredField("loader");
                    field.setAccessible(true);
                    sourceLoaderField = field;
                }
            }
        }
        Object raw = field.get(value);
        if (!(raw instanceof Source.SourceLoader))
            throw new IllegalStateException("animation source loader is unavailable");
        return raw;
    }

    private static FileData loadFile(Object rawLoader, AnimCI animation, String name) {
        return ((Source.SourceLoader) rawLoader).loadFile(animation.id.base, animation.id, name);
    }

    private static File workspaceDirectory(AnimCI animation, Object rawLoader) throws Exception {
        Source source = source(rawLoader);
        if (source != null && !(source instanceof Source.Workspace)) return null;
        return animationDirectory(animation.id);
    }

    private static String[] listNames(AnimCI animation, Object rawLoader, File workspace) {
        if (workspace != null && workspace.isDirectory()) return workspace.list();
        Source source = source(rawLoader);
        if (source != null)
            return source.listFile(animation.id.base.toString()
                    + "/" + animation.id.id);
        return null;
    }

    private static Source source(Object loader) {
        if (loader instanceof Source) return (Source) loader;
        if (loader == null) return null;
        try {
            for (Field field : loader.getClass().getDeclaredFields()) {
                if (!Source.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                Object value = field.get(loader);
                if (value instanceof Source) return (Source) value;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static void rejectUnexpectedNames(String[] names) {
        if (names == null) return;
        for (String name : names) {
            if (name == null) continue;
            String lower = name.toLowerCase(Locale.ROOT);
            if (MANIFEST_NAME.equals(lower)) continue;
            if (!lower.startsWith("maanim_attack") || !lower.endsWith(".txt")) continue;
            String number = lower.substring("maanim_attack".length(), lower.length() - 4);
            if (number.isEmpty()) continue;  
            if (!number.matches("\\d+"))
                throw new IllegalStateException(name + " is not a supported attack timeline");
            long value;
            try { value = Long.parseLong(number); }
            catch (NumberFormatException error) { value = Long.MAX_VALUE; }
            if (value < 2 || value > 5 || !number.equals(Long.toString(value)))
                throw new IllegalStateException(name + " is outside attack2..attack5");
        }
    }

    private static boolean hasExtensionData(File directory) {
        if (new File(directory, MANIFEST_NAME).exists()) return true;
        for (int attack = 2; attack <= 5; attack++)
            if (new File(directory, fileName(attack)).exists()) return true;
        return false;
    }

    private static Manifest parseManifest(byte[] payload) throws Exception {
        BufferedReader reader = new BufferedReader(new StringReader(
                new String(payload, StandardCharsets.US_ASCII)));
        if (!MANIFEST_MAGIC.equals(reader.readLine()))
            throw new IllegalStateException("invalid extra attack animation manifest");
        String countLine = reader.readLine();
        if (countLine == null || !countLine.startsWith("count="))
            throw new IllegalStateException("missing extra attack animation count");
        int count = boundedInt(countLine.substring(6), 0,
                AttackAnimationIndex.MAX_EXTRA_ATTACKS, "manifest count");
        String[] hashes = new String[count];
        for (int i = 0; i < count; i++) {
            String prefix = "attack" + (i + 2) + "=";
            String line = reader.readLine();
            if (line == null || !line.startsWith(prefix))
                throw new IllegalStateException("missing " + prefix + " manifest entry");
            String hash = line.substring(prefix.length());
            if (!hash.matches("[0-9a-f]{64}"))
                throw new IllegalStateException("invalid manifest digest for attack" + (i + 2));
            hashes[i] = hash;
        }
        for (String trailing; (trailing = reader.readLine()) != null; )
            if (!trailing.trim().isEmpty())
                throw new IllegalStateException("extra attack animation manifest has trailing data");
        return new Manifest(count, hashes);
    }

    private static byte[] encodeManifest(Manifest manifest) {
        StringBuilder text = new StringBuilder(MANIFEST_MAGIC).append('\n')
                .append("count=").append(manifest.count).append('\n');
        for (int i = 0; i < manifest.count; i++)
            text.append("attack").append(i + 2).append('=')
                    .append(manifest.hashes[i]).append('\n');
        return text.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] readBytes(FileData data, int limit, String owner) {
        if (data == null) throw new IllegalArgumentException(owner + " is missing");
        int size = data.size();
        if (size < 0 || size > limit)
            throw new IllegalArgumentException(owner + " exceeds the size limit");
        byte[] bytes = data.getBytes();
        if (bytes == null || bytes.length > limit)
            throw new IllegalArgumentException(owner + " could not be read safely");
        return bytes;
    }

    private static byte[] readSmallFile(File file, int limit, String owner) throws Exception {
        if (!file.isFile() || file.length() < 0 || file.length() > limit)
            throw new IllegalArgumentException(owner + " exceeds the size limit or is missing");
        byte[] bytes = Files.readAllBytes(file.toPath());
        if (bytes.length > limit)
            throw new IllegalArgumentException(owner + " exceeds the size limit");
        return bytes;
    }

    private static void writeFresh(File target, byte[] payload) throws Exception {
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
        } catch (Exception error) {
            if (claimed) try { Files.deleteIfExists(target.toPath()); } catch (Exception ignored) {}
            throw error;
        }
    }

    private static void cleanupTransaction(File transaction) throws Exception {
        if (!transaction.exists()) return;
        File parent = transaction.getParentFile();
        requireTransactionDirectory(parent, transaction);
        File[] files = transaction.listFiles();
        if (files == null) throw new IOException("could not inspect attack animation transaction");
        for (File file : files) {
            String name = file.getName();
            boolean expected = STAGED_MANIFEST.equals(name) || READY_MARKER.equals(name)
                    || name.matches("maanim_attack[2-5]\\.txt")
                    || name.matches("commit_.+\\.tmp");
            if (!expected || !file.isFile())
                throw new SecurityException("unexpected transaction entry " + name);
        }
        for (File file : files) Files.deleteIfExists(file.toPath());
        Files.delete(transaction.toPath());
    }

    private static void requireTransactionDirectory(File parent, File transaction)
            throws Exception {
        if (parent == null || transaction == null
                || !transaction.getCanonicalFile().equals(
                new File(parent.getCanonicalFile(), TRANSACTION_DIRECTORY).getCanonicalFile()))
            throw new SecurityException("invalid attack animation transaction path");
        if (transaction.exists() && !transaction.isDirectory())
            throw new SecurityException("attack animation transaction is not a directory");
    }

    private static SaveLock acquire(File directory) throws Exception {
        File lockFile = new File(directory, LOCK_NAME);
        FileChannel channel = FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IllegalStateException(
                    "another process is saving this animation");
            return new SaveLock(channel, lock);
        } catch (Throwable error) {
            try { channel.close(); } catch (Throwable ignored) {}
            if (error instanceof Exception) throw (Exception) error;
            if (error instanceof Error) throw (Error) error;
            throw new IllegalStateException(error);
        }
    }

    private static void fault(String step) throws IOException {
        if (step.equals(System.getProperty("manualcontrol.attackform.animator.failStep")))
            throw new IOException("injected attack animation failure at " + step);
    }

    private static File animationDirectory(Source.ResourceLocation id) throws Exception {
        if (CommonStatic.ctx == null) throw new IllegalStateException("BCU context is unavailable");
        File root = CommonStatic.ctx.getWorkspaceFile(".").getCanonicalFile();
        File directory = CommonStatic.ctx.getWorkspaceFile(id.getPath()).getCanonicalFile();
        String rootPath = root.getPath();
        String targetPath = directory.getPath();
        if (!targetPath.equals(rootPath)
                && !targetPath.startsWith(rootPath + File.separator))
            throw new SecurityException("animation sidecar path escaped workspace");
        return directory;
    }

    private static String fileName(int attackNumber) {
        return "maanim_attack" + attackNumber + ".txt";
    }

    private static void requireEligible(AnimCI animation) {
        if (animation == null) throw new IllegalArgumentException("animation is missing");
        requireEligible(animation.id);
    }

    private static void requireEligible(Source.ResourceLocation id) {
        if (id == null || id.base == null || empty(id.pack) || empty(id.id))
            throw new IllegalArgumentException("animation identity is incomplete");
        if (id.base != Source.BasePath.ANIM)
            throw new IllegalArgumentException("extra attacks require a unit animation");
    }

    private static void requireOpen(SaveTransaction transaction) {
        if (transaction == null || transaction.closed)
            throw new IllegalStateException("attack animation transaction is closed");
    }

    private static String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return hex(digest.digest(bytes));
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format("%02x", value & 0xff));
        return out.toString();
    }

    private static boolean empty(String value) {
        return value == null || value.isEmpty();
    }

    static final class SaveTransaction {
        final File directory;
        final File transactionDirectory;
        final Manifest manifest;
        final SaveLock lock;
        boolean ready;
        boolean closed;

        SaveTransaction(File directory, File transactionDirectory, Manifest manifest,
                        SaveLock lock) {
            this.directory = directory;
            this.transactionDirectory = transactionDirectory;
            this.manifest = manifest;
            this.lock = lock;
        }

        void close() {
            if (closed) return;
            closed = true;
            lock.close();
        }
    }

    private static final class SaveLock {
        final FileChannel channel;
        final FileLock lock;

        SaveLock(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        void close() {
            try { if (lock != null && lock.isValid()) lock.release(); }
            catch (Throwable ignored) {}
            try { channel.close(); } catch (Throwable ignored) {}
        }
    }

    private static final class Manifest {
        final int count;
        final String[] hashes;

        Manifest(int count, String[] hashes) {
            this.count = count;
            this.hashes = hashes == null ? new String[0] : hashes.clone();
            if (count != this.hashes.length)
                throw new IllegalArgumentException("manifest count/hash mismatch");
        }

        @Override
        public boolean equals(Object value) {
            if (!(value instanceof Manifest)) return false;
            Manifest other = (Manifest) value;
            if (count != other.count) return false;
            for (int i = 0; i < count; i++)
                if (!hashes[i].equals(other.hashes[i])) return false;
            return true;
        }

        @Override
        public int hashCode() {
            int value = count;
            for (String hash : hashes) value = value * 31 + hash.hashCode();
            return value;
        }
    }

    private static final class BoundedOutputStream extends OutputStream {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        BoundedOutputStream(int limit) {
            this.limit = limit;
        }

        @Override
        public void write(int value) throws IOException {
            requireCapacity(1);
            bytes.write(value);
        }

        @Override
        public void write(byte[] values, int offset, int length) throws IOException {
            requireCapacity(length);
            bytes.write(values, offset, length);
        }

        byte[] toByteArray() {
            return bytes.toByteArray();
        }

        private void requireCapacity(int added) throws IOException {
            if (added < 0 || bytes.size() > limit - added)
                throw new IOException("extra attack timeline exceeds the size limit");
        }
    }
}
