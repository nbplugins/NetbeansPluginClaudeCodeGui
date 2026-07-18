package io.github.nbplugins.claudecodegui.chatgptauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.nbplugins.claudecodegui.settings.ProxyConfiguration;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs the PKCE OAuth flow used to authenticate a ChatGPT Plus/Pro/Team
 * subscription, and exchanges an authorization code or refresh token for an
 * access token.
 *
 * <p>Reuses OpenAI's own Codex CLI public client id and endpoints, since
 * this plugin has no registered OAuth application of its own — the same
 * approach taken by third-party tools such as {@code raine/claude-code-proxy}.
 * No client secret is required (public PKCE client).
 *
 * <p>This client never opens a browser and never waits for the OAuth
 * redirect over the network: {@link #beginSignIn()} builds the authorize URL
 * and binds a local listener purely as a convenience (it renders the
 * authorization code on a friendly page when the browser can reach it — see
 * {@link #handleCallback}), but completion is always driven by the caller
 * manually calling {@link PendingSignIn#completeWithCode}. This is
 * deliberate: the browser used to complete sign-in may run under different
 * network conditions than the plugin (a different proxy, or on a machine
 * that can't reach this one at all), so relying on the redirect actually
 * reaching the local listener would be fragile.
 *
 * <p>The resulting {@link TokenSet} is used both to call OpenAI's Codex
 * Responses API ({@code https://chatgpt.com/backend-api/codex/responses})
 * and to refresh the access token via {@link #refresh}, wrapped
 * by {@link ChatGptTokenManager} for expiry tracking and single-flight refresh.
 */
public final class ChatGptOAuthClient {

    private static final Logger LOG = Logger.getLogger(ChatGptOAuthClient.class.getName());

    static final String CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";
    static final String ISSUER = "https://auth.openai.com";
    static final String AUTHORIZE_ENDPOINT = ISSUER + "/oauth/authorize";
    static final String TOKEN_ENDPOINT = ISSUER + "/oauth/token";
    static final int PRIMARY_CALLBACK_PORT = 1455;
    static final int FALLBACK_CALLBACK_PORT = 1457;
    static final String SCOPES = "openid profile email offline_access api.connectors.read api.connectors.invoke";
    private static final String CALLBACK_PATH = "/auth/callback";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Result of a successful login or token refresh: the OAuth token set plus
     * account identity claims extracted from the {@code id_token}.
     *
     * @param accessToken  bearer token sent to the Codex Responses API
     * @param refreshToken used to obtain a new access token when it expires
     * @param accountId    {@code chatgpt_account_id} claim, sent as the
     *                     {@code ChatGPT-Account-Id} header
     * @param email        {@code email} claim, if present; used only for display
     * @param expiresAt    access-token expiry instant
     */
    public record TokenSet(String accessToken, String refreshToken, String accountId,
                            String email, Instant expiresAt) {}

    /**
     * Starts a new sign-in attempt: generates fresh PKCE parameters, binds a
     * local one-shot HTTP listener (primary port {@value #PRIMARY_CALLBACK_PORT},
     * falling back to {@value #FALLBACK_CALLBACK_PORT}), and builds the
     * authorize URL.
     *
     * <p>Purely local work — no network call — so this is safe to call
     * directly on the EDT. The returned {@link PendingSignIn} must eventually
     * be completed via {@link PendingSignIn#completeWithCode} or discarded
     * via {@link PendingSignIn#close()} to release the bound port.
     *
     * @return a new pending sign-in attempt
     * @throws OAuthException if neither the primary nor fallback port can be bound
     */
    public PendingSignIn beginSignIn() throws OAuthException {
        String codeVerifier = generateCodeVerifier();
        String codeChallenge = deriveCodeChallenge(codeVerifier);
        String state = generateState();

        HttpServer server;
        int port;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", PRIMARY_CALLBACK_PORT), 0);
            port = PRIMARY_CALLBACK_PORT;
        } catch (IOException primaryFailed) {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", FALLBACK_CALLBACK_PORT), 0);
                port = FALLBACK_CALLBACK_PORT;
            } catch (IOException fallbackFailed) {
                throw new OAuthException("Ports " + PRIMARY_CALLBACK_PORT + "/" + FALLBACK_CALLBACK_PORT
                        + " are in use — close other Codex/Claude Code sign-in flows and retry.",
                        fallbackFailed);
            }
        }

        server.createContext(CALLBACK_PATH, this::handleCallback);
        server.start();

        String authorizeUrl = buildAuthorizeUrl(codeVerifier, codeChallenge, state, port);
        return new PendingSignIn(authorizeUrl, codeVerifier, port, server);
    }

    /**
     * A single in-progress sign-in attempt: an authorize URL the user opens
     * themselves, plus the PKCE state needed to redeem the code they paste
     * back once they've completed sign-in in their browser.
     */
    public final class PendingSignIn implements AutoCloseable {
        private final String authorizeUrl;
        private final String codeVerifier;
        private final int port;
        private final HttpServer server;
        private volatile boolean closed;

        private PendingSignIn(String authorizeUrl, String codeVerifier, int port, HttpServer server) {
            this.authorizeUrl = authorizeUrl;
            this.codeVerifier = codeVerifier;
            this.port = port;
            this.server = server;
        }

        /** The authorize URL to present to the user (e.g. copy to clipboard). */
        public String authorizeUrl() { return authorizeUrl; }

        /**
         * Redeems the authorization code the user pasted back after
         * completing sign-in in their browser.
         *
         * <p>Accepts either the bare code or the full redirect URL the
         * browser landed on (e.g. copied from the address bar when the
         * browser couldn't reach this machine's local listener) — the code
         * is extracted from either form. Performs a real network call to
         * the token endpoint; callers must invoke this off the EDT.
         *
         * @param pastedCodeOrUrl the code, or a URL containing a {@code code} query parameter
         * @param proxyConfig     proxy settings to use for the token-exchange HTTP call,
         *                        read fresh at call time (not captured at {@link #beginSignIn()})
         * @return the obtained token set
         * @throws OAuthException if no code could be extracted from the input,
         *                        or the token exchange fails
         */
        public TokenSet completeWithCode(String pastedCodeOrUrl, ProxyConfiguration proxyConfig)
                throws OAuthException {
            String code = extractCode(pastedCodeOrUrl);
            if (code.isBlank()) {
                throw new OAuthException("Could not find a sign-in code in the pasted text.");
            }
            HttpClient httpClient = proxyConfig.applyTo(
                    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30))).build();
            return exchangeCodeForTokens(httpClient, code, codeVerifier, port);
        }

        /** Stops the local listener, releasing its port. Safe to call more than once. */
        @Override
        public void close() {
            if (closed) return;
            closed = true;
            server.stop(0);
        }
    }

    /**
     * Handles a hit on the local listener, when the user's browser is able to
     * reach this machine: renders the authorization code (or an error) on a
     * simple page for the user to copy back into NetBeans. Purely a
     * convenience — does not drive completion of any pending sign-in.
     */
    private void handleCallback(HttpExchange exchange) throws IOException {
        try {
            Map<String, String> params = parseQuery(exchange.getRequestURI().getRawQuery());
            String error = params.get("error");
            String code = params.get("code");

            String html = (error == null && code != null)
                    ? "<html><body>"
                        + "<p>Sign-in code:</p>"
                        + "<p style='font-family:monospace;font-size:1.4em;'>" + escapeHtml(code) + "</p>"
                        + "<p>Copy this code and paste it back into NetBeans, then click \"Complete Sign-in\".</p>"
                        + "</body></html>"
                    : "<html><body><p>Sign-in failed"
                        + (error != null ? ": " + escapeHtml(error) : "")
                        + ". You can close this tab and try again in NetBeans.</p></body></html>";
            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error rendering ChatGPT sign-in callback page", e);
        }
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // -------------------------------------------------------------------------
    // PKCE / URL building — package-private for unit testing
    // -------------------------------------------------------------------------

    static String generateCodeVerifier() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String deriveCodeChallenge(String verifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String generateState() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String buildAuthorizeUrl(String codeVerifier, String codeChallenge, String state, int callbackPort) {
        String redirectUri = "http://localhost:" + callbackPort + CALLBACK_PATH;
        return AUTHORIZE_ENDPOINT
                + "?response_type=code"
                + "&client_id=" + urlEncode(CLIENT_ID)
                + "&redirect_uri=" + urlEncode(redirectUri)
                + "&scope=" + urlEncode(SCOPES)
                + "&code_challenge=" + urlEncode(codeChallenge)
                + "&code_challenge_method=S256"
                + "&state=" + urlEncode(state);
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) return out;
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String k = java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            String v = java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            out.put(k, v);
        }
        return out;
    }

    /**
     * Extracts an authorization code from either a bare code string or a full
     * redirect URL containing a {@code code} query parameter.
     *
     * @param pastedCodeOrUrl user-pasted text — a code or a URL
     * @return the extracted code, or the trimmed input as-is if it doesn't
     *         look like a URL (treated as a bare code); never {@code null}
     */
    static String extractCode(String pastedCodeOrUrl) {
        if (pastedCodeOrUrl == null) return "";
        String trimmed = pastedCodeOrUrl.trim();
        int queryStart = trimmed.indexOf('?');
        boolean looksLikeUrl = trimmed.startsWith("http://") || trimmed.startsWith("https://") || queryStart >= 0;
        if (looksLikeUrl) {
            String query = queryStart >= 0 ? trimmed.substring(queryStart + 1) : "";
            // A URL/query string with no "code" param has no code to extract — don't fall back
            // to treating the whole URL as a bare code, that would just fail confusingly later.
            return parseQuery(query).getOrDefault("code", "");
        }
        return trimmed;
    }

    // -------------------------------------------------------------------------
    // Token exchange
    // -------------------------------------------------------------------------

    static TokenSet exchangeCodeForTokens(HttpClient httpClient, String code, String codeVerifier, int callbackPort)
            throws OAuthException {
        String redirectUri = "http://localhost:" + callbackPort + CALLBACK_PATH;
        String form = "grant_type=authorization_code"
                + "&code=" + urlEncode(code)
                + "&redirect_uri=" + urlEncode(redirectUri)
                + "&client_id=" + urlEncode(CLIENT_ID)
                + "&code_verifier=" + urlEncode(codeVerifier);
        return requestTokens(httpClient, form);
    }

    /**
     * Exchanges a refresh token for a new access token. Called by
     * {@link ChatGptTokenManager} when the current access token is near expiry.
     *
     * @param httpClient   client configured with the profile's proxy settings
     * @param refreshToken the current refresh token
     * @return the refreshed token set (OpenAI may rotate the refresh token)
     * @throws OAuthException if the refresh request fails (e.g. revoked token)
     */
    public TokenSet refresh(HttpClient httpClient, String refreshToken) throws OAuthException {
        String form = "grant_type=refresh_token"
                + "&refresh_token=" + urlEncode(refreshToken)
                + "&client_id=" + urlEncode(CLIENT_ID);
        return requestTokens(httpClient, form);
    }

    // -------------------------------------------------------------------------
    // Model discovery
    // -------------------------------------------------------------------------

    static final String MODELS_ENDPOINT = "https://chatgpt.com/backend-api/codex/models";

    /**
     * Value sent as the required {@code client_version} query parameter on
     * {@link #MODELS_ENDPOINT} — the backend rejects requests missing it with
     * {@code HTTP 400 "Field required"}, and (confirmed against
     * {@code openai/codex}'s {@code codex-rs/codex-api/src/endpoint/models.rs}
     * and {@code ModelInfo.minimal_client_version}) also uses it to filter out
     * any model whose {@code minimal_client_version} exceeds this value — so
     * an old/low value here silently returns an empty {@code models} array
     * rather than an error. Update this to (approximately) the current Codex
     * CLI release — see {@code gh release list --repo openai/codex} — if
     * fetches start returning nothing again.
     */
    static final String CLIENT_VERSION = "0.144.6";

    /**
     * Fetches the list of model IDs available to this ChatGPT account on the
     * Codex backend, for the "Model Aliases…" dialog's "Fetch" button — an
     * alternative to the hardcoded model list this connection type used to have.
     *
     * <p>This endpoint is not officially documented (reverse-engineered the
     * same way as the rest of this Codex integration — see class Javadoc), so
     * {@link #parseModelIds} is deliberately tolerant of a few plausible
     * response shapes rather than assuming one exact schema.
     *
     * @param httpClient  client configured with the profile's proxy settings
     * @param accessToken current (already-refreshed) ChatGPT OAuth access token
     * @param accountId   {@code chatgpt_account_id} claim, sent as {@code ChatGPT-Account-Id}
     * @return the available model IDs
     * @throws OAuthException if the request fails, the response can't be parsed,
     *                        or no models could be recognized in the response
     *                        (the exception message then includes the raw body,
     *                        since this endpoint's exact shape isn't documented)
     */
    public java.util.List<String> fetchModels(HttpClient httpClient, String accessToken, String accountId)
            throws OAuthException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(MODELS_ENDPOINT + "?client_version=" + urlEncode(CLIENT_VERSION)))
                .header("Authorization", "Bearer " + accessToken)
                .header("ChatGPT-Account-Id", accountId)
                .header("Accept", "application/json")
                .header("originator", "codex_cli_rs")
                .header("User-Agent", "netbeans-plugin-claude-code-gui")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new OAuthException("Could not reach the ChatGPT models endpoint: " + e.getMessage(), e);
        }

        if (response.statusCode() >= 400) {
            // Include the upstream body — it usually states the actual reason
            // (wrong endpoint/headers/auth), so the user sees a real cause in
            // the dialog's status line instead of an opaque bare status code.
            String detail = response.body() == null ? "" : response.body().trim();
            if (detail.length() > 300) detail = detail.substring(0, 300) + "…";
            throw new OAuthException("ChatGPT models endpoint returned HTTP " + response.statusCode()
                    + (detail.isBlank() ? "" : ": " + detail));
        }

        LOG.fine("ChatGPT models endpoint response: " + response.body());

        java.util.List<String> ids;
        try {
            ids = parseModelIds(MAPPER.readTree(response.body()));
        } catch (Exception e) {
            throw new OAuthException("Invalid response from the ChatGPT models endpoint", e);
        }

        if (ids.isEmpty()) {
            // parseModelIds tolerates a few plausible shapes, but this endpoint is
            // undocumented — surface the raw body so the actual shape can be
            // diagnosed from the dialog's status line instead of digging in logs.
            String body = response.body() == null ? "" : response.body().trim();
            if (body.length() > 500) body = body.substring(0, 500) + "…";
            throw new OAuthException("ChatGPT models endpoint returned no recognizable models. Raw response: "
                    + (body.isBlank() ? "(empty)" : body));
        }

        return ids;
    }

    /**
     * Extracts model IDs from a Codex {@code /models} response. Confirmed
     * against {@code openai/codex}'s own source (both response envelope and
     * per-model field name): {@code {"models":[{"slug":...}]}}. Also tolerates
     * a {@code {"data":[{"id":...}]}} envelope (matching the OpenAI-compatible
     * {@code /v1/models} convention), a bare top-level array, and — within
     * each array entry — an {@code id}, {@code model}, or {@code name} field
     * as a fallback if {@code slug} is absent, or a bare ID string.
     *
     * @param json the parsed response body
     * @return extracted model IDs, in response order; never {@code null}
     */
    static java.util.List<String> parseModelIds(JsonNode json) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        // Confirmed live shape: {"models":[...]}. Also tolerate the OpenAI-compatible
        // {"data":[...]} envelope and a bare top-level array, in case the shape
        // varies by account/rollout — this endpoint is undocumented.
        JsonNode array = null;
        for (String key : new String[]{"models", "data"}) {
            JsonNode candidate = json.path(key);
            if (candidate.isArray()) {
                array = candidate;
                break;
            }
        }
        if (array == null && json.isArray()) {
            array = json;
        }
        if (array != null) {
            for (JsonNode item : array) {
                // "slug" is the confirmed field name in openai/codex's ModelInfo
                // (codex-rs/codex-protocol/src/openai_models.rs); id/model/name are
                // kept as tolerant fallbacks in case the shape varies.
                String id = item.isTextual() ? item.asText()
                        : item.path("slug").asText(item.path("id").asText(
                                item.path("model").asText(item.path("name").asText(""))));
                if (!id.isBlank()) ids.add(id);
            }
        }
        return ids;
    }

    private static TokenSet requestTokens(HttpClient httpClient, String form) throws OAuthException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TOKEN_ENDPOINT))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new OAuthException("Could not reach ChatGPT sign-in server: " + e.getMessage(), e);
        }

        if (response.statusCode() >= 400) {
            throw new OAuthException("ChatGPT session expired or was revoked — please sign in again "
                    + "in Profile settings. (HTTP " + response.statusCode() + ")");
        }

        JsonNode json;
        try {
            json = MAPPER.readTree(response.body());
        } catch (Exception e) {
            throw new OAuthException("Invalid response from ChatGPT sign-in server", e);
        }

        String accessToken = json.path("access_token").asText("");
        String refreshTokenOut = json.path("refresh_token").asText("");
        String idToken = json.path("id_token").asText("");
        long expiresIn = json.path("expires_in").asLong(3600);

        if (accessToken.isBlank()) {
            throw new OAuthException("ChatGPT sign-in server did not return an access token");
        }

        String accountId = "";
        String email = "";
        if (!idToken.isBlank()) {
            JsonNode claims = parseIdTokenClaims(idToken);
            if (claims != null) {
                accountId = claims.path("https://api.openai.com/auth").path("chatgpt_account_id").asText("");
                email = claims.path("email").asText("");
            }
        }

        return new TokenSet(accessToken, refreshTokenOut, accountId, email,
                Instant.now().plusSeconds(expiresIn));
    }

    /**
     * Extracts the claims payload from a JWT without verifying its signature.
     * Safe here because the token is obtained directly from {@code auth.openai.com}
     * over TLS, not accepted from an untrusted third party.
     */
    private static JsonNode parseIdTokenClaims(String idToken) {
        String[] parts = idToken.split("\\.");
        if (parts.length < 2) return null;
        try {
            String payload = parts[1];
            // JWT base64url omits padding; Base64.getUrlDecoder() requires it be added back.
            int rem = payload.length() % 4;
            if (rem != 0) payload += "=".repeat(4 - rem);
            byte[] decoded = Base64.getUrlDecoder().decode(payload);
            return MAPPER.readTree(decoded);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not parse id_token claims", e);
            return null;
        }
    }
}
