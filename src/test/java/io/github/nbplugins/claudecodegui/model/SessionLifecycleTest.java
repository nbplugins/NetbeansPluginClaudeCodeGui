package io.github.nbplugins.claudecodegui.model;

import io.github.nbplugins.claudecodegui.model.SessionLifecycle;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the {@link SessionLifecycle} enum.
 *
 * <p>Tests verify that the enum has the expected states and that state
 * properties (ready vs. working) are correctly identified.
 */
class SessionLifecycleTest {

    @Test
    void enumHasExpectedStates() {
        SessionLifecycle[] values = SessionLifecycle.values();
        assertEquals(3, values.length, "SessionLifecycle must have exactly 3 states");
        assertEquals(SessionLifecycle.STARTING, values[0]);
        assertEquals(SessionLifecycle.READY,    values[1]);
        assertEquals(SessionLifecycle.WORKING,  values[2]);
    }

    @Test
    void startingIsNotReadyOrWorking() {
        SessionLifecycle s = SessionLifecycle.STARTING;
        assertNotEquals(SessionLifecycle.READY,   s);
        assertNotEquals(SessionLifecycle.WORKING, s);
    }

    @Test
    void readyIsDistinctFromOthers() {
        SessionLifecycle s = SessionLifecycle.READY;
        assertNotEquals(SessionLifecycle.STARTING, s);
        assertNotEquals(SessionLifecycle.WORKING,  s);
    }

    @Test
    void workingIsDistinctFromOthers() {
        SessionLifecycle s = SessionLifecycle.WORKING;
        assertNotEquals(SessionLifecycle.STARTING, s);
        assertNotEquals(SessionLifecycle.READY,    s);
    }

    @Test
    void valueOfByName() {
        assertEquals(SessionLifecycle.STARTING, SessionLifecycle.valueOf("STARTING"));
        assertEquals(SessionLifecycle.READY,    SessionLifecycle.valueOf("READY"));
        assertEquals(SessionLifecycle.WORKING,  SessionLifecycle.valueOf("WORKING"));
    }
}
