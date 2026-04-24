package io.github.nbclaudecodegui.mcp;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TC: MCPSseServer must route /messages responses only to the originating SSE session.
 *
 * <p>Root-cause regression test for the "1 MCP server failed" bug: with a single
 * shared queue, session B could steal the initialize/tools-list response intended
 * for session A, leaving session A without a response.
 *
 * <p>Fix: per-session queues keyed by {@code sessionId}.  The endpoint event sent
 * to each SSE client contains {@code /messages?sessionId=<uuid>}; the client uses
 * that URL for all subsequent POSTs; the server routes the response exclusively to
 * the matching session queue.
 */
public class MCPSseServerPerSessionTest {

    private MCPSseServer server;
    private int port;

    @BeforeEach
    void startServer() throws Exception {
        port = findFreePort();
        server = new MCPSseServer(new NetBeansMCPHandler());
        assertTrue(server.start(port), "server must start");
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    // -----------------------------------------------------------------------
    // TC-1: endpoint event contains sessionId
    // -----------------------------------------------------------------------

    /**
     * GET /sse must send an {@code event:endpoint} whose data contains
     * {@code /messages?sessionId=} so the client knows which URL to POST to.
     */
    @Test
    void testEndpointEventContainsSessionId() throws Exception {
        AtomicReference<String> endpointData = new AtomicReference<>();
        CountDownLatch received = new CountDownLatch(1);

        Thread sseThread = new Thread(() -> {
            try {
                URL url = new URL("http://localhost:" + port + "/sse");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("Accept", "text/event-stream");
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(5000);

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("data:")) {
                            endpointData.set(line.substring(5).trim());
                            received.countDown();
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                // timeout — latch never counted down
            }
        });
        sseThread.setDaemon(true);
        sseThread.start();

        assertTrue(received.await(4, TimeUnit.SECONDS), "endpoint event not received");
        String data = endpointData.get();
        assertNotNull(data, "endpoint data must not be null");
        assertTrue(data.contains("/messages?sessionId="),
                "endpoint data must contain '/messages?sessionId=', got: " + data);
    }

    // -----------------------------------------------------------------------
    // TC-2: two sessions each receive only their own response
    // -----------------------------------------------------------------------

    /**
     * Two concurrent SSE sessions post {@code tools/list} requests.
     * Each session must receive exactly its own response, not the other's.
     */
    @Test
    void testTwoSessionsDoNotStealEachOthersResponse() throws Exception {
        SseClient sessionA = new SseClient();
        SseClient sessionB = new SseClient();

        sessionA.connect(port);
        sessionB.connect(port);

        // Both wait for their endpoint event
        assertTrue(sessionA.awaitEndpoint(4), "session A endpoint timeout");
        assertTrue(sessionB.awaitEndpoint(4), "session B endpoint timeout");

        String urlA = sessionA.messagesUrl;
        String urlB = sessionB.messagesUrl;

        assertNotEquals(urlA, urlB, "each session must get a distinct messages URL");

        // Session A posts tools/list (id=1), session B posts tools/list (id=2)
        // The responses should each land in the correct SSE stream.
        sessionA.startCollecting();
        sessionB.startCollecting();

        postMessage(urlA, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}");
        postMessage(urlB, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");

        // Give the server time to process
        Thread.sleep(500);

        List<String> messagesA = sessionA.collectedMessages();
        List<String> messagesB = sessionB.collectedMessages();

        // Session A must have id=1, NOT id=2
        assertTrue(messagesA.stream().anyMatch(m -> m.contains("\"id\":1")),
                "session A must receive response with id=1; got: " + messagesA);
        assertFalse(messagesA.stream().anyMatch(m -> m.contains("\"id\":2")),
                "session A must NOT receive session B's response (id=2); got: " + messagesA);

        // Session B must have id=2, NOT id=1
        assertTrue(messagesB.stream().anyMatch(m -> m.contains("\"id\":2")),
                "session B must receive response with id=2; got: " + messagesB);
        assertFalse(messagesB.stream().anyMatch(m -> m.contains("\"id\":1")),
                "session B must NOT receive session A's response (id=1); got: " + messagesB);
    }

    // -----------------------------------------------------------------------
    // TC-3: unknown sessionId returns 400
    // -----------------------------------------------------------------------

    /**
     * Posting to {@code /messages?sessionId=nonexistent} must return 4xx
     * (or at minimum not crash the server).
     */
    @Test
    void testPostWithUnknownSessionIdReturnsError() throws Exception {
        URL url = new URL("http://localhost:" + port + "/messages?sessionId=nonexistent-uuid");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(3000);

        byte[] body = "{\"jsonrpc\":\"2.0\",\"id\":99,\"method\":\"tools/list\",\"params\":{}}"
                .getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(body);
        }

        int status = conn.getResponseCode();
        // Must NOT be 202 (which would imply it was accepted and silently dropped).
        // Acceptable codes: 400, 404, 410.
        assertNotEquals(HttpURLConnection.HTTP_ACCEPTED, status,
                "posting to unknown sessionId must not return 202 Accepted");
    }

    // -----------------------------------------------------------------------
    // TC-4: session count tracked correctly
    // -----------------------------------------------------------------------

    /**
     * {@code getSessionCount()} must reflect connected sessions.
     */
    @Test
    void testSessionCountTracked() throws Exception {
        assertEquals(0, server.getSessionCount(), "no sessions initially");

        SseClient sessionA = new SseClient();
        SseClient sessionB = new SseClient();
        sessionA.connect(port);
        sessionB.connect(port);

        assertTrue(sessionA.awaitEndpoint(4), "session A endpoint timeout");
        assertTrue(sessionB.awaitEndpoint(4), "session B endpoint timeout");

        assertEquals(2, server.getSessionCount(), "two sessions after both connect");

        sessionA.disconnect();
        sessionB.disconnect();
    }

    // -----------------------------------------------------------------------
    // TC-5: HookServlet async — Jetty thread not blocked while future is pending
    // -----------------------------------------------------------------------

    /**
     * A POST to {@code /hook} must return (HTTP connection must be completed)
     * only AFTER the CompletableFuture is resolved, but the Jetty worker thread
     * must NOT be held during the wait.
     *
     * <p>The test verifies async behaviour indirectly: a second concurrent request
     * to a different endpoint ({@code /stop}) must be served while the hook future
     * is still pending.  If the servlet were synchronous, the single Jetty thread
     * handling the hook would be blocked and the /stop request would time out.
     */
    @Test
    void testHookServletAsyncDoesNotBlockThread() throws Exception {
        CompletableFuture<String> pendingFuture = new CompletableFuture<>();
        MCPSseServer hookServer = startWithHandler(new NetBeansMCPHandler() {
            @Override
            public CompletableFuture<String> handlePreToolUse(String hookJson) {
                return pendingFuture;
            }
        });
        try {
            int hookPort = hookServer.getPort();

            // Fire the hook request — it should NOT block the Jetty thread
            CountDownLatch hookDone = new CountDownLatch(1);
            AtomicInteger hookStatus = new AtomicInteger(-1);
            new Thread(() -> {
                try {
                    hookStatus.set(postToHook(hookPort, "{}"));
                } catch (Exception e) { /* ignore */ }
                hookDone.countDown();
            }).start();

            // While hook is pending, /stop must still be served within 2 seconds
            int stopStatus = postToStop(hookPort, "{}");
            assertEquals(200, stopStatus, "/stop must return 200 while hook is pending");

            // Now resolve the future and verify hook response is delivered
            pendingFuture.complete("{\"hookSpecificOutput\":{\"hookEventName\":\"PreToolUse\",\"permissionDecision\":\"allow\"}}");
            assertTrue(hookDone.await(5, TimeUnit.SECONDS), "hook response not delivered after future resolved");
            assertEquals(200, hookStatus.get(), "/hook must return 200 after future resolved");
        } finally {
            hookServer.stop();
        }
    }

    // -----------------------------------------------------------------------
    // TC-6: N concurrent hook requests — all served without 502
    // -----------------------------------------------------------------------

    /**
     * Sends N concurrent {@code /hook} requests (each backed by a pending
     * {@link CompletableFuture}) and verifies that ALL return HTTP 200 after
     * their futures are resolved.  This is the regression test for thread-pool
     * exhaustion: the synchronous implementation would have blocked N Jetty
     * threads; if N exceeded the pool size some requests would have returned 502.
     */
    @Test
    void testConcurrentHookRequestsAllServed() throws Exception {
        int n = 10;
        List<CompletableFuture<String>> futures = new ArrayList<>();
        MCPSseServer hookServer = startWithHandler(new NetBeansMCPHandler() {
            @Override
            public CompletableFuture<String> handlePreToolUse(String hookJson) {
                CompletableFuture<String> f = new CompletableFuture<>();
                synchronized (futures) { futures.add(f); }
                return f;
            }
        });
        String allowJson = "{\"hookSpecificOutput\":{\"hookEventName\":\"PreToolUse\",\"permissionDecision\":\"allow\"}}";
        try {
            int hookPort = hookServer.getPort();
            ExecutorService pool = Executors.newFixedThreadPool(n);
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                results.add(pool.submit(() -> postToHook(hookPort, "{}")));
            }
            pool.shutdown();

            // Wait until all N futures are registered
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                synchronized (futures) { if (futures.size() == n) break; }
                Thread.sleep(50);
            }
            synchronized (futures) {
                assertEquals(n, futures.size(), n + " futures must be registered");
                futures.forEach(f -> f.complete(allowJson));
            }

            // All N responses must be 200
            AtomicInteger failures = new AtomicInteger(0);
            for (Future<Integer> r : results) {
                int status = r.get(10, TimeUnit.SECONDS);
                if (status != 200) failures.incrementAndGet();
            }
            assertEquals(0, failures.get(), "all " + n + " hook requests must return 200");
        } finally {
            hookServer.stop();
        }
    }

    // -----------------------------------------------------------------------
    // TC-7: POST /stop returns 200 with {} body
    // -----------------------------------------------------------------------

    /**
     * A well-formed {@code POST /stop} must return {@code 200 OK} with body
     * {@code {}}.  Regression guard for a missing {@code flush()} call.
     */
    @Test
    void testStopHookReturns200() throws Exception {
        String payload = "{\"hook_event_name\":\"Stop\",\"cwd\":\"/tmp\",\"session_id\":\"abc\"}";
        int status = postToStop(port, payload);
        assertEquals(200, status, "/stop must return 200");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Starts a fresh MCPSseServer on a free port backed by the given handler. */
    private MCPSseServer startWithHandler(NetBeansMCPHandler handler) throws Exception {
        int p = findFreePort();
        MCPSseServer s = new MCPSseServer(handler);
        assertTrue(s.start(p), "server with custom handler must start");
        return s;
    }

    private int postToHook(int hookPort, String json) throws Exception {
        URL url = new URL("http://localhost:" + hookPort + "/hook");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(15000);
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = conn.getOutputStream()) { out.write(body); }
        return conn.getResponseCode();
    }

    private int postToStop(int stopPort, String json) throws Exception {
        URL url = new URL("http://localhost:" + stopPort + "/stop");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(5000);
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = conn.getOutputStream()) { out.write(body); }
        return conn.getResponseCode();
    }

    private void postMessage(String messagesUrl, String json) throws Exception {
        URL url = new URL(messagesUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(3000);
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(body);
        }
        conn.getResponseCode(); // ensure request is sent
    }

    private static int findFreePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    /**
     * Minimal SSE client that connects to GET /sse, extracts the endpoint URL
     * from the first {@code event:endpoint} frame, then optionally collects
     * subsequent {@code event:message} frames.
     */
    private static class SseClient {

        volatile String messagesUrl;
        private volatile boolean collecting = false;
        private final List<String> messages = new ArrayList<>();
        private volatile Thread thread;
        private volatile HttpURLConnection conn;
        private final CountDownLatch endpointLatch = new CountDownLatch(1);

        void connect(int port) {
            thread = new Thread(() -> {
                try {
                    URL url = new URL("http://localhost:" + port + "/sse");
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestProperty("Accept", "text/event-stream");
                    conn.setConnectTimeout(3000);
                    conn.setReadTimeout(10000);

                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (line.startsWith("event:") || line.startsWith("event: ")) {
                                String eventType = line.replaceFirst("event:\\s*", "").trim();
                                String dataLine = reader.readLine(); // next line is data:
                                if (dataLine != null && dataLine.startsWith("data:")) {
                                    String data = dataLine.substring(5).trim();
                                    if ("endpoint".equals(eventType)) {
                                        messagesUrl = "http://localhost:" + port + data;
                                        endpointLatch.countDown();
                                    } else if ("message".equals(eventType) && collecting) {
                                        synchronized (messages) {
                                            messages.add(data);
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    // disconnect or timeout
                    endpointLatch.countDown(); // unblock if we were waiting
                }
            });
            thread.setDaemon(true);
            thread.start();
        }

        boolean awaitEndpoint(int timeoutSeconds) throws InterruptedException {
            return endpointLatch.await(timeoutSeconds, TimeUnit.SECONDS) && messagesUrl != null;
        }

        void startCollecting() {
            collecting = true;
        }

        List<String> collectedMessages() {
            synchronized (messages) {
                return new ArrayList<>(messages);
            }
        }

        void disconnect() {
            if (conn != null) conn.disconnect();
            if (thread != null) thread.interrupt();
        }
    }
}
