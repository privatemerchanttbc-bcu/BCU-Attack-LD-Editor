package manualcontrol.attackform.data;

import common.battle.data.AtkDataModel;
import common.io.json.JsonClass;
import common.io.json.JsonField;
import common.util.Data;

@JsonClass(noTag = JsonClass.NoTag.LOAD)
public final class AttackFormHitData {

    public String name = "";
    public int attack;
    public int pre = 1;
    public int ldNear;
    public int ldFar;
    public int targetType = 1;
    public int count = -1;
    public int direction = 1;
    public int alternateAbility;
    public int move;
    public boolean areaAttack = true;
    public boolean specialTrait;
    @JsonField
    public Data.Proc proc = Data.Proc.blank();

    public AttackFormHitData() {}

    public static AttackFormHitData capture(AtkDataModel source) {
        AttackFormHitData hit = new AttackFormHitData();
        if (source == null) return hit;
        hit.name = source.str == null ? "" : source.str;
        hit.attack = source.atk;
        hit.pre = source.pre;
        hit.ldNear = source.ld0;
        hit.ldFar = source.ld1;
        hit.targetType = source.targ;
        hit.count = source.count;
        hit.direction = source.dire;
        hit.alternateAbility = source.alt;
        hit.move = source.move;
        hit.areaAttack = source.range;
        hit.specialTrait = source.specialTrait;
        hit.proc = cloneProc(source.proc);
        return hit;
    }

    public AttackFormHitData deepCopy() {
        AttackFormHitData copy = new AttackFormHitData();
        copy.name = name;
        copy.attack = attack;
        copy.pre = pre;
        copy.ldNear = ldNear;
        copy.ldFar = ldFar;
        copy.targetType = targetType;
        copy.count = count;
        copy.direction = direction;
        copy.alternateAbility = alternateAbility;
        copy.move = move;
        copy.areaAttack = areaAttack;
        copy.specialTrait = specialTrait;
        copy.proc = cloneProc(proc);
        return copy;
    }

    boolean repair() {
        boolean changed = false;
        if (name == null) {
            name = "";
            changed = true;
        }
        if (proc == null) {
            proc = Data.Proc.blank();
            changed = true;
        }
        return changed;
    }

    static Data.Proc cloneProc(Data.Proc source) {
        return source == null ? Data.Proc.blank() : source.clone();
    }
}
