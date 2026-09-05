package manualcontrol.attackform;

import common.battle.data.AtkDataModel;
import common.battle.data.CustomUnit;
import manualcontrol.attackform.data.AttackFormData;
import manualcontrol.attackform.data.AttackFormHitData;
import manualcontrol.attackform.data.AttackFormSet;

import java.util.Arrays;

public final class AttackFormPart3DataTest {

    private static int assertions;

    private AttackFormPart3DataTest() {}

    public static void main(String[] args) {
        testApplyAndLinkedHits();
        testSafeFormAccessAndReplacement();
        testEditorMutatorsAndOneFormCleanup();
        testLdPointMutators();
        System.out.println("[AttackFormPart3DataTest] PASS assertions=" + assertions);
    }

    private static void testApplyAndLinkedHits() {
        AttackFormData form = AttackFormData.blank(1);
        form.tba = 41;
        form.range = 555;
        form.toBase = 17;
        form.touchType = 3;
        form.attackLoop = -1;
        form.ability = 0x456;
        form.useCommonProc = false;
        form.commonProc.CRIT.prob = 23;

        AttackFormHitData linked = new AttackFormHitData();
        linked.name = "linked";
        linked.attack = 1234;
        linked.pre = 19;
        linked.ldNear = -80;
        linked.ldFar = 260;
        linked.targetType = 7;
        linked.count = 2;
        linked.direction = -1;
        linked.alternateAbility = 9;
        linked.move = 13;
        linked.areaAttack = false;
        linked.specialTrait = true;
        linked.proc.CRIT.prob = 37;
        form.hits = new AttackFormHitData[]{linked, linked};

        CustomUnit target = new CustomUnit();
        form.applyTo(target);

        check(target.tba == 41 && target.range == 555 && target.base == 17,
                "form scalar fields were not applied");
        check(target.touch == 3 && target.loop == -1 && target.abi == 0x456 && !target.common,
                "form flags were not applied");
        check(target.rep != null && target.rep.ce == target && target.rep.proc.CRIT.prob == 23,
                "common Proc was not rebuilt for the target");
        check(target.atks.length == 2 && target.atks[0] == target.atks[1],
                "linked-hit identity was not preserved");
        AtkDataModel hit = target.atks[0];
        check(hit.ce == target && "linked".equals(hit.str) && hit.atk == 1234 && hit.pre == 19,
                "hit owner/basic fields were not applied");
        check(hit.ld0 == -80 && hit.ld1 == 260 && hit.targ == 7 && hit.count == 2,
                "hit range/target fields were not applied");
        check(hit.dire == -1 && hit.alt == 9 && hit.move == 13,
                "hit direction/alternate/move fields were not applied");
        check(!hit.range && hit.specialTrait && hit.proc.CRIT.prob == 37,
                "hit flags/Proc were not applied");
        hit.proc.CRIT.prob = 99;
        target.rep.proc.CRIT.prob = 88;
        check(linked.proc.CRIT.prob == 37 && form.commonProc.CRIT.prob == 23,
                "applied Proc objects leaked back into the snapshot");
    }

    private static void testSafeFormAccessAndReplacement() {
        AttackFormSet set = new AttackFormSet();
        set.addForm(0, true);
        set.setRate(0, 65);
        check(set.formCount() == 2, "wrong form count");

        AttackFormData copy = set.formCopy(1);
        copy.range = 777;
        copy.hits[0].attack = 4321;
        check(set.formCopy(1).range != 777 && set.formCopy(1).hits[0].attack != 4321,
                "formCopy exposed mutable persisted data");

        copy.rate = 1;
        copy.animation = "wrong";
        set.replaceForm(1, copy);
        AttackFormData replaced = set.formCopy(1);
        check(replaced.range == 777 && replaced.hits[0].attack == 4321,
                "replaceForm lost independent data");
        check(replaced.rate == 35 && "attack2".equals(replaced.animation),
                "replaceForm did not retain Rate/fixed animation name");

        copy.hits[0].attack = 9999;
        check(set.formCopy(1).hits[0].attack == 4321,
                "replaceForm retained the caller's mutable object graph");
    }

    private static void testEditorMutatorsAndOneFormCleanup() {
        AttackFormSet set = new AttackFormSet();
        expectFailure(new Runnable() {
            @Override public void run() { set.setEnabled(true); }
        }, "enabled a one-form set");
        expectFailure(new Runnable() {
            @Override public void run() { set.setPreventConsecutive(true); }
        }, "enabled no-repeat for one form");

        set.addForm(0, true);
        set.setEnabled(true);
        set.setPreventConsecutive(true);
        set.setRatesExact(new int[]{73, 27});
        check(Arrays.equals(new int[]{73, 27}, set.rates()),
                "exact manual Rates were not accepted");

        final int[] stable = set.rates();
        expectFailure(new Runnable() {
            @Override public void run() { set.setRatesExact(new int[]{70, 20}); }
        }, "accepted invalid exact Rates");
        check(Arrays.equals(stable, set.rates()), "invalid exact Rates partially mutated the set");

        set.removeForm(1);
        check(set.formCount() == 1 && !set.enabled && !set.preventConsecutive,
                "last-secondary removal left one-form flags enabled");
        check(Arrays.equals(new int[]{100}, set.rates()),
                "last-secondary removal did not restore the primary Rate");

        set.addForm(0, true);
        set.setPreventConsecutive(true);
        set.setEnabled(false);
        check(set.formCount() == 1 && !set.enabled && !set.preventConsecutive,
                "disable did not discard secondary forms and no-repeat");
    }

    private static void testLdPointMutators() {
        AttackFormSet set = new AttackFormSet();
        set.addForm(0, true);
        check(set.hitCount(1) == 1, "secondary hit count was not exposed safely");
        check(Arrays.equals(new int[]{0, 0}, set.ldPoints(1, 0)),
                "blank LD pair was not 0/0");

        check(Arrays.equals(new int[]{100, 101}, set.setLdNear(1, 0, 100)),
                "Near edit did not advance Far");
        check(Arrays.equals(new int[]{-51, -50}, set.setLdFar(1, 0, -50)),
                "Far edit did not retreat Near");
        int[] detached = set.ldPoints(1, 0);
        detached[0] = 999;
        check(set.ldPoints(1, 0)[0] == -51, "ldPoints exposed persisted storage");

        final int[] stable = set.ldPoints(1, 0);
        expectFailure(new Runnable() {
            @Override public void run() { set.setLdNear(1, 0, Integer.MAX_VALUE); }
        }, "accepted overflowing Near");
        check(Arrays.equals(stable, set.ldPoints(1, 0)),
                "overflowing Near partially mutated LD data");
        expectFailure(new Runnable() {
            @Override public void run() { set.setLdFar(1, 0, Integer.MIN_VALUE); }
        }, "accepted overflowing Far");
        check(Arrays.equals(stable, set.ldPoints(1, 0)),
                "overflowing Far partially mutated LD data");
    }

    private static void expectFailure(Runnable action, String message) {
        boolean failed = false;
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            failed = true;
        } catch (IllegalStateException expected) {
            failed = true;
        }
        check(failed, message);
    }

    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
}
