package io.github.nbplugins.claudecodegui.ui;

import io.github.nbplugins.claudecodegui.model.SessionMode;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that tearing down the chat invalidates a launch that is still being
 * prepared in the background.
 *
 * <p>{@code startProcessAsync} prepares the launch identity off the EDT and then
 * starts the process on the EDT, guarded by {@code startRequestSeq}. Any action
 * that tears the chat down — including "Start New Session", which stops and
 * clears the layout but may not schedule a replacement start yet — must bump
 * that sequence, otherwise the process would be attached to the detached widget.
 */
class ClaudeSessionTabLaunchCancelTest {

    @Test
    void saveAndSwitchToNewSession_invalidatesAPendingLaunch() throws Exception {
        ClaudeSessionTab tab = new ClaudeSessionTab();
        long before = startRequestSeq(tab);

        // No working directory is known during a launch, so this path only tears
        // the chat down and does not schedule a replacement start.
        tab.onSaveAndSwitch("", SessionMode.NEW, null);

        assertTrue(startRequestSeq(tab) > before,
                "tearing down the chat must cancel a launch still being prepared");
    }

    private static long startRequestSeq(ClaudeSessionTab tab) throws Exception {
        Field f = ClaudeSessionTab.class.getDeclaredField("startRequestSeq");
        f.setAccessible(true);
        return f.getLong(tab);
    }
}
