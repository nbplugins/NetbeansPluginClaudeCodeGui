package io.github.nbclaudecodegui.ui.common;

import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import java.awt.event.KeyEvent;

/** Shared zoom utilities for terminal and markdown preview surfaces. */
public final class ZoomSupport {

    private ZoomSupport() {}

    /**
     * Returns the modifier mask used for mouse-wheel zoom.
     * NetBeans standard binding is Alt+Wheel; we use that unconditionally.
     */
    public static int discoverZoomWheelModifiers() {
        return InputEvent.ALT_DOWN_MASK;
    }

    /** Creates a MouseWheelListener that calls zoomIn/zoomOut when Alt is held. */
    public static MouseWheelListener createWheelListener(Zoomable zoomable) {
        int mask = discoverZoomWheelModifiers();
        return (MouseWheelEvent e) -> {
            if ((e.getModifiersEx() & mask) != 0) {
                e.consume();
                if (e.getWheelRotation() < 0) {
                    zoomable.zoomIn();
                } else {
                    zoomable.zoomOut();
                }
            }
        };
    }

    /** Builds a "Zoom" JMenu with Increase, Decrease, and Reset items. */
    public static JMenu buildZoomMenu(Zoomable zoomable) {
        JMenu menu = new JMenu("Zoom");

        JMenuItem increase = new JMenuItem("Increase (Alt+Scroll Up)");
        increase.addActionListener(e -> zoomable.zoomIn());

        JMenuItem decrease = new JMenuItem("Decrease (Alt+Scroll Down)");
        decrease.addActionListener(e -> zoomable.zoomOut());

        JMenuItem reset = new JMenuItem("Reset (Ctrl+0)");
        reset.addActionListener(e -> zoomable.resetZoom());

        menu.add(increase);
        menu.add(decrease);
        menu.addSeparator();
        menu.add(reset);
        return menu;
    }

    /**
     * Appends a separator (only when {@code menu} already has items) followed by
     * the "Zoom" submenu produced by {@link #buildZoomMenu(Zoomable)} to the given
     * popup. Used to splice the Zoom controls into JediTerm's native terminal
     * context menu, which JediTerm builds internally and does not expose via
     * Swing's {@code setComponentPopupMenu}.
     *
     * @param menu     the popup to extend (typically JediTerm's native context menu);
     *                 must not be {@code null}
     * @param zoomable the surface whose font is zoomed by the submenu items; a
     *                 {@code null} value makes this a no-op
     */
    public static void appendZoomMenu(JPopupMenu menu, Zoomable zoomable) {
        if (zoomable == null) {
            return;
        }
        if (menu.getComponentCount() > 0) {
            menu.addSeparator();
        }
        menu.add(buildZoomMenu(zoomable));
    }

    /** Binds Ctrl+0 → resetZoom() on the given component via InputMap/ActionMap. */
    public static void bindResetKey(JComponent comp, Zoomable zoomable) {
        KeyStroke ks = KeyStroke.getKeyStroke(KeyEvent.VK_0, InputEvent.CTRL_DOWN_MASK);
        comp.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(ks, "zoom-reset");
        comp.getActionMap().put("zoom-reset", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { zoomable.resetZoom(); }
        });
    }
}
