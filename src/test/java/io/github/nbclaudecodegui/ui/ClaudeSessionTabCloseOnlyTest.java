package io.github.nbclaudecodegui.ui;

import io.github.nbclaudecodegui.model.SessionMode;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@link ClaudeSessionTab#onSaveAndSwitch} closes the tab when
 * {@link SessionMode#CLOSE_ONLY} is selected.
 */
class ClaudeSessionTabCloseOnlyTest {

    @Test
    void onSaveAndSwitch_closeOnly_closesTab() throws Exception {
        AtomicBoolean closed = new AtomicBoolean(false);

        ClaudeSessionTab tab = new ClaudeSessionTab() {
            @Override
            protected void doCloseTab() {
                closed.set(true);
            }
        };

        tab.onSaveAndSwitch("", SessionMode.CLOSE_ONLY, null);

        assertTrue(closed.get(), "Tab must be closed when CLOSE_ONLY mode is selected");
    }

    @Test
    void onSaveAndSwitch_newMode_doesNotCloseTab() throws Exception {
        AtomicBoolean closed = new AtomicBoolean(false);

        ClaudeSessionTab tab = new ClaudeSessionTab() {
            @Override
            protected void doCloseTab() {
                closed.set(true);
            }
        };

        tab.onSaveAndSwitch("", SessionMode.NEW, null);

        assertFalse(closed.get(), "Tab must not be closed when NEW mode is selected");
    }

    @Test
    void onSaveAndSwitch_restartAdvanced_doesNotCloseTab() throws Exception {
        AtomicBoolean closed = new AtomicBoolean(false);

        ClaudeSessionTab tab = new ClaudeSessionTab() {
            @Override
            protected void doCloseTab() {
                closed.set(true);
            }
        };

        tab.onSaveAndSwitch("", SessionMode.RESTART_ADVANCED, null);

        assertFalse(closed.get(), "Tab must not be closed when RESTART_ADVANCED mode is selected");
    }
}
