package io.github.nbplugins.claudecodegui.openaiproxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.nbplugins.claudecodegui.chatgptauth.ChatGptTokenManager;
import io.github.nbplugins.claudecodegui.chatgptauth.OAuthException;
import io.github.nbplugins.claudecodegui.mcp.MCPSseServer;
import io.github.nbplugins.claudecodegui.settings.ClaudeCodePreferences;
import io.github.nbplugins.claudecodegui.settings.ClaudeProfile;
import io.github.nbplugins.claudecodegui.settings.ClaudeProfileStore;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Enumeration;
import java.util.logging.Logger;

// Duration used in HttpRequest timeout

/**
 * Servlet registered at {@code /openai-proxy/*} that translates
 * Anthropic Messages API requests to OpenAI Chat Completions format
 * and translates responses back.
 *
 * <p>The URL structure is:
 * <pre>  /openai-proxy/{uuid}/v1/messages</pre>
 * where {@code uuid} identifies the session and maps to an
 * {@link OpenAIProxyConfig} stored in the shared {@link MCPSseServer}.
 *
 * <p>Both streaming and non-streaming modes are supported.
 */
public final class OpenAIProxyServlet extends HttpServlet {

    private static final Logger LOG = Logger.getLogger(OpenAIProxyServlet.class.getName());

    private final MCPSseServer mcpServer;
    private final ChatGptTokenManager tokenManager = new ChatGptTokenManager();

    // HttpClient is built per-request from the session's OpenAIProxyConfig,
    // which contains the proxy settings from the profile.

    public OpenAIProxyServlet(MCPSseServer mcpServer) {
        this.mcpServer = mcpServer;
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        // Extract UUID from path: /openai-proxy/<uuid>/v1/messages
        String pathInfo = req.getPathInfo(); // e.g. "/abc-123/v1/messages"
        if (pathInfo == null || pathInfo.length() < 2) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Missing session UUID in path");
            return;
        }
        String[] parts = pathInfo.substring(1).split("/", 2); // ["abc-123", "v1/messages"]
        String uuid = parts[0];

        OpenAIProxyConfig config = mcpServer.getOpenAIProxyConfig(uuid);
        if (config == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND,
                    "No active proxy session for UUID: " + uuid);
            return;
        }

        boolean debug = ClaudeCodePreferences.isDebugMode();

        // Read Anthropic request body
        String body = readBody(req);
        JsonNode anthropicReq;
        try {
            anthropicReq = AnthropicToOpenAITranslator.MAPPER.readTree(body);
        } catch (Exception e) {
            LOG.severe("OpenAI proxy: failed to parse Anthropic request: " + e.getMessage());
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid JSON");
            return;
        }

        boolean streaming = anthropicReq.path("stream").asBoolean(false);
        String model = anthropicReq.path("model").asText("unknown");

        if (debug) {
            StringBuilder sb = new StringBuilder("OpenAI proxy ← Anthropic request: ");
            sb.append(AnthropicToOpenAITranslator.summarizeAnthropicRequest(anthropicReq));
            sb.append(" | headers:");
            Enumeration<String> headerNames = req.getHeaderNames();
            while (headerNames.hasMoreElements()) {
                String h = headerNames.nextElement();
                sb.append(" ").append(h).append("=").append(req.getHeader(h));
            }
            LOG.info(sb.toString());
        }

        if (config.getMode() == OpenAIProxyConfig.Mode.CHATGPT_CODEX) {
            handleCodexRequest(req, resp, config, uuid, anthropicReq, model, streaming, debug);
            return;
        }

        // Translate request
        ObjectNode openaiReq = AnthropicToOpenAITranslator.translateRequest(anthropicReq);
        String openaiBody = AnthropicToOpenAITranslator.MAPPER.writeValueAsString(openaiReq);

        String targetUrl = config.getBaseUrl();
        if (!targetUrl.endsWith("/")) targetUrl += "/";
        targetUrl += "v1/chat/completions";

        if (debug) {
            LOG.info("OpenAI proxy → OpenAI request: url=" + targetUrl
                    + " Authorization=Bearer [REDACTED]"
                    + " | " + AnthropicToOpenAITranslator.summarizeOpenAIRequest(openaiReq));
        }

        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create(targetUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(openaiBody, StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(600))
                .build();

        HttpClient httpClient = config.buildHttpClient();
        if (streaming) {
            handleStreaming(httpClient, httpReq, model, resp, debug);
        } else {
            handleNonStreaming(httpClient, httpReq, model, resp, debug);
        }
    }

    private void handleNonStreaming(HttpClient httpClient, HttpRequest httpReq, String model,
                                    HttpServletResponse resp, boolean debug) throws IOException {
        HttpResponse<String> httpResp;
        try {
            httpResp = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            resp.sendError(HttpServletResponse.SC_BAD_GATEWAY, "Proxy request interrupted");
            return;
        }

        int status = httpResp.statusCode();

        if (status >= 400) {
            String errBody = httpResp.body();
            LOG.severe("OpenAI proxy: provider returned HTTP " + status + ": " + errBody);
            resp.setStatus(status);
            resp.setContentType("application/json");
            resp.getWriter().write(toAnthropicError(errBody, status));
            return;
        }

        JsonNode openaiResp;
        try {
            openaiResp = AnthropicToOpenAITranslator.MAPPER.readTree(httpResp.body());
        } catch (Exception e) {
            LOG.severe("OpenAI proxy: failed to parse provider response: " + e.getMessage());
            resp.sendError(HttpServletResponse.SC_BAD_GATEWAY, "Invalid JSON from provider");
            return;
        }

        if (debug) {
            StringBuilder sb = new StringBuilder("OpenAI proxy ← OpenAI response: status=" + status);
            httpResp.headers().map().forEach((k, v) ->
                    sb.append(" ").append(k).append("=").append(String.join(",", v)));
            sb.append(" | ").append(AnthropicToOpenAITranslator.summarizeOpenAIResponse(openaiResp));
            LOG.info(sb.toString());
        }

        ObjectNode anthropicResp = AnthropicToOpenAITranslator.translateResponse(openaiResp, model);

        if (debug) {
            LOG.info("OpenAI proxy → Anthropic response: stop_reason="
                    + anthropicResp.path("stop_reason").asText()
                    + " input_tokens=" + anthropicResp.path("usage").path("input_tokens").asInt()
                    + " output_tokens=" + anthropicResp.path("usage").path("output_tokens").asInt());
        }

        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("application/json");
        resp.getWriter().write(AnthropicToOpenAITranslator.MAPPER.writeValueAsString(anthropicResp));
    }

    private void handleStreaming(HttpClient httpClient, HttpRequest httpReq, String model,
                                 HttpServletResponse resp, boolean debug) throws IOException {
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("text/event-stream");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Cache-Control", "no-cache");
        resp.setHeader("Connection", "keep-alive");
        resp.setHeader("X-Accel-Buffering", "no");

        PrintWriter writer = resp.getWriter();

        HttpResponse<InputStream> httpResp;
        try {
            httpResp = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        int status = httpResp.statusCode();
        if (status >= 400) {
            String errBody = new String(httpResp.body().readAllBytes(), StandardCharsets.UTF_8);
            LOG.severe("OpenAI proxy: provider returned HTTP " + status + " (streaming): " + errBody);
            writer.write("event: error\ndata: " + toAnthropicError(errBody, status) + "\n\n");
            writer.flush();
            return;
        }

        AnthropicToOpenAITranslator.StreamingState state =
                new AnthropicToOpenAITranslator.StreamingState();

        int chunkCount = 0;
        boolean firstChunk = true;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(httpResp.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data: ")) {
                    String data = line.substring(6).trim();
                    if (firstChunk && debug) {
                        LOG.info("OpenAI proxy: first streaming chunk received");
                        firstChunk = false;
                    }
                    String events = state.processChunk(data);
                    if (!events.isEmpty()) {
                        writer.write(events);
                        writer.flush();
                    }
                    if (!"[DONE]".equals(data)) chunkCount++;
                }
            }
        }

        if (state.isMessageStarted() && !state.isDoneReceived()) {
            writer.write(state.buildDoneEvents());
        }

        if (debug) {
            LOG.info("OpenAI proxy: streaming done, total chunks=" + chunkCount);
        }
        writer.flush();
    }

    // -------------------------------------------------------------------------
    // ChatGPT-subscription (Codex Responses API) path
    // -------------------------------------------------------------------------

    /**
     * Handles a request for a {@link OpenAIProxyConfig.Mode#CHATGPT_CODEX} session:
     * refreshes the access token if needed, translates via
     * {@link AnthropicToCodexTranslator}, and calls the Codex Responses endpoint.
     * Model validity is not checked here — an unsupported model just gets
     * whatever error the real Codex backend returns, handled by
     * {@link #toCodexAnthropicError}.
     */
    private void handleCodexRequest(HttpServletRequest req, HttpServletResponse resp, OpenAIProxyConfig config,
            String uuid, JsonNode anthropicReq, String model, boolean streaming, boolean debug) throws IOException {

        ClaudeProfile profile = ClaudeProfileStore.findById(config.getProfileId());
        String accessToken;
        try {
            accessToken = profile != null ? tokenManager.getValidAccessToken(profile) : config.getAccessToken();
        } catch (OAuthException e) {
            LOG.warning("OpenAI proxy (Codex): token refresh failed: " + e.getMessage());
            resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            resp.setContentType("application/json");
            resp.getWriter().write("{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\""
                    + e.getMessage().replace("\"", "'") + "\"}}");
            return;
        }

        ObjectNode codexReq = AnthropicToCodexTranslator.translateRequest(anthropicReq, uuid);
        String codexBody = AnthropicToCodexTranslator.MAPPER.writeValueAsString(codexReq);

        String targetUrl = config.getBaseUrl();
        if (!targetUrl.endsWith("/")) targetUrl += "/";
        targetUrl += "responses";

        if (debug) {
            LOG.info("OpenAI proxy (Codex) → Codex request: url=" + targetUrl
                    + " Authorization=Bearer [REDACTED]"
                    + " | " + AnthropicToCodexTranslator.summarizeCodexRequest(codexReq));
        }

        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create(targetUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + accessToken)
                .header("ChatGPT-Account-Id", profile != null ? profile.getChatgptAccountId() : config.getAccountId())
                .header("User-Agent", "netbeans-plugin-claude-code-gui")
                .header("originator", "codex_cli_rs")
                .POST(HttpRequest.BodyPublishers.ofString(codexBody, StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(600))
                .build();

        HttpClient httpClient = config.buildHttpClient();
        if (streaming) {
            handleCodexStreaming(httpClient, httpReq, resp, debug);
        } else {
            // The Codex backend requires stream:true on every request body
            // (see AnthropicToCodexTranslator.translateRequest) — there is no
            // plain non-streaming request to make. When the Anthropic client
            // itself didn't ask for streaming, open the same SSE connection
            // anyway and collapse it into one response via ResponseAggregator,
            // the same way Codex CLI's own non-streaming callers do.
            handleCodexNonStreamingViaAggregatedStream(httpClient, httpReq, model, resp, debug);
        }
    }

    private void handleCodexNonStreamingViaAggregatedStream(HttpClient httpClient, HttpRequest httpReq, String model,
            HttpServletResponse resp, boolean debug) throws IOException {
        HttpResponse<InputStream> httpResp;
        try {
            httpResp = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            resp.sendError(HttpServletResponse.SC_BAD_GATEWAY, "Proxy request interrupted");
            return;
        }

        int status = httpResp.statusCode();
        if (status >= 400) {
            String errBody = new String(httpResp.body().readAllBytes(), StandardCharsets.UTF_8);
            LOG.severe("OpenAI proxy (Codex): provider returned HTTP " + status + ": " + errBody);
            resp.setStatus(status);
            resp.setContentType("application/json");
            resp.getWriter().write(toCodexAnthropicError(errBody, status));
            return;
        }

        AnthropicToCodexTranslator.ResponseAggregator aggregator =
                new AnthropicToCodexTranslator.ResponseAggregator();
        String currentEvent = null;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(httpResp.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("event: ")) {
                    currentEvent = line.substring(7).trim();
                } else if (line.startsWith("data: ") && currentEvent != null) {
                    aggregator.processEvent(currentEvent, line.substring(6).trim());
                }
            }
        }

        JsonNode codexResp = aggregator.buildCodexResponse();
        if (debug) {
            LOG.info("OpenAI proxy (Codex) ← Codex response (aggregated from stream): status=" + status
                    + " | " + AnthropicToCodexTranslator.summarizeCodexResponse(codexResp));
        }

        ObjectNode anthropicResp = AnthropicToCodexTranslator.translateResponse(codexResp, model);
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("application/json");
        resp.getWriter().write(AnthropicToCodexTranslator.MAPPER.writeValueAsString(anthropicResp));
    }

    private void handleCodexStreaming(HttpClient httpClient, HttpRequest httpReq,
            HttpServletResponse resp, boolean debug) throws IOException {
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("text/event-stream");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Cache-Control", "no-cache");
        resp.setHeader("Connection", "keep-alive");
        resp.setHeader("X-Accel-Buffering", "no");

        PrintWriter writer = resp.getWriter();

        HttpResponse<InputStream> httpResp;
        try {
            httpResp = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        int status = httpResp.statusCode();
        if (status >= 400) {
            String errBody = new String(httpResp.body().readAllBytes(), StandardCharsets.UTF_8);
            LOG.severe("OpenAI proxy (Codex): provider returned HTTP " + status + " (streaming): " + errBody);
            writer.write("event: error\ndata: " + toCodexAnthropicError(errBody, status) + "\n\n");
            writer.flush();
            return;
        }

        AnthropicToCodexTranslator.StreamingState state = new AnthropicToCodexTranslator.StreamingState();
        String currentEvent = null;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(httpResp.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("event: ")) {
                    currentEvent = line.substring(7).trim();
                } else if (line.startsWith("data: ") && currentEvent != null) {
                    String data = line.substring(6).trim();
                    String events = state.processEvent(currentEvent, data);
                    if (!events.isEmpty()) {
                        writer.write(events);
                        writer.flush();
                    }
                }
            }
        }

        if (state.isMessageStarted() && !state.isDoneReceived()) {
            writer.write(state.buildDoneEvents());
        }
        if (debug) {
            LOG.info("OpenAI proxy (Codex): streaming done");
        }
        writer.flush();
    }

    /**
     * Converts a Codex provider error body to Anthropic error JSON, with a
     * dedicated mapping for {@code codex.rate_limits.limit_reached} (Codex's
     * account-wide rate limit, shared across all clients of the upstream
     * ChatGPT account) to a clear {@code rate_limit_error}.
     */
    static String toCodexAnthropicError(String providerBody, int httpStatus) {
        try {
            JsonNode json = AnthropicToCodexTranslator.MAPPER.readTree(providerBody);
            String errorCode = json.path("error").path("code").asText("");
            if ("codex.rate_limits.limit_reached".equals(errorCode) || httpStatus == 429) {
                return "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":"
                        + "\"ChatGPT subscription rate limit reached (shared account-wide across all clients).\"}}";
            }
        } catch (Exception ignored) {}
        return toAnthropicError(providerBody, httpStatus);
    }

    /**
     * Converts a provider error body to Anthropic error JSON string.
     * Handles both Anthropic-format ({@code {"type":"error","error":{...}}})
     * and OpenAI-format ({@code {"error":{...}}}) provider responses.
     */
    static String toAnthropicError(String providerBody, int httpStatus) {
        try {
            JsonNode json = AnthropicToOpenAITranslator.MAPPER.readTree(providerBody);
            // Already Anthropic format
            if (json.has("type") && json.has("error")) {
                return providerBody;
            }
            // OpenAI format: {"error":{"message":"...","type":"..."}}
            if (json.has("error")) {
                JsonNode err = json.get("error");
                String msg  = err.path("message").asText("Provider error " + httpStatus);
                String type = err.path("type").asText("api_error");
                String escaped = msg.replace("\\", "\\\\").replace("\"", "\\\"");
                return "{\"type\":\"error\",\"error\":{\"type\":\"" + type + "\",\"message\":\"" + escaped + "\"}}";
            }
            // Codex backend format: {"detail":"..."} (e.g. "Store must be set to false")
            if (json.has("detail")) {
                String msg = json.get("detail").asText("Provider error " + httpStatus);
                String escaped = msg.replace("\\", "\\\\").replace("\"", "\\\"");
                return "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"" + escaped + "\"}}";
            }
        } catch (Exception ignored) {}
        return "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"Provider returned HTTP " + httpStatus + "\"}}";
    }

    private static String readBody(HttpServletRequest req) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = req.getReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }
}
