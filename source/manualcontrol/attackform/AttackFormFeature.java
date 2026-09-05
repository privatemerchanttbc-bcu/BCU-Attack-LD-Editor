package manualcontrol.attackform;

import manualcontrol.FeatureFlags;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class AttackFormFeature {

    public enum State {
        NOT_INITIALIZED,
        DISABLED,
        READY,
        INCOMPATIBLE,
        FAILED
    }

    private static volatile State state = State.NOT_INITIALIZED;
    private static volatile AttackFormCompatibility.Report report;
    private static boolean disableLogged;
    private static final List<Runnable> DISABLE_LISTENERS = new ArrayList<Runnable>();

    private AttackFormFeature() {}

    public static synchronized State initialize(ClassLoader loader) {
        if (state != State.NOT_INITIALIZED) return state;
        if (!FeatureFlags.switchAttackForm) {
            state = State.DISABLED;
            publishState();
            AttackFormLog.info("disabled by feature selection; no attack-form transformer registered");
            return state;
        }

        try {
            report = AttackFormCompatibility.probe(loader);
            AttackFormLog.info("BCU build=" + report.build.displayName());
            for (Map.Entry<String, AttackFormCompatibility.GroupResult> entry
                    : report.groups.entrySet()) {
                AttackFormCompatibility.GroupResult group = entry.getValue();
                AttackFormLog.info("capability " + group.name + "="
                        + (group.compatible ? "READY" : "MISSING")
                        + (group.missing.isEmpty() ? "" : " details=" + group.missing));
            }
            if (!report.compatible) {
                FeatureFlags.switchAttackForm = false;
                state = State.INCOMPATIBLE;
                publishState();
                AttackFormLog.info("DISABLED safely: " + report.summary()
                        + "; stock BCU behavior remains active");
                return state;
            }

            state = State.READY;
            publishState();
            AttackFormLog.info("compatibility gate READY " + report.summary());
            return state;
        } catch (Throwable error) {
            FeatureFlags.switchAttackForm = false;
            state = State.FAILED;
            publishState();
            AttackFormLog.error("foundation failed; feature disabled and stock BCU preserved", error);
            return state;
        }
    }

    public static synchronized void disable(String where, Throwable error) {
        FeatureFlags.switchAttackForm = false;
        state = State.FAILED;
        publishState();
        for (Runnable listener : new ArrayList<Runnable>(DISABLE_LISTENERS)) {
            try { listener.run(); }
            catch (Throwable listenerError) {
                AttackFormLog.error("attack-form disable listener failed", listenerError);
            }
        }
        if (disableLogged) return;
        disableLogged = true;
        String detail = where == null || where.trim().length() == 0 ? "unknown" : where.trim();
        if (error == null)
            AttackFormLog.info("DISABLED safely at " + detail + "; stock BCU behavior remains active");
        else
            AttackFormLog.error("DISABLED safely at " + detail
                    + "; stock BCU behavior remains active", error);
    }

    public static State getState() {
        return state;
    }

    public static boolean isReady() {
        return state == State.READY && FeatureFlags.switchAttackForm;
    }

    public static AttackFormCompatibility.Report getReport() {
        return report;
    }

    public static synchronized void addDisableListener(Runnable listener) {
        if (listener != null && !DISABLE_LISTENERS.contains(listener))
            DISABLE_LISTENERS.add(listener);
    }

    static synchronized void resetForTests() {
        state = State.NOT_INITIALIZED;
        report = null;
        disableLogged = false;
        System.clearProperty("manualcontrol.attackform.status");
    }

    private static void publishState() {
        System.setProperty("manualcontrol.attackform.status", state.name());
    }
}
