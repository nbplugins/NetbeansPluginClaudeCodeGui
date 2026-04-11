package io.github.nbclaudecodegui.settings;

import io.github.nbclaudecodegui.model.SessionMode;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.logging.Logger;
import org.openide.modules.Places;
import org.openide.util.NbPreferences;

/**
 * Typed access to Claude Code GUI plugin preferences stored via
 * {@link NbPreferences}.
 */
public final class ClaudeCodePreferences {

    /** Preference key: path to the {@code claude} CLI executable. */
    public static final String KEY_CLAUDE_EXECUTABLE_PATH = "claudeExecutablePath";
    /** Default: empty — resolved from {@code PATH} at runtime. */
    public static final String DEFAULT_CLAUDE_EXECUTABLE_PATH = "";

    /** Preference key: key combination that sends the prompt. */
    public static final String KEY_SEND_KEY = "sendKey";
    /** Preference key: key combination that inserts a newline. */
    public static final String KEY_NEWLINE_KEY = "newlineKey";

    /** Allowed key-combo values. */
    /** Enter key. */ public static final String ENTER        = "ENTER";
    /** Shift+Enter key. */ public static final String SHIFT_ENTER  = "SHIFT_ENTER";
    /** Ctrl+Enter key. */ public static final String CTRL_ENTER   = "CTRL_ENTER";
    /** Alt+Enter key. */ public static final String ALT_ENTER    = "ALT_ENTER";

    /** Default key combo for sending the prompt (Ctrl+Enter). */
    public static final String DEFAULT_SEND_KEY    = CTRL_ENTER;
    /** Default key combo for inserting a newline (Enter). */
    public static final String DEFAULT_NEWLINE_KEY = ENTER;

    private static final Logger LOG =
            Logger.getLogger(ClaudeCodePreferences.class.getName());

    private ClaudeCodePreferences() {}

    // -------------------------------------------------------------------------
    // claudeExecutablePath
    // -------------------------------------------------------------------------

    /**
     * Returns the stored path to the {@code claude} CLI, or an empty string.
     *
     * @return stored path or {@link #DEFAULT_CLAUDE_EXECUTABLE_PATH}
     */
    public static String getClaudeExecutablePath() {
        return NbPreferences.forModule(ClaudeCodePreferences.class)
                .get(KEY_CLAUDE_EXECUTABLE_PATH, DEFAULT_CLAUDE_EXECUTABLE_PATH);
    }

    /**
     * Persists the path to the {@code claude} CLI executable.
     *
     * @param path absolute path, or empty string to use {@code PATH}
     */
    public static void setClaudeExecutablePath(String path) {
        NbPreferences.forModule(ClaudeCodePreferences.class)
                .put(KEY_CLAUDE_EXECUTABLE_PATH,
                        path == null ? DEFAULT_CLAUDE_EXECUTABLE_PATH : path);
    }

    /**
     * Returns the {@code claude} executable to use.
     *
     * <p>Uses the stored path when non-empty; otherwise searches {@code PATH},
     * stores the result for future calls, and falls back to {@code "claude"}.
     *
     * @return resolved executable path or {@code "claude"}
     */
    public static String resolveClaudeExecutable() {
        String stored = getClaudeExecutablePath();
        if (!stored.isBlank()) {
            return stored;
        }
        String found = findOnPath();
        if (found != null) {
            setClaudeExecutablePath(found);
            return found;
        }
        return "claude";
    }

    /**
     * Searches the system {@code PATH} for the {@code claude} executable.
     *
     * @return absolute path, or {@code null} if not found
     */
    static String findOnPath() {
        boolean isWindows = System.getProperty("os.name", "")
                .toLowerCase().contains("win");
        String[] candidates = isWindows
                ? new String[]{"claude.cmd", "claude.exe", "claude"}
                : new String[]{"claude"};
        String locator = isWindows ? "where" : "which";
        for (String candidate : candidates) {
            try {
                ProcessBuilder pb = new ProcessBuilder(locator, candidate);
                pb.redirectErrorStream(true);
                Process p = pb.start();
                String output = new String(p.getInputStream().readAllBytes()).trim();
                p.waitFor();
                String first = output.lines().findFirst().orElse("").trim();
                if (!first.isBlank() && new File(first).canExecute()) {
                    return first;
                }
            } catch (IOException | InterruptedException ex) {
                if (ex instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // sendKey / newlineKey
    // -------------------------------------------------------------------------

    /**
     * Returns the key combination used to send the prompt.
     *
     * @return one of {@link #ENTER}, {@link #SHIFT_ENTER}, {@link #CTRL_ENTER},
     *         {@link #ALT_ENTER}
     */
    public static String getSendKey() {
        return NbPreferences.forModule(ClaudeCodePreferences.class)
                .get(KEY_SEND_KEY, DEFAULT_SEND_KEY);
    }

    /**
     * Persists the send-prompt key combination.
     *
     * @param value one of the {@code *_ENTER} constants
     */
    public static void setSendKey(String value) {
        NbPreferences.forModule(ClaudeCodePreferences.class)
                .put(KEY_SEND_KEY, validated(value, DEFAULT_SEND_KEY));
    }

    /**
     * Returns the key combination used to insert a newline.
     *
     * @return one of {@link #ENTER}, {@link #SHIFT_ENTER}, {@link #CTRL_ENTER},
     *         {@link #ALT_ENTER}
     */
    public static String getNewlineKey() {
        return NbPreferences.forModule(ClaudeCodePreferences.class)
                .get(KEY_NEWLINE_KEY, DEFAULT_NEWLINE_KEY);
    }

    /**
     * Persists the insert-newline key combination.
     *
     * @param value one of the {@code *_ENTER} constants
     */
    public static void setNewlineKey(String value) {
        NbPreferences.forModule(ClaudeCodePreferences.class)
                .put(KEY_NEWLINE_KEY, validated(value, DEFAULT_NEWLINE_KEY));
    }

    // -------------------------------------------------------------------------
    // debugMode
    // -------------------------------------------------------------------------

    /** Preference key: enable debug/trace logging. */
    public static final String KEY_DEBUG_MODE = "debugMode";
    /** Default: debug logging off. */
    public static final boolean DEFAULT_DEBUG_MODE = false;

    /**
     * Returns whether debug mode is enabled.
     *
     * @return {@code true} if debug logging is on
     */
    public static boolean isDebugMode() {
        return NbPreferences.forModule(ClaudeCodePreferences.class)
                .getBoolean(KEY_DEBUG_MODE, DEFAULT_DEBUG_MODE);
    }

    /**
     * Persists the debug mode flag and applies the logger level immediately.
     *
     * @param enabled {@code true} to enable debug logging
     */
    public static void setDebugMode(boolean enabled) {
        NbPreferences.forModule(ClaudeCodePreferences.class)
                .putBoolean(KEY_DEBUG_MODE, enabled);
        applyDebugMode(enabled);
    }

    /**
     * Sets the plugin package logger level to FINE (debug on) or INFO (debug off).
     *
     * @param enabled {@code true} to set level FINE, {@code false} to set INFO
     */
    public static void applyDebugMode(boolean enabled) {
        java.util.logging.Logger pkgLogger =
                java.util.logging.Logger.getLogger("io.github.nbclaudecodegui");
        pkgLogger.setLevel(enabled ? java.util.logging.Level.FINE : java.util.logging.Level.INFO);
    }

    // -------------------------------------------------------------------------
    // mcpPort
    // -------------------------------------------------------------------------

    /** Preference key: MCP SSE server port. */
    public static final String KEY_MCP_PORT = "mcpPort";
    /** Default port for the NetBeans MCP SSE server. */
    public static final int DEFAULT_MCP_PORT = 28991;

    /**
     * Returns the configured MCP server port.
     *
     * @return configured port, or {@link #DEFAULT_MCP_PORT} if not set
     */
    public static int getMcpPort() {
        return NbPreferences.forModule(ClaudeCodePreferences.class)
                .getInt(KEY_MCP_PORT, DEFAULT_MCP_PORT);
    }

    /**
     * Persists the MCP server port.
     *
     * @param port port number to save
     */
    public static void setMcpPort(int port) {
        NbPreferences.forModule(ClaudeCodePreferences.class)
                .putInt(KEY_MCP_PORT, port);
    }

    // -------------------------------------------------------------------------
    // profilesDir
    // -------------------------------------------------------------------------

    /** Preference key: base directory for non-Default profile config dirs. */
    public static final String KEY_PROFILES_DIR = "profilesDir";

    /**
     * Returns the configured profiles base directory, or the platform default.
     *
     * <p>Default: {@code parent(Places.getUserDirectory()) / claude-plugin/profiles/}.
     * For example, if {@code getUserDirectory()} is {@code ~/.netbeans/21/},
     * the default is {@code ~/.netbeans/claude-plugin/profiles/}.
     *
     * @return absolute path to the profiles base directory
     */
    public static Path getProfilesDir() {
        String stored = NbPreferences.forModule(ClaudeCodePreferences.class)
                .get(KEY_PROFILES_DIR, "");
        if (!stored.isBlank()) {
            return Path.of(stored);
        }
        return resolveDefaultProfilesDir();
    }

    /**
     * Persists the profiles base directory.
     *
     * @param dir absolute path, or blank/null to use the default
     */
    public static void setProfilesDir(Path dir) {
        NbPreferences.forModule(ClaudeCodePreferences.class)
                .put(KEY_PROFILES_DIR, dir != null ? dir.toString() : "");
    }

    /**
     * Computes the default profiles directory as
     * {@code parent(Places.getUserDirectory())/claude-plugin/profiles/}.
     *
     * @return default profiles directory path
     */
    public static Path resolveDefaultProfilesDir() {
        try {
            File userDir = Places.getUserDirectory();
            if (userDir != null) {
                Path parent = userDir.toPath().getParent();
                if (parent != null) {
                    return parent.resolve("claude-plugin/profiles");
                }
            }
        } catch (Exception e) {
            LOG.warning("Could not resolve Places.getUserDirectory(): " + e.getMessage());
        }
        // Fallback: ~/.netbeans/claude-plugin/profiles
        return Path.of(System.getProperty("user.home"), ".netbeans", "claude-plugin", "profiles");
    }

    // -------------------------------------------------------------------------
    // historyMaxDepth
    // -------------------------------------------------------------------------

    /** Preference key: maximum number of persisted prompt history entries. */
    public static final String KEY_HISTORY_MAX_DEPTH = "historyMaxDepth";
    /** Default: keep up to 200 entries. */
    public static final int DEFAULT_HISTORY_MAX_DEPTH = 200;

    /**
     * Returns the maximum number of history entries to keep per project.
     *
     * @return max depth (≥ 1)
     */
    public static int getHistoryMaxDepth() {
        return NbPreferences.forModule(ClaudeCodePreferences.class)
                .getInt(KEY_HISTORY_MAX_DEPTH, DEFAULT_HISTORY_MAX_DEPTH);
    }

    /**
     * Persists the history max-depth setting.
     *
     * @param v new max depth; clamped to [1, 2000]
     */
    public static void setHistoryMaxDepth(int v) {
        NbPreferences.forModule(ClaudeCodePreferences.class)
                .putInt(KEY_HISTORY_MAX_DEPTH, Math.max(1, Math.min(2000, v)));
    }

    // -------------------------------------------------------------------------
    // historyTtlDays
    // -------------------------------------------------------------------------

    /** Preference key: number of days after which history entries expire. */
    public static final String KEY_HISTORY_TTL_DAYS = "historyTtlDays";
    /** Default: 0 — entries never expire. */
    public static final int DEFAULT_HISTORY_TTL_DAYS = 0;

    /**
     * Returns the TTL for history entries in days.
     *
     * @return TTL in days, or {@code 0} to keep entries forever
     */
    public static int getHistoryTtlDays() {
        return NbPreferences.forModule(ClaudeCodePreferences.class)
                .getInt(KEY_HISTORY_TTL_DAYS, DEFAULT_HISTORY_TTL_DAYS);
    }

    /**
     * Persists the history TTL setting.
     *
     * @param v TTL in days; clamped to [0, 3650]
     */
    public static void setHistoryTtlDays(int v) {
        NbPreferences.forModule(ClaudeCodePreferences.class)
                .putInt(KEY_HISTORY_TTL_DAYS, Math.max(0, Math.min(3650, v)));
    }

    // -------------------------------------------------------------------------
    // openDiffInSeparateTab
    // -------------------------------------------------------------------------

    /** Preference key: open file diffs in a separate TopComponent tab. */
    public static final String KEY_OPEN_DIFF_IN_SEPARATE_TAB = "openDiffInSeparateTab";
    /** Default: embed diff inside the session tab. */
    public static final boolean DEFAULT_OPEN_DIFF_IN_SEPARATE_TAB = false;

    /**
     * Returns whether file diffs should open in a separate tab.
     *
     * @return {@code true} to open in a separate TopComponent; {@code false} to embed in session tab
     */
    public static boolean isOpenDiffInSeparateTab() {
        return NbPreferences.forModule(ClaudeCodePreferences.class)
                .getBoolean(KEY_OPEN_DIFF_IN_SEPARATE_TAB, DEFAULT_OPEN_DIFF_IN_SEPARATE_TAB);
    }

    /**
     * Persists the diff location setting.
     *
     * @param v {@code true} to open in a separate tab; {@code false} to embed in session tab
     */
    public static void setOpenDiffInSeparateTab(boolean v) {
        NbPreferences.forModule(ClaudeCodePreferences.class)
                .putBoolean(KEY_OPEN_DIFF_IN_SEPARATE_TAB, v);
    }

    // -------------------------------------------------------------------------
    // mdPreviewInDiff
    // -------------------------------------------------------------------------

    /** Preference key: show markdown preview for .md files in diff. */
    public static final String KEY_MD_PREVIEW_IN_DIFF = "mdPreviewInDiff";
    /** Default: markdown preview enabled. */
    public static final boolean DEFAULT_MD_PREVIEW_IN_DIFF = true;

    /**
     * Returns whether the markdown preview is shown for .md files in diffs.
     *
     * @return {@code true} if the preview is enabled
     */
    public static boolean isMdPreviewInDiff() {
        return NbPreferences.forModule(ClaudeCodePreferences.class)
                .getBoolean(KEY_MD_PREVIEW_IN_DIFF, DEFAULT_MD_PREVIEW_IN_DIFF);
    }

    /**
     * Persists the markdown-preview-in-diff setting.
     *
     * @param v {@code true} to enable the preview
     */
    public static void setMdPreviewInDiff(boolean v) {
        NbPreferences.forModule(ClaudeCodePreferences.class).putBoolean(KEY_MD_PREVIEW_IN_DIFF, v);
    }

    // -------------------------------------------------------------------------
    // defaultExtraCliArgs
    // -------------------------------------------------------------------------

    /** Preference key: extra CLI args for the Default profile. */
    public static final String KEY_DEFAULT_EXTRA_CLI_ARGS = "defaultExtraCliArgs";

    public static String getDefaultExtraCliArgs() {
        return NbPreferences.forModule(ClaudeCodePreferences.class)
                .get(KEY_DEFAULT_EXTRA_CLI_ARGS, "");
    }

    public static void setDefaultExtraCliArgs(String v) {
        NbPreferences.forModule(ClaudeCodePreferences.class)
                .put(KEY_DEFAULT_EXTRA_CLI_ARGS, v != null ? v : "");
    }

    // -------------------------------------------------------------------------
    // contextMenuSessionMode
    // -------------------------------------------------------------------------

    /** Preference key: session mode used when opening via "Open with Claude" context menu. */
    public static final String KEY_CONTEXT_MENU_SESSION_MODE = "contextMenuSessionMode";
    /** Default: continue the last session. */
    public static final SessionMode DEFAULT_CONTEXT_MENU_SESSION_MODE = SessionMode.CONTINUE_LAST;

    /**
     * Returns the session mode used for context-menu ("Open with Claude") actions.
     *
     * @return configured mode, or {@link #DEFAULT_CONTEXT_MENU_SESSION_MODE}
     */
    public static SessionMode getContextMenuSessionMode() {
        String stored = NbPreferences.forModule(ClaudeCodePreferences.class)
                .get(KEY_CONTEXT_MENU_SESSION_MODE,
                        DEFAULT_CONTEXT_MENU_SESSION_MODE.name());
        try {
            SessionMode mode = SessionMode.valueOf(stored);
            // CLOSE_ONLY and RESUME_SPECIFIC are not valid context-menu modes
            if (mode == SessionMode.CLOSE_ONLY || mode == SessionMode.RESUME_SPECIFIC) {
                return DEFAULT_CONTEXT_MENU_SESSION_MODE;
            }
            return mode;
        } catch (IllegalArgumentException e) {
            return DEFAULT_CONTEXT_MENU_SESSION_MODE;
        }
    }

    /**
     * Persists the session mode for context-menu actions.
     *
     * @param mode {@link SessionMode#NEW} or {@link SessionMode#CONTINUE_LAST}
     */
    public static void setContextMenuSessionMode(SessionMode mode) {
        if (mode == null || mode == SessionMode.CLOSE_ONLY
                || mode == SessionMode.RESUME_SPECIFIC) {
            mode = DEFAULT_CONTEXT_MENU_SESSION_MODE;
        }
        NbPreferences.forModule(ClaudeCodePreferences.class)
                .put(KEY_CONTEXT_MENU_SESSION_MODE, mode.name());
    }

    // -------------------------------------------------------------------------
    // sessionListLimit
    // -------------------------------------------------------------------------

    /** Preference key: maximum number of sessions shown in the session list. */
    public static final String KEY_SESSION_LIST_LIMIT = "sessionListLimit";
    /** Default: show the 30 most recent sessions. */
    public static final int DEFAULT_SESSION_LIST_LIMIT = 30;

    /**
     * Returns the maximum number of sessions to show in the session list.
     *
     * @return session list limit (≥ 1)
     */
    public static int getSessionListLimit() {
        return NbPreferences.forModule(ClaudeCodePreferences.class)
                .getInt(KEY_SESSION_LIST_LIMIT, DEFAULT_SESSION_LIST_LIMIT);
    }

    /**
     * Persists the session list limit.
     *
     * @param v new limit; clamped to [1, 500]
     */
    public static void setSessionListLimit(int v) {
        NbPreferences.forModule(ClaudeCodePreferences.class)
                .putInt(KEY_SESSION_LIST_LIMIT, Math.max(1, Math.min(500, v)));
    }

    // -------------------------------------------------------------------------
    // hangTimeoutSeconds
    // -------------------------------------------------------------------------

    /** Preference key: seconds of PTY silence before a hang is declared (0 = disabled). */
    public static final String KEY_HANG_TIMEOUT_SECONDS = "hangTimeoutSeconds";
    /** Default: 60 seconds. */
    public static final int DEFAULT_HANG_TIMEOUT_SECONDS = 60;

    /**
     * Returns the configured hang-detection timeout in seconds.
     *
     * @return timeout in seconds, or {@code 0} to disable hang detection
     */
    public static int getHangTimeoutSeconds() {
        return NbPreferences.forModule(ClaudeCodePreferences.class)
                .getInt(KEY_HANG_TIMEOUT_SECONDS, DEFAULT_HANG_TIMEOUT_SECONDS);
    }

    /**
     * Persists the hang-detection timeout.
     *
     * @param v timeout in seconds; clamped to [0, 3600]; 0 disables detection
     */
    public static void setHangTimeoutSeconds(int v) {
        NbPreferences.forModule(ClaudeCodePreferences.class)
                .putInt(KEY_HANG_TIMEOUT_SECONDS, Math.max(0, Math.min(3600, v)));
    }

    // -------------------------------------------------------------------------
    // mcpEnabled
    // -------------------------------------------------------------------------

    /** Preference key: pass {@code --mcp-config} flag to claude CLI. */
    private static final String MCP_ENABLED = "mcpEnabled";

    /**
     * Returns whether MCP integration is enabled (default {@code true}).
     *
     * @return {@code true} to pass {@code --mcp-config}; {@code false} to skip it
     */
    public static boolean isMcpEnabled() {
        return NbPreferences.forModule(ClaudeCodePreferences.class)
                .getBoolean(MCP_ENABLED, true);
    }

    /**
     * Persists the MCP-enabled flag.
     *
     * @param v {@code true} to enable MCP integration
     */
    public static void setMcpEnabled(boolean v) {
        NbPreferences.forModule(ClaudeCodePreferences.class).putBoolean(MCP_ENABLED, v);
    }

    private static String validated(String value, String fallback) {
        return ENTER.equals(value) || SHIFT_ENTER.equals(value)
                || CTRL_ENTER.equals(value) || ALT_ENTER.equals(value)
                ? value : fallback;
    }
}
