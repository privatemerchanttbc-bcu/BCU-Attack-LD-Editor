package manualcontrol.attackform.animator;

public final class AttackAnimationIndex {

    public static final int STOCK_ATTACK_INDEX = 2;
    public static final int MAX_EXTRA_ATTACKS = 4;

    private AttackAnimationIndex() {}

    public static String[] visibleNames(String[] stockNames, int extraCount) {
        requireExtraCount(extraCount);
        if (stockNames == null || stockNames.length <= STOCK_ATTACK_INDEX)
            return stockNames == null ? new String[0] : stockNames.clone();
        String[] visible = new String[stockNames.length + extraCount];
        System.arraycopy(stockNames, 0, visible, 0, STOCK_ATTACK_INDEX + 1);
        for (int i = 0; i < extraCount; i++)
            visible[STOCK_ATTACK_INDEX + 1 + i] = "attack" + (i + 2);
        System.arraycopy(stockNames, STOCK_ATTACK_INDEX + 1, visible,
                STOCK_ATTACK_INDEX + 1 + extraCount,
                stockNames.length - STOCK_ATTACK_INDEX - 1);
        return visible;
    }

    public static int visibleToAttackSlot(int visibleIndex, int extraCount) {
        requireExtraCount(extraCount);
        if (visibleIndex == STOCK_ATTACK_INDEX) return 0;
        int firstExtra = STOCK_ATTACK_INDEX + 1;
        if (visibleIndex >= firstExtra && visibleIndex < firstExtra + extraCount)
            return visibleIndex - STOCK_ATTACK_INDEX;
        return -1;
    }

    public static int visibleToStock(int visibleIndex, int extraCount) {
        requireExtraCount(extraCount);
        if (visibleToAttackSlot(visibleIndex, extraCount) > 0)
            return STOCK_ATTACK_INDEX;
        if (visibleIndex > STOCK_ATTACK_INDEX + extraCount)
            return visibleIndex - extraCount;
        return visibleIndex;
    }

    public static int stockToVisible(int stockIndex, int extraCount) {
        requireExtraCount(extraCount);
        return stockIndex > STOCK_ATTACK_INDEX ? stockIndex + extraCount : stockIndex;
    }

    public static int attackSlotToVisible(int attackSlot) {
        if (attackSlot < 0 || attackSlot > MAX_EXTRA_ATTACKS)
            throw new IllegalArgumentException("attack slot must be 0..4");
        return STOCK_ATTACK_INDEX + attackSlot;
    }

    public static int remapVisible(int visibleIndex, int oldExtraCount, int newExtraCount) {
        requireExtraCount(oldExtraCount);
        requireExtraCount(newExtraCount);
        if (visibleIndex < 0) return -1;
        int slot = visibleToAttackSlot(visibleIndex, oldExtraCount);
        if (slot > 0)
            return slot <= newExtraCount ? attackSlotToVisible(slot) : STOCK_ATTACK_INDEX;
        return stockToVisible(visibleToStock(visibleIndex, oldExtraCount), newExtraCount);
    }

    private static void requireExtraCount(int extraCount) {
        if (extraCount < 0 || extraCount > MAX_EXTRA_ATTACKS)
            throw new IllegalArgumentException("extra attack count must be 0..4");
    }
}
