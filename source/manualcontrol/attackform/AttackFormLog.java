package manualcontrol.attackform;

import manualcontrol.Logger;

public final class AttackFormLog {

    public static final String PREFIX = "[AttackSwitch] ";
    public static final String DEBUG_PROPERTY = "manualcontrol.attackform.debug";

    private AttackFormLog() {}

    public static void info(String message) {
        Logger.log(PREFIX + message);
    }

    public static void error(String message, Throwable error) {
        Logger.err(PREFIX + message, error);
    }

    public static boolean isDebugEnabled() {
        return Boolean.parseBoolean(System.getProperty(DEBUG_PROPERTY, "false"));
    }

    public static void debug(String event, String details) {
        if (!isDebugEnabled()) return;
        info("debug event=" + safe(event) + " " + safe(details));
    }

    private static String safe(String value) {
        if (value == null) return "";
        return value.replace('\r', ' ').replace('\n', ' ');
    }
}
