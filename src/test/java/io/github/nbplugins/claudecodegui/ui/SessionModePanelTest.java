package io.github.nbplugins.claudecodegui.ui;

import io.github.nbplugins.claudecodegui.model.SessionMode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class SessionModePanelTest {

    @TempDir Path emptyDir;
    @TempDir Path sessionDir;

    /** Creates a fake sessions directory with one .jsonl stub so hasAnySessions() returns true. */
    private Path withSession(Path workingDir) throws IOException {
        // ClaudeSessionStore hashes the path by replacing / with -
        String hash = workingDir.toAbsolutePath().toString().replace("/", "-");
        Path sessionsSubDir = workingDir.resolve(".claude").resolve("projects").resolve(hash);
        Files.createDirectories(sessionsSubDir);
        Files.writeString(sessionsSubDir.resolve("fake-session.jsonl"), "{}");
        return workingDir.resolve(".claude");
    }

    // -------------------------------------------------------------------------
    // No-sessions: radios disabled and selection falls back to NEW
    // -------------------------------------------------------------------------

    @Test
    void noSessions_continueAndResumeDisabled() {
        SessionModePanel panel = new SessionModePanel(emptyDir, null, null, false);
        assertFalse(panel.isContinueRadioEnabled(), "Continue last should be disabled when no sessions");
        assertFalse(panel.isResumeRadioEnabled(),   "Resume specific should be disabled when no sessions");
    }

    @Test
    void noSessions_defaultSelectionIsNew() {
        SessionModePanel panel = new SessionModePanel(emptyDir, null, null, false);
        assertEquals(SessionMode.NEW, panel.getSelectedMode());
    }

    @Test
    void noSessions_setModeContinueLast_fallsBackToNew() {
        SessionModePanel panel = new SessionModePanel(emptyDir, null, null, false);
        panel.setMode(SessionMode.CONTINUE_LAST);
        assertEquals(SessionMode.NEW, panel.getSelectedMode());
    }

    @Test
    void noSessions_setModeResumeSpecific_fallsBackToNew() {
        SessionModePanel panel = new SessionModePanel(emptyDir, null, null, false);
        panel.setMode(SessionMode.RESUME_SPECIFIC);
        assertEquals(SessionMode.NEW, panel.getSelectedMode());
    }

    @Test
    void noSessions_reload_switchesFromContinueToNew() throws IOException {
        // Start with a dir that has sessions, select CONTINUE_LAST
        Path configDir = withSession(sessionDir);
        SessionModePanel panel = new SessionModePanel(sessionDir, configDir, null, false);
        panel.setMode(SessionMode.CONTINUE_LAST);
        assertEquals(SessionMode.CONTINUE_LAST, panel.getSelectedMode());

        // Reload with an empty dir → should fall back to NEW
        panel.reload(emptyDir, null, null);
        assertEquals(SessionMode.NEW, panel.getSelectedMode());
        assertFalse(panel.isContinueRadioEnabled());
    }

    // -------------------------------------------------------------------------
    // With sessions: radios enabled
    // -------------------------------------------------------------------------

    @Test
    void withSessions_radiosEnabled() throws IOException {
        Path configDir = withSession(sessionDir);
        SessionModePanel panel = new SessionModePanel(sessionDir, configDir, null, false);
        assertTrue(panel.isContinueRadioEnabled(),  "Continue last should be enabled when sessions exist");
        assertTrue(panel.isResumeRadioEnabled(),    "Resume specific should be enabled when sessions exist");
    }

    @Test
    void withSessions_setModeContinueLast_selects() throws IOException {
        Path configDir = withSession(sessionDir);
        SessionModePanel panel = new SessionModePanel(sessionDir, configDir, null, false);
        panel.setMode(SessionMode.CONTINUE_LAST);
        assertEquals(SessionMode.CONTINUE_LAST, panel.getSelectedMode());
    }

    @Test
    void withSessions_setModeResumeSpecific_selects() throws IOException {
        Path configDir = withSession(sessionDir);
        SessionModePanel panel = new SessionModePanel(sessionDir, configDir, null, false);
        panel.setMode(SessionMode.RESUME_SPECIFIC);
        assertEquals(SessionMode.RESUME_SPECIFIC, panel.getSelectedMode());
    }

    @Test
    void withSessions_reload_restoresContinueLast() throws IOException {
        // Start with empty dir
        SessionModePanel panel = new SessionModePanel(emptyDir, null, null, false);
        assertEquals(SessionMode.NEW, panel.getSelectedMode());

        // Reload with sessions, then apply preferred mode
        Path configDir = withSession(sessionDir);
        panel.reload(sessionDir, configDir, null);
        panel.setMode(SessionMode.CONTINUE_LAST);
        assertEquals(SessionMode.CONTINUE_LAST, panel.getSelectedMode());
    }

    // -------------------------------------------------------------------------
    // Null workingDir (selector opened with no directory chosen yet)
    // -------------------------------------------------------------------------

    @Test
    void nullWorkingDir_defaultIsNew() {
        SessionModePanel panel = new SessionModePanel(null, null, null, false);
        assertEquals(SessionMode.NEW, panel.getSelectedMode());
    }

    // -------------------------------------------------------------------------
    // Selection validity
    // -------------------------------------------------------------------------

    @Test
    void isSelectionValid_trueForNewMode() {
        SessionModePanel panel = new SessionModePanel(null, null, null, false);
        panel.setMode(SessionMode.NEW);
        assertTrue(panel.isSelectionValid());
    }

    @Test
    void isSelectionValid_trueForContinueLast() throws IOException {
        Path configDir = withSession(sessionDir);
        SessionModePanel panel = new SessionModePanel(sessionDir, configDir, null, false);
        panel.setMode(SessionMode.CONTINUE_LAST);
        assertTrue(panel.isSelectionValid());
    }

    @Test
    void isSelectionValid_falseForResumeSpecificWithNoRowSelected() throws IOException {
        Path configDir = withSession(sessionDir);
        SessionModePanel panel = new SessionModePanel(sessionDir, configDir, null, false);
        panel.setMode(SessionMode.RESUME_SPECIFIC);
        assertFalse(panel.isSelectionValid(),
                "RESUME_SPECIFIC with no row selected should be invalid");
    }

    @Test
    void isSelectionValid_trueForCloseOnly() {
        SessionModePanel panel = new SessionModePanel(null, null, null, true);
        panel.setMode(SessionMode.CLOSE_ONLY);
        assertTrue(panel.isSelectionValid());
    }

    // -------------------------------------------------------------------------
    // Default selection
    // -------------------------------------------------------------------------

    @Test
    void getSelectedMode_defaultIsCloseOnly_whenShowCloseOnlyTrue() {
        SessionModePanel panel = new SessionModePanel(null, null, null, true);
        assertEquals(SessionMode.CLOSE_ONLY, panel.getSelectedMode());
    }

    @Test
    void getSelectedMode_restartAdvanced_whenSet() {
        SessionModePanel panel = new SessionModePanel(null, null, null, true);
        panel.setMode(SessionMode.RESTART_ADVANCED);
        assertEquals(SessionMode.RESTART_ADVANCED, panel.getSelectedMode());
    }

    @Test
    void isSelectionValid_trueForRestartAdvanced() {
        SessionModePanel panel = new SessionModePanel(null, null, null, true);
        panel.setMode(SessionMode.RESTART_ADVANCED);
        assertTrue(panel.isSelectionValid());
    }

    // -------------------------------------------------------------------------
    // getSelectedSessionId
    // -------------------------------------------------------------------------

    @Test
    void getSelectedSessionId_nullWhenNotResumeSpecific() {
        SessionModePanel panel = new SessionModePanel(null, null, null, false);
        panel.setMode(SessionMode.NEW);
        assertNull(panel.getSelectedSessionId());
    }

    // -------------------------------------------------------------------------
    // Rename button visibility
    // -------------------------------------------------------------------------

    @Test
    void renameButton_hiddenWhenNewModeSelected() {
        SessionModePanel panel = new SessionModePanel(null, null, null, false);
        panel.setMode(SessionMode.NEW);
        assertFalse(panel.isRenameButtonVisible(), "Rename button should be hidden when NEW mode is selected");
    }

    @Test
    void renameButton_hiddenWhenContinueLastSelected() throws IOException {
        Path configDir = withSession(sessionDir);
        SessionModePanel panel = new SessionModePanel(sessionDir, configDir, null, false);
        panel.setMode(SessionMode.CONTINUE_LAST);
        assertFalse(panel.isRenameButtonVisible(), "Rename button should be hidden when CONTINUE_LAST mode is selected");
    }

    @Test
    void renameButton_visibleWhenResumeSpecificSelected() throws IOException {
        Path configDir = withSession(sessionDir);
        SessionModePanel panel = new SessionModePanel(sessionDir, configDir, null, false);
        panel.setMode(SessionMode.RESUME_SPECIFIC);
        assertTrue(panel.isRenameButtonVisible(), "Rename button should be visible when RESUME_SPECIFIC mode is selected");
    }

    @Test
    void renameButton_hiddenByDefault() {
        SessionModePanel panel = new SessionModePanel(null, null, null, false);
        assertFalse(panel.isRenameButtonVisible(), "Rename button should be hidden by default");
    }
}
