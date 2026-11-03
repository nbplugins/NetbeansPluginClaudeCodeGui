package io.github.nbplugins.claudecodegui.ui;

import io.github.nbplugins.claudecodegui.model.SavedSession;
import io.github.nbplugins.claudecodegui.model.SessionMode;
import io.github.nbplugins.claudecodegui.process.ClaudeSessionStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class SessionModePanelTest {

    @TempDir Path emptyDir;
    @TempDir Path sessionDir;
    @TempDir Path otherSessionDir;

    /** Creates a fake sessions directory with one .jsonl stub so hasAnySessions() returns true. */
    private Path withSession(Path workingDir) throws IOException {
        // ClaudeSessionStore hashes the path by replacing / with -
        String hash = workingDir.toAbsolutePath().toString().replace("/", "-");
        Path sessionsSubDir = workingDir.resolve(".claude").resolve("projects").resolve(hash);
        Files.createDirectories(sessionsSubDir);
        Files.writeString(sessionsSubDir.resolve("fake-session.jsonl"), "{}");
        return workingDir.resolve(".claude");
    }

    /**
     * Creates a sessions directory with timestamped JSONL stubs so that
     * {@link ClaudeSessionStore#listSessions} returns them as table rows.
     */
    private Path withSessions(Path workingDir, String... sessionIds) throws IOException {
        Path configDir = workingDir.resolve(".claude");
        String hash = workingDir.toAbsolutePath().toString().replace("/", "-");
        Path sessionsSubDir = configDir.resolve("projects").resolve(hash);
        Files.createDirectories(sessionsSubDir);
        for (String id : sessionIds) {
            Files.writeString(sessionsSubDir.resolve(id + ".jsonl"),
                    "{\"sessionId\":\"" + id + "\",\"timestamp\":\"2026-01-01T00:00:00Z\"}\n");
        }
        return configDir;
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
    // Selection survives session-list refreshes
    // -------------------------------------------------------------------------

    @Test
    void applySessions_keepsSelectedSessionById() throws IOException {
        Path configDir = withSessions(sessionDir, "session-a", "session-b");
        SessionModePanel panel = new SessionModePanel(sessionDir, configDir, null, false);
        panel.setMode(SessionMode.RESUME_SPECIFIC);
        panel.selectSessionById("session-a");
        assertEquals("session-a", panel.getSelectedSessionId());

        List<SavedSession> reloaded = ClaudeSessionStore.listSessions(sessionDir, configDir);
        panel.applySessions(sessionDir, configDir, null, reloaded);

        assertEquals("session-a", panel.getSelectedSessionId(),
                "The selected session must survive a background refresh");
        assertTrue(panel.isSelectionValid(),
                "Resume specific must stay valid while the selected session is still listed");
    }

    @Test
    void reload_keepsSelectedSessionById() throws IOException {
        Path configDir = withSessions(sessionDir, "session-a", "session-b");
        SessionModePanel panel = new SessionModePanel(sessionDir, configDir, null, false);
        panel.setMode(SessionMode.RESUME_SPECIFIC);
        panel.selectSessionById("session-b");

        panel.reload(sessionDir, configDir, null);

        assertEquals("session-b", panel.getSelectedSessionId(),
                "Reloading the same directory must keep the selected session");
        assertTrue(panel.isSelectionValid());
    }

    @Test
    void applySessions_dropsSelectionWhenTheSessionIsGone() throws IOException {
        Path configDir = withSessions(sessionDir, "session-a");
        SessionModePanel panel = new SessionModePanel(sessionDir, configDir, null, false);
        panel.setMode(SessionMode.RESUME_SPECIFIC);
        panel.selectSessionById("session-a");
        assertTrue(panel.isSelectionValid());

        panel.applySessions(sessionDir, configDir, null, List.of());

        assertNull(panel.getSelectedSessionId());
        assertFalse(panel.isSelectionValid(),
                "Resume specific without a row must not be accepted by the Open button");
    }

    @Test
    void applySessions_excludedSessionIsNotSelected() throws IOException {
        Path configDir = withSessions(sessionDir, "session-a", "session-b");
        SessionModePanel panel = new SessionModePanel(sessionDir, configDir, null, false);
        panel.setMode(SessionMode.RESUME_SPECIFIC);
        panel.selectSessionById("session-a");

        List<SavedSession> reloaded = ClaudeSessionStore.listSessions(sessionDir, configDir);
        panel.applySessions(sessionDir, configDir, "session-a", reloaded);

        assertNull(panel.getSelectedSessionId(),
                "The excluded (current) session must not stay selected");
        assertFalse(panel.isSelectionValid());
    }

    @Test
    void applySessionsIfCurrent_appliesForTheShownStore() throws IOException {
        Path configDir = withSessions(sessionDir, "session-a");
        SessionModePanel panel = new SessionModePanel(sessionDir, configDir, null, false);
        panel.setMode(SessionMode.RESUME_SPECIFIC);

        List<SavedSession> reloaded = ClaudeSessionStore.listSessions(sessionDir, configDir);
        assertTrue(panel.applySessionsIfCurrent(sessionDir, configDir, reloaded));
    }

    @Test
    void applySessionsIfCurrent_discardsALoadForAnotherStore() throws IOException {
        Path configA = withSessions(sessionDir, "session-a");
        Path configB = withSessions(otherSessionDir, "session-b");

        SessionModePanel panel = new SessionModePanel(sessionDir, configA, null, false);
        panel.setMode(SessionMode.RESUME_SPECIFIC);
        panel.selectSessionById("session-a");

        // The user switches to another project while a load for the old one
        // is still in flight.
        panel.reload(otherSessionDir, configB, null);
        panel.selectSessionById("session-b");

        List<SavedSession> stale = ClaudeSessionStore.listSessions(sessionDir, configA);
        assertFalse(panel.applySessionsIfCurrent(sessionDir, configA, stale),
                "Sessions loaded for the previous selection must be discarded");

        assertEquals("session-b", panel.getSelectedSessionId(),
                "The table must still show the newly selected directory's sessions");
        assertTrue(panel.isSelectionValid());
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
