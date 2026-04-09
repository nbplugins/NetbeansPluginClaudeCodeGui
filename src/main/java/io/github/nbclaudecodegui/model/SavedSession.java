package io.github.nbclaudecodegui.model;

import java.time.Instant;

/**
 * Represents a saved Claude Code session found in the JSONL session store.
 *
 * @param sessionId   unique identifier (filename without .jsonl extension)
 * @param createdAt   timestamp of the first message in the session
 * @param lastAt      timestamp of the most recent message in the session
 * @param slug        auto-generated session name (always present if file has messages)
 * @param customTitle user-set title, or {@code null} if not set
 * @param firstPrompt text of the first user message, truncated to 120 chars, or {@code null}
 */
public record SavedSession(
        String sessionId,
        Instant createdAt,
        Instant lastAt,
        String slug,
        String customTitle,
        String firstPrompt
) {

    /**
     * Returns the display name: {@code customTitle} if set, otherwise {@code slug},
     * otherwise {@code sessionId}.
     *
     * @return non-null display name
     */
    public String displayName() {
        if (customTitle != null && !customTitle.isBlank()) return customTitle;
        if (slug != null && !slug.isBlank()) return slug;
        return sessionId;
    }
}
