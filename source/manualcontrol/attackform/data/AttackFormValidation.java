package manualcontrol.attackform.data;

public final class AttackFormValidation {

    public final boolean valid;
    public final String message;

    private AttackFormValidation(boolean valid, String message) {
        this.valid = valid;
        this.message = message;
    }

    public static AttackFormValidation ok() {
        return new AttackFormValidation(true, "");
    }

    public static AttackFormValidation error(String message) {
        return new AttackFormValidation(false, message == null ? "invalid attack forms" : message);
    }
}
