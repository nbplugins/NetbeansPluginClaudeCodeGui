package io.github.nbclaudecodegui.settings;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Unit tests for {@link ClaudeCodePreferences}.
 */
class ClaudeCodePreferencesTest {

    @AfterEach
    void tearDown() {
        ClaudeCodePreferences.setClaudeExecutablePath(
                ClaudeCodePreferences.DEFAULT_CLAUDE_EXECUTABLE_PATH);
        ClaudeCodePreferences.setMcpPort(ClaudeCodePreferences.DEFAULT_MCP_PORT);
        ClaudeCodePreferences.setOpenDiffInSeparateTab(ClaudeCodePreferences.DEFAULT_OPEN_DIFF_IN_SEPARATE_TAB);
        ClaudeCodePreferences.setMdPreviewInDiff(ClaudeCodePreferences.DEFAULT_MD_PREVIEW_IN_DIFF);
        ClaudeCodePreferences.setSessionDockMode(ClaudeCodePreferences.DEFAULT_SESSION_DOCK_MODE);
    }

    @Test
    void defaultPathIsEmpty() {
        assertEquals(ClaudeCodePreferences.DEFAULT_CLAUDE_EXECUTABLE_PATH,
                ClaudeCodePreferences.getClaudeExecutablePath());
    }

    @Test
    void storeAndRetrievePath() {
        ClaudeCodePreferences.setClaudeExecutablePath("/usr/local/bin/claude");
        assertEquals("/usr/local/bin/claude",
                ClaudeCodePreferences.getClaudeExecutablePath());
    }

    @Test
    void nullPathStoredAsDefault() {
        ClaudeCodePreferences.setClaudeExecutablePath(null);
        assertEquals(ClaudeCodePreferences.DEFAULT_CLAUDE_EXECUTABLE_PATH,
                ClaudeCodePreferences.getClaudeExecutablePath());
    }

    @Test
    void testFindOnPathFindsRealExecutable() {
        String result = ClaudeCodePreferences.findOnPath();
        if (result != null) {
            assertTrue(new java.io.File(result).canExecute(),
                    "findOnPath must return an executable path");
        }
    }

    @Test
    void mcpPortDefaultIs28991() {
        assertEquals(28991, ClaudeCodePreferences.DEFAULT_MCP_PORT);
        assertEquals(28991, ClaudeCodePreferences.getMcpPort());
    }

    @Test
    void mcpPortStoreAndRetrieve() {
        ClaudeCodePreferences.setMcpPort(9000);
        assertEquals(9000, ClaudeCodePreferences.getMcpPort());
    }

    @Test
    void testResolveClaudeExecutableFallsBackToLiteral() {
        ClaudeCodePreferences.setClaudeExecutablePath("");
        String resolved = ClaudeCodePreferences.resolveClaudeExecutable();
        assertNotNull(resolved);
        assertFalse(resolved.isBlank(),
                "resolveClaudeExecutable must never return blank");
    }

    @Test
    void openDiffInSeparateTabDefaultIsFalse() {
        assertFalse(ClaudeCodePreferences.isOpenDiffInSeparateTab());
    }

    @Test
    void openDiffInSeparateTabSetTrueReadBackTrue() {
        ClaudeCodePreferences.setOpenDiffInSeparateTab(true);
        assertTrue(ClaudeCodePreferences.isOpenDiffInSeparateTab());
    }

    @Test
    void openDiffInSeparateTabSetFalseReadBackFalse() {
        ClaudeCodePreferences.setOpenDiffInSeparateTab(true);
        ClaudeCodePreferences.setOpenDiffInSeparateTab(false);
        assertFalse(ClaudeCodePreferences.isOpenDiffInSeparateTab());
    }

    @Test
    void mdPreviewInDiffDefaultIsTrue() {
        assertTrue(ClaudeCodePreferences.isMdPreviewInDiff());
    }

    @Test
    void mdPreviewInDiffStoreAndRetrieve() {
        ClaudeCodePreferences.setMdPreviewInDiff(false);
        assertFalse(ClaudeCodePreferences.isMdPreviewInDiff());
    }

    @Test
    void sessionDockModeDefaultIsEditor() {
        assertEquals(ClaudeCodePreferences.DEFAULT_SESSION_DOCK_MODE,
                ClaudeCodePreferences.getSessionDockMode());
        assertEquals("editor", ClaudeCodePreferences.getSessionDockMode());
    }

    @Test
    void sessionDockModeStoreAndRetrieve() {
        ClaudeCodePreferences.setSessionDockMode("output");
        assertEquals("output", ClaudeCodePreferences.getSessionDockMode());
    }

    @Test
    void sessionDockModeNullFallsBackToDefault() {
        ClaudeCodePreferences.setSessionDockMode(null);
        assertEquals(ClaudeCodePreferences.DEFAULT_SESSION_DOCK_MODE,
                ClaudeCodePreferences.getSessionDockMode());
    }
}
