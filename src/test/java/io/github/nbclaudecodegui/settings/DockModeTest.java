package io.github.nbclaudecodegui.settings;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DockMode}.
 */
class DockModeTest {

    @Test
    void fromModeNameKnownValue() {
        assertEquals(DockMode.RIGHT, DockMode.fromModeName("commonpalette"));
    }

    @Test
    void fromModeNameUnknownFallsBackToEditor() {
        assertEquals(DockMode.EDITOR, DockMode.fromModeName("unknown"));
    }

    @Test
    void fromModeNameNullFallsBackToEditor() {
        assertEquals(DockMode.EDITOR, DockMode.fromModeName(null));
    }

    @Test
    void labelsLengthMatchesEnumSize() {
        assertEquals(DockMode.values().length, DockMode.labels(DockMode.EDITOR).length);
    }

    @Test
    void labelsContainsDefaultSuffix() {
        assertTrue(Arrays.stream(DockMode.labels(DockMode.RIGHT))
                .anyMatch(l -> l.contains("(default)")));
    }

    @Test
    void labelsDefaultSuffixOnCorrectEntry() {
        String[] labels = DockMode.labels(DockMode.RIGHT);
        int rightOrdinal = DockMode.RIGHT.ordinal();
        assertTrue(labels[rightOrdinal].contains("(default)"));
    }

    @Test
    void labelsNoDefaultSuffixOnOtherEntries() {
        String[] labels = DockMode.labels(DockMode.RIGHT);
        int rightOrdinal = DockMode.RIGHT.ordinal();
        for (int i = 0; i < labels.length; i++) {
            if (i != rightOrdinal) {
                assertFalse(labels[i].contains("(default)"),
                        "Entry " + i + " should not have (default) suffix");
            }
        }
    }
}
