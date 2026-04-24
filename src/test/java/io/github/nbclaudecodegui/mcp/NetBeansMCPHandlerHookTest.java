package io.github.nbclaudecodegui.mcp;

import io.github.nbclaudecodegui.model.ClaudeSessionModel;
import io.github.nbclaudecodegui.model.EditMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link NetBeansMCPHandler#handlePreToolUse} auto-allow logic.
 *
 * <p>Verifies the fast-path decisions made before opening a diff dialog:
 * <ul>
 *   <li>{@link EditMode#BYPASS_PERMISSIONS} — auto-allows everything</li>
 *   <li>{@link EditMode#ACCEPT_EDITS} — auto-allows only files inside {@code cwd}</li>
 *   <li>Other modes — fall through (in test: future never completes via dialog)</li>
 * </ul>
 *
 * <p>The handler under test is constructed via {@link #handler}, which wraps the
 * real {@link NetBeansMCPHandler} but throws if a diff dialog would be opened
 * (i.e. we never expect the slow path in these tests).
 */
class NetBeansMCPHandlerHookTest {

    private static final String CWD       = "/tmp/test-project";
    private static final String FILE_INSIDE  = "/tmp/test-project/src/Foo.java";
    private static final String FILE_OUTSIDE = "/tmp/other-project/Bar.java";

    private NetBeansMCPHandler handler;

    @BeforeEach
    void setUp() {
        ClaudeSessionModel.EDIT_MODE_REGISTRY.clear();
        handler = new NetBeansMCPHandler();
    }

    @AfterEach
    void tearDown() {
        ClaudeSessionModel.EDIT_MODE_REGISTRY.clear();
    }

    // -------------------------------------------------------------------------
    // bypassPermissions — auto-allow regardless of file location
    // -------------------------------------------------------------------------

    @Test
    void bypassPermissionsAutoAllowsFileInsideProject() throws Exception {
        ClaudeSessionModel.EDIT_MODE_REGISTRY.put(CWD, EditMode.BYPASS_PERMISSIONS);

        CompletableFuture<String> future = handler.handlePreToolUse(editJson(FILE_INSIDE));

        // Future must complete immediately (no dialog)
        assertTrue(future.isDone(), "Future must be completed immediately in bypassPermissions mode");
        String result = future.get(1, TimeUnit.SECONDS);
        assertTrue(result.contains("\"allow\""), "Response must be allow, got: " + result);
    }

    @Test
    void bypassPermissionsAutoAllowsFileOutsideProject() throws Exception {
        ClaudeSessionModel.EDIT_MODE_REGISTRY.put(CWD, EditMode.BYPASS_PERMISSIONS);

        CompletableFuture<String> future = handler.handlePreToolUse(editJson(FILE_OUTSIDE));

        assertTrue(future.isDone(), "Future must be completed immediately in bypassPermissions mode");
        String result = future.get(1, TimeUnit.SECONDS);
        assertTrue(result.contains("\"allow\""),
                "bypassPermissions must allow files outside project, got: " + result);
    }

    // -------------------------------------------------------------------------
    // acceptEdits — auto-allow only inside cwd
    // -------------------------------------------------------------------------

    @Test
    void acceptEditsAutoAllowsFileInsideProject() throws Exception {
        ClaudeSessionModel.EDIT_MODE_REGISTRY.put(CWD, EditMode.ACCEPT_EDITS);

        CompletableFuture<String> future = handler.handlePreToolUse(editJson(FILE_INSIDE));

        assertTrue(future.isDone(), "Future must be completed immediately for file inside cwd");
        String result = future.get(1, TimeUnit.SECONDS);
        assertTrue(result.contains("\"allow\""), "Response must be allow, got: " + result);
    }

    @Test
    void acceptEditsDoesNotAutoAllowFileOutsideProject() throws Exception {
        ClaudeSessionModel.EDIT_MODE_REGISTRY.put(CWD, EditMode.ACCEPT_EDITS);

        CompletableFuture<String> future = handler.handlePreToolUse(editJson(FILE_OUTSIDE));

        // In headless tests FileDiffOpener.open() cannot open a tab → exception caught →
        // returns hookAskJson(). Either way the response must NOT be "allow".
        assertTrue(future.isDone());
        String result = future.get(1, TimeUnit.SECONDS);
        assertFalse(result.contains("\"allow\""),
                "acceptEdits must not auto-allow files outside project, got: " + result);
    }

    // -------------------------------------------------------------------------
    // No registry entry → fall through to diff dialog
    // -------------------------------------------------------------------------

    @Test
    void noRegistryEntryDoesNotAutoAllow() throws Exception {
        // No entry for CWD — default mode: must not auto-allow
        CompletableFuture<String> future = handler.handlePreToolUse(editJson(FILE_INSIDE));

        // In headless tests FileDiffOpener.open() cannot open a tab → exception caught →
        // returns hookAskJson(). Either way the response must NOT be "allow".
        assertTrue(future.isDone());
        String result = future.get(1, TimeUnit.SECONDS);
        assertFalse(result.contains("\"allow\""),
                "Without a registry entry the handler must not auto-allow, got: " + result);
    }

    // -------------------------------------------------------------------------
    // Non-file-edit tools are always auto-allowed
    // -------------------------------------------------------------------------

    @Test
    void nonFileEditToolAutoAllowed() throws Exception {
        String json = "{\"tool_name\":\"Bash\",\"tool_input\":{\"command\":\"ls\"},\"cwd\":\"" + CWD + "\"}";

        CompletableFuture<String> future = handler.handlePreToolUse(json);

        assertTrue(future.isDone(), "Non-file-edit tool must be auto-allowed immediately");
        assertTrue(future.get(1, TimeUnit.SECONDS).contains("\"allow\""));
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private static String editJson(String filePath) {
        return "{\"tool_name\":\"Edit\","
                + "\"tool_input\":{\"file_path\":\"" + filePath + "\","
                + "\"old_string\":\"x\",\"new_string\":\"y\"},"
                + "\"cwd\":\"" + CWD + "\"}";
    }
}
