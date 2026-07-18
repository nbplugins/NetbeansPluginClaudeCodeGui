package io.github.nbplugins.claudecodegui.openaiproxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Stateless translator between Anthropic Messages API format and OpenAI's
 * Responses API format, as served by the ChatGPT-subscription Codex backend
 * ({@code https://chatgpt.com/backend-api/codex/responses}).
 *
 * <p>This is a different upstream shape from {@link AnthropicToOpenAITranslator}
 * (which targets the Chat Completions API): the Responses API represents a
 * conversation as a flat {@code input[]} array of typed items ({@code message},
 * {@code function_call}, {@code function_call_output}) rather than a
 * {@code messages[]} array of role/content pairs, and streams named SSE
 * events ({@code response.output_text.delta}, {@code response.completed}, ...)
 * rather than a single repeated chunk shape.
 *
 * <h2>Request (Anthropic → Responses)</h2>
 * <ul>
 *   <li>{@code system} → {@code instructions}</li>
 *   <li>{@code messages[]} → flat {@code input[]} items ({@code message},
 *       {@code function_call}, {@code function_call_output})</li>
 *   <li>Images inside {@code tool_result} content → {@code "[image omitted: <mediaType>]"}
 *       placeholder text (the Codex backend does not accept images there)</li>
 *   <li>Claude Code's {@code output_config.effort} → Codex {@code reasoning.effort}</li>
 *   <li>Claude Code's hosted {@code web_search_20250305} tool → Codex's native
 *       {@code web_search} tool type</li>
 *   <li>{@code prompt_cache_key} ← the session id used to route this request
 *       through {@code /openai-proxy/{uuid}/...}</li>
 * </ul>
 *
 * <h2>Response (Responses → Anthropic)</h2>
 * <ul>
 *   <li>{@code output[]} message/function_call items → Anthropic {@code content} blocks</li>
 *   <li>{@code reasoning} output items are dropped, not forwarded (Claude Code's
 *       thinking-block UI expects Anthropic-native signatures Codex traces don't provide)</li>
 * </ul>
 */
public final class AnthropicToCodexTranslator {

    static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = AnthropicToOpenAITranslator.MAPPER;

    private AnthropicToCodexTranslator() {}

    // -------------------------------------------------------------------------
    // Request translation
    // -------------------------------------------------------------------------

    /**
     * Translates an Anthropic {@code POST /v1/messages} request body to an
     * OpenAI Responses API request body targeting the Codex backend.
     *
     * @param anthropicRequest parsed Anthropic request JSON
     * @param sessionId        session/conversation identifier, used as {@code prompt_cache_key}
     * @return Responses-API-format request JSON
     */
    public static ObjectNode translateRequest(JsonNode anthropicRequest, String sessionId) {
        ObjectNode responses = MAPPER.createObjectNode();

        String model = anthropicRequest.path("model").asText("");
        responses.put("model", model);

        // No max_output_tokens / max_tokens field: the Codex backend's
        // ResponsesApiRequest (codex-rs/codex-api/src/common.rs) has no such
        // field at all and rejects it with HTTP 400 "Unsupported parameter:
        // max_output_tokens" — Anthropic's max_tokens is simply dropped here.

        // The Codex backend requires both of these fixed values regardless of what
        // the Anthropic client asked for — confirmed against openai/codex's own
        // client (codex-rs/core/src/client.rs sets stream: true unconditionally;
        // codex-rs/codex-api/src/common.rs's ResponsesApiRequest.store is always
        // false for this backend). Requesting otherwise gets HTTP 400
        // {"detail":"Store must be set to false"} / {"...":"Stream must be set to true"}.
        // OpenAIProxyServlet is responsible for aggregating the resulting SSE
        // stream into a single non-streaming response when the Anthropic client
        // itself didn't ask for streaming — the same approach Codex CLI's own
        // "non-streaming" callers take.
        responses.put("stream", true);
        responses.put("store", false);

        // System prompt → instructions
        JsonNode systemNode = anthropicRequest.path("system");
        if (!systemNode.isMissingNode() && !systemNode.isNull()) {
            String systemText = extractSystemText(systemNode);
            if (!systemText.isBlank()) {
                responses.put("instructions", systemText);
            }
        }

        // Reasoning effort — Claude Code's output_config.effort, if present
        JsonNode effort = anthropicRequest.path("output_config").path("effort");
        if (!effort.isMissingNode() && !effort.isNull() && !effort.asText().isBlank()) {
            responses.putObject("reasoning").put("effort", effort.asText());
        }

        if (sessionId != null && !sessionId.isBlank()) {
            responses.put("prompt_cache_key", sessionId);
        }

        // Messages → flat input[] items
        ArrayNode input = responses.putArray("input");
        JsonNode anthropicMessages = anthropicRequest.path("messages");
        if (anthropicMessages.isArray()) {
            for (JsonNode msg : anthropicMessages) {
                String role = msg.path("role").asText();
                JsonNode content = msg.path("content");
                if ("user".equals(role)) {
                    convertUserMessage(content, input);
                } else if ("assistant".equals(role)) {
                    convertAssistantMessage(content, input);
                }
            }
        }

        // Tools
        JsonNode tools = anthropicRequest.path("tools");
        if (tools.isArray() && !tools.isEmpty()) {
            ArrayNode responsesTools = responses.putArray("tools");
            convertTools(tools, responsesTools);
        }

        // tool_choice — Responses API uses the same string/object shape as Chat Completions
        JsonNode toolChoice = anthropicRequest.path("tool_choice");
        if (!toolChoice.isMissingNode() && !toolChoice.isNull()) {
            responses.set("tool_choice", convertToolChoice(toolChoice));
        }

        return responses;
    }

    private static String extractSystemText(JsonNode systemNode) {
        if (systemNode.isTextual()) {
            return systemNode.asText();
        }
        if (systemNode.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode block : systemNode) {
                if ("text".equals(block.path("type").asText())) {
                    if (sb.length() > 0) sb.append("\n\n");
                    sb.append(block.path("text").asText());
                }
            }
            return sb.toString();
        }
        return "";
    }

    private static void convertUserMessage(JsonNode content, ArrayNode input) {
        if (content.isTextual()) {
            ObjectNode msg = input.addObject();
            msg.put("type", "message");
            msg.put("role", "user");
            ArrayNode parts = msg.putArray("content");
            parts.addObject().put("type", "input_text").put("text", content.asText());
            return;
        }
        if (!content.isArray()) return;

        ArrayNode textParts = null;
        for (JsonNode block : content) {
            String type = block.path("type").asText();
            if ("tool_result".equals(type)) {
                ObjectNode out = input.addObject();
                out.put("type", "function_call_output");
                out.put("call_id", block.path("tool_use_id").asText());
                out.put("output", extractToolResultContent(block.path("content")));
            } else if ("text".equals(type)) {
                if (textParts == null) {
                    ObjectNode msg = input.addObject();
                    msg.put("type", "message");
                    msg.put("role", "user");
                    textParts = msg.putArray("content");
                }
                textParts.addObject().put("type", "input_text").put("text", block.path("text").asText());
            } else if ("image".equals(type)) {
                if (textParts == null) {
                    ObjectNode msg = input.addObject();
                    msg.put("type", "message");
                    msg.put("role", "user");
                    textParts = msg.putArray("content");
                }
                JsonNode src = block.path("source");
                String mediaType = src.path("media_type").asText("image/jpeg");
                String data = src.path("data").asText();
                textParts.addObject().put("type", "input_image")
                        .put("image_url", "data:" + mediaType + ";base64," + data);
            }
        }
    }

    /**
     * Extracts tool-result content as plain text, replacing any images with a
     * {@code "[image omitted: <mediaType>]"} placeholder — the Codex backend
     * does not accept images inside {@code function_call_output}.
     */
    private static String extractToolResultContent(JsonNode contentNode) {
        if (contentNode.isTextual()) return contentNode.asText();
        if (contentNode.isNull() || contentNode.isMissingNode()) return "";
        if (contentNode.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode block : contentNode) {
                String type = block.path("type").asText();
                if ("text".equals(type)) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(block.path("text").asText());
                } else if ("image".equals(type)) {
                    if (sb.length() > 0) sb.append("\n");
                    String mediaType = block.path("source").path("media_type").asText("image");
                    sb.append("[image omitted: ").append(mediaType).append("]");
                }
            }
            return sb.toString();
        }
        return contentNode.toString();
    }

    private static void convertAssistantMessage(JsonNode content, ArrayNode input) {
        if (content.isTextual()) {
            ObjectNode msg = input.addObject();
            msg.put("type", "message");
            msg.put("role", "assistant");
            msg.putArray("content").addObject().put("type", "output_text").put("text", content.asText());
            return;
        }
        if (!content.isArray()) return;

        StringBuilder textBuffer = new StringBuilder();
        for (JsonNode block : content) {
            String type = block.path("type").asText();
            if ("text".equals(type)) {
                textBuffer.append(block.path("text").asText());
            } else if ("tool_use".equals(type)) {
                if (textBuffer.length() > 0) {
                    ObjectNode msg = input.addObject();
                    msg.put("type", "message");
                    msg.put("role", "assistant");
                    msg.putArray("content").addObject().put("type", "output_text").put("text", textBuffer.toString());
                    textBuffer.setLength(0);
                }
                ObjectNode call = input.addObject();
                call.put("type", "function_call");
                call.put("call_id", block.path("id").asText());
                call.put("name", block.path("name").asText());
                JsonNode inputArgs = block.path("input");
                call.put("arguments", inputArgs.isObject() ? inputArgs.toString() : "{}");
            }
            // "thinking" blocks are not sent back upstream — Codex reasoning traces
            // are provider-internal and not accepted as input.
        }
        if (textBuffer.length() > 0) {
            ObjectNode msg = input.addObject();
            msg.put("type", "message");
            msg.put("role", "assistant");
            msg.putArray("content").addObject().put("type", "output_text").put("text", textBuffer.toString());
        }
    }

    /** Anthropic tool {@code name}/{@code type} values that map to Codex's native web_search tool. */
    private static boolean isHostedWebSearchTool(JsonNode tool) {
        String type = tool.path("type").asText("");
        return type.startsWith("web_search_");
    }

    private static void convertTools(JsonNode anthropicTools, ArrayNode responsesTools) {
        for (JsonNode tool : anthropicTools) {
            if (isHostedWebSearchTool(tool)) {
                responsesTools.addObject().put("type", "web_search");
                continue;
            }
            ObjectNode t = responsesTools.addObject();
            t.put("type", "function");
            t.put("name", tool.path("name").asText());
            if (tool.has("description")) {
                t.put("description", tool.path("description").asText());
            }
            JsonNode schema = tool.path("input_schema");
            if (!schema.isMissingNode()) {
                t.set("parameters", schema);
            }
        }
    }

    private static JsonNode convertToolChoice(JsonNode toolChoice) {
        if (toolChoice.isTextual()) {
            String val = toolChoice.asText();
            return switch (val) {
                case "any"  -> MAPPER.getNodeFactory().textNode("required");
                case "none" -> MAPPER.getNodeFactory().textNode("none");
                default     -> MAPPER.getNodeFactory().textNode("auto");
            };
        }
        if (toolChoice.isObject() && "tool".equals(toolChoice.path("type").asText())) {
            ObjectNode choice = MAPPER.createObjectNode();
            choice.put("type", "function");
            choice.put("name", toolChoice.path("name").asText());
            return choice;
        }
        return MAPPER.getNodeFactory().textNode("auto");
    }

    // -------------------------------------------------------------------------
    // Response translation (non-streaming)
    // -------------------------------------------------------------------------

    /**
     * Translates a Codex Responses API response to Anthropic Messages response format.
     *
     * @param codexResponse parsed Responses-API response JSON
     * @param model         model name to include in response
     * @return Anthropic-format response JSON
     */
    public static ObjectNode translateResponse(JsonNode codexResponse, String model) {
        ObjectNode anthropic = MAPPER.createObjectNode();
        anthropic.put("id",   codexResponse.path("id").asText("msg_proxy"));
        anthropic.put("type", "message");
        anthropic.put("role", "assistant");
        anthropic.put("model", model);

        ArrayNode content = anthropic.putArray("content");
        String stopReason = "end_turn";
        boolean hadToolCall = false;

        JsonNode output = codexResponse.path("output");
        if (output.isArray()) {
            for (JsonNode item : output) {
                String type = item.path("type").asText();
                if ("message".equals(type)) {
                    for (JsonNode part : item.path("content")) {
                        if ("output_text".equals(part.path("type").asText())) {
                            String text = part.path("text").asText();
                            if (!text.isBlank()) {
                                content.addObject().put("type", "text").put("text", text);
                            }
                        }
                    }
                } else if ("function_call".equals(type)) {
                    hadToolCall = true;
                    ObjectNode toolUse = content.addObject();
                    toolUse.put("type", "tool_use");
                    toolUse.put("id",   item.path("call_id").asText());
                    toolUse.put("name", item.path("name").asText());
                    String argsStr = item.path("arguments").asText("{}");
                    try {
                        toolUse.set("input", MAPPER.readTree(argsStr));
                    } catch (Exception e) {
                        toolUse.putObject("input");
                    }
                }
                // "reasoning" output items are intentionally dropped.
            }
        }

        String status = codexResponse.path("status").asText("completed");
        stopReason = hadToolCall ? "tool_use" : mapStatus(status);

        anthropic.put("stop_reason", stopReason);
        anthropic.putNull("stop_sequence");

        ObjectNode usage = anthropic.putObject("usage");
        JsonNode codexUsage = codexResponse.path("usage");
        usage.put("input_tokens",  codexUsage.path("input_tokens").asInt(0));
        usage.put("output_tokens", codexUsage.path("output_tokens").asInt(0));

        return anthropic;
    }

    static String mapStatus(String status) {
        return switch (status) {
            case "incomplete" -> "max_tokens";
            default            -> "end_turn";
        };
    }

    // -------------------------------------------------------------------------
    // Streaming state machine
    // -------------------------------------------------------------------------

    /**
     * Mutable per-request state for the Responses-API streaming translator.
     * Create one instance per streaming request.
     *
     * <p>The Responses API emits named SSE events ({@code response.output_text.delta},
     * {@code response.output_item.added}, {@code response.function_call_arguments.delta},
     * {@code response.completed}, {@code response.failed}) rather than Chat Completions'
     * single repeated chunk shape, so this state machine is independent of
     * {@link AnthropicToOpenAITranslator.StreamingState}.
     */
    public static final class StreamingState {

        private boolean messageStarted = false;
        private boolean doneReceived   = false;
        private boolean textBlockOpen  = false;
        private int     textBlockIndex = -1;
        private int     nextBlockIndex = 0;
        private String  stopReason     = "end_turn";
        private int     outputTokens   = 0;
        private int     inputTokens    = 0;
        private String  messageId      = "msg_proxy";
        private String  model          = "";

        // Codex output_index -> block state, for function_call items
        private final java.util.Map<Integer, ToolCallState> toolCalls = new java.util.LinkedHashMap<>();

        private static final class ToolCallState {
            String id;
            String name;
            boolean started;
            int blockIndex;
        }

        public boolean isMessageStarted() { return messageStarted; }
        public boolean isDoneReceived()   { return doneReceived; }

        /**
         * Processes one named Responses-API SSE event and returns zero or more
         * Anthropic SSE events.
         *
         * @param eventType the SSE {@code event:} name (e.g. {@code response.output_text.delta})
         * @param dataLine  the raw {@code data:} JSON payload for that event
         * @return string of Anthropic SSE events to write to the client, may be empty
         */
        public String processEvent(String eventType, String dataLine) {
            JsonNode data;
            try {
                data = MAPPER.readTree(dataLine);
            } catch (Exception e) {
                return "";
            }

            StringBuilder out = new StringBuilder();
            ensureMessageStarted(data, out);

            switch (eventType) {
                case "response.output_text.delta" -> {
                    String delta = data.path("delta").asText("");
                    if (!delta.isEmpty()) {
                        if (!textBlockOpen) {
                            textBlockIndex = nextBlockIndex++;
                            out.append(AnthropicToOpenAITranslator.sseEvent("content_block_start",
                                    buildTextBlockStart(textBlockIndex)));
                            textBlockOpen = true;
                        }
                        out.append(AnthropicToOpenAITranslator.sseEvent("content_block_delta",
                                buildTextDelta(textBlockIndex, delta)));
                    }
                }
                case "response.output_item.added" -> {
                    JsonNode item = data.path("item");
                    if ("function_call".equals(item.path("type").asText())) {
                        closeTextBlockIfOpen(out);
                        int outputIndex = data.path("output_index").asInt(0);
                        ToolCallState state = new ToolCallState();
                        state.id = item.path("call_id").asText();
                        state.name = item.path("name").asText();
                        state.blockIndex = nextBlockIndex++;
                        state.started = true;
                        toolCalls.put(outputIndex, state);
                        out.append(AnthropicToOpenAITranslator.sseEvent("content_block_start",
                                buildToolUseBlockStart(state.blockIndex, state.id, state.name)));
                    }
                }
                case "response.function_call_arguments.delta" -> {
                    int outputIndex = data.path("output_index").asInt(0);
                    ToolCallState state = toolCalls.get(outputIndex);
                    String delta = data.path("delta").asText("");
                    if (state != null && !delta.isEmpty()) {
                        out.append(AnthropicToOpenAITranslator.sseEvent("content_block_delta",
                                buildInputJsonDelta(state.blockIndex, delta)));
                    }
                }
                case "response.completed", "response.incomplete" -> {
                    JsonNode usage = data.path("response").path("usage");
                    if (!usage.isMissingNode()) {
                        outputTokens = usage.path("output_tokens").asInt(outputTokens);
                        inputTokens  = usage.path("input_tokens").asInt(inputTokens);
                    }
                    stopReason = !toolCalls.isEmpty() ? "tool_use"
                            : mapStatus(data.path("response").path("status").asText("completed"));
                    doneReceived = true;
                    closeTextBlockIfOpen(out);
                    out.append(buildDoneEvents());
                }
                case "response.failed", "error" -> {
                    doneReceived = true;
                    closeTextBlockIfOpen(out);
                    out.append(buildDoneEvents());
                }
                default -> { /* ignore other event types (response.created, response.output_item.done, ...) */ }
            }

            return out.toString();
        }

        private void ensureMessageStarted(JsonNode data, StringBuilder out) {
            if (messageStarted) return;
            JsonNode response = data.path("response");
            if (!response.isMissingNode()) {
                messageId = response.path("id").asText("msg_proxy");
                model     = response.path("model").asText("");
            }
            out.append(AnthropicToOpenAITranslator.sseEvent("message_start", buildMessageStart()));
            out.append(AnthropicToOpenAITranslator.sseEvent("ping", "{\"type\":\"ping\"}"));
            messageStarted = true;
        }

        private void closeTextBlockIfOpen(StringBuilder out) {
            if (textBlockOpen) {
                out.append(AnthropicToOpenAITranslator.sseEvent("content_block_stop",
                        "{\"type\":\"content_block_stop\",\"index\":" + textBlockIndex + "}"));
                textBlockOpen = false;
            }
        }

        String buildDoneEvents() {
            StringBuilder out = new StringBuilder();
            for (ToolCallState state : toolCalls.values()) {
                out.append(AnthropicToOpenAITranslator.sseEvent("content_block_stop",
                        "{\"type\":\"content_block_stop\",\"index\":" + state.blockIndex + "}"));
            }
            out.append(AnthropicToOpenAITranslator.sseEvent("message_delta", buildMessageDelta()));
            out.append(AnthropicToOpenAITranslator.sseEvent("message_stop", "{\"type\":\"message_stop\"}"));
            return out.toString();
        }

        private String buildMessageStart() {
            ObjectNode msg = MAPPER.createObjectNode();
            msg.put("id", messageId);
            msg.put("type", "message");
            msg.put("role", "assistant");
            msg.putArray("content");
            msg.put("model", model);
            msg.putNull("stop_reason");
            msg.putNull("stop_sequence");
            msg.putObject("usage").put("input_tokens", inputTokens).put("output_tokens", 0);
            ObjectNode root = MAPPER.createObjectNode();
            root.put("type", "message_start");
            root.set("message", msg);
            return toJson(root);
        }

        private static String buildTextBlockStart(int index) {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("type", "content_block_start");
            root.put("index", index);
            root.putObject("content_block").put("type", "text").put("text", "");
            return toJson(root);
        }

        private static String buildTextDelta(int index, String text) {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("type", "content_block_delta");
            root.put("index", index);
            root.putObject("delta").put("type", "text_delta").put("text", text);
            return toJson(root);
        }

        private static String buildToolUseBlockStart(int index, String id, String name) {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("type", "content_block_start");
            root.put("index", index);
            ObjectNode cb = root.putObject("content_block");
            cb.put("type", "tool_use");
            cb.put("id", id);
            cb.put("name", name);
            cb.putObject("input");
            return toJson(root);
        }

        private static String buildInputJsonDelta(int index, String partialJson) {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("type", "content_block_delta");
            root.put("index", index);
            root.putObject("delta").put("type", "input_json_delta").put("partial_json", partialJson);
            return toJson(root);
        }

        private String buildMessageDelta() {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("type", "message_delta");
            ObjectNode delta = root.putObject("delta");
            delta.put("stop_reason", stopReason);
            delta.putNull("stop_sequence");
            root.putObject("usage").put("output_tokens", outputTokens);
            return toJson(root);
        }

        private static String toJson(ObjectNode node) {
            try {
                return MAPPER.writeValueAsString(node);
            } catch (Exception e) {
                return "{}";
            }
        }
    }

    // -------------------------------------------------------------------------
    // Stream aggregation (for non-streaming Anthropic clients)
    // -------------------------------------------------------------------------

    /**
     * Aggregates a Codex Responses-API SSE stream into a single non-streaming
     * Codex-response-shaped {@link JsonNode}, suitable for {@link #translateResponse}.
     *
     * <p>The Codex backend requires {@code stream: true} on every request (see
     * {@link #translateRequest}), so when the Anthropic client didn't itself
     * ask for streaming, {@code OpenAIProxyServlet} still has to open an SSE
     * connection to Codex — this class lets it collapse that stream back into
     * one response instead of duplicating the non-streaming translation logic.
     * Create one instance per request.
     */
    public static final class ResponseAggregator {

        private String responseId = "msg_proxy";
        private String status = "completed";
        private int inputTokens = 0;
        private int outputTokens = 0;
        private final StringBuilder textBuffer = new StringBuilder();

        // Codex output_index -> in-progress function call
        private final java.util.Map<Integer, FunctionCallState> functionCalls = new java.util.LinkedHashMap<>();

        private static final class FunctionCallState {
            String callId;
            String name;
            final StringBuilder arguments = new StringBuilder();
        }

        /**
         * Processes one named Responses-API SSE event.
         *
         * @param eventType the SSE {@code event:} name (e.g. {@code response.output_text.delta})
         * @param dataLine  the raw {@code data:} JSON payload for that event
         */
        public void processEvent(String eventType, String dataLine) {
            JsonNode data;
            try {
                data = MAPPER.readTree(dataLine);
            } catch (Exception e) {
                return;
            }

            switch (eventType) {
                case "response.output_text.delta" -> textBuffer.append(data.path("delta").asText(""));
                case "response.output_item.added" -> {
                    JsonNode item = data.path("item");
                    if ("function_call".equals(item.path("type").asText())) {
                        int outputIndex = data.path("output_index").asInt(0);
                        FunctionCallState state = new FunctionCallState();
                        state.callId = item.path("call_id").asText();
                        state.name = item.path("name").asText();
                        functionCalls.put(outputIndex, state);
                    }
                }
                case "response.function_call_arguments.delta" -> {
                    int outputIndex = data.path("output_index").asInt(0);
                    FunctionCallState state = functionCalls.get(outputIndex);
                    if (state != null) {
                        state.arguments.append(data.path("delta").asText(""));
                    }
                }
                case "response.completed", "response.incomplete" -> {
                    JsonNode response = data.path("response");
                    responseId = response.path("id").asText(responseId);
                    status = response.path("status").asText("completed");
                    JsonNode usage = response.path("usage");
                    inputTokens  = usage.path("input_tokens").asInt(inputTokens);
                    outputTokens = usage.path("output_tokens").asInt(outputTokens);
                }
                default -> { /* ignore other event types (response.created, response.output_item.done, ...) */ }
            }
        }

        /**
         * Builds a Codex-response-shaped JSON object from everything processed so
         * far, in the same {@code {"id":...,"output":[...],"usage":{...},"status":...}}
         * shape a non-streaming Codex response would have — pass it to
         * {@link #translateResponse} to get the final Anthropic response.
         *
         * @return the aggregated Codex-shaped response
         */
        public ObjectNode buildCodexResponse() {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("id", responseId);
            root.put("status", status);

            ArrayNode output = root.putArray("output");
            if (textBuffer.length() > 0) {
                ObjectNode message = output.addObject();
                message.put("type", "message");
                message.putArray("content").addObject().put("type", "output_text").put("text", textBuffer.toString());
            }
            for (FunctionCallState state : functionCalls.values()) {
                ObjectNode call = output.addObject();
                call.put("type", "function_call");
                call.put("call_id", state.callId);
                call.put("name", state.name);
                call.put("arguments", state.arguments.toString());
            }

            ObjectNode usage = root.putObject("usage");
            usage.put("input_tokens", inputTokens);
            usage.put("output_tokens", outputTokens);
            return root;
        }
    }

    // -------------------------------------------------------------------------
    // Debug summary helpers (no conversation content)
    // -------------------------------------------------------------------------

    /** Returns a one-line summary of an Anthropic request for debug logging. */
    public static String summarizeAnthropicRequest(JsonNode req) {
        return AnthropicToOpenAITranslator.summarizeAnthropicRequest(req);
    }

    /** Returns a one-line summary of a Codex Responses-API request for debug logging. */
    public static String summarizeCodexRequest(JsonNode req) {
        return "model=" + req.path("model").asText("?")
                + " stream=" + req.path("stream").asBoolean(false)
                + " input=" + req.path("input").size()
                + " tools=" + req.path("tools").size();
    }

    /** Returns a one-line summary of a Codex Responses-API response for debug logging. */
    public static String summarizeCodexResponse(JsonNode resp) {
        JsonNode usage = resp.path("usage");
        return "status=" + resp.path("status").asText("?")
                + " output_items=" + resp.path("output").size()
                + " input_tokens=" + usage.path("input_tokens").asInt(0)
                + " output_tokens=" + usage.path("output_tokens").asInt(0);
    }
}
