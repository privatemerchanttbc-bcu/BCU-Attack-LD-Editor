package manualcontrol.attackform.animator;

import common.util.anim.AnimCE;
import common.util.anim.MaAnim;
import manualcontrol.attackform.AttackFormFeature;
import manualcontrol.attackform.AttackFormLog;
import page.Page;

import javax.swing.JButton;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

public final class AttackFormAnimatorHooks {

    private static final Map<Object, Controls> CONTROLS = Collections.synchronizedMap(
            new WeakHashMap<Object, Controls>());
    private static final Runnable DISABLE_LISTENER = new Runnable() {
        @Override
        public void run() {
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() { teardownAll(); }
            });
        }
    };
    private static boolean listenerRegistered;

    private AttackFormAnimatorHooks() {}

    public static boolean onLdMousePressed(Object page, Object event) {
        return AttackFormFeature.isReady() && AttackLdEditor.onMousePressed(page, event);
    }

    public static boolean onLdMouseDragged(Object page, Object event) {
        return AttackFormFeature.isReady() && AttackLdEditor.onMouseDragged(page, event);
    }

    public static boolean onLdMouseReleased(Object page, Object event) {
        return AttackFormFeature.isReady() && AttackLdEditor.onMouseReleased(page, event);
    }

    public static void onBuilt(Object page) {
        if (!AttackFormFeature.isReady() || !(page instanceof Container)) return;
        try {
            if (CONTROLS.containsKey(page)) return;
            if (!listenerRegistered) {
                AttackFormFeature.addDisableListener(DISABLE_LISTENER);
                listenerRegistered = true;
            }
            final Controls controls = new Controls();
            controls.add = new JButton("Add Attack");
            controls.delete = new JButton("Delete Attack");
            controls.add.setToolTipText("Create attack2..attack5 (blank or copied timeline)");
            controls.delete.setToolTipText("Delete the selected attack2..attack5");
            controls.add.addActionListener(event -> addAttack(page));
            controls.delete.addActionListener(event -> deleteAttack(page));

            button(page, "sort").addActionListener(event ->
                    SwingUtilities.invokeLater(() -> markCustomSort(page)));

            button(page, "advs").addActionListener(event -> prepareAdvanced(page));
            ((Container) page).add(controls.add);
            ((Container) page).add(controls.delete);
            CONTROLS.put(page, controls);
            AttackLdEditor.onBuilt(page);
            updateControls(page, null, -1);
            ((Container) page).revalidate();
            ((Container) page).repaint();
        } catch (Throwable error) {
            fail(page, "Animator control construction", error);
        }
    }

    public static void onResized(Object page, int width, int height) {
        if (!AttackFormFeature.isReady()) return;
        Controls controls = CONTROLS.get(page);
        if (controls == null) return;
        try {
            JScrollPane stockList = scroll(page);
            controls.stockListBounds = stockList.getBounds();

            Page.set(stockList, width, height, 0, 450, 300, 250);
            Page.set(controls.add, width, height, 0, 700, 160, 50);
            Page.set(controls.delete, width, height, 160, 700, 140, 50);
            AttackLdEditor.onResized(page, width, height);
        } catch (Throwable error) {
            fail(page, "Animator control layout", error);
        }
    }

    public static void beforeSetAnimation(Object page, AnimCE animation) {
        if (!AttackFormFeature.isReady()) return;
        try {
            Controls controls = controls(page);
            controls.selectionBeforeSet = list(page).getSelectedIndex();
            AnimCE previous = currentAnimation(page);
            controls.selectionExtrasBeforeSet = previous == null
                    ? 0 : AttackAnimationStore.extraCount(previous);
        } catch (Throwable error) {
            fail(page, "Animator selection capture", error);
        }
    }

    public static void afterSetAnimation(Object page, AnimCE animation) {
        if (!AttackFormFeature.isReady()) return;
        try {
            Controls controls = controls(page);
            if (animation == null) {
                controls.selectionBeforeSet = -1;
                controls.selectionExtrasBeforeSet = 0;
                updateControls(page, null, -1);
                return;
            }
            AttackAnimationStore.ensureForEditor(animation);
            int count = AttackAnimationStore.extraCount(animation);
            String[] names = AttackAnimationIndex.visibleNames(animation.names(), count);
            int selected = AttackAnimationIndex.remapVisible(controls.selectionBeforeSet,
                    controls.selectionExtrasBeforeSet, count);
            controls.selectionBeforeSet = -1;
            controls.selectionExtrasBeforeSet = 0;
            if (selected >= names.length) selected = names.length - 1;
            rebuildList(page, animation, names, selected);
        } catch (Throwable error) {
            fail(page, "Animator animation-list refresh", error);
        }
    }

    public static boolean selectAnimation(Object page, AnimCE animation, int visibleIndex) {
        if (!AttackFormFeature.isReady() || animation == null) return false;
        Controls controls = CONTROLS.get(page);
        if (controls == null || controls.bridging) return false;
        try {
            AttackAnimationStore.ensureForEditor(animation);
            int count = AttackAnimationStore.extraCount(animation);
            int slot = AttackAnimationIndex.visibleToAttackSlot(visibleIndex, count);
            int stockIndex = AttackAnimationIndex.visibleToStock(visibleIndex, count);
            AttackAnimationStore.setEditorSlot(animation, slot);
            boolean custom = slot > 0;
            boolean shiftedStock = slot < 0 && stockIndex != visibleIndex;
            if (!custom && !shiftedStock) {
                updateControls(page, animation, visibleIndex);
                return false;
            }

            controls.bridging = true;
            MaAnim original = null;
            if (custom) {
                if (animation.anims == null || animation.anims.length <= 2)
                    throw new IllegalStateException("stock attack timeline is unavailable");
                original = animation.anims[2];
                MaAnim selected = AttackAnimationStore.timeline(animation, slot);
                if (selected == null) throw new IllegalStateException("selected timeline is missing");
                animation.anims[2] = selected;
            }
            try {
                invokeSetB(page, animation, stockIndex);
            } finally {
                if (custom) animation.anims[2] = original;
                controls.bridging = false;
            }
            if (custom) button(page, "advs").setEnabled(false);
            updateControls(page, animation, visibleIndex);
            return true;
        } catch (Throwable error) {
            controls.bridging = false;
            return failSelection(page, animation, visibleIndex, error);
        }
    }

    public static boolean callBack(Object page, Object argument) {
        if (!AttackFormFeature.isReady()) return false;
        Controls controls = CONTROLS.get(page);
        if (controls == null || controls.bridging) return false;
        try {
            AnimCE animation = currentAnimation(page);
            if (animation == null) return false;
            int visible = list(page).getSelectedIndex();
            int count = AttackAnimationStore.extraCount(animation);
            int slot = AttackAnimationIndex.visibleToAttackSlot(visible, count);
            int stock = AttackAnimationIndex.visibleToStock(visible, count);
            AttackAnimationStore.setEditorSlot(animation, slot);
            if (slot <= 0 && stock == visible) return false;

            controls.bridging = true;
            MaAnim original = slot > 0 ? animation.anims[2] : null;
            MaAnim custom = slot > 0 ? AttackAnimationStore.timeline(animation, slot) : null;
            if (slot > 0 && custom == null)
                throw new IllegalStateException("selected timeline is missing");
            int oldAdjusting = beginAdjusting(page);
            try {
                list(page).setSelectedIndex(stock);

                invokeCallBack(page, argument);
                if (slot > 0) {
                    animation.anims[2] = custom;
                    try {
                        invokeSetB(page, animation, stock);
                    } finally {
                        animation.anims[2] = original;
                    }
                }
                list(page).setSelectedIndex(visible);
            } finally {
                endAdjusting(page, oldAdjusting);
                if (slot > 0) animation.anims[2] = original;
                controls.bridging = false;
            }
            if (slot > 0) button(page, "advs").setEnabled(false);
            return true;
        } catch (Throwable error) {
            controls.bridging = false;
            return failCallback(page, error);
        }
    }

    private static void addAttack(Object page) {
        if (!AttackFormFeature.isReady()) return;
        try {
            AnimCE animation = currentAnimation(page);
            if (animation == null) return;
            int count = AttackAnimationStore.extraCount(animation);
            if (count >= AttackAnimationIndex.MAX_EXTRA_ATTACKS) return;
            int choice = 0;
            if (!GraphicsEnvironment.isHeadless()) {
                Object[] choices = {"Blank", "Copy Current Attack", "Cancel"};
                choice = JOptionPane.showOptionDialog((java.awt.Component) page,
                        "How should attack" + (count + 2) + " be created?",
                        "Add Attack", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                        null, choices, choices[0]);
            }
            if (choice < 0 || choice == 2) return;
            int sourceSlot = -1;
            if (choice == 1) {
                sourceSlot = AttackAnimationIndex.visibleToAttackSlot(
                        list(page).getSelectedIndex(), count);
                if (sourceSlot < 0) sourceSlot = 0;
            }
            int newSlot = AttackAnimationStore.add(animation, sourceSlot);
            animation.unSave(sourceSlot < 0
                    ? "attack form add blank timeline" : "attack form copy timeline");
            refresh(page, animation, AttackAnimationIndex.attackSlotToVisible(newSlot));
            AttackFormLog.debug("animator-add", safeId(animation) + " attack=" + (newSlot + 1));
        } catch (Throwable error) {
            fail(page, "Add Attack", error);
        }
    }

    private static void deleteAttack(Object page) {
        if (!AttackFormFeature.isReady()) return;
        try {
            AnimCE animation = currentAnimation(page);
            if (animation == null) return;
            int count = AttackAnimationStore.extraCount(animation);
            int slot = AttackAnimationIndex.visibleToAttackSlot(
                    list(page).getSelectedIndex(), count);
            if (slot <= 0) return;
            if (AttackAnimationStore.isReferencedByCustomPack(animation)) {
                showMessage(page, "This attack cannot be deleted while its animation is used "
                        + "by a custom pack.", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (!GraphicsEnvironment.isHeadless()) {
                int answer = JOptionPane.showConfirmDialog((java.awt.Component) page,
                        "Delete attack" + (slot + 1) + "? Later attacks will shift down.",
                        "Delete Attack", JOptionPane.OK_CANCEL_OPTION,
                        JOptionPane.WARNING_MESSAGE);
                if (answer != JOptionPane.OK_OPTION) return;
            }
            AttackAnimationStore.remove(animation, slot);
            animation.unSave("attack form delete timeline");
            int remaining = AttackAnimationStore.extraCount(animation);
            int nextSlot = Math.min(slot, remaining);
            refresh(page, animation, AttackAnimationIndex.attackSlotToVisible(nextSlot));
            AttackFormLog.debug("animator-delete", safeId(animation)
                    + " removed=attack" + (slot + 1));
        } catch (Throwable error) {
            fail(page, "Delete Attack", error);
        }
    }

    private static void markCustomSort(Object page) {
        if (!AttackFormFeature.isReady()) return;
        try {
            AnimCE animation = currentAnimation(page);
            if (animation == null) return;
            int count = AttackAnimationStore.extraCount(animation);
            int slot = AttackAnimationIndex.visibleToAttackSlot(
                    list(page).getSelectedIndex(), count);
            if (slot > 0) animation.unSave("attack form sort timeline");
        } catch (Throwable error) {
            fail(page, "custom attack timeline sort", error);
        }
    }

    private static void prepareAdvanced(Object page) {
        if (!AttackFormFeature.isReady()) return;
        Controls controls = CONTROLS.get(page);
        if (controls == null || controls.advancedVisible >= 0) return;
        try {
            AnimCE animation = currentAnimation(page);
            if (animation == null) return;
            int visible = list(page).getSelectedIndex();
            int count = AttackAnimationStore.extraCount(animation);
            int slot = AttackAnimationIndex.visibleToAttackSlot(visible, count);
            int stock = AttackAnimationIndex.visibleToStock(visible, count);
            if (slot > 0 || stock == visible) return;
            controls.advancedVisible = visible;
            controls.advancedPreviousAdjusting = beginAdjusting(page);
            list(page).setSelectedIndex(stock);
            SwingUtilities.invokeLater(() -> restoreAdvanced(page));
        } catch (Throwable error) {
            restoreAdvanced(page);
            fail(page, "Advanced animation index mapping", error);
        }
    }

    private static void restoreAdvanced(Object page) {
        Controls controls = CONTROLS.get(page);
        if (controls == null || controls.advancedVisible < 0) return;
        int visible = controls.advancedVisible;
        int previous = controls.advancedPreviousAdjusting;
        controls.advancedVisible = -1;
        try {
            list(page).setSelectedIndex(visible);
            endAdjusting(page, previous);
        } catch (Throwable error) {
            fail(page, "Advanced animation selection restore", error);
        }
    }

    private static void refresh(Object page, AnimCE animation, int selection) throws Exception {
        int count = AttackAnimationStore.extraCount(animation);
        rebuildList(page, animation,
                AttackAnimationIndex.visibleNames(animation.names(), count), selection);
    }

    private static void rebuildList(Object page, AnimCE animation, String[] names, int selection)
            throws Exception {
        JList<String> list = list(page);
        int oldAdjusting = beginAdjusting(page);
        try {
            list.setListData(names);
            if (selection >= 0 && selection < names.length) list.setSelectedIndex(selection);
            else list.clearSelection();
        } finally {
            endAdjusting(page, oldAdjusting);
        }
        if (selection >= 0 && selection < names.length)
            invokeSetB(page, animation, selection);
        updateControls(page, animation, selection);
    }

    private static void updateControls(Object page, AnimCE animation, int visibleIndex)
            throws Exception {
        Controls controls = CONTROLS.get(page);
        if (controls == null) return;
        int count = animation == null ? 0 : AttackAnimationStore.extraCount(animation);
        int slot = animation == null ? -1
                : AttackAnimationIndex.visibleToAttackSlot(visibleIndex, count);
        boolean eligible = animation != null && animation.id != null
                && animation.id.base == common.pack.Source.BasePath.ANIM
                && animation.anims != null && animation.anims.length > 2;
        controls.add.setEnabled(eligible
                && count < AttackAnimationIndex.MAX_EXTRA_ATTACKS);

        controls.delete.setEnabled(animation != null && slot > 0);
        AttackLdEditor.onSelection(page, animation, slot);
    }

    private static boolean failSelection(Object page, AnimCE animation, int visible,
                                         Throwable error) {
        fail(page, "Animator timeline selection", error);
        return true;
    }

    private static boolean failCallback(Object page, Throwable error) {
        fail(page, "Animator callback mapping", error);
        return true;
    }

    private static void fail(Object page, String where, Throwable error) {
        teardownAll();
        AttackFormFeature.disable(where, unwrap(error));
    }

    private static void teardownAll() {
        Object[] pages;
        synchronized (CONTROLS) {
            pages = CONTROLS.keySet().toArray();
        }
        for (Object page : pages) teardown(page);
    }

    private static void teardown(Object page) {
        Controls controls = CONTROLS.remove(page);
        if (controls == null || !(page instanceof Container)) return;
        AttackLdEditor.teardown(page);
        try {
            if (controls.advancedVisible >= 0) {
                int visible = controls.advancedVisible;
                int previous = controls.advancedPreviousAdjusting;
                controls.advancedVisible = -1;
                list(page).setSelectedIndex(visible);
                endAdjusting(page, previous);
            }
            AnimCE animation = currentAnimation(page);
            if (animation != null) {
                int visible = list(page).getSelectedIndex();
                int count = AttackAnimationStore.extraCount(animation);
                int stock = AttackAnimationIndex.visibleToStock(visible, count);
                int safe = Math.max(-1, Math.min(stock, animation.names().length - 1));
                int old = beginAdjusting(page);
                try {
                    list(page).setListData(animation.names());
                    if (safe >= 0) list(page).setSelectedIndex(safe);
                    else list(page).clearSelection();
                } finally {
                    endAdjusting(page, old);
                }
                if (safe >= 0) invokeSetB(page, animation, safe);
            }
            if (controls.stockListBounds != null)
                scroll(page).setBounds(controls.stockListBounds);
        } catch (Throwable restoreError) {
            AttackFormLog.error("could not fully restore stock Animator UI", restoreError);
        }
        ((Container) page).remove(controls.add);
        ((Container) page).remove(controls.delete);
        ((Container) page).revalidate();
        ((Container) page).repaint();
    }

    private static Throwable unwrap(Throwable error) {
        if (error instanceof InvocationTargetException
                && ((InvocationTargetException) error).getCause() != null)
            return ((InvocationTargetException) error).getCause();
        return error;
    }

    @SuppressWarnings("unchecked")
    private static JList<String> list(Object page) throws Exception {
        return (JList<String>) field(page.getClass(), "jlt").get(page);
    }

    private static JButton button(Object page, String name) throws Exception {
        return (JButton) field(page.getClass(), name).get(page);
    }

    private static JScrollPane scroll(Object page) throws Exception {
        return (JScrollPane) field(page.getClass(), "jspt").get(page);
    }

    private static AnimCE currentAnimation(Object page) throws Exception {
        Object table = field(page.getClass(), "maet").get(page);
        Object value = field(table.getClass(), "anim").get(table);
        return value instanceof AnimCE ? (AnimCE) value : null;
    }

    private static int beginAdjusting(Object page) throws Exception {
        Field adjusting = field(Page.class, "adjusting");
        int previous = adjusting.getInt(page);
        adjusting.setInt(page, previous + 1);
        return previous;
    }

    private static void endAdjusting(Object page, int previous) throws Exception {
        field(Page.class, "adjusting").setInt(page, previous);
    }

    private static void invokeSetB(Object page, AnimCE animation, int index) throws Exception {
        Method method = page.getClass().getDeclaredMethod("setB", AnimCE.class, int.class);
        method.setAccessible(true);
        method.invoke(page, animation, index);
    }

    private static void invokeCallBack(Object page, Object argument) throws Exception {
        Method method = page.getClass().getMethod("callBack", Object.class);
        method.invoke(page, argument);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Class<?> cursor = type;
        while (cursor != null) {
            try {
                Field field = cursor.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                cursor = cursor.getSuperclass();
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    private static Controls controls(Object page) {
        Controls controls = CONTROLS.get(page);
        if (controls == null) throw new IllegalStateException("Animator controls are unavailable");
        return controls;
    }

    private static void showMessage(Object page, String message, int type) {
        if (!GraphicsEnvironment.isHeadless())
            JOptionPane.showMessageDialog((java.awt.Component) page, message,
                    "Switch Attack Form", type);
    }

    private static String safeId(AnimCE animation) {
        try { return animation == null || animation.id == null ? "unknown" : animation.id.toString(); }
        catch (Throwable ignored) { return "unknown"; }
    }

    private static final class Controls {
        JButton add;
        JButton delete;
        int selectionBeforeSet = -1;
        int selectionExtrasBeforeSet;
        boolean bridging;
        Rectangle stockListBounds;
        int advancedVisible = -1;
        int advancedPreviousAdjusting;
    }
}
