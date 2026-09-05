package manualcontrol.attackform.animator;

public final class AttackAnimationStatus {

    public enum Resolution {
        RESOLVED,
        NOT_RESOLVED,
        FAILED,
        INELIGIBLE
    }

    public final Resolution resolution;
    public final boolean primaryTimelineAvailable;
    public final int extraTimelineCount;
    public final int totalTimelineCount;
    public final String message;

    private AttackAnimationStatus(Resolution resolution,
                                  boolean primaryTimelineAvailable,
                                  int extraTimelineCount,
                                  String message) {
        this.resolution = resolution;
        this.primaryTimelineAvailable = primaryTimelineAvailable;
        this.extraTimelineCount = extraTimelineCount;
        this.totalTimelineCount = resolution == Resolution.RESOLVED
                && primaryTimelineAvailable ? extraTimelineCount + 1 : -1;
        this.message = message == null ? "" : message;
    }

    public static AttackAnimationStatus resolved(boolean primaryTimelineAvailable,
                                                 int extraTimelineCount) {
        if (extraTimelineCount < 0 || extraTimelineCount > AttackAnimationIndex.MAX_EXTRA_ATTACKS)
            throw new IllegalArgumentException("extra attack timeline count must be 0..4");
        return new AttackAnimationStatus(Resolution.RESOLVED,
                primaryTimelineAvailable, extraTimelineCount, "");
    }

    public static AttackAnimationStatus notResolved(boolean primaryTimelineAvailable,
                                                    String message) {
        return new AttackAnimationStatus(Resolution.NOT_RESOLVED,
                primaryTimelineAvailable, -1, fallback(message,
                "Extra attack timelines have not been resolved."));
    }

    public static AttackAnimationStatus failed(boolean primaryTimelineAvailable,
                                               String message) {
        return new AttackAnimationStatus(Resolution.FAILED,
                primaryTimelineAvailable, -1, fallback(message,
                "Extra attack timelines could not be resolved."));
    }

    public static AttackAnimationStatus ineligible(String message) {
        return new AttackAnimationStatus(Resolution.INELIGIBLE,
                false, -1, fallback(message,
                "A unit or enemy animation is required."));
    }

    public boolean isResolved() {
        return resolution == Resolution.RESOLVED;
    }

    private static String fallback(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }
}
