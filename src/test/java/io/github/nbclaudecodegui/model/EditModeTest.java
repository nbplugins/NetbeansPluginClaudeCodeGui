package io.github.nbclaudecodegui.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link EditMode}.
 */
class EditModeTest {

    @Test
    void keyRoundtrip() {
        for (EditMode m : EditMode.values()) {
            assertEquals(m, EditMode.fromKey(m.key()).orElseThrow(),
                    "fromKey(key()) must round-trip for " + m);
        }
    }

    @Test
    void fromKeyUnknownReturnsEmpty() {
        assertTrue(EditMode.fromKey("nonExistent").isEmpty());
        assertTrue(EditMode.fromKey(null).isEmpty());
    }

    @Test
    void fromKeyOrDefaultFallsBackToDefault() {
        assertEquals(EditMode.DEFAULT, EditMode.fromKeyOrDefault(null));
        assertEquals(EditMode.DEFAULT, EditMode.fromKeyOrDefault("unknown"));
        assertEquals(EditMode.ACCEPT_EDITS, EditMode.fromKeyOrDefault("acceptEdits"));
    }

    @Test
    void bypassPermissionsKeyValue() {
        assertEquals("bypassPermissions", EditMode.BYPASS_PERMISSIONS.key());
    }

    @Test
    void allModesHaveUniqueKeys() {
        long distinct = java.util.Arrays.stream(EditMode.values())
                .map(EditMode::key)
                .distinct()
                .count();
        assertEquals(EditMode.values().length, distinct, "All EditMode keys must be unique");
    }
}
