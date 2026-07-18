package io.github.nbplugins.claudecodegui.openaiproxy;

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

    /** Which upstream API shape {@link OpenAIProxyServlet} should translate to/from. */
    public enum Mode {
        /** Any OpenAI API-key-based provider, via the Chat Completions API. */
        CHAT_COMPLETIONS,
        /** ChatGPT Plus/Pro/Team subscription, via OpenAI's Codex Responses API. */
        CHATGPT_CODEX
    }

    private final Mode               mode;
    private final String             baseUrl;
    private final String             apiKey;
    private final ProxyConfiguration proxy;
    private final String             profileId;
    private final String             accessToken;
    private final String             accountId;

    /**
     * Creates a {@link Mode#CHAT_COMPLETIONS} config for an OpenAI API-key-based provider.
     *
     * @param baseUrl provider base URL (e.g. {@code https://api.openai.com/v1})
     * @param apiKey  provider API key, sent as {@code Authorization: Bearer <key>}
     * @param proxy   proxy settings from the profile
     */
    public OpenAIProxyConfig(String baseUrl, String apiKey, ProxyConfiguration proxy) {
        this(Mode.CHAT_COMPLETIONS, baseUrl, apiKey, proxy, null, null, null);
    }

    /**
     * Creates a config for either upstream mode.
     *
     * @param mode        which upstream API shape to use
     * @param baseUrl     upstream base URL
     * @param apiKey      provider API key ({@link Mode#CHAT_COMPLETIONS} only; blank otherwise)
     * @param proxy       proxy settings from the profile
     * @param profileId   owning profile id ({@link Mode#CHATGPT_CODEX} only; used to re-fetch
     *                    the live profile for token refresh), or {@code null}
     * @param accessToken ChatGPT OAuth access token ({@link Mode#CHATGPT_CODEX} only), or {@code null}
     * @param accountId   {@code chatgpt_account_id} claim ({@link Mode#CHATGPT_CODEX} only), or {@code null}
     */
    public OpenAIProxyConfig(Mode mode, String baseUrl, String apiKey, ProxyConfiguration proxy,
            String profileId, String accessToken, String accountId) {
        this.mode        = mode;
        this.baseUrl     = baseUrl;
        this.apiKey      = apiKey;
        this.proxy       = proxy;
        this.profileId   = profileId;
        this.accessToken = accessToken;
        this.accountId   = accountId;
    }

    /** Which upstream API shape this config targets. */
    public Mode getMode() { return mode; }

    /** Base URL of the OpenAI-compatible endpoint (e.g. {@code https://api.openai.com/v1}). */
    public String getBaseUrl() { return baseUrl; }

    /** API key sent as {@code Authorization: Bearer <key>}. */
    public String getApiKey()  { return apiKey; }

    /** Owning profile id, for {@link Mode#CHATGPT_CODEX} token-refresh lookups. */
    public String getProfileId() { return profileId; }

    /** ChatGPT OAuth access token, for {@link Mode#CHATGPT_CODEX}. */
    public String getAccessToken() { return accessToken; }

    /** {@code chatgpt_account_id} claim, sent as the {@code ChatGPT-Account-Id} header. */
    public String getAccountId() { return accountId; }

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
