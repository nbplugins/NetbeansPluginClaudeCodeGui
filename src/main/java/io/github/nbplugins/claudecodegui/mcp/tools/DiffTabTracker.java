// Originally forked from https://github.com/emilianbold/claude-code-netbeans
// Original: src/main/java/org/openbeans/claude/netbeans/tools/DiffTabTracker.java
package io.github.nbplugins.claudecodegui.mcp.tools;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.openbeans.claude.netbeans.tools.AsyncHandler;
import org.openbeans.claude.netbeans.tools.params.OpenDiffResult;

/**
 * Tracks pending diff tabs and their async response handlers.
 * When a diff tab is closed or accepted, the corresponding handler is notified.
 */
public class DiffTabTracker {

    /** Private constructor — this class is a static utility and should not be instantiated. */
    private DiffTabTracker() {}

    private static final Logger LOGGER = Logger.getLogger(DiffTabTracker.class.getName());

    private static final ConcurrentHashMap<String, AsyncHandler> pendingDiffs = new ConcurrentHashMap<>();

    /** Futures for PreToolUse HTTP hook decisions (separate from MCP tool async handlers). */
    private static final ConcurrentHashMap<String, CompletableFuture<String>> pendingHookFutures = new ConcurrentHashMap<>();

    /**
     * Register a diff tab for async response handling.
     * @param tabName The name of the diff tab
     * @param handler Handler to call when the diff is accepted or rejected
     */
    public static void register(String tabName, AsyncHandler handler) {
        LOGGER.info("Registering async handler for diff tab: " + tabName);
        pendingDiffs.put(tabName, handler);
    }

    /**
     * Get and remove the handler for a diff tab.
     * @param tabName The name of the diff tab
     * @return The async handler, or null if not found
     */
    public static AsyncHandler remove(String tabName) {
        AsyncHandler handler = pendingDiffs.remove(tabName);
        if (handler != null) {
            LOGGER.info("Removed async handler for diff tab: " + tabName);
        }
        return handler;
    }

    /**
     * Resolves the pending async response for a diff tab with the given result.
     *
     * @param tabName the name of the diff tab
     * @param result  the result to send
     */
    public static void setResponse(String tabName, OpenDiffResult result) {
        AsyncHandler handler = pendingDiffs.getOrDefault(tabName, null);
        if (handler != null) {
            handler.sendResponse(result);
        } else {
            LOGGER.warning("No async handler found for diff tab: " + tabName);
        }
    }

    /**
     * Resolves the pending async response for a diff tab with a {@code FILE_REJECTED} status.
     *
     * <p>Call this when the user rejects the diff (Reject button or tab close).
     * If no handler is registered for the tab (e.g. already resolved), this is a no-op.
     *
     * @param tabName the name of the diff tab
     */
    public static void setRejected(String tabName) {
        AsyncHandler handler = pendingDiffs.remove(tabName);
        if (handler != null) {
            LOGGER.info("Diff rejected for tab: " + tabName);
            java.util.List<org.openbeans.claude.netbeans.tools.params.Content> contentList = new java.util.ArrayList<>();
            contentList.add(new org.openbeans.claude.netbeans.tools.params.Content("text", "FILE_REJECTED"));
            handler.sendResponse(new OpenDiffResult(contentList));
        } else {
            LOGGER.info("No pending handler for tab (already resolved or never registered): " + tabName);
        }
    }

    /**
     * Check if a tab name is tracked as a pending diff.
     * @param tabName The name of the tab
     * @return true if this is a tracked diff tab
     */
    public static boolean isTracked(String tabName) {
        return pendingDiffs.containsKey(tabName) || pendingHookFutures.containsKey(tabName);
    }

    // -------------------------------------------------------------------------
    // Hook future support (PreToolUse HTTP hook)
    // -------------------------------------------------------------------------

    /**
     * Registers a hook future for a diff tab opened in response to a PreToolUse hook.
     *
     * @param tabName the display name of the diff tab
     * @return the future that will be completed with {@code "allow"} or {@code "deny"}
     */
    public static CompletableFuture<String> registerHookFuture(String tabName) {
        LOGGER.info("Registering hook future for tab: " + tabName);
        CompletableFuture<String> future = new CompletableFuture<>();
        pendingHookFutures.put(tabName, future);
        return future;
    }

    /**
     * Resolves a hook future with the given JSON response string.
     *
     * @param tabName      the display name of the diff tab
     * @param jsonResponse complete hook response JSON (built by caller)
     */
    public static void resolveHook(String tabName, String jsonResponse) {
        CompletableFuture<String> future = pendingHookFutures.remove(tabName);
        if (future != null) {
            LOGGER.info("Hook resolved for tab: " + tabName);
            future.complete(jsonResponse);
        }
    }

    /**
     * Returns {@code true} if a hook future is pending for the given tab name.
     *
     * @param tabName the tab name to check
     * @return {@code true} if a pending hook future exists for the tab
     */
    public static boolean isHookTracked(String tabName) {
        return pendingHookFutures.containsKey(tabName);
    }
}
