package io.github.nbplugins.claudecodegui.openaiproxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AnthropicToOpenAITranslator}.
 *
 * <p>JSON fixtures are in
 * {@code src/test/resources/io/github/nbplugins/claudecodegui/openaiproxy/}.
 */
class AnthropicToOpenAITranslatorTest {

    private static JsonNode load(String name) throws Exception {
        String path = "/io/github/nbplugins/claudecodegui/openaiproxy/" + name;
        try (InputStream is = AnthropicToOpenAITranslatorTest.class.getResourceAsStream(path)) {
            assertNotNull(is, "Missing fixture: " + path);
            return AnthropicToOpenAITranslator.MAPPER.readTree(
                    new String(is.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static AnthropicToOpenAITranslator.StreamingState state() {
        return new AnthropicToOpenAITranslator.StreamingState();
    }

    // -------------------------------------------------------------------------
    // translateRequest
    // -------------------------------------------------------------------------

    @Test
    void translateRequest_simpleTextMessage() throws Exception {
        ObjectNode result = AnthropicToOpenAITranslator.translateRequest(load("req_simple_text.json"));

        assertEquals("claude-sonnet-4-5", result.path("model").asText());
        assertEquals(1024, result.path("max_tokens").asInt());
        JsonNode messages = result.path("messages");
        assertEquals(1, messages.size());
        assertEquals("user", messages.get(0).path("role").asText());
        assertEquals("Hello", messages.get(0).path("content").asText());
    }

    @Test
    void translateRequest_systemPromptPrependedAsFirstMessage() throws Exception {
        ObjectNode result = AnthropicToOpenAITranslator.translateRequest(load("req_system_prompt.json"));

        JsonNode messages = result.path("messages");
        assertEquals(2, messages.size());
        assertEquals("system", messages.get(0).path("role").asText());
        assertEquals("You are helpful.", messages.get(0).path("content").asText());
        assertEquals("user", messages.get(1).path("role").asText());
    }

    @Test
    void translateRequest_systemAsListOfBlocks() throws Exception {
        ObjectNode result = AnthropicToOpenAITranslator.translateRequest(load("req_system_as_blocks.json"));

        JsonNode sysMsg = result.path("messages").get(0);
        assertEquals("system", sysMsg.path("role").asText());
        assertEquals("Block one.\n\nBlock two.", sysMsg.path("content").asText());
    }

    @Test
    void translateRequest_toolUseInAssistantMessage() throws Exception {
        ObjectNode result = AnthropicToOpenAITranslator.translateRequest(load("req_tool_use_assistant.json"));

        JsonNode msg = result.path("messages").get(0);
        assertEquals("assistant", msg.path("role").asText());
        assertTrue(msg.path("content").isNull());
        JsonNode toolCalls = msg.path("tool_calls");
        assertEquals(1, toolCalls.size());
        assertEquals("call_1",    toolCalls.get(0).path("id").asText());
        assertEquals("function",  toolCalls.get(0).path("type").asText());
        assertEquals("read_file", toolCalls.get(0).path("function").path("name").asText());
        String args = toolCalls.get(0).path("function").path("arguments").asText();
        assertTrue(args.contains("test.txt"), "arguments should contain path: " + args);
    }

    @Test
    void translateRequest_toolResultInUserMessage() throws Exception {
        ObjectNode result = AnthropicToOpenAITranslator.translateRequest(load("req_tool_result_user.json"));

        JsonNode messages = result.path("messages");
        assertEquals(1, messages.size());
        JsonNode toolMsg = messages.get(0);
        assertEquals("tool",              toolMsg.path("role").asText());
        assertEquals("call_1",            toolMsg.path("tool_call_id").asText());
        assertEquals("file content here", toolMsg.path("content").asText());
    }

    @Test
    void translateRequest_toolResultWithListContent() throws Exception {
        ObjectNode result = AnthropicToOpenAITranslator.translateRequest(load("req_tool_result_list_content.json"));

        JsonNode toolMsg = result.path("messages").get(0);
        assertEquals("tool",        toolMsg.path("role").asText());
        assertEquals("result text", toolMsg.path("content").asText());
    }

    @Test
    void translateRequest_toolsConverted() throws Exception {
        ObjectNode result = AnthropicToOpenAITranslator.translateRequest(load("req_tools.json"));

        JsonNode tools = result.path("tools");
        assertEquals(1, tools.size());
        JsonNode tool = tools.get(0);
        assertEquals("function",  tool.path("type").asText());
        assertEquals("read_file", tool.path("function").path("name").asText());
        assertEquals("Reads a file", tool.path("function").path("description").asText());
        assertTrue(tool.path("function").path("parameters").has("properties"));
    }

    @Test
    void translateRequest_maxTokensCappedForNonClaudeModel() throws Exception {
        ObjectNode result = AnthropicToOpenAITranslator.translateRequest(load("req_max_tokens_non_claude.json"));
        assertEquals(16384, result.path("max_tokens").asInt());
    }

    @Test
    void translateRequest_maxTokensNotCappedForClaudeModel() throws Exception {
        ObjectNode result = AnthropicToOpenAITranslator.translateRequest(load("req_max_tokens_claude.json"));
        assertEquals(100000, result.path("max_tokens").asInt());
    }

    @Test
    void translateRequest_mixedAssistantContent() throws Exception {
        ObjectNode result = AnthropicToOpenAITranslator.translateRequest(load("req_mixed_assistant.json"));

        JsonNode msg = result.path("messages").get(0);
        assertEquals("I will call the tool.", msg.path("content").asText());
        assertEquals(1, msg.path("tool_calls").size());
    }

    // -------------------------------------------------------------------------
    // translateResponse
    // -------------------------------------------------------------------------

    @Test
    void translateResponse_textContent() throws Exception {
        ObjectNode result = AnthropicToOpenAITranslator.translateResponse(load("resp_text.json"), "gpt-4o");

        assertEquals("message",     result.path("type").asText());
        assertEquals("assistant",   result.path("role").asText());
        assertEquals("end_turn",    result.path("stop_reason").asText());
        assertEquals(1,             result.path("content").size());
        assertEquals("text",        result.path("content").get(0).path("type").asText());
        assertEquals("Hello there!", result.path("content").get(0).path("text").asText());
        assertEquals(10, result.path("usage").path("input_tokens").asInt());
        assertEquals(5,  result.path("usage").path("output_tokens").asInt());
    }

    @Test
    void translateResponse_toolCalls() throws Exception {
        ObjectNode result = AnthropicToOpenAITranslator.translateResponse(load("resp_tool_calls.json"), "gpt-4o");

        assertEquals("tool_use", result.path("stop_reason").asText());
        JsonNode content = result.path("content");
        assertEquals(1, content.size());
        assertEquals("tool_use",  content.get(0).path("type").asText());
        assertEquals("call_abc",  content.get(0).path("id").asText());
        assertEquals("read_file", content.get(0).path("name").asText());
        assertEquals("/tmp/x.txt", content.get(0).path("input").path("path").asText());
    }

    @Test
    void translateResponse_finishReasonMapping() {
        assertEquals("end_turn",   AnthropicToOpenAITranslator.mapFinishReason("stop"));
        assertEquals("max_tokens", AnthropicToOpenAITranslator.mapFinishReason("length"));
        assertEquals("tool_use",   AnthropicToOpenAITranslator.mapFinishReason("tool_calls"));
        assertEquals("tool_use",   AnthropicToOpenAITranslator.mapFinishReason("function_call"));
        assertEquals("end_turn",   AnthropicToOpenAITranslator.mapFinishReason("unknown"));
    }

    @Test
    void translateRequest_thinkingBlockConverted() throws Exception {
        ObjectNode result = AnthropicToOpenAITranslator.translateRequest(load("req_thinking_assistant.json"));

        JsonNode msg = result.path("messages").get(0);
        assertEquals("assistant", msg.path("role").asText());
        assertEquals("Let me reason about this.", msg.path("reasoning_content").asText());
        assertEquals("Here is the answer.", msg.path("content").asText());
    }

    // -------------------------------------------------------------------------
    // StreamingState
    // -------------------------------------------------------------------------

    @Test
    void streaming_textDelta() {
        var s = state();

        String first = s.processChunk(
                "{\"id\":\"c1\",\"model\":\"gpt-4o\",\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"\"},\"index\":0}]}");
        assertTrue(first.contains("message_start"), "expected message_start in: " + first);
        assertTrue(first.contains("ping"),           "expected ping in: " + first);

        String second = s.processChunk(
                "{\"id\":\"c1\",\"choices\":[{\"delta\":{\"content\":\"Hello\"},\"index\":0}]}");
        assertTrue(second.contains("content_block_start"), "expected content_block_start in: " + second);
        assertTrue(second.contains("text_delta"),           "expected text_delta in: " + second);
        assertTrue(second.contains("Hello"),                "expected Hello in: " + second);

        String done = s.processChunk("[DONE]");
        assertTrue(done.contains("content_block_stop"), "expected content_block_stop in: " + done);
        assertTrue(done.contains("message_delta"),      "expected message_delta in: " + done);
        assertTrue(done.contains("message_stop"),       "expected message_stop in: " + done);
    }

    @Test
    void streaming_toolCallDelta() {
        var s = state();

        s.processChunk("{\"id\":\"c1\",\"model\":\"gpt-4o\",\"choices\":[{\"delta\":{\"role\":\"assistant\"},\"index\":0}]}");

        String chunk1 = s.processChunk(
                "{\"id\":\"c1\",\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"tc1\",\"function\":{\"name\":\"read_file\",\"arguments\":\"\"}}]},\"index\":0}]}");
        assertTrue(chunk1.contains("content_block_start"), "expected content_block_start in: " + chunk1);
        assertTrue(chunk1.contains("tool_use"),             "expected tool_use in: " + chunk1);
        assertTrue(chunk1.contains("tc1"),                  "expected id in: " + chunk1);

        String chunk2 = s.processChunk(
                "{\"id\":\"c1\",\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"path\\\":\\\"\"}}]},\"index\":0}]}");
        assertTrue(chunk2.contains("input_json_delta"), "expected input_json_delta in: " + chunk2);

        String done = s.processChunk("[DONE]");
        assertTrue(done.contains("content_block_stop"), "done should close tool block: " + done);
        assertTrue(done.contains("message_stop"),       "done should end message: " + done);
    }

    @Test
    void streaming_finishReasonInChunk() {
        var s = state();
        s.processChunk("{\"id\":\"c1\",\"model\":\"m\",\"choices\":[{\"delta\":{\"content\":\"hi\"},\"index\":0}]}");
        s.processChunk("{\"id\":\"c1\",\"choices\":[{\"delta\":{},\"finish_reason\":\"length\",\"index\":0}]}");
        String done = s.processChunk("[DONE]");
        assertTrue(done.contains("max_tokens"), "expected max_tokens stop_reason in: " + done);
    }

    @Test
    void streaming_thinkingDelta() {
        var s = state();

        s.processChunk("{\"id\":\"c1\",\"model\":\"big-pickle\",\"choices\":[{\"delta\":{\"role\":\"assistant\"},\"index\":0}]}");

        String chunk1 = s.processChunk(
                "{\"id\":\"c1\",\"choices\":[{\"delta\":{\"reasoning_content\":\"Let me think.\"},\"index\":0}]}");
        assertTrue(chunk1.contains("content_block_start"), "expected thinking block start: " + chunk1);
        assertTrue(chunk1.contains("thinking_delta"),      "expected thinking_delta: " + chunk1);
        assertTrue(chunk1.contains("Let me think."),       "expected thinking text: " + chunk1);

        String chunk2 = s.processChunk(
                "{\"id\":\"c1\",\"choices\":[{\"delta\":{\"content\":\"Answer.\"},\"index\":0}]}");
        assertTrue(chunk2.contains("signature_delta"),     "expected signature_delta before text: " + chunk2);
        assertTrue(chunk2.contains("content_block_stop"),  "expected thinking block stop: " + chunk2);
        assertTrue(chunk2.contains("text_delta"),          "expected text_delta: " + chunk2);

        String done = s.processChunk("[DONE]");
        assertTrue(done.contains("message_stop"), "expected message_stop: " + done);
    }

    @Test
    void streaming_invalidJsonIsNoop() {
        var s = state();
        assertEquals("", s.processChunk("not-valid-json"));
    }

    @Test
    void sseEvent_format() {
        String event = AnthropicToOpenAITranslator.sseEvent("ping", "{\"type\":\"ping\"}");
        assertEquals("event: ping\ndata: {\"type\":\"ping\"}\n\n", event);
    }

    // -------------------------------------------------------------------------
    // Control character escaping regression tests (fix: use Jackson instead of manual escaping)
    // -------------------------------------------------------------------------

    @Test
    void streaming_textWithFormFeedProducesValidJson() throws Exception {
        var s = state();
        s.processChunk("{\"id\":\"c1\",\"model\":\"m\",\"choices\":[{\"delta\":{\"role\":\"assistant\"},\"index\":0}]}");

        String textWithControlChars = "line1\fline2line3 line4";
        String chunk = s.processChunk(
                "{\"id\":\"c1\",\"choices\":[{\"delta\":{\"content\":" +
                AnthropicToOpenAITranslator.MAPPER.writeValueAsString(textWithControlChars) +
                "},\"index\":0}]}");

        // The SSE data line must be valid JSON
        String dataLine = extractSseDataLine(chunk, "content_block_delta");
        assertNotNull(dataLine, "Expected content_block_delta event in: " + chunk);
        JsonNode parsed = AnthropicToOpenAITranslator.MAPPER.readTree(dataLine);
        assertEquals(textWithControlChars, parsed.path("delta").path("text").asText());
    }

    @Test
    void streaming_thinkingWithControlCharsProducesValidJson() throws Exception {
        var s = state();
        s.processChunk("{\"id\":\"c1\",\"model\":\"m\",\"choices\":[{\"delta\":{\"role\":\"assistant\"},\"index\":0}]}");

        String thinkingWithCtrl = "thinking\n\tcode:";
        String chunk = s.processChunk(
                "{\"id\":\"c1\",\"choices\":[{\"delta\":{\"reasoning_content\":" +
                AnthropicToOpenAITranslator.MAPPER.writeValueAsString(thinkingWithCtrl) +
                "},\"index\":0}]}");

        String dataLine = extractSseDataLine(chunk, "content_block_delta");
        assertNotNull(dataLine, "Expected content_block_delta event in: " + chunk);
        JsonNode parsed = AnthropicToOpenAITranslator.MAPPER.readTree(dataLine);
        assertEquals(thinkingWithCtrl, parsed.path("delta").path("thinking").asText());
    }

    @Test
    void streaming_singleChunkFullResponseProducesCompleteStream() throws Exception {
        var s = state();

        // Single-chunk pseudo-streaming: full response + finish_reason in one chunk
        String single = s.processChunk(
                "{\"id\":\"c1\",\"model\":\"deepseek\",\"choices\":[{\"delta\":{" +
                "\"role\":\"assistant\",\"content\":\"Hello world\"},\"finish_reason\":\"stop\",\"index\":0}]," +
                "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":3}}");

        assertTrue(single.contains("message_start"),       "missing message_start: " + single);
        assertTrue(single.contains("content_block_start"), "missing content_block_start: " + single);
        assertTrue(single.contains("text_delta"),          "missing text_delta: " + single);

        String done = s.processChunk("[DONE]");
        assertTrue(done.contains("content_block_stop"), "missing content_block_stop: " + done);
        assertTrue(done.contains("message_delta"),      "missing message_delta: " + done);
        assertTrue(done.contains("message_stop"),       "missing message_stop: " + done);
        assertTrue(done.contains("end_turn"),           "missing end_turn: " + done);
        // Usage from the chunk should be in message_delta
        JsonNode msgDelta = AnthropicToOpenAITranslator.MAPPER.readTree(
                extractSseDataLine(done, "message_delta"));
        assertEquals(3, msgDelta.path("usage").path("output_tokens").asInt());
    }

    @Test
    void translateRequest_streamOptionsAddedWhenStreamTrue() throws Exception {
        ObjectNode result = AnthropicToOpenAITranslator.translateRequest(load("req_simple_text.json"));
        // req_simple_text.json has no stream field; stream_options should not be added
        assertFalse(result.has("stream_options"), "stream_options must not appear for non-streaming");
    }

    @Test
    void translateRequest_streamOptionsAddedForStreamingRequest() throws Exception {
        ObjectNode req = (ObjectNode) load("req_simple_text.json");
        req.put("stream", true);
        ObjectNode result = AnthropicToOpenAITranslator.translateRequest(req);
        assertTrue(result.has("stream_options"),                                "stream_options missing");
        assertTrue(result.path("stream_options").path("include_usage").asBoolean(), "include_usage not true");
    }

    @Test
    void streaming_missingDoneHandledByIsMessageStartedAndIsDoneReceived() {
        var s = state();
        assertFalse(s.isMessageStarted());
        assertFalse(s.isDoneReceived());

        s.processChunk("{\"id\":\"c1\",\"model\":\"m\",\"choices\":[{\"delta\":{\"content\":\"hi\"},\"index\":0}]}");
        assertTrue(s.isMessageStarted());
        assertFalse(s.isDoneReceived());

        s.processChunk("[DONE]");
        assertTrue(s.isDoneReceived());
    }

    /** Extract the JSON data line for the first SSE event with the given name from an event block string. */
    private static String extractSseDataLine(String events, String eventName) {
        for (String block : events.split("\n\n")) {
            if (block.contains("event: " + eventName)) {
                for (String line : block.split("\n")) {
                    if (line.startsWith("data: ")) return line.substring(6);
                }
            }
        }
        return null;
    }
}
