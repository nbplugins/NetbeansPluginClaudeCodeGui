package io.github.nbplugins.claudecodegui.chatgptauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the parts of {@link ChatGptOAuthClient} that don't require a
 * real network round-trip: PKCE verifier/challenge generation, authorize URL
 * construction, {@code id_token} claim extraction, {@link ChatGptOAuthClient#beginSignIn()}'s
 * local listener setup, and the pasted-code extraction logic.
 *
 * <p>{@code ChatGptOAuthClient} never opens a browser and never waits for the
 * OAuth redirect over the network — the caller (see
 * {@code ClaudeProfilesPanel.onChatgptSignIn}/{@code onChatgptSubmitCode})
 * copies the authorize URL for the user to open themselves, and later
 * redeems a code the user pastes back. NOT tested here (requires manual
 * verification): the actual token exchange against {@code auth.openai.com}.
 */
class ChatGptOAuthClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // -------------------------------------------------------------------------
    // generateCodeVerifier
    // -------------------------------------------------------------------------

    @Test
    void generateCodeVerifier_meetsRfc7636LengthAndCharsetConstraints() {
        String verifier = ChatGptOAuthClient.generateCodeVerifier();
        // RFC 7636 §4.1: 43-128 characters from [A-Z a-z 0-9 - . _ ~]
        assertTrue(verifier.length() >= 43 && verifier.length() <= 128,
                "verifier length out of RFC 7636 range: " + verifier.length());
        assertTrue(verifier.matches("[A-Za-z0-9\\-._~]+"),
                "verifier contains characters outside the unreserved set: " + verifier);
    }

    @Test
    void generateCodeVerifier_producesDistinctValues() {
        assertNotEquals(ChatGptOAuthClient.generateCodeVerifier(), ChatGptOAuthClient.generateCodeVerifier());
    }

    // -------------------------------------------------------------------------
    // deriveCodeChallenge — RFC 7636 Appendix B test vector
    // -------------------------------------------------------------------------

    @Test
    void deriveCodeChallenge_matchesRfc7636AppendixBVector() {
        String verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        String expectedChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";
        assertEquals(expectedChallenge, ChatGptOAuthClient.deriveCodeChallenge(verifier));
    }

    // -------------------------------------------------------------------------
    // buildAuthorizeUrl
    // -------------------------------------------------------------------------

    @Test
    void buildAuthorizeUrl_containsAllRequiredParams() {
        String url = ChatGptOAuthClient.buildAuthorizeUrl("verifier123", "challenge456", "state789", 1455);

        assertTrue(url.startsWith(ChatGptOAuthClient.AUTHORIZE_ENDPOINT + "?"));
        assertTrue(url.contains("response_type=code"));
        assertTrue(url.contains("client_id=" + ChatGptOAuthClient.CLIENT_ID));
        assertTrue(url.contains("redirect_uri=http%3A%2F%2Flocalhost%3A1455%2Fauth%2Fcallback"));
        assertTrue(url.contains("code_challenge=challenge456"));
        assertTrue(url.contains("code_challenge_method=S256"));
        assertTrue(url.contains("state=state789"));
        assertTrue(url.contains("scope=" + java.net.URLEncoder.encode(ChatGptOAuthClient.SCOPES, StandardCharsets.UTF_8)));
    }

    @Test
    void buildAuthorizeUrl_usesGivenCallbackPort() {
        String url = ChatGptOAuthClient.buildAuthorizeUrl("v", "c", "s", 1457);
        assertTrue(url.contains("redirect_uri=http%3A%2F%2Flocalhost%3A1457%2Fauth%2Fcallback"));
    }

    // -------------------------------------------------------------------------
    // id_token claim parsing (via reflection on the private helper)
    // -------------------------------------------------------------------------

    @Test
    void parseIdTokenClaims_extractsAccountIdAndEmail() throws Exception {
        String idToken = buildFakeIdToken("acct-123", "user@example.com");
        JsonNode claims = invokeParseIdTokenClaims(idToken);

        assertNotNull(claims);
        assertEquals("acct-123", claims.path("https://api.openai.com/auth").path("chatgpt_account_id").asText());
        assertEquals("user@example.com", claims.path("email").asText());
    }

    @Test
    void parseIdTokenClaims_malformedToken_returnsNull() throws Exception {
        assertNull(invokeParseIdTokenClaims("not-a-jwt"));
    }

    private static String buildFakeIdToken(String accountId, String email) {
        String header = base64Url("{\"alg\":\"none\"}");
        var payload = MAPPER.createObjectNode();
        payload.put("email", email);
        payload.putObject("https://api.openai.com/auth").put("chatgpt_account_id", accountId);
        String payloadEncoded = base64Url(payload.toString());
        // No real signature needed — the client intentionally does not verify it
        // (the token is obtained directly from auth.openai.com over TLS).
        return header + "." + payloadEncoded + ".fakesignature";
    }

    private static String base64Url(String json) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static JsonNode invokeParseIdTokenClaims(String idToken) throws Exception {
        Method m = ChatGptOAuthClient.class.getDeclaredMethod("parseIdTokenClaims", String.class);
        m.setAccessible(true);
        return (JsonNode) m.invoke(null, idToken);
    }

    // -------------------------------------------------------------------------
    // beginSignIn
    // -------------------------------------------------------------------------

    @Test
    void beginSignIn_returnsPendingSignInWithWellFormedAuthorizeUrl() throws Exception {
        ChatGptOAuthClient client = new ChatGptOAuthClient();
        try (ChatGptOAuthClient.PendingSignIn pending = client.beginSignIn()) {
            String url = pending.authorizeUrl();
            assertTrue(url.startsWith(ChatGptOAuthClient.AUTHORIZE_ENDPOINT + "?"));
            assertTrue(url.contains("client_id=" + ChatGptOAuthClient.CLIENT_ID));
            assertTrue(url.contains("code_challenge_method=S256"));
        }
    }

    @Test
    void beginSignIn_closeIsIdempotent() throws Exception {
        ChatGptOAuthClient client = new ChatGptOAuthClient();
        ChatGptOAuthClient.PendingSignIn pending = client.beginSignIn();
        assertDoesNotThrow(() -> {
            pending.close();
            pending.close();
        });
    }

    @Test
    void beginSignIn_calledTwice_bindsDistinctPorts() throws Exception {
        ChatGptOAuthClient client = new ChatGptOAuthClient();
        try (ChatGptOAuthClient.PendingSignIn first = client.beginSignIn();
             ChatGptOAuthClient.PendingSignIn second = client.beginSignIn()) {
            assertNotEquals(first.authorizeUrl(), second.authorizeUrl(),
                    "each attempt should use fresh PKCE parameters / a free port");
        }
    }

    // -------------------------------------------------------------------------
    // extractCode — pasted code or full redirect URL
    // -------------------------------------------------------------------------

    @Test
    void extractCode_bareCode_returnedAsIs() {
        assertEquals("abc123", ChatGptOAuthClient.extractCode("  abc123  "));
    }

    @Test
    void extractCode_fullRedirectUrl_extractsCodeParam() {
        String url = "http://localhost:1455/auth/callback?code=XYZ789&state=somestate";
        assertEquals("XYZ789", ChatGptOAuthClient.extractCode(url));
    }

    @Test
    void extractCode_urlEncodedCode_isDecoded() {
        String url = "http://localhost:1455/auth/callback?code=a%2Fb%3Dc&state=s";
        assertEquals("a/b=c", ChatGptOAuthClient.extractCode(url));
    }

    @Test
    void extractCode_urlWithoutCodeParam_returnsEmpty() {
        String url = "http://localhost:1455/auth/callback?error=access_denied";
        assertEquals("", ChatGptOAuthClient.extractCode(url));
    }

    @Test
    void extractCode_blankInput_returnsEmpty() {
        assertEquals("", ChatGptOAuthClient.extractCode("   "));
        assertEquals("", ChatGptOAuthClient.extractCode(null));
    }

    // -------------------------------------------------------------------------
    // parseModelIds(JsonNode) — Codex /models response is undocumented,
    // so this tolerates a few plausible shapes.
    // -------------------------------------------------------------------------

    @Test
    void parseModelIds_dataEnvelopeOfObjects_returnsIds() throws Exception {
        JsonNode json = MAPPER.readTree("{\"data\":[{\"id\":\"gpt-5-codex\"},{\"id\":\"gpt-5-codex-mini\"}]}");
        assertEquals(java.util.List.of("gpt-5-codex", "gpt-5-codex-mini"), ChatGptOAuthClient.parseModelIds(json));
    }

    @Test
    void parseModelIds_bareArrayOfStrings_returnsIds() throws Exception {
        JsonNode json = MAPPER.readTree("[\"gpt-5-codex\",\"gpt-5.1-codex\"]");
        assertEquals(java.util.List.of("gpt-5-codex", "gpt-5.1-codex"), ChatGptOAuthClient.parseModelIds(json));
    }

    @Test
    void parseModelIds_bareArrayOfObjects_returnsIds() throws Exception {
        JsonNode json = MAPPER.readTree("[{\"id\":\"gpt-5-codex\"}]");
        assertEquals(java.util.List.of("gpt-5-codex"), ChatGptOAuthClient.parseModelIds(json));
    }

    @Test
    void parseModelIds_blankIdsSkipped() throws Exception {
        JsonNode json = MAPPER.readTree("{\"data\":[{\"id\":\"\"},{\"id\":\"gpt-5-codex\"}]}");
        assertEquals(java.util.List.of("gpt-5-codex"), ChatGptOAuthClient.parseModelIds(json));
    }

    @Test
    void parseModelIds_unrecognizedShape_returnsEmpty() throws Exception {
        JsonNode json = MAPPER.readTree("{\"status\":\"ok\"}");
        assertTrue(ChatGptOAuthClient.parseModelIds(json).isEmpty());
    }

    /** Confirmed live shape from the real Codex backend (2026-07-18): {@code {"models":[...]}}. */
    @Test
    void parseModelIds_modelsEnvelopeOfObjects_returnsIds() throws Exception {
        JsonNode json = MAPPER.readTree("{\"models\":[{\"id\":\"gpt-5-codex\"},{\"id\":\"gpt-5.1-codex\"}]}");
        assertEquals(java.util.List.of("gpt-5-codex", "gpt-5.1-codex"), ChatGptOAuthClient.parseModelIds(json));
    }

    @Test
    void parseModelIds_modelsEnvelopeEmpty_returnsEmpty() throws Exception {
        JsonNode json = MAPPER.readTree("{\"models\":[]}");
        assertTrue(ChatGptOAuthClient.parseModelIds(json).isEmpty());
    }

    @Test
    void parseModelIds_modelFieldInsteadOfId_returnsIds() throws Exception {
        JsonNode json = MAPPER.readTree("{\"models\":[{\"model\":\"gpt-5-codex\"}]}");
        assertEquals(java.util.List.of("gpt-5-codex"), ChatGptOAuthClient.parseModelIds(json));
    }

    @Test
    void parseModelIds_nameFieldInsteadOfId_returnsIds() throws Exception {
        JsonNode json = MAPPER.readTree("{\"models\":[{\"name\":\"gpt-5-codex\"}]}");
        assertEquals(java.util.List.of("gpt-5-codex"), ChatGptOAuthClient.parseModelIds(json));
    }

    /**
     * The confirmed real shape (verified against {@code openai/codex}'s
     * {@code ModelInfo} struct and its own {@code models.rs} test fixture): each
     * entry's identifier field is {@code slug}, not {@code id}.
     */
    @Test
    void parseModelIds_slugField_returnsIds() throws Exception {
        JsonNode json = MAPPER.readTree(
                "{\"models\":[{\"slug\":\"gpt-5-codex\",\"display_name\":\"GPT-5 Codex\"}]}");
        assertEquals(java.util.List.of("gpt-5-codex"), ChatGptOAuthClient.parseModelIds(json));
    }

    @Test
    void parseModelIds_slugTakesPrecedenceOverId() throws Exception {
        JsonNode json = MAPPER.readTree("{\"models\":[{\"slug\":\"real-slug\",\"id\":\"unused-id\"}]}");
        assertEquals(java.util.List.of("real-slug"), ChatGptOAuthClient.parseModelIds(json));
    }
}
