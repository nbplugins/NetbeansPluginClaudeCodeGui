package io.github.nbclaudecodegui.ui;

import io.github.nbclaudecodegui.model.SessionMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SessionModePanelTest {

    @Test
    void isSelectionValid_falseForResumeSpecificWithNoRowSelected() {
        SessionModePanel panel = new SessionModePanel(null, null, null, false);
        panel.setMode(SessionMode.RESUME_SPECIFIC);
        assertFalse(panel.isSelectionValid(),
                "RESUME_SPECIFIC with no row selected should be invalid");
    }

    @Test
    void isSelectionValid_trueForNewMode() {
        SessionModePanel panel = new SessionModePanel(null, null, null, false);
        panel.setMode(SessionMode.NEW);
        assertTrue(panel.isSelectionValid());
    }

    @Test
    void isSelectionValid_trueForContinueLast() {
        SessionModePanel panel = new SessionModePanel(null, null, null, false);
        panel.setMode(SessionMode.CONTINUE_LAST);
        assertTrue(panel.isSelectionValid());
    }

    @Test
    void isSelectionValid_trueForCloseOnly() {
        SessionModePanel panel = new SessionModePanel(null, null, null, true);
        panel.setMode(SessionMode.CLOSE_ONLY);
        assertTrue(panel.isSelectionValid());
    }

    @Test
    void getSelectedMode_defaultIsContinueLast_whenShowCloseOnlyFalse() {
        SessionModePanel panel = new SessionModePanel(null, null, null, false);
        assertEquals(SessionMode.CONTINUE_LAST, panel.getSelectedMode());
    }

    @Test
    void getSelectedMode_defaultIsCloseOnly_whenShowCloseOnlyTrue() {
        SessionModePanel panel = new SessionModePanel(null, null, null, true);
        assertEquals(SessionMode.CLOSE_ONLY, panel.getSelectedMode());
    }

    @Test
    void getSelectedSessionId_nullWhenNotResumeSpecific() {
        SessionModePanel panel = new SessionModePanel(null, null, null, false);
        panel.setMode(SessionMode.NEW);
        assertNull(panel.getSelectedSessionId());
    }

    @Test
    void renameButton_hiddenWhenNewModeSelected() {
        SessionModePanel panel = new SessionModePanel(null, null, null, false);
        panel.setMode(SessionMode.NEW);
        assertFalse(panel.isRenameButtonVisible(), "Rename button should be hidden when NEW mode is selected");
    }

    @Test
    void renameButton_hiddenWhenContinueLastSelected() {
        SessionModePanel panel = new SessionModePanel(null, null, null, false);
        panel.setMode(SessionMode.CONTINUE_LAST);
        assertFalse(panel.isRenameButtonVisible(), "Rename button should be hidden when CONTINUE_LAST mode is selected");
    }

    @Test
    void renameButton_visibleWhenResumeSpecificSelected() {
        SessionModePanel panel = new SessionModePanel(null, null, null, false);
        panel.setMode(SessionMode.RESUME_SPECIFIC);
        assertTrue(panel.isRenameButtonVisible(), "Rename button should be visible when RESUME_SPECIFIC mode is selected");
    }

    @Test
    void renameButton_hiddenByDefault() {
        SessionModePanel panel = new SessionModePanel(null, null, null, false);
        assertFalse(panel.isRenameButtonVisible(), "Rename button should be hidden by default (CONTINUE_LAST is default)");
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
}
