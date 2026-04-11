package io.github.nbclaudecodegui.process;

import com.pty4j.PtyProcess;
import io.github.nbclaudecodegui.settings.ClaudeProfile;
import java.io.File;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ClaudeProcess}.
 */
class ClaudeProcessTest {

    @Test
    void testStartRejectsBlankDir() {
        ClaudeProcess cp = new ClaudeProcess();
        assertThrows(IllegalArgumentException.class, () -> cp.start(""));
        assertThrows(IllegalArgumentException.class, () -> cp.start(null));
    }

    @Test
    void testStopWhenNotRunningIsNoop() {
        ClaudeProcess cp = new ClaudeProcess();
        assertDoesNotThrow(cp::stop);
    }

    @Test
    void testIsRunningFalseBeforeStart() {
        ClaudeProcess cp = new ClaudeProcess();
        assertFalse(cp.isRunning());
    }

    @Test
    void testStartReturnsPtyProcess() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(
                System.getProperty("os.name", "").toLowerCase().contains("win"),
                "Skipping on Windows");

        File script = makeFakeClaude("sleep 60\n");
        try {
            ClaudeProcess cp = new ClaudeProcess();
            PtyProcess p = cp.start(System.getProperty("java.io.tmpdir"));
            assertNotNull(p);
            assertTrue(p.isAlive());
            cp.stop();
        } finally {
            resetPrefs(script);
        }
    }

    @Test
    void testStartedProcessIsRunning() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(
                System.getProperty("os.name", "").toLowerCase().contains("win"),
                "Skipping on Windows");

        File script = makeFakeClaude("sleep 60\n");
        try {
            ClaudeProcess cp = new ClaudeProcess();
            cp.start(System.getProperty("java.io.tmpdir"));
            assertTrue(cp.isRunning());
            cp.stop();
            assertFalse(cp.isRunning());
        } finally {
            resetPrefs(script);
        }
    }

    @Test
    void testStopKillsProcess() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(
                System.getProperty("os.name", "").toLowerCase().contains("win"),
                "Skipping on Windows");

        File script = makeFakeClaude("sleep 60\n");
        try {
            ClaudeProcess cp = new ClaudeProcess();
            PtyProcess p = cp.start(System.getProperty("java.io.tmpdir"));
            assertTrue(p.isAlive());
            cp.stop();
            p.waitFor();
            assertFalse(p.isAlive());
        } finally {
            resetPrefs(script);
        }
    }

    // -------------------------------------------------------------------------
    // mergeSettingsJson
    // -------------------------------------------------------------------------

    @Test
    void testMergeEmptyFileAddsOurEntries() {
        String result = ClaudeProcess.mergeSettingsJson("{}", 8990, null);
        assertFalse(result.contains("\"netbeans\""), "mcpServers.netbeans should NOT appear in settings.local.json");
        assertFalse(result.contains("/sse"), "SSE URL should NOT appear in settings.local.json");
        assertTrue(result.contains("PreToolUse"), "should add PreToolUse hook");
        assertTrue(result.contains("http://localhost:8990/hook"), "should contain hook URL");
        assertTrue(result.contains("Edit|Write|MultiEdit"), "should contain matcher");
    }

    @Test
    void testMergePreservesOtherMcpServers() {
        // mcpServers in settings.local.json should be left untouched (not added to by plugin)
        String existing = "{\"mcpServers\":{\"other-server\":{\"type\":\"stdio\",\"command\":\"foo\"}}}";
        String result = ClaudeProcess.mergeSettingsJson(existing, 9000, null);
        assertTrue(result.contains("\"other-server\""), "should preserve user-provided MCP server");
        assertFalse(result.contains("\"netbeans\""), "netbeans should NOT be written to settings.local.json");
    }

    @Test
    void testMergeUpdatesPortWhenAlreadyPresent() {
        String existing = ClaudeProcess.mergeSettingsJson("{}", 8888, null);
        String result = ClaudeProcess.mergeSettingsJson(existing, 9999, null);
        assertTrue(result.contains("localhost:9999/hook"), "hook URL should use new port");
        assertFalse(result.contains("localhost:8888"), "should not keep old port");
        assertEquals(1, countOccurrences(result, "Edit|Write|MultiEdit"));
    }

    @Test
    void testMergeWithClaudeApiProfileInjectsApiKeyHelper() {
        ClaudeProfile p = ClaudeProfile.createNamed("API");
        p.setApiKey("sk-ant-abc123");
        String result = ClaudeProcess.mergeSettingsJson("{}", 9000, p);
        assertTrue(result.contains("\"apiKeyHelper\""), "should inject apiKeyHelper for CLAUDE_API profile");
        assertTrue(result.contains("echo sk-ant-abc123"), "apiKeyHelper should contain echo <key>");
    }

    @Test
    void testMergeWithNullProfileNoApiKeyHelper() {
        String result = ClaudeProcess.mergeSettingsJson("{}", 9000, null);
        assertFalse(result.contains("apiKeyHelper"), "should not inject apiKeyHelper when no profile");
    }

    @Test
    void testMergeWithOtherApiProfileNoApiKeyHelper() {
        ClaudeProfile p = ClaudeProfile.createNamed("Other");
        p.setApiKey("sk-ant-abc123");
        p.setBaseUrl("https://proxy.example.com");
        String result = ClaudeProcess.mergeSettingsJson("{}", 9000, p);
        assertFalse(result.contains("apiKeyHelper"),
                "should not inject apiKeyHelper for OTHER_API profile (uses env vars instead)");
    }

    @Test
    void testCleanupRemovesApiKeyHelper() {
        ClaudeProfile p = ClaudeProfile.createNamed("API");
        p.setApiKey("sk-ant-abc123");
        String merged = ClaudeProcess.mergeSettingsJson("{}", 9000, p);
        assertTrue(merged.contains("apiKeyHelper"), "merged should have apiKeyHelper");
        String cleaned = ClaudeProcess.cleanedSettingsJson(merged);
        assertNull(cleaned, "file with only plugin content (including apiKeyHelper) should yield null");
    }

    @Test
    void testCleanupRemovesApiKeyHelperButKeepsUserContent() {
        ClaudeProfile p = ClaudeProfile.createNamed("API");
        p.setApiKey("sk-ant-secret");
        String existing = "{\"myCustomKey\":\"someValue\"}";
        String merged = ClaudeProcess.mergeSettingsJson(existing, 9000, p);
        assertTrue(merged.contains("apiKeyHelper"), "merged should have apiKeyHelper");
        assertTrue(merged.contains("myCustomKey"), "merged should preserve user content");
        String cleaned = ClaudeProcess.cleanedSettingsJson(merged);
        assertNotNull(cleaned, "should keep file — user content remains");
        assertFalse(cleaned.contains("apiKeyHelper"), "cleaned should remove apiKeyHelper");
        assertTrue(cleaned.contains("myCustomKey"), "cleaned should preserve user content");
    }

    // -------------------------------------------------------------------------
    // toShellCommand
    // -------------------------------------------------------------------------

    @Test
    void testToShellCommandNoQuoting() {
        List<String> cmd = List.of("claude", "--continue");
        // plain args need no quoting on either platform
        assertEquals("claude --continue", ClaudeProcess.toShellCommand(cmd, false));
        assertEquals("claude --continue", ClaudeProcess.toShellCommand(cmd, true));
    }

    @Test
    void testToShellCommandQuotesJsonArgUnix() {
        String json = ClaudeProcess.buildMcpConfigJson(28991);
        List<String> cmd = List.of("claude", "--mcp-config", json, "--continue");
        String result = ClaudeProcess.toShellCommand(cmd, false);
        // Expected: claude --mcp-config "{\"mcpServers\":...}" --continue
        assertTrue(result.startsWith("claude --mcp-config \""), "should quote JSON arg");
        assertTrue(result.endsWith("\" --continue"), "should close quote before next arg");
        String inner = result.substring("claude --mcp-config \"".length(),
                result.lastIndexOf("\" --continue"));
        // On Unix all " inside must be escaped as \"
        for (int i = 0; i < inner.length(); i++) {
            if (inner.charAt(i) == '"') {
                assertTrue(i > 0 && inner.charAt(i - 1) == '\\',
                        "unescaped double-quote at position " + i + " in: " + inner);
            }
        }
    }

    @Test
    void testToShellCommandQuotesJsonArgWindows() {
        String json = ClaudeProcess.buildMcpConfigJson(28991);
        List<String> cmd = List.of("claude", "--mcp-config", json, "--continue");
        String result = ClaudeProcess.toShellCommand(cmd, true);
        // Expected: claude --mcp-config "{""mcpServers"":...}" --continue
        assertTrue(result.startsWith("claude --mcp-config \""), "should quote JSON arg");
        assertTrue(result.endsWith("\" --continue"), "should close quote before next arg");
        String inner = result.substring("claude --mcp-config \"".length(),
                result.lastIndexOf("\" --continue"));
        // On Windows all " inside must be doubled as ""
        assertFalse(inner.contains("\\\""), "Windows quoting must not use backslash-escape");
        assertTrue(inner.contains("\"\""), "Windows quoting must double double-quotes");
    }

    // -------------------------------------------------------------------------
    // buildMcpConfigJson
    // -------------------------------------------------------------------------

    @Test
    void testBuildMcpConfigJsonContainsNetbeans() {
        String json = ClaudeProcess.buildMcpConfigJson(9000);
        assertTrue(json.contains("\"netbeans\""), "should contain netbeans key");
        assertTrue(json.contains("http://localhost:9000/sse"), "should contain SSE URL");
        assertTrue(json.contains("\"type\":\"sse\""), "should be SSE type");
    }

    @Test
    void testBuildMcpConfigJsonPort() {
        String json1 = ClaudeProcess.buildMcpConfigJson(28991);
        String json2 = ClaudeProcess.buildMcpConfigJson(12345);
        assertTrue(json1.contains(":28991/"), "should use correct port");
        assertTrue(json2.contains(":12345/"), "should use correct port");
    }

    @Test
    void testMergePreservesOtherPreToolUseHooks() {
        String existing = "{"
                + "\"hooks\":{\"PreToolUse\":["
                + "{\"matcher\":\"Read\",\"hooks\":[{\"type\":\"http\",\"url\":\"http://other/hook\"}]}"
                + "]}}";
        String result = ClaudeProcess.mergeSettingsJson(existing, 9000, null);
        assertTrue(result.contains("\"Read\""), "should preserve other PreToolUse hooks");
        assertTrue(result.contains("Edit|Write|MultiEdit"), "should add our hook");
        // Our matcher appears exactly once
        assertEquals(1, countOccurrences(result, "Edit|Write|MultiEdit"));
    }

    @Test
    void testMergeReplacesOurHookWithUpdatedPort() {
        String existing = ClaudeProcess.mergeSettingsJson("{}", 8888, null);
        String result = ClaudeProcess.mergeSettingsJson(existing, 9999, null);
        assertEquals(1, countOccurrences(result, "Edit|Write|MultiEdit"),
                "our hook matcher should appear exactly once");
        assertTrue(result.contains("localhost:9999/hook"), "hook URL should be updated");
    }

    @Test
    void testMergeRemovesStaleNetbeansEntry() {
        // Simulate a file left by old plugin (< 0.19.22) that has mcpServers.netbeans
        String stale = "{\"mcpServers\":{\"netbeans\":{\"type\":\"sse\",\"url\":\"http://localhost:8888/sse\"}}}";
        String result = ClaudeProcess.mergeSettingsJson(stale, 9000, null);
        assertFalse(result.contains("\"netbeans\""), "stale mcpServers.netbeans should be removed on merge");
        assertFalse(result.contains("mcpServers"), "mcpServers should be gone if netbeans was the only entry");
    }

    @Test
    void testMergeRemovesStaleNetbeansButKeepsOtherMcpServers() {
        String stale = "{\"mcpServers\":{"
                + "\"netbeans\":{\"type\":\"sse\",\"url\":\"http://localhost:8888/sse\"},"
                + "\"other\":{\"type\":\"stdio\",\"command\":\"foo\"}}}";
        String result = ClaudeProcess.mergeSettingsJson(stale, 9000, null);
        assertFalse(result.contains("\"netbeans\""), "stale netbeans entry should be removed");
        assertTrue(result.contains("\"other\""), "other mcpServers entries should be preserved");
    }

    // -------------------------------------------------------------------------
    // cleanedSettingsJson
    // -------------------------------------------------------------------------

    @Test
    void testCleanupRemovesStaleNetbeansEntry() {
        // File has stale mcpServers.netbeans from old plugin + our hooks
        String stale = "{\"mcpServers\":{\"netbeans\":{\"type\":\"sse\",\"url\":\"http://localhost:8888/sse\"}},"
                + "\"hooks\":{\"Stop\":[{\"matcher\":\".*\",\"hooks\":[]}]}}";
        String cleaned = ClaudeProcess.cleanedSettingsJson(stale);
        assertNull(cleaned, "should delete file — only plugin-owned content after cleanup");
    }

    @Test
    void testCleanupRemovesStaleNetbeansButKeepsUserMcpServers() {
        String stale = "{\"mcpServers\":{"
                + "\"netbeans\":{\"type\":\"sse\",\"url\":\"http://localhost:8888/sse\"},"
                + "\"user-server\":{\"type\":\"stdio\",\"command\":\"bar\"}}}";
        String cleaned = ClaudeProcess.cleanedSettingsJson(stale);
        assertNotNull(cleaned, "should keep file — user-server remains");
        assertFalse(cleaned.contains("\"netbeans\""), "stale netbeans entry should be removed");
        assertTrue(cleaned.contains("\"user-server\""), "user server should be preserved");
    }

    @Test
    void testCleanupReturnsNullWhenOnlyOurEntries() {
        String merged = ClaudeProcess.mergeSettingsJson("{}", 9000, null);
        String cleaned = ClaudeProcess.cleanedSettingsJson(merged);
        assertNull(cleaned, "file containing only plugin entries should yield null (→ delete)");
    }

    @Test
    void testCleanupRemovesOurEntriesButKeepsOthers() {
        String existing = "{"
                + "\"mcpServers\":{\"other-server\":{\"type\":\"stdio\",\"command\":\"foo\"}},"
                + "\"hooks\":{\"PreToolUse\":["
                + "{\"matcher\":\"Read\",\"hooks\":[{\"type\":\"http\",\"url\":\"http://other/hook\"}]}"
                + "]}}";
        String merged = ClaudeProcess.mergeSettingsJson(existing, 9000, null);
        String cleaned = ClaudeProcess.cleanedSettingsJson(merged);

        assertNotNull(cleaned, "should not delete file when user content remains");
        assertFalse(cleaned.contains("\"netbeans\""), "should remove netbeans MCP server");
        assertFalse(cleaned.contains("Edit|Write|MultiEdit"), "should remove our PreToolUse hook");
        assertTrue(cleaned.contains("\"other-server\""), "should preserve other MCP server");
        assertTrue(cleaned.contains("\"Read\""), "should preserve other PreToolUse hooks");
    }

    @Test
    void testCleanupPreservesNonPreToolUseHooks() {
        String existing = "{\"hooks\":{\"PostToolUse\":[{\"matcher\":\"*\",\"hooks\":[]}]}}";
        String merged = ClaudeProcess.mergeSettingsJson(existing, 9000, null);
        String cleaned = ClaudeProcess.cleanedSettingsJson(merged);

        assertNotNull(cleaned, "should not delete when PostToolUse hook remains");
        assertTrue(cleaned.contains("PostToolUse"), "should preserve PostToolUse hook");
    }

    // -------------------------------------------------------------------------
    // cleanupSettingsLocalJson (I/O)
    // -------------------------------------------------------------------------

    @Test
    void testCleanupDeletesFileWhenOnlyPluginContent(@TempDir Path tmpDir) throws Exception {
        Path claudeDir = tmpDir.resolve(".claude");
        Files.createDirectories(claudeDir);
        Path cfg = claudeDir.resolve("settings.local.json");
        Files.writeString(cfg, ClaudeProcess.mergeSettingsJson("{}", 9000, null), StandardCharsets.UTF_8);

        ClaudeProcess.cleanupSettingsLocalJson(tmpDir.toString());

        assertFalse(Files.exists(cfg), "file should be deleted when only plugin content was present");
    }

    @Test
    void testCleanupUpdatesFileWhenUserContentPresent(@TempDir Path tmpDir) throws Exception {
        Path claudeDir = tmpDir.resolve(".claude");
        Files.createDirectories(claudeDir);
        Path cfg = claudeDir.resolve("settings.local.json");
        String userContent = "{\"mcpServers\":{\"my-server\":{\"type\":\"stdio\",\"command\":\"bar\"}}}";
        Files.writeString(cfg, ClaudeProcess.mergeSettingsJson(userContent, 9000, null), StandardCharsets.UTF_8);

        ClaudeProcess.cleanupSettingsLocalJson(tmpDir.toString());

        assertTrue(Files.exists(cfg), "file should remain when user content was present");
        String remaining = Files.readString(cfg);
        assertTrue(remaining.contains("my-server"), "user content should be preserved");
        assertFalse(remaining.contains("netbeans"), "plugin entry should be removed");
    }

    @Test
    void testCleanupIsNoopWhenFileAbsent(@TempDir Path tmpDir) {
        assertDoesNotThrow(() -> ClaudeProcess.cleanupSettingsLocalJson(tmpDir.toString()));
    }

    // -------------------------------------------------------------------------
    // readVersion
    // -------------------------------------------------------------------------

    @Test
    void testReadVersionReturnsStringOnSuccess() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(
                System.getProperty("os.name", "").toLowerCase().contains("win"),
                "Skipping on Windows");

        // Create a fake claude that prints a version line
        File script = makeFakeClaude("echo '1.2.3 (Claude Code)'\n");
        try {
            ClaudeProcess cp = new ClaudeProcess();
            String version = cp.readVersion();
            assertEquals("1.2.3 (Claude Code)", version);
        } finally {
            resetPrefs(script);
        }
    }

    @Test
    void testReadVersionReturnsEmptyStringOnFailure() {
        // Point to a non-existent executable
        io.github.nbclaudecodegui.settings.ClaudeCodePreferences
                .setClaudeExecutablePath("/nonexistent/claude-xyz");
        try {
            ClaudeProcess cp = new ClaudeProcess();
            String version = cp.readVersion();
            assertEquals("", version);
        } finally {
            io.github.nbclaudecodegui.settings.ClaudeCodePreferences
                    .setClaudeExecutablePath("");
        }
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private static int countOccurrences(String text, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    private static File makeFakeClaude(String body) throws Exception {
        File script = File.createTempFile("fake-claude", ".sh");
        script.deleteOnExit();
        Files.writeString(script.toPath(), "#!/bin/sh\n" + body);
        script.setExecutable(true);
        io.github.nbclaudecodegui.settings.ClaudeCodePreferences
                .setClaudeExecutablePath(script.getAbsolutePath());
        return script;
    }

    private static void resetPrefs(File script) {
        io.github.nbclaudecodegui.settings.ClaudeCodePreferences
                .setClaudeExecutablePath("");
        if (script != null) script.delete();
    }

    // -------------------------------------------------------------------------
    // appendSessionFlags — --continue only when sessions exist
    // -------------------------------------------------------------------------

    @Test
    void appendSessionFlags_continueLast_noSessionsDir_noFlag(@TempDir Path tmpDir) {
        ClaudeProcess cp = new ClaudeProcess();
        java.util.List<String> cmd = new java.util.ArrayList<>(java.util.List.of("claude"));
        // tmpDir has no sessions subdirectory → no --continue
        cp.appendSessionFlags(cmd, tmpDir.toString(), java.util.Map.of(),
                "", io.github.nbclaudecodegui.model.SessionMode.CONTINUE_LAST, null);
        assertFalse(cmd.contains("--continue"),
                "should NOT add --continue when no sessions exist");
    }

    @Test
    void appendSessionFlags_continueLast_sessionsExist_addsFlag(@TempDir Path tmpDir) throws Exception {
        // Create a fake sessions dir with one .jsonl file
        String hash = tmpDir.toString().replace('/', '-');
        Path sessionsDir = tmpDir.resolve(".claude").resolve("projects").resolve(hash);
        Files.createDirectories(sessionsDir);
        Files.writeString(sessionsDir.resolve("fake-session.jsonl"), "{}\n");

        ClaudeProcess cp = new ClaudeProcess();
        java.util.List<String> cmd = new java.util.ArrayList<>(java.util.List.of("claude"));
        cp.appendSessionFlags(cmd, tmpDir.toString(),
                java.util.Map.of("CLAUDE_CONFIG_DIR", tmpDir.resolve(".claude").toString()),
                "", io.github.nbclaudecodegui.model.SessionMode.CONTINUE_LAST, null);
        assertTrue(cmd.contains("--continue"),
                "should add --continue when sessions exist");
    }

    @Test
    void appendSessionFlags_newMode_noFlag(@TempDir Path tmpDir) throws Exception {
        // Even if sessions exist, NEW mode should not add any session flag
        String hash = tmpDir.toString().replace('/', '-');
        Path sessionsDir = tmpDir.resolve(".claude").resolve("projects").resolve(hash);
        Files.createDirectories(sessionsDir);
        Files.writeString(sessionsDir.resolve("fake-session.jsonl"), "{}\n");

        ClaudeProcess cp = new ClaudeProcess();
        java.util.List<String> cmd = new java.util.ArrayList<>(java.util.List.of("claude"));
        cp.appendSessionFlags(cmd, tmpDir.toString(),
                java.util.Map.of("CLAUDE_CONFIG_DIR", tmpDir.resolve(".claude").toString()),
                "", io.github.nbclaudecodegui.model.SessionMode.NEW, null);
        assertFalse(cmd.contains("--continue"), "NEW mode should not add --continue");
        assertFalse(cmd.contains("--resume"), "NEW mode should not add --resume");
    }

    @Test
    void appendSessionFlags_resumeSpecific_addsResumeFlag(@TempDir Path tmpDir) {
        ClaudeProcess cp = new ClaudeProcess();
        java.util.List<String> cmd = new java.util.ArrayList<>(java.util.List.of("claude"));
        cp.appendSessionFlags(cmd, tmpDir.toString(), java.util.Map.of(),
                "", io.github.nbclaudecodegui.model.SessionMode.RESUME_SPECIFIC, "abc-123");
        assertTrue(cmd.contains("--resume"), "should add --resume");
        assertTrue(cmd.contains("abc-123"), "should add the session id");
        assertFalse(cmd.contains("--continue"), "should not add --continue");
    }

    // -------------------------------------------------------------------------
    // parseArgs
    // -------------------------------------------------------------------------

    @Test
    void parseArgs_blank_returnsEmpty() {
        assertTrue(ClaudeProcess.parseArgs("").isEmpty());
        assertTrue(ClaudeProcess.parseArgs("   ").isEmpty());
        assertTrue(ClaudeProcess.parseArgs(null).isEmpty());
    }

    @Test
    void parseArgs_simple_splitsBySpace() {
        java.util.List<String> result = ClaudeProcess.parseArgs("--verbose --model foo");
        assertEquals(java.util.List.of("--verbose", "--model", "foo"), result);
    }

    @Test
    void parseArgs_quotedArg_preservesSpaces() {
        java.util.List<String> result = ClaudeProcess.parseArgs("--model \"claude opus 4\"");
        assertEquals(java.util.List.of("--model", "claude opus 4"), result);
    }

    @Test
    void parseArgs_trailingSpaces_ignored() {
        java.util.List<String> result = ClaudeProcess.parseArgs("  --verbose  ");
        assertEquals(java.util.List.of("--verbose"), result);
    }
}
