package manualcontrol.attackform.editor;

import common.battle.data.CustomEntity;
import common.pack.PackData;
import common.util.Animable;
import common.util.anim.AnimCE;
import common.util.anim.AnimCI;
import common.util.unit.Enemy;
import common.util.unit.Form;
import common.util.unit.Unit;
import manualcontrol.attackform.AttackFormFeature;
import manualcontrol.attackform.AttackFormLog;
import manualcontrol.attackform.animator.AttackAnimationStatus;
import manualcontrol.attackform.animator.AttackAnimationStore;
import manualcontrol.attackform.data.AttackFormData;
import manualcontrol.attackform.data.AttackFormSet;
import page.JTF;
import page.Page;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.GraphicsEnvironment;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;

public final class AttackFormEditorHooks {

    private static final Map<Object, WeakReference<State>> STATES =
            Collections.synchronizedMap(new WeakHashMap<Object, WeakReference<State>>());

    private static final Map<PackData.UserPack, Integer> SAVING_PACKS =
            new IdentityHashMap<PackData.UserPack, Integer>();
    private static boolean disableListenerInstalled;

    private AttackFormEditorHooks() {}

    public static void onBuilt(final Object page) {
        if (!AttackFormFeature.isReady() || !(page instanceof Container)) return;
        try {
            if (stateFor(page) != null) return;
            final CustomEntity entity = entity(page);
            final boolean editable = editable(page);
            final State state = new State(page, entity, editable);
            installControls(state);
            synchronized (STATES) {
                STATES.put(page, new WeakReference<State>(state));
                if (!disableListenerInstalled) {
                    AttackFormFeature.addDisableListener(new Runnable() {
                        @Override public void run() { teardownAll(); }
                    });
                    disableListenerInstalled = true;
                }
            }
            int saveDepth = savingDepth(entity);
            if (saveDepth > 0) alignSessionToSaveDepth(state, saveDepth);
            updateControls(state);
            Container container = (Container) page;
            if (container.getWidth() > 0 && container.getHeight() > 0)
                place(state, container.getWidth(), container.getHeight());
            AttackFormLog.debug("editor-open", entity.getClass().getSimpleName());
        } catch (Throwable error) {
            fail(page, "Unit/Enemy Editor construction", error);
        }
    }

    public static void onResized(Object page, int width, int height) {
        State state = stateFor(page);
        if (state == null || !AttackFormFeature.isReady()) return;
        try { place(state, width, height); }
        catch (Throwable error) { fail(page, "Unit/Enemy Editor layout", error); }
    }

    public static void afterStockSetData(Object page) {
        State state = stateFor(page);
        if (state == null || !AttackFormFeature.isReady()
                || state.swapping || !state.session.isActive()
                || state.session.isSaveSuspended()) return;
        try {
            state.session.captureVisible();
            updateControls(state);
        } catch (Throwable error) {
            fail(page, "Unit/Enemy Editor form capture", error);
        }
    }

    public static void afterRenew(Object page) {
        State state = stateFor(page);
        if (state == null || !AttackFormFeature.isReady()) return;
        try {
            if (!state.session.isActive()) {
                state.swapping = true;
                try {
                    state.session.reactivate();
                    if (!state.session.isSaveSuspended()) invokeStockRefresh(state);
                } finally {
                    state.swapping = false;
                }
            }
            updateControls(state);
        } catch (Throwable error) {
            fail(page, "Unit/Enemy Editor resume", error);
        }
    }

    public static void onLeave(Object page) {
        State state = stateFor(page);
        if (state == null || !AttackFormFeature.isReady()) return;
        try {
            restorePrimaryAndRefresh(state);
        } catch (Throwable error) {
            fail(page, "Unit/Enemy Editor leave", error);
        }
    }

    public static void onExit(Object page) {
        State state = stateFor(page);
        if (state == null) return;
        try {
            restorePrimaryAndRefresh(state);
        } catch (Throwable error) {
            AttackFormLog.error("could not restore attack1 while closing editor", unwrap(error));
        } finally {
            teardown(page, false);
        }
    }

    public static void beforePackSave(Object value) {
        if (!AttackFormFeature.isReady() || !(value instanceof PackData.UserPack)) return;
        final PackData.UserPack pack = (PackData.UserPack) value;
        final int previous;
        final int target;
        synchronized (SAVING_PACKS) {
            Integer depth = SAVING_PACKS.get(pack);
            previous = depth == null ? 0 : depth;
            target = previous + 1;
            SAVING_PACKS.put(pack, target);
        }
        try {
            runOnEdtAndWait(new EdtAction() {
                @Override public void run() throws Exception {
                    alignPackSessions(pack, target);
                }
            });
        } catch (Throwable error) {
            synchronized (SAVING_PACKS) {
                if (previous == 0) SAVING_PACKS.remove(pack);
                else SAVING_PACKS.put(pack, previous);
            }
            try {
                runOnEdtAndWait(new EdtAction() {
                    @Override public void run() throws Exception {
                        alignPackSessions(pack, previous);
                    }
                });
            } catch (Throwable rollback) {
                AttackFormLog.error("could not roll back editor Save suspension", rollback);
            }
            if (error instanceof RuntimeException) throw (RuntimeException) error;
            if (error instanceof Error) throw (Error) error;
            throw new IllegalStateException("could not suspend editor for Save", error);
        }
    }

    public static boolean requiresEdtSave(Object value) {
        if (!AttackFormFeature.isReady() || !(value instanceof PackData.UserPack)) return false;
        PackData.UserPack pack = (PackData.UserPack) value;
        try {
            for (State state : snapshotStates())
                if (belongsTo(pack, state.entity)) return true;
            return false;
        } catch (Throwable error) {

            AttackFormLog.error("could not inspect pack before EDT Save routing", error);
            return true;
        }
    }

    public static void afterPackSave(Object value) {
        if (!(value instanceof PackData.UserPack)) return;
        final PackData.UserPack pack = (PackData.UserPack) value;
        final int target;
        synchronized (SAVING_PACKS) {
            Integer depth = SAVING_PACKS.get(pack);
            if (depth == null || depth <= 1) {
                SAVING_PACKS.remove(pack);
                target = 0;
            } else {
                target = depth - 1;
                SAVING_PACKS.put(pack, target);
            }
        }
        try {
            runOnEdtAndWait(new EdtAction() {
                @Override public void run() throws Exception {
                    alignPackSessions(pack, target);
                }
            });
        } catch (Throwable error) {
            AttackFormLog.error("could not restore Unit/Enemy Editor after Save", unwrap(error));
            AttackFormFeature.disable("Unit/Enemy Editor Save restore", unwrap(error));
        }
    }

    private static void installControls(final State state) {
        Container page = (Container) state.page;
        state.enabled = new JCheckBox("Switch Attack Form");
        state.enabled.setOpaque(true);
        state.enabled.setBackground(Page.BGCOLOR);
        state.enabled.setToolTipText("Use independent parameters and animation timelines for attack..attack5.");
        state.enabled.setEnabled(state.editable);

        state.selector = new JComboBox<String>();
        state.selector.setToolTipText("Select the attack form whose parameters are shown below.");

        state.rateLabel = new JLabel("Rate", JLabel.CENTER);
        state.rateLabel.setBorder(BorderFactory.createEtchedBorder());
        state.rate = new JTF();
        state.rate.setToolTipText("Appearance chance for this form. Other Rates rebalance automatically to total 100%.");

        state.prevent = new JCheckBox("Prevent Consecutive");
        state.prevent.setOpaque(true);
        state.prevent.setBackground(Page.BGCOLOR);
        state.prevent.setToolTipText("When enabled, the form that just dealt damage cannot be selected next.");

        state.status = new JLabel("", JLabel.CENTER);
        state.status.setBorder(BorderFactory.createEtchedBorder());
        state.status.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (!state.syncAvailable || !state.editable || state.updating
                        || state.session.isSaveSuspended()) return;
                try {
                    synchronizeMissingForms(state, true);
                    updateControls(state);
                } catch (Throwable error) {
                    fail(state.page, "Attack form parameter synchronization", error);
                }
            }
        });

        page.add(state.enabled);
        page.add(state.selector);
        page.add(state.rateLabel);
        page.add(state.rate);
        page.add(state.prevent);
        page.add(state.status);

        state.enabled.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent event) {
                if (!state.updating) toggleEnabled(state);
            }
        });
        state.selector.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent event) {
                if (!state.updating) selectForm(state);
            }
        });
        state.rate.setLnr(e -> commitRate(state));
        state.rate.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent event) { commitRate(state); }
        });
        state.prevent.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent event) {
                if (state.updating) return;
                if (state.session.isSaveSuspended()) {
                    updateControls(state);
                    return;
                }
                try {
                    AttackFormSet forms = state.session.forms();
                    if (forms != null) forms.setPreventConsecutive(state.prevent.isSelected());
                    updateControls(state);
                } catch (Throwable error) {
                    fail(state.page, "Prevent Consecutive edit", error);
                }
            }
        });
    }

    private static void toggleEnabled(State state) {
        if (state.session.isSaveSuspended()) {
            updateControls(state);
            return;
        }
        try {
            if (!state.enabled.isSelected()) {
                AttackFormSet current = state.session.forms();
                if (current != null && current.formCount() > 1 && !confirmDiscard(state)) {
                    updateControls(state);
                    return;
                }
                state.swapping = true;
                try {
                    state.session.discardSecondaryForms();
                    invokeStockRefresh(state);
                } finally {
                    state.swapping = false;
                }
                updateControls(state);
                return;
            }

            AttackAnimationStatus animation = resolveAnimation(state.entity);
            if (!animation.isResolved() || !animation.primaryTimelineAvailable
                    || animation.totalTimelineCount < 2) {
                show(state, animation.isResolved()
                                ? "Create attack2 in Animator before enabling Switch Attack Form."
                                : animation.message,
                        JOptionPane.WARNING_MESSAGE);
                updateControls(state);
                return;
            }

            AttackFormSet forms = state.session.ensureForms();
            int wanted = animation.totalTimelineCount;
            int existing = forms.formCount();
            if (existing > wanted) {
                show(state, "This entity has more attack forms than its animation. Restore the missing "
                                + "timeline in Animator, or untick Switch Attack Form to discard secondary forms.",
                        JOptionPane.WARNING_MESSAGE);
                updateControls(state);
                return;
            }
            boolean copy = true;
            if (existing < wanted) {
                int choice = chooseInitialization(state, existing, wanted);
                if (choice < 0) {
                    updateControls(state);
                    return;
                }
                copy = choice == 0;
                while (forms.formCount() < wanted) forms.addForm(0, copy);
            }
            forms.setEnabled(true);
            state.swapping = true;
            try {
                state.session.select(Math.min(state.session.selectedIndex(), forms.formCount() - 1));
                invokeStockRefresh(state);
            } finally {
                state.swapping = false;
            }
            updateControls(state);
        } catch (Throwable error) {
            fail(state.page, "Switch Attack Form toggle", error);
        }
    }

    private static void selectForm(State state) {
        if (state.session.isSaveSuspended()) {
            updateControls(state);
            return;
        }
        int index = state.selector.getSelectedIndex();
        if (index < 0 || index == state.session.selectedIndex()) return;
        try {
            state.swapping = true;
            try {
                state.session.select(index);
                invokeStockRefresh(state);
            } finally {
                state.swapping = false;
            }
            updateControls(state);
            AttackFormLog.debug("editor-select", AttackFormData.animationName(index));
        } catch (Throwable error) {
            fail(state.page, "Attack form selection", error);
        }
    }

    private static void commitRate(State state) {
        if (state.updating) return;
        if (state.session.isSaveSuspended()) {
            updateControls(state);
            return;
        }
        try {
            AttackFormSet forms = state.session.forms();
            if (forms == null || forms.formCount() <= 1) return;
            String text = state.rate.getText() == null ? "" : state.rate.getText().trim();
            int requested = Integer.parseInt(text);
            forms.setRate(state.session.selectedIndex(), requested);
            updateControls(state);
        } catch (NumberFormatException invalid) {
            show(state, "Rate must be a whole number from 1 to 100.",
                    JOptionPane.WARNING_MESSAGE);
            updateControls(state);
        } catch (Throwable error) {
            fail(state.page, "Attack form Rate edit", error);
        }
    }

    private static void updateControls(State state) {
        if (state.updating) return;
        state.updating = true;
        try {
            AttackFormSet forms = state.session.forms();
            boolean enabled = forms != null && forms.isEnabled() && forms.formCount() > 1;
            int count = enabled ? forms.formCount() : 1;
            int selected = enabled
                    ? Math.max(0, Math.min(state.session.selectedIndex(), count - 1)) : 0;

            boolean saveLocked = state.session.isSaveSuspended();
            state.enabled.setSelected(enabled);
            state.enabled.setEnabled(state.editable && !saveLocked);
            state.selector.removeAllItems();
            for (int i = 0; i < count; i++) state.selector.addItem(AttackFormData.animationName(i));
            state.selector.setSelectedIndex(selected);
            state.selector.setVisible(enabled);
            state.selector.setEnabled(enabled && !saveLocked);

            boolean ratesVisible = enabled && count > 1;
            state.rateLabel.setVisible(ratesVisible);
            state.rate.setVisible(ratesVisible);
            state.rate.setEditable(state.editable && ratesVisible && !saveLocked);
            state.prevent.setVisible(ratesVisible);
            state.prevent.setEnabled(state.editable && ratesVisible && !saveLocked);
            if (ratesVisible) {
                state.rate.setText(String.valueOf(forms.rates()[selected]));
                state.prevent.setSelected(forms.isPreventConsecutive());
            }

            state.status.setVisible(enabled);
            if (enabled) {
                AttackAnimationStatus animation = inspectAnimation(state.entity);
                AttackFormBinding.Report binding = AttackFormBinding.validate(forms, animation);
                state.syncAvailable = state.editable && animation.isResolved()
                        && animation.primaryTimelineAvailable
                        && animation.totalTimelineCount > forms.formCount();
                state.status.setText(binding.valid ? "Animation binding ready"
                        : (state.syncAvailable ? "Add missing attack forms" : "Timeline mismatch"));
                state.status.setToolTipText(binding.valid
                        ? "Every form has its matching attack animation timeline."
                        : binding.message + (state.syncAvailable
                        ? " Click here to create the missing parameter forms." : ""));
                state.status.setForeground(binding.valid ? new Color(0, 128, 0) : Color.RED.darker());
                state.status.setCursor(Cursor.getPredefinedCursor(state.syncAvailable
                        ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
            } else {
                state.syncAvailable = false;
                state.status.setCursor(Cursor.getDefaultCursor());
            }
            ((Container) state.page).revalidate();
            ((Container) state.page).repaint();
        } finally {
            state.updating = false;
        }
    }

    private static AttackAnimationStatus inspectAnimation(CustomEntity entity) {
        AnimCI animation = animation(entity);
        if (animation instanceof AnimCE) AttackAnimationStore.ensureForEditor((AnimCE) animation);

        return animation instanceof AnimCE ? AttackAnimationStore.inspect(animation)
                : AttackAnimationStore.resolveForValidation(animation);
    }

    private static boolean synchronizeMissingForms(State state, boolean explainUnavailable) {
        if (!state.editable) return false;
        AttackFormSet forms = state.session.forms();
        if (forms == null || !forms.isEnabled() || forms.formCount() < 2) return false;

        AttackAnimationStatus animation = resolveAnimation(state.entity);
        if (!animation.isResolved() || !animation.primaryTimelineAvailable) {
            if (explainUnavailable)
                show(state, animation.message, JOptionPane.WARNING_MESSAGE);
            return false;
        }

        int existing = forms.formCount();
        int wanted = animation.totalTimelineCount;
        if (wanted <= existing) return false;
        state.session.captureVisible();
        int choice = chooseInitialization(state, existing, wanted);
        if (choice < 0) return false;
        boolean copyPrimary = choice == 0;
        while (forms.formCount() < wanted) forms.addForm(0, copyPrimary);
        forms.setEnabled(true);
        AttackFormLog.debug("editor-sync", "forms=" + existing + "->" + wanted);
        return true;
    }

    private static AttackAnimationStatus resolveAnimation(CustomEntity entity) {
        AnimCI animation = animation(entity);
        if (animation instanceof AnimCE) AttackAnimationStore.ensureForEditor((AnimCE) animation);
        return AttackAnimationStore.resolveForValidation(animation);
    }

    @SuppressWarnings("rawtypes")
    private static AnimCI animation(CustomEntity entity) {
        if (entity == null) return null;
        Animable owner = entity.getPack();
        Object value = owner == null ? null : owner.anim;
        return value instanceof AnimCI ? (AnimCI) value : null;
    }

    private static void invokeStockRefresh(State state) throws Exception {
        Method method = method(state.page.getClass(), "setData", CustomEntity.class);
        method.invoke(state.page, state.entity);
    }

    private static void restorePrimaryAndRefresh(final State state) throws Exception {
        final Throwable[] failure = new Throwable[1];
        Runnable restore = new Runnable() {
            @Override public void run() {
                try {
                    state.swapping = true;
                    try {
                        state.session.deactivate();
                        invokeStockRefresh(state);
                    } finally {
                        state.swapping = false;
                    }
                } catch (Throwable error) {
                    failure[0] = error;
                }
            }
        };
        if (SwingUtilities.isEventDispatchThread()) restore.run();
        else SwingUtilities.invokeAndWait(restore);
        if (failure[0] != null) {
            Throwable error = unwrap(failure[0]);
            if (error instanceof Exception) throw (Exception) error;
            if (error instanceof Error) throw (Error) error;
            throw new IllegalStateException(error);
        }
    }

    private static CustomEntity entity(Object page) throws Exception {
        Class<?> owner = namedSuperclass(page.getClass(), "page.info.edit.EntityEditPage");
        Field field = owner.getDeclaredField("ce");
        field.setAccessible(true);
        Object value = field.get(page);
        if (!(value instanceof CustomEntity))
            throw new IllegalStateException("EntityEditPage.ce is unavailable");
        return (CustomEntity) value;
    }

    private static boolean editable(Object page) throws Exception {
        Class<?> owner = namedSuperclass(page.getClass(), "page.info.edit.EntityEditPage");
        Field field = owner.getDeclaredField("editable");
        field.setAccessible(true);
        return field.getBoolean(page);
    }

    private static Class<?> namedSuperclass(Class<?> type, String name) {
        Class<?> cursor = type;
        while (cursor != null && !name.equals(cursor.getName())) cursor = cursor.getSuperclass();
        if (cursor == null) throw new IllegalStateException(name + " is not in the page hierarchy");
        return cursor;
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters)
            throws NoSuchMethodException {
        Class<?> cursor = type;
        while (cursor != null) {
            try {
                Method method = cursor.getDeclaredMethod(name, parameters);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                cursor = cursor.getSuperclass();
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }

    private static void place(State state, int width, int height) {

        Page.set(state.enabled, width, height, 350, 0, 300, 50);
        Page.set(state.selector, width, height, 650, 0, 200, 50);
        Page.set(state.rateLabel, width, height, 850, 0, 80, 50);
        Page.set(state.rate, width, height, 930, 0, 100, 50);
        Page.set(state.prevent, width, height, 1030, 0, 270, 50);
        Page.set(state.status, width, height, 1300, 0, 500, 50);
    }

    private static boolean confirmDiscard(State state) {
        if (GraphicsEnvironment.isHeadless()) return true;
        return JOptionPane.showConfirmDialog((Component) state.page,
                "Unticking Switch Attack Form permanently removes attack2..attack5 parameters "
                        + "from this entity. Animation timelines are not deleted. Continue?",
                "Switch Attack Form", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION;
    }

    private static int chooseInitialization(State state, int existing, int wanted) {
        if (GraphicsEnvironment.isHeadless()) return 0;
        Object[] choices = {"Copy Attack 1", "Blank", "Cancel"};
        int answer = JOptionPane.showOptionDialog((Component) state.page,
                "The animation has " + wanted + " attack timelines, but this entity has "
                        + existing + " parameter form" + (existing == 1 ? "" : "s") + ".\n"
                        + "How should the missing forms be initialized?",
                "Switch Attack Form", JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE, null, choices, choices[0]);
        return answer == 0 || answer == 1 ? answer : -1;
    }

    private static void show(State state, String message, int type) {
        if (!GraphicsEnvironment.isHeadless())
            JOptionPane.showMessageDialog((Component) state.page, message,
                    "Switch Attack Form", type);
    }

    private static boolean belongsTo(PackData.UserPack pack, CustomEntity entity) {
        if (pack == null || entity == null) return false;
        try {
            for (Unit unit : pack.units) {
                if (unit == null || unit.forms == null) continue;
                for (Form form : unit.forms) if (form != null && form.du == entity) return true;
            }
            for (Enemy enemy : pack.enemies)
                if (enemy != null && enemy.de == entity) return true;
        } catch (Throwable error) {
            AttackFormLog.error("could not match active editor to saving pack", error);
        }
        return false;
    }

    private static int savingDepth(CustomEntity entity) {
        synchronized (SAVING_PACKS) {
            int depth = 0;
            for (Map.Entry<PackData.UserPack, Integer> entry : SAVING_PACKS.entrySet()) {
                Integer value = entry.getValue();
                if (value != null && value > depth && belongsTo(entry.getKey(), entity))
                    depth = value;
            }
            return depth;
        }
    }

    private static void alignPackSessions(PackData.UserPack pack, int targetDepth)
            throws Exception {
        for (State state : snapshotStates()) {
            if (belongsTo(pack, state.entity)) alignSessionToSaveDepth(state, targetDepth);
        }
    }

    private static void alignSessionToSaveDepth(State state, int targetDepth) throws Exception {
        int before = state.session.saveSuspensionDepth();
        state.swapping = true;
        try {
            while (state.session.saveSuspensionDepth() < targetDepth)
                state.session.suspendForSave();
            while (state.session.saveSuspensionDepth() > targetDepth)
                state.session.resumeAfterSave();
            int after = state.session.saveSuspensionDepth();
            if ((before == 0) != (after == 0)) invokeStockRefresh(state);
        } finally {
            state.swapping = false;
        }
        updateControls(state);
    }

    private static void runOnEdtAndWait(final EdtAction action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
            return;
        }
        final Throwable[] failure = new Throwable[1];
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                try { action.run(); }
                catch (Throwable error) { failure[0] = error; }
            }
        });
        if (failure[0] == null) return;
        Throwable error = unwrap(failure[0]);
        if (error instanceof Exception) throw (Exception) error;
        if (error instanceof Error) throw (Error) error;
        throw new IllegalStateException(error);
    }

    private static ArrayList<State> snapshotStates() {
        synchronized (STATES) {
            ArrayList<State> result = new ArrayList<State>();
            for (WeakReference<State> reference : STATES.values()) {
                State state = reference == null ? null : reference.get();
                if (state != null) result.add(state);
            }
            return result;
        }
    }

    private static void refreshLater(final State state) {
        Runnable refresh = new Runnable() {
            @Override public void run() {
                if (stateFor(state.page) != state) return;
                try {
                    state.swapping = true;
                    try { invokeStockRefresh(state); }
                    finally { state.swapping = false; }
                    updateControls(state);
                } catch (Throwable error) {
                    fail(state.page, "Unit/Enemy Editor post-Save refresh", error);
                }
            }
        };
        if (SwingUtilities.isEventDispatchThread()) refresh.run();
        else SwingUtilities.invokeLater(refresh);
    }

    private static void fail(Object page, String where, Throwable error) {
        try { teardown(page, true); }
        catch (Throwable restoreError) {
            AttackFormLog.error("could not restore stock editor after Part 3 failure", restoreError);
        }
        AttackFormFeature.disable(where, unwrap(error));
    }

    private static void teardownAll() {
        for (State state : snapshotStates()) teardown(state.page, true);
    }

    private static void teardown(Object page, boolean restorePrimary) {
        WeakReference<State> reference = STATES.remove(page);
        State state = reference == null ? null : reference.get();
        if (state == null) return;
        if (restorePrimary) {
            try { restorePrimaryAndRefresh(state); }
            catch (Throwable error) {
                AttackFormLog.error("could not restore attack1 while removing editor controls", error);
            }
        }
        if (page instanceof Container) {
            Container container = (Container) page;
            container.remove(state.enabled);
            container.remove(state.selector);
            container.remove(state.rateLabel);
            container.remove(state.rate);
            container.remove(state.prevent);
            container.remove(state.status);
            container.revalidate();
            container.repaint();
        }
    }

    private static Throwable unwrap(Throwable error) {
        if (error instanceof InvocationTargetException
                && ((InvocationTargetException) error).getCause() != null)
            return ((InvocationTargetException) error).getCause();
        return error;
    }

    private static State stateFor(Object page) {
        if (page == null) return null;
        synchronized (STATES) {
            WeakReference<State> reference = STATES.get(page);
            State state = reference == null ? null : reference.get();
            if (reference != null && state == null) STATES.remove(page);
            return state;
        }
    }

    private interface EdtAction {
        void run() throws Exception;
    }

    private static final class State {
        final Object page;
        final CustomEntity entity;
        final boolean editable;
        final AttackFormEditorSession session;
        JCheckBox enabled;
        JComboBox<String> selector;
        JLabel rateLabel;
        JTF rate;
        JCheckBox prevent;
        JLabel status;
        boolean updating;
        boolean swapping;
        boolean syncAvailable;

        State(Object page, CustomEntity entity, boolean editable) {
            this.page = page;
            this.entity = entity;
            this.editable = editable;
            this.session = AttackFormEditorSession.open(entity);
        }
    }
}
