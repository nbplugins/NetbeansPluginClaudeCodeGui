package io.github.nbclaudecodegui.settings;

import java.io.File;
import java.util.logging.Logger;
import org.openide.util.NbPreferences;

/**
 * Per-project profile assignment.
 *
 * <p>Stores the name of the {@link ClaudeProfile} assigned to a project,
 * keyed by the project's root directory absolute path.  Assignments are
 * persisted in {@link NbPreferences} under keys of the form
 * {@code "project." + projectDir.getAbsolutePath()}.
 *
 * <p>When the Default profile is assigned the entry is deleted (Default
 * is the implicit fallback and needs no explicit storage).
 *
 * <h2>Usage example</h2>
 * <pre>{@code
 * // Assign a profile to a project
 * ClaudeProjectProperties.setProfileName(projectDir, "MyProfile");
 *
 * // Retrieve the assigned profile name (falls back to Default when blank)
 * String profileName = ClaudeProjectProperties.getProfileName(projectDir);
 * ClaudeProfile profile = ClaudeProfileStore.findByName(profileName);
 * }</pre>
 */
public final class ClaudeProjectProperties {

    private static final Logger LOG =
            Logger.getLogger(ClaudeProjectProperties.class.getName());

    /** Prefix used for NbPreferences keys. */
    private static final String KEY_PREFIX = "project.";

    private ClaudeProjectProperties() {}

    // -------------------------------------------------------------------------
    // Read
    // -------------------------------------------------------------------------

    /**
     * Returns the profile name assigned to the given project directory,
     * or an empty string if no assignment exists (i.e. Default is used).
     *
     * @param projectDir the project root directory; must not be {@code null}
     * @return assigned profile name, or {@code ""} for Default
     */
    public static String getProfileName(File projectDir) {
        if (projectDir == null) return "";
        return NbPreferences.forModule(ClaudeProjectProperties.class)
                .get(KEY_PREFIX + projectDir.getAbsolutePath(), "");
    }

    // -------------------------------------------------------------------------
    // Write
    // -------------------------------------------------------------------------

    /**
     * Assigns the named profile to the given project directory.
     *
     * <p>Passing {@code null}, {@code ""}, or {@code "Default"} removes the
     * assignment (falling back to Default).
     *
     * @param projectDir  the project root directory; must not be {@code null}
     * @param profileName profile name to assign, or blank / {@code "Default"} to clear
     */
    public static void setProfileName(File projectDir, String profileName) {
        if (projectDir == null) return;
        String key = KEY_PREFIX + projectDir.getAbsolutePath();
        if (profileName == null || profileName.isBlank()
                || ClaudeProfile.DEFAULT_NAME.equals(profileName)) {
            NbPreferences.forModule(ClaudeProjectProperties.class).remove(key);
        } else {
            NbPreferences.forModule(ClaudeProjectProperties.class).put(key, profileName);
        }
    }

    /**
     * Removes the profile assignment for all projects that reference the
     * given profile name.  Called when a profile is renamed or deleted so
     * that stale assignments do not accumulate.
     *
     * @param profileName profile name to clear
     */
    public static void clearAssignmentsForProfile(String profileName) {
        if (profileName == null || profileName.isBlank()) return;
        try {
            var prefs = NbPreferences.forModule(ClaudeProjectProperties.class);
            for (String key : prefs.keys()) {
                if (key.startsWith(KEY_PREFIX)) {
                    if (profileName.equals(prefs.get(key, ""))) {
                        prefs.remove(key);
                    }
                }
            }
        } catch (java.util.prefs.BackingStoreException e) {
            LOG.warning("Could not clear assignments for profile '" + profileName + "': " + e.getMessage());
        }
    }

    /**
     * Renames assignments: replaces all occurrences of {@code oldName} with
     * {@code newName}.  Called after a profile is renamed so that project
     * assignments stay in sync.
     *
     * @param oldName previous profile name
     * @param newName new profile name
     */
    public static void renameAssignments(String oldName, String newName) {
        if (oldName == null || oldName.isBlank()) return;
        if (newName == null || newName.isBlank()) return;
        try {
            var prefs = NbPreferences.forModule(ClaudeProjectProperties.class);
            for (String key : prefs.keys()) {
                if (key.startsWith(KEY_PREFIX)) {
                    if (oldName.equals(prefs.get(key, ""))) {
                        prefs.put(key, newName);
                    }
                }
            }
        } catch (java.util.prefs.BackingStoreException e) {
            LOG.warning("Could not rename assignments from '" + oldName
                    + "' to '" + newName + "': " + e.getMessage());
        }
    }
}
