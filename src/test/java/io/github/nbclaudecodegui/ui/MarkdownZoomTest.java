package io.github.nbclaudecodegui.ui;

import io.github.nbclaudecodegui.ui.common.MarkdownRenderer;
import javax.swing.JEditorPane;
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
}
