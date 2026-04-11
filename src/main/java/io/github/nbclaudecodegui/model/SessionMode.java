package io.github.nbclaudecodegui.model;

/**
 * Determines how a new Claude Code session is started.
 *
 * <ul>
 *   <li>{@link #CLOSE_ONLY} — stop the current session without starting a new one
 *       (only meaningful in the Save &amp; Switch dialog).</li>
 *   <li>{@link #RESTART_ADVANCED} — stop the current session and return to the
 *       selector panel in the same tab, allowing full reconfiguration before
 *       starting a new session (only meaningful in the Save &amp; Switch dialog).</li>
 *   <li>{@link #NEW} — start a fresh session (no {@code --continue} or
 *       {@code --resume} flag).</li>
 *   <li>{@link #CONTINUE_LAST} — resume the most recent session via
 *       {@code --continue}.</li>
 *   <li>{@link #RESUME_SPECIFIC} — resume a specific session via
 *       {@code --resume <id>}.</li>
 * </ul>
 */
public enum SessionMode {
    CLOSE_ONLY,
    RESTART_ADVANCED,
    NEW,
    CONTINUE_LAST,
    RESUME_SPECIFIC
}
