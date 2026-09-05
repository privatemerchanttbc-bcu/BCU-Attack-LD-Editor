package manualcontrol.attackform.data;

import common.battle.data.CustomEntity;
import manualcontrol.attackform.AttackFormFeature;
import manualcontrol.attackform.AttackFormLog;

public final class AttackFormStore {

    private AttackFormStore() {}

    public static AttackFormSet get(Object entity) {
        if (!(entity instanceof AttackFormCarrier)) return null;
        return ((AttackFormCarrier) entity).attackForm$get();
    }

    public static AttackFormSet ensure(CustomEntity entity) {
        if (!(entity instanceof AttackFormCarrier))
            throw new IllegalStateException("CustomEntity attack-form carrier is unavailable");
        AttackFormCarrier carrier = (AttackFormCarrier) entity;
        AttackFormSet set = carrier.attackForm$get();
        if (set == null) {
            set = AttackFormSet.capture(entity);
            carrier.attackForm$set(set);
        } else {
            set.repair();
        }
        return set;
    }

    public static void clear(Object entity) {
        if (entity instanceof AttackFormCarrier)
            ((AttackFormCarrier) entity).attackForm$set(null);
    }

    public static void onImport(Object target, Object source) {
        if (!(target instanceof AttackFormCarrier)) return;
        try {
            if (!AttackFormFeature.isReady()) {
                clear(target);
                return;
            }
            AttackFormSet sourceSet = get(source);
            AttackFormSet copy = sourceSet == null ? null : sourceSet.deepCopy();
            if (copy != null && target instanceof CustomEntity)
                copy.synchronizePrimary((CustomEntity) target);
            ((AttackFormCarrier) target).attackForm$set(copy);
        } catch (Throwable error) {
            clear(target);
            AttackFormFeature.disable("CustomEntity attack-form copy hook", error);
        }
    }

    public static void onInjected(Object entity) {
        try {
            if (!AttackFormFeature.isReady()) {
                clear(entity);
                return;
            }
            AttackFormSet set = get(entity);
            if (set == null) return;
            if (set.repair()) AttackFormLog.info("repaired invalid persisted attack-form data");
        } catch (Throwable error) {
            clear(entity);
            AttackFormFeature.disable("CustomEntity attack-form load hook", error);
        }
    }

    public static boolean attach(CustomEntity entity, AttackFormSet value) {
        if (!(entity instanceof AttackFormCarrier) || value == null) return false;
        try {
            AttackFormSet copy = value.deepCopy();
            copy.synchronizePrimary(entity);
            ((AttackFormCarrier) entity).attackForm$set(copy);
            return true;
        } catch (Throwable error) {
            clear(entity);
            AttackFormFeature.disable("attack-form sidecar attach", error);
            return false;
        }
    }
}
