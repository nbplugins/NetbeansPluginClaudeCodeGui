package io.github.nbclaudecodegui.ui.common;

import java.awt.Color;
import javax.swing.JButton;

/** Shared UI utility helpers for the plugin. */
public final class UiUtils {

    /** ✓ CHECK MARK — used on confirmation/positive action buttons. */
    public static final String ICON_CHECK = "\u2713";

    /** ✗ BALLOT X — used on decline/negative action buttons. */
    public static final String ICON_CROSS = "\u2717";

    private UiUtils() {}

    /**
     * Applies a theme-aware green ({@code positive=true}) or red ({@code positive=false}) style
     * to a button. Tries FlatLaf semantic color keys first ({@code Actions.Green} /
     * {@code Actions.Red}); falls back to luminance-based dark/light detection.
     */
    public static void applyActionStyle(JButton btn, boolean positive) {
        String flatKey = positive ? "Actions.Green" : "Actions.Red";
        Color resolved = javax.swing.UIManager.getColor(flatKey);
        if (resolved == null) {
            Color panelBg = javax.swing.UIManager.getColor("Panel.background");
            boolean dark = panelBg != null
                    && (panelBg.getRed() + panelBg.getGreen() + panelBg.getBlue()) < 384;
            resolved = positive
                    ? (dark ? new Color(60, 160, 60) : new Color(34, 139, 34))
                    : (dark ? new Color(180, 60, 60) : new Color(178, 34, 34));
        }
        final Color base = resolved;
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
