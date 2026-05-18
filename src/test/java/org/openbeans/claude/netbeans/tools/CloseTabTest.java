package org.openbeans.claude.netbeans.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nbplugins.claudecodegui.mcp.MCPResponseBuilder;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.openbeans.claude.netbeans.tools.params.CloseTabParams;

public class CloseTabTest {

    ObjectMapper objectMapper = new ObjectMapper();
    MCPResponseBuilder responseBuilder = new MCPResponseBuilder(objectMapper);

    @Test
    public void testJSONResponse() throws JsonProcessingException, Exception {
        CloseTab tool = new CloseTab() {
            @Override
            boolean closeTopComponent(String tabName) {
                return true;
            }
        };

        JsonNode n = responseBuilder.createToolResponse(tool.run(new CloseTabParams("a name")));

        assertEquals("{\"content\":[{\"type\":\"text\",\"text\":\"TAB_CLOSED\"}]}", objectMapper.writeValueAsString(n));
    }
}
