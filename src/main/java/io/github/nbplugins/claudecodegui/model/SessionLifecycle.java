package io.github.nbplugins.claudecodegui.model;

/**
 * Lifecycle state of a Claude Code session.
 *
 * <p>Drives the enable/disable state of the Send and Cancel buttons and the
 * content of the state label in the status bar.
 *
 * <p>State machine:
 * <pre>
 *   showChatUI()                          STARTING
 *   detectInputPromptReady() = true   →   READY    (one-shot, from STARTING only)
 *   sendPrompt() / discoverModels() /
 *   sendShiftTabsUntilMode() start    →   WORKING
 *   onClaudeIdle()                    →   READY
 * </pre>
 *
 * <p>All transitions go through {@link ClaudeSessionModel#setLifecycle}, which
 * dispatches listeners on the EDT.
 */
public enum SessionLifecycle {

    /**
     * Process launched; waiting for the first {@code ❯} prompt to appear on screen.
     * Send is disabled; the screen-content detector drives the STARTING → READY transition.
     */
    STARTING,

    /**
     * Claude is idle and waiting for user input.
     * Send is enabled; the model combo is enabled when populated.
     */
    READY,

    /**
     * Claude is executing (prompt sent, model discovery, or edit-mode switch in progress).
     * Send is disabled; Cancel is enabled.
     */
    WORKING
}
