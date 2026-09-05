package manualcontrol.attackform.animator;

import common.CommonStatic;
import common.battle.data.AtkDataModel;
import common.battle.data.CustomEntity;
import common.pack.PackData;
import common.pack.UserProfile;
import common.system.P;
import common.system.fake.FakeGraphics;
import common.util.anim.AnimCE;
import common.util.unit.Form;
import common.util.unit.Unit;
import manualcontrol.attackform.AttackFormLog;
import manualcontrol.attackform.data.AttackFormSet;
import manualcontrol.attackform.data.AttackFormStore;
import page.Page;
import page.anim.AttackLdPreviewAccess;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Stroke;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public final class AttackLdEditor {

    private static final float HITBOX_SCALE = 0.32f * 1.25f;
    private static final Map<Object, State> PAGES = Collections.synchronizedMap(
            new WeakHashMap<Object, State>());
    private static final Map<Object, State> BOXES = Collections.synchronizedMap(
            new WeakHashMap<Object, State>());

    private AttackLdEditor() {}

    public static void onBuilt(Object page) throws Exception {
        if (!(page instanceof Container) || PAGES.containsKey(page)) return;
        State state = new State(page);
        state.box = field(page.getClass(), "ab").get(page);
        state.parts = field(page.getClass(), "jspp").get(page);
        state.panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 6));
        state.panel.setBorder(BorderFactory.createEtchedBorder());
        state.owner = new JComboBox<Owner>();
        state.owner.setPrototypeDisplayValue(Owner.prototype());
        state.owner.setPreferredSize(new Dimension(130, 26));
        state.owner.setMinimumSize(new Dimension(90, 26));
        state.hit = new JComboBox<String>();
        state.hit.setPreferredSize(new Dimension(52, 26));
        state.near = new JTextField();
        state.far = new JTextField();
        state.near.setPreferredSize(new Dimension(56, 26));
        state.far.setPreferredSize(new Dimension(56, 26));
        state.enemy = new JCheckBox("Enemy");
        state.enemy.setToolTipText("Preview facing: checked = right, unchecked = left");
        state.panel.add(new JLabel("Unit:"));
        state.panel.add(state.owner);
        state.panel.add(new JLabel("Hit:"));
        state.panel.add(state.hit);
        state.panel.add(new JLabel("LD Near:"));
        state.panel.add(state.near);
        state.panel.add(new JLabel("LD Far:"));
        state.panel.add(state.far);
        state.panel.add(state.enemy);
        state.panel.setVisible(false);

        state.owner.addActionListener(event -> selectOwner(state));
        state.hit.addActionListener(event -> selectHit(state));
        state.enemy.addActionListener(event -> {
            state.enemyFacing = state.enemy.isSelected();
            repaintPreview(state);
        });
        addFieldListener(state, state.near, true);
        addFieldListener(state, state.far, false);

        if (state.box instanceof Component) {
            state.dragListener = new DragListener(state);
            ((Component) state.box).addMouseMotionListener(state.dragListener);
            ((Component) state.box).addMouseListener(state.dragListener);
        }

        ((Container) page).add(state.panel);
        PAGES.put(page, state);
        if (state.box != null) BOXES.put(state.box, state);
    }

    public static void onResized(Object page, int width, int height) {
        State state = PAGES.get(page);
        if (state == null) return;
        state.width = width;
        state.height = height;
        layout(state);
    }

    public static void onSelection(Object page, AnimCE animation, int attackSlot) {
        State state = PAGES.get(page);
        if (state == null) return;
        String retained = selectedOwnerKey(state);
        state.animation = animation;
        state.attackSlot = attackSlot;
        state.owners = attackSlot < 0 ? Collections.<Owner>emptyList()
                : findOwners(animation, attackSlot);
        state.updating = true;
        try {
            state.owner.removeAllItems();
            int selection = 0;
            for (int i = 0; i < state.owners.size(); i++) {
                Owner owner = state.owners.get(i);
                state.owner.addItem(owner);
                if (owner.key.equals(retained)) selection = i;
            }
            if (!state.owners.isEmpty()) state.owner.setSelectedIndex(selection);
        } finally {
            state.updating = false;
        }
        Owner selected = selectedOwner(state);
        state.owner.setToolTipText(selected == null ? null : selected.label);
        state.visible = !state.owners.isEmpty();
        state.panel.setVisible(state.visible);
        refreshHits(state, 0);
        layout(state);
        ((Container) page).revalidate();
        ((Container) page).repaint();
    }

    public static void teardown(Object page) {
        State state = PAGES.remove(page);
        if (state == null) return;
        if (state.box != null) BOXES.remove(state.box);
        if (state.box instanceof Component && state.dragListener != null) {
            ((Component) state.box).removeMouseListener(state.dragListener);
            ((Component) state.box).removeMouseMotionListener(state.dragListener);
        }
        if (page instanceof Container && state.panel != null)
            ((Container) page).remove(state.panel);
    }

    public static void onFakePreviewDraw(Object box, Object graphics) {
        State state = BOXES.get(box);
        if (state == null || !(graphics instanceof FakeGraphics) || !drawable(state)) return;
        try {
            float zoom = previewZoom(box);
            P pan = previewPan(box);
            float originX = -pan.x;
            float originY = -pan.y;

            Component canvas = (Component) box;
            rememberGeometry(state, canvas.getWidth() / 2.0f + originX,
                    canvas.getHeight() * 3.0f / 4.0f + originY, zoom);
            drawFake((FakeGraphics) graphics, originX, originY, zoom,
                    state.previewNear, state.previewFar, state.enemyFacing);
        } catch (Throwable error) {
            logDrawOnce(state, error);
        }
    }

    public static void onBufferedPreviewDraw(Object box, Object graphics) {
        State state = BOXES.get(box);
        if (state == null || !(box instanceof Component) || !(graphics instanceof Graphics2D)
                || !drawable(state)) return;
        try {
            Component component = (Component) box;
            float zoom = previewZoom(box);
            P pan = previewPan(box);
            float originX = component.getWidth() / 2.0f - pan.x;
            float originY = component.getHeight() * 3.0f / 4.0f - pan.y;
            rememberGeometry(state, originX, originY, zoom);
            drawAwt((Graphics2D) graphics, originX, originY, zoom,
                    state.previewNear, state.previewFar, state.enemyFacing);
        } catch (Throwable error) {
            logDrawOnce(state, error);
        }
    }

    private static List<Owner> findOwners(AnimCE animation, int attackSlot) {
        if (animation == null) return Collections.emptyList();
        List<Owner> result = new ArrayList<Owner>();
        try {
            for (PackData.UserPack pack : UserProfile.getUserPacks()) {
                if (pack == null || !pack.loaded || !pack.editable) continue;
                for (Unit unit : pack.units) {
                    if (unit == null || unit.forms == null) continue;
                    for (Form form : unit.forms) {
                        if (form == null || !(form.du instanceof CustomEntity)
                                || !AttackAnimationStore.references(animation, form.anim)) continue;
                        CustomEntity entity = (CustomEntity) form.du;
                        AttackFormSet forms = AttackFormStore.get(entity);
                        if (attackSlot == 0) {
                            if (entity.atks == null || entity.atks.length == 0) continue;
                        } else if (forms == null || forms.formCount() <= attackSlot
                                || forms.hitCount(attackSlot) <= 0) {
                            continue;
                        }
                        result.add(new Owner(pack, unit, form, entity, forms, attackSlot));
                    }
                }
            }
        } catch (Throwable error) {
            AttackFormLog.error("could not resolve Animator LD unit owners", error);
            return Collections.emptyList();
        }
        Collections.sort(result, Comparator.comparing(owner -> owner.key));
        return result;
    }

    private static void selectOwner(State state) {
        if (state.updating) return;
        Owner owner = selectedOwner(state);
        state.owner.setToolTipText(owner == null ? null : owner.label);
        refreshHits(state, 0);
    }

    private static void selectHit(State state) {
        if (state.updating) return;
        refreshFields(state);
    }

    private static void refreshHits(State state, int preferred) {
        Owner owner = selectedOwner(state);
        state.updating = true;
        try {
            state.hit.removeAllItems();
            int count = owner == null ? 0 : owner.hitCount();
            for (int i = 0; i < count; i++) state.hit.addItem("Hit " + (i + 1));
            if (count > 0) state.hit.setSelectedIndex(Math.max(0, Math.min(preferred, count - 1)));
        } finally {
            state.updating = false;
        }
        refreshFields(state);
    }

    private static void refreshFields(State state) {
        Owner owner = selectedOwner(state);
        int hit = state.hit.getSelectedIndex();
        int[] points = owner == null || hit < 0 ? null : owner.ldPoints(hit);
        state.updating = true;
        try {
            state.near.setText(points == null ? "" : String.valueOf(points[0]));
            state.far.setText(points == null ? "" : String.valueOf(points[1]));
            state.near.setEditable(points != null);
            state.far.setEditable(points != null);
        } finally {
            state.updating = false;
        }
        state.previewValid = points != null;
        state.previewNear = points == null ? 0 : points[0];
        state.previewFar = points == null ? 0 : points[1];
        repaintPreview(state);
    }

    private static void addFieldListener(final State state, final JTextField field,
                                         final boolean near) {
        DocumentListener listener = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { edit(); }
            @Override public void removeUpdate(DocumentEvent event) { edit(); }
            @Override public void changedUpdate(DocumentEvent event) { edit(); }
            private void edit() { commitField(state, field, near, false); }
        };
        field.getDocument().addDocumentListener(listener);
        field.addActionListener(event -> commitField(state, field, near, true));
        field.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent event) {
                commitField(state, field, near, true);
            }
        });
    }

    private static void commitField(State state, JTextField field, boolean near,
                                    boolean finalAttempt) {
        if (state.updating) return;
        Owner owner = selectedOwner(state);
        int hit = state.hit.getSelectedIndex();
        if (owner == null || hit < 0) return;
        try {
            String text = field.getText() == null ? "" : field.getText().trim();
            if (text.isEmpty() || "-".equals(text) || "+".equals(text)) {
                if (finalAttempt) refreshFields(state);
                return;
            }
            int value = Integer.parseInt(text);
            int[] points = near ? owner.setNear(hit, value) : owner.setFar(hit, value);
            state.previewNear = points[0];
            state.previewFar = points[1];
            state.previewValid = true;
            repaintPreview(state);
            if (finalAttempt) refreshFields(state);
            else normalizeFieldsLater(state, owner, hit);
        } catch (NumberFormatException invalid) {
            if (finalAttempt) {
                show(state, "LD values must be whole numbers.");
                refreshFields(state);
            }
        } catch (IllegalArgumentException invalid) {
            if (finalAttempt) {
                show(state, invalid.getMessage());
                refreshFields(state);
            }
        } catch (Throwable error) {
            AttackFormLog.error("Animator LD edit failed", error);
            if (finalAttempt) {
                show(state, "Could not update LD values: " + error.getClass().getSimpleName());
                refreshFields(state);
            }
        }
    }

    private static void normalizeFieldsLater(final State state, final Owner owner,
                                             final int hit) {
        if (state.normalizePending) return;
        state.normalizePending = true;
        SwingUtilities.invokeLater(() -> {
            state.normalizePending = false;
            if (selectedOwner(state) == owner && state.hit.getSelectedIndex() == hit)
                refreshFields(state);
        });
    }

    private static void repaintPreview(State state) {
        if (state.box instanceof Component) ((Component) state.box).repaint();
    }

    public static boolean onMousePressed(Object page, Object event) {
        State state = PAGES.get(page);
        if (state == null || !(event instanceof MouseEvent) || !drawable(state)
                || !state.geometryValid) return false;
        MouseEvent mouse = (MouseEvent) event;
        if (mouse.getSource() != state.box || mouse.getButton() != MouseEvent.BUTTON1)
            return false;
        if (state.dragHandle != 0) return true;

        try {
            Component canvas = (Component) state.box;
            P pan = previewPan(state.box);
            rememberGeometry(state, canvas.getWidth() / 2.0f - pan.x,
                    canvas.getHeight() * 3.0f / 4.0f - pan.y, previewZoom(state.box));
        } catch (Exception error) {
            logDrawOnce(state, error);
            return false;
        }
        int handle = handleAt(state, mouse.getX(), mouse.getY());
        if (handle == 0) return false;
        state.dragHandle = handle;
        state.dragOwner = selectedOwner(state);
        state.dragHit = state.hit.getSelectedIndex();
        state.dragStartX = mouse.getX();
        state.dragStartValue = handle == 1 ? state.previewNear : state.previewFar;
        state.dragScale = (state.enemyFacing ? 1.0 : -1.0) * HITBOX_SCALE * state.lastZoom;
        clearCameraAnchor(state);
        mouse.consume();
        return true;
    }

    public static boolean onMouseDragged(Object page, Object event) {
        State state = PAGES.get(page);
        if (state == null || state.dragHandle == 0 || !(event instanceof MouseEvent))
            return false;
        MouseEvent mouse = (MouseEvent) event;
        if (mouse.getSource() != state.box) return false;
        Owner owner = selectedOwner(state);
        int hit = state.hit.getSelectedIndex();
        if (owner == null || hit < 0 || owner != state.dragOwner || hit != state.dragHit
                || !drawable(state)) {
            mouse.consume();
            return true;
        }
        double denominator = state.dragScale;
        if (denominator == 0.0) return true;
        double raw = state.dragStartValue + (mouse.getX() - state.dragStartX) / denominator;
        int value = raw >= Integer.MAX_VALUE ? Integer.MAX_VALUE
                : raw <= Integer.MIN_VALUE ? Integer.MIN_VALUE
                : (int) Math.round(raw);
        try {
            int[] points = state.dragHandle == 1
                    ? owner.setNear(hit, value) : owner.setFar(hit, value);
            state.previewNear = points[0];
            state.previewFar = points[1];
            state.previewValid = true;
            refreshFields(state);
        } catch (IllegalArgumentException ignored) {

        } catch (Throwable error) {
            AttackFormLog.error("Animator LD handle drag failed", error);
            state.dragHandle = 0;
        }
        clearCameraAnchor(state);
        mouse.consume();
        return true;
    }

    public static boolean onMouseReleased(Object page, Object event) {
        State state = PAGES.get(page);
        if (state == null || state.dragHandle == 0) return false;
        state.dragHandle = 0;
        state.dragOwner = null;
        if (state.box instanceof Component)
            ((Component) state.box).setCursor(Cursor.getDefaultCursor());
        if (event instanceof MouseEvent) ((MouseEvent) event).consume();
        clearCameraAnchor(state);
        return true;
    }

    private static void rememberGeometry(State state, float originX, float originY,
                                         float zoom) {
        state.lastOriginX = originX;
        state.lastOriginY = originY;
        state.lastZoom = zoom;
        state.lastHeight = Math.max(48.0f, 640.0f * HITBOX_SCALE * zoom);
        state.geometryValid = zoom > 0.0f;
    }

    private static int handleAt(State state, int x, int y) {
        if (y < state.lastOriginY - 10.0f
                || y > state.lastOriginY + state.lastHeight + 10.0f) return 0;
        float direction = state.enemyFacing ? 1.0f : -1.0f;
        float scale = direction * HITBOX_SCALE * state.lastZoom;
        float nearX = state.lastOriginX + state.previewNear * scale;
        float farX = state.lastOriginX + state.previewFar * scale;
        float nearDistance = Math.abs(x - nearX);
        float farDistance = Math.abs(x - farX);
        if (nearDistance > 9.0f && farDistance > 9.0f) return 0;
        return nearDistance <= farDistance ? 1 : 2;
    }

    private static void clearCameraAnchor(State state) {
        try {
            field(state.page.getClass(), "p").set(state.page, null);
        } catch (Throwable ignored) {

        }
    }

    private static boolean drawable(State state) {
        return state.visible && state.previewValid
                && (state.previewNear != 0 || state.previewFar != 0)
                && CommonStatic.getConfig().ref;
    }

    private static void drawFake(FakeGraphics g, float originX, float originY, float zoom,
                                 int near, int far, boolean enemyFacing) {
        float scale = HITBOX_SCALE * zoom;
        float direction = enemyFacing ? 1.0f : -1.0f;
        float nx = originX + direction * near * scale;
        float fx = originX + direction * far * scale;
        float x0 = Math.min(nx, fx);
        float x1 = Math.max(nx, fx);
        float actualWidth = x1 - x0;
        float width = Math.max(3.0f, actualWidth);
        float fillX = x0 - (width - actualWidth) / 2.0f;
        float height = Math.max(48.0f, 640.0f * scale);
        g.setColor(FakeGraphics.CYAN);
        g.setComposite(FakeGraphics.TRANS, 64, 0);
        g.fillRect(fillX, originY, width, height);
        g.setComposite(FakeGraphics.DEF, 0, 0);
        g.drawRect(fillX, originY, width, height);
        g.setColor(FakeGraphics.YELLOW);
        drawFakeBoundary(g, nx, originY, height);
        g.drawRect(nx - 5, originY - 5, 10, 10);
        drawN(g, Math.abs(fx - nx) < 24.0f ? nx - 11 : nx + 3, originY + 4);
        g.setColor(FakeGraphics.RED);
        drawFakeBoundary(g, fx, originY, height);
        g.drawRect(fx - 5, originY - 5, 10, 10);
        drawF(g, fx + 3, originY + 4);
    }

    private static void drawAwt(Graphics2D g, float originX, float originY, float zoom,
                                int near, int far, boolean enemyFacing) {
        float scale = HITBOX_SCALE * zoom;
        float direction = enemyFacing ? 1.0f : -1.0f;
        int nx = Math.round(originX + direction * near * scale);
        int fx = Math.round(originX + direction * far * scale);
        int x0 = Math.min(nx, fx);
        int x1 = Math.max(nx, fx);
        int actualWidth = x1 - x0;
        int width = Math.max(3, actualWidth);
        int fillX = x0 - (width - actualWidth) / 2;
        int height = Math.max(48, Math.round(640.0f * scale));
        java.awt.Composite oldComposite = g.getComposite();
        Color oldColor = g.getColor();
        Stroke oldStroke = g.getStroke();
        try {
            g.setComposite(AlphaComposite.SrcOver.derive(0.25f));
            g.setColor(Color.CYAN);
            g.fillRect(fillX, Math.round(originY), width, height);
            g.setComposite(AlphaComposite.SrcOver);
            g.drawRect(fillX, Math.round(originY), width, height);
            g.setStroke(new BasicStroke(2.0f));
            g.setColor(Color.YELLOW.darker());
            g.drawLine(nx, Math.round(originY), nx, Math.round(originY) + height);
            g.drawRect(nx - 5, Math.round(originY) - 5, 10, 10);
            g.drawString("Near", Math.abs(fx - nx) < 48 ? nx - 38 : nx + 3,
                    Math.round(originY) + 14);
            g.setColor(Color.RED);
            g.drawLine(fx, Math.round(originY), fx, Math.round(originY) + height);
            g.drawRect(fx - 5, Math.round(originY) - 5, 10, 10);
            g.drawString("Far", fx + 3, Math.round(originY) + 14);
        } finally {
            g.setStroke(oldStroke);
            g.setComposite(oldComposite);
            g.setColor(oldColor);
        }
    }

    private static void drawFakeBoundary(FakeGraphics g, float x, float y, float height) {
        g.drawLine(x - 1, y, x - 1, y + height);
        g.drawLine(x, y, x, y + height);
        g.drawLine(x + 1, y, x + 1, y + height);
    }

    private static void drawN(FakeGraphics g, float x, float y) {
        g.drawLine(x, y, x, y + 10);
        g.drawLine(x, y, x + 7, y + 10);
        g.drawLine(x + 7, y, x + 7, y + 10);
    }

    private static void drawF(FakeGraphics g, float x, float y) {
        g.drawLine(x, y, x, y + 10);
        g.drawLine(x, y, x + 7, y);
        g.drawLine(x, y + 5, x + 5, y + 5);
    }

    private static float previewZoom(Object box) throws Exception {
        return AttackLdPreviewAccess.zoom(box);
    }

    private static P previewPan(Object box) throws Exception {
        return AttackLdPreviewAccess.pan();
    }

    private static void layout(State state) {
        if (state.width <= 0 || state.height <= 0 || state.panel == null) return;

        Page.set(state.panel, state.width, state.height, 300, 500, 800, 50);
        if (state.box instanceof Component)
            Page.set((Component) state.box, state.width, state.height,
                    300, 50, 800, state.visible ? 450 : 500);
        if (state.parts instanceof Component)
            Page.set((Component) state.parts, state.width, state.height,
                    1100, 50, 200, 500);
    }

    private static Owner selectedOwner(State state) {
        Object selected = state.owner == null ? null : state.owner.getSelectedItem();
        return selected instanceof Owner ? (Owner) selected : null;
    }

    private static String selectedOwnerKey(State state) {
        Owner owner = selectedOwner(state);
        return owner == null ? "" : owner.key;
    }

    private static void show(State state, String message) {
        if (!GraphicsEnvironment.isHeadless() && state.page instanceof Component)
            JOptionPane.showMessageDialog((Component) state.page, message,
                    "LD Points", JOptionPane.WARNING_MESSAGE);
    }

    private static void logDrawOnce(State state, Throwable error) {
        if (state.drawError) return;
        state.drawError = true;
        AttackFormLog.error("Animator LD preview draw failed", error);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Class<?> cursor = type;
        while (cursor != null) {
            try {
                Field field = cursor.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                cursor = cursor.getSuperclass();
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    private static final class DragListener extends MouseAdapter {
        private final State state;

        DragListener(State state) { this.state = state; }

        @Override public void mouseMoved(MouseEvent event) {
            if (!(state.box instanceof Component)) return;
            boolean handle = drawable(state) && state.geometryValid
                    && handleAt(state, event.getX(), event.getY()) != 0;
            ((Component) state.box).setCursor(Cursor.getPredefinedCursor(
                    handle ? Cursor.E_RESIZE_CURSOR : Cursor.DEFAULT_CURSOR));
        }

        @Override public void mouseExited(MouseEvent event) {
            if (state.dragHandle == 0 && state.box instanceof Component)
                ((Component) state.box).setCursor(Cursor.getDefaultCursor());
        }
    }

    private static final class State {
        final Object page;
        Object box;
        Object parts;
        JPanel panel;
        JComboBox<Owner> owner;
        JComboBox<String> hit;
        JTextField near;
        JTextField far;
        JCheckBox enemy;
        DragListener dragListener;
        List<Owner> owners = Collections.emptyList();
        AnimCE animation;
        int attackSlot = -1;
        int width;
        int height;
        boolean updating;
        boolean normalizePending;
        volatile boolean visible;
        volatile boolean previewValid;
        volatile int previewNear;
        volatile int previewFar;
        volatile boolean enemyFacing;
        volatile boolean geometryValid;
        volatile float lastOriginX;
        volatile float lastOriginY;
        volatile float lastZoom;
        volatile float lastHeight;
        int dragHandle;
        Owner dragOwner;
        int dragHit;
        int dragStartX;
        int dragStartValue;
        double dragScale;
        boolean drawError;

        State(Object page) { this.page = page; }
    }

    private static final class Owner {
        final PackData.UserPack pack;
        final Unit unit;
        final Form form;
        final CustomEntity entity;
        AttackFormSet forms;
        final int slot;
        final String key;
        final String label;

        Owner(PackData.UserPack pack, Unit unit, Form form, CustomEntity entity,
              AttackFormSet forms, int slot) {
            this.pack = pack;
            this.unit = unit;
            this.form = form;
            this.entity = entity;
            this.forms = forms;
            this.slot = slot;
            int unitId = unit.id == null ? -1 : unit.id.id;
            this.key = safe(pack.getSID()) + "/" + unitId + "/" + form.fid;
            String packName = pack.desc == null ? "" : safe(pack.desc.names);
            if (packName.isEmpty()) packName = safe(pack.getSID());
            String formName = safe(form.names);
            if (formName.isEmpty()) formName = safe(form.name);
            if (formName.isEmpty()) formName = "Unit " + unitId + " Form " + (form.fid + 1);
            this.label = packName + " / " + formName + " [" + unitId + ":" + form.fid + "]";
        }

        static Owner prototype() {
            return new Owner();
        }

        private Owner() {
            pack = null;
            unit = null;
            form = null;
            entity = null;
            forms = null;
            slot = 0;
            key = "";
            label = "Long pack name / Long unit form name [000:0]";
        }

        int hitCount() {
            if (slot == 0) return entity.atks == null ? 0 : entity.atks.length;
            forms = AttackFormStore.get(entity);
            return forms == null || forms.formCount() <= slot ? 0 : forms.hitCount(slot);
        }

        int[] ldPoints(int hit) {
            if (slot == 0) {
                AtkDataModel model = primary(hit);
                return new int[]{model.ld0, model.ld1};
            }
            forms = requireForms();
            return forms.ldPoints(slot, hit);
        }

        int[] setNear(int hit, int value) {
            if (slot == 0) {
                AtkDataModel model = primary(hit);
                if (value == Integer.MAX_VALUE && model.ld1 <= value
                        && (value != 0 || model.ld1 != 0))
                    throw new IllegalArgumentException("LD Near is too large");
                model.ld0 = value;
                if ((model.ld0 != 0 || model.ld1 != 0) && model.ld1 <= value) {
                    model.ld1 = value + 1;
                }
                synchronizePrimary();
                return new int[]{model.ld0, model.ld1};
            }
            return requireForms().setLdNear(slot, hit, value);
        }

        int[] setFar(int hit, int value) {
            if (slot == 0) {
                AtkDataModel model = primary(hit);
                if (value == Integer.MIN_VALUE && model.ld0 >= value
                        && (model.ld0 != 0 || value != 0))
                    throw new IllegalArgumentException("LD Far is too small");
                model.ld1 = value;
                if ((model.ld0 != 0 || model.ld1 != 0) && model.ld0 >= value) {
                    model.ld0 = value - 1;
                }
                synchronizePrimary();
                return new int[]{model.ld0, model.ld1};
            }
            return requireForms().setLdFar(slot, hit, value);
        }

        private AtkDataModel primary(int hit) {
            if (entity.atks == null || hit < 0 || hit >= entity.atks.length
                    || entity.atks[hit] == null)
                throw new IllegalStateException("Selected unit hit is unavailable");
            return entity.atks[hit];
        }

        private AttackFormSet requireForms() {
            forms = AttackFormStore.get(entity);
            if (forms == null || forms.formCount() <= slot)
                throw new IllegalStateException("Selected attack form is unavailable");
            return forms;
        }

        private void synchronizePrimary() {
            AttackFormSet current = AttackFormStore.get(entity);
            if (current != null) current.synchronizePrimary(entity);
            forms = current;
        }

        @Override public String toString() { return label; }
    }

    private static String safe(Object value) {
        try { return value == null ? "" : String.valueOf(value).trim(); }
        catch (Throwable ignored) { return ""; }
    }
}
