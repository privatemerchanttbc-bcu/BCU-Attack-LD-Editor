package manualcontrol.attackform.data;

import common.battle.data.AtkDataModel;
import common.battle.data.CustomEntity;
import common.io.json.JsonClass;
import common.io.json.JsonField;
import common.util.Data;
import manualcontrol.newproc.NewProcs;

import java.util.IdentityHashMap;

@JsonClass(noTag = JsonClass.NoTag.LOAD)
public final class AttackFormData {

    public int rate = 100;
    public String animation = "attack";

    public int tba;
    public int range;
    public int toBase;
    public int touchType = 1;
    public int attackLoop = 1;
    public int ability;
    public boolean useCommonProc = true;

    @JsonField
    public Data.Proc commonProc = Data.Proc.blank();
    @JsonField(usePool = true)
    public AttackFormHitData[] hits = {new AttackFormHitData()};

    public AttackFormData() {}

    public static AttackFormData capture(CustomEntity entity, int index) {
        AttackFormData form = new AttackFormData();
        form.animation = animationName(index);
        if (entity == null) return form;

        form.tba = entity.tba;
        form.range = entity.range;
        form.toBase = entity.base;
        form.touchType = entity.touch;
        form.attackLoop = entity.loop;
        form.ability = entity.abi;
        form.useCommonProc = entity.common;
        form.commonProc = entity.rep == null
                ? Data.Proc.blank() : AttackFormHitData.cloneProc(entity.rep.proc);

        AtkDataModel[] source = entity.atks;
        if (source == null || source.length == 0) {
            form.hits = new AttackFormHitData[]{new AttackFormHitData()};
        } else {
            form.hits = new AttackFormHitData[source.length];
            IdentityHashMap<AtkDataModel, AttackFormHitData> linked =
                    new IdentityHashMap<AtkDataModel, AttackFormHitData>();
            for (int i = 0; i < source.length; i++) {
                AtkDataModel hit = source[i];
                AttackFormHitData copy = linked.get(hit);
                if (copy == null) {
                    copy = AttackFormHitData.capture(hit);
                    if (hit != null) linked.put(hit, copy);
                }
                form.hits[i] = copy;
            }
        }
        form.repair(index);
        return form;
    }

    public static AttackFormData blank(int index) {
        AttackFormData form = new AttackFormData();
        form.animation = animationName(index);
        return form;
    }

    public AttackFormData deepCopy() {
        AttackFormData copy = new AttackFormData();
        copy.rate = rate;
        copy.animation = animation;
        copy.tba = tba;
        copy.range = range;
        copy.toBase = toBase;
        copy.touchType = touchType;
        copy.attackLoop = attackLoop;
        copy.ability = ability;
        copy.useCommonProc = useCommonProc;
        copy.commonProc = AttackFormHitData.cloneProc(commonProc);
        copy.hits = new AttackFormHitData[hits == null ? 0 : hits.length];
        IdentityHashMap<AttackFormHitData, AttackFormHitData> linked =
                new IdentityHashMap<AttackFormHitData, AttackFormHitData>();
        for (int i = 0; i < copy.hits.length; i++) {
            AttackFormHitData source = hits[i];
            AttackFormHitData hitCopy = linked.get(source);
            if (hitCopy == null) {
                hitCopy = source == null ? new AttackFormHitData() : source.deepCopy();
                if (source != null) linked.put(source, hitCopy);
            }
            copy.hits[i] = hitCopy;
        }
        return copy;
    }

    public void applyTo(CustomEntity entity) {
        if (entity == null) throw new IllegalArgumentException("target entity is required");
        if (commonProc == null) throw new IllegalStateException("attack form has no common proc data");
        if (hits == null || hits.length == 0)
            throw new IllegalStateException("attack form has no hit data");

        AtkDataModel representative = new AtkDataModel(entity);
        representative.proc = AttackFormHitData.cloneProc(commonProc);

        if (entity.rep != null)
            NewProcs.copyUnitScoped(entity.rep.proc, representative.proc);

        AtkDataModel[] appliedHits = new AtkDataModel[hits.length];
        IdentityHashMap<AttackFormHitData, AtkDataModel> linked =
                new IdentityHashMap<AttackFormHitData, AtkDataModel>();
        for (int i = 0; i < hits.length; i++) {
            AttackFormHitData source = hits[i];
            if (source == null)
                throw new IllegalStateException("attack form hit " + (i + 1) + " is missing");
            if (source.proc == null)
                throw new IllegalStateException("attack form hit " + (i + 1) + " has no proc data");
            AtkDataModel applied = linked.get(source);
            if (applied == null) {
                applied = applyHit(entity, source);
                linked.put(source, applied);
            }
            appliedHits[i] = applied;
        }

        entity.tba = tba;
        entity.range = range;
        entity.base = toBase;
        entity.touch = touchType;
        entity.loop = attackLoop;
        entity.abi = ability;
        entity.common = useCommonProc;
        entity.rep = representative;
        entity.atks = appliedHits;
        entity.updateAllProc();
    }

    private static AtkDataModel applyHit(CustomEntity entity, AttackFormHitData source) {
        AtkDataModel hit = new AtkDataModel(entity);
        hit.str = source.name == null ? "" : source.name;
        hit.atk = source.attack;
        hit.pre = source.pre;
        hit.ld0 = source.ldNear;
        hit.ld1 = source.ldFar;
        hit.targ = source.targetType;
        hit.count = source.count;
        hit.dire = source.direction;
        hit.alt = source.alternateAbility;
        hit.move = source.move;
        hit.range = source.areaAttack;
        hit.specialTrait = source.specialTrait;
        hit.proc = AttackFormHitData.cloneProc(source.proc);
        return hit;
    }

    boolean repair(int index) {
        boolean changed = false;
        String expectedAnimation = animationName(index);
        if (!expectedAnimation.equals(animation)) {
            animation = expectedAnimation;
            changed = true;
        }
        if (commonProc == null) {
            commonProc = Data.Proc.blank();
            changed = true;
        }
        if (hits == null || hits.length == 0) {
            hits = new AttackFormHitData[]{new AttackFormHitData()};
            changed = true;
        }
        for (int i = 0; i < hits.length; i++) {
            if (hits[i] == null) {
                hits[i] = new AttackFormHitData();
                changed = true;
            }
            changed |= hits[i].repair();
        }
        return changed;
    }

    public static String animationName(int index) {
        if (index < 0 || index >= AttackFormSet.MAX_FORMS)
            throw new IllegalArgumentException("attack form index out of range: " + index);
        return index == 0 ? "attack" : "attack" + (index + 1);
    }
}
