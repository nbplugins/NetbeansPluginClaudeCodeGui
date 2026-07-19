package io.github.nbplugins.claudecodegui.ui;

import java.awt.KeyEventDispatcher;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ClaudeSessionTab}'s {@code zoomKeyInterceptor}, the
 * {@code KeyEventDispatcher} that handles Ctrl+zoom shortcuts while focus is
 * inside the embedded JediTerm widget (which otherwise consumes key events
 * before Swing's InputMap machinery sees them).
 */
class ClaudeSessionTabZoomKeyTest {

    @SuppressWarnings("unchecked")
    private static KeyEventDispatcher getInterceptor(ClaudeSessionTab tab) throws Exception {
        Field f = ClaudeSessionTab.class.getDeclaredField("zoomKeyInterceptor");
        f.setAccessible(true);
        return (KeyEventDispatcher) f.get(tab);
    }

    private static void setTerminalWidget(ClaudeSessionTab tab, ZoomableJediTermWidget widget) throws Exception {
        Field f = ClaudeSessionTab.class.getDeclaredField("terminalWidget");
        f.setAccessible(true);
        f.set(tab, widget);
    }

    private static KeyEvent keyPress(java.awt.Component src, int keyCode) {
        return new KeyEvent(src, KeyEvent.KEY_PRESSED, 0L, InputEvent.CTRL_DOWN_MASK,
                keyCode, KeyEvent.CHAR_UNDEFINED);
    }

    @Test
    void dispatchKeyEvent_ctrlNumpadPlusAndMinusTriggerZoom() throws Exception {
        ClaudeSessionTab tab = new ClaudeSessionTab();
        ZoomableJediTermWidget widget = new ZoomableJediTermWidget(new NetBeansSettingsProvider(), tab);
        setTerminalWidget(tab, widget);
        KeyEventDispatcher interceptor = getInterceptor(tab);

        int deltaBefore = tab.getZoomDelta();

        assertTrue(interceptor.dispatchKeyEvent(keyPress(widget, KeyEvent.VK_ADD)),
                "Ctrl+NumpadPlus must be consumed");
        java.awt.Toolkit.getDefaultToolkit().sync();
        waitForEdt();
        assertEquals(deltaBefore + 1, tab.getZoomDelta(), "Ctrl+NumpadPlus must zoom in");

        assertTrue(interceptor.dispatchKeyEvent(keyPress(widget, KeyEvent.VK_SUBTRACT)),
                "Ctrl+NumpadMinus must be consumed");
        waitForEdt();
        assertEquals(deltaBefore, tab.getZoomDelta(), "Ctrl+NumpadMinus must zoom back out");

        assertTrue(interceptor.dispatchKeyEvent(keyPress(widget, KeyEvent.VK_NUMPAD0)),
                "Ctrl+Numpad0 must be consumed");
        waitForEdt();
        assertEquals(0, tab.getZoomDelta(), "Ctrl+Numpad0 must reset zoom");
    }

    private static void waitForEdt() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> {});
    }
}
