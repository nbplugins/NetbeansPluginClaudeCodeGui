package io.github.nbplugins.claudecodegui.openaiproxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import java.util.Map;

/**
 * Stateless translator between Anthropic Messages API format and
 * OpenAI Chat Completions API format.
 *
 * <p>Based on open-source reference implementations:
 * <ul>
 *   <li>https://github.com/1rgs/claude-code-proxy (server.py)</li>
 *   <li>https://github.com/fuergaosi233/claude-code-proxy</li>
 * </ul>
 *
 * <h2>Request (Anthropic → OpenAI)</h2>
 * <ul>
 *   <li>{@code system} field → prepended as {@code {"role":"system",...}} message</li>
 *   <li>User text content blocks → flattened to string</li>
 *   <li>User tool_result blocks → converted to {@code role:"tool"} messages</li>
 *   <li>Assistant tool_use blocks → converted to {@code tool_calls}</li>
 *   <li>Image blocks → converted to OpenAI vision format</li>
 *   <li>{@code tools} → wrapped in {@code {"type":"function","function":{...}}}</li>
 *   <li>{@code max_tokens} → capped at 16384 for non-Claude models</li>
 * </ul>
 *
 * <h2>Response non-streaming (OpenAI → Anthropic)</h2>
 * Converts {@code choices[0].message} back to Anthropic content blocks.
 *
 * <h2>Response streaming</h2>
 * State machine converting OpenAI SSE chunks to Anthropic SSE event sequence.
 */
public final class AnthropicToOpenAITranslator {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private static final int MAX_TOKENS_NON_CLAUDE = 16384;

    private AnthropicToOpenAITranslator() {}

    // -------------------------------------------------------------------------
    // Request translation
    // -------------------------------------------------------------------------

    /**
     * Translates an Anthropic {@code POST /v1/messages} request body to an
     * OpenAI {@code POST /chat/completions} request body.
     *
     * @param anthropicRequest parsed Anthropic request JSON
     * @return OpenAI-format request JSON
     */
    public static ObjectNode translateRequest(JsonNode anthropicRequest) {
        ObjectNode openai = MAPPER.createObjectNode();

        // Model — pass as-is; user configures ANTHROPIC_DEFAULT_*_MODEL aliases
        String model = anthropicRequest.path("model").asText("");
        openai.put("model", model);

        // max_tokens
        if (anthropicRequest.has("max_tokens")) {
            int maxTokens = anthropicRequest.get("max_tokens").asInt();
            if (!model.startsWith("claude")) {
                maxTokens = Math.min(maxTokens, MAX_TOKENS_NON_CLAUDE);
            }
            openai.put("max_tokens", maxTokens);
        }

        // stream
        if (anthropicRequest.has("stream")) {
            openai.put("stream", anthropicRequest.get("stream").asBoolean());
        }

        // temperature, top_p (pass through if present)
        for (String field : new String[]{"temperature", "top_p"}) {
            if (anthropicRequest.has(field)) {
                openai.set(field, anthropicRequest.get(field));
            }
        }

        // Messages
        ArrayNode messages = openai.putArray("messages");

        // System prompt → first message
        JsonNode systemNode = anthropicRequest.path("system");
        if (!systemNode.isMissingNode() && !systemNode.isNull()) {
            String systemText = extractSystemText(systemNode);
            if (!systemText.isBlank()) {
                ObjectNode sysMsg = messages.addObject();
                sysMsg.put("role", "system");
                sysMsg.put("content", systemText);
            }
        }

        // Anthropic messages
        JsonNode anthropicMessages = anthropicRequest.path("messages");
        if (anthropicMessages.isArray()) {
            convertMessages(anthropicMessages, messages);
        }

        // Tools
        JsonNode tools = anthropicRequest.path("tools");
        if (tools.isArray() && !tools.isEmpty()) {
            ArrayNode openaiTools = openai.putArray("tools");
            convertTools(tools, openaiTools);
        }

        // tool_choice
        JsonNode toolChoice = anthropicRequest.path("tool_choice");
        if (!toolChoice.isMissingNode() && !toolChoice.isNull()) {
            openai.set("tool_choice", convertToolChoice(toolChoice));
        }

        return openai;
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

    private static void convertMessages(JsonNode anthropicMessages, ArrayNode openaiMessages) {
        for (JsonNode msg : anthropicMessages) {
            String role = msg.path("role").asText();
            JsonNode content = msg.path("content");

            if ("user".equals(role)) {
                convertUserMessage(content, openaiMessages);
            } else if ("assistant".equals(role)) {
                convertAssistantMessage(content, openaiMessages);
            }
        }
    }

    private static void convertUserMessage(JsonNode content, ArrayNode out) {
        if (content.isTextual()) {
            ObjectNode msg = out.addObject();
            msg.put("role", "user");
            msg.put("content", content.asText());
            return;
        }

        if (!content.isArray()) return;

        // Check if this is purely tool_result blocks — they become separate tool messages
        boolean allToolResults = true;
        for (JsonNode block : content) {
            if (!"tool_result".equals(block.path("type").asText())) {
                allToolResults = false;
                break;
            }
        }

        if (allToolResults) {
            for (JsonNode block : content) {
                ObjectNode toolMsg = out.addObject();
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", block.path("tool_use_id").asText());
                toolMsg.put("content", extractToolResultContent(block.path("content")));
            }
            return;
        }

        // Mixed user content — check for images
        boolean hasImage = false;
        for (JsonNode block : content) {
            if ("image".equals(block.path("type").asText())) {
                hasImage = true;
                break;
            }
        }

        if (!hasImage) {
            // Text only — flatten to string
            StringBuilder sb = new StringBuilder();
            for (JsonNode block : content) {
                if ("text".equals(block.path("type").asText())) {
                    sb.append(block.path("text").asText());
                }
            }
            ObjectNode msg = out.addObject();
            msg.put("role", "user");
            msg.put("content", sb.toString());
        } else {
            // Multimodal
            ObjectNode msg = out.addObject();
            msg.put("role", "user");
            ArrayNode parts = msg.putArray("content");
            for (JsonNode block : content) {
                String type = block.path("type").asText();
                if ("text".equals(type)) {
                    ObjectNode part = parts.addObject();
                    part.put("type", "text");
                    part.put("text", block.path("text").asText());
                } else if ("image".equals(type)) {
                    JsonNode src = block.path("source");
                    String mediaType = src.path("media_type").asText("image/jpeg");
                    String data = src.path("data").asText();
                    ObjectNode part = parts.addObject();
                    part.put("type", "image_url");
                    part.putObject("image_url").put("url", "data:" + mediaType + ";base64," + data);
                }
            }
        }
    }

    private static String extractToolResultContent(JsonNode contentNode) {
        if (contentNode.isTextual()) return contentNode.asText();
        if (contentNode.isNull() || contentNode.isMissingNode()) return "";
        if (contentNode.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode block : contentNode) {
                if ("text".equals(block.path("type").asText())) {
                    sb.append(block.path("text").asText());
                }
            }
            if (sb.length() > 0) return sb.toString();
        }
        // Fallback: JSON serialize
        return contentNode.toString();
    }

    private static void convertAssistantMessage(JsonNode content, ArrayNode out) {
        if (content.isTextual()) {
            ObjectNode msg = out.addObject();
            msg.put("role", "assistant");
            msg.put("content", content.asText());
            return;
        }

        if (!content.isArray()) return;

        StringBuilder textBuffer      = new StringBuilder();
        StringBuilder reasoningBuffer = new StringBuilder();
        ArrayNode toolCalls = null;

        for (JsonNode block : content) {
            String type = block.path("type").asText();
            if ("text".equals(type)) {
                textBuffer.append(block.path("text").asText());
            } else if ("thinking".equals(type)) {
                reasoningBuffer.append(block.path("thinking").asText());
            } else if ("tool_use".equals(type)) {
                if (toolCalls == null) {
                    toolCalls = MAPPER.createArrayNode();
                }
                ObjectNode tc = toolCalls.addObject();
                tc.put("id", block.path("id").asText());
                tc.put("type", "function");
                ObjectNode fn = tc.putObject("function");
                fn.put("name", block.path("name").asText());
                JsonNode input = block.path("input");
                fn.put("arguments", input.isObject() ? input.toString() : "{}");
            }
        }

        ObjectNode msg = out.addObject();
        msg.put("role", "assistant");
        if (reasoningBuffer.length() > 0) {
            msg.put("reasoning_content", reasoningBuffer.toString());
        }
        if (textBuffer.length() > 0) {
            msg.put("content", textBuffer.toString());
        } else {
            msg.putNull("content");
        }
        if (toolCalls != null) {
            msg.set("tool_calls", toolCalls);
        }
    }

    private static void convertTools(JsonNode anthropicTools, ArrayNode openaiTools) {
        for (JsonNode tool : anthropicTools) {
            ObjectNode oTool = openaiTools.addObject();
            oTool.put("type", "function");
            ObjectNode fn = oTool.putObject("function");
            fn.put("name", tool.path("name").asText());
            if (tool.has("description")) {
                fn.put("description", tool.path("description").asText());
            }
            JsonNode schema = tool.path("input_schema");
            if (!schema.isMissingNode()) {
                fn.set("parameters", schema);
            }
        }
    }

    private static JsonNode convertToolChoice(JsonNode toolChoice) {
        if (toolChoice.isTextual()) {
            String val = toolChoice.asText();
            return switch (val) {
                case "any"  -> MAPPER.getNodeFactory().textNode("required");
                case "auto" -> MAPPER.getNodeFactory().textNode("auto");
                case "none" -> MAPPER.getNodeFactory().textNode("none");
                default     -> MAPPER.getNodeFactory().textNode("auto");
            };
        }
        if (toolChoice.isObject()) {
            String type = toolChoice.path("type").asText();
            if ("tool".equals(type)) {
                ObjectNode choice = MAPPER.createObjectNode();
                choice.put("type", "function");
                choice.putObject("function").put("name", toolChoice.path("name").asText());
                return choice;
            }
        }
        return MAPPER.getNodeFactory().textNode("auto");
    }

    // -------------------------------------------------------------------------
    // Response translation (non-streaming)
    // -------------------------------------------------------------------------

    /**
     * Translates an OpenAI Chat Completions response to Anthropic Messages response format.
     *
     * @param openaiResponse parsed OpenAI response JSON
     * @param model          model name to include in response
     * @return Anthropic-format response JSON
     */
    public static ObjectNode translateResponse(JsonNode openaiResponse, String model) {
        ObjectNode anthropic = MAPPER.createObjectNode();

        anthropic.put("id",   openaiResponse.path("id").asText("msg_proxy"));
        anthropic.put("type", "message");
        anthropic.put("role", "assistant");
        anthropic.put("model", model);

        ArrayNode content = anthropic.putArray("content");
        JsonNode choices = openaiResponse.path("choices");
        String stopReason = "end_turn";

        if (choices.isArray() && !choices.isEmpty()) {
            JsonNode choice = choices.get(0);
            JsonNode message = choice.path("message");

            // Thinking / reasoning content (DeepSeek-style providers)
            JsonNode rc = message.path("reasoning_content");
            if (!rc.isNull() && !rc.isMissingNode() && !rc.asText().isBlank()) {
                ObjectNode tb = content.addObject();
                tb.put("type",      "thinking");
                tb.put("thinking",  rc.asText());
                tb.put("signature", "proxy_thinking_sig");
            }

            // Text content
            JsonNode textContent = message.path("content");
            if (!textContent.isNull() && !textContent.isMissingNode()) {
                String text = textContent.asText();
                if (!text.isBlank()) {
                    ObjectNode textBlock = content.addObject();
                    textBlock.put("type", "text");
                    textBlock.put("text", text);
                }
            }

            // Tool calls
            JsonNode toolCalls = message.path("tool_calls");
            if (toolCalls.isArray()) {
                for (JsonNode tc : toolCalls) {
                    ObjectNode toolUse = content.addObject();
                    toolUse.put("type", "tool_use");
                    toolUse.put("id",   tc.path("id").asText());
                    toolUse.put("name", tc.path("function").path("name").asText());
                    String argsStr = tc.path("function").path("arguments").asText("{}");
                    try {
                        toolUse.set("input", MAPPER.readTree(argsStr));
                    } catch (Exception e) {
                        toolUse.putObject("input");
                    }
                }
            }

            // finish_reason
            stopReason = mapFinishReason(choice.path("finish_reason").asText("stop"));
        }

        anthropic.put("stop_reason",    stopReason);
        anthropic.putNull("stop_sequence");

        // Usage
        ObjectNode usage = anthropic.putObject("usage");
        JsonNode openaiUsage = openaiResponse.path("usage");
        usage.put("input_tokens",  openaiUsage.path("prompt_tokens").asInt(0));
        usage.put("output_tokens", openaiUsage.path("completion_tokens").asInt(0));

        return anthropic;
    }

    static String mapFinishReason(String openaiReason) {
        return switch (openaiReason) {
            case "length"         -> "max_tokens";
            case "tool_calls",
                 "function_call"  -> "tool_use";
            default               -> "end_turn";
        };
    }

    // -------------------------------------------------------------------------
    // Streaming state machine
    // -------------------------------------------------------------------------

    /**
     * Mutable per-request state for the streaming SSE translator.
     * Create one instance per streaming request.
     */
    public static final class StreamingState {

        private boolean messageStarted    = false;
        private boolean thinkingBlockOpen = false;
        private int     thinkingBlockIndex = -1;
        private boolean textBlockOpen     = false;
        private int     textBlockIndex    = -1;
        private int     nextBlockIndex    = 0;
        private String  stopReason        = "end_turn";
        private int     outputTokens      = 0;
        private int     inputTokens       = 0;
        private String  messageId         = "msg_proxy";
        private String  model             = "";

        // Per OpenAI tool_call index: {id, name, started}
        private final java.util.Map<Integer, ToolCallState> toolCalls = new java.util.LinkedHashMap<>();

        private static final class ToolCallState {
            String id;
            String name;
            boolean started;
            int blockIndex;
        }

        /**
         * Process one OpenAI SSE data line and return zero or more Anthropic SSE events.
         *
         * @param dataLine the raw data line (without the {@code "data: "} prefix)
         * @return string of Anthropic SSE events to write to the client, may be empty
         */
        public String processChunk(String dataLine) {
            if ("[DONE]".equals(dataLine)) {
                return buildDoneEvents();
            }

            JsonNode chunk;
            try {
                chunk = MAPPER.readTree(dataLine);
            } catch (Exception e) {
                return "";
            }

            StringBuilder out = new StringBuilder();

            if (!messageStarted) {
                messageId = chunk.path("id").asText("msg_proxy");
                model     = chunk.path("model").asText("");
                out.append(sseEvent("message_start", buildMessageStart()));
                out.append(sseEvent("ping", "{\"type\":\"ping\"}"));
                messageStarted = true;
            }

            JsonNode choices = chunk.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                // Might be a usage-only chunk
                JsonNode usage = chunk.path("usage");
                if (!usage.isMissingNode()) {
                    outputTokens = usage.path("completion_tokens").asInt(outputTokens);
                    inputTokens  = usage.path("prompt_tokens").asInt(inputTokens);
                }
                return out.toString();
            }

            JsonNode choice = choices.get(0);
            JsonNode delta  = choice.path("delta");

            // Track finish_reason
            String fr = choice.path("finish_reason").asText(null);
            if (fr != null && !"null".equals(fr)) {
                stopReason = mapFinishReason(fr);
            }

            // Usage in this chunk (some providers send it here)
            JsonNode usage = chunk.path("usage");
            if (!usage.isMissingNode()) {
                outputTokens = usage.path("completion_tokens").asInt(outputTokens);
                inputTokens  = usage.path("prompt_tokens").asInt(inputTokens);
            }

            // Reasoning (thinking) delta — DeepSeek/OpenCode style
            JsonNode reasoningNode = delta.path("reasoning_content");
            if (!reasoningNode.isNull() && !reasoningNode.isMissingNode()) {
                String rc = reasoningNode.asText();
                if (!rc.isEmpty()) {
                    if (!thinkingBlockOpen) {
                        thinkingBlockIndex = nextBlockIndex++;
                        out.append(sseEvent("content_block_start",
                                buildThinkingBlockStart(thinkingBlockIndex)));
                        thinkingBlockOpen = true;
                    }
                    out.append(sseEvent("content_block_delta",
                            buildThinkingDelta(thinkingBlockIndex, rc)));
                }
            }

            // Text delta
            JsonNode contentNode = delta.path("content");
            if (!contentNode.isNull() && !contentNode.isMissingNode()) {
                String text = contentNode.asText();
                if (!text.isEmpty()) {
                    // Close thinking block before opening text block
                    if (thinkingBlockOpen) {
                        out.append(sseEvent("content_block_delta",
                                buildSignatureDelta(thinkingBlockIndex)));
                        out.append(sseEvent("content_block_stop",
                                "{\"type\":\"content_block_stop\",\"index\":" + thinkingBlockIndex + "}"));
                        thinkingBlockOpen = false;
                    }
                    if (!textBlockOpen) {
                        textBlockIndex = nextBlockIndex++;
                        out.append(sseEvent("content_block_start",
                                buildTextBlockStart(textBlockIndex)));
                        textBlockOpen = true;
                    }
                    out.append(sseEvent("content_block_delta",
                            buildTextDelta(textBlockIndex, text)));
                }
            }

            // Tool call deltas
            JsonNode toolCallsNode = delta.path("tool_calls");
            if (toolCallsNode.isArray()) {
                for (JsonNode tc : toolCallsNode) {
                    int tcIdx = tc.path("index").asInt(0);
                    ToolCallState state = toolCalls.computeIfAbsent(tcIdx, k -> new ToolCallState());

                    String tcId   = tc.path("id").asText(null);
                    String tcName = tc.path("function").path("name").asText(null);
                    if (tcId   != null) state.id   = tcId;
                    if (tcName != null) state.name = tcName;

                    if (!state.started && state.id != null && state.name != null) {
                        // Close thinking block if open
                        if (thinkingBlockOpen) {
                            out.append(sseEvent("content_block_delta",
                                    buildSignatureDelta(thinkingBlockIndex)));
                            out.append(sseEvent("content_block_stop",
                                    "{\"type\":\"content_block_stop\",\"index\":" + thinkingBlockIndex + "}"));
                            thinkingBlockOpen = false;
                        }
                        // Close text block if open
                        if (textBlockOpen) {
                            out.append(sseEvent("content_block_stop",
                                    "{\"type\":\"content_block_stop\",\"index\":" + textBlockIndex + "}"));
                            textBlockOpen = false;
                        }
                        state.blockIndex = nextBlockIndex++;
                        out.append(sseEvent("content_block_start",
                                buildToolUseBlockStart(state.blockIndex, state.id, state.name)));
                        state.started = true;
                    }

                    String argsFrag = tc.path("function").path("arguments").asText(null);
                    if (argsFrag != null && !argsFrag.isEmpty() && state.started) {
                        out.append(sseEvent("content_block_delta",
                                buildInputJsonDelta(state.blockIndex, argsFrag)));
                    }
                }
            }

            return out.toString();
        }

        private String buildDoneEvents() {
            StringBuilder out = new StringBuilder();
            if (thinkingBlockOpen) {
                out.append(sseEvent("content_block_delta", buildSignatureDelta(thinkingBlockIndex)));
                out.append(sseEvent("content_block_stop",
                        "{\"type\":\"content_block_stop\",\"index\":" + thinkingBlockIndex + "}"));
            }
            if (textBlockOpen) {
                out.append(sseEvent("content_block_stop",
                        "{\"type\":\"content_block_stop\",\"index\":" + textBlockIndex + "}"));
            }
            for (ToolCallState state : toolCalls.values()) {
                if (state.started) {
                    out.append(sseEvent("content_block_stop",
                            "{\"type\":\"content_block_stop\",\"index\":" + state.blockIndex + "}"));
                }
            }
            out.append(sseEvent("message_delta", buildMessageDelta()));
            out.append(sseEvent("message_stop",  "{\"type\":\"message_stop\"}"));
            return out.toString();
        }

        private String buildMessageStart() {
            return String.format(
                    "{\"type\":\"message_start\",\"message\":{\"id\":\"%s\",\"type\":\"message\"," +
                    "\"role\":\"assistant\",\"content\":[],\"model\":\"%s\"," +
                    "\"stop_reason\":null,\"stop_sequence\":null," +
                    "\"usage\":{\"input_tokens\":%d,\"output_tokens\":0}}}",
                    messageId, model, inputTokens);
        }

        private static String buildTextBlockStart(int index) {
            return String.format(
                    "{\"type\":\"content_block_start\",\"index\":%d," +
                    "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                    index);
        }

        private static String buildTextDelta(int index, String text) {
            String escaped = text.replace("\\", "\\\\").replace("\"", "\\\"")
                                 .replace("\n", "\\n").replace("\r", "\\r")
                                 .replace("\t", "\\t");
            return String.format(
                    "{\"type\":\"content_block_delta\",\"index\":%d," +
                    "\"delta\":{\"type\":\"text_delta\",\"text\":\"%s\"}}",
                    index, escaped);
        }

        private static String buildToolUseBlockStart(int index, String id, String name) {
            return String.format(
                    "{\"type\":\"content_block_start\",\"index\":%d," +
                    "\"content_block\":{\"type\":\"tool_use\",\"id\":\"%s\",\"name\":\"%s\",\"input\":{}}}",
                    index, id, name);
        }

        private static String buildInputJsonDelta(int index, String partialJson) {
            String escaped = partialJson.replace("\\", "\\\\").replace("\"", "\\\"")
                                        .replace("\n", "\\n").replace("\r", "\\r");
            return String.format(
                    "{\"type\":\"content_block_delta\",\"index\":%d," +
                    "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"%s\"}}",
                    index, escaped);
        }

        private static String buildThinkingBlockStart(int index) {
            return String.format(
                    "{\"type\":\"content_block_start\",\"index\":%d," +
                    "\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}",
                    index);
        }

        private static String buildThinkingDelta(int index, String thinking) {
            String escaped = thinking.replace("\\", "\\\\").replace("\"", "\\\"")
                                     .replace("\n", "\\n").replace("\r", "\\r")
                                     .replace("\t", "\\t");
            return String.format(
                    "{\"type\":\"content_block_delta\",\"index\":%d," +
                    "\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"%s\"}}",
                    index, escaped);
        }

        private static String buildSignatureDelta(int index) {
            return String.format(
                    "{\"type\":\"content_block_delta\",\"index\":%d," +
                    "\"delta\":{\"type\":\"signature_delta\",\"signature\":\"proxy_thinking_sig\"}}",
                    index);
        }

        private String buildMessageDelta() {
            return String.format(
                    "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"%s\"," +
                    "\"stop_sequence\":null},\"usage\":{\"output_tokens\":%d}}",
                    stopReason, outputTokens);
        }
    }

    // -------------------------------------------------------------------------
    // SSE helpers
    // -------------------------------------------------------------------------

    static String sseEvent(String eventType, String dataJson) {
        return "event: " + eventType + "\ndata: " + dataJson + "\n\n";
    }

    // -------------------------------------------------------------------------
    // Debug summary helpers (no conversation content)
    // -------------------------------------------------------------------------

    /** Returns a one-line summary of an Anthropic request for debug logging. */
    public static String summarizeAnthropicRequest(JsonNode req) {
        int msgCount  = req.path("messages").size();
        int toolCount = req.path("tools").size();
        return "model=" + req.path("model").asText("?")
                + " stream=" + req.path("stream").asBoolean(false)
                + " max_tokens=" + req.path("max_tokens").asInt(0)
                + " messages=" + msgCount
                + " tools=" + toolCount;
    }

    /** Returns a one-line summary of an OpenAI request for debug logging. */
    public static String summarizeOpenAIRequest(JsonNode req) {
        int msgCount  = req.path("messages").size();
        int toolCount = req.path("tools").size();
        return "model=" + req.path("model").asText("?")
                + " stream=" + req.path("stream").asBoolean(false)
                + " max_tokens=" + req.path("max_tokens").asInt(0)
                + " messages=" + msgCount
                + " tools=" + toolCount;
    }

    /** Returns a one-line summary of an OpenAI non-streaming response for debug logging. */
    public static String summarizeOpenAIResponse(JsonNode resp) {
        JsonNode usage  = resp.path("usage");
        JsonNode choice = resp.path("choices").path(0);
        return "finish_reason=" + choice.path("finish_reason").asText("?")
                + " prompt_tokens=" + usage.path("prompt_tokens").asInt(0)
                + " completion_tokens=" + usage.path("completion_tokens").asInt(0);
    }
}
