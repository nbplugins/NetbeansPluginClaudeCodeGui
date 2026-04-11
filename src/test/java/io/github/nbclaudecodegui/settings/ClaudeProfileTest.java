package io.github.nbclaudecodegui.settings;

import io.github.nbclaudecodegui.settings.ClaudeProfile.ConnectionType;
import io.github.nbclaudecodegui.settings.ClaudeProfile.ProxyMode;
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
    void toEnvVars_claudeApi_injectsApiKey() {
        ClaudeProfile p = ClaudeProfile.createNamed("P");
        p.setApiKey("sk-123");
        Map<String, String> env = p.toEnvVars();
        assertEquals("sk-123", env.get("ANTHROPIC_API_KEY"));
        assertFalse(env.containsKey("ANTHROPIC_BASE_URL"));
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
}
