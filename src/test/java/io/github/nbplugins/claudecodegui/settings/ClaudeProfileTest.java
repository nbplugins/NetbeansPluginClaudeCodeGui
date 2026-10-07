package io.github.nbplugins.claudecodegui.settings;

import io.github.nbplugins.claudecodegui.settings.ClaudeProfile.ConnectionType;
import io.github.nbplugins.claudecodegui.settings.ClaudeProfile.ProxyMode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ClaudeProfile}.
 */
class ClaudeProfileTest {

    // -------------------------------------------------------------------------
    // createDefault
    // -------------------------------------------------------------------------

    @Test
    void createDefault_hasBlankId() {
        ClaudeProfile p = ClaudeProfile.createDefault();
        assertTrue(p.isDefault());
        assertEquals("", p.getId());
        assertEquals("Default", p.getName());
    }

    @Test
    void createDefault_connectionTypeIsClaudeManaged() {
        assertEquals(ConnectionType.CLAUDE_MANAGED,
                ClaudeProfile.createDefault().computeConnectionType());
    }

    // -------------------------------------------------------------------------
    // createNamed
    // -------------------------------------------------------------------------

    @Test
    void createNamed_setsIdAndName() {
        ClaudeProfile p = ClaudeProfile.createNamed("Work");
        assertFalse(p.isDefault());
        assertEquals("Work", p.getId());
        assertEquals("Work", p.getName());
    }

    @Test
    void createNamed_invalidName_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> ClaudeProfile.createNamed("bad name"));
        assertThrows(IllegalArgumentException.class, () -> ClaudeProfile.createNamed("."));
        assertThrows(IllegalArgumentException.class, () -> ClaudeProfile.createNamed(".."));
        assertThrows(IllegalArgumentException.class, () -> ClaudeProfile.createNamed("a/b"));
        assertThrows(IllegalArgumentException.class, () -> ClaudeProfile.createNamed(""));
    }

    // -------------------------------------------------------------------------
    // validateName
    // -------------------------------------------------------------------------

    @Test
    void validateName_nullOrBlank_returnsError() {
        assertNotNull(ClaudeProfile.validateName(null));
        assertNotNull(ClaudeProfile.validateName(""));
        assertNotNull(ClaudeProfile.validateName("   "));
    }

    @Test
    void validateName_dotOrDoubleDot_returnsError() {
        assertNotNull(ClaudeProfile.validateName("."));
        assertNotNull(ClaudeProfile.validateName(".."));
    }

    @Test
    void validateName_forbiddenChars_returnsError() {
        for (String bad : List.of("a b", "a/b", "a\\b", "a:b", "a*b",
                "a?b", "a\"b", "a<b", "a>b", "a|b")) {
            assertNotNull(ClaudeProfile.validateName(bad),
                    "Expected error for name: " + bad);
        }
    }

    @Test
    void validateName_valid_returnsNull() {
        assertNull(ClaudeProfile.validateName("MyProfile"));
        assertNull(ClaudeProfile.validateName("profile-1"));
        assertNull(ClaudeProfile.validateName("profile.work"));
        assertNull(ClaudeProfile.validateName("OpenAI_Proxy"));
    }

    // -------------------------------------------------------------------------
    // computeConnectionType
    // -------------------------------------------------------------------------

    @Test
    void computeConnectionType_emptyCredentials_isClaudeManaged() {
        ClaudeProfile p = ClaudeProfile.createNamed("P");
        assertEquals(ConnectionType.CLAUDE_MANAGED, p.computeConnectionType());
    }

    @Test
    void computeConnectionType_tokenOnly_isSubscription() {
        ClaudeProfile p = ClaudeProfile.createNamed("P");
        p.setToken("tok123");
        assertEquals(ConnectionType.SUBSCRIPTION, p.computeConnectionType());
    }

    @Test
    void computeConnectionType_apiKeyOnly_isClaudeApi() {
        ClaudeProfile p = ClaudeProfile.createNamed("P");
        p.setApiKey("sk-ant-123");
        assertEquals(ConnectionType.CLAUDE_API, p.computeConnectionType());
    }

    @Test
    void computeConnectionType_apiKeyAndBaseUrl_isOtherApi() {
        ClaudeProfile p = ClaudeProfile.createNamed("P");
        p.setApiKey("sk-ant-123");
        p.setBaseUrl("https://api.example.com");
        assertEquals(ConnectionType.OTHER_API, p.computeConnectionType());
    }

    @Test
    void computeConnectionType_apiKeyTakesPrecedenceOverToken() {
        ClaudeProfile p = ClaudeProfile.createNamed("P");
        p.setToken("tok");
        p.setApiKey("key");
        assertEquals(ConnectionType.CLAUDE_API, p.computeConnectionType());
    }

    @Test
    void computeConnectionType_openaiSubscription_isOpenaiSubscription() {
        ClaudeProfile p = ClaudeProfile.createNamed("P");
        p.setOpenaiSubscription(true);
        assertEquals(ConnectionType.OPENAI_SUBSCRIPTION, p.computeConnectionType());
    }

    @Test
    void computeConnectionType_openaiSubscriptionTakesPrecedenceOverOpenaiProxy() {
        ClaudeProfile p = ClaudeProfile.createNamed("P");
        p.setOpenaiProxy(true);
        p.setOpenaiSubscription(true);
        assertEquals(ConnectionType.OPENAI_SUBSCRIPTION, p.computeConnectionType());
    }

    // -------------------------------------------------------------------------
    // ChatGPT OAuth state
    // -------------------------------------------------------------------------

    @Test
    void isSignedIntoChatgpt_falseByDefault() {
        ClaudeProfile p = ClaudeProfile.createNamed("P");
        assertFalse(p.isSignedIntoChatgpt());
    }

    @Test
    void isSignedIntoChatgpt_trueWhenAccessAndRefreshTokenPresent() {
        ClaudeProfile p = ClaudeProfile.createNamed("P");
        p.setChatgptAccessToken("access");
        p.setChatgptRefreshToken("refresh");
        assertTrue(p.isSignedIntoChatgpt());
    }

    @Test
    void isSignedIntoChatgpt_falseWhenOnlyAccessTokenPresent() {
        ClaudeProfile p = ClaudeProfile.createNamed("P");
        p.setChatgptAccessToken("access");
        assertFalse(p.isSignedIntoChatgpt());
    }

    @Test
    void clearChatgptAuth_clearsAllTokenFieldsButNotFlag() {
        ClaudeProfile p = ClaudeProfile.createNamed("P");
        p.setOpenaiSubscription(true);
        p.setChatgptAccessToken("access");
        p.setChatgptRefreshToken("refresh");
        p.setChatgptAccountId("acct-1");
        p.setChatgptTokenExpiresAt("2026-01-01T00:00:00Z");
        p.setChatgptEmail("user@example.com");

        p.clearChatgptAuth();

        assertFalse(p.isSignedIntoChatgpt());
        assertEquals("", p.getChatgptAccessToken());
        assertEquals("", p.getChatgptRefreshToken());
        assertEquals("", p.getChatgptAccountId());
        assertEquals("", p.getChatgptTokenExpiresAt());
        assertEquals("", p.getChatgptEmail());
        assertTrue(p.isOpenaiSubscription(), "clearChatgptAuth must not change the connection-type flag");
    }

    // -------------------------------------------------------------------------
    // toEnvVars — auth
    // -------------------------------------------------------------------------

    @Test
    void toEnvVars_claudeManaged_noAuthVars() {
        ClaudeProfile p = ClaudeProfile.createNamed("P");
        Map<String, String> env = p.toEnvVars();
        assertFalse(env.containsKey("ANTHROPIC_API_KEY"));
        assertFalse(env.containsKey("ANTHROPIC_AUTH_TOKEN"));
        assertFalse(env.containsKey("CLAUDE_CODE_OAUTH_TOKEN"));
    }

    @Test
    void toEnvVars_subscription_injectsToken() {
        ClaudeProfile p = ClaudeProfile.createNamed("P");
        p.setToken("my-token");
        Map<String, String> env = p.toEnvVars();
        assertEquals("my-token", env.get("CLAUDE_CODE_OAUTH_TOKEN"));
        assertFalse(env.containsKey("ANTHROPIC_API_KEY"));
    }

    @Test
    void toEnvVars_claudeApi_doesNotInjectApiKey() {
        // CLAUDE_API key is written to settings.local.json as apiKeyHelper, not env var
        ClaudeProfile p = ClaudeProfile.createNamed("P");
        p.setApiKey("sk-123");
        Map<String, String> env = p.toEnvVars();
        assertFalse(env.containsKey("ANTHROPIC_API_KEY"),
                "CLAUDE_API must not inject ANTHROPIC_API_KEY as env var");
        assertFalse(env.containsKey("ANTHROPIC_BASE_URL"));
    }

    @Test
    void toEnvVars_openaiSubscription_noAuthVars() {
        ClaudeProfile p = ClaudeProfile.createNamed("P");
        p.setOpenaiSubscription(true);
        Map<String, String> env = p.toEnvVars();
        assertFalse(env.containsKey("ANTHROPIC_AUTH_TOKEN"));
        assertFalse(env.containsKey("ANTHROPIC_BASE_URL"));
        assertFalse(env.containsKey("CLAUDE_CODE_OAUTH_TOKEN"));
    }

    @Test
    void toEnvVars_otherApi_injectsAuthTokenAndBaseUrl() {
        ClaudeProfile p = ClaudeProfile.createNamed("P");
        p.setApiKey("sk-123");
        p.setBaseUrl("https://proxy.example.com");
        Map<String, String> env = p.toEnvVars();
        assertEquals("sk-123", env.get("ANTHROPIC_AUTH_TOKEN"));
        assertEquals("https://proxy.example.com", env.get("ANTHROPIC_BASE_URL"));
        assertFalse(env.containsKey("ANTHROPIC_API_KEY"));
    }

    // -------------------------------------------------------------------------
    // toEnvVars — proxy
    // -------------------------------------------------------------------------

    @Test
    void toEnvVars_systemManagedProxy_noProxyVars() {
        ClaudeProfile p = ClaudeProfile.createNamed("P");
        p.setProxyMode(ProxyMode.SYSTEM_MANAGED);
        Map<String, String> env = p.toEnvVars();
        assertFalse(env.containsKey("HTTP_PROXY"));
        assertFalse(env.containsKey("HTTPS_PROXY"));
        assertFalse(env.containsKey("NO_PROXY"));
    }

    @Test
    void toEnvVars_noProxy_setsEmptyStrings() {
        ClaudeProfile p = ClaudeProfile.createNamed("P");
        p.setProxyMode(ProxyMode.NO_PROXY);
        Map<String, String> env = p.toEnvVars();
        assertEquals("", env.get("HTTP_PROXY"));
        assertEquals("", env.get("HTTPS_PROXY"));
        assertEquals("", env.get("NO_PROXY"));
    }

    @Test
    void toEnvVars_customProxy_injectsValues() {
        ClaudeProfile p = ClaudeProfile.createNamed("P");
        p.setProxyMode(ProxyMode.CUSTOM);
        p.setHttpProxy("http://proxy:3128");
        p.setHttpsProxy("http://proxy:3128");
        p.setNoProxy("localhost");
        Map<String, String> env = p.toEnvVars();
        assertEquals("http://proxy:3128", env.get("HTTP_PROXY"));
        assertEquals("http://proxy:3128", env.get("HTTPS_PROXY"));
        assertEquals("localhost", env.get("NO_PROXY"));
    }

    @Test
    void toEnvVars_customProxy_blankNoProxy_omitsNoProxy() {
        ClaudeProfile p = ClaudeProfile.createNamed("P");
        p.setProxyMode(ProxyMode.CUSTOM);
        p.setHttpProxy("http://proxy:3128");
        p.setHttpsProxy("http://proxy:3128");
        Map<String, String> env = p.toEnvVars();
        assertFalse(env.containsKey("NO_PROXY"));
    }

    // -------------------------------------------------------------------------
    // toEnvVars — extraEnvVars
    // -------------------------------------------------------------------------

    @Test
    void toEnvVars_extraVars_injected() {
        ClaudeProfile p = ClaudeProfile.createNamed("P");
        List<String[]> extra = new java.util.ArrayList<>();
        extra.add(new String[]{"AWS_REGION", "us-east-1"});
        extra.add(new String[]{"MY_VAR", "hello"});
        p.setExtraEnvVars(extra);
        Map<String, String> env = p.toEnvVars();
        assertEquals("us-east-1", env.get("AWS_REGION"));
        assertEquals("hello", env.get("MY_VAR"));
    }

    @Test
    void toEnvVars_extraVars_overrideAuthVars() {
        // Extra vars should win over computed auth vars
        ClaudeProfile p = ClaudeProfile.createNamed("P");
        p.setApiKey("sk-original");
        List<String[]> extra2 = new java.util.ArrayList<>();
        extra2.add(new String[]{"ANTHROPIC_API_KEY", "sk-override"});
        p.setExtraEnvVars(extra2);
        Map<String, String> env = p.toEnvVars();
        assertEquals("sk-override", env.get("ANTHROPIC_API_KEY"));
    }

    // -------------------------------------------------------------------------
    // toEnvVars — modelAliases
    // -------------------------------------------------------------------------

    @Test
    void toEnvVars_emitsAliasEnvVars() {
        ClaudeProfile p = ClaudeProfile.createNamed("P");
        p.setApiKey("key");
        p.setBaseUrl("https://proxy.example.com");
        p.setModelAliases(Map.of("sonnet", "gpt-4o", "haiku", "gpt-4o-mini"));
        Map<String, String> env = p.toEnvVars();
        assertEquals("gpt-4o",      env.get("ANTHROPIC_DEFAULT_SONNET_MODEL"));
        assertEquals("gpt-4o-mini", env.get("ANTHROPIC_DEFAULT_HAIKU_MODEL"));
        assertFalse(env.containsKey("ANTHROPIC_DEFAULT_OPUS_MODEL"));
    }

    @Test
    void toEnvVars_noAliases_noAliasEnvVars() {
        ClaudeProfile p = ClaudeProfile.createNamed("P");
        Map<String, String> env = p.toEnvVars();
        assertFalse(env.containsKey("ANTHROPIC_DEFAULT_SONNET_MODEL"));
        assertFalse(env.containsKey("ANTHROPIC_DEFAULT_OPUS_MODEL"));
        assertFalse(env.containsKey("ANTHROPIC_DEFAULT_HAIKU_MODEL"));
    }

    // -------------------------------------------------------------------------
    // getExtraEnvVars returns unmodifiable view
    // -------------------------------------------------------------------------

    @Test
    void getExtraEnvVars_returnsUnmodifiableView() {
        ClaudeProfile p = ClaudeProfile.createNamed("P");
        List<String[]> kv = new java.util.ArrayList<>();
        kv.add(new String[]{"K", "V"});
        p.setExtraEnvVars(kv);
        var list = p.getExtraEnvVars();
        assertThrows(UnsupportedOperationException.class, () -> list.add(new String[]{"X", "Y"}));
    }

    // -------------------------------------------------------------------------
    // customModels
    // -------------------------------------------------------------------------

    @Test
    void getCustomModels_defaultEmpty() {
        ClaudeProfile p = ClaudeProfile.createNamed("P");
        assertTrue(p.getCustomModels().isEmpty());
    }

    @Test
    void setCustomModels_roundTrip() {
        ClaudeProfile p = ClaudeProfile.createNamed("P");
        p.setCustomModels(List.of("openai/gpt-4o", "openai/gpt-4-turbo"));
        assertEquals(List.of("openai/gpt-4o", "openai/gpt-4-turbo"), p.getCustomModels());
    }

    @Test
    void setCustomModels_null_clearsList() {
        ClaudeProfile p = ClaudeProfile.createNamed("P");
        p.setCustomModels(List.of("model-x"));
        p.setCustomModels(null);
        assertTrue(p.getCustomModels().isEmpty());
    }

    @Test
    void getCustomModels_returnsUnmodifiableView() {
        ClaudeProfile p = ClaudeProfile.createNamed("P");
        p.setCustomModels(List.of("model-x"));
        assertThrows(UnsupportedOperationException.class,
                () -> p.getCustomModels().add("model-y"));
    }

    @Test
    void toEnvVars_customModels_noEnvVarEmitted() {
        // Custom models must NOT produce any ANTHROPIC_DEFAULT_CUSTOM_MODEL env var
        ClaudeProfile p = ClaudeProfile.createNamed("P");
        p.setCustomModels(List.of("openai/gpt-4o"));
        Map<String, String> env = p.toEnvVars();
        assertFalse(env.containsKey("ANTHROPIC_DEFAULT_CUSTOM_MODEL"));
    }

    // -------------------------------------------------------------------------
    // extraCliArgs
    // -------------------------------------------------------------------------

    @Test
    void extraCliArgs_defaultIsEmpty() {
        assertEquals("", ClaudeProfile.createDefault().getExtraCliArgs());
        assertEquals("", ClaudeProfile.createNamed("P").getExtraCliArgs());
    }

    @Test
    void extraCliArgs_setAndGet() {
        ClaudeProfile p = ClaudeProfile.createNamed("P");
        p.setExtraCliArgs("--verbose");
        assertEquals("--verbose", p.getExtraCliArgs());
    }

    @Test
    void extraCliArgs_nullTreatedAsEmpty() {
        ClaudeProfile p = ClaudeProfile.createNamed("P");
        p.setExtraCliArgs(null);
        assertEquals("", p.getExtraCliArgs());
    }

    // -------------------------------------------------------------------------
    // storageDir
    // -------------------------------------------------------------------------

    @Test
    void storageDir_defaultIsEmpty() {
        assertEquals("", ClaudeProfile.createNamed("P").getStorageDir());
    }

    @Test
    void storageDir_setAndGet() {
        ClaudeProfile p = ClaudeProfile.createNamed("P");
        p.setStorageDir("/my/custom/dir");
        assertEquals("/my/custom/dir", p.getStorageDir());
    }

    @Test
    void storageDir_nullTreatedAsEmpty() {
        ClaudeProfile p = ClaudeProfile.createNamed("P");
        p.setStorageDir(null);
        assertEquals("", p.getStorageDir());
    }

    @Test
    void storageDir_fluentSetter() {
        ClaudeProfile p = ClaudeProfile.createNamed("P").withStorageDir("/x/y");
        assertEquals("/x/y", p.getStorageDir());
    }

    @Test
    void storageDir_jacksonRoundTrip() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        ClaudeProfile p = ClaudeProfile.createNamed("P");
        p.setStorageDir("/custom/path");
        String json = mapper.writeValueAsString(p);
        ClaudeProfile loaded = mapper.readValue(json, ClaudeProfile.class);
        assertEquals("/custom/path", loaded.getStorageDir());
    }

    // -------------------------------------------------------------------------
    // toEnvVars — model aliases (regression for stale-aliases-after-type-switch)
    // -------------------------------------------------------------------------

    @Test
    void toEnvVars_doesNotInjectModelAliasesForManagedConnection() {
        ClaudeProfile p = ClaudeProfile.createNamed("Test");
        // no apiKey / token → CLAUDE_MANAGED
        p.setModelAliases(Map.of("sonnet", "old-model-id"));
        p.setCustomModels(List.of("old-custom-model"));

        assertEquals(ConnectionType.CLAUDE_MANAGED, p.computeConnectionType());
        Map<String, String> env = p.toEnvVars();

        assertFalse(env.containsKey("ANTHROPIC_DEFAULT_SONNET_MODEL"),
                "Model alias env vars must not be injected for CLAUDE_MANAGED connection");
    }

    @Test
    void toEnvVars_injectsModelAliasesForOtherApiConnection() {
        ClaudeProfile p = ClaudeProfile.createNamed("Test");
        p.setApiKey("sk-test");
        p.setBaseUrl("https://example.com");
        p.setModelAliases(Map.of("sonnet", "my-sonnet-model"));

        assertEquals(ConnectionType.OTHER_API, p.computeConnectionType());
        Map<String, String> env = p.toEnvVars();

        assertEquals("my-sonnet-model", env.get("ANTHROPIC_DEFAULT_SONNET_MODEL"),
                "Model alias env vars must be injected for OTHER_API connection");
    }

    @Test
    void toEnvVars_injectsModelAliasesForOpenaiSubscriptionConnection() {
        // Without these, subagents (haiku) and side requests (sonnet) reach the
        // ChatGPT Codex backend as claude-* model ids and fail with HTTP 400.
        ClaudeProfile p = ClaudeProfile.createNamed("Test");
        p.setOpenaiSubscription(true);
        p.setModelAliases(Map.of("haiku", "gpt-5.6-luna", "sonnet", "gpt-5.6-terra"));

        assertEquals(ConnectionType.OPENAI_SUBSCRIPTION, p.computeConnectionType());
        Map<String, String> env = p.toEnvVars();

        assertEquals("gpt-5.6-luna", env.get("ANTHROPIC_DEFAULT_HAIKU_MODEL"));
        assertEquals("gpt-5.6-terra", env.get("ANTHROPIC_DEFAULT_SONNET_MODEL"));
    }

    @Test
    void getComboModelIds_followsModelAliasesDialogOrder() {
        // Models mapped to sonnet/opus/haiku stay selectable by their own id, in the
        // order of the rows in the Model Aliases dialog
        ClaudeProfile p = ClaudeProfile.createNamed("Test");
        p.setOpenaiSubscription(true);
        p.setModelAliases(Map.of("sonnet", "gpt-5.6-terra", "opus", "gpt-5.6-terra", "haiku", "gpt-5.6-luna"));
        p.setCustomModels(List.of("gpt-5.6-sol", "gpt-5.5"));
        p.setModelOrder(List.of("gpt-5.6-sol", "gpt-5.6-luna", "gpt-5.5", "gpt-5.6-terra"));

        assertEquals(List.of("gpt-5.6-sol", "gpt-5.6-luna", "gpt-5.5", "gpt-5.6-terra"), p.getComboModelIds());
    }

    @Test
    void getComboModelIds_skipsOrderEntriesNoLongerAliased_andAppendsUnorderedOnes() {
        ClaudeProfile p = ClaudeProfile.createNamed("Test");
        p.setOpenaiSubscription(true);
        p.setModelAliases(Map.of("haiku", "gpt-5.6-luna"));
        p.setCustomModels(List.of("gpt-5.6-sol"));
        p.setModelOrder(List.of("removed-model", "gpt-5.6-sol"));

        assertEquals(List.of("gpt-5.6-sol", "gpt-5.6-luna"), p.getComboModelIds());
    }

    @Test
    void getComboModelIds_legacyProfileWithoutOrder_aliasedThenCustom_withoutDuplicates() {
        ClaudeProfile p = ClaudeProfile.createNamed("Test");
        p.setOpenaiSubscription(true);
        p.setModelAliases(Map.of("sonnet", "gpt-5.6-terra", "opus", "gpt-5.6-terra", "haiku", "gpt-5.6-luna"));
        p.setCustomModels(List.of("gpt-5.6-sol", "gpt-5.6-luna"));

        assertEquals(List.of("gpt-5.6-terra", "gpt-5.6-luna", "gpt-5.6-sol"), p.getComboModelIds());
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper STORE_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper()
                    .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Test
    void jsonRoundTrip_withAliasesAndCustomModels_keepsModelOrder_andDoesNotStoreComboModelIds()
            throws Exception {
        // Regression: the derived getComboModelIds() was serialized as a property and,
        // on load, Jackson tried to fill the immutable list it returns → every profile
        // except Default failed to load.
        ClaudeProfile p = ClaudeProfile.createNamed("Test");
        p.setOpenaiSubscription(true);
        p.setModelAliases(Map.of("haiku", "gpt-5.6-luna"));
        p.setCustomModels(List.of("gpt-5.6-sol"));
        p.setModelOrder(List.of("gpt-5.6-sol", "gpt-5.6-luna"));

        String json = STORE_MAPPER.writeValueAsString(p);
        assertFalse(json.contains("comboModelIds"), json);

        ClaudeProfile back = STORE_MAPPER.readValue(json, ClaudeProfile.class);
        assertEquals(List.of("gpt-5.6-sol", "gpt-5.6-luna"), back.getModelOrder());
        assertEquals(List.of("gpt-5.6-sol", "gpt-5.6-luna"), back.getComboModelIds());
    }

    @Test
    void jsonLoad_ignoresComboModelIdsWrittenByBuild_1_3_47() throws Exception {
        // Profiles saved by 1.3.47/1.3.48 contain a stray "comboModelIds" array
        String json = "[{\"name\":\"ChatGPT\",\"openaiSubscription\":true,"
                + "\"customModels\":[\"gpt-5.6-sol\"],\"comboModelIds\":[\"gpt-5.6-sol\"]}]";

        List<ClaudeProfile> loaded = STORE_MAPPER.readValue(json,
                new com.fasterxml.jackson.core.type.TypeReference<List<ClaudeProfile>>() {});

        assertEquals(1, loaded.size());
        assertEquals("ChatGPT", loaded.get(0).getName());
        assertEquals(List.of("gpt-5.6-sol"), loaded.get(0).getComboModelIds());
    }

    @Test
    void getComboModelIds_aliasesIgnoredWhereTheyAreNotApplied() {
        ClaudeProfile p = ClaudeProfile.createNamed("Test"); // CLAUDE_MANAGED
        p.setModelAliases(Map.of("sonnet", "stale-model"));
        p.setCustomModels(List.of("custom-1"));

        assertEquals(List.of("custom-1"), p.getComboModelIds());
    }
}
