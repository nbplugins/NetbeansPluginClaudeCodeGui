package io.github.nbclaudecodegui.ui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for issue #23: {@link ClaudeSessionTab#autoStart(File, String)}
 * must forward the profile's {@code extraCliArgs} to the underlying session start,
 * not pass {@code null}.
 *
 * <p>Before the fix, {@code autoStart(File, String)} called
 * {@code autoStart(dir, profileName, null)}, silently discarding any Extra CLI
 * args configured for the profile in Tools → Options → Profiles.
 */
class ClaudeSessionTabAutoStartTest {

    /**
     * Verifies that {@code autoStart(File, String)} passes a non-null
     * {@code extraCliArgs} string to the three-argument overload.
     *
     * <p>A non-null value guarantees that the profile's CLI args setting
     * (even if empty) is forwarded rather than dropped.
     */
    @Test
    void autoStart_twoArg_passesNonNullExtraCliArgs() throws IOException {
        AtomicReference<String> capturedExtra = new AtomicReference<>("UNSET");

        ClaudeSessionTab tab = new ClaudeSessionTab() {
            @Override
            public void autoStart(File dir, String profileName, String extraCliArgs) {
                capturedExtra.set(extraCliArgs);
            }
        };

        File tmpDir = Files.createTempDirectory("autostart-test").toFile();
        try {
            tab.autoStart(tmpDir, null);
            assertNotNull(capturedExtra.get(),
                    "autoStart(File,String) must not pass null extraCliArgs to the " +
                    "three-argument overload — profile extraCliArgs would be silently discarded");
        } finally {
            tmpDir.delete();
        }
    }
}
