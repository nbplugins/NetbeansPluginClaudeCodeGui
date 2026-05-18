package io.github.nbplugins.claudecodegui.model;

import java.time.Instant;

/**
 * A single entry in the persistent prompt history.
 */
public final class HistoryEntry extends PromptEntry {

    /** Timestamp when this prompt was submitted. */
    private Instant time;

    /**
     * Creates a history entry.
     *
     * @param text the prompt text
     * @param time the timestamp when the prompt was submitted
     */
    public HistoryEntry(String text, Instant time) {
        super(text);
        this.time = time;
    }

    /**
     * Returns the timestamp when this prompt was submitted.
     *
     * @return submission timestamp
     */
    public Instant getTime() { return time; }
}
