package io.github.nbclaudecodegui.settings;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ClaudeProfileStore}.
 *
 * <p><b>Note:</b> these tests call {@link ClaudeProfileStore#clearProfiles()}
 * in {@link #setUp()} to start from a known-empty state.  Because
 * {@link org.openide.util.NbPreferences} is not available outside a running
 * NetBeans platform, tests that require persistence are run only when the
 * platform is available; the pure-logic tests (serialisation, Default
 * handling) do not depend on NbPreferences and run in plain JUnit.
 */
class ClaudeProfileStoreTest {

    @BeforeEach
    void setUp() {
        // Start each test with no persisted profiles.
        // NbPreferences may or may not be available; swallow errors.
        try {
            ClaudeProfileStore.clearProfiles();
        } catch (Exception ignored) {
            // platform not available in plain unit-test environment
        }
    }

    // -------------------------------------------------------------------------
    // getProfiles — Default always first
    // -------------------------------------------------------------------------

    @Test
    void getProfiles_defaultAlwaysFirst() {
        List<ClaudeProfile> profiles = ClaudeProfileStore.getProfiles();
        assertFalse(profiles.isEmpty());
        assertTrue(profiles.get(0).isDefault());
        assertEquals("Default", profiles.get(0).getName());
    }

    @Test
    void getProfiles_defaultIsNeverNull() {
        List<ClaudeProfile> profiles = ClaudeProfileStore.getProfiles();
        assertNotNull(profiles.get(0));
    }

    // -------------------------------------------------------------------------
    // resolveConfigDir
    // -------------------------------------------------------------------------

    @Test
    void resolveConfigDir_namedProfile_appendsName() {
        ClaudeProfile p = ClaudeProfile.createNamed("MyProfile");
        Path base = Path.of("/home/user/.netbeans/claude-profiles");
        Path result = ClaudeProfileStore.resolveConfigDir(p, base);
        assertEquals(base.resolve("MyProfile"), result);
    }

    @Test
    void resolveConfigDir_defaultProfile_throwsException() {
        ClaudeProfile def = ClaudeProfile.createDefault();
        Path base = Path.of("/home/user/.netbeans/claude-profiles");
        assertThrows(IllegalArgumentException.class,
                () -> ClaudeProfileStore.resolveConfigDir(def, base));
    }

    // -------------------------------------------------------------------------
    // findByName
    // -------------------------------------------------------------------------

    @Test
    void findByName_blankName_returnsDefault() {
        ClaudeProfile found = ClaudeProfileStore.findByName("");
        assertTrue(found.isDefault());
    }

    @Test
    void findByName_nullName_returnsDefault() {
        ClaudeProfile found = ClaudeProfileStore.findByName(null);
        assertTrue(found.isDefault());
    }

    // -------------------------------------------------------------------------
    // saveProfile / Default cannot be saved
    // -------------------------------------------------------------------------

    @Test
    void saveProfile_defaultProfile_throwsException() {
        assertThrows(IllegalArgumentException.class,
                () -> ClaudeProfileStore.saveProfile(ClaudeProfile.createDefault()));
    }

    // -------------------------------------------------------------------------
    // saveProfiles / getProfiles round-trip
    // -------------------------------------------------------------------------

    @Test
    void jacksonRoundTrip_namedProfile_noUnknownPropertyException() throws Exception {
        // Regression: isDefault() getter caused Jackson to serialize "default":false.
        // With FAIL_ON_UNKNOWN_PROPERTIES=true (Jackson default), deserializing that JSON
        // threw UnrecognizedPropertyException → only Default returned after reload.
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        ClaudeProfile p = ClaudeProfile.createNamed("Work");
        p.setApiKey("sk-test");

        String json = mapper.writeValueAsString(java.util.List.of(p));
        // Must not throw UnrecognizedPropertyException:
        com.fasterxml.jackson.core.type.TypeReference<List<ClaudeProfile>> listType =
                new com.fasterxml.jackson.core.type.TypeReference<>() {};
        List<ClaudeProfile> loaded = mapper.readValue(json, listType);
        assertEquals(1, loaded.size());
        assertEquals("Work", loaded.get(0).getName());
        assertEquals("sk-test", loaded.get(0).getApiKey());
    }

    @Test
    void saveAndReload_namedProfile_persistsCorrectly() {
        // Regression: isDefault() getter caused Jackson to serialize "default":false,
        // which then triggered UnrecognizedPropertyException on reload (FAIL_ON_UNKNOWN_PROPERTIES=true),
        // silently caught → only Default returned.
        try {
            ClaudeProfile p = ClaudeProfile.createNamed("Work");
            p.setApiKey("sk-test");
            ClaudeProfileStore.saveProfiles(java.util.List.of(ClaudeProfile.createDefault(), p));

            List<ClaudeProfile> loaded = ClaudeProfileStore.getProfiles();
            assertEquals(2, loaded.size(), "Expected Default + Work");
            assertEquals("Work", loaded.get(1).getName());
            assertEquals("sk-test", loaded.get(1).getApiKey());
        } catch (Exception e) {
            // NbPreferences unavailable in plain JUnit — skip
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "NbPreferences not available");
        }
    }

    // -------------------------------------------------------------------------
    // saveProfiles — skips Default entries
    // -------------------------------------------------------------------------

    @Test
    void saveProfiles_skipsDefaultEntries() {
        // Build a list that includes the Default
        List<ClaudeProfile> all = ClaudeProfileStore.getProfiles();
        // saving a list that starts with Default must not throw
        assertDoesNotThrow(() -> ClaudeProfileStore.saveProfiles(all));
    }

    // -------------------------------------------------------------------------
    // Corrupt JSON resilience
    // -------------------------------------------------------------------------

    @Test
    void getProfiles_corruptJson_onlyDefaultReturned() {
        // Manually inject corrupt JSON via NbPreferences
        try {
            org.openide.util.NbPreferences.forModule(ClaudeProfileStore.class)
                    .put(ClaudeProfileStore.KEY_PROFILES, "NOT_VALID_JSON");
            List<ClaudeProfile> profiles = ClaudeProfileStore.getProfiles();
            assertEquals(1, profiles.size());
            assertTrue(profiles.get(0).isDefault());
        } catch (Exception e) {
            // NbPreferences unavailable in plain JUnit — skip
        }
    }

    // -------------------------------------------------------------------------
    // Default profile extraCliArgs persistence
    // -------------------------------------------------------------------------

    @Test
    void defaultProfile_extraCliArgs_roundTrip() {
        try {
            ClaudeProfile def = ClaudeProfile.createDefault();
            def.setExtraCliArgs("--verbose");
            ClaudeProfileStore.saveProfiles(java.util.List.of(def));

            List<ClaudeProfile> loaded = ClaudeProfileStore.getProfiles();
            assertTrue(loaded.get(0).isDefault());
            assertEquals("--verbose", loaded.get(0).getExtraCliArgs());
        } catch (Exception e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "NbPreferences not available");
        }
    }

    // -------------------------------------------------------------------------
    // extraCliArgs serialization round-trip
    // -------------------------------------------------------------------------

    @Test
    void extraCliArgs_jacksonRoundTrip() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        ClaudeProfile p = ClaudeProfile.createNamed("Test");
        p.setExtraCliArgs("--verbose --model foo");

        String json = mapper.writeValueAsString(java.util.List.of(p));
        com.fasterxml.jackson.core.type.TypeReference<List<ClaudeProfile>> listType =
                new com.fasterxml.jackson.core.type.TypeReference<>() {};
        List<ClaudeProfile> loaded = mapper.readValue(json, listType);
        assertEquals(1, loaded.size());
        assertEquals("--verbose --model foo", loaded.get(0).getExtraCliArgs());
    }

    // -------------------------------------------------------------------------
    // Default profile full round-trip (bug: all fields except extraCliArgs were lost)
    // -------------------------------------------------------------------------

    @Test
    void defaultProfile_allFields_roundTrip() {
        try {
            ClaudeProfile def = ClaudeProfile.createDefault();
            def.setApiKey("sk-api-key-123");
            def.setToken("oauth-token-xyz");
            def.setBaseUrl("https://custom.api.example.com");
            def.setProxyMode(ClaudeProfile.ProxyMode.CUSTOM);
            def.setHttpProxy("http://proxy.example.com:8080");
            def.setHttpsProxy("https://proxy.example.com:8443");
            def.setNoProxy("localhost,127.0.0.1");
            java.util.List<String[]> defEnvVars = new java.util.ArrayList<>();
            defEnvVars.add(new String[]{"FOO", "bar"});
            def.setExtraEnvVars(defEnvVars);
            def.setExtraCliArgs("--verbose");

            ClaudeProfileStore.saveProfiles(java.util.List.of(def));

            List<ClaudeProfile> loaded = ClaudeProfileStore.getProfiles();
            ClaudeProfile loadedDef = loaded.get(0);
            assertTrue(loadedDef.isDefault());
            assertEquals("sk-api-key-123", loadedDef.getApiKey());
            assertEquals("oauth-token-xyz", loadedDef.getToken());
            assertEquals("https://custom.api.example.com", loadedDef.getBaseUrl());
            assertEquals(ClaudeProfile.ProxyMode.CUSTOM, loadedDef.getProxyMode());
            assertEquals("http://proxy.example.com:8080", loadedDef.getHttpProxy());
            assertEquals("https://proxy.example.com:8443", loadedDef.getHttpsProxy());
            assertEquals("localhost,127.0.0.1", loadedDef.getNoProxy());
            assertEquals("--verbose", loadedDef.getExtraCliArgs());
            assertEquals(1, loadedDef.getExtraEnvVars().size());
            assertArrayEquals(new String[]{"FOO", "bar"}, (Object[]) loadedDef.getExtraEnvVars().get(0));
        } catch (Exception e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "NbPreferences not available");
        }
    }

    @Test
    void namedProfile_allFields_roundTrip() {
        try {
            ClaudeProfile p = ClaudeProfile.createNamed("Work");
            p.setApiKey("sk-named-key");
            p.setToken("named-token");
            p.setBaseUrl("https://api.work.example.com");
            p.setProxyMode(ClaudeProfile.ProxyMode.CUSTOM);
            p.setHttpProxy("http://work-proxy:8080");
            p.setHttpsProxy("https://work-proxy:8443");
            p.setNoProxy("internal.example.com");
            java.util.List<String[]> namedEnvVars = new java.util.ArrayList<>();
            namedEnvVars.add(new String[]{"ENV_VAR", "value1"});
            p.setExtraEnvVars(namedEnvVars);
            p.setExtraCliArgs("--model claude-opus");

            ClaudeProfileStore.saveProfiles(java.util.List.of(ClaudeProfile.createDefault(), p));

            List<ClaudeProfile> loaded = ClaudeProfileStore.getProfiles();
            assertEquals(2, loaded.size());
            ClaudeProfile loadedP = loaded.get(1);
            assertEquals("Work", loadedP.getName());
            assertEquals("sk-named-key", loadedP.getApiKey());
            assertEquals("named-token", loadedP.getToken());
            assertEquals("https://api.work.example.com", loadedP.getBaseUrl());
            assertEquals(ClaudeProfile.ProxyMode.CUSTOM, loadedP.getProxyMode());
            assertEquals("http://work-proxy:8080", loadedP.getHttpProxy());
            assertEquals("https://work-proxy:8443", loadedP.getHttpsProxy());
            assertEquals("internal.example.com", loadedP.getNoProxy());
            assertEquals("--model claude-opus", loadedP.getExtraCliArgs());
            assertEquals(1, loadedP.getExtraEnvVars().size());
            assertArrayEquals(new String[]{"ENV_VAR", "value1"}, (Object[]) loadedP.getExtraEnvVars().get(0));
        } catch (Exception e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "NbPreferences not available");
        }
    }

    // -------------------------------------------------------------------------
    // resolveStorageDir
    // -------------------------------------------------------------------------

    @Test
    void resolveStorageDir_computed() {
        ClaudeProfile p = ClaudeProfile.createNamed("MyProfile");
        Path profilesDir = Path.of("/base/profiles");
        Path result = ClaudeProfileStore.resolveStorageDir(p, profilesDir);
        assertEquals(Path.of("/base/profiles/MyProfile"), result);
    }

    @Test
    void resolveStorageDir_explicit() {
        ClaudeProfile p = ClaudeProfile.createNamed("MyProfile");
        p.withStorageDir("/my/custom/dir");
        Path profilesDir = Path.of("/base/profiles");
        Path result = ClaudeProfileStore.resolveStorageDir(p, profilesDir);
        assertEquals(Path.of("/my/custom/dir"), result);
    }

    @Test
    void resolveStorageDir_default_throws() {
        ClaudeProfile def = ClaudeProfile.createDefault();
        Path profilesDir = Path.of("/base/profiles");
        assertThrows(IllegalArgumentException.class,
                () -> ClaudeProfileStore.resolveStorageDir(def, profilesDir));
    }
}
