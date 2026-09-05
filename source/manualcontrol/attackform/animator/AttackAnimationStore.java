package manualcontrol.attackform.animator;

import common.pack.PackData;
import common.pack.Source;
import common.pack.UserProfile;
import common.util.anim.AnimCE;
import common.util.anim.AnimCI;
import common.util.anim.MaAnim;
import common.util.unit.Enemy;
import common.util.unit.Form;
import common.util.unit.Unit;
import manualcontrol.attackform.AttackFormFeature;
import manualcontrol.attackform.AttackFormLog;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

public final class AttackAnimationStore {

    private static final int UNDO_HISTORY_CAP = 20;
    private static final Map<AnimCE, State> STATES = new WeakHashMap<AnimCE, State>();

    private static final Map<AnimCI, ReadOnlyState> READ_ONLY_STATES =
            new WeakHashMap<AnimCI, ReadOnlyState>();
    private static final ThreadLocal<PendingSave> ACTIVE_SAVE =
            new ThreadLocal<PendingSave>();
    private static volatile Field savedField;

    private AttackAnimationStore() {}

    public static synchronized AttackAnimationStatus inspect(AnimCI animation) {
        if (!eligible(animation))
            return AttackAnimationStatus.ineligible(
                    "A unit or enemy animation is required.");

        boolean primaryAvailable = animation.anims[2] != null;
        if (!(animation instanceof AnimCE)) {
            return resolveReadOnlyState(animation, primaryAvailable).status;
        }

        State state = STATES.get((AnimCE) animation);
        if (state == null)
            return AttackAnimationStatus.notResolved(primaryAvailable,
                    "Extra attack timelines have not been resolved for this animation.");
        if (state.failure != null)
            return AttackAnimationStatus.failed(primaryAvailable,
                    "Extra attack timeline resolution failed: " + state.failure);
        if (state.extras == null || state.extras.length > AttackAnimationIndex.MAX_EXTRA_ATTACKS)
            return AttackAnimationStatus.failed(primaryAvailable,
                    "Extra attack timeline state has an invalid count.");
        return AttackAnimationStatus.resolved(primaryAvailable, state.extras.length);
    }

    public static synchronized AttackAnimationStatus inspect(AnimCE animation) {
        return inspect((AnimCI) animation);
    }

    public static synchronized AttackAnimationStatus resolveForValidation(AnimCI animation) {
        if (!eligible(animation))
            return AttackAnimationStatus.ineligible(
                    "A unit or enemy animation is required.");
        boolean primaryAvailable = animation.anims[2] != null;
        if (animation instanceof AnimCE) {
            ensureForEditor((AnimCE) animation);
            return inspect(animation);
        }
        return resolveReadOnlyState(animation, primaryAvailable).status;
    }

    private static ReadOnlyState resolveReadOnlyState(AnimCI animation,
                                                      boolean primaryAvailable) {
        ReadOnlyState cached = READ_ONLY_STATES.get(animation);
        if (cached != null) return cached;
        ReadOnlyState resolved;
        try {
            MaAnim[] extras = AttackAnimationRepository.load(animation);
            resolved = new ReadOnlyState(extras,
                    AttackAnimationStatus.resolved(primaryAvailable, extras.length));
        } catch (Throwable error) {
            resolved = new ReadOnlyState(new MaAnim[0],
                    AttackAnimationStatus.failed(primaryAvailable,
                            "Extra attack timeline resolution failed: "
                                    + error.getClass().getSimpleName()
                                    + (error.getMessage() == null
                                    ? "" : ": " + error.getMessage())));
        }
        READ_ONLY_STATES.put(animation, resolved);
        return resolved;
    }

    public static synchronized int extraCount(AnimCE animation) {
        State state = STATES.get(animation);
        return state == null ? 0 : state.extras.length;
    }

    public static synchronized MaAnim timeline(AnimCE animation, int attackSlot) {
        if (animation == null) return null;
        if (attackSlot == 0)
            return animation.anims != null && animation.anims.length > 2
                    ? animation.anims[2] : null;
        State state = STATES.get(animation);
        int index = attackSlot - 1;
        return state != null && index >= 0 && index < state.extras.length
                ? state.extras[index] : null;
    }

    public static synchronized MaAnim runtimeTimeline(AnimCI animation, int attackSlot) {
        if (attackSlot < 0 || attackSlot > AttackAnimationIndex.MAX_EXTRA_ATTACKS)
            throw new IllegalArgumentException("attack slot must be 0..4");
        if (!eligible(animation))
            throw new IllegalArgumentException("a unit or enemy animation is required");
        if (attackSlot == 0) return animation.anims[2];

        MaAnim[] extras;
        String failure = null;
        if (animation instanceof AnimCE) {
            State state = resolveState((AnimCE) animation);
            extras = state.extras;
            failure = state.failure;
        } else {
            ReadOnlyState state = resolveReadOnlyState(animation,
                    animation.anims[2] != null);
            extras = state.extras;
            if (!state.status.isResolved()) failure = state.status.message;
        }
        if (failure != null)
            throw new IllegalStateException("could not resolve runtime attack timeline: "
                    + failure);
        int index = attackSlot - 1;
        return index < extras.length ? extras[index] : null;
    }

    public static synchronized void ensureForEditor(AnimCE animation) {
        if (!eligible(animation) || STATES.containsKey(animation)) return;
        resolveState(animation);
    }

    public static synchronized void setEditorSlot(AnimCE animation, int attackSlot) {
        if (!eligible(animation)) return;
        State state = resolveState(animation);
        state.editorSlot = attackSlot > 0 ? attackSlot : 0;
    }

    public static synchronized int add(AnimCE animation, int copyAttackSlot) {
        requireEditable(animation);
        State state = state(animation);
        if (state.extras.length >= AttackAnimationIndex.MAX_EXTRA_ATTACKS)
            throw new IllegalStateException("maximum attack5 reached");
        MaAnim added;
        if (copyAttackSlot < 0) {
            added = new MaAnim();
        } else {
            MaAnim source = timeline(animation, copyAttackSlot);
            if (source == null) throw new IllegalArgumentException("source attack is unavailable");
            added = source.clone();
        }
        MaAnim[] next = new MaAnim[state.extras.length + 1];
        System.arraycopy(state.extras, 0, next, 0, state.extras.length);
        next[next.length - 1] = added;
        state.extras = next;
        state.structureDirty = true;
        return next.length;
    }

    public static synchronized void remove(AnimCE animation, int attackSlot) {
        requireEditable(animation);
        State state = state(animation);
        int index = attackSlot - 1;
        if (index < 0 || index >= state.extras.length)
            throw new IllegalArgumentException("only an existing attack2..attack5 can be removed");
        MaAnim[] next = new MaAnim[state.extras.length - 1];
        System.arraycopy(state.extras, 0, next, 0, index);
        System.arraycopy(state.extras, index + 1, next, index,
                state.extras.length - index - 1);
        state.extras = next;
        state.structureDirty = true;
        if (state.editorSlot > next.length) state.editorSlot = next.length;
    }

    public static synchronized void onLoaded(Object value) {
        if (!AttackFormFeature.isReady() || !(value instanceof AnimCE)) return;
        AnimCE animation = (AnimCE) value;
        if (!eligible(animation)) return;
        invalidateReadOnly(animation.id);
        try {
            State state = new State(AttackAnimationRepository.load(animation));
            resetHistory(animation, state);
            STATES.put(animation, state);
            AttackFormLog.debug("animator-load", safeId(animation)
                    + " extras=" + state.extras.length);
        } catch (Throwable error) {
            attachFailure(animation, "extra timeline load failed", error);
        }
    }

    public static synchronized void beforeSave(Source.ResourceLocation target, AnimCI value) {
        PendingSave abandoned = ACTIVE_SAVE.get();
        if (abandoned != null) {
            AttackAnimationRepository.abort(abandoned.transaction);
            ACTIVE_SAVE.remove();
        }
        if (!AttackFormFeature.isReady() || !(value instanceof AnimCE)) return;
        AnimCE animation = (AnimCE) value;
        if (!eligible(animation) || target == null || target.base != Source.BasePath.ANIM) return;
        State state = resolveState(animation);
        if (state.failure != null)
            throw new IllegalStateException("cannot overwrite unresolved attack timelines for "
                    + safeId(animation) + ": " + state.failure);
        try {
            AttackAnimationRepository.SaveTransaction transaction =
                    AttackAnimationRepository.stage(target, cloneAll(state.extras));
            if (transaction != null)
                ACTIVE_SAVE.set(new PendingSave(target, animation, transaction));
        } catch (RuntimeException error) {
            markStockUnsaved(animation);
            throw error;
        } catch (Exception error) {
            markStockUnsaved(animation);
            throw new IllegalStateException("could not stage extra attack timelines for "
                    + safeId(animation), error);
        }
    }

    public static synchronized void onSaveFailed(Source.ResourceLocation target, AnimCI value) {
        PendingSave pending = ACTIVE_SAVE.get();
        if (pending == null) {
            if (value instanceof AnimCE) markStockUnsaved((AnimCE) value);
            return;
        }
        try {
            if (!pending.matches(target, value)) {
                AttackAnimationRepository.abort(pending.transaction);
                throw new IllegalStateException("stock animation failure token mismatch");
            }
            AttackAnimationRepository.abort(pending.transaction);
        } finally {
            if (value instanceof AnimCE) markStockUnsaved((AnimCE) value);
            ACTIVE_SAVE.remove();
        }
    }

    public static synchronized void onSaveAborted(Source.ResourceLocation target, AnimCI value) {
        PendingSave pending = ACTIVE_SAVE.get();
        try {
            if (pending != null) {
                if (!pending.matches(target, value))
                    AttackFormLog.error("stock animation abort token mismatch", null);
                AttackAnimationRepository.abort(pending.transaction);
            }
        } catch (Throwable error) {

            AttackFormLog.error("could not abort extra attack save", error);
        } finally {
            if (value instanceof AnimCE) markStockUnsaved((AnimCE) value);
            ACTIVE_SAVE.remove();
        }
    }

    public static synchronized void afterSave(Source.ResourceLocation target, AnimCI value) {
        PendingSave pending = ACTIVE_SAVE.get();
        if (pending == null) return;
        try {
            if (!pending.matches(target, value)) {
                AttackAnimationRepository.abort(pending.transaction);
                throw new IllegalStateException("stock animation Save token mismatch");
            }
            AttackAnimationRepository.commit(pending.transaction);
            invalidateReadOnly(target);
        } catch (RuntimeException error) {
            if (pending.transaction.ready) invalidateReadOnly(target);
            if (value instanceof AnimCE) markStockUnsaved((AnimCE) value);
            throw error;
        } catch (Exception error) {
            if (pending.transaction.ready) invalidateReadOnly(target);
            if (value instanceof AnimCE) markStockUnsaved((AnimCE) value);
            throw new IllegalStateException("could not commit extra attack timelines for "
                    + (value instanceof AnimCE ? safeId((AnimCE) value) : "unknown"), error);
        } finally {
            ACTIVE_SAVE.remove();
        }
    }

    public static synchronized void onSaved(Source.ResourceLocation target, AnimCI value) {
        beforeSave(target, value);
        afterSave(target, value);
    }

    public static synchronized void onCopied(Object targetValue, Object sourceValue) {
        if (!AttackFormFeature.isReady() || !(targetValue instanceof AnimCE)
                || !(sourceValue instanceof AnimCI)) return;
        AnimCE target = (AnimCE) targetValue;
        AnimCI source = (AnimCI) sourceValue;
        if (!eligible(target) || !eligible(source)) return;
        State sourceState;
        if (source instanceof AnimCE) {
            sourceState = resolveState((AnimCE) source);
        } else {
            ReadOnlyState resolved = resolveReadOnlyState(source,
                    source.anims[2] != null);
            if (resolved.status.isResolved()) {
                sourceState = new State(resolved.extras);
            } else {
                sourceState = new State(new MaAnim[0]);
                sourceState.failure = resolved.status.message;
                AttackFormLog.error("could not resolve copied attack timelines for "
                        + safeId(source), null);
            }
        }
        State targetState = new State(cloneAll(sourceState.extras));
        targetState.failure = sourceState.failure;
        resetHistory(target, targetState);
        STATES.put(target, targetState);
        if (targetState.failure != null) {
            markStockUnsaved(target);
            return;
        }

        new Source.SourceAnimSaver(target.id, target).saveData();
        invalidateReadOnly(target.id);
    }

    public static synchronized void onUnsaved(Object value) {
        if (!AttackFormFeature.isReady() || !(value instanceof AnimCE)) return;
        AnimCE animation = (AnimCE) value;
        if (!eligible(animation)) return;
        boolean existed = STATES.containsKey(animation);
        State state = resolveState(animation);
        trimStockHistory(animation);
        if (state.failure != null) return;
        if (existed) state.history.add(snapshot(state));
        alignHistory(animation, state);
        state.structureDirty = false;
    }

    public static synchronized void onRestored(Object value) {
        if (!AttackFormFeature.isReady() || !(value instanceof AnimCE)) return;
        AnimCE animation = (AnimCE) value;
        State state = STATES.get(animation);
        if (state == null || state.history.isEmpty()) return;
        trimStockHistory(animation);
        int target = stockHistorySize(animation);
        while (state.history.size() > target && state.history.size() > 1)
            state.history.remove(state.history.size() - 1);
        state.extras = cloneAll(state.history.get(state.history.size() - 1));
        state.structureDirty = false;
    }

    public static synchronized boolean isReferencedByCustomPack(AnimCE animation) {
        if (animation == null) return false;
        try {
            for (PackData.UserPack pack : UserProfile.getUserPacks()) {
                if (pack == null) continue;
                for (Unit unit : pack.units) {
                    if (unit == null || unit.forms == null) continue;
                    for (Form form : unit.forms)
                        if (form != null && sameAnimation(animation, form.anim)) return true;
                }
                for (Enemy enemy : pack.enemies)
                    if (enemy != null && sameAnimation(animation, enemy.anim)) return true;
            }
        } catch (Throwable error) {

            AttackFormLog.error("could not verify animation ownership before Delete Attack", error);
            return true;
        }
        return false;
    }

    private static State resolveState(AnimCE animation) {
        State current = STATES.get(animation);
        if (current != null) return current;
        try {
            State loaded = new State(AttackAnimationRepository.load(animation));
            resetHistory(animation, loaded);
            STATES.put(animation, loaded);
            return loaded;
        } catch (Throwable error) {
            return attachFailure(animation, "extra timeline resolution failed", error);
        }
    }

    private static State attachFailure(AnimCE animation, String message, Throwable error) {
        State failed = new State(new MaAnim[0]);
        failed.failure = message + ": " + error.getClass().getSimpleName();
        resetHistory(animation, failed);
        STATES.put(animation, failed);
        AttackFormLog.error(message + " for " + safeId(animation), error);
        return failed;
    }

    private static State state(AnimCE animation) {
        return resolveState(animation);
    }

    private static void requireEditable(AnimCE animation) {
        if (!eligible(animation))
            throw new IllegalArgumentException("a unit animation is required");
        State state = state(animation);
        if (state.failure != null)
            throw new IllegalStateException("unresolved extra timeline data: " + state.failure);
    }

    private static boolean eligible(AnimCE animation) {
        return eligible((AnimCI) animation);
    }

    private static boolean eligible(AnimCI animation) {
        return animation != null && animation.id != null
                && animation.id.base == Source.BasePath.ANIM
                && animation.anims != null && animation.anims.length > 2;
    }

    private static void resetHistory(AnimCE animation, State state) {
        trimStockHistory(animation);
        state.history.clear();
        int count = stockHistorySize(animation);
        MaAnim[] baseline = cloneAll(state.extras);
        for (int i = 0; i < count; i++) state.history.add(baseline);
        state.structureDirty = false;
    }

    private static MaAnim[] snapshot(State state) {
        if (state.structureDirty || state.editorSlot < 0 || state.history.isEmpty()
                || state.history.get(state.history.size() - 1).length != state.extras.length)
            return cloneAll(state.extras);
        MaAnim[] copy = state.history.get(state.history.size() - 1).clone();
        if (state.editorSlot > 0) {
            int index = state.editorSlot - 1;
            if (index >= 0 && index < copy.length)
                copy[index] = state.extras[index].clone();
        }
        return copy;
    }

    private static void alignHistory(AnimCE animation, State state) {
        int target = stockHistorySize(animation);
        while (state.history.size() > target)
            state.history.remove(0);
        while (state.history.size() < target) {
            MaAnim[] value = state.history.isEmpty()
                    ? cloneAll(state.extras) : state.history.get(state.history.size() - 1);
            state.history.add(value);
        }
    }

    private static int stockHistorySize(AnimCE animation) {
        return animation == null || animation.history == null
                ? 1 : Math.max(1, Math.min(UNDO_HISTORY_CAP, animation.history.size()));
    }

    private static void trimStockHistory(AnimCE animation) {
        if (animation == null || animation.history == null) return;
        while (animation.history.size() > UNDO_HISTORY_CAP) animation.history.remove(0);
    }

    private static MaAnim[] cloneAll(MaAnim[] values) {
        MaAnim[] copy = new MaAnim[values == null ? 0 : values.length];
        for (int i = 0; i < copy.length; i++)
            copy[i] = values[i] == null ? new MaAnim() : values[i].clone();
        return copy;
    }

    private static boolean sameAnimation(AnimCE expected, Object candidate) {
        if (candidate == expected) return true;
        if (!(candidate instanceof AnimCI) || expected.id == null
                || ((AnimCI) candidate).id == null) return false;
        return sameAnimationId(expected.id, ((AnimCI) candidate).id);
    }

    static boolean references(AnimCE expected, Object candidate) {
        return sameAnimation(expected, candidate);
    }

    private static boolean sameAnimationId(Source.ResourceLocation left,
                                           Source.ResourceLocation right) {
        if (left == null || right == null) return false;
        if (left.base != right.base) return false;
        if (same(left.pack, right.pack) && same(left.id, right.id)) return true;
        String leftId = unmapped(left.id);
        String rightId = unmapped(right.id);
        if (!same(leftId, rightId)) return false;

        return ("_local".equals(left.pack) && mapped(right.id))
                || ("_local".equals(right.pack) && mapped(left.id));
    }

    private static void invalidateReadOnly(Source.ResourceLocation changed) {
        if (changed == null) return;
        Iterator<Map.Entry<AnimCI, ReadOnlyState>> iterator =
                READ_ONLY_STATES.entrySet().iterator();
        while (iterator.hasNext()) {
            AnimCI cached = iterator.next().getKey();
            if (cached == null || sameAnimationId(changed, cached.id)) iterator.remove();
        }
    }

    private static boolean mapped(String value) {
        return value != null && value.startsWith("_mapped_");
    }

    private static String unmapped(String value) {
        return mapped(value) ? value.substring("_mapped_".length()) : value;
    }

    private static boolean same(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private static void markStockUnsaved(AnimCE animation) {
        try {
            Field field = savedField;
            if (field == null) {
                synchronized (AttackAnimationStore.class) {
                    field = savedField;
                    if (field == null) {
                        field = AnimCE.class.getDeclaredField("saved");
                        field.setAccessible(true);
                        savedField = field;
                    }
                }
            }
            field.setBoolean(animation, false);
        } catch (Throwable error) {
            AttackFormFeature.disable("could not restore AnimCE unsaved state", error);
        }
    }

    private static String safeId(AnimCE animation) {
        return safeId((AnimCI) animation);
    }

    private static String safeId(AnimCI animation) {
        try { return animation == null || animation.id == null ? "unknown" : animation.id.toString(); }
        catch (Throwable ignored) { return "unknown"; }
    }

    private static final class State {
        MaAnim[] extras;
        String failure;
        int editorSlot = -1;
        boolean structureDirty;
        final ArrayList<MaAnim[]> history = new ArrayList<MaAnim[]>();

        State(MaAnim[] extras) {
            this.extras = extras == null ? new MaAnim[0] : extras;
        }
    }

    private static final class ReadOnlyState {
        final MaAnim[] extras;
        final AttackAnimationStatus status;

        ReadOnlyState(MaAnim[] extras, AttackAnimationStatus status) {
            this.extras = extras == null ? new MaAnim[0] : extras;
            this.status = status;
        }
    }

    private static final class PendingSave {
        final String pack;
        final String id;
        final Source.BasePath base;
        final AnimCI animation;
        final AttackAnimationRepository.SaveTransaction transaction;

        PendingSave(Source.ResourceLocation target, AnimCI animation,
                    AttackAnimationRepository.SaveTransaction transaction) {
            this.pack = target.pack;
            this.id = target.id;
            this.base = target.base;
            this.animation = animation;
            this.transaction = transaction;
        }

        boolean matches(Source.ResourceLocation target, AnimCI value) {
            return target != null && animation == value && base == target.base
                    && same(pack, target.pack) && same(id, target.id);
        }
    }
}
