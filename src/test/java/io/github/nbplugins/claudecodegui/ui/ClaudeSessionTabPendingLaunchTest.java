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
