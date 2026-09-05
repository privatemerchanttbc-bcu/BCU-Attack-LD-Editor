package manualcontrol.attackform.data;

import common.battle.data.CustomEntity;
import common.io.json.JsonClass;

import java.util.Arrays;

@JsonClass(noTag = JsonClass.NoTag.LOAD)
public final class AttackFormSet {

    public static final int MAX_FORMS = 5;

    public boolean enabled;
    public boolean preventConsecutive;
    public AttackFormData[] forms = {new AttackFormData()};

    public AttackFormSet() {}

    public static AttackFormSet capture(CustomEntity entity) {
        AttackFormSet set = new AttackFormSet();
        set.forms = new AttackFormData[]{AttackFormData.capture(entity, 0)};
        set.forms[0].rate = 100;
        return set;
    }

    public AttackFormSet deepCopy() {
        AttackFormSet copy = new AttackFormSet();
        copy.enabled = enabled;
        copy.preventConsecutive = preventConsecutive;
        copy.forms = new AttackFormData[forms == null ? 0 : forms.length];
        for (int i = 0; i < copy.forms.length; i++)
            copy.forms[i] = forms[i] == null ? AttackFormData.blank(i) : forms[i].deepCopy();
        copy.repair();
        return copy;
    }

    public synchronized int formCount() {
        return forms == null ? 0 : forms.length;
    }

    public synchronized AttackFormData formCopy(int index) {
        if (forms == null || index < 0 || index >= forms.length)
            throw new IndexOutOfBoundsException("form index=" + index);
        if (forms[index] == null)
            throw new IllegalStateException("attack " + (index + 1) + " is missing");
        return forms[index].deepCopy();
    }

    public synchronized int hitCount(int formIndex) {
        requireHitForm(formIndex);
        return forms[formIndex].hits.length;
    }

    public synchronized int[] ldPoints(int formIndex, int hitIndex) {
        AttackFormHitData hit = requireHit(formIndex, hitIndex);
        return new int[]{hit.ldNear, hit.ldFar};
    }

    public synchronized int[] setLdNear(int formIndex, int hitIndex, int near) {
        AttackFormHitData hit = requireHit(formIndex, hitIndex);
        if (near == Integer.MAX_VALUE
                && (hit.ldFar <= near) && (near != 0 || hit.ldFar != 0))
            throw new IllegalArgumentException("LD Near is too large");
        hit.ldNear = near;
        if ((hit.ldNear != 0 || hit.ldFar != 0) && hit.ldFar <= near) {
            hit.ldFar = near + 1;
        }
        return new int[]{hit.ldNear, hit.ldFar};
    }

    public synchronized int[] setLdFar(int formIndex, int hitIndex, int far) {
        AttackFormHitData hit = requireHit(formIndex, hitIndex);
        if (far == Integer.MIN_VALUE
                && (hit.ldNear >= far) && (hit.ldNear != 0 || far != 0))
            throw new IllegalArgumentException("LD Far is too small");
        hit.ldFar = far;
        if ((hit.ldNear != 0 || hit.ldFar != 0) && hit.ldNear >= far) {
            hit.ldNear = far - 1;
        }
        return new int[]{hit.ldNear, hit.ldFar};
    }

    public synchronized void replaceForm(int index, AttackFormData value) {
        if (forms == null || index < 0 || index >= forms.length)
            throw new IndexOutOfBoundsException("form index=" + index);
        if (value == null) throw new IllegalArgumentException("replacement form is required");
        int retainedRate = forms[index] == null ? 0 : forms[index].rate;
        AttackFormData replacement = value.deepCopy();
        replacement.rate = retainedRate;
        replacement.animation = AttackFormData.animationName(index);
        replacement.repair(index);
        forms[index] = replacement;
    }

    public synchronized void setEnabled(boolean value) {
        repair();
        if (!value) {
            disableAndDiscardSecondaryForms();
            return;
        }
        if (forms.length <= 1)
            throw new IllegalStateException("Switch Attack Form requires attack2");
        enabled = true;
    }

    public synchronized boolean isEnabled() {
        repair();
        return enabled;
    }

    public synchronized void setPreventConsecutive(boolean value) {
        repair();
        if (value && (!enabled || forms.length <= 1))
            throw new IllegalStateException("no-repeat requires enabled multiple attack forms");
        preventConsecutive = value;
    }

    public synchronized boolean isPreventConsecutive() {
        repair();
        return preventConsecutive;
    }

    public synchronized void setRatesExact(int[] requestedRates) {
        if (forms == null || requestedRates == null || requestedRates.length != forms.length)
            throw new IllegalArgumentException("Rate/form count mismatch");
        if (!AttackRateEngine.isValid(requestedRates))
            throw new IllegalArgumentException("Rates must be 1..100 and total 100%");
        applyRates(requestedRates.clone());
    }

    public synchronized int addForm(int sourceIndex, boolean copySource) {
        repair();
        if (forms.length >= MAX_FORMS) throw new IllegalStateException("maximum attack5 reached");
        if (sourceIndex < 0 || sourceIndex >= forms.length)
            throw new IndexOutOfBoundsException("source index=" + sourceIndex);

        int newIndex = forms.length;
        AttackFormData added = copySource ? forms[sourceIndex].deepCopy() : AttackFormData.blank(newIndex);
        AttackFormData[] next = Arrays.copyOf(forms, newIndex + 1);
        next[newIndex] = added;
        forms = next;
        enabled = true;
        applyRates(AttackRateEngine.even(forms.length));
        renameAnimations();
        return newIndex;
    }

    public synchronized void removeForm(int index) {
        repair();
        if (index <= 0 || index >= forms.length)
            throw new IllegalArgumentException("only attack2..attack5 can be removed");
        int[] nextRates = AttackRateEngine.remove(rates(), index);
        AttackFormData[] next = new AttackFormData[forms.length - 1];
        System.arraycopy(forms, 0, next, 0, index);
        System.arraycopy(forms, index + 1, next, index, forms.length - index - 1);
        forms = next;
        applyRates(nextRates);
        renameAnimations();
        if (forms.length == 1) {
            enabled = false;
            preventConsecutive = false;
        }
    }

    public synchronized void setRate(int index, int requestedRate) {
        repair();
        if (index < 0 || index >= forms.length) throw new IndexOutOfBoundsException("index=" + index);
        applyRates(AttackRateEngine.edit(rates(), index, requestedRate));
    }

    public synchronized void disableAndDiscardSecondaryForms() {
        repair();
        AttackFormData first = forms[0].deepCopy();
        first.rate = 100;
        first.animation = "attack";
        forms = new AttackFormData[]{first};
        enabled = false;
        preventConsecutive = false;
    }

    public synchronized int[] rates() {
        if (forms == null) return new int[0];
        int[] rates = new int[forms.length];
        for (int i = 0; i < forms.length; i++)
            rates[i] = forms[i] == null ? 0 : forms[i].rate;
        return rates;
    }

    public synchronized AttackFormValidation validateForSave() {
        AttackFormValidation structure = validateStructure();
        if (!structure.valid) return structure;
        int[] strictRates = new int[forms.length];
        for (int i = 0; i < forms.length; i++) strictRates[i] = forms[i].rate;
        if (!AttackRateEngine.isValid(strictRates))
            return AttackFormValidation.error("Attack form Rates must be 1..100 and total 100%");
        return AttackFormValidation.ok();
    }

    public synchronized AttackFormValidation validateStructureForLoad() {
        return validateStructure();
    }

    private AttackFormValidation validateStructure() {
        if (forms == null || forms.length < 1 || forms.length > MAX_FORMS)
            return AttackFormValidation.error("Attack form count must be 1..5");
        if (!enabled && forms.length > 1)
            return AttackFormValidation.error("Disabled Switch Attack Form cannot keep secondary forms");
        for (int i = 0; i < forms.length; i++) {
            if (forms[i] == null) return AttackFormValidation.error("Attack " + (i + 1) + " is missing");
            if (!AttackFormData.animationName(i).equals(forms[i].animation))
                return AttackFormValidation.error("Animation names must be attack..attack5");
            if (forms[i].commonProc == null)
                return AttackFormValidation.error("Attack " + (i + 1)
                        + " has no common proc data");
            if (forms[i].hits == null || forms[i].hits.length == 0)
                return AttackFormValidation.error("Attack " + (i + 1) + " has no hit data");
            for (int hit = 0; hit < forms[i].hits.length; hit++) {
                if (forms[i].hits[hit] == null)
                    return AttackFormValidation.error("Attack " + (i + 1)
                            + " hit " + (hit + 1) + " is missing");
                if (forms[i].hits[hit].name == null)
                    return AttackFormValidation.error("Attack " + (i + 1)
                            + " hit " + (hit + 1) + " has no name value");
                if (forms[i].hits[hit].proc == null)
                    return AttackFormValidation.error("Attack " + (i + 1)
                            + " hit " + (hit + 1) + " has no proc data");
            }
        }
        return AttackFormValidation.ok();
    }

    public synchronized boolean repairRatesOnly() {
        if (!validateStructure().valid)
            throw new IllegalStateException("cannot repair Rates in malformed attack-form data");
        int[] before = rates();
        int[] repaired = AttackRateEngine.repairOrEven(before, forms.length);
        if (Arrays.equals(before, repaired)) return false;
        applyRates(repaired);
        return true;
    }

    public synchronized boolean repair() {
        boolean changed = repairStructureOnly();
        int[] before = rates();
        int[] repaired = AttackRateEngine.repairOrEven(before, forms.length);
        if (!Arrays.equals(before, repaired)) {
            applyRates(repaired);
            changed = true;
        }
        if (forms.length > 1 && !enabled) {

            enabled = true;
            changed = true;
        }
        if (forms.length <= 1) {
            if (enabled) {
                enabled = false;
                changed = true;
            }
            if (preventConsecutive) {
                preventConsecutive = false;
                changed = true;
            }
        }
        return changed;
    }

    private boolean repairStructureOnly() {
        boolean changed = false;
        if (forms == null || forms.length == 0) {
            forms = new AttackFormData[]{new AttackFormData()};
            changed = true;
        } else if (forms.length > MAX_FORMS) {
            forms = Arrays.copyOf(forms, MAX_FORMS);
            changed = true;
        }
        for (int i = 0; i < forms.length; i++) {
            if (forms[i] == null) {
                forms[i] = AttackFormData.blank(i);
                changed = true;
            }
            changed |= forms[i].repair(i);
        }
        return changed;
    }

    public synchronized void synchronizePrimary(CustomEntity entity) {
        repair();
        int primaryRate = forms[0].rate;
        forms[0] = AttackFormData.capture(entity, 0);
        forms[0].rate = primaryRate;
    }

    public synchronized boolean isMeaningful() {
        return enabled && forms != null && forms.length > 1;
    }

    private void applyRates(int[] rates) {
        if (rates.length != forms.length) throw new IllegalArgumentException("Rate/form count mismatch");
        for (int i = 0; i < forms.length; i++) forms[i].rate = rates[i];
    }

    private void renameAnimations() {
        for (int i = 0; i < forms.length; i++) forms[i].animation = AttackFormData.animationName(i);
    }

    private void requireHitForm(int formIndex) {
        if (forms == null || formIndex < 0 || formIndex >= forms.length)
            throw new IndexOutOfBoundsException("form index=" + formIndex);
        if (forms[formIndex] == null || forms[formIndex].hits == null
                || forms[formIndex].hits.length == 0)
            throw new IllegalStateException("attack " + (formIndex + 1) + " has no hit data");
    }

    private AttackFormHitData requireHit(int formIndex, int hitIndex) {
        requireHitForm(formIndex);
        if (hitIndex < 0 || hitIndex >= forms[formIndex].hits.length)
            throw new IndexOutOfBoundsException("hit index=" + hitIndex);
        AttackFormHitData hit = forms[formIndex].hits[hitIndex];
        if (hit == null)
            throw new IllegalStateException("attack " + (formIndex + 1)
                    + " hit " + (hitIndex + 1) + " is missing");
        return hit;
    }
}
