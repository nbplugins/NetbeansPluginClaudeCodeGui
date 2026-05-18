package io.github.nbclaudecodegui.ui.common;

import java.awt.event.InputEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ZoomSupportTest {

    @Test
    void discoverZoomWheelModifiers_returnsAltMask() {
        assertEquals(InputEvent.ALT_DOWN_MASK, ZoomSupport.discoverZoomWheelModifiers());
    }

    @Test
    void createWheelListener_callsZoomInOnUpWithAltModifier() {
        int[] calls = {0, 0};
        Zoomable z = new Zoomable() {
            @Override public void zoomIn()    { calls[0]++; }
            @Override public void zoomOut()   { calls[1]++; }
            @Override public void resetZoom() {}
            @Override public int getZoomDelta() { return 0; }
            @Override public int getMinDelta()  { return -8; }
            @Override public int getMaxDelta()  { return 20; }
        };
        MouseWheelListener listener = ZoomSupport.createWheelListener(z);
        JLabel src = new JLabel();
        MouseWheelEvent up = new MouseWheelEvent(src, MouseWheelEvent.MOUSE_WHEEL,
                0L, InputEvent.ALT_DOWN_MASK, 0, 0, 1, false,
                MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, -1);
        listener.mouseWheelMoved(up);
        assertEquals(1, calls[0]);
        assertEquals(0, calls[1]);
    }

    @Test
    void createWheelListener_ignoresWheelWithoutModifier() {
        int[] calls = {0, 0};
        Zoomable z = new Zoomable() {
            @Override public void zoomIn()    { calls[0]++; }
            @Override public void zoomOut()   { calls[1]++; }
            @Override public void resetZoom() {}
            @Override public int getZoomDelta() { return 0; }
            @Override public int getMinDelta()  { return -8; }
            @Override public int getMaxDelta()  { return 20; }
        };
        MouseWheelListener listener = ZoomSupport.createWheelListener(z);
        JLabel src = new JLabel();
        MouseWheelEvent noMod = new MouseWheelEvent(src, MouseWheelEvent.MOUSE_WHEEL,
                0L, 0, 0, 0, 1, false,
                MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, -1);
        listener.mouseWheelMoved(noMod);
        assertEquals(0, calls[0]);
        assertEquals(0, calls[1]);
    }

    @Test
    void buildZoomMenu_menuHasExpectedItems() {
        Zoomable z = new Zoomable() {
            @Override public void zoomIn()    {}
            @Override public void zoomOut()   {}
            @Override public void resetZoom() {}
            @Override public int getZoomDelta() { return 0; }
            @Override public int getMinDelta()  { return -8; }
            @Override public int getMaxDelta()  { return 20; }
        };
        JMenu menu = ZoomSupport.buildZoomMenu(z);
        assertEquals("Zoom", menu.getText());
        // 3 items + 1 separator = 4 components
        assertEquals(4, menu.getMenuComponentCount());
    }

    private static Zoomable stubZoomable() {
        return new Zoomable() {
            @Override public void zoomIn()    {}
            @Override public void zoomOut()   {}
            @Override public void resetZoom() {}
            @Override public int getZoomDelta() { return 0; }
            @Override public int getMinDelta()  { return -8; }
            @Override public int getMaxDelta()  { return 20; }
        };
    }

    @Test
    void appendZoomMenu_addsSeparatorAndZoomSubmenuAfterExistingItems() {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem first  = new JMenuItem("Copy");
        JMenuItem second = new JMenuItem("Paste");
        menu.add(first);
        menu.add(second);

        ZoomSupport.appendZoomMenu(menu, stubZoomable());

        assertEquals(4, menu.getComponentCount());
        assertSame(first,  menu.getComponent(0));
        assertSame(second, menu.getComponent(1));
        assertTrue(menu.getComponent(2) instanceof JSeparator);
        assertTrue(menu.getComponent(3) instanceof JMenu);
        JMenu zoom = (JMenu) menu.getComponent(3);
        assertEquals("Zoom", zoom.getText());
        assertEquals(4, zoom.getMenuComponentCount());
    }

    @Test
    void appendZoomMenu_noLeadingSeparatorOnEmptyMenu() {
        JPopupMenu menu = new JPopupMenu();

        ZoomSupport.appendZoomMenu(menu, stubZoomable());

        assertEquals(1, menu.getComponentCount());
        assertTrue(menu.getComponent(0) instanceof JMenu);
        assertEquals("Zoom", ((JMenu) menu.getComponent(0)).getText());
    }

    @Test
    void appendZoomMenu_nullZoomableIsNoOp() {
        JPopupMenu menu = new JPopupMenu();
        menu.add(new JMenuItem("Copy"));

        ZoomSupport.appendZoomMenu(menu, null);

        assertEquals(1, menu.getComponentCount());
    }

    // --- Scroll-forwarding overload (Markdown Preview regression) -------------

    private static Zoomable countingZoomable(int[] calls) {
        return new Zoomable() {
            @Override public void zoomIn()    { calls[0]++; }
            @Override public void zoomOut()   { calls[1]++; }
            @Override public void resetZoom() {}
            @Override public int getZoomDelta() { return 0; }
            @Override public int getMinDelta()  { return -8; }
            @Override public int getMaxDelta()  { return 20; }
        };
    }

    /** pane inside a JScrollPane; returns the scroll pane with a recording wheel listener. */
    private static JScrollPane scrolledPane(JEditorPane pane, int[] forwarded) {
        JScrollPane sp = new JScrollPane(pane);
        sp.addMouseWheelListener((MouseWheelEvent e) -> forwarded[0]++);
        return sp;
    }

    private static MouseWheelEvent wheel(java.awt.Component src, int modifiers) {
        return new MouseWheelEvent(src, MouseWheelEvent.MOUSE_WHEEL,
                0L, modifiers, 0, 0, 1, false,
                MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, -1);
    }

    @Test
    void createWheelListener_forwardTrue_redispatchesPlainWheelToScrollAncestor() {
        int[] calls = {0, 0};
        int[] forwarded = {0};
        JEditorPane pane = new JEditorPane();
        scrolledPane(pane, forwarded);

        MouseWheelListener listener = ZoomSupport.createWheelListener(countingZoomable(calls), true);
        MouseWheelEvent plain = wheel(pane, 0);
        listener.mouseWheelMoved(plain);

        assertEquals(1, forwarded[0], "plain wheel must be forwarded to the JScrollPane");
        assertEquals(0, calls[0]);
        assertEquals(0, calls[1]);
        assertFalse(plain.isConsumed(), "forwarded event must not be consumed");
    }

    @Test
    void createWheelListener_forwardFalse_doesNotForward_terminalBehaviorUnchanged() {
        int[] calls = {0, 0};
        int[] forwarded = {0};
        JEditorPane pane = new JEditorPane();
        scrolledPane(pane, forwarded);

        // Explicit false overload
        ZoomSupport.createWheelListener(countingZoomable(calls), false)
                .mouseWheelMoved(wheel(pane, 0));
        // Legacy single-arg method must keep the same (non-forwarding) behavior
        ZoomSupport.createWheelListener(countingZoomable(calls))
                .mouseWheelMoved(wheel(pane, 0));

        assertEquals(0, forwarded[0], "no forwarding when forwardToScrollAncestor is false");
        assertEquals(0, calls[0]);
        assertEquals(0, calls[1]);
    }

    @Test
    void createWheelListener_forwardTrue_altWheelStillZoomsAndIsNotForwarded() {
        int[] calls = {0, 0};
        int[] forwarded = {0};
        JEditorPane pane = new JEditorPane();
        scrolledPane(pane, forwarded);

        MouseWheelListener listener = ZoomSupport.createWheelListener(countingZoomable(calls), true);
        MouseWheelEvent alt = wheel(pane, InputEvent.ALT_DOWN_MASK);
        listener.mouseWheelMoved(alt);

        assertEquals(1, calls[0], "Alt+wheel up must zoom in");
        assertEquals(0, calls[1]);
        assertEquals(0, forwarded[0], "zoom event must not be forwarded to the scroll pane");
        assertTrue(alt.isConsumed(), "zoom event must be consumed");
    }
}
