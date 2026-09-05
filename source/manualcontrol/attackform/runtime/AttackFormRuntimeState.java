package manualcontrol.attackform.runtime;

import common.battle.attack.AtkModelEntity;
import common.battle.data.MaskEntity;
import common.util.BattleObj;
import common.util.CopRand;
import common.util.anim.MaAnim;
import manualcontrol.attackform.data.AttackFormSet;
import manualcontrol.attackform.data.AttackRateEngine;

public final class AttackFormRuntimeState extends BattleObj {

    private final boolean preventConsecutive;
    private final int[] rates;

    private final MaskEntity[] formData;
    private final AtkModelEntity[] formModels;
    private final MaAnim[] formTimelines;

    private final MaskEntity stockData;
    private final AtkModelEntity stockModel;
    private final int[] stockPres;

    private final int[][] pres;
    private final int[] attacksRemaining;

    private int selectedForm = -1;
    private int activeForm;
    private int lastCompletedForm = -1;
    private boolean attackInProgress;
    private boolean damageCreated;

    public AttackFormRuntimeState(boolean preventConsecutive, int[] rates,
                                  MaskEntity[] formData,
                                  AtkModelEntity[] formModels,
                                  MaAnim[] formTimelines) {
        this(preventConsecutive, rates, formData, formModels, formTimelines,
                extractPreTimes(formData), extractAttackLoops(formData),
                first(formData), first(formModels));
    }

    public AttackFormRuntimeState(boolean preventConsecutive, int[] rates,
                                  MaskEntity[] formData,
                                  AtkModelEntity[] formModels,
                                  MaAnim[] formTimelines,
                                  MaskEntity stockData,
                                  AtkModelEntity stockModel) {
        this(preventConsecutive, rates, formData, formModels, formTimelines,
                extractPreTimes(formData), extractAttackLoops(formData),
                stockData, stockModel);
    }

    public AttackFormRuntimeState(boolean preventConsecutive, int[] rates,
                                  MaskEntity[] formData,
                                  AtkModelEntity[] formModels,
                                  MaAnim[] formTimelines,
                                  int[][] pres,
                                  int[] attacksRemaining) {
        this(preventConsecutive, rates, formData, formModels, formTimelines,
                pres, attacksRemaining, first(formData), first(formModels));
    }

    private AttackFormRuntimeState(boolean preventConsecutive, int[] rates,
                                   MaskEntity[] formData,
                                   AtkModelEntity[] formModels,
                                   MaAnim[] formTimelines,
                                   int[][] pres,
                                   int[] attacksRemaining,
                                   MaskEntity stockData,
                                   AtkModelEntity stockModel) {
        int count = validateMappings(rates, formData, formModels, formTimelines,
                pres, attacksRemaining);
        if (preventConsecutive && count <= 1)
            throw new IllegalArgumentException("Prevent Consecutive requires multiple forms");
        if (stockData == null || stockModel == null)
            throw new IllegalArgumentException("stock data/model pair is missing");

        this.preventConsecutive = preventConsecutive;
        this.rates = rates.clone();
        this.formData = formData.clone();
        this.formModels = formModels.clone();
        this.formTimelines = formTimelines.clone();
        this.pres = cloneRows(pres);
        this.attacksRemaining = attacksRemaining.clone();
        this.stockData = stockData;
        this.stockModel = stockModel;

        this.stockPres = pres[0].clone();
    }

    public int formCount() {
        return rates.length;
    }

    public boolean isPreventConsecutive() {
        return preventConsecutive;
    }

    public int rate(int form) {
        requireForm(form);
        return rates[form];
    }

    public int selectIfNeeded(CopRand random) {
        if (selectedForm >= 0) return selectedForm;
        if (rates.length == 1) {
            selectedForm = 0;
            return 0;
        }
        if (random == null) throw new IllegalArgumentException("battle RNG is required");

        boolean exclude = preventConsecutive && lastCompletedForm >= 0
                && lastCompletedForm < rates.length;
        int eligibleTotal = exclude
                ? AttackRateEngine.TOTAL - rates[lastCompletedForm]
                : AttackRateEngine.TOTAL;
        float sample = random.nextFloat();
        int roll;
        if (Float.isNaN(sample) || sample <= 0.0f) roll = 0;
        else if (sample >= 1.0f) roll = eligibleTotal - 1;
        else roll = Math.min(eligibleTotal - 1, (int) (sample * eligibleTotal));

        int cumulative = 0;
        for (int form = 0; form < rates.length; form++) {
            if (exclude && form == lastCompletedForm) continue;
            cumulative += rates[form];
            if (roll < cumulative) {
                selectedForm = form;
                return form;
            }
        }

        selectedForm = 0;
        return 0;
    }

    public int activateSelected() {
        if (selectedForm < 0)
            throw new IllegalStateException("select a form before activation");
        activeForm = selectedForm;
        return activeForm;
    }

    public void beginAttack() {
        if (selectedForm < 0)
            throw new IllegalStateException("select a form before attack");
        if (attackInProgress)
            throw new IllegalStateException("attack is already in progress");
        activeForm = selectedForm;
        attackInProgress = true;
        damageCreated = false;
    }

    public boolean markDamageCreated() {
        if (!attackInProgress)
            throw new IllegalStateException("attack is not in progress");
        boolean first = !damageCreated;
        damageCreated = true;
        return first;
    }

    public boolean finishOrCancelAttack() {
        if (!attackInProgress) return false;
        attackInProgress = false;
        boolean rotate = damageCreated;
        if (rotate) {
            lastCompletedForm = selectedForm;
            selectedForm = -1;
        }
        damageCreated = false;
        return rotate;
    }

    public int getSelectedForm() {
        return selectedForm;
    }

    public int getActiveForm() {
        return activeForm;
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

    public MaskEntity data(int form) {
        requireForm(form);
        return formData[form];
    }

    public AtkModelEntity model(int form) {
        requireForm(form);
        return formModels[form];
    }

    public MaAnim timeline(int form) {
        requireForm(form);
        return formTimelines[form];
    }

    public MaskEntity selectedData() {
        return data(requireSelected());
    }

    public AtkModelEntity selectedModel() {
        return model(requireSelected());
    }

    public MaAnim selectedTimeline() {
        return timeline(requireSelected());
    }

    public MaskEntity activeData() {
        return data(activeForm);
    }

    public AtkModelEntity activeModel() {
        return model(activeForm);
    }

    public MaAnim activeTimeline() {
        return timeline(activeForm);
    }

    public MaskEntity stockData() {
        return stockData;
    }

    public AtkModelEntity stockModel() {
        return stockModel;
    }

    public int[] stockPreSchedule() {
        return stockPres;
    }

    public int stockAttacksRemaining() {
        return attacksRemaining[0];
    }

    public boolean ownsModel(Object value) {
        if (value == null) return false;
        for (AtkModelEntity model : formModels)
            if (model == value) return true;
        return false;
    }

    public boolean isSelectedModel(Object value) {
        return selectedForm >= 0 && formModels[selectedForm] == value;
    }

    public int hitCount(int form) {
        requireForm(form);
        return pres[form].length;
    }

    public int pre(int form, int hit) {
        requireForm(form);
        if (hit < 0 || hit >= pres[form].length)
            throw new IndexOutOfBoundsException("hit=" + hit);
        return pres[form][hit];
    }

    public int selectedHitCount() {
        return hitCount(requireSelected());
    }

    public int selectedPre(int hit) {
        return pre(requireSelected(), hit);
    }

    public int[] selectedPreSchedule() {
        return pres[requireSelected()];
    }

    public int attacksRemaining(int form) {
        requireForm(form);
        return attacksRemaining[form];
    }

    public int selectedAttacksRemaining() {
        return attacksRemaining(requireSelected());
    }

    public void setAttacksRemaining(int form, int value) {
        requireForm(form);
        attacksRemaining[form] = value;
    }

    public int decrementSelectedAttacksRemaining() {
        int form = requireSelected();
        return --attacksRemaining[form];
    }

    public void resetLifecycle() {
        selectedForm = -1;
        activeForm = 0;
        lastCompletedForm = -1;
        attackInProgress = false;
        damageCreated = false;
    }

    private int requireSelected() {
        if (selectedForm < 0)
            throw new IllegalStateException("no attack form is selected");
        return selectedForm;
    }

    private void requireForm(int form) {
        if (form < 0 || form >= rates.length)
            throw new IndexOutOfBoundsException("form=" + form);
    }

    private static int validateMappings(int[] rates,
                                        MaskEntity[] formData,
                                        AtkModelEntity[] formModels,
                                        MaAnim[] formTimelines,
                                        int[][] pres,
                                        int[] attacksRemaining) {
        if (rates == null || !AttackRateEngine.isValid(rates))
            throw new IllegalArgumentException("Rates must be positive and total 100");
        int count = rates.length;
        if (count < 1 || count > AttackFormSet.MAX_FORMS)
            throw new IllegalArgumentException("attack form count must be 1..5");
        requireLength(formData, count, "form data");
        requireLength(formModels, count, "attack models");
        requireLength(formTimelines, count, "animation timelines");
        requireLength(pres, count, "pre-hit schedules");
        requireLength(attacksRemaining, count, "attack-loop counters");
        for (int form = 0; form < count; form++) {
            if (formData[form] == null)
                throw new IllegalArgumentException("form data " + form + " is missing");
            if (formModels[form] == null)
                throw new IllegalArgumentException("attack model " + form + " is missing");
            if (formTimelines[form] == null)
                throw new IllegalArgumentException("animation timeline " + form + " is missing");
            if (pres[form] == null || pres[form].length == 0)
                throw new IllegalArgumentException("pre-hit schedule " + form + " is empty");
        }
        return count;
    }

    private static int[][] extractPreTimes(MaskEntity[] data) {
        if (data == null) return null;
        int[][] result = new int[data.length][];
        for (int form = 0; form < data.length; form++) {
            MaskEntity value = data[form];
            if (value == null) {
                result[form] = null;
                continue;
            }
            int[][] raw = value.rawAtkData();
            if (raw == null) {
                result[form] = null;
                continue;
            }
            result[form] = new int[raw.length];
            for (int hit = 0; hit < raw.length; hit++) {
                if (raw[hit] == null || raw[hit].length <= 1)
                    throw new IllegalArgumentException("invalid raw attack data at form "
                            + form + ", hit " + hit);
                result[form][hit] = raw[hit][1];
            }
        }
        return result;
    }

    private static int[] extractAttackLoops(MaskEntity[] data) {
        if (data == null) return null;
        int[] result = new int[data.length];
        for (int form = 0; form < data.length; form++)
            result[form] = data[form] == null ? 0 : data[form].getAtkLoop();
        return result;
    }

    private static int[][] cloneRows(int[][] values) {
        int[][] copy = new int[values.length][];
        for (int i = 0; i < values.length; i++) copy[i] = values[i].clone();
        return copy;
    }

    private static <T> T first(T[] values) {
        return values == null || values.length == 0 ? null : values[0];
    }

    private static void requireLength(Object[] values, int count, String name) {
        if (values == null || values.length != count)
            throw new IllegalArgumentException(name + "/form count mismatch");
    }

    private static void requireLength(int[] values, int count, String name) {
        if (values == null || values.length != count)
            throw new IllegalArgumentException(name + "/form count mismatch");
    }
}
