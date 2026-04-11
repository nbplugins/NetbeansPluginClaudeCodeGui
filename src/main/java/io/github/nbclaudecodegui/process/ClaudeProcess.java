package io.github.nbclaudecodegui.process;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import io.github.nbclaudecodegui.model.SessionMode;
import io.github.nbclaudecodegui.settings.ClaudeCodePreferences;
import io.github.nbclaudecodegui.settings.ClaudeProfile;
import io.github.nbclaudecodegui.settings.ClaudeProfileStore;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.openbeans.claude.netbeans.ClaudeCodeStatusService;
import org.openide.util.Lookup;

/**
 * Manages a Claude Code CLI session as an interactive PTY process.
 *
 * <p>The process is launched without {@code --print} so that the full Claude
 * TUI (ink/React) runs inside the PTY.  The caller embeds a JediTerm terminal
 * widget that renders the TUI natively.
 *
 * <p><b>settings.local.json lifecycle</b>
 * <p>Before starting the PTY, the plugin writes
 * {@code {workingDir}/.claude/settings.local.json} to register itself as an
 * MCP server and to install a {@code PreToolUse} hook.  The write is a
 * <em>merge</em>: existing user content (other MCP servers, other hooks) is
 * preserved; only the plugin's own keys are added or updated.
 *
 * <p>When the session stops, the plugin removes its keys from the file.  If
 * the file becomes empty after the cleanup it is deleted entirely (this is the
 * normal case when the file did not exist before the session started).  If the
 * file contained user content that content is left intact.
 */
public final class ClaudeProcess {

    private static final Logger LOG =
            Logger.getLogger(ClaudeProcess.class.getName());

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Matcher string the plugin registers in PreToolUse hooks. */
    static final String OUR_HOOK_MATCHER = "Edit|Write|MultiEdit";

    /** Matcher for Stop and PermissionRequest hooks (match all). */
    static final String OUR_STOP_MATCHER = ".*";

    /** Key the plugin registers under {@code mcpServers}. */
    static final String OUR_MCP_KEY = "netbeans";

    /** Creates a new, idle {@code ClaudeProcess} instance. */
    public ClaudeProcess() {}

    private volatile PtyProcess ptyProcess;
    private String lastCommand = "";

    /** Working directory of the current session; {@code null} when stopped. */
    private volatile String workingDir;

    /**
     * Starts a Claude CLI PTY process in the given working directory using
     * the Default profile (no extra env vars injected).
     *
     * @param workingDir absolute path to the session working directory
     * @return the started {@link PtyProcess}
     * @throws IllegalArgumentException if {@code workingDir} is blank
     * @throws IOException              if the process cannot be launched
     */
    public PtyProcess start(String workingDir) throws IOException {
        return start(workingDir, null);
    }

    /**
     * Starts a Claude CLI PTY process in the given working directory with
     * the supplied connection profile.
     *
     * <p>The profile contributes:
     * <ul>
     *   <li>{@code CLAUDE_CONFIG_DIR} — set to the profile's isolated config
     *       directory for non-Default profiles; not set for the Default profile.</li>
     *   <li>Auth env vars ({@code ANTHROPIC_API_KEY}, etc.) based on the
     *       profile's connection type.</li>
     *   <li>Proxy env vars based on the profile's proxy mode.</li>
     *   <li>Any extra env vars configured on the profile.</li>
     * </ul>
     *
     * @param workingDir absolute path to the session working directory
     * @param profile    connection profile to apply; {@code null} uses Default behaviour
     * @return the started {@link PtyProcess}
     * @throws IllegalArgumentException if {@code workingDir} is blank
     * @throws IOException              if the process cannot be launched
     */
    public PtyProcess start(String workingDir, ClaudeProfile profile) throws IOException {
        return start(workingDir, profile, "");
    }

    /**
     * Starts a Claude CLI PTY process with extra CLI arguments appended to the command.
     *
     * @param workingDir    absolute path to the session working directory
     * @param profile       connection profile; {@code null} uses Default behaviour
     * @param extraCliArgs  extra CLI arguments string (may be blank)
     * @return the started {@link PtyProcess}
     * @throws IllegalArgumentException if {@code workingDir} is blank
     * @throws IOException              if the process cannot be launched
     */
    public PtyProcess start(String workingDir, ClaudeProfile profile, String extraCliArgs) throws IOException {
        return start(workingDir, profile, extraCliArgs, SessionMode.NEW, null);
    }

    /**
     * Starts a Claude CLI PTY process with session mode support.
     *
     * @param workingDir      absolute path to the session working directory
     * @param profile         connection profile; {@code null} uses Default behaviour
     * @param extraCliArgs    extra CLI arguments string (may be blank)
     * @param mode            how to start: NEW, CONTINUE_LAST, or RESUME_SPECIFIC
     * @param resumeSessionId session ID to resume (only used when mode is RESUME_SPECIFIC)
     * @return the started {@link PtyProcess}
     * @throws IllegalArgumentException if {@code workingDir} is blank
     * @throws IOException              if the process cannot be launched
     */
    public PtyProcess start(String workingDir, ClaudeProfile profile, String extraCliArgs,
                            SessionMode mode, String resumeSessionId) throws IOException {
        if (workingDir == null || workingDir.isBlank()) {
            throw new IllegalArgumentException("workingDir must not be blank");
        }

        // stop() cleans up the previous session's settings.local.json (if any)
        stop();
        this.workingDir = workingDir;

        String executable = ClaudeCodePreferences.resolveClaudeExecutable();

        Map<String, String> env = buildEnv(profile, ClaudeCodePreferences.getProfilesDir());

        LOG.info("Starting Claude: profile=" + (profile != null ? profile.getName() + " (" + profile.computeConnectionType() + ")" : "Default")
                + ", ANTHROPIC_API_KEY=" + (!env.getOrDefault("ANTHROPIC_API_KEY", "").isBlank() ? "SET" : "NOT SET")
                + ", ANTHROPIC_AUTH_TOKEN=" + (!env.getOrDefault("ANTHROPIC_AUTH_TOKEN", "").isBlank() ? "SET" : "NOT SET")
                + ", ANTHROPIC_BASE_URL=" + env.getOrDefault("ANTHROPIC_BASE_URL", "(not set)")
                + ", CLAUDE_CONFIG_DIR=" + env.getOrDefault("CLAUDE_CONFIG_DIR", "(inherited)")
                + ", sessionMode=" + mode
                + (mode == SessionMode.RESUME_SPECIFIC ? ", resumeId=" + resumeSessionId : ""));

        List<String> cmd = new ArrayList<>();
        cmd.add(executable);
        ClaudeCodeStatusService mcp = Lookup.getDefault().lookup(ClaudeCodeStatusService.class);
        LOG.info("MCP service lookup: " + (mcp == null ? "null" : mcp.getClass().getName())
                + ", running=" + (mcp != null && mcp.isServerRunning())
                + ", port=" + (mcp != null ? mcp.getServerPort() : -1));
        if (mcp != null && mcp.isServerRunning()) {
            int port = mcp.getServerPort();
            // Pass MCP server config via --mcp-config (Claude 2.x no longer reads
            // mcpServers from settings.local.json; --mcp-config works in TUI mode).
            cmd.add("--mcp-config");
            cmd.add(buildMcpConfigJson(port));
            LOG.info("Passing --mcp-config with netbeans SSE server on port " + port);
            try {
                writeSettingsLocalJson(workingDir, port);
            } catch (IOException e) {
                LOG.warning("Could not write .claude/settings.local.json: " + e.getMessage());
            }
        }

        appendSessionFlags(cmd, workingDir, env, extraCliArgs, mode, resumeSessionId);

        lastCommand = String.join(" ", cmd);
        LOG.info("Claude command: " + lastCommand);
        PtyProcessBuilder builder = new PtyProcessBuilder(cmd.toArray(new String[0]))
                .setEnvironment(env)
                .setDirectory(workingDir)
                .setInitialColumns(120)
                .setInitialRows(40)
                .setConsole(false)
                .setRedirectErrorStream(true);

        PtyProcess p;
        try {
            p = builder.start();
        } catch (IOException ptyEx) {
            // pty4j's exec_pty() often yields "Exec_tty error:Unknown reason" even for
            // mundane failures (binary not found, no execute permission). Run the same
            // command via plain ProcessBuilder — purely to get a meaningful errno message
            // from the JVM — then rethrow that exception instead.
            try {
                new ProcessBuilder(cmd.toArray(new String[0]))
                        .directory(new java.io.File(workingDir))
                        .start()
                        .destroyForcibly();
            } catch (IOException betterEx) {
                throw betterEx;
            }
            throw ptyEx;
        }
        ptyProcess = p;
        LOG.fine("Claude PTY started, pid=" + p.pid());
        return p;
    }

    /** Returns the last command attempted to start, as a space-joined string. */
    public String getLastCommand() { return lastCommand; }

    /**
     * Appends extra CLI args and session-mode flags to {@code cmd}.
     *
     * <p>Package-private for unit testing.
     *
     * @param cmd             command list to append to (already contains executable and MCP flags)
     * @param workingDir      working directory (used to check for existing sessions)
     * @param env             environment map (used to resolve {@code CLAUDE_CONFIG_DIR})
     * @param extraCliArgs    extra CLI arguments string (may be blank)
     * @param mode            session mode
     * @param resumeSessionId session ID for RESUME_SPECIFIC mode
     */
    void appendSessionFlags(List<String> cmd, String workingDir, Map<String, String> env,
                            String extraCliArgs, SessionMode mode, String resumeSessionId) {
        List<String> extra = parseArgs(extraCliArgs);
        if (!extra.isEmpty()) {
            cmd.addAll(extra);
            LOG.info("Extra CLI args appended: " + extra);
        }

        if (mode == SessionMode.CONTINUE_LAST) {
            String configDirStr = env.get("CLAUDE_CONFIG_DIR");
            Path claudeConfigDir = configDirStr != null
                    ? Path.of(configDirStr)
                    : Path.of(System.getProperty("user.home"), ".claude");
            if (ClaudeSessionStore.hasAnySessions(Path.of(workingDir), claudeConfigDir)) {
                cmd.add("--continue");
                LOG.fine("Session mode: --continue (existing session found)");
            } else {
                LOG.fine("Session mode: CONTINUE_LAST — no sessions found, starting new");
            }
        } else if (mode == SessionMode.RESUME_SPECIFIC
                && resumeSessionId != null && !resumeSessionId.isBlank()) {
            cmd.add("--resume");
            cmd.add(resumeSessionId);
            LOG.fine("Session mode: --resume " + resumeSessionId);
        }
    }

    /**
     * Stops the current PTY process and cleans up {@code settings.local.json}.
     *
     * <p>Cleanup removes only the plugin's own keys ({@code mcpServers.netbeans}
     * and the {@code PreToolUse} hook entry with matcher
     * {@value #OUR_HOOK_MATCHER}).  User-provided keys are left untouched.
     * If the file becomes empty after cleanup it is deleted.
     */
    public void stop() {
        PtyProcess p = ptyProcess;
        if (p != null && p.isAlive()) {
            p.destroy();
            try {
                if (!p.waitFor(3, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
        }
        ptyProcess = null;

        String dir = workingDir;
        workingDir = null;
        if (dir != null) {
            try {
                cleanupSettingsLocalJson(dir);
            } catch (IOException e) {
                LOG.warning("Could not clean up .claude/settings.local.json: " + e.getMessage());
            }
        }
    }

    /**
     * Runs {@code claude --version} as a separate (non-PTY) process and returns the first line
     * of its stdout output, or an empty string on any error.
     *
     * @return version string, e.g. {@code "1.0.20 (Claude Code)"}
     */
    public String readVersion() {
        String executable = ClaudeCodePreferences.resolveClaudeExecutable();
        try {
            Process p = new ProcessBuilder(executable, "--version")
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                p.waitFor(5, TimeUnit.SECONDS);
                return line != null ? line.trim() : "";
            }
        } catch (Exception e) {
            LOG.warning("readVersion failed: " + e.getMessage());
            return "";
        }
    }

    /**
     * Returns {@code true} while a PTY process is alive.
     *
     * @return {@code true} if the process is running
     */
    public boolean isRunning() {
        PtyProcess p = ptyProcess;
        return p != null && p.isAlive();
    }

    // -------------------------------------------------------------------------
    // settings.local.json — write (merge)
    // -------------------------------------------------------------------------

    /**
     * Merges the plugin's MCP server and PreToolUse hook into
     * {@code {workingDir}/.claude/settings.local.json}.
     *
     * <p>If the file already exists its content is preserved: only
     * {@code mcpServers.netbeans} and the {@code PreToolUse} entry with matcher
     * {@value #OUR_HOOK_MATCHER} are added or updated; all other keys remain
     * unchanged.  If the file does not exist it is created.
     *
     * @param workingDir the session working directory
     * @param port       the port the MCP SSE server is listening on
     * @throws IOException if the file cannot be written
     */
    static void writeSettingsLocalJson(String workingDir, int port) throws IOException {
        Path claudeDir = Path.of(workingDir, ".claude");
        Files.createDirectories(claudeDir);
        Path cfg = claudeDir.resolve("settings.local.json");

        String existing = Files.exists(cfg)
                ? Files.readString(cfg, StandardCharsets.UTF_8)
                : "{}";

        String merged = mergeSettingsJson(existing, port);
        Files.writeString(cfg, merged, StandardCharsets.UTF_8);
        LOG.info("settings.local.json written (merged): " + cfg.toAbsolutePath());
    }

    /**
     * Merges the plugin's entries into {@code existingJson} and returns the
     * resulting JSON string.
     *
     * <p>Specifically:
     * <ul>
     *   <li>{@code mcpServers.netbeans} is set to the SSE URL for {@code port}.</li>
     *   <li>Any existing {@code hooks.PreToolUse} entry whose {@code matcher}
     *       equals {@value #OUR_HOOK_MATCHER} is replaced with a fresh entry
     *       pointing to the hook URL for {@code port}.  Other entries are kept.</li>
     * </ul>
     *
     * <p>Falls back to a fresh minimal JSON if {@code existingJson} cannot be
     * parsed.
     *
     * @param existingJson current file content (may be {@code "{}"} for a new file)
     * @param port         the MCP/hook server port
     * @return merged JSON string
     */
    static String mergeSettingsJson(String existingJson, int port) {
        try {
            ObjectNode root = existingJson == null || existingJson.isBlank()
                    ? MAPPER.createObjectNode()
                    : (ObjectNode) MAPPER.readTree(existingJson);

            // Remove stale mcpServers.netbeans written by plugin < 0.19.22
            if (root.has("mcpServers")) {
                ObjectNode mcpServers = (ObjectNode) root.get("mcpServers");
                mcpServers.remove(OUR_MCP_KEY);
                if (mcpServers.isEmpty()) {
                    root.remove("mcpServers");
                }
            }

            // --- hooks.PreToolUse ---
            ObjectNode hooks = root.has("hooks")
                    ? (ObjectNode) root.get("hooks")
                    : MAPPER.createObjectNode();
            ArrayNode preToolUse = hooks.has("PreToolUse")
                    ? (ArrayNode) hooks.get("PreToolUse")
                    : MAPPER.createArrayNode();

            // Remove our old entry, keep others
            ArrayNode filtered = MAPPER.createArrayNode();
            for (JsonNode entry : preToolUse) {
                String matcher = entry.has("matcher") ? entry.get("matcher").asText() : "";
                if (!OUR_HOOK_MATCHER.equals(matcher)) {
                    filtered.add(entry);
                }
            }

            // Add fresh entry for this port
            ObjectNode ourEntry = MAPPER.createObjectNode();
            ourEntry.put("matcher", OUR_HOOK_MATCHER);
            ArrayNode hooksArr = MAPPER.createArrayNode();
            ObjectNode httpHook = MAPPER.createObjectNode();
            httpHook.put("type", "http");
            httpHook.put("url", "http://localhost:" + port + "/hook");
            hooksArr.add(httpHook);
            ourEntry.set("hooks", hooksArr);
            filtered.add(ourEntry);

            hooks.set("PreToolUse", filtered);

            // --- hooks.Stop ---
            ObjectNode stopEntry = MAPPER.createObjectNode();
            stopEntry.put("matcher", OUR_STOP_MATCHER);
            ArrayNode stopHooksArr = MAPPER.createArrayNode();
            ObjectNode stopHook = MAPPER.createObjectNode();
            stopHook.put("type", "http");
            stopHook.put("url", "http://localhost:" + port + "/stop");
            stopHooksArr.add(stopHook);
            stopEntry.set("hooks", stopHooksArr);
            ArrayNode stopArr = MAPPER.createArrayNode();
            stopArr.add(stopEntry);
            hooks.set("Stop", stopArr);

            // --- hooks.PermissionRequest ---
            ObjectNode permEntry = MAPPER.createObjectNode();
            permEntry.put("matcher", OUR_STOP_MATCHER);
            ArrayNode permHooksArr = MAPPER.createArrayNode();
            ObjectNode permHook = MAPPER.createObjectNode();
            permHook.put("type", "http");
            permHook.put("url", "http://localhost:" + port + "/permission-request");
            permHooksArr.add(permHook);
            permEntry.set("hooks", permHooksArr);
            ArrayNode permArr = MAPPER.createArrayNode();
            permArr.add(permEntry);
            hooks.set("PermissionRequest", permArr);

            root.set("hooks", hooks);

            return MAPPER.writeValueAsString(root);

        } catch (Exception e) {
            LOG.warning("Could not merge settings JSON, using fresh: " + e.getMessage());
            return buildSettingsLocalJson(port);
        }
    }

    // -------------------------------------------------------------------------
    // settings.local.json — cleanup
    // -------------------------------------------------------------------------

    /**
     * Removes the plugin's keys from {@code {workingDir}/.claude/settings.local.json}.
     *
     * <p>If the file does not exist this method does nothing.  Otherwise:
     * <ul>
     *   <li>If the file is empty after removing our keys it is deleted (the
     *       normal case when it was created entirely by the plugin).</li>
     *   <li>If the file still contains user-provided content it is written back
     *       without our keys.</li>
     * </ul>
     *
     * @param workingDir the session working directory
     * @throws IOException if the file cannot be read, written, or deleted
     */
    static void cleanupSettingsLocalJson(String workingDir) throws IOException {
        Path cfg = Path.of(workingDir, ".claude", "settings.local.json");
        if (!Files.exists(cfg)) {
            return;
        }
        String existing = Files.readString(cfg, StandardCharsets.UTF_8);
        String cleaned = cleanedSettingsJson(existing);
        if (cleaned == null) {
            Files.delete(cfg);
            LOG.info("settings.local.json deleted (was plugin-only): " + cfg.toAbsolutePath());
        } else {
            Files.writeString(cfg, cleaned, StandardCharsets.UTF_8);
            LOG.info("settings.local.json cleaned (user content preserved): " + cfg.toAbsolutePath());
        }
    }

    /**
     * Returns a copy of {@code existingJson} with the plugin's keys removed,
     * or {@code null} if nothing remains after the removal.
     *
     * <p>The plugin's keys are:
     * <ul>
     *   <li>{@code mcpServers.netbeans}</li>
     *   <li>The {@code hooks.PreToolUse} array entry whose {@code matcher}
     *       equals {@value #OUR_HOOK_MATCHER}</li>
     * </ul>
     *
     * <p>A {@code null} return value signals that the file should be deleted
     * (i.e., it contained only the plugin's entries and is now effectively
     * {@code {}}).
     *
     * @param existingJson current file content
     * @return cleaned JSON string, or {@code null} to indicate «delete the file»
     */
    static String cleanedSettingsJson(String existingJson) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(existingJson);

            // Remove stale mcpServers.netbeans (backward compat with plugin < 0.19.22)
            if (root.has("mcpServers")) {
                ObjectNode mcpServers = (ObjectNode) root.get("mcpServers");
                mcpServers.remove(OUR_MCP_KEY);
                if (mcpServers.isEmpty()) {
                    root.remove("mcpServers");
                }
            }

            // Remove our hook entries
            if (root.has("hooks")) {
                ObjectNode hooks = (ObjectNode) root.get("hooks");
                if (hooks.has("PreToolUse")) {
                    ArrayNode preToolUse = (ArrayNode) hooks.get("PreToolUse");
                    ArrayNode filtered = MAPPER.createArrayNode();
                    for (JsonNode entry : preToolUse) {
                        String matcher = entry.has("matcher") ? entry.get("matcher").asText() : "";
                        if (!OUR_HOOK_MATCHER.equals(matcher)) {
                            filtered.add(entry);
                        }
                    }
                    if (filtered.isEmpty()) {
                        hooks.remove("PreToolUse");
                    } else {
                        hooks.set("PreToolUse", filtered);
                    }
                }
                hooks.remove("Stop");
                hooks.remove("PermissionRequest");
                if (hooks.isEmpty()) {
                    root.remove("hooks");
                }
            }

            return root.isEmpty() ? null : MAPPER.writeValueAsString(root);

        } catch (Exception e) {
            LOG.warning("Could not parse settings JSON for cleanup, deleting: " + e.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Builds the environment map for a new PTY process.
     *
     * <p>Starts from a copy of {@link System#getenv()}, then:
     * <ol>
     *   <li>Sets {@code TERM=xterm-256color}.</li>
     *   <li>If {@code profile} is non-null and non-Default, sets
     *       {@code CLAUDE_CONFIG_DIR} to the profile's isolated config
     *       directory under {@code profilesDir}.</li>
     *   <li>Applies the profile's auth, proxy, and extra env vars via
     *       {@link ClaudeProfile#toEnvVars()}.</li>
     * </ol>
     *
     * <p>This method is {@code static} so that it can be unit-tested without
     * instantiating a {@link ClaudeProcess}.
     *
     * @param profile     profile to apply; {@code null} means Default (no extra vars)
     * @param profilesDir base directory for profile config dirs
     * @return mutable env map ready to pass to {@link PtyProcessBuilder}
     */
    static Map<String, String> buildEnv(ClaudeProfile profile, java.nio.file.Path profilesDir) {
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("TERM", "xterm-256color");

        if (profile != null && !profile.isDefault()) {
            // Set isolated CLAUDE_CONFIG_DIR for this profile
            java.nio.file.Path configDir = ClaudeProfileStore.resolveConfigDir(profile, profilesDir);
            env.put("CLAUDE_CONFIG_DIR", configDir.toAbsolutePath().toString());
            try {
                java.nio.file.Files.createDirectories(configDir);
            } catch (java.io.IOException e) {
                LOG.warning("Could not create profile config dir " + configDir + ": " + e.getMessage());
            }
        }

        if (profile != null) {
            // Merge auth / proxy / extra vars (overwrites env-inherited values)
            env.putAll(profile.toEnvVars());
        }

        return env;
    }

    /**
     * Builds a minimal {@code settings.local.json} from scratch for the given port.
     * Used as a fallback when the existing file cannot be parsed.
     */
    /**
     * Builds the JSON string to pass as {@code --mcp-config} to the Claude CLI.
     */
    static String buildMcpConfigJson(int port) {
        return "{\"mcpServers\":{\"" + OUR_MCP_KEY + "\":{\"type\":\"sse\",\"url\":\"http://localhost:" + port + "/sse\"}}}";
    }

    /**
     * Parses a CLI argument string into a list of tokens.
     * Splits on whitespace; double-quoted tokens are treated as a single token
     * (quotes are stripped). Returns an empty list for blank input.
     *
     * @param args the argument string; may be {@code null} or blank
     * @return list of parsed tokens; never {@code null}
     */
    static List<String> parseArgs(String args) {
        List<String> result = new ArrayList<>();
        if (args == null || args.isBlank()) return result;
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (char c : args.toCharArray()) {
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ' ' && !inQuotes) {
                if (!current.isEmpty()) {
                    result.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) result.add(current.toString());
        return result;
    }

    private static String buildSettingsLocalJson(int port) {
        return "{"
                + "\"hooks\":{"
                + "\"PreToolUse\":["
                + "{\"matcher\":\"Edit|Write|MultiEdit\","
                + "\"hooks\":[{\"type\":\"http\",\"url\":\"http://localhost:" + port + "/hook\"}]}],"
                + "\"Stop\":["
                + "{\"matcher\":\".*\","
                + "\"hooks\":[{\"type\":\"http\",\"url\":\"http://localhost:" + port + "/stop\"}]}],"
                + "\"PermissionRequest\":["
                + "{\"matcher\":\".*\","
                + "\"hooks\":[{\"type\":\"http\",\"url\":\"http://localhost:" + port + "/permission-request\"}]}]"
                + "}}";
    }
}
