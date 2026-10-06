package io.github.nbplugins.claudecodegui.openaiproxy;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PromptPrefixTrackerTest {

    private static JsonNode json(String s) throws Exception {
        return AnthropicToCodexTranslator.MAPPER.readTree(s);
    }

    @Test
    void firstRequest_reportedAsFirst() throws Exception {
        PromptPrefixTracker t = new PromptPrefixTracker();
        String d = t.recordAndDescribe("k", json("{\"instructions\":\"i\",\"input\":[{\"a\":1}]}"));
        assertTrue(d.startsWith("first request"), d);
    }

    @Test
    void codexShape_appendedItem_reportsStablePrefix() throws Exception {
        PromptPrefixTracker t = new PromptPrefixTracker();
        t.recordAndDescribe("k", json("{\"tools\":[1],\"instructions\":\"i\",\"input\":[{\"a\":1},{\"b\":2}]}"));
        String d = t.recordAndDescribe("k",
                json("{\"tools\":[1],\"instructions\":\"i\",\"input\":[{\"a\":1},{\"b\":2},{\"c\":3}]}"));
        assertEquals("tools=same instructions=same items_common_prefix=2/3 (previous had 2)", d);
    }

    @Test
    void codexShape_changedInstructionsAndTools_reported() throws Exception {
        PromptPrefixTracker t = new PromptPrefixTracker();
        t.recordAndDescribe("k", json("{\"tools\":[1],\"instructions\":\"i\",\"input\":[{\"a\":1}]}"));
        String d = t.recordAndDescribe("k", json("{\"tools\":[2],\"instructions\":\"j\",\"input\":[{\"x\":1}]}"));
        assertEquals("tools=CHANGED instructions=CHANGED items_common_prefix=0/1 (previous had 1)", d);
    }

    @Test
    void chatCompletionsShape_leadingSystemTreatedAsInstructions() throws Exception {
        PromptPrefixTracker t = new PromptPrefixTracker();
        t.recordAndDescribe("k", json("{\"messages\":[{\"role\":\"system\",\"content\":\"s\"},"
                + "{\"role\":\"user\",\"content\":\"u\"}]}"));
        String d = t.recordAndDescribe("k", json("{\"messages\":[{\"role\":\"system\",\"content\":\"s2\"},"
                + "{\"role\":\"user\",\"content\":\"u\"}]}"));
        assertEquals("tools=same instructions=CHANGED items_common_prefix=1/1 (previous had 1)", d);
    }

    @Test
    void differentKeys_trackedIndependently() throws Exception {
        PromptPrefixTracker t = new PromptPrefixTracker();
        t.recordAndDescribe("k1", json("{\"instructions\":\"i\",\"input\":[]}"));
        String d = t.recordAndDescribe("k2", json("{\"instructions\":\"i\",\"input\":[]}"));
        assertTrue(d.startsWith("first request"), d);
    }

    @Test
    void sameKeyDifferentModels_trackedSeparately() throws Exception {
        PromptPrefixTracker t = new PromptPrefixTracker();
        t.recordAndDescribe("k", json("{\"model\":\"main\",\"tools\":[1],\"instructions\":\"i\",\"input\":[{\"a\":1},{\"b\":2}]}"));
        // a side request on another model must neither be compared with, nor hide, the main chain
        String side = t.recordAndDescribe("k", json("{\"model\":\"side\",\"instructions\":\"s\",\"input\":[{\"x\":1}]}"));
        assertTrue(side.startsWith("first request"), side);
        String main = t.recordAndDescribe("k",
                json("{\"model\":\"main\",\"tools\":[1],\"instructions\":\"i\",\"input\":[{\"a\":1},{\"b\":2},{\"c\":3}]}"));
        assertEquals("tools=same instructions=same items_common_prefix=2/3 (previous had 2)", main);
    }
}
