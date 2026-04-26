package io.github.nbclaudecodegui.ui;

import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.emulator.ColorPaletteImpl;
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider;
import io.github.nbclaudecodegui.settings.ClaudeCodePreferences;
import java.awt.Color;
import java.awt.Font;
import javax.swing.UIManager;

/**
 * JediTerm settings provider that adapts the terminal colors and font to the
 * current NetBeans IDE Look &amp; Feel theme and user preferences.
 *
 * <p><b>Font selection:</b> JediTerm uses a single {@link Font} with no
 * per-glyph fallback, so the chosen font must cover all Unicode characters
 * used by the Claude Code TUI (U+23F5 prompt marker, U+25D0 spinner,
 * box-drawing, dingbats). Font resolution is delegated to
 * {@link io.github.nbclaudecodegui.settings.ClaudeCodePreferences#resolveTerminalFontName()},
 * which prefers Adwaita Mono — the only widely-available FOSS monospace font
 * with full coverage of those characters, pre-installed on most Linux GNOME desktops.
 */
final class NetBeansSettingsProvider extends DefaultSettingsProvider {

    @Override
    public float getTerminalFontSize() {
        return ClaudeCodePreferences.getTerminalFontSize();
    }

    @Override
    public Font getTerminalFont() {
        return new Font(ClaudeCodePreferences.resolveTerminalFontName(),
                Font.PLAIN, (int) getTerminalFontSize());
    }

    @Override
    public TerminalColor getDefaultForeground() {
        Color fg = UIManager.getColor("EditorPane.foreground");
        if (fg == null) fg = UIManager.getColor("Panel.foreground");
        if (fg == null) return super.getDefaultForeground();
        return TerminalColor.rgb(fg.getRed(), fg.getGreen(), fg.getBlue());
    }

    @Override
    public TerminalColor getDefaultBackground() {
        Color bg = UIManager.getColor("EditorPane.background");
        if (bg == null) bg = UIManager.getColor("Panel.background");
        if (bg == null) return super.getDefaultBackground();
        return TerminalColor.rgb(bg.getRed(), bg.getGreen(), bg.getBlue());
    }

    @Override
    public TextStyle getSelectionColor() {
        Color selBg = UIManager.getColor("TextArea.selectionBackground");
        Color selFg = UIManager.getColor("TextArea.selectionForeground");
        if (selBg == null || selFg == null) return super.getSelectionColor();
        return new TextStyle(
                TerminalColor.rgb(selFg.getRed(), selFg.getGreen(), selFg.getBlue()),
                TerminalColor.rgb(selBg.getRed(), selBg.getGreen(), selBg.getBlue()));
    }

    @Override
    public com.jediterm.terminal.emulator.ColorPalette getTerminalColorPalette() {
        return ColorPaletteImpl.XTERM_PALETTE;
    }
}
