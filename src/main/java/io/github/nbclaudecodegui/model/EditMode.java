package io.github.nbclaudecodegui.model;

import java.util.Optional;

/**
 * Claude Code interactive edit modes, cyclable via Shift+Tab in the TUI.
 *
 * <p>Each constant carries a {@link #key()} string that is used as the canonical
 * identifier in {@link ClaudeSessionModel#EDIT_MODE_REGISTRY}, in serialized
 * settings, and in logging.
 *
 * <p>Screen-text mapping (used by {@code ScreenContentDetector.detectEditMode}):
 * <ul>
 *   <li>{@link #PLAN} — bottom line contains {@code "plan mode"} or {@code "plan-mode"}</li>
 *   <li>{@link #ACCEPT_EDITS} — bottom line contains {@code "accept edits"}</li>
 *   <li>{@link #BYPASS_PERMISSIONS} — bottom line contains {@code "bypass permissions"};
 *       appears when Claude was launched with {@code --dangerously-skip-permissions}</li>
 *   <li>{@link #DEFAULT} — bottom line starts with {@code "  esc to interrupt"}
 *       (two leading spaces)</li>
 * </ul>
 */
public enum EditMode {

    /** Ask-on-edit — shows a diff dialog before every file change. */
    DEFAULT("default"),

    /** Plan mode — Claude proposes a plan before executing. */
    PLAN("plan"),

    /**
     * Accept-edits — auto-allows file changes inside the session's working
     * directory; shows a diff dialog for files outside.
     */
    ACCEPT_EDITS("acceptEdits"),

    /**
     * Bypass-permissions — auto-allows all file changes regardless of location.
     * Active when Claude was launched with {@code --dangerously-skip-permissions}.
     * Detected on screen as {@code ⏵⏵ bypass permissions on (shift+tab to cycle)}.
     */
    BYPASS_PERMISSIONS("bypassPermissions");

    // -------------------------------------------------------------------------

    private final String key;

    EditMode(String key) {
        this.key = key;
    }

    /**
     * Returns the canonical string key for this mode (e.g. {@code "acceptEdits"}).
     *
     * @return canonical key
     */
    public String key() {
        return key;
    }

    /**
     * Finds the {@code EditMode} whose {@link #key()} equals {@code key}.
     *
     * @param key the key to look up
     * @return matching mode, or {@link Optional#empty()} if not found
     */
    public static Optional<EditMode> fromKey(String key) {
        if (key == null) return Optional.empty();
        for (EditMode m : values()) {
            if (m.key.equals(key)) return Optional.of(m);
        }
        return Optional.empty();
    }

    /**
     * Finds the {@code EditMode} whose {@link #key()} equals {@code key},
     * falling back to {@link #DEFAULT} if not found.
     *
     * @param key the key to look up; {@code null} returns {@link #DEFAULT}
     * @return matching mode, or {@link #DEFAULT}
     */
    public static EditMode fromKeyOrDefault(String key) {
        return fromKey(key).orElse(DEFAULT);
    }
}
