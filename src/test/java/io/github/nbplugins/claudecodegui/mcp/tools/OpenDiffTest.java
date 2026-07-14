package io.github.nbplugins.claudecodegui.mcp.tools;

import org.junit.jupiter.api.Test;
import org.openide.windows.TopComponent;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link OpenDiff}.
 */
class OpenDiffTest {

    @Test
    void diffTopComponentPersistenceTypeIsNever() {
        // Reproduces the bug where the diff tab's TopComponent defaulted to PERSISTENCE_ALWAYS,
        // causing the NetBeans window system to try to serialize it (and the non-serializable
        // async-handler state it closes over) into a .settings file on IDE exit — producing a
        // corrupted-settings "Warning" dialog with raw XML on the next startup.
        OpenDiff.DiffTopComponent tc = new OpenDiff.DiffTopComponent("Diff: test");
        assertEquals(TopComponent.PERSISTENCE_NEVER, tc.getPersistenceType());
    }
}
