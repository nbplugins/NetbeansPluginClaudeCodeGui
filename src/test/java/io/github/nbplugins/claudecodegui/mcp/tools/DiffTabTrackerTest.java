package io.github.nbplugins.claudecodegui.mcp.tools;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openbeans.claude.netbeans.tools.AsyncHandler;
import org.openbeans.claude.netbeans.tools.params.OpenDiffResult;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DiffTabTracker}.
 */
public class DiffTabTrackerTest {

    private static final String TAB = "test-diff-tab";
    private static final String ALLOW_JSON =
            "{\"hookSpecificOutput\":{\"hookEventName\":\"PreToolUse\",\"permissionDecision\":\"allow\"}}";
    private static final String DENY_JSON =
            "{\"hookSpecificOutput\":{\"hookEventName\":\"PreToolUse\",\"permissionDecision\":\"deny\"}}";
    private static final String ASK_JSON =
            "{\"hookSpecificOutput\":{\"hookEventName\":\"PreToolUse\",\"permissionDecision\":\"ask\"}}";

    @AfterEach
    public void cleanup() {
        // Remove any leftover handler after each test
        DiffTabTracker.remove(TAB);
        DiffTabTracker.resolveHook(TAB, ASK_JSON); // no-op if not registered
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testSetResponse_deliversResult() {
        AtomicReference<OpenDiffResult> received = new AtomicReference<>();
        DiffTabTracker.register(TAB, (AsyncHandler<OpenDiffResult>) received::set);

        var result = buildResult("FILE_SAVED");
        DiffTabTracker.setResponse(TAB, result);

        assertNotNull(received.get());
        assertEquals("FILE_SAVED", received.get().getContent().get(0).getText());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testSetRejected_deliversFileRejected() {
        AtomicReference<OpenDiffResult> received = new AtomicReference<>();
        DiffTabTracker.register(TAB, (AsyncHandler<OpenDiffResult>) received::set);

        DiffTabTracker.setRejected(TAB);

        assertNotNull(received.get(), "Handler should have been called");
        assertEquals("FILE_REJECTED", received.get().getContent().get(0).getText());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testSetRejected_removesHandler() {
        AtomicReference<OpenDiffResult> received = new AtomicReference<>();
        DiffTabTracker.register(TAB, (AsyncHandler<OpenDiffResult>) received::set);

        DiffTabTracker.setRejected(TAB);
        // Calling setRejected a second time must not deliver another result
        received.set(null);
        DiffTabTracker.setRejected(TAB);

        assertNull(received.get(), "Handler should not be called after it was removed");
    }

    @Test
    public void testSetRejected_noHandler_isNoop() {
        // Must not throw when no handler is registered
        assertDoesNotThrow(() -> DiffTabTracker.setRejected("unknown-tab"));
    }

    @Test
    public void testIsTracked() {
        assertFalse(DiffTabTracker.isTracked(TAB));
        DiffTabTracker.register(TAB, (AsyncHandler<OpenDiffResult>) r -> {});
        assertTrue(DiffTabTracker.isTracked(TAB));
    }

    @Test
    public void testRemove_returnsAndUnregistersHandler() {
        DiffTabTracker.register(TAB, (AsyncHandler<OpenDiffResult>) r -> {});
        assertNotNull(DiffTabTracker.remove(TAB));
        assertFalse(DiffTabTracker.isTracked(TAB));
    }

    // ------------------------------------------------------------------
    // Hook future tests
    // ------------------------------------------------------------------

    @Test
    public void testRegisterHookFuture_isHookTracked() {
        assertFalse(DiffTabTracker.isHookTracked(TAB));
        CompletableFuture<String> f = DiffTabTracker.registerHookFuture(TAB);
        assertNotNull(f);
        assertTrue(DiffTabTracker.isHookTracked(TAB));
        assertFalse(f.isDone());
    }

    @Test
    public void testResolveHook_allow_completesFuture() {
        CompletableFuture<String> f = DiffTabTracker.registerHookFuture(TAB);
        DiffTabTracker.resolveHook(TAB, ALLOW_JSON);
        assertTrue(f.isDone());
        assertEquals(ALLOW_JSON, f.getNow(null));
        assertFalse(DiffTabTracker.isHookTracked(TAB));
    }

    @Test
    public void testResolveHook_deny_completesFuture() {
        CompletableFuture<String> f = DiffTabTracker.registerHookFuture(TAB);
        DiffTabTracker.resolveHook(TAB, DENY_JSON);
        assertTrue(f.isDone());
        assertEquals(DENY_JSON, f.getNow(null));
        assertFalse(DiffTabTracker.isHookTracked(TAB));
    }

    @Test
    public void testResolveHook_ask_completesFuture() {
        CompletableFuture<String> f = DiffTabTracker.registerHookFuture(TAB);
        DiffTabTracker.resolveHook(TAB, ASK_JSON);
        assertEquals(ASK_JSON, f.getNow(null));
    }

    @Test
    public void testResolveHook_noRegistration_isNoop() {
        assertDoesNotThrow(() -> DiffTabTracker.resolveHook("unknown-hook-tab", ALLOW_JSON));
    }

    @Test
    public void testResolveHook_idempotent() {
        CompletableFuture<String> f = DiffTabTracker.registerHookFuture(TAB);
        DiffTabTracker.resolveHook(TAB, ALLOW_JSON);
        DiffTabTracker.resolveHook(TAB, ALLOW_JSON); // second call must be a no-op
        assertEquals(ALLOW_JSON, f.getNow(null));
    }

    @Test
    public void testIsTracked_includesHookFutures() {
        assertFalse(DiffTabTracker.isTracked(TAB));
        DiffTabTracker.registerHookFuture(TAB);
        assertTrue(DiffTabTracker.isTracked(TAB));
        DiffTabTracker.resolveHook(TAB, ASK_JSON);
        assertFalse(DiffTabTracker.isTracked(TAB));
    }

    // ------------------------------------------------------------------

    private static OpenDiffResult buildResult(String text) {
        var content = new org.openbeans.claude.netbeans.tools.params.Content("text", text);
        return new OpenDiffResult(java.util.List.of(content));
    }
}
