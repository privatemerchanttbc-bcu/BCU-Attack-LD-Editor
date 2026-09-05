package page.anim;

import common.CommonStatic;
import common.battle.data.AtkDataModel;
import common.battle.data.CustomUnit;
import common.system.P;
import common.system.fake.FakeGraphics;
import common.util.anim.EAnimD;
import common.util.anim.EAnimU;
import manualcontrol.attackform.animator.AttackLdEditor;

import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Canvas;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;

public final class AttackLdDragTest {
    private static int checks;

    public static void run() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try { exercise(); }
            catch (Exception error) { throw new RuntimeException(error); }
        });
        System.out.println("[AttackLdDragTest] PASS checks=" + checks);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void exercise() throws Exception {

        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field unsafeField = unsafeType.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        Object page = unsafeType.getMethod("allocateInstance", Class.class)
                .invoke(unsafe, MaAnimEditPage.class);
        Box box = new Box();
        box.setSize(800, 400);
        Class<?> stateType = Class.forName(AttackLdEditor.class.getName() + "$State");
        Constructor<?> stateCtor = stateType.getDeclaredConstructor(Object.class);
        stateCtor.setAccessible(true);
        Object state = stateCtor.newInstance(page);
        put(state, "box", box);
        put(state, "panel", new JPanel());
        JComboBox ownerBox = new JComboBox();
        JComboBox<String> hitBox = new JComboBox<String>(new String[]{"Hit 1", "Hit 2"});
        JTextField nearField = new JTextField();
        JTextField farField = new JTextField();
        put(state, "owner", ownerBox);
        put(state, "hit", hitBox);
        put(state, "near", nearField);
        put(state, "far", farField);
        put(state, "visible", true);
        put(state, "previewValid", true);
        Class<?> ownerType = Class.forName(AttackLdEditor.class.getName() + "$Owner");
        Constructor<?> ownerCtor = ownerType.getDeclaredConstructor();
        ownerCtor.setAccessible(true);
        Object owner = ownerCtor.newInstance();
        CustomUnit unit = new CustomUnit();
        unit.atks = new AtkDataModel[]{new AtkDataModel(unit), new AtkDataModel(unit)};
        unit.atks[1].ld0 = 31;
        unit.atks[1].ld1 = 79;
        put(owner, "entity", unit);
        ownerBox.addItem(owner);
        Map pages = (Map) getStatic(AttackLdEditor.class, "PAGES");
        Map boxes = (Map) getStatic(AttackLdEditor.class, "BOXES");
        pages.put(page, state);
        boxes.put(box, state);
        P pan = AnimBox.ori;
        float oldX = pan.x, oldY = pan.y;
        boolean oldRef = CommonStatic.getConfig().ref;
        try {
            CommonStatic.getConfig().ref = true;

            for (boolean gl : new boolean[]{true, false}) {
                for (boolean enemy : new boolean[]{false, true}) {
                    for (int near : new int[]{100, -100}) {
                        box.zoom = 1.25f;
                        pan.x = 27;
                        pan.y = 41;
                        put(state, "enemyFacing", enemy);
                        unit.atks[0].ld0 = near;
                        unit.atks[0].ld1 = 300;
                        put(state, "previewNear", near);
                        put(state, "previewFar", 300);
                        render(gl, box);
                        check(number(state, "lastOriginX") == 373f
                                && number(state, "lastOriginY") == 259f,
                                "mouse geometry matches the rendered canvas origin");
                        float direction = enemy ? 1 : -1;
                        int nx = Math.round(373 + direction * near * 0.5f);

                        mouse(page, box, "mousePressed", nx + 3, 259);
                        check(intValue(state, "dragHandle") == 1, "grab visible Near handle");
                        mouse(page, box, "mouseDragged", nx + 3, 259);
                        check(unit.atks[0].ld0 == near, "no jump when grabbing off-center");
                        mouse(page, box, "mouseDragged", nx + 23, 271);
                        int wantedNear = near + (enemy ? 40 : -40);
                        check(unit.atks[0].ld0 == wantedNear && unit.atks[0].ld1 == 300,
                                "Near drag updates only its endpoint with correct facing");
                        check(nearField.getText().equals(String.valueOf(wantedNear)), "Near UI sync");
                        mouse(page, box, "mouseReleased", nx + 23, 271);
                        check(intValue(state, "dragHandle") == 0, "release ends drag");
                        render(gl, box);
                        int fx = Math.round(373 + direction * 300 * 0.5f);
                        mouse(page, box, "mousePressed", fx, 259);
                        check(intValue(state, "dragHandle") == 2, "grab visible Far handle");
                        mouse(page, box, "mouseDragged", fx + 10, 259);
                        int wantedFar = 300 + (enemy ? 20 : -20);
                        check(unit.atks[0].ld1 == wantedFar && unit.atks[0].ld0 == wantedNear,
                                "Far drag updates only its endpoint");
                        check(farField.getText().equals(String.valueOf(wantedFar)), "Far UI sync");
                        check(pan.x == 27 && pan.y == 41, "LD dragging does not move camera");
                        mouse(page, box, "mouseReleased", fx + 10, 259);
                        check(unit.atks[1].ld0 == 31 && unit.atks[1].ld1 == 79,
                                "other hit retained");
                    }
                }
            }

            CommonStatic.getConfig().ref = false;
            mouse(page, box, "mousePressed", 20, 20);
            mouse(page, box, "mouseDragged", 30, 25);
            check(pan.x == 17 && pan.y == 36, "stock camera still pans off the handles");
            mouse(page, box, "mouseReleased", 30, 25);
            check(intValue(state, "dragHandle") == 0, "disabled hitboxes cannot start LD drag");
        } finally {
            pages.remove(page);
            boxes.remove(box);
            pan.x = oldX;
            pan.y = oldY;
            CommonStatic.getConfig().ref = oldRef;
        }
    }

    private static void render(boolean gl, Box box) {
        if (gl) {
            FakeGraphics graphics = (FakeGraphics) Proxy.newProxyInstance(
                    AttackLdDragTest.class.getClassLoader(), new Class<?>[]{FakeGraphics.class},
                    (proxy, method, args) -> null);
            AttackLdEditor.onFakePreviewDraw(box, graphics);
        } else {
            java.awt.Graphics2D graphics = new BufferedImage(800, 400,
                    BufferedImage.TYPE_INT_ARGB).createGraphics();
            try { AttackLdEditor.onBufferedPreviewDraw(box, graphics); }
            finally { graphics.dispose(); }
        }
    }

    private static void mouse(Object page, Box box, String name, int x, int y) throws Exception {
        int id = "mousePressed".equals(name) ? MouseEvent.MOUSE_PRESSED
                : "mouseReleased".equals(name) ? MouseEvent.MOUSE_RELEASED : MouseEvent.MOUSE_DRAGGED;
        MouseEvent event = new MouseEvent(box, id, 0, 0, x, y, 1, false,
                id == MouseEvent.MOUSE_DRAGGED ? MouseEvent.NOBUTTON : MouseEvent.BUTTON1);
        Method method = MaAnimEditPage.class.getDeclaredMethod(name, MouseEvent.class);
        method.setAccessible(true);
        method.invoke(page, event);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
    private static Object getStatic(Class<?> type, String name) throws Exception {
        return field(type, name).get(null);
    }
    private static void put(Object target, String name, Object value) throws Exception {
        field(target.getClass(), name).set(target, value);
    }
    private static float number(Object target, String name) throws Exception {
        return ((Number) field(target.getClass(), name).get(target)).floatValue();
    }
    private static int intValue(Object target, String name) throws Exception {
        return ((Number) field(target.getClass(), name).get(target)).intValue();
    }
    private static void check(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    private static final class Box extends Canvas implements AnimBox {
        float zoom = 1;
        public EAnimD<?> getEntity() { return null; }
        public void setEntity(EAnimU entity) {}
        public void setSele(int index) {}
        public void update() {}
        public void setSiz(float value) { zoom = value; }
        public float getSiz() { return zoom; }
        public void draw() {}
        public Point getPoint(Point point) { return point; }
    }
}
