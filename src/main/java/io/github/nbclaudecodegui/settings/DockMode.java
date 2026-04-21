package io.github.nbclaudecodegui.settings;

import java.util.Arrays;

/**
 * Dock positions for plugin-managed TopComponent tabs.
 *
 * <p>Each constant maps to a NetBeans window-manager mode name and a
 * human-readable label shown in the Options panel.
 */
public enum DockMode {

    /** Editor area (center). */
    EDITOR      ("editor",          "Editor area"),
    /** Right-side dock (commonpalette mode). */
    RIGHT       ("commonpalette",   "Right side"),
    /** Left-side top dock (explorer mode). */
    LEFT_TOP    ("explorer",        "Left side top"),
    /** Left-side bottom dock (navigator mode). */
    LEFT_BOTTOM ("navigator",       "Left side bottom"),
    /** Bottom dock (output mode). */
    BOTTOM      ("output",          "Bottom dock");

    private final String modeName;
    private final String label;

    DockMode(String modeName, String label) {
        this.modeName = modeName;
        this.label    = label;
    }

    /**
     * Returns the NetBeans window-manager mode name (e.g. {@code "editor"},
     * {@code "commonpalette"}).
     *
     * @return mode name string
     */
    public String getModeName() { return modeName; }

    /**
     * Returns the human-readable label for use in UI controls.
     *
     * @return display label
     */
    public String getLabel() { return label; }

    /**
     * Returns the constant whose {@link #getModeName()} matches {@code name},
     * or {@link #EDITOR} as fallback when no match is found.
     *
     * @param name mode name string to look up
     * @return matching constant, or {@link #EDITOR}
     */
    public static DockMode fromModeName(String name) {
        for (DockMode d : values()) {
            if (d.modeName.equals(name)) return d;
        }
        return EDITOR;
    }

    /**
     * Returns label strings for use in a {@link javax.swing.JComboBox}, with
     * {@code " (default)"} appended to the label of {@code defaultMode}.
     *
     * @param defaultMode the constant that should receive the "(default)" suffix
     * @return array of label strings in enum declaration order
     */
    public static String[] labels(DockMode defaultMode) {
        return Arrays.stream(values())
                .map(d -> d == defaultMode ? d.label + " (default)" : d.label)
                .toArray(String[]::new);
    }
}
