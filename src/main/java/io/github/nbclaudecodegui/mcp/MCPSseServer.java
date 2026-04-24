// Originally forked from https://github.com/emilianbold/claude-code-netbeans
// Original: src/main/java/org/openbeans/claude/netbeans/MCPSseServer.java
package io.github.nbclaudecodegui.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * HTTP server providing MCP SSE transport for Claude Code CLI.
 *
 * <p>Implements the MCP SSE transport (protocol 2024-11-05):
 * <ul>
 *   <li>{@code GET /sse}       — establishes the SSE stream; sends an
 *       {@code event:endpoint} frame so the client knows where to POST,
 *       then streams all server→client messages (responses + notifications).</li>
 *   <li>{@code POST /messages} — receives JSON-RPC requests from Claude,
 *       returns {@code 202 Accepted}, and routes the response via the SSE
 *       stream (not the HTTP response body).</li>
 * </ul>
 *
 * <p>Verified by Python integration test {@code test_mcp_sse_transport.py}:
 * only variant A (responses via SSE stream) results in Claude showing
 * {@code connected}; variant B (responses in POST body) shows {@code failed}.
 */
public class MCPSseServer {

    private static final Logger LOGGER = Logger.getLogger(MCPSseServer.class.getName());

    private Server server;
    private int port;
    private final NetBeansMCPHandler mcpHandler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Per-session queues keyed by sessionId (UUID).
     * Each SSE client gets its own queue so responses are never stolen by
     * a concurrent session.
     */
    private final ConcurrentHashMap<String, BlockingQueue<String>> sessionQueues =
            new ConcurrentHashMap<>();

    /**
     * Creates a new MCPSseServer backed by the given MCP handler.
     *
     * @param mcpHandler the handler that processes incoming MCP requests
     */
    public MCPSseServer(NetBeansMCPHandler mcpHandler) {
        this.mcpHandler = mcpHandler;
    }

    /**
     * Starts the HTTP/SSE server on the specified port.
     * Fails immediately if the port is already in use.
     *
     * @param port the port to listen on
     * @return {@code true} if the server started successfully
     */
    public boolean start(int port) {
        try (ServerSocket test = new ServerSocket(port)) {
            // port is free — close probe socket and let Jetty bind it
        } catch (Exception e) {
            LOGGER.severe("MCP port " + port + " is busy: " + e.getMessage());
            return false;
        }
        try {
            this.port = port;

            server = new Server();
            ServerConnector connector = new ServerConnector(server);
            connector.setPort(port);
            server.addConnector(connector);

            ServletContextHandler ctx =
                    new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
            ctx.setContextPath("/");
            ctx.addServlet(new ServletHolder(new SseServlet()), "/sse");
            ctx.addServlet(new ServletHolder(new MessagesServlet()), "/messages");
            ServletHolder hookHolder = new ServletHolder(new HookServlet());
            hookHolder.getRegistration().setAsyncSupported(true);
            ctx.addServlet(hookHolder, "/hook");
            ctx.addServlet(new ServletHolder(new StopServlet()), "/stop");
            ctx.addServlet(new ServletHolder(new PermissionRequestServlet()), "/permission-request");

            server.setHandler(ctx);
            server.setRequestLog((request, response) ->
                LOGGER.log(Level.FINE, "Jetty: {0} {1} → {2}",
                    new Object[]{request.getMethod(), request.getRequestURI(),
                                 response.getCommittedMetaData().getStatus()}));
            server.start();

            mcpHandler.setBroadcaster(this::broadcast);

            LOGGER.log(Level.INFO, "MCP SSE server started on port {0}", port);
            return true;

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to start MCP SSE server", e);
            return false;
        }
    }

    /** Stops the server. */
    public void stop() {
        if (server != null && server.isStarted()) {
            try {
                server.stop();
                LOGGER.info("MCP SSE server stopped");
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error stopping MCP SSE server", e);
            }
        }
    }

    /**
     * Returns the port this server listens on.
     *
     * @return the port number
     */
    public int getPort() { return port; }

    /**
     * Returns {@code true} while the Jetty server is running.
     *
     * @return {@code true} if the server is currently started
     */
    public boolean isRunning() { return server != null && server.isStarted(); }

    /** Returns the number of currently active SSE sessions (for testing). */
    int getSessionCount() { return sessionQueues.size(); }

    /**
     * Broadcasts {@code msg} to every active SSE session.
     * Used for notifications (selection_changed, notifications/initialized)
     * that are relevant to all connected Claude processes.
     */
    void broadcast(String msg) {
        for (BlockingQueue<String> q : sessionQueues.values()) {
            if (!q.offer(msg)) {
                LOGGER.warning("SSE session queue full; broadcast message dropped");
            }
        }
    }

    // -------------------------------------------------------------------------
    // POST /hook  (PreToolUse HTTP hook)
    // -------------------------------------------------------------------------

    /**
     * Receives PreToolUse hook calls from Claude Code CLI using Jetty async dispatch.
     *
     * <p>The Jetty thread is released immediately after reading the request body.
     * {@link jakarta.servlet.AsyncContext} is used to hold the HTTP connection open
     * while the user decides in the diff-view tab (which may take up to 590 s).
     * The response is written by {@code future.whenComplete()} on whichever thread
     * resolves the {@link CompletableFuture} — typically the EDT when the user
     * clicks Accept/Reject/Cancel in {@code FileDiffTab}.
     *
     * <p>This prevents Jetty thread-pool exhaustion: previously each pending diff
     * dialog blocked one Jetty thread for up to 590 s.  With many concurrent Write
     * hooks (e.g. claude writing multiple files at once) and a Stop hook firing
     * after every response, the thread pool could be saturated, causing HTTP 502.
     *
     * <p>Async support is enabled on the {@link ServletHolder} in {@link #start}.
     * The async timeout is set to 590 000 ms (just under Claude's 600 s hook
     * timeout); on expiry the servlet container calls {@code onTimeout} and we
     * return {@code "ask"} so Claude falls back to its built-in PTY dialog.
     */
    private class HookServlet extends HttpServlet {

        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp)
                throws IOException {

            byte[] bodyBytes;
            try (InputStream in = req.getInputStream()) {
                bodyBytes = in.readAllBytes();
            }
            String body = new String(bodyBytes, StandardCharsets.UTF_8);
            LOGGER.log(Level.INFO, "PreToolUse hook received: {0}", body);

            jakarta.servlet.AsyncContext asyncCtx = req.startAsync(req, resp);
            asyncCtx.setTimeout(590_000L);

            CompletableFuture<String> future;
            try {
                future = mcpHandler.handlePreToolUse(body);
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error starting PreToolUse hook handler", e);
                asyncCtx.complete();
                return;
            }

            // Timeout: resolve with ASK_JSON so Claude falls back to its PTY dialog
            asyncCtx.addListener(new jakarta.servlet.AsyncListener() {
                @Override public void onTimeout(jakarta.servlet.AsyncEvent ev) {
                    LOGGER.warning("PreToolUse hook async timeout; returning 'ask'");
                    future.complete(ASK_JSON);
                }
                @Override public void onError(jakarta.servlet.AsyncEvent ev) {
                    future.complete(ASK_JSON);
                }
                @Override public void onComplete(jakarta.servlet.AsyncEvent ev) {}
                @Override public void onStartAsync(jakarta.servlet.AsyncEvent ev) {}
            });

            future.whenComplete((responseJson, ex) -> {
                if (ex != null || responseJson == null) {
                    responseJson = ASK_JSON;
                }
                try {
                    HttpServletResponse r = (HttpServletResponse) asyncCtx.getResponse();
                    byte[] bytes = responseJson.getBytes(StandardCharsets.UTF_8);
                    r.setStatus(HttpServletResponse.SC_OK);
                    r.setContentType("application/json");
                    r.setContentLength(bytes.length);
                    r.getOutputStream().write(bytes);
                    r.getOutputStream().flush();
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Error writing async hook response", e);
                } finally {
                    asyncCtx.complete();
                }
            });
            // Jetty thread is released here — no blocking
        }

    }

    private static final String ASK_JSON =
            "{\"hookSpecificOutput\":{\"hookEventName\":\"PreToolUse\",\"permissionDecision\":\"ask\"}}";

    // -------------------------------------------------------------------------
    // POST /stop  (Stop hook)
    // -------------------------------------------------------------------------

    private class StopServlet extends HttpServlet {
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp)
                throws IOException {
            byte[] bodyBytes;
            try (InputStream in = req.getInputStream()) { bodyBytes = in.readAllBytes(); }
            String body = new String(bodyBytes, StandardCharsets.UTF_8);
            LOGGER.log(Level.FINE, "Stop hook received: {0}", body);
            mcpHandler.handleStop(body);
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.setContentType("application/json");
            resp.getOutputStream().write("{}".getBytes(StandardCharsets.UTF_8));
            resp.getOutputStream().flush();
        }
    }

    // -------------------------------------------------------------------------
    // POST /permission-request  (PermissionRequest hook)
    // -------------------------------------------------------------------------

    private class PermissionRequestServlet extends HttpServlet {
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp)
                throws IOException {
            byte[] bodyBytes;
            try (InputStream in = req.getInputStream()) { bodyBytes = in.readAllBytes(); }
            String body = new String(bodyBytes, StandardCharsets.UTF_8);
            LOGGER.log(Level.FINE, "PermissionRequest hook received: {0}", body);
            mcpHandler.handlePermissionRequest(body);
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.setContentType("application/json");
            resp.getOutputStream().write("{}".getBytes(StandardCharsets.UTF_8));
            resp.getOutputStream().flush();
        }
    }

    // -------------------------------------------------------------------------
    // GET /sse
    // -------------------------------------------------------------------------

    /**
     * Long-lived SSE endpoint.
     *
     * <ol>
     *   <li>Sends {@code event:endpoint\ndata:/messages\n\n} so Claude knows
     *       where to POST JSON-RPC requests.</li>
     *   <li>Blocks on the per-session queue, writing each item as a
     *       {@code data:} frame until the client disconnects.</li>
     * </ol>
     */
    private class SseServlet extends HttpServlet {

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws IOException {

            resp.setContentType("text/event-stream");
            resp.setCharacterEncoding("UTF-8");
            resp.setHeader("Cache-Control", "no-cache");
            resp.setHeader("Connection", "keep-alive");
            resp.flushBuffer();

            String sessionId = UUID.randomUUID().toString();
            BlockingQueue<String> myQueue = new LinkedBlockingQueue<>(1000);
            sessionQueues.put(sessionId, myQueue);

            PrintWriter writer = resp.getWriter();
            writeEvent(writer, "endpoint", "/messages?sessionId=" + sessionId);

            LOGGER.log(Level.INFO, "SSE client connected from {0}, sessionId={1}",
                    new Object[]{req.getRemoteAddr(), sessionId});

            try {
                while (!writer.checkError()) {
                    String msg = myQueue.poll(5, TimeUnit.SECONDS);
                    if (msg == null) {
                        writer.write(": ping\n\n");
                        writer.flush();
                    } else {
                        writeEvent(writer, "message", msg);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                sessionQueues.remove(sessionId);
                LOGGER.log(Level.INFO, "SSE client disconnected, sessionId={0}", sessionId);
            }
        }

        private void writeEvent(PrintWriter w, String event, String data) {
            w.write("event: " + event + "\n");
            w.write("data: " + data + "\n\n");
            w.flush();
        }
    }

    // -------------------------------------------------------------------------
    // POST /messages
    // -------------------------------------------------------------------------

    /**
     * Accepts JSON-RPC requests from Claude Code, routes them to
     * {@link NetBeansMCPHandler}, and enqueues any response for the SSE stream.
     *
     * <p>Per the MCP SSE spec the HTTP response is always {@code 202 Accepted}
     * with an empty body; the actual JSON-RPC response is sent via SSE.
     */
    private class MessagesServlet extends HttpServlet {

        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp)
                throws IOException {

            String sessionId = req.getParameter("sessionId");
            BlockingQueue<String> sessionQueue = sessionId != null
                    ? sessionQueues.get(sessionId) : null;

            if (sessionQueue == null) {
                LOGGER.warning("MCP POST with unknown sessionId: " + sessionId);
                resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                resp.flushBuffer();
                return;
            }

            resp.setStatus(HttpServletResponse.SC_ACCEPTED);
            resp.flushBuffer();

            try {
                JsonNode message = objectMapper.readTree(req.getInputStream());
                LOGGER.log(Level.FINE, "MCP request (session {0}): {1}",
                        new Object[]{sessionId, message});

                String response = mcpHandler.handleMessage(message, sessionQueue);
                if (response != null) {
                    if (!sessionQueue.offer(response)) {
                        LOGGER.warning("SSE session queue full; MCP response dropped");
                    }
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error handling MCP POST", e);
            }
        }
    }

}
