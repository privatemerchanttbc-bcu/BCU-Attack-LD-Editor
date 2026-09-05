package manualcontrol.attackform.runtime;

import common.battle.entity.Entity;

public interface AttackFormManagerCarrier {

    Entity attackFormRuntime$owner();

    int attackFormRuntime$attackTime();

    void attackFormRuntime$attackTime(int value);

    int attackFormRuntime$preTime();

    int attackFormRuntime$attacksLeft();

    void attackFormRuntime$configure(int[] pres, int attacksLeft);
}
