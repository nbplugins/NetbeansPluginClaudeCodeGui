package io.github.nbplugins.claudecodegui.ui;

import io.github.nbplugins.claudecodegui.model.SavedSession;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that the Save &amp; Switch dialog never pre-fills the name field with
 * the raw session ID (previously {@code displayName()} did, so accepting the
 * prefill stored a UUID as the session name).
 */
class ClaudeSessionTabPrefillNameTest {

    private static final Instant TS = Instant.parse("2026-10-31T15:48:30Z");

    private static SavedSession session(String slug, String title) {
        return new SavedSession("7641f6b2-e7a0-4370-8087-358d762b7b7c", TS, TS, slug, title, null);
    }

    @Test
    void customTitleWins() {
        assertEquals("My Name", ClaudeSessionTab.prefillSessionName(session("some-slug", "My Name")));
    }

    @Test
    void slugUsedWhenNoTitle() {
        assertEquals("sunny-zooming-thunder",
                ClaudeSessionTab.prefillSessionName(session("sunny-zooming-thunder", null)));
    }

    @Test
    void emptyWhenNoTitleAndNoSlug_neverSessionId() {
        assertEquals("", ClaudeSessionTab.prefillSessionName(session(null, null)),
                "a brand-new session with no slug must not pre-fill its UUID");
    }

    @Test
    void emptyWhenBlankTitleAndNoSlug() {
        assertEquals("", ClaudeSessionTab.prefillSessionName(session(null, "   ")));
    }

    @Test
    void titleEqualToSessionIdIgnored() {
        String id = "7641f6b2-e7a0-4370-8087-358d762b7b7c";
        assertEquals("", ClaudeSessionTab.prefillSessionName(session(null, id)),
                "a custom title equal to the session ID is junk left by the old bug");
    }

    @Test
    void nullSessionGivesEmptyString() {
        assertEquals("", ClaudeSessionTab.prefillSessionName(null));
    }
}
