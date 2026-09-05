package manualcontrol.attackform.runtime;

import common.battle.attack.AtkModelEntity;
import common.battle.data.MaskEntity;
import common.util.anim.EAnimU;

public interface AttackFormRuntimeCarrier {

    AttackFormRuntimeState attackFormRuntime$get();

    void attackFormRuntime$set(AttackFormRuntimeState value);

    MaskEntity attackFormRuntime$data();

    AtkModelEntity attackFormRuntime$model();

    Object attackFormRuntime$manager();

    EAnimU attackFormRuntime$animation();

    void attackFormRuntime$activate(MaskEntity data, AtkModelEntity model);
}
