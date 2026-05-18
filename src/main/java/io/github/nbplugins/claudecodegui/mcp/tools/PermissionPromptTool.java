// Originally forked from https://github.com/emilianbold/claude-code-netbeans
// Original: src/main/java/org/openbeans/claude/netbeans/tools/PermissionPromptTool.java
package io.github.nbplugins.claudecodegui.mcp.tools;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import io.github.nbplugins.claudecodegui.ui.FileDiffOpener;
import org.openbeans.claude.netbeans.tools.AsyncHandler;
import org.openbeans.claude.netbeans.tools.AsyncResponse;
import org.openbeans.claude.netbeans.tools.Tool;
import org.openbeans.claude.netbeans.tools.params.Content;

/**
 * MCP tool that intercepts Claude Code permission prompts for file-editing
 * tools (Edit, Write, MultiEdit) and shows the proposed change as a
 * NetBeans diff view before allowing or denying the operation.
 *
 * <p>This tool is registered as {@code permissionPromptTool} in the
 * {@code --mcp-config} file passed to the Claude CLI.  Claude calls it
 * instead of showing its built-in PTY permission dialog.
 *
 * <p>For non-file-editing tools the permission is granted automatically.
 *
 * <p>Return value: a single text content item whose text is either
 * {@code "allow"} or {@code "deny"}.
 */
public class PermissionPromptTool implements Tool<PermissionPromptTool.Params, AsyncResponse<List<Content>>> {

    /** Creates a new instance of this tool. */
    public PermissionPromptTool() {}

    private static final Logger LOGGER = Logger.getLogger(PermissionPromptTool.class.getName());

    // -----------------------------------------------------------------------
    // Parameter POJO
    // -----------------------------------------------------------------------

    /**
     * Parameters sent by Claude when it requests a permission decision.
     */
    public static final class Params {

        /** Default constructor for Jackson deserialization. */
        public Params() {}

        /** Name of the Claude tool requesting permission (e.g. {@code Edit}). */
        @JsonProperty("tool_name")
        private String toolName;

        /** Raw JSON arguments for the tool requesting permission. */
        @JsonProperty("tool_input")
        private JsonNode toolInput;

        /**
         * Returns the name of the tool requesting permission.
         *
         * @return tool name
         */
        public String getToolName() { return toolName; }

        /**
         * Returns the tool's input arguments as a JSON node.
         *
         * @return tool input as a JSON node
         */
        public JsonNode getToolInput() { return toolInput; }
    }

    // -----------------------------------------------------------------------
    // Tool interface
    // -----------------------------------------------------------------------

    @Override
    public String getName() {
        return "permission_prompt";
    }

    @Override
    public String getDescription() {
        return "Shows the proposed file change as a diff and asks the user to allow or deny it.";
    }

    @Override
    public Class<Params> getParameterClass() {
        return Params.class;
    }

    @Override
    public AsyncResponse<List<Content>> run(Params params) throws Exception {
        String toolName = params.getToolName();
        JsonNode toolInput = params.getToolInput();

        LOGGER.info("permission_prompt called for tool: " + toolName);

        // For non-file-editing tools, auto-allow.
        if (!isFileEditTool(toolName)) {
            LOGGER.info("Auto-allowing non-file-edit tool: " + toolName);
            return syncAllow();
        }

        // Compute before/after content
        final String filePath;
        final String before;
        final String after;

        try {
            filePath = getFilePath(toolInput);
            before   = computeBefore(toolName, toolInput, filePath);
            after    = computeAfter(toolName, toolInput, before);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Cannot compute diff for " + toolName + ": " + e.getMessage(), e);
            return syncDeny("Cannot compute diff: " + e.getMessage());
        }

        String tabName = "Diff: " + new File(filePath).getName();
        final String finalTabName = resolveUniqueTabName(tabName);

        return handler -> FileDiffOpener.open(filePath, before, after, finalTabName, null,
            () -> {
                LOGGER.info("Permission granted for tab: " + finalTabName);
                AsyncHandler<List<Content>> h = DiffTabTracker.remove(finalTabName);
                if (h != null) h.sendResponse(allowResult());
            },
            reason -> {
                // reason is not forwarded in the MCP protocol — tool returns just "deny"
                LOGGER.info("Permission denied for tab: " + finalTabName);
                DiffTabTracker.setRejected(finalTabName);
            },
            () -> {
                LOGGER.info("Permission cancelled for tab: " + finalTabName);
                DiffTabTracker.setRejected(finalTabName);
                FileDiffOpener.cancelCurrentPromptForFile(filePath);
            },
            () -> {
                LOGGER.info("Permission tab closed for tab: " + finalTabName);
                if (DiffTabTracker.isTracked(finalTabName)) {
                    DiffTabTracker.setRejected(finalTabName);
                }
            }
        );
    }

    // -----------------------------------------------------------------------
    // Before / after computation
    // -----------------------------------------------------------------------

    private static boolean isFileEditTool(String toolName) {
        return "Edit".equals(toolName) || "Write".equals(toolName) || "MultiEdit".equals(toolName);
    }

    private static String getFilePath(JsonNode toolInput) {
        JsonNode node = toolInput.get("file_path");
        if (node == null || node.isNull()) {
            throw new IllegalArgumentException("tool_input missing file_path");
        }
        return node.asText();
    }

    private static String computeBefore(String toolName, JsonNode toolInput, String filePath)
            throws IOException {
        File file = new File(filePath);
        if (!file.exists()) {
            // New file — before is empty
            return "";
        }
        return Files.readString(file.toPath(), StandardCharsets.UTF_8);
    }

    private static String computeAfter(String toolName, JsonNode toolInput, String before)
            throws Exception {
        return switch (toolName) {
            case "Edit"      -> applyEdit(toolInput, before);
            case "Write"     -> toolInput.has("content") ? toolInput.get("content").asText() : before;
            case "MultiEdit" -> applyMultiEdit(toolInput, before);
            default          -> before;
        };
    }

    private static String applyEdit(JsonNode toolInput, String before) {
        String oldStr = toolInput.has("old_string") ? toolInput.get("old_string").asText() : null;
        String newStr = toolInput.has("new_string") ? toolInput.get("new_string").asText() : "";

        if (oldStr == null || oldStr.isEmpty()) {
            return before + newStr;
        }
        int idx = before.indexOf(oldStr);
        if (idx < 0) {
            throw new IllegalArgumentException(
                    "old_string not found in file — cannot compute diff");
        }
        return before.substring(0, idx) + newStr + before.substring(idx + oldStr.length());
    }

    private static String applyMultiEdit(JsonNode toolInput, String before) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode edits = toolInput.get("edits");
        if (edits == null || !edits.isArray()) {
            return before;
        }
        String result = before;
        for (JsonNode edit : edits) {
            result = applyEdit(edit, result);
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Ensures the tab name is unique among open TopComponents. */
    private static String resolveUniqueTabName(String base) {
        String name = base;
        int suffix = 1;
        while (DiffTabTracker.isTracked(name)) {
            name = base + " (" + (++suffix) + ")";
        }
        return name;
    }

    private static AsyncResponse<List<Content>> syncAllow() {
        List<Content> r = allowResult();
        return h -> h.sendResponse(r);
    }

    private static AsyncResponse<List<Content>> syncDeny(String reason) {
        LOGGER.warning("Denying permission: " + reason);
        List<Content> r = denyResult();
        return h -> h.sendResponse(r);
    }

    private static List<Content> allowResult() {
        List<Content> list = new ArrayList<>();
        list.add(new Content("text", "allow"));
        return list;
    }

    private static List<Content> denyResult() {
        List<Content> list = new ArrayList<>();
        list.add(new Content("text", "deny"));
        return list;
    }
}
