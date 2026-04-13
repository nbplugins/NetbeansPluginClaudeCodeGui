package io.github.nbclaudecodegui.settings;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.prefs.BackingStoreException;
import org.openide.util.NbPreferences;

/**
 * Persistence layer for {@link ClaudeProfile} objects.
 *
 * <p>Profiles are serialised as a JSON array and stored under a single
 * {@link org.openide.util.NbPreferences} key ({@value #KEY_PROFILES}).
 * The built-in <em>Default</em> profile is never persisted — it is
 * constructed programmatically and always returned as the first element
 * of {@link #getProfiles()}.
 *
 * <p>On any JSON parse error the store falls back gracefully: only the
 * Default profile is returned and no exception is thrown.
 *
 * <h2>Thread safety</h2>
 * All public methods may be called from any thread.  Reads and writes
 * go through {@link NbPreferences} which is itself thread-safe.
 */
public final class ClaudeProfileStore {

    private static final Logger LOG =
            Logger.getLogger(ClaudeProfileStore.class.getName());

    /** NbPreferences key under which the JSON array is stored. */
    public static final String KEY_PROFILES = "profiles";

    /** NbPreferences key under which the full Default profile JSON is stored. */
    private static final String KEY_DEFAULT_PROFILE = "defaultProfile";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final TypeReference<List<ClaudeProfile>> LIST_TYPE =
            new TypeReference<>() {};

    private ClaudeProfileStore() {}

    // -------------------------------------------------------------------------
    // Read
    // -------------------------------------------------------------------------

    /**
     * Returns all profiles, with the Default profile always first.
     *
     * <p>If the stored JSON is missing or corrupt, only the Default profile
     * is returned.
     *
     * @return mutable list; first element is always the Default profile
     */
    public static List<ClaudeProfile> getProfiles() {
        List<ClaudeProfile> result = new ArrayList<>();
        ClaudeProfile def = ClaudeProfile.createDefault();

        String defaultJson = NbPreferences.forModule(ClaudeProfileStore.class)
                .get(KEY_DEFAULT_PROFILE, null);
        if (defaultJson != null) {
            try {
                ClaudeProfile stored = MAPPER.readValue(defaultJson, ClaudeProfile.class);
                def.setToken(stored.getToken());
                def.setApiKey(stored.getApiKey());
                def.setBaseUrl(stored.getBaseUrl());
                def.setProxyMode(stored.getProxyMode());
                def.setHttpProxy(stored.getHttpProxy());
                def.setHttpsProxy(stored.getHttpsProxy());
                def.setNoProxy(stored.getNoProxy());
                def.setExtraEnvVars(stored.getExtraEnvVars());
                def.setExtraCliArgs(stored.getExtraCliArgs());
                def.setModelAliases(stored.getModelAliases());
                def.setCustomModels(stored.getCustomModels());
                def.setStorageDir(stored.getStorageDir());
            } catch (Exception e) {
                LOG.warning("Could not parse stored Default profile JSON, using defaults. Cause: " + e);
                def.setExtraCliArgs(ClaudeCodePreferences.getDefaultExtraCliArgs());
            }
        } else {
            def.setExtraCliArgs(ClaudeCodePreferences.getDefaultExtraCliArgs());
        }

        result.add(def);

        String json = NbPreferences.forModule(ClaudeProfileStore.class)
                .get(KEY_PROFILES, "[]");
        try {
            List<ClaudeProfile> stored = MAPPER.readValue(json, LIST_TYPE);
            result.addAll(stored);
            LOG.fine(() -> "Loaded " + stored.size() + " profile(s): " + profileNames(stored));
        } catch (Exception e) {
            LOG.warning("Could not parse stored profiles JSON, returning only Default. Cause: " + e);
        }

        return result;
    }

    /**
     * Looks up a profile by name.
     *
     * @param name profile name, or {@code null}/{@code ""} for Default
     * @return the matching profile, or the Default profile if not found
     */
    public static ClaudeProfile findByName(String name) {
        if (name == null || name.isBlank()) {
            return ClaudeProfile.createDefault();
        }
        for (ClaudeProfile p : getProfiles()) {
            if (p.getName().equals(name)) {
                return p;
            }
        }
        return ClaudeProfile.createDefault();
    }

    // -------------------------------------------------------------------------
    // Write
    // -------------------------------------------------------------------------

    /**
     * Persists the given list of profiles (excluding the Default profile,
     * which is always built programmatically).
     *
     * <p>Any profile whose {@link ClaudeProfile#isDefault()} returns
     * {@code true} is silently skipped.
     *
     * @param profiles the profiles to save; {@code null} clears the list
     */
    public static void saveProfiles(List<ClaudeProfile> profiles) {
        List<ClaudeProfile> toSave = new ArrayList<>();
        if (profiles != null) {
            for (ClaudeProfile p : profiles) {
                if (p.isDefault()) {
                    ClaudeCodePreferences.setDefaultExtraCliArgs(p.getExtraCliArgs()); // back-compat
                    try {
                        String defaultJson = MAPPER.writeValueAsString(p);
                        NbPreferences.forModule(ClaudeProfileStore.class)
                                .put(KEY_DEFAULT_PROFILE, defaultJson);
                    } catch (Exception e) {
                        LOG.severe("Could not serialise Default profile: " + e.getMessage());
                    }
                } else {
                    toSave.add(p);
                }
            }
        }
        try {
            String json = MAPPER.writeValueAsString(toSave);
            NbPreferences.forModule(ClaudeProfileStore.class)
                    .put(KEY_PROFILES, json);
            LOG.fine(() -> "Saved " + toSave.size() + " profile(s): " + profileNames(toSave));
        } catch (Exception e) {
            LOG.severe("Could not serialise profiles: " + e.getMessage());
        }
    }

    /**
     * Saves a single profile, replacing the existing entry with the same id
     * or appending it if not found.
     *
     * @param profile profile to save; must not be the Default profile
     * @throws IllegalArgumentException if {@code profile.isDefault()} is {@code true}
     */
    public static void saveProfile(ClaudeProfile profile) {
        if (profile.isDefault()) {
            throw new IllegalArgumentException("The Default profile cannot be persisted.");
        }
        List<ClaudeProfile> all = getProfiles();
        List<ClaudeProfile> named = new ArrayList<>();
        boolean replaced = false;
        for (ClaudeProfile p : all) {
            if (p.isDefault()) continue;
            if (p.getId().equals(profile.getId())) {
                named.add(profile);
                replaced = true;
            } else {
                named.add(p);
            }
        }
        if (!replaced) {
            named.add(profile);
        }
        saveProfiles(named);
    }

    /**
     * Removes the profile with the given id from persistent storage.
     *
     * <p>Silently does nothing if no such profile exists or if
     * {@code id} is blank (Default).
     *
     * @param id the profile id to remove
     */
    public static void removeProfile(String id) {
        if (id == null || id.isBlank()) return;
        List<ClaudeProfile> all = getProfiles();
        List<ClaudeProfile> updated = new ArrayList<>();
        for (ClaudeProfile p : all) {
            if (!p.isDefault() && !p.getId().equals(id)) {
                updated.add(p);
            }
        }
        saveProfiles(updated);
    }

    /**
     * Clears all persisted profiles (leaves only the Default in memory).
     */
    public static void clearProfiles() {
        try {
            NbPreferences.forModule(ClaudeProfileStore.class)
                    .remove(KEY_PROFILES);
            NbPreferences.forModule(ClaudeProfileStore.class).flush();
        } catch (BackingStoreException e) {
            LOG.warning("Could not flush prefs after clear: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Config-dir resolution
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /** Returns a comma-separated list of profile names (no credentials). */
    private static String profileNames(List<ClaudeProfile> profiles) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < profiles.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(profiles.get(i).getName());
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Returns the storage directory (used as {@code CLAUDE_CONFIG_DIR}) for a
     * non-Default profile.
     *
     * <p>If the profile has an explicit {@link ClaudeProfile#getStorageDir()} set,
     * that path is returned as-is.  Otherwise the computed path
     * {@code <profilesDir>/<profile.getId()>} is used.
     *
     * @param profile     the profile (must not be Default)
     * @param profilesDir base directory for per-profile config dirs; used only
     *                    when no explicit path is set
     * @return absolute path to this profile's config directory
     * @throws IllegalArgumentException if {@code profile.isDefault()} is {@code true}
     */
    public static Path resolveStorageDir(ClaudeProfile profile, Path profilesDir) {
        if (profile.isDefault()) {
            throw new IllegalArgumentException(
                    "Default profile has no isolated config dir; caller must not set CLAUDE_CONFIG_DIR.");
        }
        String explicit = profile.getStorageDir();
        if (explicit != null && !explicit.isBlank()) {
            return Path.of(explicit);
        }
        return profilesDir.resolve(profile.getId());
    }

    /**
     * @deprecated Use {@link #resolveStorageDir(ClaudeProfile, Path)} instead.
     */
    @Deprecated
    public static Path resolveConfigDir(ClaudeProfile profile, Path profilesDir) {
        return resolveStorageDir(profile, profilesDir);
    }

    /**
     * Returns the settings directory for any profile, including Default.
     * Default profile maps to {@code ~/.claude/}; named profiles map to
     * {@code <profilesDir>/<profile.getId()>/}.
     */
    private static Path resolveSettingsDir(ClaudeProfile profile, Path profilesDir) {
        if (profile.isDefault()) {
            return Path.of(System.getProperty("user.home"), ".claude");
        }
        return profilesDir.resolve(profile.getId());
    }
}
