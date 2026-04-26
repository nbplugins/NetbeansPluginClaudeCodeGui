package io.github.nbclaudecodegui.ui;

import io.github.nbclaudecodegui.settings.ClaudeCodePreferences;
import java.awt.Font;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the font selection logic in {@link NetBeansSettingsProvider}.
 */
class NetBeansSettingsProviderTest {

    private final NetBeansSettingsProvider provider = new NetBeansSettingsProvider();

    @AfterEach
    void tearDown() {
        ClaudeCodePreferences.setTerminalFontName(ClaudeCodePreferences.DEFAULT_TERMINAL_FONT_NAME);
        ClaudeCodePreferences.setTerminalFontSize(ClaudeCodePreferences.DEFAULT_TERMINAL_FONT_SIZE);
    }

    @Test
    void autoModeReturnsNonNullFontWithNoDialogFamily() {
        ClaudeCodePreferences.setTerminalFontName("");
        Font f = provider.getTerminalFont();
        assertNotNull(f);
        // "Dialog" is Java's fallback for unknown fonts; auto-detection must
        // always resolve to a real font (at minimum "Monospaced" is available)
        assertNotEquals("Dialog", f.getFamily(),
                "Auto mode must not fall back to the Dialog family");
    }

    @Test
    void explicitFontNameUsedDirectly() {
        ClaudeCodePreferences.setTerminalFontName("Monospaced");
        Font f = provider.getTerminalFont();
        assertEquals("Monospaced", f.getFamily());
    }

    @Test
    void fontSizeReadFromPreferences() {
        ClaudeCodePreferences.setTerminalFontSize(20);
        assertEquals(20.0f, provider.getTerminalFontSize());
        assertEquals(20, provider.getTerminalFont().getSize());
    }

    @Test
    void defaultFontSizeIs14() {
        assertEquals(14.0f, provider.getTerminalFontSize());
    }
}
