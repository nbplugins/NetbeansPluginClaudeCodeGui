package io.github.nbplugins.claudecodegui.ui;

import io.github.nbplugins.claudecodegui.model.ClaudeSessionModel;
import io.github.nbplugins.claudecodegui.model.SessionMode;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that restart actions target the project being launched while that
 * launch is still being prepared.
 *
 * <p>{@code ClaudeSessionModel.getWorkingDirectory()} only changes when the
 * process actually starts, so during the off-EDT preparation it still names the
 * previous project. A restart requested in that window (e.g. "Start New
 * Session" after Restart Advanced switched the tab from project A to B) must
 * use B, not A.
 */
class ClaudeSessionTabPendingLaunchTest {

    @Test
    void restartAfterAPendingLaunchUsesThatLaunchesDirectory() throws Exception {
        Path tmp = Files.createTempDirectory("pending-launch-test");
        try {
            File previous = tmp.resolve("project-a").toFile();
            File pending = tmp.resolve("project-b").toFile();
            AtomicReference<File> restartedIn = new AtomicReference<>();

            ClaudeSessionTab tab = new ClaudeSessionTab() {
                @Override
                protected void restartSession(File dir, String profileName,
                                              String extraCliArgs, SessionMode mode,
                                              String resumeId) {
                    restartedIn.set(dir);
                }
            };
            modelOf(tab).setWorkingDirectory(previous);
            setField(tab, "pendingLaunchDir", pending);

            tab.onSaveAndSwitch("", SessionMode.NEW, null);

            assertEquals(pending, restartedIn.get(),
                    "a restart must target the project being launched, not the "
                            + "previous one still held by the model");
        } finally {
            tmp.toFile().delete();
        }
    }

    /**
     * Closing the tab while a launch is still being prepared cancels that
     * launch, but reopening the tab must restore the project it was going to
     * use — not the previous one still held by the model.
     */
    @Test
    void closingWhileAPendingLaunchSavesThatLaunchesDirectory() throws Exception {
        Path tmp = Files.createTempDirectory("pending-launch-close-test");
        try {
            File previous = tmp.resolve("project-a").toFile();
            File pending = tmp.resolve("project-b").toFile();
            ClaudeSessionTab tab = new ClaudeSessionTab();
            modelOf(tab).setWorkingDirectory(previous);
            setField(tab, "pendingLaunchDir", pending);

            tab.componentClosed();

            assertEquals(pending.getAbsolutePath(), pathToRestore(tab),
                    "closing during a launch must restore the project being launched");
        } finally {
            tmp.toFile().delete();
        }
    }

    /**
     * NetBeans persists open tabs while a launch may still be preparing; the
     * saved path must be the project being launched, not the previous one.
     */
    @Test
    void persistingDuringAPendingLaunchStoresThatLaunchesDirectory() throws Exception {
        Path tmp = Files.createTempDirectory("pending-launch-save-test");
        try {
            File previous = tmp.resolve("project-a").toFile();
            File pending = tmp.resolve("project-b").toFile();
            ClaudeSessionTab tab = new ClaudeSessionTab();
            modelOf(tab).setWorkingDirectory(previous);
            setField(tab, "pendingLaunchDir", pending);

            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            try (java.io.ObjectOutputStream out = new java.io.ObjectOutputStream(bos)) {
                tab.writeExternal(out);
            }
            String serialized = new String(bos.toByteArray(),
                    java.nio.charset.StandardCharsets.ISO_8859_1);

            // The superclass metadata (tab name/tooltip) may still describe the
            // previous project until the launch starts; the restored path is the
            // project being launched.
            assertTrue(serialized.contains(pending.getAbsolutePath()),
                    "the saved path must be the project being launched");
        } finally {
            tmp.toFile().delete();
        }
    }

    /**
     * Opening the same project again while its launch is still being prepared
     * must focus that tab instead of creating a second one: the model still
     * reports the previous directory (or none) until the process starts.
     */
    @Test
    void aTabWithAPendingLaunchServesThatDirectory() throws Exception {
        Path tmp = Files.createTempDirectory("pending-launch-lookup-test");
        try {
            File previous = tmp.resolve("project-a").toFile();
            File pending = tmp.resolve("project-b").toFile();
            ClaudeSessionTab tab = new ClaudeSessionTab();
            modelOf(tab).setWorkingDirectory(previous);
            setField(tab, "pendingLaunchDir", pending);

            assertTrue(tab.servesDirectory(pending),
                    "a tab whose launch is pending already serves that directory");
            assertFalse(tab.servesDirectory(previous),
                    "the previous project must not be treated as served any more");
        } finally {
            tmp.toFile().delete();
        }
    }

    /**
     * File references pasted or dropped into the prompt are stored relative to
     * the project the session will run in. During a pending launch the model
     * still names the previous project, so the supplier must use the pending
     * one — otherwise the reference points at a path inside the wrong project.
     */
    @Test
    void promptFileReferencesUseThePendingLaunchDirectory() throws Exception {
        Path tmp = Files.createTempDirectory("pending-launch-prompt-test");
        try {
            File previous = tmp.resolve("repo").toFile();
            File pending = tmp.resolve("repo").resolve("module").toFile();
            ClaudeSessionTab tab = new ClaudeSessionTab();
            modelOf(tab).setWorkingDirectory(previous);
            setField(tab, "pendingLaunchDir", pending);

            assertEquals(pending.getAbsolutePath(), tab.currentOrPendingDirectoryPath(),
                    "file references must be relative to the project being launched");
        } finally {
            tmp.toFile().delete();
        }
    }

    /**
     * A profile may set {@code CLAUDE_CONFIG_DIR} through its extra environment
     * variables, which takes precedence over its storage dir. A pending launch
     * must resolve the directory exactly as the process will, or the dialog
     * would list one store and the started process use another.
     */
    @Test
    void pendingLaunchConfigDirAppliesTheProfilesEnvironmentOverride() throws Exception {
        Path profiles = Files.createTempDirectory("pending-launch-config-test");
        try {
            io.github.nbplugins.claudecodegui.settings.ClaudeProfile profile =
                    io.github.nbplugins.claudecodegui.settings.ClaudeProfile.createNamed("Work");
            assertEquals(profiles.resolve("Work"),
                    ClaudeSessionTab.resolveLaunchConfigDir(profile, profiles),
                    "a named profile uses its storage dir by default");

            Path override = profiles.resolve("override-store");
            profile.setExtraEnvVars(java.util.Collections.singletonList(
                    new String[]{"CLAUDE_CONFIG_DIR", override.toString()}));
            assertEquals(override,
                    ClaudeSessionTab.resolveLaunchConfigDir(profile, profiles),
                    "the environment override must win over the storage dir");

            io.github.nbplugins.claudecodegui.settings.ClaudeProfile fallback =
                    io.github.nbplugins.claudecodegui.settings.ClaudeProfile.createDefault();
            Path defaultOverride = profiles.resolve("default-override-store");
            fallback.setExtraEnvVars(java.util.Collections.singletonList(
                    new String[]{"CLAUDE_CONFIG_DIR", defaultOverride.toString()}));
            assertEquals(defaultOverride,
                    ClaudeSessionTab.resolveLaunchConfigDir(fallback, profiles),
                    "the default profile's environment override must be honored too");
        } finally {
            profiles.toFile().delete();
        }
    }

    private static String pathToRestore(ClaudeSessionTab tab) throws Exception {
        Field f = ClaudeSessionTab.class.getDeclaredField("pathToRestore");
        f.setAccessible(true);
        return (String) f.get(tab);
    }

    private static ClaudeSessionModel modelOf(ClaudeSessionTab tab) throws Exception {
        Field f = ClaudeSessionTab.class.getDeclaredField("model");
        f.setAccessible(true);
        return (ClaudeSessionModel) f.get(tab);
    }

    private static void setField(ClaudeSessionTab tab, String name, Object value)
            throws Exception {
        Field f = ClaudeSessionTab.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(tab, value);
    }
}
