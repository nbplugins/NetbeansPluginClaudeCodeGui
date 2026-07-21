package io.github.nbplugins.claudecodegui.openaiproxy;

import io.github.nbplugins.claudecodegui.settings.ProxyConfiguration;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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

    // Cumulative usage totals, per model, for the life of this proxy session (i.e.
    // one PTY process) — surfaced via the Session Statistics dialog. Not persisted; reset
    // to zero on every new session. Keyed by model, since a session can switch
    // models mid-conversation (via /model) and per-model breakdown is more useful
    // than one blended total. Insertion order (LinkedHashMap-backed reads via
    // ModelUsage.snapshot) reflects first-seen order, which is also recency-ish
    // since models are typically appended, not interleaved.
    private final Map<String, ModelUsage> usageByModel = new ConcurrentHashMap<>();
    private volatile String lastModel;

    /** Mutable per-model usage accumulator; not exposed directly — see {@link UsageSnapshot}. */
    private static final class ModelUsage {
        final AtomicLong inputTokens     = new AtomicLong();
        final AtomicLong outputTokens    = new AtomicLong();
        final AtomicLong cachedTokens    = new AtomicLong();
        final AtomicLong cacheWriteTokens = new AtomicLong();
        final AtomicLong requests        = new AtomicLong();
        // Not cumulative — overwritten on every request, so the Session Statistics dialog
        // can show the size of the *last* request for this model (useful for judging
        // whether an empty-response failure was caused by context/output-length limits).
        volatile int  lastRequestMessageCount = 0;
        volatile long lastRequestSizeBytes    = 0;
    }

    /**
     * Immutable snapshot of one model's cumulative usage, returned by
     * {@link #getUsageByModel()}.
     *
     * @param lastRequestMessageCount number of messages in the most recent request for
     *                                this model (not cumulative)
     * @param lastRequestSizeBytes    size in bytes of the most recent request for this
     *                                model (not cumulative)
     */
    public record UsageSnapshot(long inputTokens, long outputTokens,
                                 long cachedTokens, long cacheWriteTokens, long requests,
                                 int lastRequestMessageCount, long lastRequestSizeBytes) {
    }

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

    /**
     * Accumulates usage totals from one successful response, keyed by model.
     * Thread-safe — called from {@link OpenAIProxyServlet}'s request-handling threads.
     *
     * @param model            the model id this response was for
     * @param inputTokens      uncached input tokens billed for this response
     * @param outputTokens     output tokens generated for this response
     * @param cachedTokens     input tokens served from cache (0 if not reported)
     * @param cacheWriteTokens input tokens newly written to cache (0 if not reported)
     */
    public void addUsage(String model, long inputTokens, long outputTokens,
            long cachedTokens, long cacheWriteTokens) {
        String key = model == null || model.isBlank() ? "(unknown)" : model;
        ModelUsage usage = usageByModel.computeIfAbsent(key, k -> new ModelUsage());
        usage.inputTokens.addAndGet(inputTokens);
        usage.outputTokens.addAndGet(outputTokens);
        usage.cachedTokens.addAndGet(cachedTokens);
        usage.cacheWriteTokens.addAndGet(cacheWriteTokens);
        usage.requests.incrementAndGet();
        lastModel = key;
    }

    /**
     * Records the size of a request about to be sent for {@code model}, so the
     * Session Statistics dialog can show it even if the request ultimately fails or
     * returns an empty response (i.e. before {@link #addUsage} would otherwise
     * be the only place a model gets registered). Thread-safe. Overwrites any
     * previously recorded size for the same model — this is "last", not cumulative.
     *
     * @param model            the model id this request is for
     * @param messageCount     number of messages in the request
     * @param requestSizeBytes size of the (translated, upstream-format) request body in bytes
     */
    public void recordRequest(String model, int messageCount, long requestSizeBytes) {
        String key = model == null || model.isBlank() ? "(unknown)" : model;
        ModelUsage usage = usageByModel.computeIfAbsent(key, k -> new ModelUsage());
        usage.lastRequestMessageCount = messageCount;
        usage.lastRequestSizeBytes = requestSizeBytes;
        lastModel = key;
    }

    /**
     * Returns the model id of the most recently recorded usage, or {@code null}
     * if no usage has been recorded yet.
     */
    public String getLastModel() { return lastModel; }

    /**
     * Returns a snapshot of cumulative usage per model, in first-seen order.
     *
     * @return unmodifiable map, model id &rarr; usage snapshot; empty if nothing recorded yet
     */
    public Map<String, UsageSnapshot> getUsageByModel() {
        Map<String, UsageSnapshot> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, ModelUsage> e : usageByModel.entrySet()) {
            ModelUsage u = e.getValue();
            snapshot.put(e.getKey(), new UsageSnapshot(
                    u.inputTokens.get(), u.outputTokens.get(),
                    u.cachedTokens.get(), u.cacheWriteTokens.get(), u.requests.get(),
                    u.lastRequestMessageCount, u.lastRequestSizeBytes));
        }
        return Collections.unmodifiableMap(snapshot);
    }
}
