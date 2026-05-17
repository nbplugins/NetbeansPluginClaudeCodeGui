package io.github.nbclaudecodegui.ui.common;

import java.awt.event.InputEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import javax.swing.JLabel;
import javax.swing.JMenu;
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
}
