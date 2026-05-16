package io.github.nbclaudecodegui.settings;

/**
 * Controls when the inline Markdown preview is shown inside the file diff panel.
 */
public enum MdPreviewInDiffMode {
    /** Always show the inline preview for .md files in the diff panel. */
    ALWAYS,
    /** Never show the inline preview. */
    NEVER,
    /**
     * Show the inline preview, except when a live Plan Preview tab is already
     * open for a file in the {@code plans/} directory — in that case the
     * dedicated preview tab makes the inline one redundant.
     */
    EXCEPT_PLAN
}
