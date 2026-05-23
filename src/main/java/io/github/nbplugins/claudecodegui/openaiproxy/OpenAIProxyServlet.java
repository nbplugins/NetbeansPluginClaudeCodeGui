package io.github.nbplugins.claudecodegui.openaiproxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.nbplugins.claudecodegui.mcp.MCPSseServer;
import io.github.nbplugins.claudecodegui.settings.ClaudeCodePreferences;
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

        if (debug) {
            LOG.info("OpenAI proxy: streaming done, total chunks=" + chunkCount);
        }
        writer.flush();
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
