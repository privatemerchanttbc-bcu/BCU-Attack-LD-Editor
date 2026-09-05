package manualcontrol.attackform.runtime;

import common.util.CopRand;
import manualcontrol.attackform.AttackFormLog;
import manualcontrol.attackform.data.AttackFormData;
import manualcontrol.attackform.data.AttackFormSet;
import manualcontrol.attackform.data.AttackRateEngine;

import java.util.concurrent.atomic.AtomicBoolean;

public final class AttackFormSelector {

    private static final AtomicBoolean INVALID_DATA_LOGGED = new AtomicBoolean();

    private AttackFormSelector() {}

    public static int select(AttackFormSet set, int previousForm, CopRand random) {
        if (set == null || !set.enabled || set.forms == null || set.forms.length <= 1) return 0;
        if (random == null) throw new IllegalArgumentException("battle RNG is required");
        if (!validForms(set.forms)) {
            if (INVALID_DATA_LOGGED.compareAndSet(false, true))
                AttackFormLog.info("invalid runtime Rates; falling back to attack");
            return 0;
        }

        boolean exclude = set.preventConsecutive && previousForm >= 0
                && previousForm < set.forms.length;
        int total = exclude ? AttackRateEngine.TOTAL - set.forms[previousForm].rate
                : AttackRateEngine.TOTAL;
        float sample = random.nextFloat();  
        int roll;
        if (Float.isNaN(sample) || sample <= 0.0f) roll = 0;
        else if (sample >= 1.0f) roll = total - 1;
        else roll = Math.min(total - 1, (int) (sample * total));

        int cumulative = 0;
        for (int i = 0; i < set.forms.length; i++) {
            if (exclude && i == previousForm) continue;
            cumulative += set.forms[i].rate;
            if (roll < cumulative) return i;
        }
        return 0;
    }

    public static int selectWithRoll(int[] rates, boolean preventConsecutive,
                                     int previousForm, int roll) {
        if (!AttackRateEngine.isValid(rates))
            throw new IllegalArgumentException("Rates must be positive and total 100");
        int total = eligibleTotal(rates, preventConsecutive, previousForm);
        int cursor = Math.floorMod(roll, total);
        int cumulative = 0;
        for (int i = 0; i < rates.length; i++) {
            if (preventConsecutive && rates.length > 1 && i == previousForm) continue;
            cumulative += rates[i];
            if (cursor < cumulative) return i;
        }
        throw new IllegalStateException("weighted selection did not resolve");
    }

    private static int eligibleTotal(int[] rates, boolean preventConsecutive, int previousForm) {
        if (!AttackRateEngine.isValid(rates))
            throw new IllegalArgumentException("Rates must be positive and total 100");
        if (preventConsecutive && rates.length > 1
                && previousForm >= 0 && previousForm < rates.length)
            return AttackRateEngine.TOTAL - rates[previousForm];
        return AttackRateEngine.TOTAL;
    }

    private static boolean validForms(AttackFormData[] forms) {
        if (forms == null || forms.length < 1 || forms.length > AttackFormSet.MAX_FORMS)
            return false;
        int total = 0;
        for (AttackFormData form : forms) {
            if (form == null || form.rate < 1 || form.rate > AttackRateEngine.TOTAL)
                return false;
            total += form.rate;
        }
        return total == AttackRateEngine.TOTAL;
    }
}
