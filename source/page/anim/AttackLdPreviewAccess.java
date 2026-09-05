package page.anim;

import common.system.P;

public final class AttackLdPreviewAccess {

    private AttackLdPreviewAccess() {}

    public static float zoom(Object box) {
        if (!(box instanceof AnimBox))
            throw new IllegalArgumentException("Object is not an animation preview");
        return ((AnimBox) box).getSiz();
    }

    public static P pan() {
        return AnimBox.ori;
    }
}
