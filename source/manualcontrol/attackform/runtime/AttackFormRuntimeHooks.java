package manualcontrol.attackform.runtime;

import common.battle.attack.AtkModelEntity;
import common.battle.attack.AttackAb;
import common.battle.data.CustomEnemy;
import common.battle.data.CustomEntity;
import common.battle.data.CustomUnit;
import common.battle.data.MaskEntity;
import common.battle.data.PCoin;
import common.battle.entity.Entity;
import common.util.Animable;
import common.util.anim.AnimCI;
import common.util.anim.AnimU;
import common.util.anim.EAnimU;
import common.util.anim.MaAnim;
import common.util.unit.Level;
import manualcontrol.attackform.AttackFormFeature;
import manualcontrol.attackform.AttackFormLog;
import manualcontrol.attackform.animator.AttackAnimationStore;
import manualcontrol.attackform.data.AttackFormData;
import manualcontrol.attackform.data.AttackFormSet;
import manualcontrol.attackform.data.AttackFormStore;
import manualcontrol.attackform.data.AttackFormValidation;
import manualcontrol.newproc.NewProcRuntime;

import java.util.concurrent.atomic.AtomicBoolean;

public final class AttackFormRuntimeHooks {

    private static final AtomicBoolean INIT_FAILURE_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean COEXISTENCE_FAILURE_LOGGED = new AtomicBoolean();

    private AttackFormRuntimeHooks() {}

    public static void initializeUnit(Entity entity, float treasure, float level,
                                      PCoin pcoin, Level lv) {
        initialize(entity, new ModelFactory() {
            @Override public AtkModelEntity create(Entity owner) {
                return AtkModelEntity.getUnitAtk(owner, treasure, level, pcoin, lv);
            }
        });
    }

    public static void initializeEnemy(Entity entity, float attackMagnification) {
        initialize(entity, new ModelFactory() {
            @Override public AtkModelEntity create(Entity owner) {
                return AtkModelEntity.getEnemyAtk(owner, attackMagnification);
            }
        });
    }

    private static void initialize(Entity entity, ModelFactory factory) {
        if (!AttackFormFeature.isReady() || !(entity instanceof AttackFormRuntimeCarrier)) return;
        AttackFormRuntimeCarrier carrier = (AttackFormRuntimeCarrier) entity;
        if (carrier.attackFormRuntime$get() != null) return;

        MaskEntity originalData = carrier.attackFormRuntime$data();
        AtkModelEntity originalModel = carrier.attackFormRuntime$model();
        if (!(originalData instanceof CustomEntity)) return;
        AttackFormSet live = AttackFormStore.get((CustomEntity) originalData);
        if (live == null || !live.isMeaningful()) return;

        try {
            AttackFormSet forms = live.deepCopy();
            forms.synchronizePrimary((CustomEntity) originalData);
            AttackFormValidation validation = forms.validateForSave();
            if (!validation.valid)
                throw new IllegalStateException("invalid battle attack forms: " + validation.message);

            int count = forms.formCount();
            int[] rates = forms.rates();
            MaskEntity[] data = new MaskEntity[count];
            AtkModelEntity[] models = new AtkModelEntity[count];
            MaAnim[] timelines = new MaAnim[count];
            AnimCI animation = animation(originalData);

            for (int form = 0; form < count; form++) {
                CustomEntity view = copy((CustomEntity) originalData);
                AttackFormData snapshot = forms.formCopy(form);
                snapshot.applyTo(view);
                AttackFormStore.clear(view);
                data[form] = view;
                timelines[form] = AttackAnimationStore.runtimeTimeline(animation, form);
                if (timelines[form] == null)
                    throw new IllegalStateException("missing " + AttackFormData.animationName(form));

                carrier.attackFormRuntime$activate(view, originalModel);
                models[form] = factory.create(entity);
                if (models[form] == null)
                    throw new IllegalStateException("BCU did not create an attack model");
            }

            AttackFormRuntimeState state = new AttackFormRuntimeState(
                    forms.isPreventConsecutive(), rates, data, models, timelines,
                    originalData, originalModel);
            carrier.attackFormRuntime$set(state);
            carrier.attackFormRuntime$activate(data[0], models[0]);
            notifyActivation(entity);
            if (AttackFormLog.isDebugEnabled())
                AttackFormLog.debug("battle-runtime", "prepared forms=" + count
                        + " entity=" + entity.getClass().getSimpleName());
        } catch (Throwable error) {
            carrier.attackFormRuntime$activate(originalData, originalModel);
            carrier.attackFormRuntime$set(null);
            if (INIT_FAILURE_LOGGED.compareAndSet(false, true))
                AttackFormLog.error("Switch Attack Form battle preparation failed; stock attack retained",
                        error);
        }
    }

    public static void beforeTargetSearch(Entity entity) {
        activateSelection(entity);
    }

    public static void beforeStartAttack(Object managerValue) {
        AttackFormManagerCarrier manager = manager(managerValue);
        if (manager != null) activateSelection(manager.attackFormRuntime$owner());
    }

    private static void activateSelection(Entity entity) {
        if (!AttackFormFeature.isReady()) {
            deactivateIfIdle(entity);
            return;
        }
        if (!(entity instanceof AttackFormRuntimeCarrier)) return;
        AttackFormRuntimeCarrier carrier = (AttackFormRuntimeCarrier) entity;
        AttackFormRuntimeState state = carrier.attackFormRuntime$get();
        if (state == null || state.isAttackInProgress()) return;

        int previous = state.getSelectedForm();
        int selected = state.selectIfNeeded(entity.basis.r);
        AtkModelEntity selectedModel = state.model(selected);
        MaskEntity selectedData = state.data(selected);
        if (carrier.attackFormRuntime$model() == selectedModel
                && carrier.attackFormRuntime$data() == selectedData) return;

        state.activateSelected();
        carrier.attackFormRuntime$activate(selectedData, selectedModel);
        notifyActivation(entity);
        AttackFormManagerCarrier manager = manager(carrier.attackFormRuntime$manager());
        if (manager != null)
            manager.attackFormRuntime$configure(state.selectedPreSchedule(),
                    state.selectedAttacksRemaining());
        if (previous < 0 && AttackFormLog.isDebugEnabled())
            AttackFormLog.debug("battle-select", AttackFormData.animationName(selected)
                    + " rate=" + state.rate(selected));
    }

    public static void afterStartAttack(Object managerValue) {
        AttackFormManagerCarrier manager = manager(managerValue);
        if (manager == null) return;
        if (!AttackFormFeature.isReady()) {
            deactivateIfIdle(manager.attackFormRuntime$owner());
            return;
        }
        AttackFormRuntimeState state = state(manager.attackFormRuntime$owner());
        if (state == null) return;
        if (!state.isAttackInProgress()) state.beginAttack();
        MaAnim timeline = state.selectedTimeline();
        manager.attackFormRuntime$attackTime(timeline.max + 1);
    }

    public static AttackAb attackCreated(AttackAb attack, AtkModelEntity model, int hit) {
        if (attack == null || model == null) return attack;
        if (!AttackFormFeature.isReady()) {
            deactivateIfIdle(model.e);
            return attack;
        }
        AttackFormRuntimeState state = state(model.e);
        if (state != null && state.isAttackInProgress() && state.isSelectedModel(model)
                && hit >= 0 && hit < state.selectedHitCount())
            state.markDamageCreated();
        return attack;
    }

    public static void afterUpdateAttack(Object managerValue) {
        AttackFormManagerCarrier manager = manager(managerValue);
        if (manager == null) return;
        Entity owner = manager.attackFormRuntime$owner();
        AttackFormRuntimeState state = state(owner);
        if (!AttackFormFeature.isReady()) {
            if (state != null && state.isAttackInProgress()) saveRemaining(state, manager);
            if (state != null && state.isAttackInProgress()
                    && manager.attackFormRuntime$attackTime() == 0)
                state.finishOrCancelAttack();
            deactivateIfIdle(owner);
            return;
        }
        if (state == null || !state.isAttackInProgress()) return;
        saveRemaining(state, manager);
        if (manager.attackFormRuntime$attackTime() == 0) state.finishOrCancelAttack();
    }

    public static void afterStopAttack(Object managerValue) {
        AttackFormManagerCarrier manager = manager(managerValue);
        if (manager == null) return;
        Entity owner = manager.attackFormRuntime$owner();
        AttackFormRuntimeState state = state(owner);
        if (!AttackFormFeature.isReady()) {
            if (state != null && state.isAttackInProgress()) {
                saveRemaining(state, manager);
                state.finishOrCancelAttack();
            }
            deactivateIfIdle(owner);
            return;
        }
        if (state == null || !state.isAttackInProgress()) return;
        saveRemaining(state, manager);
        state.finishOrCancelAttack();
    }

    private static void saveRemaining(AttackFormRuntimeState state,
                                      AttackFormManagerCarrier manager) {
        int selected = state.getSelectedForm();
        if (selected >= 0)
            state.setAttacksRemaining(selected, manager.attackFormRuntime$attacksLeft());
    }

    public static int animationLength(int stockLength, Entity entity, EAnimU animation,
                                      AnimU.UType type) {
        if (type != AnimU.UType.ATK || animation == null) return stockLength;
        if (!AttackFormFeature.isReady()) {
            deactivateIfIdle(entity);
            return stockLength;
        }
        AttackFormRuntimeState state = state(entity);
        if (state == null || state.getSelectedForm() < 0) return stockLength;
        MaAnim timeline = state.selectedTimeline();
        if (!(animation instanceof AttackFormTimelineCarrier)) return stockLength;
        ((AttackFormTimelineCarrier) animation).attackFormRuntime$timeline(timeline);
        return timeline.max + 1;
    }

    public static int effectiveAttackLength(Entity entity, int stockLength) {
        int fallback = Math.max(1, stockLength);
        if (!AttackFormFeature.isReady()) {
            deactivateIfIdle(entity);

            AttackFormRuntimeState draining = state(entity);
            return draining != null && draining.isAttackInProgress()
                    ? Math.max(1, draining.selectedTimeline().max + 1) : fallback;
        }
        AttackFormRuntimeState state = state(entity);
        if (state == null || state.getSelectedForm() < 0) return fallback;
        MaAnim timeline = state.selectedTimeline();
        return timeline == null ? fallback : Math.max(1, timeline.max + 1);
    }

    private static void deactivateIfIdle(Entity entity) {
        if (!(entity instanceof AttackFormRuntimeCarrier)) return;
        AttackFormRuntimeCarrier carrier = (AttackFormRuntimeCarrier) entity;
        AttackFormRuntimeState state = carrier.attackFormRuntime$get();
        if (state == null || state.isAttackInProgress()) return;
        carrier.attackFormRuntime$activate(state.stockData(), state.stockModel());
        AttackFormManagerCarrier manager = manager(carrier.attackFormRuntime$manager());
        if (manager != null)
            manager.attackFormRuntime$configure(state.stockPreSchedule(),
                    state.stockAttacksRemaining());
        carrier.attackFormRuntime$set(null);
        notifyActivation(entity);
    }

    private static void notifyActivation(Entity entity) {
        try {
            NewProcRuntime.onAttackFormActivated(entity);
        } catch (Throwable error) {
            if (COEXISTENCE_FAILURE_LOGGED.compareAndSet(false, true))
                AttackFormLog.error("New Proc attack-form activation bridge failed", error);
        }
    }

    private static AttackFormRuntimeState state(Entity entity) {
        return entity instanceof AttackFormRuntimeCarrier
                ? ((AttackFormRuntimeCarrier) entity).attackFormRuntime$get() : null;
    }

    private static AttackFormManagerCarrier manager(Object value) {
        return value instanceof AttackFormManagerCarrier
                ? (AttackFormManagerCarrier) value : null;
    }

    private static AnimCI animation(MaskEntity data) {
        Animable<?, ?> pack = data.getPack();
        if (pack == null || !(pack.anim instanceof AnimCI))
            throw new IllegalStateException("Switch Attack Form requires an AnimCI animation");
        return (AnimCI) pack.anim;
    }

    private static CustomEntity copy(CustomEntity source) {
        if (source instanceof CustomUnit) return ((CustomUnit) source).clone();
        if (source instanceof CustomEnemy) {
            CustomEnemy enemy = (CustomEnemy) source;
            return enemy.copy(enemy.getPack());
        }
        throw new IllegalArgumentException("unsupported CustomEntity type "
                + source.getClass().getName());
    }

    private interface ModelFactory {
        AtkModelEntity create(Entity owner);
    }
}
