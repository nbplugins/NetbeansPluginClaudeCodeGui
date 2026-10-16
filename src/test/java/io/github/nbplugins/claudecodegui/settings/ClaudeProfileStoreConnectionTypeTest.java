package io.github.nbplugins.claudecodegui.settings;

import io.github.nbplugins.claudecodegui.settings.ClaudeProfile.ConnectionType;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.openide.util.NbPreferences;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Reading the connection type of the Default and of a custom profile from settings
 * written by older plugin versions (no {@code connectionType}, legacy
 * {@code openaiProxy}/{@code openaiSubscription} flags) and by the current version
 * (explicit {@code connectionType}); issue #191.
 *
 * <p>Profiles are stored as JSON strings in {@link NbPreferences} and read back through
 * {@link ClaudeProfileStore#getProfiles()}, the production loading path.
 */
class ClaudeProfileStoreConnectionTypeTest {

    private static final String KEY_DEFAULT = "defaultProfile"; // ClaudeProfileStore.KEY_DEFAULT_PROFILE (private)

    @BeforeEach
    @AfterEach
    void clean() {
        NbPreferences.forModule(ClaudeProfileStore.class).remove(KEY_DEFAULT);
        ClaudeProfileStore.clearProfiles();
    }

    /** {name, JSON body fields, expected type}. */
    static Stream<Arguments> settings() {
        return Stream.of(
                // --- old settings: no connectionType, legacy flags + credentials ---
                Arguments.of("old managed", "", ConnectionType.CLAUDE_MANAGED),
                Arguments.of("old subscription", "\"token\":\"t\"", ConnectionType.SUBSCRIPTION),
                Arguments.of("old claude api", "\"apiKey\":\"k\"", ConnectionType.CLAUDE_API),
                Arguments.of("old other api", "\"apiKey\":\"k\",\"baseUrl\":\"https://x/v1\"",
                        ConnectionType.OTHER_API),
                Arguments.of("old openai proxy (issue 191)",
                        "\"openaiProxy\":true,\"apiKey\":\"k\",\"baseUrl\":\"https://x/v1\"",
                        ConnectionType.OPENAI_PROXY),
                Arguments.of("old chatgpt subscription",
                        "\"openaiSubscription\":true,\"chatgptAccessToken\":\"a\",\"chatgptRefreshToken\":\"r\"",
                        ConnectionType.OPENAI_SUBSCRIPTION),
                Arguments.of("old, both openai flags", "\"openaiProxy\":true,\"openaiSubscription\":true",
                        ConnectionType.OPENAI_SUBSCRIPTION),
                Arguments.of("old, apiKey and token", "\"apiKey\":\"k\",\"token\":\"t\"",
                        ConnectionType.CLAUDE_API),
                Arguments.of("old, explicit false flags", "\"openaiProxy\":false,\"openaiSubscription\":false",
                        ConnectionType.CLAUDE_MANAGED),
                // --- new settings: explicit connectionType ---
                Arguments.of("new managed", "\"connectionType\":\"CLAUDE_MANAGED\"", ConnectionType.CLAUDE_MANAGED),
                Arguments.of("new subscription", "\"connectionType\":\"SUBSCRIPTION\",\"token\":\"t\"",
                        ConnectionType.SUBSCRIPTION),
                Arguments.of("new claude api", "\"connectionType\":\"CLAUDE_API\",\"apiKey\":\"k\"",
                        ConnectionType.CLAUDE_API),
                Arguments.of("new other api",
                        "\"connectionType\":\"OTHER_API\",\"apiKey\":\"k\",\"baseUrl\":\"https://x/v1\"",
                        ConnectionType.OTHER_API),
                Arguments.of("new openai proxy",
                        "\"connectionType\":\"OPENAI_PROXY\",\"apiKey\":\"k\",\"baseUrl\":\"https://x/v1\"",
                        ConnectionType.OPENAI_PROXY),
                Arguments.of("new chatgpt subscription", "\"connectionType\":\"OPENAI_SUBSCRIPTION\"",
                        ConnectionType.OPENAI_SUBSCRIPTION),
                Arguments.of("new wins over stale legacy flags",
                        "\"connectionType\":\"CLAUDE_API\",\"apiKey\":\"k\",\"openaiProxy\":true",
                        ConnectionType.CLAUDE_API),
                Arguments.of("unknown future type falls back to legacy derivation",
                        "\"connectionType\":\"SOMETHING_NEW\",\"apiKey\":\"k\",\"baseUrl\":\"https://x/v1\"",
                        ConnectionType.OTHER_API));
    }

    @ParameterizedTest(name = "Default: {0}")
    @MethodSource("settings")
    void defaultProfile_readsConnectionType(String name, String fields, ConnectionType expected) {
        NbPreferences.forModule(ClaudeProfileStore.class).put(KEY_DEFAULT, "{" + fields + "}");

        ClaudeProfile loaded = ClaudeProfileStore.getProfiles().get(0);

        assertTrue(loaded.isDefault());
        assertEquals("Default", loaded.getName());
        assertEquals(expected, loaded.getConnectionType());
    }

    @ParameterizedTest(name = "Custom: {0}")
    @MethodSource("settings")
    void customProfile_readsConnectionType(String name, String fields, ConnectionType expected) {
        String body = "\"name\":\"Work\",\"id\":\"Work\"" + (fields.isEmpty() ? "" : "," + fields);
        NbPreferences.forModule(ClaudeProfileStore.class).put(ClaudeProfileStore.KEY_PROFILES, "[{" + body + "}]");

        List<ClaudeProfile> profiles = ClaudeProfileStore.getProfiles();

        assertEquals(2, profiles.size());
        assertEquals("Work", profiles.get(1).getName());
        assertEquals(expected, profiles.get(1).getConnectionType());
    }

    @Test
    void defaultProfile_oldOpenaiProxy_keepsCredentials() {
        NbPreferences.forModule(ClaudeProfileStore.class).put(KEY_DEFAULT,
                "{\"openaiProxy\":true,\"apiKey\":\"sk\",\"baseUrl\":\"https://x/v1\","
                        + "\"proxyMode\":\"CUSTOM\",\"httpProxy\":\"http://p:3128\","
                        + "\"modelAliases\":{\"sonnet\":\"gpt-x\"},\"extraCliArgs\":\"--verbose\"}");

        ClaudeProfile loaded = ClaudeProfileStore.getProfiles().get(0);

        assertEquals("sk", loaded.getApiKey());
        assertEquals("https://x/v1", loaded.getBaseUrl());
        assertEquals(ClaudeProfile.ProxyMode.CUSTOM, loaded.getProxyMode());
        assertEquals("http://p:3128", loaded.getHttpProxy());
        assertEquals("gpt-x", loaded.getModelAliases().get("sonnet"));
        assertEquals("--verbose", loaded.getExtraCliArgs());
        assertEquals("", loaded.getId());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("settings")
    void write_alwaysUsesNewFormat_andIsLossless(String name, String fields, ConnectionType expected) throws Exception {
        NbPreferences.forModule(ClaudeProfileStore.class).put(KEY_DEFAULT, "{" + fields + "}");
        ClaudeProfile loaded = ClaudeProfileStore.getProfiles().get(0);

        ClaudeProfileStore.saveProfiles(List.of(loaded));
        String written = NbPreferences.forModule(ClaudeProfileStore.class).get(KEY_DEFAULT, null);

        assertNotNull(written);
        assertTrue(written.contains("\"connectionType\":\"" + expected.name() + "\""), written);
        assertFalse(written.contains("openaiProxy"), written);
        assertFalse(written.contains("openaiSubscription"), written);
        ClaudeProfile reloaded = ClaudeProfileStore.getProfiles().get(0);
        assertEquals(expected, reloaded.getConnectionType());
        assertEquals(loaded.getApiKey(), reloaded.getApiKey());
        assertEquals(loaded.getBaseUrl(), reloaded.getBaseUrl());
        assertEquals(loaded.getToken(), reloaded.getToken());
    }
}
