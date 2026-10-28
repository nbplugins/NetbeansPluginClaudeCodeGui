package io.github.nbplugins.claudecodegui.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.LinkedBlockingQueue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The deprecated {@code getCurrentSelection} tool is no longer advertised in
 * {@code tools/list} (the prompt panel attaches the editor selection instead),
 * but calls to it must still succeed for existing callers.
 */
class NetBeansMCPHandlerToolsListTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode call(String json) throws Exception {
        String response = new NetBeansMCPHandler()
                .handleMessage(MAPPER.readTree(json), new LinkedBlockingQueue<>());
        assertNotNull(response, "handler must return a response");
        return MAPPER.readTree(response);
    }

    @Test
    void getCurrentSelectionIsNotAdvertised() throws Exception {
        JsonNode tools = call("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}")
                .path("result").path("tools");
        assertTrue(tools.isArray() && tools.size() > 0, "tools/list must return tools: " + tools);
        boolean hasOpenFile = false;
        for (JsonNode t : tools) {
            String name = t.path("name").asText();
            assertNotEquals("getCurrentSelection", name, "getCurrentSelection must not be advertised");
            if ("openFile".equals(name)) hasOpenFile = true;
        }
        assertTrue(hasOpenFile, "other tools must still be advertised");
    }

    @Test
    void getCurrentSelectionCallStillWorks() throws Exception {
        JsonNode response = call("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"getCurrentSelection\",\"arguments\":{}}}");
        assertTrue(response.has("result"), "call must still return a result: " + response);
        assertFalse(response.has("error"), "call must not fail: " + response);
    }
}
