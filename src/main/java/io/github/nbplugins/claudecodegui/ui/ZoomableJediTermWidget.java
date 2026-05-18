package io.github.nbplugins.claudecodegui.ui;

import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jediterm.terminal.ui.JediTermWidget;
import com.jediterm.terminal.ui.TerminalActionProvider;
import com.jediterm.terminal.ui.TerminalPanel;
import com.jediterm.terminal.ui.settings.SettingsProvider;
import io.github.nbplugins.claudecodegui.ui.common.Zoomable;
import io.github.nbplugins.claudecodegui.ui.common.ZoomSupport;
import javax.swing.JPopupMenu;

/**
 * A {@link JediTermWidget} that splices a "Zoom" submenu into the terminal's
 * native right-click context menu.
 *
 * <p>JediTerm builds its context menu internally: {@code TerminalPanel} has its
 * own mouse listener that calls the protected
 * {@code TerminalPanel.createPopupMenu(TerminalActionProvider)} and fills it from
 * the {@code TerminalActionProvider} chain. It never consults Swing's
 * {@code JComponent.setComponentPopupMenu()}. Adding a Zoom item via the Swing
 * popup mechanism therefore produces a <em>separate</em> single-item popup
 * instead of extending the native Copy/Paste/Clear menu.
 *
 * <p>The only clean integration point is to subclass {@code TerminalPanel} and
 * override {@code createPopupMenu}: build the native menu via {@code super}, then
 * append the Zoom submenu. This widget overrides the protected
 * {@link #createTerminalPanel} factory so JediTerm uses that customized panel,
 * and exposes {@link #refreshFont()} so callers can re-apply the font after a
 * zoom change without reflection.
 */
public class ZoomableJediTermWidget extends JediTermWidget {

    private final Zoomable zoomable;
    private ZoomTerminalPanel zoomPanel;

    /**
     * @param settingsProvider terminal settings (font, colors); also supplies the
     *                          zoom-adjusted font size
     * @param zoomable          the surface whose font the context-menu Zoom submenu
     *                          and {@link #refreshFont()} operate on
     */
    public ZoomableJediTermWidget(SettingsProvider settingsProvider, Zoomable zoomable) {
        super(settingsProvider);
        this.zoomable = zoomable;
    }

    /**
     * Re-applies the (possibly zoom-adjusted) font to the terminal and resizes
     * the grid. Delegates to {@code TerminalPanel.reinitFontAndResize()}, which
     * is {@code protected} and reachable only from within a panel subclass.
     */
    public void refreshFont() {
        if (zoomPanel != null) {
            zoomPanel.refreshFont();
        }
    }

    @Override
    protected TerminalPanel createTerminalPanel(SettingsProvider settingsProvider,
                                                StyleState styleState,
                                                TerminalTextBuffer textBuffer) {
        // Invoked from the JediTermWidget super-constructor (before this subclass's
        // constructor body runs). 'zoomable' is read only later, lazily, from
        // createPopupMenu()/refreshFont() — never here — so its not-yet-assigned
        // state at this point is harmless.
        zoomPanel = new ZoomTerminalPanel(settingsProvider, textBuffer, styleState);
        return zoomPanel;
    }

    /** Terminal panel that appends the Zoom submenu to the native context menu. */
    private final class ZoomTerminalPanel extends TerminalPanel {

        ZoomTerminalPanel(SettingsProvider settingsProvider,
                          TerminalTextBuffer textBuffer,
                          StyleState styleState) {
            super(settingsProvider, textBuffer, styleState);
        }

        @Override
        protected JPopupMenu createPopupMenu(TerminalActionProvider provider) {
            JPopupMenu menu = super.createPopupMenu(provider);
            ZoomSupport.appendZoomMenu(menu, zoomable);
            return menu;
        }

        void refreshFont() {
            reinitFontAndResize();
        }
    }
}
