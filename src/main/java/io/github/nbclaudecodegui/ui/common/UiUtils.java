package io.github.nbclaudecodegui.ui.common;

import io.github.nbclaudecodegui.settings.DockMode;
import java.awt.Color;
import javax.swing.JButton;
import javax.swing.UIManager;
import org.openide.windows.TopComponent;
import org.openide.windows.WindowManager;

/** Shared UI utility helpers for the plugin. */
public final class UiUtils {

    /** ✓ CHECK MARK — used on confirmation/positive action buttons. */
    public static final String ICON_CHECK = "\u2713";

    /** ✗ BALLOT X — used on decline/negative action buttons. */
    public static final String ICON_CROSS = "\u2717";

    private UiUtils() {}

    /**
     * Docks {@code tc} into the window-manager mode specified by {@code mode},
     * then opens the component.
     *
     * <p>When {@code mode} is {@link DockMode#EDITOR} no docking call is made and
     * the component opens in its default position, preserving any manual
     * repositioning the user may have done.
     *
     * @param tc   the TopComponent to open
     * @param mode desired dock position
     */
    public static void dockAndOpen(TopComponent tc, DockMode mode) {
        if (mode != DockMode.EDITOR) {
            org.openide.windows.Mode m =
                    WindowManager.getDefault().findMode(mode.getModeName());
            if (m != null) {
                m.dockInto(tc);
            }
        }
        tc.open();
    }

    /**
     * Resolves a theme-aware color: tries the FlatLaf {@code UIManager} key first;
     * falls back to {@code light} or {@code dark} based on the luminance of
     * {@code Panel.background} (sum of RGB components &lt; 384 → dark theme).
     *
     * @param flatLafKey UIManager key to try first (e.g. {@code "Actions.Green"})
     * @param light      color to use in light themes when the key is absent
     * @param dark       color to use in dark themes when the key is absent
     * @return resolved {@link Color}, never {@code null}
     */
    public static Color resolveColor(String flatLafKey, Color light, Color dark) {
        Color resolved = UIManager.getColor(flatLafKey);
        if (resolved != null) return resolved;
        Color panelBg = UIManager.getColor("Panel.background");
        boolean isDark = panelBg != null
                && (panelBg.getRed() + panelBg.getGreen() + panelBg.getBlue()) < 384;
        return isDark ? dark : light;
    }

    /** Returns the theme-aware positive/confirm color (green). */
    public static Color getPositiveColor() {
        return resolveColor("Actions.Green", new Color(34, 139, 34), new Color(60, 160, 60));
    }

    /** Returns the theme-aware negative/cancel color (red). */
    public static Color getNegativeColor() {
        return resolveColor("Actions.Red", new Color(178, 34, 34), new Color(180, 60, 60));
    }

    /** Returns the theme-aware warning/accent color (orange). */
    public static Color getWarningColor() {
        return resolveColor("Actions.Yellow", new Color(180, 100, 0), new Color(200, 130, 0));
    }

    /**
     * Formats a {@link Color} as an HTML hex string (e.g. {@code "#228B22"}).
     * Useful for {@code <font color='...'>} in HTML-rendered Swing labels/buttons.
     *
     * @param c the color to format
     * @return six-digit uppercase hex string prefixed with {@code #}
     */
    public static String toHex(Color c) {
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    /**
     * Applies a theme-aware green ({@code positive=true}) or red ({@code positive=false})
     * background style to a button: sets opaque background, white foreground, and a
     * brighter focus highlight. Uses {@link #getPositiveColor()} / {@link #getNegativeColor()}.
     */
    public static void applyActionStyle(JButton btn, boolean positive) {
        final Color base = positive ? getPositiveColor() : getNegativeColor();
        final Color focus = base.brighter();
        btn.setOpaque(true);
        btn.setBackground(base);
        btn.setForeground(Color.WHITE);
        btn.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusGained(java.awt.event.FocusEvent e) { btn.setBackground(focus); }
            @Override public void focusLost(java.awt.event.FocusEvent e)   { btn.setBackground(base); }
        });
    }
}
