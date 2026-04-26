package io.github.nbclaudecodegui.settings;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertFalse;
import io.github.nbclaudecodegui.settings.DockMode;

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
        ClaudeCodePreferences.setMarkdownPreviewDockMode(ClaudeCodePreferences.DEFAULT_MARKDOWN_PREVIEW_DOCK_MODE);
        ClaudeCodePreferences.setFileDiffDockMode(ClaudeCodePreferences.DEFAULT_FILE_DIFF_DOCK_MODE);
        ClaudeCodePreferences.setTerminalFontName(ClaudeCodePreferences.DEFAULT_TERMINAL_FONT_NAME);
        ClaudeCodePreferences.setTerminalFontSize(ClaudeCodePreferences.DEFAULT_TERMINAL_FONT_SIZE);
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
        assertEquals(DockMode.EDITOR, ClaudeCodePreferences.DEFAULT_SESSION_DOCK_MODE);
        assertEquals(DockMode.EDITOR, ClaudeCodePreferences.getSessionDockMode());
    }

    @Test
    void sessionDockModeStoreAndRetrieve() {
        ClaudeCodePreferences.setSessionDockMode(DockMode.BOTTOM);
        assertEquals(DockMode.BOTTOM, ClaudeCodePreferences.getSessionDockMode());
    }

    @Test
    void sessionDockModeNullFallsBackToDefault() {
        ClaudeCodePreferences.setSessionDockMode(null);
        assertEquals(DockMode.EDITOR, ClaudeCodePreferences.getSessionDockMode());
    }

    @Test
    void markdownPreviewDockModeDefaultIsRight() {
        assertEquals(DockMode.RIGHT, ClaudeCodePreferences.DEFAULT_MARKDOWN_PREVIEW_DOCK_MODE);
        assertEquals(DockMode.RIGHT, ClaudeCodePreferences.getMarkdownPreviewDockMode());
    }

    @Test
    void markdownPreviewDockModeStoreAndRetrieve() {
        ClaudeCodePreferences.setMarkdownPreviewDockMode(DockMode.BOTTOM);
        assertEquals(DockMode.BOTTOM, ClaudeCodePreferences.getMarkdownPreviewDockMode());
    }

    @Test
    void markdownPreviewDockModeNullFallsBackToDefault() {
        ClaudeCodePreferences.setMarkdownPreviewDockMode(null);
        assertEquals(DockMode.RIGHT, ClaudeCodePreferences.getMarkdownPreviewDockMode());
    }

    @Test
    void fileDiffDockModeDefaultIsEditor() {
        assertEquals(DockMode.EDITOR, ClaudeCodePreferences.DEFAULT_FILE_DIFF_DOCK_MODE);
        assertEquals(DockMode.EDITOR, ClaudeCodePreferences.getFileDiffDockMode());
    }

    @Test
    void fileDiffDockModeStoreAndRetrieve() {
        ClaudeCodePreferences.setFileDiffDockMode(DockMode.BOTTOM);
        assertEquals(DockMode.BOTTOM, ClaudeCodePreferences.getFileDiffDockMode());
    }

    @Test
    void fileDiffDockModeNullFallsBackToDefault() {
        ClaudeCodePreferences.setFileDiffDockMode(null);
        assertEquals(DockMode.EDITOR, ClaudeCodePreferences.getFileDiffDockMode());
    }

    @Test
    void terminalFontNameDefaultIsEmpty() {
        assertEquals("", ClaudeCodePreferences.DEFAULT_TERMINAL_FONT_NAME);
        assertEquals("", ClaudeCodePreferences.getTerminalFontName());
    }

    @Test
    void terminalFontNameRoundTrip() {
        ClaudeCodePreferences.setTerminalFontName("Adwaita Mono");
        assertEquals("Adwaita Mono", ClaudeCodePreferences.getTerminalFontName());
    }

    @Test
    void terminalFontNameNullStoredAsEmpty() {
        ClaudeCodePreferences.setTerminalFontName(null);
        assertEquals("", ClaudeCodePreferences.getTerminalFontName());
    }

    @Test
    void terminalFontSizeDefaultIs14() {
        assertEquals(14, ClaudeCodePreferences.DEFAULT_TERMINAL_FONT_SIZE);
        assertEquals(14, ClaudeCodePreferences.getTerminalFontSize());
    }

    @Test
    void terminalFontSizeRoundTrip() {
        ClaudeCodePreferences.setTerminalFontSize(20);
        assertEquals(20, ClaudeCodePreferences.getTerminalFontSize());
    }

    @Test
    void terminalFontSizeClampedToMin() {
        ClaudeCodePreferences.setTerminalFontSize(5);
        assertEquals(8, ClaudeCodePreferences.getTerminalFontSize());
    }

    @Test
    void terminalFontSizeClampedToMax() {
        ClaudeCodePreferences.setTerminalFontSize(100);
        assertEquals(72, ClaudeCodePreferences.getTerminalFontSize());
    }
}
