package manualcontrol.attackform;

import common.CommonStatic;
import common.pack.Context;
import common.pack.Source;
import common.util.anim.AnimCE;
import common.util.anim.AnimCI;
import common.util.anim.AnimU;
import common.util.anim.ImgCut;
import common.util.anim.MaAnim;
import common.util.anim.MaModel;
import common.util.anim.Part;
import manualcontrol.FeatureFlags;
import manualcontrol.attackform.animator.AttackAnimationIndex;
import manualcontrol.attackform.animator.AttackAnimationStore;
import manualcontrol.attackform.transform.AttackFormAnimatorTransformer;
import manualcontrol.attackform.transform.AttackFormPreviewTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class AttackFormPart2Test {

    private static int checks;
    private static File contextRoot;

    public static void main(String[] args) throws Exception {
        System.setProperty("manualcontrol.log", "logs/attack-form-part2-test.log");
        ensureTestContext("core");
        ready(AttackFormPart2Test.class.getClassLoader());
        testVisibleIndexMapping();
        testStoreCopyDeleteLimitAndUndo();
        testTimelinePersistenceAndLoadFailure();
        testLazyResolutionAndMappedCopy();
        testTransactionRecoveryAndParserLimits();
        testBoundedUndoHistory();
        verifyOfflineTransform(new File("../BCU-0-5-8-8.jar"));
        for (String path : args) verifyOfflineTransform(new File(path));
        System.out.println("[AttackFormPart2Test] PASS checks=" + checks);
    }

    private static void testVisibleIndexMapping() {
        String[] stock = {"walk", "idle", "attack", "hit back", "burrow down",
                "burrow move", "burrow up"};
        for (int extras = 0; extras <= 4; extras++) {
            String[] visible = AttackAnimationIndex.visibleNames(stock, extras);
            require(visible.length == stock.length + extras, "visible list length");
            require("attack".equals(visible[2]), "stock attack name moved");
            for (int slot = 1; slot <= extras; slot++) {
                int row = AttackAnimationIndex.attackSlotToVisible(slot);
                require(("attack" + (slot + 1)).equals(visible[row]),
                        "extra attack name mismatch");
                require(AttackAnimationIndex.visibleToAttackSlot(row, extras) == slot,
                        "extra attack slot mapping mismatch");
                require(AttackAnimationIndex.visibleToStock(row, extras) == 2,
                        "custom timeline did not bridge through stock ATK");
            }
            for (int stockRow = 0; stockRow < stock.length; stockRow++) {
                int visibleRow = AttackAnimationIndex.stockToVisible(stockRow, extras);
                require(stockRow == AttackAnimationIndex.visibleToStock(visibleRow, extras),
                        "stock row round trip mismatch");
                if (stockRow != 2)
                    require(AttackAnimationIndex.visibleToAttackSlot(visibleRow, extras) == -1,
                            "stock row misclassified as an attack form");
            }
        }
        require(AttackAnimationIndex.remapVisible(5, 2, 0) == 3,
                "switching animations changed shifted hit-back into another stock row");
        require(AttackAnimationIndex.remapVisible(4, 2, 4) == 4,
                "switching animations did not preserve attack3");
        require(AttackAnimationIndex.remapVisible(4, 2, 0) == 2,
                "missing attack3 did not fall back to stock attack");
        expectThrows(() -> AttackAnimationIndex.visibleNames(stock, 5),
                "attack6 was accepted by visible list");
    }

    private static void testStoreCopyDeleteLimitAndUndo() throws Exception {
        AnimCE animation = animation("store_" + unique(), "anim");
        animation.anims[2] = namedTimeline("base");
        pushStockHistory(animation);
        AttackAnimationStore.ensureForEditor(animation);
        require(AttackAnimationStore.extraCount(animation) == 0, "new store not empty");

        int attack2 = AttackAnimationStore.add(animation, 0);
        coreUnsave(animation, "add attack2");
        require(attack2 == 1, "first extra was not attack2");
        require(AttackAnimationStore.timeline(animation, 1) != animation.anims[2],
                "copied attack shares timeline object");
        require(AttackAnimationStore.timeline(animation, 1).parts[0]
                        != animation.anims[2].parts[0],
                "copied attack shares Part object");
        AttackAnimationStore.timeline(animation, 1).parts[0].name = "attack2";
        coreUnsave(animation, "edit attack2");
        require("base".equals(animation.anims[2].parts[0].name),
                "editing attack2 changed stock attack");

        int attack3 = AttackAnimationStore.add(animation, 1);
        coreUnsave(animation, "add attack3");
        require(attack3 == 2, "second extra was not attack3");
        require(AttackAnimationStore.timeline(animation, 2)
                        != AttackAnimationStore.timeline(animation, 1),
                "attack3 shares timeline object with attack2");
        AttackAnimationStore.timeline(animation, 2).parts[0].name = "attack3";
        coreUnsave(animation, "edit attack3");
        require("attack2".equals(AttackAnimationStore.timeline(animation, 1).parts[0].name),
                "editing attack3 changed attack2");

        coreRestore(animation);
        require(AttackAnimationStore.extraCount(animation) == 2
                        && "attack2".equals(
                        AttackAnimationStore.timeline(animation, 2).parts[0].name),
                "first Undo did not restore the previous attack3 edit");
        coreRestore(animation);
        require(AttackAnimationStore.extraCount(animation) == 1,
                "Undo did not restore the previous extra count");
        require("attack2".equals(AttackAnimationStore.timeline(animation, 1).parts[0].name),
                "Undo restored the wrong custom timeline");

        AttackAnimationStore.add(animation, -1);
        AttackAnimationStore.onUnsaved(animation);
        AttackAnimationStore.add(animation, 0);
        AttackAnimationStore.onUnsaved(animation);
        AttackAnimationStore.add(animation, 0);
        AttackAnimationStore.onUnsaved(animation);
        require(AttackAnimationStore.extraCount(animation) == 4, "attack5 was not reachable");
        expectThrows(() -> AttackAnimationStore.add(animation, 0),
                "more than attack5 was accepted");

        AttackAnimationStore.timeline(animation, 2).parts =
                new Part[]{new Part(0, 5)};
        AttackAnimationStore.timeline(animation, 2).n = 1;
        AttackAnimationStore.timeline(animation, 2).parts[0].name = "shift-me";
        AttackAnimationStore.remove(animation, 1);
        require("shift-me".equals(AttackAnimationStore.timeline(animation, 1).parts[0].name),
                "deleting attack2 did not shift attack3 down");
    }

    private static void testTimelinePersistenceAndLoadFailure() throws Exception {
        String stockOnlyPack = "stock_only_" + unique();
        AnimCE stockOnly = animation(stockOnlyPack, "anim");
        AttackAnimationStore.ensureForEditor(stockOnly);
        AttackAnimationStore.onSaved(stockOnly.id, stockOnly);
        File stockOnlyDirectory = new File(contextRoot, stockOnly.id.getPath());
        require(!new File(stockOnlyDirectory, "maanim_attack_forms.txt").exists(),
                "one-attack Save created an extension manifest");
        require(!new File(stockOnlyDirectory, ".maanim_attack_forms.pending").exists(),
                "one-attack Save left an empty transaction");
        for (int attack = 2; attack <= 5; attack++)
            require(!new File(stockOnlyDirectory,
                            "maanim_attack" + attack + ".txt").exists(),
                    "one-attack Save created attack" + attack);
        AnimCE stockOnlyReload = animation(stockOnlyPack, "anim");
        AttackAnimationStore.onLoaded(stockOnlyReload);
        require(AttackAnimationStore.extraCount(stockOnlyReload) == 0,
                "one-attack Save reloaded phantom extras");

        String pack = "persist_" + unique();
        AnimCE source = animation(pack, "anim");
        source.anims[2] = namedTimeline("base-persist");
        AttackAnimationStore.ensureForEditor(source);
        AttackAnimationStore.add(source, 0);
        AttackAnimationStore.timeline(source, 1).parts[0].name = "persist-two";
        AttackAnimationStore.onUnsaved(source);
        AttackAnimationStore.add(source, 1);
        AttackAnimationStore.timeline(source, 2).parts[0].name = "persist-three";
        AttackAnimationStore.onUnsaved(source);
        AttackAnimationStore.onSaved(source.id, source);

        File directory = new File(contextRoot, source.id.getPath());
        File attack2 = new File(directory, "maanim_attack2.txt");
        File attack3 = new File(directory, "maanim_attack3.txt");
        require(attack2.isFile() && attack3.isFile(), "extra timeline files were not saved");
        require(!new File(directory, "maanim_attack6.txt").exists(),
                "attack6 file was created");
        require(new String(Files.readAllBytes(attack2.toPath()), StandardCharsets.UTF_8)
                        .contains("persist-two"),
                "attack2 payload was not independent");

        AnimCE reloaded = animation(pack, "anim");
        AttackAnimationStore.onLoaded(reloaded);
        require(AttackAnimationStore.extraCount(reloaded) == 2,
                "saved timelines did not reload");
        require("persist-two".equals(AttackAnimationStore.timeline(reloaded, 1).parts[0].name)
                        && "persist-three".equals(
                        AttackAnimationStore.timeline(reloaded, 2).parts[0].name),
                "reloaded timeline payload changed");

        AttackAnimationStore.remove(reloaded, 1);
        AttackAnimationStore.onUnsaved(reloaded);
        AttackAnimationStore.onSaved(reloaded.id, reloaded);
        require(attack2.isFile() && !attack3.exists(),
                "delete did not remove the obsolete sidecar file");
        require(new String(Files.readAllBytes(attack2.toPath()), StandardCharsets.UTF_8)
                        .contains("persist-three"),
                "delete did not shift the persisted timeline");

        AttackAnimationStore.remove(reloaded, 1);
        AttackAnimationStore.onUnsaved(reloaded);
        AttackAnimationStore.onSaved(reloaded.id, reloaded);
        require(!attack2.exists(), "deleting the final extra kept attack2 on disk");
        require(!new File(directory, "maanim_attack_forms.txt").exists(),
                "deleting the final extra kept an empty manifest");
        require(!new File(directory, ".maanim_attack_forms.pending").exists(),
                "deleting the final extra left a transaction journal");
        for (int attack = 2; attack <= 5; attack++)
            require(!new File(directory, "maanim_attack" + attack + ".txt").exists(),
                    "deleting the final extra kept attack" + attack);
        AnimCE emptyReload = animation(pack, "anim");
        AttackAnimationStore.onLoaded(emptyReload);
        require(AttackAnimationStore.extraCount(emptyReload) == 0,
                "deleted final extra reloaded from disk");

        String brokenPack = "broken_" + unique();
        AnimCE brokenSource = animation(brokenPack, "anim");
        brokenSource.anims[2] = namedTimeline("broken-base");
        AttackAnimationStore.ensureForEditor(brokenSource);
        AttackAnimationStore.add(brokenSource, 0);
        AttackAnimationStore.onUnsaved(brokenSource);
        AttackAnimationStore.add(brokenSource, 1);
        AttackAnimationStore.timeline(brokenSource, 2).parts[0].name = "must-survive";
        AttackAnimationStore.onUnsaved(brokenSource);
        AttackAnimationStore.onSaved(brokenSource.id, brokenSource);
        File brokenDir = new File(contextRoot, brokenSource.id.getPath());
        File broken2 = new File(brokenDir, "maanim_attack2.txt");
        File broken3 = new File(brokenDir, "maanim_attack3.txt");
        byte[] original3 = Files.readAllBytes(broken3.toPath());
        Files.delete(broken2.toPath());

        AnimCE brokenReload = animation(brokenPack, "anim");
        AttackAnimationStore.onLoaded(brokenReload);
        require(AttackAnimationStore.extraCount(brokenReload) == 0,
                "malformed gapped timelines partially attached");
        expectThrows(() -> AttackAnimationStore.onSaved(brokenReload.id, brokenReload),
                "Save overwrote unresolved timeline files");
        require(Arrays.equals(original3, Files.readAllBytes(broken3.toPath())),
                "blocked Save changed unresolved attack3 bytes");
    }

    private static void testLazyResolutionAndMappedCopy() throws Exception {
        String pack = "lazy_" + unique();
        AnimCE seed = animation(pack, "anim");
        seed.anims[2] = namedTimeline("lazy-base");
        AttackAnimationStore.ensureForEditor(seed);
        AttackAnimationStore.add(seed, 0);
        AttackAnimationStore.timeline(seed, 1).parts[0].name = "lazy-two";
        AttackAnimationStore.add(seed, 1);
        AttackAnimationStore.timeline(seed, 2).parts[0].name = "lazy-three";
        AttackAnimationStore.onSaved(seed.id, seed);

        File sourceDirectory = new File(contextRoot, seed.id.getPath());
        byte[] original2 = Files.readAllBytes(
                new File(sourceDirectory, "maanim_attack2.txt").toPath());
        byte[] original3 = Files.readAllBytes(
                new File(sourceDirectory, "maanim_attack3.txt").toPath());

        AnimCE lazy = animation(pack, "anim");
        pushStockHistory(lazy);
        AttackAnimationStore.onUnsaved(lazy);
        require(AttackAnimationStore.extraCount(lazy) == 2,
                "lazy unSave interpreted unknown sidecars as empty");
        AttackAnimationStore.onSaved(lazy.id, lazy);
        require(Arrays.equals(original2, Files.readAllBytes(
                        new File(sourceDirectory, "maanim_attack2.txt").toPath()))
                        && Arrays.equals(original3, Files.readAllBytes(
                        new File(sourceDirectory, "maanim_attack3.txt").toPath())),
                "lazy stock edit changed extra timeline bytes");

        Source.ResourceLocation mapped = new Source.ResourceLocation(
                "mapped_" + unique(), "_mapped_anim", Source.BasePath.ANIM);
        AttackAnimationStore.onSaved(mapped, lazy);
        File mappedDirectory = new File(contextRoot, mapped.getPath());
        require(new File(mappedDirectory, "maanim_attack2.txt").isFile()
                        && new File(mappedDirectory, "maanim_attack3.txt").isFile(),
                "lazy export did not copy extra timelines");

        AnimCI packed = new AnimCI(new Source.SourceAnimLoader(seed.id, null));
        packed.anims = new MaAnim[7];
        for (int i = 0; i < packed.anims.length; i++) packed.anims[i] = new MaAnim();
        packed.types = AnimU.TYPE7;
        MaAnim cachedAttack2 = AttackAnimationStore.runtimeTimeline(packed, 1);
        require(cachedAttack2 != null
                        && "lazy-two".equals(cachedAttack2.parts[0].name),
                "runtime AnimCI resolution returned the wrong attack2");
        require(cachedAttack2 == AttackAnimationStore.runtimeTimeline(packed, 1),
                "runtime AnimCI timeline was reloaded instead of cached");
        require(packed.anims[2] == AttackAnimationStore.runtimeTimeline(packed, 0),
                "runtime slot 0 did not return the stock attack timeline");
        require(AttackAnimationStore.runtimeTimeline(packed, 3) == null,
                "runtime resolution invented a missing attack4");
        require(AttackAnimationStore.runtimeTimeline(lazy, 1)
                        == AttackAnimationStore.timeline(lazy, 1),
                "runtime AnimCE resolution ignored its editable store state");
        expectThrows(() -> AttackAnimationStore.runtimeTimeline(packed, 5),
                "runtime resolution accepted attack6");

        AnimCE target = animation("copy_" + unique(), "target");
        AttackAnimationStore.onCopied(target, packed);
        require(AttackAnimationStore.extraCount(target) == 2,
                "AnimCI-to-AnimCE copy lost extra timelines");
        AttackAnimationStore.timeline(target, 1).parts[0].name = "copy-independent";
        require("lazy-two".equals(AttackAnimationStore.timeline(lazy, 1).parts[0].name),
                "localized copy shares attack2 with its source");

        AttackAnimationStore.timeline(lazy, 1).parts[0].name = "lazy-two-v2";
        AttackAnimationStore.remove(lazy, 2);
        AttackAnimationStore.onSaved(lazy.id, lazy);
        MaAnim refreshedAttack2 = AttackAnimationStore.runtimeTimeline(packed, 1);
        require(refreshedAttack2 != cachedAttack2
                        && "lazy-two-v2".equals(refreshedAttack2.parts[0].name),
                "committed Save did not refresh the runtime timeline cache");
        require(AttackAnimationStore.runtimeTimeline(packed, 2) == null,
                "committed Delete kept a stale runtime timeline");
    }

    private static void testTransactionRecoveryAndParserLimits() throws Exception {
        String[] faultSteps = {"after-attack2", "after-attack3",
                "after-delete-attack4", "after-delete-attack5", "after-manifest"};
        for (String faultStep : faultSteps) {
            String pack = "txn_" + faultStep.replace('-', '_') + "_" + unique();
            AnimCE animation = animation(pack, "anim");
            animation.anims[2] = namedTimeline("txn-base");
            AttackAnimationStore.ensureForEditor(animation);
            for (int slot = 1; slot <= 4; slot++) {
                AttackAnimationStore.add(animation, slot == 1 ? 0 : slot - 1);
                AttackAnimationStore.timeline(animation, slot).parts[0].name =
                        "old-attack" + (slot + 1);
            }
            AttackAnimationStore.onSaved(animation.id, animation);
            AttackAnimationStore.remove(animation, 4);
            AttackAnimationStore.remove(animation, 3);
            AttackAnimationStore.timeline(animation, 1).parts[0].name = "new-attack2";
            AttackAnimationStore.timeline(animation, 2).parts[0].name = "new-attack3";

            System.setProperty("manualcontrol.attackform.animator.failStep", faultStep);
            try {
                expectThrows(() -> AttackAnimationStore.onSaved(animation.id, animation),
                        "fault injection did not interrupt " + faultStep);
            } finally {
                System.clearProperty("manualcontrol.attackform.animator.failStep");
            }

            AnimCE recovered = animation(pack, "anim");
            AttackAnimationStore.onLoaded(recovered);
            require(AttackAnimationStore.extraCount(recovered) == 2,
                    "transaction recovery produced a mixed count at " + faultStep);
            require("new-attack2".equals(
                            AttackAnimationStore.timeline(recovered, 1).parts[0].name)
                            && "new-attack3".equals(
                            AttackAnimationStore.timeline(recovered, 2).parts[0].name),
                    "transaction recovery mixed generations at " + faultStep);
            File directory = new File(contextRoot, recovered.id.getPath());
            require(!new File(directory, "maanim_attack4.txt").exists()
                            && !new File(directory, "maanim_attack5.txt").exists()
                            && !new File(directory, ".maanim_attack_forms.pending").exists(),
                    "transaction recovery left obsolete files at " + faultStep);
        }

        String[] emptyFaultSteps = {"after-delete-attack2", "after-delete-attack3",
                "after-delete-attack4", "after-delete-attack5", "after-manifest"};
        for (String faultStep : emptyFaultSteps) {
            String pack = "txn_empty_" + faultStep.replace('-', '_') + "_" + unique();
            AnimCE animation = animation(pack, "anim");
            AttackAnimationStore.ensureForEditor(animation);
            for (int slot = 1; slot <= 4; slot++)
                AttackAnimationStore.add(animation, slot == 1 ? 0 : slot - 1);
            AttackAnimationStore.onSaved(animation.id, animation);
            for (int slot = 4; slot >= 1; slot--)
                AttackAnimationStore.remove(animation, slot);

            System.setProperty("manualcontrol.attackform.animator.failStep", faultStep);
            try {
                expectThrows(() -> AttackAnimationStore.onSaved(animation.id, animation),
                        "empty-generation fault did not interrupt " + faultStep);
            } finally {
                System.clearProperty("manualcontrol.attackform.animator.failStep");
            }
            require(!animation.isSaved(),
                    "empty-generation commit failure kept saved=true at " + faultStep);

            AnimCE recovered = animation(pack, "anim");
            AttackAnimationStore.onLoaded(recovered);
            require(AttackAnimationStore.extraCount(recovered) == 0,
                    "empty-generation recovery restored extras at " + faultStep);
            File directory = new File(contextRoot, recovered.id.getPath());
            for (int attack = 2; attack <= 5; attack++)
                require(!new File(directory, "maanim_attack" + attack + ".txt").exists(),
                        "empty recovery kept attack" + attack + " at " + faultStep);
            require(!new File(directory, "maanim_attack_forms.txt").exists()
                            && !new File(directory,
                            ".maanim_attack_forms.pending").exists(),
                    "empty recovery kept generation metadata at " + faultStep);
            AttackAnimationStore.onSaved(recovered.id, recovered);
        }

        String abortPack = "abort_" + unique();
        AnimCE abort = animation(abortPack, "anim");
        abort.anims[2] = namedTimeline("abort-base");
        AttackAnimationStore.ensureForEditor(abort);
        AttackAnimationStore.add(abort, 0);
        AttackAnimationStore.timeline(abort, 1).parts[0].name = "old-abort";
        AttackAnimationStore.onSaved(abort.id, abort);
        File abortDirectory = new File(contextRoot, abort.id.getPath());
        File abort2 = new File(abortDirectory, "maanim_attack2.txt");
        byte[] committed = Files.readAllBytes(abort2.toPath());
        AttackAnimationStore.timeline(abort, 1).parts[0].name = "must-not-commit";
        AttackAnimationStore.beforeSave(abort.id, abort);
        require(Arrays.equals(committed, Files.readAllBytes(abort2.toPath())),
                "staging touched committed attack2 before stock Save");
        AttackAnimationStore.onSaveFailed(abort.id, abort);
        require(Arrays.equals(committed, Files.readAllBytes(abort2.toPath()))
                        && !new File(abortDirectory,
                        ".maanim_attack_forms.pending").exists(),
                "stock-save failure did not abort the uncommitted stage");
        require(!abort.isSaved(), "stock-save failure kept saved=true");
        AttackAnimationStore.onSaved(abort.id, abort);
        require(new String(Files.readAllBytes(abort2.toPath()), StandardCharsets.UTF_8)
                        .contains("must-not-commit"),
                "retry after stock-save failure did not acquire and commit");
        AnimCE abortReload = animation(abortPack, "anim");
        AttackAnimationStore.onLoaded(abortReload);
        require("must-not-commit".equals(
                        AttackAnimationStore.timeline(abortReload, 1).parts[0].name),
                "retry after stock-save failure did not reload");

        String hugePack = "huge_" + unique();
        AnimCE huge = animation(hugePack, "anim");
        File hugeDirectory = new File(contextRoot, huge.id.getPath());
        require(hugeDirectory.mkdirs(), "could not create huge-count fixture");
        File huge2 = new File(hugeDirectory, "maanim_attack2.txt");
        byte[] hugePayload = "[maanim]\n1\n2147483647\n"
                .getBytes(StandardCharsets.UTF_8);
        Files.write(huge2.toPath(), hugePayload);
        AttackAnimationStore.onLoaded(huge);
        require(AttackAnimationStore.extraCount(huge) == 0,
                "huge count partially attached a timeline");
        expectThrows(() -> AttackAnimationStore.onSaved(huge.id, huge),
                "huge count was allowed to overwrite its source");
        require(Arrays.equals(hugePayload, Files.readAllBytes(huge2.toPath())),
                "blocked huge-count Save changed source bytes");

        String[] invalidNames = {"maanim_attack1.txt", "maanim_attack6.txt",
                "maanim_attack7.txt", "maanim_attack02.txt",
                "maanim_attack99999999999999999999.txt",
                "maanim_attack_backup.txt"};
        for (String invalidName : invalidNames) {
            String invalidPack = "invalid_name_" + unique();
            AnimCE seed = animation(invalidPack, "anim");
            AttackAnimationStore.ensureForEditor(seed);
            AttackAnimationStore.add(seed, 0);
            AttackAnimationStore.onSaved(seed.id, seed);
            File invalidDirectory = new File(contextRoot, seed.id.getPath());
            File valid2 = new File(invalidDirectory, "maanim_attack2.txt");
            byte[] validBytes = Files.readAllBytes(valid2.toPath());
            File invalid = new File(invalidDirectory, invalidName);
            Files.copy(valid2.toPath(), invalid.toPath());
            byte[] invalidBytes = Files.readAllBytes(invalid.toPath());
            AnimCE reload = animation(invalidPack, "anim");
            AttackAnimationStore.onLoaded(reload);
            require(AttackAnimationStore.extraCount(reload) == 0,
                    invalidName + " was silently ignored");
            expectThrows(() -> AttackAnimationStore.onSaved(reload.id, reload),
                    invalidName + " did not block destructive Save");
            require(Arrays.equals(validBytes, Files.readAllBytes(valid2.toPath()))
                            && Arrays.equals(invalidBytes,
                            Files.readAllBytes(invalid.toPath())),
                    "blocked Save changed bytes for " + invalidName);
        }
    }

    private static void testBoundedUndoHistory() throws Exception {
        AnimCE animation = animation("history_" + unique(), "anim");
        animation.anims[2] = namedTimeline("history-base");
        pushStockHistory(animation);
        AttackAnimationStore.ensureForEditor(animation);
        AttackAnimationStore.add(animation, 0);
        coreUnsave(animation, "history add");
        for (int edit = 0; edit < 100; edit++) {
            AttackAnimationStore.timeline(animation, 1).parts[0].name = "edit-" + edit;
            coreUnsave(animation, "history edit");
        }
        require(animation.history.size() == 20, "stock history was not capped at 20");
        Field statesField = AttackAnimationStore.class.getDeclaredField("STATES");
        statesField.setAccessible(true);
        Object state = ((java.util.Map<?, ?>) statesField.get(null)).get(animation);
        Field historyField = state.getClass().getDeclaredField("history");
        historyField.setAccessible(true);
        require(((java.util.List<?>) historyField.get(state)).size() == 20,
                "custom history was not capped/aligned at 20");
        for (int undo = 0; undo < 19; undo++) coreRestore(animation);
        require("edit-80".equals(AttackAnimationStore.timeline(
                        animation, 1).parts[0].name),
                "bounded custom Undo restored the wrong surviving snapshot");
    }

    private static void verifyOfflineTransform(File jarFile) throws Exception {
        require(jarFile.isFile(), "BCU jar missing: " + jarFile);
        URLClassLoader loader = new URLClassLoader(new URL[]{jarFile.toURI().toURL()}, null);
        try {
            ready(loader);
            AttackFormAnimatorTransformer transformer = new AttackFormAnimatorTransformer();
            verifyTarget(jarFile, loader, transformer, "page/anim/MaAnimEditPage",
                    new String[]{"onBuilt", "onResized", "beforeSetAnimation",
                            "afterSetAnimation", "selectAnimation", "callBack",
                            "onLdMousePressed", "onLdMouseDragged", "onLdMouseReleased"},
                    new int[]{2, 1, 1, 1, 1, 1, 1, 1, 1});
            verifyTarget(jarFile, loader, transformer, "common/util/anim/AnimCE",
                    new String[]{"onLoaded", "onUnsaved", "onRestored", "onCopied"},
                    new int[]{1, 1, 1, 1});
            verifyTarget(jarFile, loader, transformer,
                    "common/pack/Source$SourceAnimSaver",
                    new String[]{"beforeSave", "onSaveFailed", "onSaveAborted", "afterSave"},
                    new int[]{1, 1, 1, 1});
            AttackFormPreviewTransformer preview = new AttackFormPreviewTransformer();
            verifyPreviewTarget(jarFile, loader, preview, "page/anim/BufferedAnim",
                    "onBufferedPreviewDraw");
            verifyPreviewTarget(jarFile, loader, preview, "page/anim/GLAnimBox",
                    "onFakePreviewDraw");
        } finally {
            loader.close();
        }
    }

    private static void verifyTarget(File jarFile, ClassLoader loader,
                                     AttackFormAnimatorTransformer transformer,
                                     String target, String[] hooks, int[] expected) throws Exception {
        byte[] source = readClass(jarFile, target);
        byte[] patched = transformer.transform(loader, target, null, null, source);
        require(patched != null, "Part 2 target was not transformed: " + target);
        for (int i = 0; i < hooks.length; i++)
            require(countHook(patched, hooks[i]) == expected[i],
                    target + " hook count mismatch for " + hooks[i]);
        require(transformer.transform(loader, target, null, null, patched) == null,
                target + " transform was not idempotent");
    }

    private static void verifyPreviewTarget(File jarFile, ClassLoader loader,
                                            AttackFormPreviewTransformer transformer,
                                            String target, String hook) throws Exception {
        byte[] source = readClass(jarFile, target);
        byte[] patched = transformer.transform(loader, target, null, null, source);
        require(patched != null, "LD preview target was not transformed: " + target);
        require(countHook(patched, hook) == 1,
                target + " LD preview hook count mismatch");
        require(transformer.transform(loader, target, null, null, patched) == null,
                target + " LD preview transform was not idempotent");
    }

    private static int countHook(byte[] bytes, final String hook) {
        final int[] count = {0};
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name,
                                                String descriptor, boolean isInterface) {
                        if (("manualcontrol/attackform/animator/AttackFormAnimatorHooks".equals(owner)
                                || "manualcontrol/attackform/animator/AttackAnimationStore".equals(owner)
                                || "manualcontrol/attackform/animator/AttackLdEditor".equals(owner))
                                && hook.equals(name)) count[0]++;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return count[0];
    }

    private static AnimCE animation(String pack, String id) throws Exception {
        Source.ResourceLocation location = new Source.ResourceLocation(pack, id,
                Source.BasePath.ANIM);
        AnimCE animation = new AnimCE(new Source.SourceAnimLoader(location, null));
        animation.imgcut = new ImgCut();
        animation.mamodel = new MaModel();
        animation.anims = new MaAnim[7];
        for (int i = 0; i < animation.anims.length; i++) animation.anims[i] = new MaAnim();
        animation.types = AnimU.TYPE7;
        return animation;
    }

    private static MaAnim namedTimeline(String name) {
        MaAnim timeline = new MaAnim();
        timeline.n = 1;
        timeline.parts = new Part[]{new Part(0, 5)};
        timeline.parts[0].name = name;
        timeline.validate();
        return timeline;
    }

    private static void ready(ClassLoader loader) throws Exception {
        FeatureFlags.switchAttackForm = true;
        AttackFormFeature.resetForTests();
        require(AttackFormFeature.initialize(loader) == AttackFormFeature.State.READY,
                "Part 2 compatibility gate was not READY");
    }

    private static byte[] readClass(File jarFile, String internalName) throws Exception {
        JarFile jar = new JarFile(jarFile);
        try {
            JarEntry entry = jar.getJarEntry(internalName + ".class");
            require(entry != null, "class missing: " + internalName);
            InputStream input = jar.getInputStream(entry);
            try {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                for (int read; (read = input.read(buffer)) >= 0; ) output.write(buffer, 0, read);
                return output.toByteArray();
            } finally {
                input.close();
            }
        } finally {
            jar.close();
        }
    }

    private static void ensureTestContext(final String suffix) {
        contextRoot = new File("build-next/attack-form-part2-context-" + suffix)
                .getAbsoluteFile();
        contextRoot.mkdirs();
        CommonStatic.ctx = (Context) Proxy.newProxyInstance(
                AttackFormPart2Test.class.getClassLoader(), new Class[]{Context.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        String name = method.getName();
                        if ("noticeErr".equals(name) && args != null && args.length > 0) {
                            if (args[0] instanceof Context.SupExc)
                                return ((Context.SupExc<?>) args[0]).get();
                            if (args[0] instanceof Context.RunExc) {
                                ((Context.RunExc) args[0]).run();
                                return true;
                            }
                            return null;
                        }
                        if ("getWorkspaceFile".equals(name) || "getAuxFile".equals(name)
                                || "getAssetFile".equals(name) || "getUserFile".equals(name)
                                || "getBackupFile".equals(name))
                            return new File(contextRoot, args == null || args.length == 0
                                    ? "." : String.valueOf(args[0]));
                        if ("getBCUFolder".equals(name)) return contextRoot;
                        if ("getAuthor".equals(name)) return "test";
                        Class<?> type = method.getReturnType();
                        if (type == Boolean.TYPE) return false;
                        if (type == Integer.TYPE) return 0;
                        if (type == Long.TYPE) return 0L;
                        if (type == Float.TYPE) return 0.0f;
                        if (type == Double.TYPE) return 0.0d;
                        return null;
                    }
                });
    }

    private static String unique() {
        return Long.toHexString(System.nanoTime());
    }

    private static void expectThrows(ThrowingRunnable runnable, String message) {
        try {
            runnable.run();
            throw new AssertionError(message);
        } catch (AssertionError error) {
            throw error;
        } catch (Throwable expected) {
            checks++;
        }
    }

    private static void coreUnsave(AnimCE animation, String reason) {
        pushStockHistory(animation);
        AttackAnimationStore.onUnsaved(animation);
    }

    private static void coreRestore(AnimCE animation) {
        if (animation.history != null && animation.history.size() > 1)
            animation.history.pop();
        AttackAnimationStore.onRestored(animation);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void pushStockHistory(AnimCE animation) {
        ((java.util.Stack) animation.history).push(new Object());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    public static final class AgentAnimatorSmoke {
        public static void main(String[] args) throws Exception {
            if (AttackFormFeature.getState() != AttackFormFeature.State.READY)
                throw new AssertionError("feature state=" + AttackFormFeature.getState());
            ensureTestContext("agent-" + BcuBuildInfo.detect(
                    AgentAnimatorSmoke.class.getClassLoader()).versionCode + "-" + unique());
            Class.forName("page.anim.MaAnimEditPage", false,
                    AgentAnimatorSmoke.class.getClassLoader());
            Class.forName("page.anim.BufferedAnim", false,
                    AgentAnimatorSmoke.class.getClassLoader());
            Class.forName("page.anim.GLAnimBox", false,
                    AgentAnimatorSmoke.class.getClassLoader());
            Class<?> animBox = Class.forName("page.anim.AnimBox", false,
                    AgentAnimatorSmoke.class.getClassLoader());
            Class<?> previewAccess = Class.forName("page.anim.AttackLdPreviewAccess", true,
                    AgentAnimatorSmoke.class.getClassLoader());
            if (previewAccess.getMethod("pan").invoke(null) == null)
                throw new AssertionError("Animator LD preview package bridge has no origin");
            Object previewProxy = java.lang.reflect.Proxy.newProxyInstance(
                    AgentAnimatorSmoke.class.getClassLoader(), new Class<?>[]{animBox},
                    (proxy, method, values) -> "getSiz".equals(method.getName()) ? 2.5f : null);
            Number bridgedZoom = (Number) previewAccess.getMethod("zoom", Object.class)
                    .invoke(null, previewProxy);
            if (Math.abs(bridgedZoom.floatValue() - 2.5f) > 0.0001f)
                throw new AssertionError("Animator LD preview package bridge lost zoom");
            page.anim.AttackLdDragTest.run();

            AnimCE animation = animation("agent_" + unique(), "anim");
            setBoolean(animation, "partial", true);
            setBoolean(animation, "loaded", true);
            AttackAnimationStore.ensureForEditor(animation);
            AttackAnimationStore.add(animation, 0);
            animation.unSave("part2 smoke attack2");
            AttackAnimationStore.add(animation, 1);
            animation.unSave("part2 smoke attack3");
            if (AttackAnimationStore.extraCount(animation) != 2)
                throw new AssertionError("unSave hook did not align custom history");

            if (animation.history != null && animation.history.size() > 1)
                animation.history.pop();
            AttackAnimationStore.onRestored(animation);
            if (AttackAnimationStore.extraCount(animation) != 1)
                throw new AssertionError("unSave hooks did not align custom Undo history");

            new Source.SourceAnimSaver(animation.id, animation).saveData();
            File persisted = new File(contextRoot,
                    animation.id.getPath() + "/maanim_attack2.txt");
            if (!persisted.isFile())
                throw new AssertionError("SourceAnimSaver hook did not persist attack2");

            AnimCE stockOnly = animation("agent_stock_only_" + unique(), "anim");
            setBoolean(stockOnly, "partial", true);
            setBoolean(stockOnly, "loaded", true);
            AttackAnimationStore.ensureForEditor(stockOnly);
            new Source.SourceAnimSaver(stockOnly.id, stockOnly).saveData();
            File stockOnlyDirectory = new File(contextRoot, stockOnly.id.getPath());
            setBoolean(stockOnly, "saved", true);
            File stockOnlyImgCut = new File(stockOnlyDirectory, "imgcut.txt");
            Files.delete(stockOnlyImgCut.toPath());
            Files.createDirectory(stockOnlyImgCut.toPath());
            new Source.SourceAnimSaver(stockOnly.id, stockOnly).saveData();
            if (stockOnly.isSaved())
                throw new AssertionError("stock-only IOException kept saved=true");
            if (new File(stockOnlyDirectory, "maanim_attack_forms.txt").exists()
                    || new File(stockOnlyDirectory,
                    ".maanim_attack_forms.pending").exists())
                throw new AssertionError("stock-only Save created extension metadata");
            for (int attack = 2; attack <= 5; attack++)
                if (new File(stockOnlyDirectory,
                        "maanim_attack" + attack + ".txt").exists())
                    throw new AssertionError("stock-only Save created attack" + attack);

            AnimCE escaping = animation("agent_escape_" + unique(), "anim");
            setBoolean(escaping, "partial", true);
            setBoolean(escaping, "loaded", true);
            AttackAnimationStore.ensureForEditor(escaping);
            AttackAnimationStore.add(escaping, 0);
            AttackAnimationStore.timeline(escaping, 1).parts =
                    namedTimeline("old-agent-escape").parts;
            AttackAnimationStore.timeline(escaping, 1).n = 1;
            escaping.unSave("agent unchecked baseline");
            new Source.SourceAnimSaver(escaping.id, escaping).saveData();
            File escapingDirectory = new File(contextRoot, escaping.id.getPath());
            File escapingAttack2 = new File(escapingDirectory, "maanim_attack2.txt");
            byte[] beforeEscape = Files.readAllBytes(escapingAttack2.toPath());
            AttackAnimationStore.timeline(escaping, 1).parts[0].name =
                    "retry-after-unchecked";
            escaping.unSave("agent unchecked failure");
            setBoolean(escaping, "saved", true);
            ImgCut escapingImgCut = escaping.imgcut;
            escaping.imgcut = null;
            boolean escaped = false;
            try {
                new Source.SourceAnimSaver(escaping.id, escaping).saveData();
            } catch (NullPointerException expected) {
                escaped = true;
            } finally {
                escaping.imgcut = escapingImgCut;
            }
            if (!escaped)
                throw new AssertionError("unchecked stock failure was swallowed");
            if (!Arrays.equals(beforeEscape, Files.readAllBytes(escapingAttack2.toPath())))
                throw new AssertionError("unchecked stock failure committed attack2");
            if (new File(escapingDirectory, ".maanim_attack_forms.pending").exists())
                throw new AssertionError("unchecked stock failure left a transaction");
            if (escaping.isSaved())
                throw new AssertionError("unchecked stock failure kept saved=true");
            new Source.SourceAnimSaver(escaping.id, escaping).saveData();
            if (!new String(Files.readAllBytes(escapingAttack2.toPath()),
                    StandardCharsets.UTF_8).contains("retry-after-unchecked"))
                throw new AssertionError("retry after unchecked failure did not commit");

            AnimCE failing = animation("agent_fail_" + unique(), "anim");
            setBoolean(failing, "partial", true);
            setBoolean(failing, "loaded", true);
            AttackAnimationStore.ensureForEditor(failing);
            AttackAnimationStore.add(failing, 0);
            AttackAnimationStore.timeline(failing, 1).parts =
                    namedTimeline("old-agent-failure").parts;
            AttackAnimationStore.timeline(failing, 1).n = 1;
            failing.unSave("agent failure baseline");
            new Source.SourceAnimSaver(failing.id, failing).saveData();
            File failingDirectory = new File(contextRoot, failing.id.getPath());
            File failingAttack2 = new File(failingDirectory, "maanim_attack2.txt");
            byte[] beforeFailure = Files.readAllBytes(failingAttack2.toPath());
            AttackAnimationStore.timeline(failing, 1).parts[0].name = "must-not-commit";
            failing.unSave("agent injected stock failure");
            setBoolean(failing, "saved", true);
            File stockImgCut = new File(failingDirectory, "imgcut.txt");
            Files.delete(stockImgCut.toPath());
            Files.createDirectory(stockImgCut.toPath());
            new Source.SourceAnimSaver(failing.id, failing).saveData();
            if (!Arrays.equals(beforeFailure, Files.readAllBytes(failingAttack2.toPath())))
                throw new AssertionError("stock IOException committed new attack2 bytes");
            if (new File(failingDirectory, ".maanim_attack_forms.pending").exists())
                throw new AssertionError("stock IOException left an unready transaction");
            if (failing.isSaved())
                throw new AssertionError("stock IOException did not restore saved=false");
            System.out.println("[AttackFormAgentAnimatorSmoke] PASS ver="
                    + BcuBuildInfo.detect(AgentAnimatorSmoke.class.getClassLoader()).versionCode);
        }

        private static void setBoolean(Object value, String name, boolean state) throws Exception {
            Class<?> type = value.getClass();
            while (type != null) {
                try {
                    Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    field.setBoolean(value, state);
                    return;
                } catch (NoSuchFieldException ignored) {
                    type = type.getSuperclass();
                }
            }
            throw new NoSuchFieldException(name);
        }
    }

    public static final class DisabledAnimatorSmoke {
        public static void main(String[] args) throws Exception {
            if (AttackFormFeature.getState() != AttackFormFeature.State.DISABLED)
                throw new AssertionError("feature state=" + AttackFormFeature.getState());
            Class.forName("page.anim.MaAnimEditPage", false,
                    DisabledAnimatorSmoke.class.getClassLoader());
            System.out.println("[AttackFormDisabledAnimatorSmoke] PASS");
        }
    }
}
