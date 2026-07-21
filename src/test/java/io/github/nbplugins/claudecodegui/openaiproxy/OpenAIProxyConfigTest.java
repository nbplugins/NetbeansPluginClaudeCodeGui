package io.github.nbplugins.claudecodegui.openaiproxy;

import io.github.nbplugins.claudecodegui.settings.ClaudeProfile.ProxyMode;
import io.github.nbplugins.claudecodegui.settings.ProxyConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link OpenAIProxyConfig}.
 */
class OpenAIProxyConfigTest {

    @Test
    void threeArgConstructor_buildsChatCompletionsMode() {
        OpenAIProxyConfig config = new OpenAIProxyConfig("https://api.example.com/v1", "sk-key",
                new ProxyConfiguration(ProxyMode.SYSTEM_MANAGED, "", "", ""));

        assertEquals(OpenAIProxyConfig.Mode.CHAT_COMPLETIONS, config.getMode());
        assertEquals("https://api.example.com/v1", config.getBaseUrl());
        assertEquals("sk-key", config.getApiKey());
        assertNull(config.getProfileId());
        assertNull(config.getAccessToken());
        assertNull(config.getAccountId());
    }

    @Test
    void sevenArgConstructor_buildsChatgptCodexMode() {
        OpenAIProxyConfig config = new OpenAIProxyConfig(OpenAIProxyConfig.Mode.CHATGPT_CODEX,
                "https://chatgpt.com/backend-api/codex", "access-token",
                new ProxyConfiguration(ProxyMode.SYSTEM_MANAGED, "", "", ""),
                "profile-1", "access-token", "acct-1");

        assertEquals(OpenAIProxyConfig.Mode.CHATGPT_CODEX, config.getMode());
        assertEquals("profile-1", config.getProfileId());
        assertEquals("access-token", config.getAccessToken());
        assertEquals("acct-1", config.getAccountId());
    }

    @Test
    void buildHttpClient_appliesConfiguredProxyForBothModes() {
        ProxyConfiguration customProxy = new ProxyConfiguration(
                ProxyMode.CUSTOM, "", "http://proxy.example.com:3128", "");

        OpenAIProxyConfig chatCompletions = new OpenAIProxyConfig("https://api.example.com/v1", "sk-key", customProxy);
        assertTrue(chatCompletions.buildHttpClient().proxy().isPresent());

        OpenAIProxyConfig codex = new OpenAIProxyConfig(OpenAIProxyConfig.Mode.CHATGPT_CODEX,
                "https://chatgpt.com/backend-api/codex", "", customProxy, "p", "tok", "acct");
        assertTrue(codex.buildHttpClient().proxy().isPresent());
    }

    @Test
    void buildHttpClient_noProxyMode_usesNoProxySelector() {
        ProxyConfiguration noProxy = new ProxyConfiguration(ProxyMode.NO_PROXY, "", "", "");
        OpenAIProxyConfig config = new OpenAIProxyConfig("https://api.example.com/v1", "sk-key", noProxy);
        assertEquals(java.net.http.HttpClient.Builder.NO_PROXY, config.buildHttpClient().proxy().orElseThrow());
    }

    // -------------------------------------------------------------------------
    // Usage accumulation (Session Statistics dialog)
    // -------------------------------------------------------------------------

    @Test
    void addUsage_freshConfig_noUsageRecorded() {
        OpenAIProxyConfig config = new OpenAIProxyConfig("https://api.example.com/v1", "sk-key",
                new ProxyConfiguration(ProxyMode.SYSTEM_MANAGED, "", "", ""));

        assertTrue(config.getUsageByModel().isEmpty());
        assertNull(config.getLastModel());
    }

    @Test
    void addUsage_accumulatesAcrossMultipleCallsForSameModel() {
        OpenAIProxyConfig config = new OpenAIProxyConfig("https://api.example.com/v1", "sk-key",
                new ProxyConfiguration(ProxyMode.SYSTEM_MANAGED, "", "", ""));

        config.addUsage("gpt-5.6-terra", 100, 20, 5, 0);
        config.addUsage("gpt-5.6-terra", 50, 10, 0, 15);

        OpenAIProxyConfig.UsageSnapshot usage = config.getUsageByModel().get("gpt-5.6-terra");
        assertEquals(150, usage.inputTokens());
        assertEquals(30, usage.outputTokens());
        assertEquals(5, usage.cachedTokens());
        assertEquals(15, usage.cacheWriteTokens());
        assertEquals(2, usage.requests());
        assertEquals("gpt-5.6-terra", config.getLastModel());
    }

    @Test
    void addUsage_multipleModels_trackedSeparately() {
        OpenAIProxyConfig config = new OpenAIProxyConfig("https://api.example.com/v1", "sk-key",
                new ProxyConfiguration(ProxyMode.SYSTEM_MANAGED, "", "", ""));

        config.addUsage("gpt-5.6-terra", 100, 20, 5, 0);
        config.addUsage("gpt-4o", 40, 8, 0, 0);

        assertEquals(2, config.getUsageByModel().size());
        assertEquals(100, config.getUsageByModel().get("gpt-5.6-terra").inputTokens());
        assertEquals(40, config.getUsageByModel().get("gpt-4o").inputTokens());
        assertEquals("gpt-4o", config.getLastModel());
    }

    @Test
    void addUsage_blankModel_recordedAsUnknown() {
        OpenAIProxyConfig config = new OpenAIProxyConfig("https://api.example.com/v1", "sk-key",
                new ProxyConfiguration(ProxyMode.SYSTEM_MANAGED, "", "", ""));

        config.addUsage(null, 10, 2, 0, 0);
        config.addUsage("", 5, 1, 0, 0);

        assertEquals(1, config.getUsageByModel().size());
        assertEquals(15, config.getUsageByModel().get("(unknown)").inputTokens());
    }

    // -------------------------------------------------------------------------
    // recordRequest (last-request size, for the Session Statistics dialog)
    // -------------------------------------------------------------------------

    @Test
    void recordRequest_registersModelEvenWithoutAddUsage() {
        OpenAIProxyConfig config = new OpenAIProxyConfig("https://api.example.com/v1", "sk-key",
                new ProxyConfiguration(ProxyMode.SYSTEM_MANAGED, "", "", ""));

        config.recordRequest("gpt-5.6-terra", 936, 1746582L);

        OpenAIProxyConfig.UsageSnapshot usage = config.getUsageByModel().get("gpt-5.6-terra");
        assertEquals(936, usage.lastRequestMessageCount());
        assertEquals(1746582L, usage.lastRequestSizeBytes());
        assertEquals(0, usage.requests(), "recordRequest alone must not count as a completed request");
        assertEquals("gpt-5.6-terra", config.getLastModel());
    }

    @Test
    void recordRequest_overwritesPreviousValue_notCumulative() {
        OpenAIProxyConfig config = new OpenAIProxyConfig("https://api.example.com/v1", "sk-key",
                new ProxyConfiguration(ProxyMode.SYSTEM_MANAGED, "", "", ""));

        config.recordRequest("gpt-5.6-terra", 100, 5000L);
        config.recordRequest("gpt-5.6-terra", 936, 1746582L);

        OpenAIProxyConfig.UsageSnapshot usage = config.getUsageByModel().get("gpt-5.6-terra");
        assertEquals(936, usage.lastRequestMessageCount());
        assertEquals(1746582L, usage.lastRequestSizeBytes());
    }
}
