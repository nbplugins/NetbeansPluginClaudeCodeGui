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
}
