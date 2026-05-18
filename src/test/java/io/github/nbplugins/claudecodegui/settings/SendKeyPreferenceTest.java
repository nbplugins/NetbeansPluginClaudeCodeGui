package io.github.nbplugins.claudecodegui.settings;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for send-key and newline-key preferences.
 */
class SendKeyPreferenceTest {

    @AfterEach
    void resetPrefs() {
        ClaudeCodePreferences.setSendKey(ClaudeCodePreferences.DEFAULT_SEND_KEY);
        ClaudeCodePreferences.setNewlineKey(ClaudeCodePreferences.DEFAULT_NEWLINE_KEY);
    }

    @Test
    void testDefaultSendKeyIsCtrlEnter() {
        ClaudeCodePreferences.setSendKey(ClaudeCodePreferences.DEFAULT_SEND_KEY);
        assertEquals(ClaudeCodePreferences.CTRL_ENTER,
                ClaudeCodePreferences.getSendKey());
    }

    @Test
    void testDefaultNewlineKeyIsEnter() {
        ClaudeCodePreferences.setNewlineKey(ClaudeCodePreferences.DEFAULT_NEWLINE_KEY);
        assertEquals(ClaudeCodePreferences.ENTER,
                ClaudeCodePreferences.getNewlineKey());
    }

    @Test
    void testSetAndGetSendKey() {
        ClaudeCodePreferences.setSendKey(ClaudeCodePreferences.ENTER);
        assertEquals(ClaudeCodePreferences.ENTER,
                ClaudeCodePreferences.getSendKey());

        ClaudeCodePreferences.setSendKey(ClaudeCodePreferences.SHIFT_ENTER);
        assertEquals(ClaudeCodePreferences.SHIFT_ENTER,
                ClaudeCodePreferences.getSendKey());
    }

    @Test
    void testSetAndGetNewlineKey() {
        ClaudeCodePreferences.setNewlineKey(ClaudeCodePreferences.CTRL_ENTER);
        assertEquals(ClaudeCodePreferences.CTRL_ENTER,
                ClaudeCodePreferences.getNewlineKey());

        ClaudeCodePreferences.setNewlineKey(ClaudeCodePreferences.ALT_ENTER);
        assertEquals(ClaudeCodePreferences.ALT_ENTER,
                ClaudeCodePreferences.getNewlineKey());
    }

    @Test
    void testInvalidSendKeyFallsBackToDefault() {
        ClaudeCodePreferences.setSendKey("INVALID");
        assertEquals(ClaudeCodePreferences.DEFAULT_SEND_KEY,
                ClaudeCodePreferences.getSendKey());
    }

    @Test
    void testInvalidNewlineKeyFallsBackToDefault() {
        ClaudeCodePreferences.setNewlineKey("BOGUS");
        assertEquals(ClaudeCodePreferences.DEFAULT_NEWLINE_KEY,
                ClaudeCodePreferences.getNewlineKey());
    }

    @Test
    void testSendKeyAndNewlineKeyAreIndependent() {
        ClaudeCodePreferences.setSendKey(ClaudeCodePreferences.SHIFT_ENTER);
        ClaudeCodePreferences.setNewlineKey(ClaudeCodePreferences.ALT_ENTER);

        assertEquals(ClaudeCodePreferences.SHIFT_ENTER,
                ClaudeCodePreferences.getSendKey());
        assertEquals(ClaudeCodePreferences.ALT_ENTER,
                ClaudeCodePreferences.getNewlineKey());
    }

    @Test
    void testAllValidKeyCombinations() {
        String[] values = {
            ClaudeCodePreferences.ENTER,
            ClaudeCodePreferences.SHIFT_ENTER,
            ClaudeCodePreferences.CTRL_ENTER,
            ClaudeCodePreferences.ALT_ENTER
        };
        for (String v : values) {
            ClaudeCodePreferences.setSendKey(v);
            assertEquals(v, ClaudeCodePreferences.getSendKey(),
                    "sendKey should accept: " + v);
            ClaudeCodePreferences.setNewlineKey(v);
            assertEquals(v, ClaudeCodePreferences.getNewlineKey(),
                    "newlineKey should accept: " + v);
        }
    }
}
