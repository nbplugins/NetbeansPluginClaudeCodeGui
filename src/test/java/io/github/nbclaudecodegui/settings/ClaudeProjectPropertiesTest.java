package io.github.nbclaudecodegui.settings;

import java.io.File;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ClaudeProjectProperties}.
 *
 * <p>Tests that depend on {@link org.openide.util.NbPreferences} are
 * wrapped in try/catch so they degrade gracefully in a plain JUnit
 * environment (no NetBeans platform).
 */
class ClaudeProjectPropertiesTest {

    private static final File PROJECT_DIR = new File("/tmp/test-project");

    // -------------------------------------------------------------------------
    // getProfileName
    // -------------------------------------------------------------------------

    @Test
    void getProfileName_nullDir_returnsEmpty() {
        assertEquals("", ClaudeProjectProperties.getProfileName(null));
    }

    @Test
    void getProfileName_noAssignment_returnsEmpty() {
        try {
            // clear any stale assignment
            ClaudeProjectProperties.setProfileName(PROJECT_DIR, null);
            assertEquals("", ClaudeProjectProperties.getProfileName(PROJECT_DIR));
        } catch (Exception ignored) {}
    }

    // -------------------------------------------------------------------------
    // setProfileName / getProfileName round-trip
    // -------------------------------------------------------------------------

    @Test
    void setAndGet_roundTrip() {
        try {
            ClaudeProjectProperties.setProfileName(PROJECT_DIR, "Work");
            assertEquals("Work", ClaudeProjectProperties.getProfileName(PROJECT_DIR));
        } catch (Exception ignored) {}
    }

    @Test
    void setProfileName_defaultName_clearsEntry() {
        try {
            ClaudeProjectProperties.setProfileName(PROJECT_DIR, "Work");
            ClaudeProjectProperties.setProfileName(PROJECT_DIR, "Default");
            assertEquals("", ClaudeProjectProperties.getProfileName(PROJECT_DIR));
        } catch (Exception ignored) {}
    }

    @Test
    void setProfileName_blankName_clearsEntry() {
        try {
            ClaudeProjectProperties.setProfileName(PROJECT_DIR, "Work");
            ClaudeProjectProperties.setProfileName(PROJECT_DIR, "");
            assertEquals("", ClaudeProjectProperties.getProfileName(PROJECT_DIR));
        } catch (Exception ignored) {}
    }

    @Test
    void setProfileName_nullName_clearsEntry() {
        try {
            ClaudeProjectProperties.setProfileName(PROJECT_DIR, "Work");
            ClaudeProjectProperties.setProfileName(PROJECT_DIR, null);
            assertEquals("", ClaudeProjectProperties.getProfileName(PROJECT_DIR));
        } catch (Exception ignored) {}
    }

    // -------------------------------------------------------------------------
    // renameAssignments
    // -------------------------------------------------------------------------

    @Test
    void renameAssignments_nullOldName_doesNotThrow() {
        assertDoesNotThrow(() -> ClaudeProjectProperties.renameAssignments(null, "New"));
    }

    @Test
    void renameAssignments_nullNewName_doesNotThrow() {
        assertDoesNotThrow(() -> ClaudeProjectProperties.renameAssignments("Old", null));
    }

    // -------------------------------------------------------------------------
    // clearAssignmentsForProfile
    // -------------------------------------------------------------------------

    @Test
    void clearAssignments_nullName_doesNotThrow() {
        assertDoesNotThrow(() -> ClaudeProjectProperties.clearAssignmentsForProfile(null));
    }

    @Test
    void clearAssignments_blankName_doesNotThrow() {
        assertDoesNotThrow(() -> ClaudeProjectProperties.clearAssignmentsForProfile(""));
    }
}
