package manualcontrol.animedit;

import common.system.fake.FakeGraphics;
import common.util.anim.AnimCE;
import manualcontrol.Logger;
import manualcontrol.attackform.animator.AttackLdEditor;
import page.anim.MaAnimEditPage;

import java.awt.event.MouseEvent;
import java.util.Collections;
import java.util.Map;
import java.util.Stack;
import java.util.WeakHashMap;

public final class DirectEditHooks {

    private static final Map<Object, DirectEditSession> PAGES =
            Collections.synchronizedMap(new WeakHashMap<Object, DirectEditSession>());
    private static final Map<Object, DirectEditSession> BOXES =
            Collections.synchronizedMap(new WeakHashMap<Object, DirectEditSession>());

    private static final int UNDO_HISTORY_CAP = 20;
    private static boolean trimErr;

    private DirectEditHooks() {}

    public static void onAnimUnsaved(Object animObj) {
        try {
            if (!(animObj instanceof AnimCE))
                return;
            Stack<?> h = ((AnimCE) animObj).history;
            if (h == null)
                return;
            while (h.size() > UNDO_HISTORY_CAP)
                h.remove(0);
        } catch (Throwable t) {
            if (!trimErr) {
                trimErr = true;
                Logger.err("DirectEdit: undo history trim failed", t);
            }
        }
    }

    public static void onAnimPageBuilt(Object page) {
        try {
            if (!(page instanceof MaAnimEditPage))
                return;
            if (PAGES.containsKey(page))
                return;
            if (!HookStatus.pageComplete()) {
                Compat.notifyDisabled("editor method hooks incomplete: "
                        + HookStatus.pageMissing());
                return;
            }
            if (!HookStatus.drawOk())
                Compat.notifyDegraded("preview overlay hook missing - selection gizmo will be invisible");
            DirectEditSession s = new DirectEditSession((MaAnimEditPage) page);
            s.install();
            PAGES.put(page, s);
            if (s.box() != null)
                BOXES.put(s.box(), s);
        } catch (Throwable t) {
            Logger.err("DirectEdit: install failed", t);
        }
    }

    public static void onAnimPageResized(Object page, int x, int y) {
        DirectEditSession s = PAGES.get(page);
        if (s == null)
            return;
        try {
            s.resized(x, y);
        } catch (Throwable t) {
            Logger.err("DirectEdit: resize failed", t);
        }
    }

    public static void onAnimPageTimer(Object page) {
        DirectEditSession s = PAGES.get(page);
        if (s == null)
            return;
        s.onTimer();
    }

    public static boolean onMousePressed(Object page, Object e) {
        if (AttackLdEditor.onMousePressed(page, e)) return true;
        DirectEditSession s = PAGES.get(page);
        if (s == null || !(e instanceof MouseEvent))
            return false;
        try {
            return s.mousePressed((MouseEvent) e);
        } catch (Throwable t) {
            Logger.err("DirectEdit: mousePressed failed", t);
            return false;
        }
    }

    public static boolean onMouseDragged(Object page, Object e) {
        if (AttackLdEditor.onMouseDragged(page, e)) return true;
        DirectEditSession s = PAGES.get(page);
        if (s == null || !(e instanceof MouseEvent))
            return false;
        try {
            return s.mouseDragged((MouseEvent) e);
        } catch (Throwable t) {
            Logger.err("DirectEdit: mouseDragged failed", t);
            return false;
        }
    }

    public static boolean onMouseReleased(Object page, Object e) {
        if (AttackLdEditor.onMouseReleased(page, e)) return true;
        DirectEditSession s = PAGES.get(page);
        if (s == null || !(e instanceof MouseEvent))
            return false;
        try {
            return s.mouseReleased((MouseEvent) e);
        } catch (Throwable t) {
            Logger.err("DirectEdit: mouseReleased failed", t);
            return false;
        }
    }

    public static boolean onMouseWheel(Object page, Object e) {
        DirectEditSession s = PAGES.get(page);
        if (s == null || !(e instanceof MouseEvent))
            return false;
        try {
            return s.mouseWheel((MouseEvent) e);
        } catch (Throwable t) {
            Logger.err("DirectEdit: mouseWheel failed", t);
            return false;
        }
    }

    public static void onPreviewDraw(Object box, Object g) {
        DirectEditSession s = BOXES.get(box);
        if (s == null || !(g instanceof FakeGraphics))
            return;
        s.drawOverlay((FakeGraphics) g);
    }
}
