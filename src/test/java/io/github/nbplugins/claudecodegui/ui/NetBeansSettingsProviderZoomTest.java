package io.github.nbplugins.claudecodegui.ui;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class NetBeansSettingsProviderZoomTest {

    @Test
    void setZoomDelta_affectsGetTerminalFontSize() {
        NetBeansSettingsProvider p = new NetBeansSettingsProvider();
        float base = p.getTerminalFontSize();
        p.setZoomDelta(3);
        assertEquals(base + 3, p.getTerminalFontSize(), 0.01f);
    }

    @Test
    void defaultDeltaIsZero() {
        NetBeansSettingsProvider p = new NetBeansSettingsProvider();
        assertEquals(0, p.getTerminalFontSize() - p.getTerminalFontSize(), 0.01f);
        // verify delta 0 does not shift size
        float before = p.getTerminalFontSize();
        p.setZoomDelta(0);
        assertEquals(before, p.getTerminalFontSize(), 0.01f);
    }
}
