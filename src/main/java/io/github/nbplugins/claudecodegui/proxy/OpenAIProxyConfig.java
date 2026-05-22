package io.github.nbplugins.claudecodegui.proxy;

import io.github.nbplugins.claudecodegui.settings.ProxyConfiguration;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Snapshot of OpenAI-compatible endpoint configuration taken at session start.
 *
 * <p>Immutable after construction. Passed to {@link OpenAIProxyServlet} via
 * {@link io.github.nbplugins.claudecodegui.mcp.MCPSseServer} registry.
 * Changes to the profile after session start do not affect a running session.
 */
public final class OpenAIProxyConfig {

    private final String             baseUrl;
    private final String             apiKey;
    private final ProxyConfiguration proxy;

    public OpenAIProxyConfig(String baseUrl, String apiKey, ProxyConfiguration proxy) {
        this.baseUrl = baseUrl;
        this.apiKey  = apiKey;
        this.proxy   = proxy;
    }

    /** Base URL of the OpenAI-compatible endpoint (e.g. {@code https://api.openai.com/v1}). */
    public String getBaseUrl() { return baseUrl; }

    /** API key sent as {@code Authorization: Bearer <key>}. */
    public String getApiKey()  { return apiKey; }

    /**
     * Builds an {@link HttpClient} configured with the proxy settings from this config.
     * See {@link ProxyConfiguration#applyTo} for details.
     */
    public HttpClient buildHttpClient() {
        return proxy.applyTo(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30))
        ).build();
    }
}
