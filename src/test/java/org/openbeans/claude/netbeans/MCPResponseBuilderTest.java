package org.openbeans.claude.netbeans;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.nbplugins.claudecodegui.mcp.MCPResponseBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class MCPResponseBuilderTest {

    private ObjectMapper objectMapper;
    private MCPResponseBuilder builder;

    @BeforeEach
    public void setUp() {
        objectMapper = new ObjectMapper();
        builder = new MCPResponseBuilder(objectMapper);
    }

    @Test
    public void testCreateToolResponse_text() throws Exception {
        ObjectNode result = builder.createToolResponse("hello");
        String json = objectMapper.writeValueAsString(result);
        assertEquals("{\"content\":[{\"type\":\"text\",\"text\":\"hello\"}]}", json);
    }

    @Test
    public void testCreateMultiPartToolResponse() throws Exception {
        ObjectNode result = builder.createMultiPartToolResponse("foo", "bar");
        JsonNode content = result.get("content");
        assertEquals(2, content.size());
        assertEquals("foo", content.get(0).get("text").asText());
        assertEquals("bar", content.get(1).get("text").asText());
    }

    @Test
    public void testCreateErrorResponse_withId() throws Exception {
        String json = builder.createErrorResponse(42, -32600, "Invalid Request", null);
        JsonNode node = objectMapper.readTree(json);
        assertEquals("2.0", node.get("jsonrpc").asText());
        assertEquals(42, node.get("id").asInt());
        assertEquals(-32600, node.get("error").get("code").asInt());
        assertEquals("Invalid Request", node.get("error").get("message").asText());
        assertFalse(node.get("error").has("data"));
    }

    @Test
    public void testCreateErrorResponse_withData() throws Exception {
        String json = builder.createErrorResponse(1, -32601, "Method not found", "unknown method");
        JsonNode node = objectMapper.readTree(json);
        assertEquals("unknown method", node.get("error").get("data").asText());
    }

    @Test
    public void testCreateErrorResponse_nullId() throws Exception {
        String json = builder.createErrorResponse(null, -32603, "Internal error", null);
        JsonNode node = objectMapper.readTree(json);
        assertFalse(node.has("id"));
    }

    @Test
    public void testCreateResponse() throws Exception {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("foo", "bar");
        ObjectNode response = builder.createResponse(7, result);
        assertEquals("2.0", response.get("jsonrpc").asText());
        assertEquals(7, response.get("id").asInt());
        assertEquals("bar", response.get("result").get("foo").asText());
    }

    @Test
    public void testCreateNotification() throws Exception {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("pid", 1234);
        ObjectNode notification = builder.createNotification("ide_connected", params);
        assertEquals("2.0", notification.get("jsonrpc").asText());
        assertEquals("ide_connected", notification.get("method").asText());
        assertEquals(1234, notification.get("params").get("pid").asInt());
        assertFalse(notification.has("id"));
    }

    @Test
    public void testCreateNotification_nullParams() throws Exception {
        ObjectNode notification = builder.createNotification("ping", null);
        assertFalse(notification.has("params"));
    }

    @Test
    public void testCreateToolResponse_object_wrapsInContentBlock() throws Exception {
        // Simulates what GetWorkspaceFolders returns
        ObjectNode data = objectMapper.createObjectNode();
        data.set("folders", objectMapper.createArrayNode());

        JsonNode result = builder.createToolResponse((Object) data);

        // Must have MCP content format, not raw object
        assertTrue(result.has("content"), "result must have 'content' field");
        JsonNode content = result.get("content");
        assertEquals(1, content.size());
        assertEquals("text", content.get(0).get("type").asText());
        assertTrue(content.get(0).get("text").asText().contains("folders"));
    }
}
