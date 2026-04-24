// Originally forked from https://github.com/emilianbold/claude-code-netbeans
// Original: src/main/java/org/openbeans/claude/netbeans/NetBeansMCPHandler.java
package io.github.nbclaudecodegui.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.nbclaudecodegui.model.EditMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ProjectUtils;
import org.openide.cookies.EditorCookie;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.loaders.DataObject;
import org.openide.nodes.Node;
import org.openide.windows.TopComponent;

import javax.swing.text.Document;
import javax.swing.text.JTextComponent;
import javax.swing.text.StyledDocument;
import javax.swing.event.CaretEvent;
import javax.swing.event.CaretListener;
import org.openide.text.NbDocument;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.netbeans.api.diff.Diff;
import org.netbeans.api.diff.DiffView;
import org.netbeans.api.diff.Difference;
import org.netbeans.api.diff.StreamSource;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import javax.swing.SwingUtilities;
import org.openide.windows.WindowManager;
import io.github.nbclaudecodegui.ui.ClaudeSessionTab;
import io.github.nbclaudecodegui.ui.FileDiffOpener;
import io.github.nbclaudecodegui.mcp.tools.DiffTabTracker;
import io.github.nbclaudecodegui.mcp.tools.GetDiagnostics;
import io.github.nbclaudecodegui.mcp.tools.GetOpenEditors;
import io.github.nbclaudecodegui.mcp.tools.OpenDiff;
import io.github.nbclaudecodegui.mcp.tools.OpenFile;
import io.github.nbclaudecodegui.mcp.tools.PermissionPromptTool;
import org.netbeans.api.project.ui.OpenProjects;
import org.openide.util.Lookup;
import org.openbeans.claude.netbeans.tools.AsyncHandler;
import org.openbeans.claude.netbeans.tools.AsyncResponse;
import org.openbeans.claude.netbeans.tools.CheckDocumentDirty;
import org.openbeans.claude.netbeans.tools.CloseAllDiffTabs;
import org.openbeans.claude.netbeans.tools.CloseTab;
import org.openbeans.claude.netbeans.tools.GetCurrentSelection;
import org.openbeans.claude.netbeans.tools.GetWorkspaceFolders;
import org.openbeans.claude.netbeans.tools.SaveDocument;
import org.openbeans.claude.netbeans.tools.params.Content;
import org.openbeans.claude.netbeans.tools.params.OpenDiffResult;

/**
 * Handles Model Context Protocol messages and provides NetBeans IDE capabilities
 * to Claude Code through MCP primitives (Tools, Resources, Prompts).
 */
public class NetBeansMCPHandler {


    private static final Logger LOGGER = Logger.getLogger(NetBeansMCPHandler.class.getName());
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MCPResponseBuilder responseBuilder;
    private volatile Consumer<String> broadcaster;

    // Selection tracking
    private final Map<JTextComponent, CaretListener> selectionListeners = new WeakHashMap<>();
    private PropertyChangeListener topComponentListener;
    private PropertyChangeListener diffTabListener;
    private JTextComponent currentTextComponent;

    private final CheckDocumentDirty checkDocumentDirtyTool;
    private final CloseAllDiffTabs closeAllDiffTabsTool;
    private final CloseTab closeTabTool;
    private final GetCurrentSelection getCurrentSelectionTool;
    private final GetDiagnostics getDiagnosticsTool;
    private final GetOpenEditors getOpenEditorsTool;
    private final GetWorkspaceFolders getWorkspaceFoldersTool;
    private final OpenDiff openDiffTool;
    private final OpenFile openFileTool;
    private final PermissionPromptTool permissionPromptTool;
    private final SaveDocument saveDocument;

    /** Creates a new handler and initializes all MCP tool instances. */
    public NetBeansMCPHandler() {
        this.responseBuilder = new MCPResponseBuilder(objectMapper);
        this.checkDocumentDirtyTool = new CheckDocumentDirty();
        this.closeAllDiffTabsTool = new CloseAllDiffTabs();
        this.closeTabTool = new CloseTab();
        this.getCurrentSelectionTool = new GetCurrentSelection();
        this.getDiagnosticsTool = new GetDiagnostics();
        this.getOpenEditorsTool = new GetOpenEditors();
        this.getWorkspaceFoldersTool = new GetWorkspaceFolders();
        this.openDiffTool = new OpenDiff();
        this.openFileTool = new OpenFile();
        this.permissionPromptTool = new PermissionPromptTool();
        this.saveDocument = new SaveDocument();
    }

    /**
     * Handles incoming MCP messages and routes them to appropriate handlers.
     *
     * @param message      the JSON-RPC message
     * @param sessionQueue the per-session queue for sending async responses back
     * @return response JSON string, or null if no response needed
     */
    public String handleMessage(JsonNode message, BlockingQueue<String> sessionQueue) {
        try {
            String method = message.get("method").asText();
            JsonNode params = message.get("params");
            Integer id = message.has("id") ? message.get("id").asInt() : null;

            LOGGER.log(Level.FINE, "Processing MCP method: {0}", method);

            ObjectNode response = responseBuilder.objectNode();
            response.put("jsonrpc", "2.0");
            if (id != null) {
                response.put("id", id);
            }

            switch (method) {
                case "initialize":
                    response.set("result", handleInitialize(params));
                    // Send the response first
                    String initResponse = objectMapper.writeValueAsString(response);
                    // Then send notifications/initialized notification
                    sendInitializedNotification();
                    return initResponse;

                case "tools/list":
                    response.set("result", handleToolsList());
                    break;

                case "tools/call":
                    JsonNode toolResult = handleToolsCall(params, id, sessionQueue);
                    if (toolResult == null) {
                        // Async tool - no immediate response
                        return null;
                    }
                    response.set("result", toolResult);
                    break;

                case "resources/list":
                    response.set("result", handleResourcesList());
                    break;

                case "resources/read":
                    response.set("result", handleResourcesRead(params));
                    break;

                case "prompts/list":
                    response.set("result", handlePromptsList());
                    break;

                default:
                    LOGGER.log(Level.WARNING, "Unknown MCP method: {0}", method);
                    return responseBuilder.createErrorResponse(id, -32601, "Method not found", method);
            }

            return objectMapper.writeValueAsString(response);

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error handling MCP message", e);
            return responseBuilder.createErrorResponse(null, -32603, "Internal error", e.getMessage());
        }
    }

    /**
     * Handles MCP initialize request.
     */
    private JsonNode handleInitialize(JsonNode params) {
        ObjectNode result = responseBuilder.objectNode();
        result.put("protocolVersion", "2024-11-05");

        ObjectNode capabilities = responseBuilder.objectNode();

        ObjectNode toolsCapability = responseBuilder.objectNode();
        toolsCapability.put("listChanged", true);
        capabilities.set("tools", toolsCapability);

        ObjectNode resourcesCapability = responseBuilder.objectNode();
        resourcesCapability.put("subscribe", true);
        resourcesCapability.put("listChanged", true);
        capabilities.set("resources", resourcesCapability);

        ObjectNode promptsCapability = responseBuilder.objectNode();
        promptsCapability.put("listChanged", true);
        capabilities.set("prompts", promptsCapability);

        result.set("capabilities", capabilities);

        ObjectNode serverInfo = responseBuilder.objectNode();
        serverInfo.put("name", "netbeans-mcp-server");
        serverInfo.put("version", "1.0.0");
        result.set("serverInfo", serverInfo);

        return result;
    }

    /**
     * Sends the notifications/initialized notification after successful initialization.
     */
    private void sendInitializedNotification() {
        try {
            ObjectNode notification = responseBuilder.createNotification(
                "notifications/initialized", null
            );
            sendViaSse(objectMapper.writeValueAsString(notification));
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to send initialized notification", e);
        }
    }

    /**
     * Lists available tools (executable functions).
     */
    private JsonNode handleToolsList() {
        ArrayNode tools = responseBuilder.arrayNode();

        // Core Claude Code tools - names/descriptions in code, schemas from JSON
        tools.add(createToolDefinition("openFile", "Opens a file in the editor", "OpenFileParams"));
        tools.add(createToolDefinition("getWorkspaceFolders", "Get list of workspace folders (open projects)", "getWorkspaceFolders"));
        tools.add(createToolDefinition("getOpenEditors", "Get list of currently open editor tabs", "getOpenEditors"));
        tools.add(createToolDefinition("getCurrentSelection", "Get the current text selection in the active editor", "getCurrentSelection"));
        tools.add(createToolDefinition("close_tab", "Close an open editor tab", "CloseTabParams"));
        tools.add(createToolDefinition("getDiagnostics", "Get diagnostic information (errors, warnings) for files", "GetDiagnosticsParams"));
        tools.add(createToolDefinition("checkDocumentDirty", "Check if a document has unsaved changes", "CheckDocumentDirtyParams"));
        tools.add(createToolDefinition("saveDocument", "Save a document to disk", "SaveDocumentParams"));
        tools.add(createToolDefinition("closeAllDiffTabs", "Close all diff viewer tabs", "CloseAllDiffTabsParams"));
        tools.add(createToolDefinition("openDiff", "Open a git diff for the file", "OpenDiffParams"));
        tools.add(createToolDefinition("permission_prompt",
                "Shows proposed file changes as a diff and asks the user to allow or deny the operation.",
                "permission_prompt"));

        ObjectNode result = responseBuilder.objectNode();
        result.set("tools", tools);
        return result;
    }

    /**
     * Handles tool call requests.
     * @param params       Tool call parameters
     * @param requestId    Request ID for async response handling
     * @param sessionQueue Per-session queue for routing async responses
     * @return JsonNode result for sync tools, null for async tools
     */
    private JsonNode handleToolsCall(JsonNode params, Integer requestId,
                                     BlockingQueue<String> sessionQueue) {
        String toolName = params.get("name").asText();
        JsonNode arguments = params.get("arguments");

        try {
            Object result;

            switch (toolName) {
                // Core Claude Code tools
                case "openFile":
                    result = this.openFileTool.run(this.openFileTool.parseArguments(arguments));
                    break;

                case "getWorkspaceFolders":
                    result = this.getWorkspaceFoldersTool.run(this.getWorkspaceFoldersTool.parseArguments(arguments));
                    break;

                case "getOpenEditors":
                    result = this.getOpenEditorsTool.run(this.getOpenEditorsTool.parseArguments(arguments));
                    break;

                case "getCurrentSelection":
                    result = this.getCurrentSelectionTool.run(this.getCurrentSelectionTool.parseArguments(arguments));
                    break;

                case "close_tab":
                    result = this.closeTabTool.run(this.closeTabTool.parseArguments(arguments));
                    break;

                case "getDiagnostics":
                    result = this.getDiagnosticsTool.run(this.getDiagnosticsTool.parseArguments(arguments));
                    break;

                case "checkDocumentDirty":
                    result = this.checkDocumentDirtyTool.run(this.checkDocumentDirtyTool.parseArguments(arguments));
                    break;

                case "saveDocument":
                    result = this.saveDocument.run(this.saveDocument.parseArguments(arguments));
                    break;

                case "closeAllDiffTabs":
                    result = this.closeAllDiffTabsTool.run(this.closeAllDiffTabsTool.parseArguments(arguments));
                    break;

                case "openDiff":
                    result = this.openDiffTool.run(this.openDiffTool.parseArguments(arguments));
                    break;

                case "permission_prompt":
                    result = this.permissionPromptTool.run(
                            this.permissionPromptTool.parseArguments(arguments));
                    break;

                default:
                    throw new IllegalArgumentException("Unknown tool: " + toolName);
            }

            // Check if result is async
            if (result instanceof AsyncResponse) {
                AsyncResponse asyncResponse = (AsyncResponse) result;
                asyncResponse.setHandler(new AsyncHandler() {
                    @Override
                    public void sendResponse(Object finalResult) {
                        sendAsyncToolResponse(requestId, finalResult, sessionQueue);
                    }
                });
                return null; // No immediate response
            }

            // Sync response
            return responseBuilder.createToolResponse(result);

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error executing tool: " + toolName, e);

            return responseBuilder.createToolResponse("Error: " + e.getMessage());
        }
    }

    /**
     * Sends an async tool response directly to the originating session's queue.
     * @param requestId    The original request ID
     * @param result       The tool result to send
     * @param sessionQueue The session queue that originated this tool call
     */
    private void sendAsyncToolResponse(Integer requestId, Object result,
                                       BlockingQueue<String> sessionQueue) {
        try {
            ObjectNode response = responseBuilder.objectNode();
            response.put("jsonrpc", "2.0");
            response.put("id", requestId);
            response.set("result", responseBuilder.createToolResponse(result));
            String json = objectMapper.writeValueAsString(response);
            if (!sessionQueue.offer(json)) {
                LOGGER.warning("SSE session queue full; async tool response dropped");
            }
            LOGGER.log(Level.INFO, "Sent async tool response for request ID: {0}", requestId);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error sending async tool response", e);
        }
    }

    /**
     * Data class to hold project information.
     */
    private static class ProjectData {
        final String path;
        final String displayName;

        ProjectData(String path, String displayName) {
            this.path = path;
            this.displayName = displayName;
        }
    }

    /**
     * Retrieves project data from NetBeans Platform.
     */
    private List<ProjectData> getOpenProjectsData() {
        List<ProjectData> projectDataList = new ArrayList<>();
        Project[] openProjects = OpenProjects.getDefault().getOpenProjects();

        for (Project project : openProjects) {
            String path = project.getProjectDirectory().getPath();
            String displayName = ProjectUtils.getInformation(project).getDisplayName();
            projectDataList.add(new ProjectData(path, displayName));
        }

        return projectDataList;
    }

    /**
     * Lists available resources.
     */
    private JsonNode handleResourcesList() {
        ArrayNode resources = responseBuilder.arrayNode();

        // Get project data from NetBeans
        List<ProjectData> projectDataList = getOpenProjectsData();

        // Build MCP response from the data
        for (ProjectData projectData : projectDataList) {
            ObjectNode resource = responseBuilder.objectNode();
            resource.put("uri", "project://" + projectData.path);
            resource.put("name", projectData.displayName);
            resource.put("description", "NetBeans project: " + projectData.displayName);
            resource.put("mimeType", "application/json");
            resources.add(resource);
        }

        ObjectNode result = responseBuilder.objectNode();
        result.set("resources", resources);
        return result;
    }

    /**
     * Reads a resource.
     */
    private JsonNode handleResourcesRead(JsonNode params) {
        String uri = params.get("uri").asText();

        if (uri.startsWith("project://")) {
            String projectPath = uri.substring("project://".length());
            //XXX: This is probably doing the wrong thing
            return getProjectInfo(projectPath);
        }

        throw new IllegalArgumentException("Unknown resource URI: " + uri);
    }

    /**
     * Lists available prompts.
     */
    private JsonNode handlePromptsList() {
        ArrayNode prompts = responseBuilder.arrayNode();

        ObjectNode codeReviewPrompt = responseBuilder.objectNode();
        codeReviewPrompt.put("name", "code_review");
        codeReviewPrompt.put("description", "Review code in NetBeans project");
        prompts.add(codeReviewPrompt);

        ObjectNode result = responseBuilder.objectNode();
        result.set("prompts", prompts);
        return result;
    }

    // Helper methods

    private JsonNode getProjectInfo(String projectPath) {
        FileObject projectDir = FileUtil.toFileObject(new File(projectPath));
        if (projectDir == null) {
            throw new IllegalArgumentException("Project not found: " + projectPath);
        }

        ObjectNode projectInfo = responseBuilder.objectNode();
        projectInfo.put("path", projectPath);
        projectInfo.put("name", projectDir.getName());

        ArrayNode files = responseBuilder.arrayNode();
        // projectInfo.set("files", files);

        return projectInfo;
    }

    // -------------------------------------------------------------------------
    // PreToolUse HTTP hook
    // -------------------------------------------------------------------------

    /**
     * Handles a PreToolUse hook call from the Claude Code CLI.
     *
     * <p>Parses the hook JSON, computes the before/after content for file-editing
     * tools (Edit, Write, MultiEdit), opens a NetBeans diff view with Allow/Deny
     * buttons, and returns a {@link CompletableFuture} that resolves to
     * {@code "allow"} or {@code "deny"} when the user makes a decision.
     *
     * <p>For non-file-editing tools the future is completed immediately with
     * {@code "allow"}.
     *
     * @param hookJson raw JSON body from the Claude hook POST request
     * @return future resolved with {@code "allow"}, {@code "deny"}, or {@code "ask"}
     */
    public CompletableFuture<String> handlePreToolUse(String hookJson) {
        try {
            JsonNode hook = objectMapper.readTree(hookJson);
            String toolName = hook.has("tool_name") ? hook.get("tool_name").asText() : "";
            JsonNode toolInput = hook.has("tool_input") ? hook.get("tool_input") : objectMapper.createObjectNode();

            LOGGER.info("handlePreToolUse: tool=" + toolName);

            if (!isFileEditTool(toolName)) {
                LOGGER.info("Auto-allowing non-file-edit tool: " + toolName);
                return CompletableFuture.completedFuture(hookAllowJson());
            }

            // cwd from hook JSON == confirmedDirectory of the session that launched Claude
            String cwd = hook.has("cwd") ? hook.get("cwd").asText() : null;
            EditMode editMode = getEditModeForCwd(cwd);
            LOGGER.info("handlePreToolUse: editMode=" + editMode + " cwd=" + cwd);

            String filePath = getFilePath(toolInput);

            // bypassPermissions: auto-allow everything regardless of location
            // acceptEdits: auto-allow only if file is inside the session's confirmed directory
            if (editMode == EditMode.BYPASS_PERMISSIONS
                    || (editMode == EditMode.ACCEPT_EDITS
                        && io.github.nbclaudecodegui.ui.FileDiffOpener.isFileUnderDirectory(filePath, cwd))) {
                LOGGER.info(editMode.key() + " mode — auto-allowing: " + filePath);
                return CompletableFuture.completedFuture(hookAllowJson());
            }
            // plan / ask / acceptEdits-outside: fall through to show diff dialog

            String before = computeBefore(toolInput, filePath);
            String after = computeAfter(toolName, toolInput, before);

            String tabName = resolveUniqueHookTabName("Diff: " + new File(filePath).getName());
            CompletableFuture<String> future = DiffTabTracker.registerHookFuture(tabName);

            // Pass cwd as confirmedDir so FileDiffTab can warn if file is outside this project
            FileDiffOpener.open(filePath, before, after, tabName, cwd,
            () -> DiffTabTracker.resolveHook(tabName, hookAllowJson()),
            reason -> DiffTabTracker.resolveHook(tabName, hookDenyJson(reason)),
            () -> {
                DiffTabTracker.resolveHook(tabName, hookDenyJson(""));
                FileDiffOpener.cancelCurrentPromptForFile(filePath);
            },
            () -> DiffTabTracker.resolveHook(tabName, hookAskJson())
        );

            return future;

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "handlePreToolUse error", e);
            return CompletableFuture.completedFuture(hookAskJson());
        }
    }

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

    private static String computeBefore(JsonNode toolInput, String filePath) throws IOException {
        File file = new File(filePath);
        if (!file.exists()) {
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
            throw new IllegalArgumentException("old_string not found in file — cannot compute diff");
        }
        return before.substring(0, idx) + newStr + before.substring(idx + oldStr.length());
    }

    private static String applyMultiEdit(JsonNode toolInput, String before) throws Exception {
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

    private static String hookAllowJson() {
        return "{\"hookSpecificOutput\":{\"hookEventName\":\"PreToolUse\",\"permissionDecision\":\"allow\"}}";
    }

    private static String hookDenyJson(String reason) {
        if (reason == null || reason.isBlank()) {
            return "{\"hookSpecificOutput\":{\"hookEventName\":\"PreToolUse\",\"permissionDecision\":\"deny\"}}";
        }
        String escaped = reason.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"hookSpecificOutput\":{\"hookEventName\":\"PreToolUse\","
             + "\"permissionDecision\":\"deny\","
             + "\"permissionDecisionReason\":\"" + escaped + "\"}}";
    }

    private static String hookAskJson() {
        return "{\"hookSpecificOutput\":{\"hookEventName\":\"PreToolUse\",\"permissionDecision\":\"ask\"}}";
    }

    private static String resolveUniqueHookTabName(String base) {
        String name = base;
        int suffix = 1;
        while (DiffTabTracker.isHookTracked(name)) {
            name = base + " (" + (++suffix) + ")";
        }
        return name;
    }


    // -------------------------------------------------------------------------
    // Stop hook
    // -------------------------------------------------------------------------

    /**
     * Called when Claude finishes its turn (Stop hook).
     * Finds the matching session by cwd and signals it that Claude is idle.
     *
     * @param payload the raw JSON payload from the Stop hook
     */
    public void handleStop(String payload) {
        String cwd = extractCwdFromPayload(payload);
        if (cwd == null) return;
        LOGGER.fine("handleStop cwd=" + cwd);
        SwingUtilities.invokeLater(() ->
            findSessionByCwd(cwd).ifPresent(ClaudeSessionTab::onClaudeIdle));
    }

    // -------------------------------------------------------------------------
    // PermissionRequest hook
    // -------------------------------------------------------------------------

    /**
     * Called before Claude shows a native PTY permission dialog (PermissionRequest hook).
     * Triggers a screen scan so the PromptResponsePanel appears promptly.
     *
     * @param payload the raw JSON payload from the PermissionRequest hook
     */
    public void handlePermissionRequest(String payload) {
        String cwd = extractCwdFromPayload(payload);
        if (cwd == null) return;
        LOGGER.fine("handlePermissionRequest cwd=" + cwd);
        SwingUtilities.invokeLater(() ->
            findSessionByCwd(cwd).ifPresent(ClaudeSessionTab::triggerPromptScan));
    }

    private String extractCwdFromPayload(String payload) {
        if (payload == null) return null;
        try {
            JsonNode node = objectMapper.readTree(payload);
            return node.has("cwd") ? node.get("cwd").asText(null) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static java.util.Optional<ClaudeSessionTab> findSessionByCwd(String cwd) {
        for (TopComponent tc : WindowManager.getDefault().getRegistry().getOpened()) {
            if (tc instanceof ClaudeSessionTab stc) {
                File dir = stc.getWorkingDirectory();
                if (dir != null && dir.getAbsolutePath().equals(cwd)) {
                    return java.util.Optional.of(stc);
                }
            }
        }
        return java.util.Optional.empty();
    }

    /**
     * Returns the {@link EditMode} of the session whose working directory is {@code cwd},
     * or {@link EditMode#DEFAULT} if the session cannot be found.
     *
     * <p>Safe to call from any thread — uses the thread-safe registry in
     * {@link io.github.nbclaudecodegui.model.ClaudeSessionModel#EDIT_MODE_REGISTRY}.
     */
    private static EditMode getEditModeForCwd(String cwd) {
        if (cwd == null) return EditMode.DEFAULT;
        EditMode mode = io.github.nbclaudecodegui.model.ClaudeSessionModel.EDIT_MODE_REGISTRY.get(cwd);
        return mode != null ? mode : EditMode.DEFAULT;
    }

    /**
     * Broadcasts a JSON-RPC notification to all active SSE sessions.
     *
     * @param json the serialised JSON-RPC notification
     */
    private void sendViaSse(String json) {
        Consumer<String> bc = broadcaster;
        if (bc == null) {
            LOGGER.fine("Broadcaster not set; notification dropped: " + json);
            return;
        }
        bc.accept(json);
    }

    private ObjectNode createToolDefinition(String toolName, String description, String schemaFileName) {
        ObjectNode tool = responseBuilder.objectNode();
        tool.put("name", toolName);
        tool.put("description", description);

        try {
            // Load parameter schema from JSON file
            String schemaPath = "/org/openbeans/claude/netbeans/tools/schemas/" + schemaFileName + ".json";
            InputStream inputStream = getClass().getResourceAsStream(schemaPath);

            if (inputStream == null) {
                // Fall back to empty schema if file not found
                LOGGER.warning("Schema file not found: " + schemaPath);
                ObjectNode inputSchema = responseBuilder.objectNode();
                inputSchema.put("type", "object");
                inputSchema.set("properties", responseBuilder.objectNode());
                inputSchema.set("required", responseBuilder.arrayNode());
                tool.set("inputSchema", inputSchema);
            } else {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode schema = mapper.readTree(inputStream);
                inputStream.close();

                // Set the loaded schema as inputSchema
                tool.set("inputSchema", schema);
            }

        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error loading parameter schema for: " + toolName, e);
            // Return minimal schema as fallback
            ObjectNode inputSchema = responseBuilder.objectNode();
            inputSchema.put("type", "object");
            inputSchema.set("properties", responseBuilder.objectNode());
            inputSchema.set("required", responseBuilder.arrayNode());
            tool.set("inputSchema", inputSchema);
        }

        return tool;
    }

    /**
     * Called by {@link MCPSseServer} to wire in the broadcast function.
     * Notifications (selection_changed, notifications/initialized) that should
     * reach all connected sessions are delivered via this broadcaster.
     *
     * @param broadcaster function that enqueues a message to every active session
     */
    public void setBroadcaster(Consumer<String> broadcaster) {
        this.broadcaster = broadcaster;
        startSelectionTracking();
        startDiffTabTracking();
    }

    /**
     * Starts tracking selection changes in editors.
     */
    private void startSelectionTracking() {
        // Listen for TopComponent activation changes
        topComponentListener = new PropertyChangeListener() {
            @Override
            public void propertyChange(PropertyChangeEvent evt) {
                if (TopComponent.Registry.PROP_ACTIVATED.equals(evt.getPropertyName())) {
                    TopComponent activated = TopComponent.getRegistry().getActivated();
                    if (activated != null) {
                        trackEditorSelection(activated);
                    }
                }
            }
        };

        TopComponent.getRegistry().addPropertyChangeListener(topComponentListener);

        // Track the currently active editor if any
        TopComponent activated = TopComponent.getRegistry().getActivated();
        if (activated != null) {
            trackEditorSelection(activated);
        }

        LOGGER.log(Level.FINE, "Started selection tracking");
    }

    /**
     * Stops tracking selection changes.
     */
    private void stopSelectionTracking() {
        // Remove TopComponent listener
        if (topComponentListener != null) {
            TopComponent.getRegistry().removePropertyChangeListener(topComponentListener);
            topComponentListener = null;
        }

        // Remove all selection listeners
        for (Map.Entry<JTextComponent, CaretListener> entry : selectionListeners.entrySet()) {
            entry.getKey().removeCaretListener(entry.getValue());
        }
        selectionListeners.clear();
        currentTextComponent = null;

        LOGGER.log(Level.FINE, "Stopped selection tracking");
    }

    /**
     * Starts tracking diff tab closures for async response handling.
     */
    private void startDiffTabTracking() {
        diffTabListener = new PropertyChangeListener() {
            @Override
            public void propertyChange(PropertyChangeEvent evt) {
                if (TopComponent.Registry.PROP_TC_CLOSED.equals(evt.getPropertyName())) {
                    TopComponent closed = (TopComponent) evt.getNewValue();
                    if (closed != null) {
                        String tabName = closed.getDisplayName();
                        if (tabName != null && DiffTabTracker.isTracked(tabName)) {
                            handleDiffTabClosed(tabName);
                        }
                    }
                }
            }
        };

        TopComponent.getRegistry().addPropertyChangeListener(diffTabListener);
        LOGGER.log(Level.FINE, "Started diff tab tracking");
    }

    /**
     * Stops tracking diff tab closures.
     */
    private void stopDiffTabTracking() {
        if (diffTabListener != null) {
            TopComponent.getRegistry().removePropertyChangeListener(diffTabListener);
            diffTabListener = null;
        }
        LOGGER.log(Level.FINE, "Stopped diff tab tracking");
    }

    /**
     * Handles a diff tab being closed, sending the async response.
     */
    private void handleDiffTabClosed(String tabName) {
        AsyncHandler handler = DiffTabTracker.remove(tabName);
        if (handler != null) {
            LOGGER.log(Level.INFO, "Diff tab closed: {0}", tabName);

            // Create response with DIFF_REJECTED status
            List<Content> contentList = new ArrayList<>();
            contentList.add(new Content("text", "DIFF_REJECTED"));
            contentList.add(new Content("text", tabName));
            OpenDiffResult result = new OpenDiffResult(contentList);

            handler.sendResponse(result);
        }
    }

    /**
     * Tracks selection changes in the given TopComponent if it's an editor.
     */
    private void trackEditorSelection(TopComponent tc) {
        try {
            Node[] nodes = tc.getActivatedNodes();
            if (nodes != null && nodes.length > 0) {
                EditorCookie editorCookie = nodes[0].getLookup().lookup(EditorCookie.class);
                if (editorCookie != null) {
                    JTextComponent[] panes = editorCookie.getOpenedPanes();
                    if (panes != null && panes.length > 0) {
                        JTextComponent textComponent = panes[0];

                        // Only track if it's a different component
                        if (textComponent != currentTextComponent) {
                            // Remove listener from previous component
                            if (currentTextComponent != null) {
                                CaretListener listener = selectionListeners.remove(currentTextComponent);
                                if (listener != null) {
                                    currentTextComponent.removeCaretListener(listener);
                                }
                            }

                            // Add listener to new component
                            currentTextComponent = textComponent;
                            CaretListener listener = new CaretListener() {
                                @Override
                                public void caretUpdate(CaretEvent e) {
                                    sendSelectionChangeEvent(textComponent, nodes[0]);
                                }
                            };

                            textComponent.addCaretListener(listener);
                            selectionListeners.put(textComponent, listener);

                            // Send initial selection event
                            sendSelectionChangeEvent(textComponent, nodes[0]);
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error tracking editor selection", e);
        }
    }

    /**
     * Sends a selection_changed event to Claude Code via SSE.
     */
    private void sendSelectionChangeEvent(JTextComponent textComponent, Node node) {
        try {
            if (broadcaster == null) {
                return;
            }

            // Get selection details
            String selectedText = textComponent.getSelectedText();
            int selectionStart = textComponent.getSelectionStart();
            int selectionEnd = textComponent.getSelectionEnd();

            // Get document and file info
            Document doc = textComponent.getDocument();
            DataObject dataObject = node.getLookup().lookup(DataObject.class);

            if (doc instanceof StyledDocument && dataObject != null) {
                StyledDocument styledDoc = (StyledDocument) doc;
                FileObject fileObject = dataObject.getPrimaryFile();

                if (fileObject != null) {
                    // Get file path
                    File file = FileUtil.toFile(fileObject);
                    String absolutePath = file.getAbsolutePath();
                    String fileUrl = "file://" + absolutePath;

                    // Calculate line and column positions (0-based for protocol)
                    int startLine = NbDocument.findLineNumber(styledDoc, selectionStart);
                    int startColumn = NbDocument.findLineColumn(styledDoc, selectionStart);
                    int endLine = NbDocument.findLineNumber(styledDoc, selectionEnd);
                    int endColumn = NbDocument.findLineColumn(styledDoc, selectionEnd);

                    // Create selection_changed notification
                    ObjectNode params = responseBuilder.objectNode();

                    // Add text (selected text or empty string)
                    params.put("text", selectedText != null ? selectedText : "");

                    // Add file paths
                    params.put("filePath", absolutePath);
                    params.put("fileUrl", fileUrl);

                    // Add selection object
                    ObjectNode selection = responseBuilder.objectNode();

                    ObjectNode start = responseBuilder.objectNode();
                    start.put("line", startLine);
                    start.put("character", startColumn);
                    selection.set("start", start);

                    ObjectNode end = responseBuilder.objectNode();
                    end.put("line", endLine);
                    end.put("character", endColumn);
                    selection.set("end", end);

                    // Set isEmpty based on whether there's selected text
                    selection.put("isEmpty", selectedText == null || selectedText.isEmpty());

                    params.set("selection", selection);

                    // Create and send the notification via SSE
                    ObjectNode notification = responseBuilder.createNotification("selection_changed", params);
                    sendViaSse(objectMapper.writeValueAsString(notification));
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error sending selection change event", e);
        }
    }
}
