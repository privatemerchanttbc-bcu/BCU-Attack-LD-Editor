package manualcontrol.attackform.runtime;

import common.util.CopRand;
import manualcontrol.attackform.data.AttackFormSet;

public final class AttackSelectionState {

    private int selectedForm = -1;
    private int lastCompletedForm = -1;
    private boolean attackInProgress;
    private boolean damageCreated;

    public int selectIfNeeded(AttackFormSet forms, CopRand random) {
        if (selectedForm < 0)
            selectedForm = AttackFormSelector.select(forms, lastCompletedForm, random);
        return selectedForm;
    }

    public void beginAttack() {
        if (selectedForm < 0) throw new IllegalStateException("select a form before attack");
        if (attackInProgress) throw new IllegalStateException("attack is already in progress");
        attackInProgress = true;
        damageCreated = false;
    }

    public void markDamageCreated() {
        if (!attackInProgress) throw new IllegalStateException("attack is not in progress");
        damageCreated = true;
    }

    public void finishOrCancelAttack() {
        if (!attackInProgress) return;
        attackInProgress = false;
        if (damageCreated) {
            lastCompletedForm = selectedForm;
            selectedForm = -1;
        }
        damageCreated = false;
    }

    public int getSelectedForm() {
        return selectedForm;
    }

    public int getLastCompletedForm() {
        return lastCompletedForm;
    }

    public boolean isAttackInProgress() {
        return attackInProgress;
    }

    public boolean hasCreatedDamage() {
        return damageCreated;
    }

    public void reset() {
        selectedForm = -1;
        lastCompletedForm = -1;
        attackInProgress = false;
        damageCreated = false;
    }

    public AttackSelectionState deepCopy() {
        AttackSelectionState copy = new AttackSelectionState();
        copy.selectedForm = selectedForm;
        copy.lastCompletedForm = lastCompletedForm;
        copy.attackInProgress = attackInProgress;
        copy.damageCreated = damageCreated;
        return copy;
    }
}
