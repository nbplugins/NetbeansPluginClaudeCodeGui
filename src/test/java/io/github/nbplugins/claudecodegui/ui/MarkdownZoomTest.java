package io.github.nbplugins.claudecodegui.ui;

import io.github.nbplugins.claudecodegui.ui.markdown.MarkdownRenderer;
import java.awt.event.KeyEvent;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.KeyStroke;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MarkdownZoomTest {

    private MarkdownPreviewTab tab;

    @BeforeEach
    void setup() {
        MarkdownPreviewTab.clearOpenTabsForTest();
        tab = new MarkdownPreviewTab();
        tab.pane = MarkdownRenderer.createOutputPane("<html><body>test</body></html>", null);
        tab.mdZoomDelta = 0;
    }

    @Test
    void zoomIn_incrementsDelta() {
        tab.zoomIn();
        assertEquals(1, tab.getZoomDelta());
    }

    @Test
    void zoomOut_decrementsDelta() {
        tab.zoomOut();
        assertEquals(-1, tab.getZoomDelta());
    }

    @Test
    void zoomIn_clampedAtMax() {
        for (int i = 0; i < 30; i++) tab.zoomIn();
        assertEquals(tab.getMaxDelta(), tab.getZoomDelta());
    }

    @Test
    void zoomOut_clampedAtMin() {
        for (int i = 0; i < 30; i++) tab.zoomOut();
        assertEquals(tab.getMinDelta(), tab.getZoomDelta());
    }

    @Test
    void resetZoom_setsZero() {
        tab.zoomIn();
        tab.zoomIn();
        tab.resetZoom();
        assertEquals(0, tab.getZoomDelta());
    }

    @Test
    void bindRefreshKey_registersF5InFocusedAndWindowMaps() {
        tab.bindRefreshKey(tab.pane);

        KeyStroke f5 = KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0);
        assertEquals("md-refresh",
                tab.pane.getInputMap(JComponent.WHEN_FOCUSED).get(f5),
                "F5 must be bound in WHEN_FOCUSED to beat the NetBeans global action");
        assertEquals("md-refresh",
                tab.pane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(f5));
        assertNotNull(tab.pane.getActionMap().get("md-refresh"));
    }
}
