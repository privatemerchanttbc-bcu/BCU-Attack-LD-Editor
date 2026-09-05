package manualcontrol.attackform.data;

import java.util.Arrays;

public final class AttackRateEngine {

    public static final int TOTAL = 100;

    private AttackRateEngine() {}

    public static int[] even(int count) {
        requireCount(count);
        int[] rates = new int[count];
        int base = TOTAL / count;
        int remainder = TOTAL % count;
        for (int i = 0; i < count; i++) rates[i] = base + (i < remainder ? 1 : 0);
        return rates;
    }

    public static int[] edit(int[] current, int editedIndex, int requestedRate) {
        requireRates(current);
        if (editedIndex < 0 || editedIndex >= current.length)
            throw new IndexOutOfBoundsException("edited index=" + editedIndex);
        if (current.length == 1) return new int[]{TOTAL};

        int maximum = TOTAL - (current.length - 1);
        int selected = clamp(requestedRate, 1, maximum);
        int[] result = new int[current.length];
        result[editedIndex] = selected;

        int[] indexes = new int[current.length - 1];
        int[] weights = new int[current.length - 1];
        int p = 0;
        for (int i = 0; i < current.length; i++) {
            if (i == editedIndex) continue;
            indexes[p] = i;

            weights[p] = Math.max(0, current[i] - 1);
            p++;
        }
        int[] allocated = allocate(weights, TOTAL - selected, 1);
        for (int i = 0; i < indexes.length; i++) result[indexes[i]] = allocated[i];
        assertValid(result);
        return result;
    }

    public static int[] remove(int[] current, int removedIndex) {
        requireRates(current);
        if (current.length <= 1) throw new IllegalStateException("cannot remove the only form");
        if (removedIndex < 0 || removedIndex >= current.length)
            throw new IndexOutOfBoundsException("removed index=" + removedIndex);
        int[] weights = new int[current.length - 1];
        for (int i = 0, p = 0; i < current.length; i++)
            if (i != removedIndex) weights[p++] = Math.max(1, current[i]);
        int[] result = allocate(weights, TOTAL, 0);
        assertValid(result);
        return result;
    }

    public static boolean isValid(int[] rates) {
        if (rates == null || rates.length < 1 || rates.length > AttackFormSet.MAX_FORMS)
            return false;
        int sum = 0;
        for (int rate : rates) {
            if (rate < 1 || rate > TOTAL) return false;
            sum += rate;
        }
        return sum == TOTAL;
    }

    public static int[] repairOrEven(int[] rates, int count) {
        requireCount(count);
        if (rates != null && rates.length == count && isValid(rates)) return rates.clone();
        return even(count);
    }

    private static int[] allocate(int[] rawWeights, int total, int minimum) {
        if (rawWeights.length == 0) return new int[0];
        if (total < rawWeights.length * minimum)
            throw new IllegalArgumentException("total cannot satisfy positive Rates");

        int[] result = new int[rawWeights.length];
        Arrays.fill(result, minimum);
        int remaining = total - rawWeights.length * minimum;
        if (remaining == 0) return result;

        long weightSum = 0;
        for (int weight : rawWeights) weightSum += Math.max(0, weight);
        if (weightSum == 0) {
            int base = remaining / rawWeights.length;
            int extra = remaining % rawWeights.length;
            for (int i = 0; i < result.length; i++)
                result[i] += base + (i < extra ? 1 : 0);
            return result;
        }
        long[] remainders = new long[rawWeights.length];
        int used = 0;
        for (int i = 0; i < rawWeights.length; i++) {
            long numerator = (long) remaining * Math.max(0, rawWeights[i]);
            int share = (int) (numerator / weightSum);
            result[i] += share;
            used += share;
            remainders[i] = numerator % weightSum;
        }

        int left = remaining - used;
        while (left-- > 0) {
            int best = 0;
            for (int i = 1; i < remainders.length; i++)
                if (remainders[i] > remainders[best]) best = i;
            result[best]++;
            remainders[best] = -1;
        }
        return result;
    }

    private static void requireCount(int count) {
        if (count < 1 || count > AttackFormSet.MAX_FORMS)
            throw new IllegalArgumentException("form count must be 1.." + AttackFormSet.MAX_FORMS);
    }

    private static void requireRates(int[] rates) {
        if (rates == null || rates.length < 1 || rates.length > AttackFormSet.MAX_FORMS)
            throw new IllegalArgumentException("invalid Rate array");
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }

    private static void assertValid(int[] result) {
        if (!isValid(result)) throw new IllegalStateException("invalid Rate result: " + Arrays.toString(result));
    }
}
