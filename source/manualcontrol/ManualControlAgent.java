package manualcontrol;

import manualcontrol.transform.BBPainterTransformer;
import manualcontrol.transform.BattleInfoPageTransformer;
import manualcontrol.transform.KeyHandlerTransformer;
import manualcontrol.transform.EntityTransformer;
import manualcontrol.transform.SniperTransformer;
import manualcontrol.transform.StageBasisTransformer;
import manualcontrol.transform.CannonTransformer;
import manualcontrol.transform.AttackSimpleTransformer;
import manualcontrol.transform.AttackWaveTransformer;
import manualcontrol.transform.AtkManagerTransformer;
import manualcontrol.transform.FormEditPageTransformer;
import manualcontrol.transform.UnitManagePageTransformer;
import manualcontrol.transform.AtkModelEntityTransformer;
import manualcontrol.transform.AtkModelUnitTransformer;
import manualcontrol.transform.EntityDamageTransformer;
import manualcontrol.transform.PackNoticeTransformer;
import manualcontrol.transform.MainPageTransformer;
import manualcontrol.transform.BattleFieldTransformer;
import manualcontrol.transform.EAnimDTransformer;
import manualcontrol.transform.ConfigPageTransformer;
import manualcontrol.transform.AnimSpeedScaleTransformer;
import manualcontrol.transform.MaAnimEditPageTransformer;
import manualcontrol.transform.GLAnimBoxTransformer;
import manualcontrol.transform.AnimCETransformer;
import manualcontrol.transform.BackgroundTransformer;
import manualcontrol.transform.BGViewPageTransformer;
import manualcontrol.transform.UtilPCTransformer;
import manualcontrol.newproc.transform.DataProcTransformer;
import manualcontrol.newproc.transform.EditorsTransformer;
import manualcontrol.newproc.transform.EntityNewProcTransformer;
import manualcontrol.newproc.transform.InterpretNewProcTransformer;
import manualcontrol.newproc.transform.ProcIconTransformer;
import manualcontrol.newproc.transform.ProcLangTransformer;
import manualcontrol.newproc.transform.ProcTableTransformer;
import manualcontrol.newproc.transform.SwingEditorTransformer;
import manualcontrol.newproc.transform.CustomEntitySummonTransformer;
import manualcontrol.crazy.CrazyPreloadService;
import manualcontrol.fps.FpsHooks;
import manualcontrol.attackform.AttackFormFeature;
import manualcontrol.attackform.AttackFormLog;
import manualcontrol.attackform.transform.AttackFormPackTransformer;
import manualcontrol.attackform.transform.AttackFormAnimatorTransformer;
import manualcontrol.attackform.transform.AttackFormPreviewTransformer;
import manualcontrol.attackform.transform.AttackFormEditorTransformer;
import manualcontrol.attackform.transform.CustomEntityAttackFormTransformer;
import manualcontrol.attackform.transform.AttackFormRuntimeTransformer;

import java.lang.instrument.Instrumentation;

public class ManualControlAgent {

    public static final String VERSION = "0.15.2";

    public static void premain(String args, Instrumentation inst) {
        StartupProfile.agentStarted();
        banner();
        Logger.log("Manual Control + Manual Sniper patch");
        Logger.log("Agent version: " + VERSION);
        Logger.log("JVM: " + System.getProperty("java.version"));
        Logger.log("user.dir: " + System.getProperty("user.dir"));

        if (inst == null) {
            Logger.err("FATAL: Instrumentation API not available", null);
            return;
        }

        FeatureSelectDialog.prompt();
        StartupProfile.featureSelectDone();

        AttackFormFeature.initialize(ManualControlAgent.class.getClassLoader());
        final boolean crazy = FeatureFlags.crazy;
        final boolean adventure = FeatureFlags.adventure;
        final boolean hybrid = FeatureFlags.hybrid;
        final boolean directEdit = FeatureFlags.directEdit;

        if (AttackFormFeature.isReady() && attackFormClassesAlreadyLoaded(inst))
            AttackFormFeature.disable("required attack-form class was already loaded", null);

        if (AttackFormFeature.isReady()) {
            try {

                inst.addTransformer(new CustomEntityAttackFormTransformer(), false);
                inst.addTransformer(new AttackFormPackTransformer(), false);
                inst.addTransformer(new AttackFormAnimatorTransformer(), false);
                inst.addTransformer(new AttackFormPreviewTransformer(), false);
                inst.addTransformer(new AttackFormEditorTransformer(), false);
                inst.addTransformer(new AttackFormRuntimeTransformer(), false);
                AttackFormLog.info("Part 1 data, Part 2 Animator, Part 3 Editor, and Part 4 battle transformers registered");
            } catch (Throwable error) {
                AttackFormFeature.disable("attack-form transformer registration", error);
            }
        }

        try {
            if (FeatureFlags.newProc) {
                inst.addTransformer(new DataProcTransformer(), true);
                inst.addTransformer(new ProcLangTransformer(), true);
                inst.addTransformer(new EditorsTransformer(), true);
                inst.addTransformer(new ProcTableTransformer(), true);
                inst.addTransformer(new ProcIconTransformer(), true);

                inst.addTransformer(new EntityNewProcTransformer(), false);
                inst.addTransformer(new InterpretNewProcTransformer(), true);
                inst.addTransformer(new SwingEditorTransformer(), true);
                inst.addTransformer(new CustomEntitySummonTransformer(), true);
            }
            if (FeatureFlags.customMaps) {
                inst.addTransformer(new BackgroundTransformer(), true);
                inst.addTransformer(new BGViewPageTransformer(), true);
                inst.addTransformer(new UtilPCTransformer(), true);
            }
            if (crazy) {
                inst.addTransformer(new BBPainterTransformer(), true);
                inst.addTransformer(new BattleInfoPageTransformer(), true);
                inst.addTransformer(new KeyHandlerTransformer(), true);
                inst.addTransformer(new EntityTransformer(), true);
                inst.addTransformer(new SniperTransformer(), true);
                inst.addTransformer(new StageBasisTransformer(), true);
                inst.addTransformer(new CannonTransformer(), true);
                inst.addTransformer(new AttackSimpleTransformer(), true);
                inst.addTransformer(new AttackWaveTransformer(), true);
                inst.addTransformer(new AtkManagerTransformer(), true);
                inst.addTransformer(new AtkModelEntityTransformer(), true);
                inst.addTransformer(new AtkModelUnitTransformer(), true);
                inst.addTransformer(new EntityDamageTransformer(), true);
                inst.addTransformer(new PackNoticeTransformer(), true);
                inst.addTransformer(new BattleFieldTransformer(), true);
                inst.addTransformer(new EAnimDTransformer(), true);
                inst.addTransformer(new ConfigPageTransformer(), true);
                inst.addTransformer(new FormEditPageTransformer(), true);
                inst.addTransformer(new UnitManagePageTransformer(), true);
            }

            if (crazy || adventure || hybrid
                    || FeatureFlags.miniModes || FeatureFlags.customMaps
                    || FeatureFlags.arena) {
                inst.addTransformer(new MainPageTransformer(), true);
            }

            if (directEdit) {
                inst.addTransformer(new MaAnimEditPageTransformer(), true);
                inst.addTransformer(new GLAnimBoxTransformer(), true);
                inst.addTransformer(new AnimCETransformer(), true);
            }

            if (FeatureFlags.speedScale) {
                inst.addTransformer(new AnimSpeedScaleTransformer(), true);
            }
            Logger.log("transformers registered (crazy=" + crazy
                    + " adventure=" + adventure + " hybrid=" + hybrid
                    + " directEdit=" + directEdit
                    + " newProc=" + FeatureFlags.newProc
                    + " switchAttackForm=" + FeatureFlags.switchAttackForm
                    + " attackFormState=" + AttackFormFeature.getState() + ")");
            StartupProfile.transformersRegistered();
            if (crazy) {
                ControlTickLoop.start();
                FpsHooks.init();
                CrazyPreloadService.registerDefaultConsumers();
                Logger.log("Crazy preload deferred until the main page is up");
            }
            Logger.log("isRetransformClassesSupported: " + inst.isRetransformClassesSupported());

            Class<?>[] loaded = inst.getAllLoadedClasses();
            Logger.log("Pre-existing loaded classes: " + loaded.length);

            Class<?> loadedBackground = null;
            Class<?> loadedBackgroundPage = null;
            Class<?> loadedUtilPC = null;
            for (Class<?> c : loaded) {
                String n = c.getName();
                if ("common.util.pack.Background".equals(n)) loadedBackground = c;
                if ("page.view.BGViewPage".equals(n)) loadedBackgroundPage = c;
                if ("utilpc.UtilPC".equals(n)) loadedUtilPC = c;
                if (n.startsWith("page.battle.") || n.startsWith("page.awt.") || n.startsWith("common.battle.")) {
                    Logger.log("Pre-existing BCU class: " + n);
                }
            }
            if (FeatureFlags.customMaps && loadedBackground != null
                    && inst.isModifiableClass(loadedBackground)) {
                inst.retransformClasses(loadedBackground);
                Logger.log("Retransformed pre-loaded Background for Custom Stage labels");
            }
            if (FeatureFlags.customMaps && loadedBackgroundPage != null
                    && inst.isModifiableClass(loadedBackgroundPage)) {
                inst.retransformClasses(loadedBackgroundPage);
                Logger.log("Retransformed pre-loaded BGViewPage for Custom Stage catalog");
            }
            if (FeatureFlags.customMaps && loadedUtilPC != null
                    && inst.isModifiableClass(loadedUtilPC)) {
                inst.retransformClasses(loadedUtilPC);
                Logger.log("Retransformed pre-loaded UtilPC for Custom Stage previews");
            }

            if (crazy) {
            Logger.log("Monitor thread starting - output also to file: " + Logger.getLogFile().getAbsolutePath());

            Thread bgChecker = new Thread(() -> {
                int iter = 0;
                boolean patched = false;
                java.util.Set<String> previouslySeen = new java.util.HashSet<>();
                while (iter < 600) {
                    iter++;
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ignored) { break; }
                    try {
                        Class<?>[] now = inst.getAllLoadedClasses();
                        Class<?> target = null;
                        java.util.List<String> newBattleClasses = new java.util.ArrayList<>();
                        for (Class<?> c : now) {
                            String n = c.getName();
                            boolean relevant = n.startsWith("page.battle.")
                                    || n.startsWith("page.awt.")
                                    || n.startsWith("jogl.")
                                    || n.startsWith("common.battle.")
                                    || n.contains("BattleBox")
                                    || n.contains("BBPainter")
                                    || n.contains("BBCtrl")
                                    || n.contains("BBRecd")
                                    || n.contains("BattleInfoPage")
                                    || n.contains("Sniper");
                            if (relevant && !previouslySeen.contains(n)) {
                                newBattleClasses.add(n);
                                previouslySeen.add(n);
                            }
                            if ("page.battle.BattleBox$BBPainter".equals(n)) target = c;
                        }

                        Class<?> infoPage = null;
                        for (Class<?> c : now) {
                            if ("page.battle.BattleInfoPage".equals(c.getName())) {
                                infoPage = c;
                                break;
                            }
                        }
                        Logger.log("[check #" + iter + "] loaded=" + now.length
                                + " transforms=" + BBPainterTransformer.transformCallCount
                                + " BBPainter=" + (target != null ? "YES" : "no")
                                + " InfoPage=" + (infoPage != null ? "YES" : "no")
                                + " newBCU=" + newBattleClasses.size());
                        if (!newBattleClasses.isEmpty()) {
                            for (String s : newBattleClasses) {
                                Logger.log("   NEW: " + s);
                            }
                        }

                        if (infoPage != null && !patched) {
                            Logger.log("Triggering retransform on BattleInfoPage...");
                            try {
                                inst.retransformClasses(infoPage);
                                Logger.log("*** BattleInfoPage retransform COMPLETED ***");
                                patched = true;
                            } catch (Throwable rt) {
                                Logger.err("Retransform BattleInfoPage failed", rt);
                            }
                        }

                    } catch (Throwable t) {
                        Logger.err("Monitor error", t);
                    }
                }
            }, "ManualControl-Monitor");
            bgChecker.setDaemon(true);
            bgChecker.start();
            }
        } catch (Throwable t) {
            Logger.err("Failed to register transformer", t);
        }

        if (Boolean.getBoolean("manualcontrol.frametime"))
            manualcontrol.perf.FrameTimeProfiler.initialize(inst);

        System.out.println("=========================================================");
        System.out.println("[ManualControl] All diagnostic output now goes to file:");
        System.out.println("  " + Logger.getLogFile().getAbsolutePath());
        System.out.println("=========================================================");
    }

    private static boolean attackFormClassesAlreadyLoaded(Instrumentation inst) {
        String[] required = {
                "common.battle.data.CustomEntity",
                "common.pack.PackData$UserPack",
                "common.pack.Source$Workspace",
                "common.pack.UserProfile",
                "common.util.anim.AnimCE",
                "common.pack.Source$SourceAnimSaver",
                "page.anim.MaAnimEditPage",
                "page.anim.BufferedAnim",
                "page.anim.GLAnimBox",
                "page.info.edit.EntityEditPage",
                "page.info.edit.FormEditPage",
                "page.info.edit.EnemyEditPage",
                "common.battle.entity.Entity",
                "common.battle.entity.Entity$AnimManager",
                "common.battle.entity.Entity$AtkManager",
                "common.battle.attack.AtkModelEntity",
                "common.util.anim.EAnimD"
        };
        try {
            for (Class<?> loaded : inst.getAllLoadedClasses()) {
                String name = loaded.getName();
                for (String target : required) if (target.equals(name)) return true;
            }
        } catch (Throwable error) {
            AttackFormFeature.disable("loaded-class safety check", error);
            return true;
        }
        return false;
    }

    public static void agentmain(String args, Instrumentation inst) {
        premain(args, inst);
    }

    private static void banner() {
        System.out.println();
        System.out.println("##########################################################");
        System.out.println("##  BCU Manual Control Patch - v" + VERSION);
        System.out.println("##  Build: 2026-09-03");
        System.out.println("##########################################################");
    }
}
