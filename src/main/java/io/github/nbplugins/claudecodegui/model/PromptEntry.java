package io.github.nbplugins.claudecodegui.model;

/**
 * Base class for a saved or historical prompt entry.
 */
public abstract class PromptEntry {

    /** The prompt text. */
    private String text;

    /**
     * Creates a prompt entry with the given text.
     *
     * @param text the prompt text
     */
    protected PromptEntry(String text) {
        this.text = text;
    }

    /**
     * Returns the prompt text.
     *
     * @return prompt text
     */
    public String getText() { return text; }

    /**
     * Sets the prompt text.
     *
     * @param text new prompt text
     */
    public void setText(String text) { this.text = text; }
}
