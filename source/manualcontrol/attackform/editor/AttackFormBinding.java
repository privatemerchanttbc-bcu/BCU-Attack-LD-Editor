package manualcontrol.attackform.editor;

import common.util.anim.AnimCI;
import manualcontrol.attackform.animator.AttackAnimationStatus;
import manualcontrol.attackform.animator.AttackAnimationStore;
import manualcontrol.attackform.data.AttackFormSet;

public final class AttackFormBinding {

    private AttackFormBinding() {}

    public static Report inspect(AttackFormSet forms, AnimCI animation) {
        return validate(forms, AttackAnimationStore.inspect(animation));
    }

    public static Report resolve(AttackFormSet forms, AnimCI animation) {
        return validate(forms, AttackAnimationStore.resolveForValidation(animation));
    }

    public static Report validate(AttackFormSet forms, AttackAnimationStatus animation) {

        int formCount = forms == null ? -1 : forms.formCount();
        if (formCount < 1 || formCount > AttackFormSet.MAX_FORMS)
            return Report.error(formCount, -1,
                    "Attack form count must be between 1 and 5.");
        if (animation == null)
            return Report.error(formCount, -1,
                    "Attack animation status is unavailable.");

        switch (animation.resolution) {
            case INELIGIBLE:
                return Report.error(formCount, -1, animation.message);
            case NOT_RESOLVED:
                return Report.error(formCount, -1,
                        "Attack animation timelines have not been resolved; binding cannot be validated. "
                                + animation.message);
            case FAILED:
                return Report.error(formCount, -1,
                        "Attack animation timelines are unresolved or corrupt; binding cannot be validated. "
                                + animation.message);
            case RESOLVED:
                break;
            default:
                return Report.error(formCount, -1,
                        "Attack animation status is unsupported.");
        }

        if (!animation.primaryTimelineAvailable)
            return Report.error(formCount, -1,
                    "The stock attack timeline is missing.");

        int timelineCount = animation.totalTimelineCount;
        if (timelineCount != formCount)
            return Report.error(formCount, timelineCount,
                    "Attack form count is " + formCount + ", but the animation has "
                            + timelineCount + " attack "
                            + (timelineCount == 1 ? "timeline" : "timelines") + " ("
                            + timelineRange(timelineCount) + ").");
        return Report.ok(formCount);
    }

    private static String timelineRange(int count) {
        if (count <= 0) return "none";
        if (count == 1) return "attack only";
        return "attack through attack" + count;
    }

    public static final class Report {
        public final boolean valid;
        public final int formCount;
        public final int timelineCount;
        public final String message;

        private Report(boolean valid, int formCount, int timelineCount, String message) {
            this.valid = valid;
            this.formCount = formCount;
            this.timelineCount = timelineCount;
            this.message = message == null ? "" : message;
        }

        private static Report ok(int count) {
            return new Report(true, count, count, "");
        }

        private static Report error(int formCount, int timelineCount, String message) {
            return new Report(false, formCount, timelineCount, message);
        }
    }
}
