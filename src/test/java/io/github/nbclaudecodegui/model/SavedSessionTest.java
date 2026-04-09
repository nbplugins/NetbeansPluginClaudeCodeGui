package io.github.nbclaudecodegui.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class SavedSessionTest {

    @Test
    void displayName_returnsCustomTitleWhenSet() {
        SavedSession s = new SavedSession("id", Instant.now(), Instant.now(),
                "auto-slug", "My Custom Title", null);
        assertEquals("My Custom Title", s.displayName());
    }

    @Test
    void displayName_returnsSlugWhenNoCustomTitle() {
        SavedSession s = new SavedSession("id", Instant.now(), Instant.now(),
                "auto-slug", null, null);
        assertEquals("auto-slug", s.displayName());
    }

    @Test
    void displayName_returnsSlugWhenCustomTitleIsBlank() {
        SavedSession s = new SavedSession("id", Instant.now(), Instant.now(),
                "auto-slug", "   ", null);
        assertEquals("auto-slug", s.displayName());
    }

    @Test
    void displayName_returnsSessionIdWhenSlugIsNull() {
        SavedSession s = new SavedSession("my-session-id", Instant.now(), Instant.now(),
                null, null, null);
        assertEquals("my-session-id", s.displayName());
    }

    @Test
    void displayName_returnsSessionIdWhenBothNull() {
        SavedSession s = new SavedSession("fallback-id", Instant.now(), Instant.now(),
                null, null, null);
        assertEquals("fallback-id", s.displayName());
    }
}
