package io.github.nbplugins.claudecodegui.ui;

import io.github.nbplugins.claudecodegui.model.EditMode;
import io.github.nbplugins.claudecodegui.model.SessionLifecycle;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the bypass-permissions combo entry:
 * once detected, the 5th "Bypass Permissions" entry must remain visible
 * when the user switches to another edit mode, and must only be removed
 * when a new session starts (STARTING lifecycle).
 *
 * <p>Base combo has 4 static entries: Plan Mode, Ask on Edit, Accept on Edit, Auto Mode.
 * Bypass Permissions is added dynamically at index 4 when detected.
 */
class ClaudeSessionTabBypassComboTest {

    @Test
    void bypassEntry_appearsWhenBypassDetected() {
        ClaudeSessionTab tab = new ClaudeSessionTab();
        assertEquals(ClaudeSessionTab.EDIT_MODE_VALUES.length, tab.editModeItemCount(),
                "Initially combo should have 4 items (Plan, Ask on Edit, Accept on Edit, Auto Mode)");

        tab.onEditModeChanged(EditMode.BYPASS_PERMISSIONS);
        assertEquals(ClaudeSessionTab.EDIT_MODE_VALUES.length + 1, tab.editModeItemCount(),
                "After BYPASS_PERMISSIONS, combo should have 5 items");
    }

    @Test
    void bypassEntry_persistsAfterSwitchingToAnotherMode() {
        ClaudeSessionTab tab = new ClaudeSessionTab();
        tab.onEditModeChanged(EditMode.BYPASS_PERMISSIONS);
        assertEquals(5, tab.editModeItemCount());

        tab.onEditModeChanged(EditMode.PLAN);
        assertEquals(5, tab.editModeItemCount(),
                "Bypass entry must remain after switching to PLAN mode");

        tab.onEditModeChanged(EditMode.DEFAULT);
        assertEquals(5, tab.editModeItemCount(),
                "Bypass entry must remain after switching to DEFAULT mode");

        tab.onEditModeChanged(EditMode.ACCEPT_EDITS);
        assertEquals(5, tab.editModeItemCount(),
                "Bypass entry must remain after switching to ACCEPT_EDITS mode");

        tab.onEditModeChanged(EditMode.AUTO);
        assertEquals(5, tab.editModeItemCount(),
                "Bypass entry must remain after switching to AUTO mode");
    }

    @Test
    void bypassEntry_removedOnNewSession() {
        ClaudeSessionTab tab = new ClaudeSessionTab();
        tab.onEditModeChanged(EditMode.BYPASS_PERMISSIONS);
        assertEquals(5, tab.editModeItemCount());

        tab.onLifecycleChanged(SessionLifecycle.STARTING);
        assertEquals(4, tab.editModeItemCount(),
                "Bypass entry must be removed when a new session starts");
    }

    @Test
    void bypassEntry_notAddedWithoutBypassSession() {
        ClaudeSessionTab tab = new ClaudeSessionTab();
        tab.onEditModeChanged(EditMode.PLAN);
        tab.onEditModeChanged(EditMode.DEFAULT);
        tab.onEditModeChanged(EditMode.ACCEPT_EDITS);
        tab.onEditModeChanged(EditMode.AUTO);
        assertEquals(4, tab.editModeItemCount(),
                "Bypass entry must not appear in a non-bypass session");
    }

    @Test
    void bypassEntry_notAddedTwice() {
        ClaudeSessionTab tab = new ClaudeSessionTab();
        tab.onEditModeChanged(EditMode.BYPASS_PERMISSIONS);
        tab.onEditModeChanged(EditMode.BYPASS_PERMISSIONS);
        assertEquals(5, tab.editModeItemCount(),
                "Bypass entry must not be added twice");
    }
}
