package manualcontrol.attackform.editor;

import common.battle.data.CustomEntity;
import manualcontrol.attackform.data.AttackFormData;
import manualcontrol.attackform.data.AttackFormSet;
import manualcontrol.attackform.data.AttackFormStore;

public final class AttackFormEditorSession {

    private final CustomEntity entity;
    private AttackFormSet forms;
    private int selected;

    private boolean active = true;
    private int saveDepth;

    private AttackFormEditorSession(CustomEntity entity) {
        if (entity == null) throw new IllegalArgumentException("entity is required");
        this.entity = entity;
        this.forms = AttackFormStore.get(entity);
    }

    public static AttackFormEditorSession open(CustomEntity entity) {
        return new AttackFormEditorSession(entity);
    }

    public synchronized CustomEntity entity() {
        return entity;
    }

    public synchronized AttackFormSet forms() {
        return forms;
    }

    public synchronized AttackFormSet ensureForms() {
        if (forms == null) forms = AttackFormStore.ensure(entity);
        return forms;
    }

    public synchronized int selectedIndex() {
        return selected;
    }

    public synchronized boolean isActive() {
        return active && saveDepth == 0;
    }

    public synchronized boolean isSaveSuspended() {
        return saveDepth > 0;
    }

    public synchronized int saveSuspensionDepth() {
        return saveDepth;
    }

    public synchronized void captureVisible() {
        if (!active || saveDepth > 0 || forms == null) return;
        int count = forms.formCount();
        if (count < 1) return;
        selected = clamp(selected, count);
        forms.replaceForm(selected, AttackFormData.capture(entity, selected));
    }

    public synchronized void select(int index) {
        if (!active || saveDepth > 0)
            throw new IllegalStateException("editor session is not active");
        AttackFormSet set = ensureForms();
        int count = set.formCount();
        if (index < 0 || index >= count) throw new IndexOutOfBoundsException("form index=" + index);
        captureVisible();
        set.formCopy(index).applyTo(entity);
        selected = index;
    }

    public synchronized void deactivate() {
        if (!active) return;
        if (saveDepth > 0) {

            active = false;
            return;
        }
        captureVisible();
        applyPrimary();
        active = false;
    }

    public synchronized void reactivate() {
        forms = AttackFormStore.get(entity);
        active = true;

        if (saveDepth > 0) return;
        if (forms == null) {
            selected = 0;
            return;
        }
        selected = clamp(selected, forms.formCount());
        forms.formCopy(selected).applyTo(entity);
    }

    public synchronized void suspendForSave() {
        if (saveDepth > 0) {
            saveDepth++;
            return;
        }
        if (active) captureVisible();

        applyPrimary();
        saveDepth = 1;
    }

    public synchronized void resumeAfterSave() {
        if (saveDepth <= 0) return;
        if (--saveDepth > 0) return;
        forms = AttackFormStore.get(entity);
        if (forms == null) {
            selected = 0;
            return;
        }
        selected = clamp(selected, forms.formCount());
        if (active) forms.formCopy(selected).applyTo(entity);
    }

    public synchronized void discardSecondaryForms() {
        if (saveDepth > 0)
            throw new IllegalStateException("cannot discard attack forms during Save");
        AttackFormSet set = ensureForms();
        captureVisible();
        AttackFormData primary = set.formCopy(0);
        set.disableAndDiscardSecondaryForms();
        primary.applyTo(entity);
        selected = 0;
    }

    private void applyPrimary() {
        if (forms == null || forms.formCount() < 1) return;
        forms.formCopy(0).applyTo(entity);
    }

    private static int clamp(int index, int count) {
        if (count <= 0) return 0;
        return Math.max(0, Math.min(index, count - 1));
    }
}
