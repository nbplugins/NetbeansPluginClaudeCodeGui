package io.github.nbplugins.claudecodegui.openaiproxy;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.nbplugins.claudecodegui.settings.ClaudeProfile.ProxyMode;
import io.github.nbplugins.claudecodegui.settings.ProxyConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link OpenAIProxyServlet}'s provider-error-to-Anthropic-error
 * translation, in particular the classification of rate-limit/quota-exhaustion
 * errors so Claude Code CLI recognizes them as {@code rate_limit_error} instead
 * of falling back to a generic {@code api_error}.
 */
class OpenAIProxyServletTest {

    @Test
    void toAnthropicError_openai429InsufficientQuota_mapsToRateLimitError() {
        String body = "{\"error\":{\"message\":\"You exceeded your current quota\","
                + "\"type\":\"insufficient_quota\",\"code\":\"insufficient_quota\"}}";

        String result = OpenAIProxyServlet.toAnthropicError(body, 429);

        assertTrue(result.contains("\"type\":\"rate_limit_error\""), result);
        assertTrue(result.contains("You exceeded your current quota"), result);
    }

    @Test
    void toAnthropicError_openai429WithoutRecognizedCode_stillMapsToRateLimitError() {
        // Any HTTP 429 should be treated as a rate limit signal, even if the
        // body's error type/code don't match a known quota/rate-limit string.
        String body = "{\"error\":{\"message\":\"Too many requests\",\"type\":\"server_error\"}}";

        String result = OpenAIProxyServlet.toAnthropicError(body, 429);

        assertTrue(result.contains("\"type\":\"rate_limit_error\""), result);
    }

    @Test
    void toAnthropicError_non429RateLimitCode_mapsToRateLimitError() {
        String body = "{\"error\":{\"message\":\"Rate limit reached\","
                + "\"type\":\"rate_limit_exceeded\",\"code\":\"rate_limit_exceeded\"}}";

        String result = OpenAIProxyServlet.toAnthropicError(body, 400);

        assertTrue(result.contains("\"type\":\"rate_limit_error\""), result);
    }

    @Test
    void toAnthropicError_unrecognizedError_passesThroughOriginalType() {
        String body = "{\"error\":{\"message\":\"Invalid request\",\"type\":\"invalid_request_error\"}}";

        String result = OpenAIProxyServlet.toAnthropicError(body, 400);

        assertTrue(result.contains("\"type\":\"invalid_request_error\""), result);
        assertFalse(result.contains("rate_limit_error"), result);
    }

    @Test
    void toAnthropicError_alreadyAnthropicFormat_passedThroughUnchanged() {
        String body = "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"busy\"}}";

        String result = OpenAIProxyServlet.toAnthropicError(body, 529);

        assertEquals(body, result);
    }

    @Test
    void toCodexAnthropicError_codexRateLimitCode_mapsToRateLimitError() {
        String body = "{\"error\":{\"code\":\"codex.rate_limits.limit_reached\","
                + "\"message\":\"The usage limit has been reached\"}}";

        String result = OpenAIProxyServlet.toCodexAnthropicError(body, 429);

        assertTrue(result.contains("\"type\":\"rate_limit_error\""), result);
        assertTrue(result.contains("ChatGPT subscription rate limit reached"), result);
    }

    @Test
    void toCodexAnthropicError_non429NonRateLimitError_fallsBackToGenericTranslation() {
        String body = "{\"detail\":\"Store must be set to false\"}";

        String result = OpenAIProxyServlet.toCodexAnthropicError(body, 400);

        assertTrue(result.contains("\"type\":\"api_error\""), result);
        assertTrue(result.contains("Store must be set to false"), result);
    }

    // -------------------------------------------------------------------------
    // extractHardLimitRetryAfterSeconds
    //
    // Confirmed empirically (claude-launch-tests/test_429_retry_headers.py) that
    // a large Retry-After value makes Claude Code CLI surface the error
    // immediately instead of retrying up to 10 times.
    // -------------------------------------------------------------------------

    @Test
    void extractHardLimitRetryAfterSeconds_codexUsageLimitReached_returnsResetsInSeconds() {
        String body = "{\"error\":{\"type\":\"usage_limit_reached\","
                + "\"message\":\"The usage limit has been reached\",\"plan_type\":\"go\","
                + "\"resets_at\":1787035344,\"eligible_promo\":null,\"resets_in_seconds\":2509946}}";

        Long result = OpenAIProxyServlet.extractHardLimitRetryAfterSeconds(body, 429);

        assertEquals(2509946L, result);
    }

    @Test
    void extractHardLimitRetryAfterSeconds_ordinaryRateLimitWithoutResetsField_returnsNull() {
        String body = "{\"error\":{\"message\":\"Rate limit reached\",\"type\":\"rate_limit_exceeded\"}}";

        Long result = OpenAIProxyServlet.extractHardLimitRetryAfterSeconds(body, 429);

        assertNull(result);
    }

    @Test
    void extractHardLimitRetryAfterSeconds_non429Status_returnsNullEvenWithResetsField() {
        String body = "{\"error\":{\"type\":\"usage_limit_reached\",\"resets_in_seconds\":2509946}}";

        Long result = OpenAIProxyServlet.extractHardLimitRetryAfterSeconds(body, 400);

        assertNull(result);
    }

    // -------------------------------------------------------------------------
    // recordUsage
    // -------------------------------------------------------------------------

    private static OpenAIProxyConfig newConfig() {
        return new OpenAIProxyConfig("https://api.example.com/v1", "sk-key",
                new ProxyConfiguration(ProxyMode.SYSTEM_MANAGED, "", "", ""));
    }

    @Test
    void recordUsage_chatCompletionsShape_accumulatesTotals() throws Exception {
        OpenAIProxyConfig config = newConfig();
        JsonNode usage = AnthropicToOpenAITranslator.MAPPER.readTree(
                "{\"prompt_tokens\":100,\"completion_tokens\":20,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":30},\"cache_write_tokens\":40}");

        OpenAIProxyServlet.recordUsage(config, "gpt-4o", usage);

        OpenAIProxyConfig.UsageSnapshot u = config.getUsageByModel().get("gpt-4o");
        assertEquals(100, u.inputTokens());
        assertEquals(20, u.outputTokens());
        assertEquals(30, u.cachedTokens());
        assertEquals(40, u.cacheWriteTokens());
        assertEquals(1, u.requests());
    }

    @Test
    void recordUsage_responsesApiShape_accumulatesTotals() throws Exception {
        OpenAIProxyConfig config = newConfig();
        JsonNode usage = AnthropicToOpenAITranslator.MAPPER.readTree(
                "{\"input_tokens\":100,\"output_tokens\":20,"
                + "\"input_tokens_details\":{\"cached_tokens\":30},\"cache_write_tokens\":40}");

        OpenAIProxyServlet.recordUsage(config, "gpt-5.6-terra", usage);

        OpenAIProxyConfig.UsageSnapshot u = config.getUsageByModel().get("gpt-5.6-terra");
        assertEquals(100, u.inputTokens());
        assertEquals(20, u.outputTokens());
        assertEquals(30, u.cachedTokens());
        assertEquals(40, u.cacheWriteTokens());
    }

    @Test
    void recordUsage_missingUsageNode_doesNotIncrementRequests() throws Exception {
        OpenAIProxyConfig config = newConfig();
        JsonNode empty = AnthropicToOpenAITranslator.MAPPER.readTree("{}");

        OpenAIProxyServlet.recordUsage(config, "gpt-4o", empty.path("usage"));

        assertTrue(config.getUsageByModel().isEmpty());
    }

    @Test
    void recordUsage_noCacheFields_defaultsToZero() throws Exception {
        OpenAIProxyConfig config = newConfig();
        JsonNode usage = AnthropicToOpenAITranslator.MAPPER.readTree(
                "{\"prompt_tokens\":5,\"completion_tokens\":2}");

        OpenAIProxyServlet.recordUsage(config, "gpt-4o", usage);

        OpenAIProxyConfig.UsageSnapshot u = config.getUsageByModel().get("gpt-4o");
        assertEquals(5, u.inputTokens());
        assertEquals(2, u.outputTokens());
        assertEquals(0, u.cachedTokens());
        assertEquals(0, u.cacheWriteTokens());
    }
}
