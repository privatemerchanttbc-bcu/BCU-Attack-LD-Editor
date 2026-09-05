package manualcontrol.animedit;

import common.CommonStatic;
import common.system.P;
import common.system.fake.FakeGraphics;
import common.system.fake.FakeTransform;
import common.util.anim.AnimCE;
import common.util.anim.AnimU;
import common.util.anim.EAnimD;
import common.util.anim.EAnimI;
import common.util.anim.EPart;
import common.util.anim.MaAnim;
import common.util.anim.MaModel;
import common.util.anim.Part;
import manualcontrol.Logger;
import manualcontrol.attackform.AttackFormFeature;
import page.Page;
import page.anim.MaAnimEditPage;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JToggleButton;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.GridLayout;
import java.awt.KeyboardFocusManager;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public final class DirectEditSession implements TickStrip.DragHost {

    public enum Tool { SELECT, MOVE, ROTATE, SCALE, PIVOT, VISUAL }

    private static final int MODE_NONE = 0, MODE_MOVE = 1, MODE_ROTATE = 2,
            MODE_SCALE_U = 3, MODE_SCALE_X = 4, MODE_SCALE_Y = 5, MODE_PIVOT = 6,
            MODE_PIVOT_ABS = 7;
    private static final String[] MODE_NAMES =
            {"", "move", "rotate", "scale", "scale-x", "scale-y", "pivot", "pivot"};
    private static final double HANDLE_R = 10;
    private static final int[] EMPTY_FRAMES = new int[0];

    private static volatile boolean LAST_ON;
    private static volatile DirectEditSession ACTIVE_DRAG;
    private static final Map<DirectEditSession, Boolean> LIVE =
            Collections.synchronizedMap(new WeakHashMap<DirectEditSession, Boolean>());

    static {
        try {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
                if (e.getID() != KeyEvent.KEY_PRESSED)
                    return false;
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    DirectEditSession s = ACTIVE_DRAG;
                    if (s != null) {
                        s.cancelDrag();
                        return true;
                    }
                    return false;
                }
                DirectEditSession target = null;
                synchronized (LIVE) {
                    for (DirectEditSession s : LIVE.keySet())
                        if (s != null && s.on && s.mouseInside && s.page.isShowing()) {
                            target = s;
                            break;
                        }
                }
                if (target == null)
                    return false;
                Component fc = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                if (fc instanceof javax.swing.text.JTextComponent)
                    return false;
                return target.handleShortcut(e);
            });
        } catch (Throwable t) {
            Logger.err("DirectEdit: dispatcher init failed", t);
        }
    }

    private final MaAnimEditPage page;
    private Object ab;
    private JList<?> jlm;
    private JScrollPane jspp;
    private JTable mpet;
    private JTable maetTable;
    private AbstractButton jtbBtn;
    private JSlider jtlSlider;
    private AbstractButton undoBtn;

    private JToggleButton toggle;
    private JPanel panel;
    private JPanel cards;
    private JButton keyPartBtn;
    private final JToggleButton[] tools = new JToggleButton[6];
    private JComboBox<String> easing;
    private JSpinner easeParam;
    private JLabel status;
    private JLabel valInfo;
    private JLabel opaLabel;
    private JSlider opaSlider;
    private JList<String> spriteList;
    private TickStrip tick;

    volatile boolean on;
    volatile boolean paused;
    volatile int selectedPart = -1;
    volatile int hoverPart = -1;
    volatile Tool tool = Tool.SELECT;
    private volatile boolean mouseInside;
    private boolean canEditLast = false;
    private boolean uiGuard;
    private AnimCE lastAnim;
    private AnimCE visualAnim;
    private boolean visualDirty;

    private boolean pressedOnPart, pressWasEmpty, draggedSince;
    private boolean timerErr, drawErr, dragErr;
    private static final long UNDO_COOLDOWN_MS = 300;
    private long lastUndoAt;

    private int dragMode = MODE_NONE;
    private int dragInd;
    private MaAnim dragMa;
    private AnimCE dragAnim;
    private MaAnim dragBackup;
    private final Map<Integer, Part> dragTracks = new HashMap<Integer, Part>();
    private boolean dragDirty;
    private int[] dragArgs;
    private MaModel dragModel;
    private int dragModelInts0, dragModelInts1;
    private float dragSiz;
    private float dragPlayhead;
    private boolean dragRotate;
    private AffineTransform dragAt, dragParentAt;
    private double[] dragPartSize, dragFaSize;
    private double[] pressWorld, pivotW;
    private double pressLocalX, pressLocalY;
    private double startA, startB;

    public DirectEditSession(MaAnimEditPage page) {
        this.page = page;
    }

    Object box() {
        return ab;
    }

    void install() {
        if (!Compat.criticalOk) {
            Compat.notifyDisabled("missing internals: " + Compat.missingText());
            return;
        }
        try {
            ab = Compat.AB.get(page);
            jlm = (JList<?>) Compat.JLM.get(page);
            maetTable = (JTable) Compat.MAET.get(page);
            jtbBtn = (AbstractButton) Compat.JTB.get(page);
            jspp = Compat.JSPP != null ? (JScrollPane) Compat.JSPP.get(page) : null;
            mpet = Compat.MPET != null ? (JTable) Compat.MPET.get(page) : null;
            jtlSlider = Compat.JTL != null ? (JSlider) Compat.JTL.get(page) : null;
            if (Compat.AEP != null && Compat.AEP_UNDO != null) {
                Object aep = Compat.AEP.get(page);
                if (aep != null)
                    undoBtn = (AbstractButton) Compat.AEP_UNDO.get(aep);
            }
            if (undoBtn != null)
                undoBtn.addActionListener(e -> scheduleUndoTimeRestore());
            if (ab == null || jlm == null || maetTable == null || jtbBtn == null) {
                Compat.notifyDisabled("core page fields resolved to null");
                return;
            }
        } catch (Throwable t) {
            Logger.err("DirectEdit: page field access failed", t);
            Compat.notifyDisabled("page field access failed: " + t);
            return;
        }
        if (!Compat.USE_JOGL) {
            Logger.log("DirectEdit: AWT render mode detected - feature is GL-only, skipping");
            return;
        }
        toggle = new JToggleButton("Direct Edit");
        toggle.addActionListener(e -> setMode(toggle.isSelected()));

        buildPanel();

        page.add(toggle);
        page.add(panel);
        tick = new TickStrip();
        tick.setHost(this, jtlSlider);
        tick.setVisible(false);
        page.add(tick);
        page.setComponentZOrder(panel, 0);
        page.setComponentZOrder(tick, 0);

        Component canvas = (Component) ab;
        canvas.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                onHover(e);
            }
        });
        canvas.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                mouseInside = true;
            }

            @Override
            public void mouseExited(MouseEvent e) {
                mouseInside = false;
                hoverPart = -1;
            }
        });

        LIVE.put(this, Boolean.TRUE);
        if (LAST_ON) {
            toggle.setSelected(true);
            setMode(true);
        }
        Logger.log("DirectEdit: UI installed on MaAnimEditPage");
    }

    private void buildPanel() {
        panel = new JPanel(new BorderLayout(3, 3));
        panel.setBorder(BorderFactory.createEtchedBorder());

        JPanel toolGrid = new JPanel(new GridLayout(3, 2, 3, 3));
        String[] names = {"V Select", "W Move", "E Rotate", "R Scale", "T Pivot", "Y Visual"};
        ButtonGroup bg = new ButtonGroup();
        for (int i = 0; i < 6; i++) {
            final Tool t = Tool.values()[i];
            tools[i] = new JToggleButton(names[i]);
            tools[i].addActionListener(e -> selectTool(t));
            tools[i].setEnabled(false);
            small(tools[i]);
            bg.add(tools[i]);
            toolGrid.add(tools[i]);
        }
        tools[0].setSelected(true);
        keyPartBtn = new JButton("Key Part (snapshot pose)");
        keyPartBtn.setEnabled(false);
        small(keyPartBtn);
        keyPartBtn.addActionListener(e -> keyPartSnapshot());
        status = centered("");
        JPanel northSouth = new JPanel(new GridLayout(0, 1, 3, 3));
        northSouth.add(keyPartBtn);
        northSouth.add(status);
        JPanel north = new JPanel(new BorderLayout(3, 3));
        north.add(toolGrid, BorderLayout.CENTER);
        north.add(northSouth, BorderLayout.SOUTH);

        JPanel mainCard = new JPanel(new GridLayout(0, 1, 3, 3));
        easing = new JComboBox<String>(new String[]{"0  Linear", "1  Instant", "2  Ease (power)", "3  Smooth", "4  Sine"});
        easing.setEnabled(false);
        small(easing);
        JPanel easeRow = new JPanel(new GridLayout(1, 2, 3, 0));
        easeRow.add(centered("Easing:"));
        easeRow.add(easing);
        mainCard.add(easeRow);
        easeParam = new JSpinner(new SpinnerNumberModel(0, -100, 100, 1));
        easeParam.setEnabled(false);
        small(easeParam);
        JPanel paramRow = new JPanel(new GridLayout(1, 2, 3, 0));
        paramRow.add(centered("Param (2/4):"));
        paramRow.add(easeParam);
        mainCard.add(paramRow);
        valInfo = centered("");
        mainCard.add(valInfo);

        JPanel visualCard = new JPanel(new BorderLayout(3, 3));
        JPanel opa = new JPanel(new GridLayout(0, 1, 2, 2));
        opaLabel = centered("Opacity: -");
        opa.add(opaLabel);
        opaSlider = new JSlider(0, 1000, 1000);
        opaSlider.setEnabled(false);
        opaSlider.addChangeListener(e -> {
            if (uiGuard || !canEditLast || selectedPart < 0)
                return;
            if (visualPutKey(12, opaSlider.getValue()))
                visualDirty = true;
            if (!opaSlider.getValueIsAdjusting() && visualDirty)
                commitVisual("opacity");
        });
        opa.add(opaSlider);
        opa.add(centered("Sprite for current frame:"));
        visualCard.add(opa, BorderLayout.NORTH);
        spriteList = new JList<String>();
        spriteList.setEnabled(false);
        spriteList.addListSelectionListener(e -> {
            if (uiGuard || e.getValueIsAdjusting() || !canEditLast || selectedPart < 0)
                return;
            int idx = spriteList.getSelectedIndex();
            if (idx < 0)
                return;
            EAnimI ent = entity();
            if (ent == null || ent.ent == null || selectedPart >= ent.ent.length)
                return;
            if (idx == (int) ent.ent[selectedPart].getValRaw(2))
                return;
            if (visualPutKey(2, idx)) {
                visualDirty = true;
                commitVisual("sprite");
            }
        });
        visualCard.add(new JScrollPane(spriteList), BorderLayout.CENTER);

        cards = new JPanel(new CardLayout());
        cards.add(mainCard, "main");
        cards.add(visualCard, "visual");

        panel.add(north, BorderLayout.NORTH);
        panel.add(cards, BorderLayout.CENTER);
        panel.setVisible(false);
    }

    private void selectTool(Tool t) {
        tool = t;
        if (cards != null)
            ((CardLayout) cards.getLayout()).show(cards, t == Tool.VISUAL ? "visual" : "main");
    }

    private static JLabel centered(String s) {
        JLabel l = new JLabel(s, SwingConstants.CENTER);
        small(l);
        return l;
    }

    private static void small(Component c) {
        c.setFont(c.getFont() == null ? null : c.getFont().deriveFont(11f));
    }

    private void setMode(boolean b) {
        on = b;
        LAST_ON = b;
        hoverPart = -1;
        if (dragMode != MODE_NONE)
            cancelDrag();
        if (panel != null)
            panel.setVisible(b);
        if (jspp != null)
            jspp.setVisible(!b);
        if (b)
            selectTool(tool);
        else if (jlm != null) {
            int s = jlm.getSelectedIndex();
            if (s >= 0) {
                jlm.clearSelection();
                jlm.setSelectedIndex(s);
            }
        }
        pressedOnPart = pressWasEmpty = draggedSince = false;
        page.revalidate();
        page.repaint();
    }

    void resized(int x, int y) {
        if (toggle == null)
            return;
        Page.set(toggle, x, y, 760, 0, 190, 50);

        if (AttackFormFeature.isReady())
            Page.set(panel, x, y, 1100, 50, 200, 500);
        else
            Page.set(panel, x, y, 1000, 50, 300, 500);
        if (tick != null)
            Page.set(tick, x, y, 300, 550, 900, 24);
    }

    void onTimer() {
        if (toggle == null)
            return;
        try {
            paused = jtbBtn != null && jtbBtn.isSelected();
            EAnimI ent = entity();
            selectedPart = (on && ent != null && jlm != null) ? jlm.getSelectedIndex() : -1;
            if (on && ent != null && ent.sele >= 0)
                ent.sele = -1;
            if (!on || !paused) {
                hoverPart = -1;
                if (dragMode != MODE_NONE)
                    cancelDrag();
            }
            boolean canEdit = on && paused && ent != null;
            if (canEdit != canEditLast) {
                canEditLast = canEdit;
                for (JToggleButton t : tools)
                    if (t != null)
                        t.setEnabled(canEdit);
                if (easing != null)
                    easing.setEnabled(canEdit);
                if (easeParam != null)
                    easeParam.setEnabled(canEdit);
                if (opaSlider != null)
                    opaSlider.setEnabled(canEdit);
                if (spriteList != null)
                    spriteList.setEnabled(canEdit);
                if (keyPartBtn != null)
                    keyPartBtn.setEnabled(canEdit);
            }
            if (on && status != null) {
                if (ent == null)
                    status.setText("No animation loaded");
                else if (!paused)
                    status.setText("Playing - pause to edit");
                else if (selectedPart < 0)
                    status.setText("Click a part to select");
                else
                    status.setText(String.valueOf(jlm.getSelectedValue()));
            }
            updateValueReadout(ent);
            updateVisualCard(ent);
            updateTick(ent);
        } catch (Throwable t) {
            if (!timerErr) {
                timerErr = true;
                Logger.err("DirectEdit: timer update failed", t);
            }
        }
    }

    private void updateValueReadout(EAnimI ent) {
        if (valInfo == null)
            return;
        int sel = selectedPart;
        if (on && ent != null && ent.ent != null && sel >= 0 && sel < ent.ent.length) {
            EPart ep = ent.ent[sel];
            valInfo.setText("<html><center>Pos " + ep.getVal(4) + " | " + ep.getVal(5)
                    + " &nbsp; Ang " + ep.getVal(11)
                    + "<br>Scale " + ep.getVal(8)
                    + "<br>Opa " + ep.getVal(12) + " &nbsp; Spr " + ep.getVal(2)
                    + "</center></html>");
        } else {
            valInfo.setText("");
        }
    }

    private void updateVisualCard(EAnimI ent) {
        if (opaSlider == null || spriteList == null || maetTable == null)
            return;
        AnimCE anim = null;
        try {
            anim = (AnimCE) Compat.MAET_ANIM.get(maetTable);
        } catch (Throwable ignored) {
        }
        if (anim != lastAnim) {
            lastAnim = anim;
            uiGuard = true;
            try {
                if (anim == null || anim.imgcut == null) {
                    spriteList.setListData(new String[0]);
                } else {
                    String[] items = new String[anim.imgcut.n];
                    for (int i = 0; i < items.length; i++)
                        items[i] = i + " " + anim.imgcut.strs[i];
                    spriteList.setListData(items);
                }
            } finally {
                uiGuard = false;
            }
        }
        int sel = selectedPart;
        if (!on || ent == null || ent.ent == null || sel < 0 || sel >= ent.ent.length) {
            opaLabel.setText("Opacity: -");
            return;
        }
        EPart ep = ent.ent[sel];
        MaModel model = ep.getModel();
        int[] args = sel < model.parts.length ? model.parts[sel] : null;
        if (args != null && args[11] != 0 && !opaSlider.getValueIsAdjusting()) {
            int v = Math.round(ep.getValRaw(12) * model.ints[2] / args[11]);
            v = Math.max(0, Math.min(1000, v));
            if (v != opaSlider.getValue()) {
                uiGuard = true;
                opaSlider.setValue(v);
                uiGuard = false;
            }
        }
        opaLabel.setText("Opacity: " + opaSlider.getValue());
        int img = (int) ep.getValRaw(2);
        if (img >= 0 && img < spriteList.getModel().getSize() && spriteList.getSelectedIndex() != img) {
            uiGuard = true;
            spriteList.setSelectedIndex(img);
            spriteList.ensureIndexIsVisible(img);
            uiGuard = false;
        }
    }

    private void updateTick(EAnimI ent) {
        if (tick == null)
            return;
        int sel = selectedPart;
        if (!on || ent == null || maetTable == null) {
            tick.setVisible(false);
            return;
        }
        MaAnim ma = null;
        try {
            ma = (MaAnim) Compat.MAET_MA.get(maetTable);
        } catch (Throwable ignored) {
        }
        if (ma == null) {
            tick.setVisible(false);
            return;
        }
        List<Integer> selL = new ArrayList<Integer>();
        List<Integer> othL = new ArrayList<Integer>();
        for (int i = 0; i < ma.n; i++) {
            Part p = ma.parts[i];
            List<Integer> dst = p.ints[0] == sel ? selL : othL;
            for (int j = 0; j < p.n; j++)
                dst.add(p.moves[j][0] - p.off);
        }
        if (selL.isEmpty() && othL.isEmpty()) {
            tick.setVisible(false);
            tick.setData(EMPTY_FRAMES, EMPTY_FRAMES, 1);
            return;
        }
        tick.setData(toArray(selL), toArray(othL), ent.len());
        tick.setVisible(true);
    }

    private static int[] toArray(List<Integer> l) {
        int[] a = new int[l.size()];
        for (int i = 0; i < a.length; i++)
            a[i] = l.get(i);
        return a;
    }

    @Override
    public boolean timelineDragEnabled() {
        return on && paused && selectedPart >= 0 && dragMode == MODE_NONE;
    }

    @Override
    public void moveSelectedKeys(int from, int to) {
        try {
            if (maetTable == null || selectedPart < 0)
                return;
            MaAnim ma = (MaAnim) Compat.MAET_MA.get(maetTable);
            AnimCE anim = (AnimCE) Compat.MAET_ANIM.get(maetTable);
            EAnimI ent = entity();
            if (ma == null || anim == null || ent == null)
                return;
            int len = ent.len();
            if (to < 0)
                to = 0;
            if (len > 0 && to > len)
                to = len;
            if (AutoKey.moveKeys(ma, selectedPart, from, to)) {
                ent.setTime(ent.ind());
                maetTable.repaint();
                if (mpet != null)
                    mpet.repaint();
                anim.unSave("move keyframe");
                page.callBack(null);
            }
        } catch (Throwable t) {
            logDragErr(t);
        }
    }

    private boolean visualPutKey(int modif, int value) {
        try {
            if (maetTable == null || selectedPart < 0)
                return false;
            MaAnim ma = (MaAnim) Compat.MAET_MA.get(maetTable);
            AnimCE anim = (AnimCE) Compat.MAET_ANIM.get(maetTable);
            EAnimI ent = entity();
            if (ma == null || anim == null || ent == null)
                return false;
            Part tr = AutoKey.ensureTrack(ma, selectedPart, modif);
            int es = easing == null ? 0 : Math.max(0, easing.getSelectedIndex());
            int ep = easeParam == null ? 0 : ((Number) easeParam.getValue()).intValue();
            MaModel model = ent.ent != null && selectedPart < ent.ent.length
                    ? ent.ent[selectedPart].getModel() : null;
            AutoKey.putKey(ma, tr, ent.ind(), rotateFlag(ent), value, es, ep,
                    neutralValue(model, selectedPart, modif));
            ent.setTime(ent.ind());
            maetTable.repaint();
            if (mpet != null)
                mpet.repaint();
            visualAnim = anim;
            return true;
        } catch (Throwable t) {
            logDragErr(t);
            return false;
        }
    }

    private void keyPartSnapshot() {
        try {
            if (!canEditLast || selectedPart < 0 || maetTable == null)
                return;
            MaAnim ma = (MaAnim) Compat.MAET_MA.get(maetTable);
            AnimCE anim = (AnimCE) Compat.MAET_ANIM.get(maetTable);
            EAnimI ent = entity();
            if (ma == null || anim == null || ent == null || ent.ent == null
                    || selectedPart >= ent.ent.length)
                return;
            EPart ep = ent.ent[selectedPart];
            MaModel model = ep.getModel();
            if (selectedPart >= model.parts.length)
                return;
            int[] args = model.parts[selectedPart];
            float f = ent.ind();
            boolean rot = rotateFlag(ent);
            int es = easing == null ? 0 : Math.max(0, easing.getSelectedIndex());
            int epr = easeParam == null ? 0 : ((Number) easeParam.getValue()).intValue();

            List<int[]> keys = new ArrayList<int[]>();
            keys.add(new int[]{2, (int) ep.getValRaw(2)});
            keys.add(new int[]{4, (int) ep.getValRaw(4) - args[4]});
            keys.add(new int[]{5, (int) ep.getValRaw(5) - args[5]});
            keys.add(new int[]{6, (int) ep.getValRaw(6) - args[6]});
            keys.add(new int[]{7, (int) ep.getValRaw(7) - args[7]});
            keys.add(new int[]{11, Math.round(ep.getValRaw(11)) - args[10]});
            if (args[11] != 0)
                keys.add(new int[]{12, Math.round(ep.getValRaw(12) * model.ints[2] / args[11])});
            P sca = ep.getSca();
            boolean has8 = AutoKey.findTrack(ma, selectedPart, 8) != null;
            boolean has9 = AutoKey.findTrack(ma, selectedPart, 9) != null;
            boolean has10 = AutoKey.findTrack(ma, selectedPart, 10) != null;
            if (has8 && args[8] != 0)
                keys.add(new int[]{8, Math.round(sca.x * model.ints[0] / args[8])});
            if ((has9 || !has8) && args[8] != 0)
                keys.add(new int[]{9, Math.round(sca.x * model.ints[0] / args[8])});
            if ((has10 || !has8) && args[9] != 0)
                keys.add(new int[]{10, Math.round(sca.y * model.ints[0] / args[9])});

            for (int[] kv : keys) {
                Part tr = AutoKey.ensureTrack(ma, selectedPart, kv[0]);
                AutoKey.putKey(ma, tr, f, rot, kv[1], es, epr,
                        neutralValue(model, selectedPart, kv[0]));
            }
            ent.setTime(f);
            maetTable.repaint();
            if (mpet != null)
                mpet.repaint();
            anim.unSave("key part");
            page.callBack(null);
        } catch (Throwable t) {
            logDragErr(t);
        }
    }

    private void commitVisual(String name) {
        if (visualAnim != null && visualDirty) {
            try {
                visualAnim.unSave("direct edit " + name);
                page.callBack(null);
            } catch (Throwable t) {
                logDragErr(t);
            }
        }
        visualAnim = null;
        visualDirty = false;
    }

    private boolean handleShortcut(KeyEvent e) {
        int kc = e.getKeyCode();
        if (e.isControlDown()) {
            if (kc == KeyEvent.VK_Z) {
                long now = System.currentTimeMillis();
                if (now - lastUndoAt >= UNDO_COOLDOWN_MS && undoBtn != null && undoBtn.isEnabled()) {
                    lastUndoAt = now;
                    undoBtn.doClick();
                }
                return true;
            }
            return false;
        }
        switch (kc) {
            case KeyEvent.VK_V: return clickTool(0);
            case KeyEvent.VK_W: return clickTool(1);
            case KeyEvent.VK_E: return clickTool(2);
            case KeyEvent.VK_R: return clickTool(3);
            case KeyEvent.VK_T: return clickTool(4);
            case KeyEvent.VK_Y: return clickTool(5);
            case KeyEvent.VK_SPACE:
                if (jtbBtn != null)
                    jtbBtn.doClick();
                return true;
            case KeyEvent.VK_LEFT: return stepFrame(-1);
            case KeyEvent.VK_RIGHT: return stepFrame(1);
        }
        return false;
    }

    private boolean clickTool(int i) {
        if (tools[i] != null && tools[i].isEnabled())
            tools[i].doClick();
        return true;
    }

    private void scheduleUndoTimeRestore() {
        if (jtlSlider == null || jtbBtn == null || !jtbBtn.isSelected())
            return;
        SwingUtilities.invokeLater(() -> {
            try {
                if (!page.isShowing())
                    return;
                EAnimI ne = entity();
                if (ne == null)
                    return;
                float t = jtlSlider.getValue();
                if (CommonStatic.getConfig().performanceModeAnimation)
                    t /= 2f;
                int len = ne.len();
                if (len > 0 && t > len)
                    t = len;
                if (t < 0)
                    t = 0;
                ne.setTime(t);
            } catch (Throwable t2) {
                if (!timerErr) {
                    timerErr = true;
                    Logger.err("DirectEdit: undo time restore failed", t2);
                }
            }
        });
    }

    private boolean stepFrame(int d) {
        if (!paused)
            return true;
        EAnimI ent = entity();
        if (ent == null)
            return true;
        int len = ent.len();
        if (len <= 0)
            return true;
        int f = Math.round(ent.ind()) + d;
        f = ((f % len) + len) % len;
        ent.setTime(f);
        if (jtlSlider != null) {
            int max = jtlSlider.getMaximum();
            jtlSlider.setValue(Math.round(f * (float) max / len));
        }
        return true;
    }

    boolean mousePressed(MouseEvent e) {
        if (!on || ab == null || e.getSource() != ab)
            return false;
        pressedOnPart = false;
        pressWasEmpty = false;
        draggedSince = false;
        EAnimI ent = entity();
        if (!paused || ent == null)
            return false;
        double[] w = worldOf(e);

        if (!e.isControlDown()) {
            int hm = hitHandle(e, ent, w);
            if (hm != MODE_NONE) {
                beginDrag(e, ent, selectedPart, w, hm);
                pressedOnPart = true;
                return true;
            }
        }

        if (tool == Tool.PIVOT && selectedPart >= 0 && !e.isControlDown()) {
            if (beginDrag(e, ent, selectedPart, w, MODE_PIVOT_ABS))
                updateDrag(e);
            pressedOnPart = true;
            return true;
        }

        int ind;
        if (e.isControlDown()) {
            List<Integer> all = AnimGeometry.pickAll(ent, w[0], w[1], siz());
            if (all.isEmpty()) {
                pressWasEmpty = true;
                return false;
            }
            int cur = all.indexOf(selectedPart);
            ind = all.get(cur < 0 ? 0 : (cur + 1) % all.size());
        } else {
            ind = AnimGeometry.pick(ent, w[0], w[1], siz());
        }
        if (ind >= 0) {
            if (jlm != null) {
                jlm.setSelectedIndex(ind);
                jlm.ensureIndexIsVisible(ind);
                selectedPart = ind;
            }
            if (e.getClickCount() >= 2)
                jumpToKeyRow(ent);
            else if (e.getClickCount() == 1 && !e.isControlDown())
                beginDrag(e, ent, ind, w, toolBodyMode());
            pressedOnPart = true;
            return true;
        }
        pressWasEmpty = true;
        return false;
    }

    boolean mouseDragged(MouseEvent e) {
        if (!on)
            return false;
        if (dragMode != MODE_NONE) {
            updateDrag(e);
            return true;
        }
        if (pressedOnPart)
            return true;
        if (pressWasEmpty)
            draggedSince = true;
        return false;
    }

    boolean mouseReleased(MouseEvent e) {
        if (!on)
            return false;
        if (dragMode != MODE_NONE) {
            int mode = dragMode;
            boolean dirty = dragDirty;
            AnimCE anim = dragAnim;
            endDrag();
            if (dirty && anim != null) {
                try {
                    anim.unSave("direct edit " + MODE_NAMES[mode]);
                    page.callBack(null);
                } catch (Throwable t) {
                    logDragErr(t);
                }
            }
            pressedOnPart = false;
            return true;
        }
        if (pressedOnPart) {
            pressedOnPart = false;
            return true;
        }
        if (pressWasEmpty && !draggedSince && jlm != null)
            jlm.clearSelection();
        pressWasEmpty = false;
        draggedSince = false;
        return false;
    }

    boolean mouseWheel(MouseEvent e) {
        if (!on || ab == null || e.getSource() != ab || !(e instanceof MouseWheelEvent))
            return false;
        if (Compat.SET_SIZ == null)
            return false;
        if (dragMode != MODE_NONE)
            return true;
        try {
            MouseWheelEvent mwe = (MouseWheelEvent) e;
            Component canvas = (Component) ab;
            float siz = siz();
            float ns = siz * (float) Math.pow(0.95, mwe.getPreciseWheelRotation());
            if (ns <= 0 || Float.isNaN(ns) || Float.isInfinite(ns))
                return true;
            double[] w = AnimGeometry.toWorld(e.getX(), e.getY(),
                    canvas.getWidth(), canvas.getHeight(), Compat.ORI.x, Compat.ORI.y);
            double ratio = ns / siz;
            Compat.ORI.x = (float) (canvas.getWidth() / 2.0 - e.getX() + w[0] * ratio);
            Compat.ORI.y = (float) (canvas.getHeight() * 3.0 / 4.0 - e.getY() + w[1] * ratio);
            Compat.SET_SIZ.invoke(ab, ns);
            return true;
        } catch (Throwable t) {
            if (!timerErr) {
                timerErr = true;
                Logger.err("DirectEdit: zoom failed", t);
            }
            return false;
        }
    }

    private int toolBodyMode() {
        switch (tool) {
            case MOVE: return MODE_MOVE;
            case ROTATE: return MODE_ROTATE;
            case SCALE: return MODE_SCALE_U;
            default: return MODE_NONE;
        }
    }

    private int hitHandle(MouseEvent e, EAnimI ent, double[] w) {
        int sel = selectedPart;
        if (sel < 0 || ent.ent == null || sel >= ent.ent.length)
            return MODE_NONE;
        try {
            AnimGeometry.PartBox b = AnimGeometry.partBox(ent.ent[sel], ent, siz());
            if (e.isAltDown() && dist(w, b.pivotX, b.pivotY) <= HANDLE_R)
                return MODE_PIVOT;
            if (!b.hasSprite)
                return MODE_NONE;
            if (tool == Tool.ROTATE) {
                for (int i = 0; i < 4; i++)
                    if (dist(w, b.xs[i], b.ys[i]) <= HANDLE_R)
                        return MODE_ROTATE;
            } else if (tool == Tool.SCALE) {
                for (int i = 0; i < 4; i++)
                    if (dist(w, b.xs[i], b.ys[i]) <= HANDLE_R)
                        return MODE_SCALE_U;
                for (int i = 0; i < 4; i++) {
                    int j = (i + 1) % 4;
                    double mx = (b.xs[i] + b.xs[j]) / 2, my = (b.ys[i] + b.ys[j]) / 2;
                    if (dist(w, mx, my) <= HANDLE_R)
                        return (i % 2 == 0) ? MODE_SCALE_Y : MODE_SCALE_X;
                }
            }
        } catch (Throwable t) {
            logDragErr(t);
        }
        return MODE_NONE;
    }

    private static double dist(double[] w, double x, double y) {
        return Math.hypot(w[0] - x, w[1] - y);
    }

    private boolean beginDrag(MouseEvent e, EAnimI ent, int ind, double[] w, int mode) {
        if (mode == MODE_NONE || ind < 0 || ent.ent == null || ind >= ent.ent.length || maetTable == null)
            return false;
        try {
            dragMa = (MaAnim) Compat.MAET_MA.get(maetTable);
            dragAnim = (AnimCE) Compat.MAET_ANIM.get(maetTable);
            if (dragMa == null || dragAnim == null)
                return false;
            EPart ep = ent.ent[ind];
            MaModel model = ep.getModel();
            if (ind >= model.parts.length)
                return false;
            dragArgs = model.parts[ind];
            dragModel = model;
            dragModelInts0 = model.ints[0];
            dragModelInts1 = model.ints[1];
            dragSiz = siz();
            dragAt = AnimGeometry.partTransform(ep, dragSiz);
            EPart fa = ep.getFa();
            dragParentAt = fa != null ? AnimGeometry.partTransform(fa, dragSiz) : new AffineTransform();
            dragPartSize = AnimGeometry.partSize(ep);
            dragFaSize = fa != null ? AnimGeometry.partSize(fa) : new double[]{1, 1};
            pressWorld = w;
            Point2D pl = dragAt.createInverse().transform(new Point2D.Double(w[0], w[1]), null);
            pressLocalX = pl.getX();
            pressLocalY = pl.getY();
            Point2D pv = dragAt.transform(new Point2D.Double(0, 0), null);
            pivotW = new double[]{pv.getX(), pv.getY()};
            switch (mode) {
                case MODE_MOVE:
                    startA = ep.getValRaw(4);
                    startB = ep.getValRaw(5);
                    break;
                case MODE_ROTATE:
                    startA = ep.getValRaw(11);
                    break;
                case MODE_SCALE_U: {
                    P sca = ep.getSca();
                    if (dragArgs[8] != 0)
                        startA = sca.x * (double) dragModelInts0 / dragArgs[8];
                    else if (dragArgs[9] != 0)
                        startA = sca.y * (double) dragModelInts0 / dragArgs[9];
                    else
                        return false;
                    break;
                }
                case MODE_SCALE_X:
                    if (dragArgs[8] == 0)
                        return false;
                    startA = ep.getSca().x * (double) dragModelInts0 / dragArgs[8];
                    break;
                case MODE_SCALE_Y:
                    if (dragArgs[9] == 0)
                        return false;
                    startA = ep.getSca().y * (double) dragModelInts0 / dragArgs[9];
                    break;
                case MODE_PIVOT:
                case MODE_PIVOT_ABS:
                    startA = ep.getValRaw(6);
                    startB = ep.getValRaw(7);
                    break;
            }
            dragPlayhead = ent.ind();
            dragRotate = rotateFlag(ent);
            dragBackup = dragMa.clone();
            dragTracks.clear();
            dragDirty = false;
            dragInd = ind;
            dragMode = mode;
            ACTIVE_DRAG = this;
            return true;
        } catch (Throwable t) {
            logDragErr(t);
            dragMode = MODE_NONE;
            return false;
        }
    }

    private void updateDrag(MouseEvent e) {
        if (dragMode == MODE_NONE)
            return;
        try {
            double[] cw = worldOf(e);
            switch (dragMode) {
                case MODE_MOVE: {
                    Point2D dl = dragParentAt.createInverse().deltaTransform(
                            new Point2D.Double(cw[0] - pressWorld[0], cw[1] - pressWorld[1]), null);
                    double fx = dragFaSize[0] * dragSiz, fy = dragFaSize[1] * dragSiz;
                    if (Math.abs(fx) < 1e-9 || Math.abs(fy) < 1e-9)
                        return;
                    writeKey(4, Math.round((float) (startA + dl.getX() / fx)) - dragArgs[4]);
                    writeKey(5, Math.round((float) (startB + dl.getY() / fy)) - dragArgs[5]);
                    break;
                }
                case MODE_ROTATE: {
                    double a0 = Math.atan2(pressWorld[1] - pivotW[1], pressWorld[0] - pivotW[0]);
                    double a1 = Math.atan2(cw[1] - pivotW[1], cw[0] - pivotW[0]);
                    double dth = a1 - a0;
                    while (dth > Math.PI) dth -= 2 * Math.PI;
                    while (dth < -Math.PI) dth += 2 * Math.PI;
                    double sign = dragAt.getDeterminant() >= 0 ? 1 : -1;
                    double na = startA + sign * dth / (2 * Math.PI) * dragModelInts1;
                    writeKey(11, Math.round((float) na) - dragArgs[10]);
                    DragHud.show(String.format("%+.1f°", sign * Math.toDegrees(dth)), e.getLocationOnScreen());
                    break;
                }
                case MODE_SCALE_U: {
                    Point2D cl = dragAt.createInverse().transform(new Point2D.Double(cw[0], cw[1]), null);
                    double denom = pressLocalX * pressLocalX + pressLocalY * pressLocalY;
                    if (denom < 1e-9)
                        return;
                    double r = (cl.getX() * pressLocalX + cl.getY() * pressLocalY) / denom;
                    int v = Math.round((float) (startA * r));
                    writeKey(8, v);
                    DragHud.show(pct(v) + "%", e.getLocationOnScreen());
                    break;
                }
                case MODE_SCALE_X: {
                    Point2D cl = dragAt.createInverse().transform(new Point2D.Double(cw[0], cw[1]), null);
                    if (Math.abs(pressLocalX) < 1e-6)
                        return;
                    int v = Math.round((float) (startA * (cl.getX() / pressLocalX)));
                    writeKey(9, v);
                    DragHud.show("X " + pct(v) + "%", e.getLocationOnScreen());
                    break;
                }
                case MODE_SCALE_Y: {
                    Point2D cl = dragAt.createInverse().transform(new Point2D.Double(cw[0], cw[1]), null);
                    if (Math.abs(pressLocalY) < 1e-6)
                        return;
                    int v = Math.round((float) (startA * (cl.getY() / pressLocalY)));
                    writeKey(10, v);
                    DragHud.show("Y " + pct(v) + "%", e.getLocationOnScreen());
                    break;
                }
                case MODE_PIVOT: {
                    Point2D dl = dragAt.createInverse().deltaTransform(
                            new Point2D.Double(cw[0] - pressWorld[0], cw[1] - pressWorld[1]), null);
                    double fx = dragPartSize[0] * dragSiz, fy = dragPartSize[1] * dragSiz;
                    if (Math.abs(fx) < 1e-9 || Math.abs(fy) < 1e-9)
                        return;
                    writeKey(6, Math.round((float) (startA + dl.getX() / fx)) - dragArgs[6]);
                    writeKey(7, Math.round((float) (startB + dl.getY() / fy)) - dragArgs[7]);
                    break;
                }
                case MODE_PIVOT_ABS: {
                    Point2D cl = dragAt.createInverse().transform(
                            new Point2D.Double(cw[0], cw[1]), null);
                    double fx = dragPartSize[0] * dragSiz, fy = dragPartSize[1] * dragSiz;
                    if (Math.abs(fx) < 1e-9 || Math.abs(fy) < 1e-9)
                        return;
                    writeKey(6, Math.round((float) (startA + cl.getX() / fx)) - dragArgs[6]);
                    writeKey(7, Math.round((float) (startB + cl.getY() / fy)) - dragArgs[7]);
                    break;
                }
            }
            refreshAfterEdit();
        } catch (Throwable t) {
            logDragErr(t);
        }
    }

    private String pct(int v) {
        return String.valueOf(Math.round(v * 100.0f / dragModelInts0));
    }

    private void writeKey(int modif, int value) {
        Part tr = dragTracks.get(modif);
        if (tr == null) {
            tr = AutoKey.ensureTrack(dragMa, dragInd, modif);
            dragTracks.put(modif, tr);
        }
        int es = easing == null ? 0 : Math.max(0, easing.getSelectedIndex());
        int ep = easeParam == null ? 0 : ((Number) easeParam.getValue()).intValue();
        AutoKey.putKey(dragMa, tr, dragPlayhead, dragRotate, value, es, ep,
                neutralValue(dragModel, dragInd, modif));
        dragDirty = true;
    }

    private static int neutralValue(MaModel model, int partInd, int modif) {
        if (model == null)
            return 0;
        switch (modif) {
            case 8:
            case 9:
            case 10:
                return model.ints[0];
            case 12:
                return model.ints[2];
            case 2:
                return partInd < model.parts.length ? model.parts[partInd][2] : 0;
            default:
                return 0;
        }
    }

    private void refreshAfterEdit() {
        EAnimI ent = entity();
        if (ent != null)
            ent.setTime(dragPlayhead);
        if (maetTable != null)
            maetTable.repaint();
        if (mpet != null)
            mpet.repaint();
    }

    private void cancelDrag() {
        if (dragMode == MODE_NONE)
            return;
        try {
            if (dragBackup != null && dragMa != null) {
                dragMa.n = dragBackup.n;
                dragMa.parts = dragBackup.parts;
                dragMa.validate();
                EAnimI ent = entity();
                if (ent != null)
                    ent.setTime(dragPlayhead);
                if (maetTable != null)
                    maetTable.repaint();
                if (mpet != null)
                    mpet.repaint();
                if (jlm != null) {
                    int s = jlm.getSelectedIndex();
                    if (s >= 0) {
                        jlm.clearSelection();
                        jlm.setSelectedIndex(s);
                    }
                }
            }
        } catch (Throwable t) {
            logDragErr(t);
        }
        endDrag();
    }

    private void endDrag() {
        dragMode = MODE_NONE;
        dragBackup = null;
        dragTracks.clear();
        ACTIVE_DRAG = null;
        DragHud.hide();
    }

    private static boolean rotateFlag(EAnimI ent) {
        try {
            if (ent instanceof EAnimD) {
                Object t = ((EAnimD<?>) ent).type;
                if (t instanceof AnimU.UType)
                    return ((AnimU.UType) t).rotate();
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    private void logDragErr(Throwable t) {
        if (!dragErr) {
            dragErr = true;
            Logger.err("DirectEdit: drag failed", t);
        }
    }

    private void onHover(MouseEvent e) {
        if (!on || !paused || dragMode != MODE_NONE) {
            hoverPart = -1;
            return;
        }
        try {
            EAnimI ent = entity();
            if (ent == null) {
                hoverPart = -1;
                return;
            }
            double[] w = worldOf(e);
            hoverPart = AnimGeometry.pick(ent, w[0], w[1], siz());
        } catch (Throwable t) {
            hoverPart = -1;
        }
    }

    private void jumpToKeyRow(EAnimI ent) {
        try {
            if (mpet == null || Compat.MPET_PART == null)
                return;
            Object partObj = Compat.MPET_PART.get(mpet);
            if (!(partObj instanceof Part))
                return;
            Part p = (Part) partObj;
            if (p.n <= 0)
                return;
            float f = ent.ind();
            int best = 0;
            double bd = Double.MAX_VALUE;
            for (int r = 0; r < p.n; r++) {
                double d = Math.abs(p.moves[r][0] - p.off - f);
                if (d < bd) {
                    bd = d;
                    best = r;
                }
            }
            mpet.setRowSelectionInterval(best, best);
            mpet.scrollRectToVisible(mpet.getCellRect(best, 0, true));
        } catch (Throwable t) {
            if (!timerErr) {
                timerErr = true;
                Logger.err("DirectEdit: keyframe row jump failed", t);
            }
        }
    }

    void drawOverlay(FakeGraphics g) {
        if (!on || !paused)
            return;
        try {
            EAnimI ent = entity();
            if (ent == null || ent.ent == null)
                return;
            float s = siz();
            int hov = hoverPart, sel = selectedPart;
            if (hov < 0 && sel < 0)
                return;
            FakeTransform at = g.getTransform();
            g.translate(-Compat.ORI.x, -Compat.ORI.y);
            try {
                if (hov >= 0 && hov != sel && hov < ent.ent.length)
                    DirectEditOverlay.drawHover(g, ent, hov, s);
                if (sel >= 0 && sel < ent.ent.length)
                    DirectEditOverlay.draw(g, ent, sel, s,
                            tool == Tool.ROTATE, tool == Tool.SCALE);
            } finally {
                g.setTransform(at);
                g.delete(at);
            }
        } catch (Throwable t) {
            if (!drawErr) {
                drawErr = true;
                Logger.err("DirectEdit: overlay draw failed", t);
            }
        }
    }

    private double[] worldOf(MouseEvent e) {
        Component canvas = (Component) ab;
        return AnimGeometry.toWorld(e.getX(), e.getY(),
                canvas.getWidth(), canvas.getHeight(), Compat.ORI.x, Compat.ORI.y);
    }

    private EAnimI entity() {
        try {
            return (EAnimI) Compat.GET_ENTITY.invoke(ab);
        } catch (Throwable t) {
            return null;
        }
    }

    private float siz() {
        try {
            return ((Float) Compat.GET_SIZ.invoke(ab)).floatValue();
        } catch (Throwable t) {
            return 0.5f;
        }
    }
}
