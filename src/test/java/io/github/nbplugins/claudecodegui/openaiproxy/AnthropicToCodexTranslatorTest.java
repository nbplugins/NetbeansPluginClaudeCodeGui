package io.github.nbplugins.claudecodegui.openaiproxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AnthropicToCodexTranslator}.
 *
 * <p>Reuses several Anthropic-request fixtures from
 * {@code AnthropicToOpenAITranslatorTest} (same input format — only the
 * upstream shape differs) plus a few {@code codex_*}-prefixed fixtures for
 * Codex-specific behaviour (image placeholders, web_search tool, response shape).
 */
class AnthropicToCodexTranslatorTest {

    private static JsonNode load(String name) throws Exception {
        String path = "/io/github/nbplugins/claudecodegui/openaiproxy/" + name;
        try (InputStream is = AnthropicToCodexTranslatorTest.class.getResourceAsStream(path)) {
            assertNotNull(is, "Missing fixture: " + path);
            return AnthropicToCodexTranslator.MAPPER.readTree(
                    new String(is.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static AnthropicToCodexTranslator.StreamingState state() {
        return new AnthropicToCodexTranslator.StreamingState();
    }

    // -------------------------------------------------------------------------
    // translateRequest
    // -------------------------------------------------------------------------

    @Test
    void translateRequest_simpleTextMessage() throws Exception {
        ObjectNode result = AnthropicToCodexTranslator.translateRequest(load("req_simple_text.json"), "sess-1");

        assertEquals("claude-sonnet-4-5", result.path("model").asText());
        JsonNode input = result.path("input");
        assertEquals(1, input.size());
        assertEquals("message", input.get(0).path("type").asText());
        assertEquals("user", input.get(0).path("role").asText());
        assertEquals("input_text", input.get(0).path("content").get(0).path("type").asText());
        assertEquals("Hello", input.get(0).path("content").get(0).path("text").asText());
    }

    /**
     * Regression: the Codex backend's request schema has no {@code max_output_tokens}
     * field at all and rejects it with {@code HTTP 400 "Unsupported parameter:
     * max_output_tokens"} — Anthropic's {@code max_tokens} must simply be dropped,
     * not translated to a Codex field name.
     */
    @Test
    void translateRequest_doesNotEmitMaxOutputTokens() throws Exception {
        ObjectNode result = AnthropicToCodexTranslator.translateRequest(load("req_simple_text.json"), "sess-1");
        assertFalse(result.has("max_output_tokens"));
        assertFalse(result.has("max_tokens"));
    }

    /**
     * Regression: the Codex backend rejects requests missing this with
     * {@code HTTP 400 {"detail":"Store must be set to false"}}.
     */
    @Test
    void translateRequest_alwaysSetsStoreFalse() throws Exception {
        ObjectNode result = AnthropicToCodexTranslator.translateRequest(load("req_simple_text.json"), "sess-1");
        assertTrue(result.path("store").isBoolean());
        assertFalse(result.path("store").asBoolean());
    }

    /**
     * Regression: the Codex backend rejects requests without this, with
     * {@code HTTP 400 {"...":"Stream must be set to true"}} — Codex CLI's own
     * client (openai/codex core/src/client.rs) always sets it too, even for
     * "non-streaming" callers, which instead aggregate the resulting SSE
     * stream client-side (see {@link AnthropicToCodexTranslator.ResponseAggregator}).
     */
    @Test
    void translateRequest_alwaysSetsStreamTrue_evenWhenAnthropicRequestDidNot() throws Exception {
        JsonNode nonStreamingReq = load("req_simple_text.json");
        assertFalse(nonStreamingReq.has("stream"), "fixture must not itself request streaming");
        ObjectNode result = AnthropicToCodexTranslator.translateRequest(nonStreamingReq, "sess-1");
        assertTrue(result.path("stream").isBoolean());
        assertTrue(result.path("stream").asBoolean());
    }

    @Test
    void translateRequest_promptCacheKeySetFromSessionId() throws Exception {
        ObjectNode result = AnthropicToCodexTranslator.translateRequest(load("req_simple_text.json"), "sess-42");
        assertEquals("sess-42", result.path("prompt_cache_key").asText());
    }

    @Test
    void translateRequest_systemPromptBecomesInstructions() throws Exception {
        ObjectNode result = AnthropicToCodexTranslator.translateRequest(load("req_system_prompt.json"), "s");
        assertEquals("You are helpful.", result.path("instructions").asText());
    }

    @Test
    void translateRequest_systemAsListOfBlocks_joinedIntoInstructions() throws Exception {
        ObjectNode result = AnthropicToCodexTranslator.translateRequest(load("req_system_as_blocks.json"), "s");
        assertEquals("Block one.\n\nBlock two.", result.path("instructions").asText());
    }

    @Test
    void translateRequest_toolUseInAssistantMessage_becomesFunctionCallItem() throws Exception {
        ObjectNode result = AnthropicToCodexTranslator.translateRequest(load("req_tool_use_assistant.json"), "s");

        JsonNode input = result.path("input");
        assertEquals(1, input.size());
        JsonNode call = input.get(0);
        assertEquals("function_call", call.path("type").asText());
        assertEquals("call_1", call.path("call_id").asText());
        assertEquals("read_file", call.path("name").asText());
        assertTrue(call.path("arguments").asText().contains("test.txt"));
    }

    @Test
    void translateRequest_toolResultInUserMessage_becomesFunctionCallOutputItem() throws Exception {
        ObjectNode result = AnthropicToCodexTranslator.translateRequest(load("req_tool_result_user.json"), "s");

        JsonNode input = result.path("input");
        assertEquals(1, input.size());
        JsonNode out = input.get(0);
        assertEquals("function_call_output", out.path("type").asText());
        assertEquals("call_1", out.path("call_id").asText());
        assertEquals("file content here", out.path("output").asText());
    }

    @Test
    void translateRequest_toolResultWithListContent() throws Exception {
        ObjectNode result = AnthropicToCodexTranslator.translateRequest(load("req_tool_result_list_content.json"), "s");
        assertEquals("result text", result.path("input").get(0).path("output").asText());
    }

    @Test
    void translateRequest_toolResultWithImage_becomesPlaceholderText() throws Exception {
        ObjectNode result = AnthropicToCodexTranslator.translateRequest(
                load("codex_req_tool_result_image.json"), "s");

        String output = result.path("input").get(0).path("output").asText();
        assertTrue(output.contains("screenshot:"));
        assertTrue(output.contains("[image omitted: image/png]"),
                "expected image placeholder, got: " + output);
    }

    @Test
    void translateRequest_toolsConverted() throws Exception {
        ObjectNode result = AnthropicToCodexTranslator.translateRequest(load("req_tools.json"), "s");

        JsonNode tools = result.path("tools");
        assertEquals(1, tools.size());
        JsonNode tool = tools.get(0);
        assertEquals("function",  tool.path("type").asText());
        assertEquals("read_file", tool.path("name").asText());
        assertEquals("Reads a file", tool.path("description").asText());
        assertTrue(tool.path("parameters").has("properties"));
    }

    @Test
    void translateRequest_webSearchTool_mappedToCodexNativeTool() throws Exception {
        ObjectNode result = AnthropicToCodexTranslator.translateRequest(load("codex_req_web_search_tool.json"), "s");

        JsonNode tools = result.path("tools");
        assertEquals(1, tools.size());
        assertEquals("web_search", tools.get(0).path("type").asText());
        assertFalse(tools.get(0).has("name"), "native web_search tool should not carry a function name");
    }

    @Test
    void translateRequest_reasoningEffortFromOutputConfig() throws Exception {
        ObjectNode req = (ObjectNode) load("req_simple_text.json");
        req.putObject("output_config").put("effort", "high");

        ObjectNode result = AnthropicToCodexTranslator.translateRequest(req, "s");
        assertEquals("high", result.path("reasoning").path("effort").asText());
    }

    // -------------------------------------------------------------------------
    // translateResponse
    // -------------------------------------------------------------------------

    @Test
    void translateResponse_textOutput() throws Exception {
        ObjectNode result = AnthropicToCodexTranslator.translateResponse(load("codex_resp_text.json"), "gpt-5-codex");

        assertEquals("message", result.path("type").asText());
        assertEquals("assistant", result.path("role").asText());
        JsonNode content = result.path("content");
        assertEquals(1, content.size());
        assertEquals("text", content.get(0).path("type").asText());
        assertEquals("Hello from Codex", content.get(0).path("text").asText());
        assertEquals("end_turn", result.path("stop_reason").asText());
        assertEquals(12, result.path("usage").path("input_tokens").asInt());
        assertEquals(5,  result.path("usage").path("output_tokens").asInt());
    }

    @Test
    void translateResponse_toolCall_dropsReasoningAndSetsToolUseStopReason() throws Exception {
        ObjectNode result = AnthropicToCodexTranslator.translateResponse(load("codex_resp_tool_call.json"), "gpt-5-codex");

        JsonNode content = result.path("content");
        assertEquals(1, content.size(), "reasoning output item must be dropped");
        JsonNode toolUse = content.get(0);
        assertEquals("tool_use", toolUse.path("type").asText());
        assertEquals("call_9", toolUse.path("id").asText());
        assertEquals("read_file", toolUse.path("name").asText());
        assertEquals("/tmp/test.txt", toolUse.path("input").path("path").asText());
        assertEquals("tool_use", result.path("stop_reason").asText());
    }

    // -------------------------------------------------------------------------
    // Streaming
    // -------------------------------------------------------------------------

    @Test
    void streaming_textDelta_producesContentBlockEvents() {
        AnthropicToCodexTranslator.StreamingState s = state();

        String events = s.processEvent("response.output_text.delta",
                "{\"response\":{\"id\":\"resp_1\",\"model\":\"gpt-5-codex\"},\"delta\":\"Hi\",\"output_index\":0}");

        assertTrue(events.contains("message_start"));
        assertTrue(events.contains("content_block_start"));
        assertTrue(events.contains("text_delta"));
        assertTrue(events.contains("\"text\":\"Hi\""));
        assertTrue(s.isMessageStarted());
        assertFalse(s.isDoneReceived());
    }

    @Test
    void streaming_functionCall_producesToolUseBlock() {
        AnthropicToCodexTranslator.StreamingState s = state();

        s.processEvent("response.output_item.added",
                "{\"output_index\":0,\"item\":{\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"read_file\"}}");
        String argsEvents = s.processEvent("response.function_call_arguments.delta",
                "{\"output_index\":0,\"delta\":\"{\\\"path\\\":\\\"x\\\"}\"}");

        assertTrue(argsEvents.contains("input_json_delta"));
        assertTrue(argsEvents.contains("path"));
    }

    @Test
    void streaming_completedEvent_setsDoneAndBuildsStopEvents() {
        AnthropicToCodexTranslator.StreamingState s = state();
        s.processEvent("response.output_text.delta",
                "{\"response\":{\"id\":\"resp_1\",\"model\":\"m\"},\"delta\":\"Hi\",\"output_index\":0}");

        String events = s.processEvent("response.completed",
                "{\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":3,\"output_tokens\":2}}}");

        assertTrue(s.isDoneReceived());
        assertTrue(events.contains("content_block_stop"));
        assertTrue(events.contains("message_delta"));
        assertTrue(events.contains("message_stop"));
    }

    // -------------------------------------------------------------------------
    // Error mapping (rate limit)
    // -------------------------------------------------------------------------

    @Test
    void toCodexAnthropicError_rateLimitCode_mapsToRateLimitError() {
        String body = "{\"error\":{\"code\":\"codex.rate_limits.limit_reached\",\"message\":\"too many requests\"}}";
        String result = OpenAIProxyServlet.toCodexAnthropicError(body, 429);
        assertTrue(result.contains("rate_limit_error"));
    }

    @Test
    void toCodexAnthropicError_otherError_fallsBackToGenericMapping() {
        String body = "{\"error\":{\"message\":\"boom\",\"type\":\"server_error\"}}";
        String result = OpenAIProxyServlet.toCodexAnthropicError(body, 500);
        assertTrue(result.contains("boom"));
    }

    /**
     * Regression: the Codex backend reports validation errors as
     * {@code {"detail":"..."}} (e.g. "Store must be set to false"), a shape
     * the generic OpenAI-style {@code {"error":{...}}} mapping didn't
     * recognize — it used to fall through to an opaque "Provider returned
     * HTTP 400" message instead of the real cause.
     */
    @Test
    void toCodexAnthropicError_detailShape_surfacesRealCause() {
        String body = "{\"detail\":\"Store must be set to false\"}";
        String result = OpenAIProxyServlet.toCodexAnthropicError(body, 400);
        assertTrue(result.contains("Store must be set to false"), "expected real cause, got: " + result);
    }

    // -------------------------------------------------------------------------
    // ResponseAggregator — collapses a Codex SSE stream into a single
    // non-streaming Codex-response-shaped JSON for translateResponse.
    // -------------------------------------------------------------------------

    private static AnthropicToCodexTranslator.ResponseAggregator aggregator() {
        return new AnthropicToCodexTranslator.ResponseAggregator();
    }

    @Test
    void responseAggregator_textOnly_buildsCodexResponseWithMessageOutput() {
        AnthropicToCodexTranslator.ResponseAggregator agg = aggregator();
        agg.processEvent("response.output_text.delta", "{\"delta\":\"Hel\"}");
        agg.processEvent("response.output_text.delta", "{\"delta\":\"lo\"}");
        agg.processEvent("response.completed",
                "{\"response\":{\"id\":\"resp_1\",\"status\":\"completed\","
                        + "\"usage\":{\"input_tokens\":5,\"output_tokens\":2}}}");

        JsonNode codexResp = agg.buildCodexResponse();
        assertEquals("resp_1", codexResp.path("id").asText());
        assertEquals("completed", codexResp.path("status").asText());
        assertEquals(5, codexResp.path("usage").path("input_tokens").asInt());
        assertEquals(2, codexResp.path("usage").path("output_tokens").asInt());

        JsonNode output = codexResp.path("output");
        assertEquals(1, output.size());
        assertEquals("message", output.get(0).path("type").asText());
        assertEquals("Hello", output.get(0).path("content").get(0).path("text").asText());
    }

    @Test
    void responseAggregator_functionCall_buildsCodexResponseWithFunctionCallOutput() {
        AnthropicToCodexTranslator.ResponseAggregator agg = aggregator();
        agg.processEvent("response.output_item.added",
                "{\"output_index\":0,\"item\":{\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"read_file\"}}");
        agg.processEvent("response.function_call_arguments.delta",
                "{\"output_index\":0,\"delta\":\"{\\\"path\\\":\"}");
        agg.processEvent("response.function_call_arguments.delta",
                "{\"output_index\":0,\"delta\":\"\\\"x\\\"}\"}");
        agg.processEvent("response.completed",
                "{\"response\":{\"id\":\"resp_2\",\"status\":\"completed\","
                        + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}");

        JsonNode output = agg.buildCodexResponse().path("output");
        assertEquals(1, output.size());
        assertEquals("function_call", output.get(0).path("type").asText());
        assertEquals("call_1", output.get(0).path("call_id").asText());
        assertEquals("read_file", output.get(0).path("name").asText());
        assertEquals("{\"path\":\"x\"}", output.get(0).path("arguments").asText());
    }

    /**
     * End-to-end: aggregated output feeds directly into {@link
     * AnthropicToCodexTranslator#translateResponse}, the same as a real
     * non-streaming Codex response would.
     */
    @Test
    void responseAggregator_feedsIntoTranslateResponse() {
        AnthropicToCodexTranslator.ResponseAggregator agg = aggregator();
        agg.processEvent("response.output_text.delta", "{\"delta\":\"Hi\"}");
        agg.processEvent("response.completed",
                "{\"response\":{\"id\":\"resp_3\",\"status\":\"completed\","
                        + "\"usage\":{\"input_tokens\":4,\"output_tokens\":3}}}");

        ObjectNode anthropicResp = AnthropicToCodexTranslator.translateResponse(agg.buildCodexResponse(), "gpt-5-codex");
        assertEquals("text", anthropicResp.path("content").get(0).path("type").asText());
        assertEquals("Hi", anthropicResp.path("content").get(0).path("text").asText());
        assertEquals("end_turn", anthropicResp.path("stop_reason").asText());
        assertEquals(4, anthropicResp.path("usage").path("input_tokens").asInt());
        assertEquals(3, anthropicResp.path("usage").path("output_tokens").asInt());
    }
}
