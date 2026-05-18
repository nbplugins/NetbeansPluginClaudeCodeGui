package org.openbeans.claude.netbeans.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nbplugins.claudecodegui.mcp.MCPResponseBuilder;
import org.openbeans.claude.netbeans.tools.params.CloseAllDiffTabsParams;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class CloseAllDiffTabsTest {

    ObjectMapper objectMapper = new ObjectMapper();
    MCPResponseBuilder responseBuilder = new MCPResponseBuilder(objectMapper);

    @Test
    public void testJSONResponse() throws JsonProcessingException {
        CloseAllDiffTabs tool = new CloseAllDiffTabs() {
            @Override
            int closeAllDiffTabs() {
                return 1;
            }
        };

        JsonNode n = responseBuilder.createToolResponse(tool.run(new CloseAllDiffTabsParams()));

        assertEquals("{\"content\":[{\"type\":\"text\",\"text\":\"CLOSED_1_DIFF_TABS\"}]}", objectMapper.writeValueAsString(n));
    }

    @Test
    public void testJSONResponse_zero() throws JsonProcessingException {
        CloseAllDiffTabs tool = new CloseAllDiffTabs() {
            @Override
            int closeAllDiffTabs() {
                return 0;
            }
        };

        JsonNode n = responseBuilder.createToolResponse(tool.run(new CloseAllDiffTabsParams()));

        assertEquals("{\"content\":[{\"type\":\"text\",\"text\":\"CLOSED_0_DIFF_TABS\"}]}", objectMapper.writeValueAsString(n));
    }
}
