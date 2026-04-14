package io.github.nbclaudecodegui.process;

import io.github.nbclaudecodegui.settings.ClaudeProfile;
import io.github.nbclaudecodegui.settings.ClaudeProfile.ProxyMode;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ClaudeProcess#buildEnv(ClaudeProfile, Path)}.
 *
 * <p>All tests are pure Java — no NetBeans platform required.
 */
class ClaudeProcessProfileTest {

    private static final Path PROFILES_DIR = Path.of("/tmp/claude-profiles");

    // -------------------------------------------------------------------------
    // Null / Default profile
    // -------------------------------------------------------------------------

    @Test
    void buildEnv_nullProfile_containsTermVar() {
        Map<String, String> env = ClaudeProcess.buildEnv(null, PROFILES_DIR);
        assertEquals("xterm-256color", env.get("TERM"));
    }

    @Test
    void buildEnv_nullProfile_noClaudeConfigDir() {
        Map<String, String> env = ClaudeProcess.buildEnv(null, PROFILES_DIR);
        assertFalse(env.containsKey("CLAUDE_CONFIG_DIR"));
    }

    @Test
    void buildEnv_defaultProfile_noClaudeConfigDir() {
        Map<String, String> env = ClaudeProcess.buildEnv(
                ClaudeProfile.createDefault(), PROFILES_DIR);
        assertFalse(env.containsKey("CLAUDE_CONFIG_DIR"));
    }

    // -------------------------------------------------------------------------
    // Named profile — CLAUDE_CONFIG_DIR
    // -------------------------------------------------------------------------

    @Test
    void buildEnv_namedProfile_setsClaudeConfigDir() {
        ClaudeProfile p = ClaudeProfile.createNamed("Work");
        Map<String, String> env = ClaudeProcess.buildEnv(p, PROFILES_DIR);
        String expected = PROFILES_DIR.resolve("Work").toAbsolutePath().toString();
        assertEquals(expected, env.get("CLAUDE_CONFIG_DIR"));
    }

    @Test
    void buildEnv_namedProfile_storageDirMatchesDefault_noClaudeConfigDir() {
        // If a non-default profile's storage dir is explicitly set to ~/.claude
        // (same as the default), CLAUDE_CONFIG_DIR must NOT be set.
        Path defaultClaudeDir = Path.of(System.getProperty("user.home"), ".claude");
        ClaudeProfile p = ClaudeProfile.createNamed("SameAsDefault");
        p.setStorageDir(defaultClaudeDir.toString());
        Map<String, String> env = ClaudeProcess.buildEnv(p, PROFILES_DIR);
        assertFalse(env.containsKey("CLAUDE_CONFIG_DIR"),
                "CLAUDE_CONFIG_DIR must not be set when storage dir equals ~/.claude");
    }

    // -------------------------------------------------------------------------
    // Auth injection
    // -------------------------------------------------------------------------

    @Test
    void buildEnv_apiKeyProfile_doesNotInjectApiKey() {
        // CLAUDE_API type: key is written to settings.local.json as apiKeyHelper, not env var
        ClaudeProfile p = ClaudeProfile.createNamed("API");
        p.setApiKey("sk-test-123");
        Map<String, String> env = ClaudeProcess.buildEnv(p, PROFILES_DIR);
        assertFalse(env.containsKey("ANTHROPIC_API_KEY"),
                "CLAUDE_API key must not be injected as env var (it goes into apiKeyHelper instead)");
    }

    @Test
    void buildEnv_otherApiProfile_injectsAuthTokenAndBaseUrl() {
        ClaudeProfile p = ClaudeProfile.createNamed("Proxy");
        p.setApiKey("sk-test");
        p.setBaseUrl("https://my-proxy.example.com");
        Map<String, String> env = ClaudeProcess.buildEnv(p, PROFILES_DIR);
        assertEquals("sk-test", env.get("ANTHROPIC_AUTH_TOKEN"));
        assertEquals("https://my-proxy.example.com", env.get("ANTHROPIC_BASE_URL"));
        assertFalse(env.containsKey("ANTHROPIC_API_KEY"));
    }

    @Test
    void buildEnv_claudeManagedProfile_noAuthVars() {
        ClaudeProfile p = ClaudeProfile.createNamed("Managed");
        Map<String, String> env = ClaudeProcess.buildEnv(p, PROFILES_DIR);
        assertFalse(env.containsKey("ANTHROPIC_API_KEY"));
        assertFalse(env.containsKey("CLAUDE_CODE_OAUTH_TOKEN"));
    }

    // -------------------------------------------------------------------------
    // Proxy injection
    // -------------------------------------------------------------------------

    @Test
    void buildEnv_noProxy_setsEmptyProxyVars() {
        ClaudeProfile p = ClaudeProfile.createNamed("NoProxy");
        p.setProxyMode(ProxyMode.NO_PROXY);
        Map<String, String> env = ClaudeProcess.buildEnv(p, PROFILES_DIR);
        assertEquals("", env.get("HTTP_PROXY"));
        assertEquals("", env.get("HTTPS_PROXY"));
        assertEquals("", env.get("NO_PROXY"));
    }

    @Test
    void buildEnv_customProxy_injectsProxyValues() {
        ClaudeProfile p = ClaudeProfile.createNamed("CustomProxy");
        p.setProxyMode(ProxyMode.CUSTOM);
        p.setHttpProxy("http://proxy:3128");
        p.setHttpsProxy("http://proxy:3128");
        p.setNoProxy("localhost,127.0.0.1");
        Map<String, String> env = ClaudeProcess.buildEnv(p, PROFILES_DIR);
        assertEquals("http://proxy:3128", env.get("HTTP_PROXY"));
        assertEquals("http://proxy:3128", env.get("HTTPS_PROXY"));
        assertEquals("localhost,127.0.0.1", env.get("NO_PROXY"));
    }

    @Test
    void buildEnv_systemManagedProxy_noProxyVarsAdded() {
        ClaudeProfile p = ClaudeProfile.createNamed("SysProxy");
        p.setProxyMode(ProxyMode.SYSTEM_MANAGED);
        Map<String, String> env = ClaudeProcess.buildEnv(p, PROFILES_DIR);
        // HTTP_PROXY may come from System.getenv() — we just verify the method
        // doesn't forcibly set it to empty
        String val = env.get("HTTP_PROXY");
        // If HTTP_PROXY is set by the system, accept it; if not, it should be absent
        if (System.getenv("HTTP_PROXY") == null) {
            assertFalse(env.containsKey("HTTP_PROXY"));
        } else {
            assertNotNull(val);
        }
    }

    // -------------------------------------------------------------------------
    // NO_PROXY auto-injection
    // -------------------------------------------------------------------------

    @Test
    void buildEnv_customProxy_noProxyBlank_injectsLocalhost() {
        ClaudeProfile p = ClaudeProfile.createNamed("CustomProxyNoNP");
        p.setProxyMode(ProxyMode.CUSTOM);
        p.setHttpProxy("http://proxy:3128");
        p.setHttpsProxy("");
        p.setNoProxy("");
        Map<String, String> env = ClaudeProcess.buildEnv(p, PROFILES_DIR);
        String noProxy = env.getOrDefault("NO_PROXY", "");
        assertTrue(noProxy.contains("localhost"), "NO_PROXY should contain localhost, was: " + noProxy);
        assertTrue(noProxy.contains("127.0.0.1"), "NO_PROXY should contain 127.0.0.1, was: " + noProxy);
    }

    @Test
    void buildEnv_customProxy_noProxyHasLocalhost_notDuplicated() {
        ClaudeProfile p = ClaudeProfile.createNamed("CustomProxyHasNP");
        p.setProxyMode(ProxyMode.CUSTOM);
        p.setHttpProxy("http://proxy:3128");
        p.setHttpsProxy("");
        p.setNoProxy("localhost,127.0.0.1");
        Map<String, String> env = ClaudeProcess.buildEnv(p, PROFILES_DIR);
        String noProxy = env.getOrDefault("NO_PROXY", "");
        // Should not contain duplicates
        int localhostCount = noProxy.split("localhost", -1).length - 1;
        assertEquals(1, localhostCount, "localhost should appear exactly once in NO_PROXY: " + noProxy);
    }

    @Test
    void buildEnv_noProxyActive_noLocalhostInjected() {
        ClaudeProfile p = ClaudeProfile.createNamed("CustomNoProxyActive");
        p.setProxyMode(ProxyMode.NO_PROXY);
        Map<String, String> env = ClaudeProcess.buildEnv(p, PROFILES_DIR);
        // NO_PROXY should be empty (explicitly cleared by NO_PROXY mode)
        // and since no proxy is set, ensureLocalhostInNoProxy should not add anything
        String noProxy = env.getOrDefault("NO_PROXY", "");
        assertFalse(noProxy.contains("localhost"),
                "With no proxy active, localhost should not be injected: " + noProxy);
    }

    // -------------------------------------------------------------------------
    // Extra env vars
    // -------------------------------------------------------------------------

    @Test
    void buildEnv_extraVars_injected() {
        ClaudeProfile p = ClaudeProfile.createNamed("Bedrock");
        java.util.List<String[]> extraList = new java.util.ArrayList<>();
        extraList.add(new String[]{"AWS_REGION", "eu-west-1"});
        extraList.add(new String[]{"AWS_PROFILE", "default"});
        p.setExtraEnvVars(extraList);
        Map<String, String> env = ClaudeProcess.buildEnv(p, PROFILES_DIR);
        assertEquals("eu-west-1", env.get("AWS_REGION"));
        assertEquals("default", env.get("AWS_PROFILE"));
    }

    @Test
    void buildEnv_extraVars_overrideApiKey() {
        ClaudeProfile p = ClaudeProfile.createNamed("Override");
        p.setApiKey("sk-original");
        java.util.List<String[]> overrideList = new java.util.ArrayList<>();
        overrideList.add(new String[]{"ANTHROPIC_API_KEY", "sk-overridden"});
        p.setExtraEnvVars(overrideList);
        Map<String, String> env = ClaudeProcess.buildEnv(p, PROFILES_DIR);
        assertEquals("sk-overridden", env.get("ANTHROPIC_API_KEY"));
    }
}
