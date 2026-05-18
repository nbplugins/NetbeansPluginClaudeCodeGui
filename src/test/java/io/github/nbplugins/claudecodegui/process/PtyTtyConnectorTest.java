package io.github.nbplugins.claudecodegui.process;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import java.awt.Dimension;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PtyTtyConnector}.
 */
class PtyTtyConnectorTest {

    private static PtyProcess startCat() throws Exception {
        return new PtyProcessBuilder(new String[]{"/bin/cat"})
                .setEnvironment(Map.of("TERM", "xterm"))
                .setDirectory(System.getProperty("java.io.tmpdir"))
                .setConsole(false)
                .setRedirectErrorStream(true)
                .start();
    }

    @Test
    void testIsConnected() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(
                System.getProperty("os.name", "").toLowerCase().contains("win"));

        PtyProcess p = startCat();
        PtyTtyConnector c = new PtyTtyConnector(p);
        assertTrue(c.isConnected());
        c.close();
        p.waitFor();
        assertFalse(c.isConnected());
    }

    @Test
    void testClose() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(
                System.getProperty("os.name", "").toLowerCase().contains("win"));

        PtyProcess p = startCat();
        PtyTtyConnector c = new PtyTtyConnector(p);
        c.close();
        p.waitFor();
        assertFalse(p.isAlive());
    }

    @Test
    void testResize() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(
                System.getProperty("os.name", "").toLowerCase().contains("win"));

        PtyProcess p = startCat();
        PtyTtyConnector c = new PtyTtyConnector(p);
        assertDoesNotThrow(() -> c.resize(new Dimension(100, 50)));
        c.close();
        p.waitFor();
    }

    @Test
    void testGetName() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(
                System.getProperty("os.name", "").toLowerCase().contains("win"));

        PtyProcess p = startCat();
        PtyTtyConnector c = new PtyTtyConnector(p);
        assertEquals("claude", c.getName());
        c.close();
        p.waitFor();
    }

    /**
     * Verifies that the line listener receives lines with ANSI escape codes stripped.
     *
     * <p>Starts an {@code echo} process that outputs a line wrapped in ANSI colour
     * codes and asserts that the listener sees only the plain-text content.
     */
    @Test
    void testLineListenerReceivesStrippedLines() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(
                System.getProperty("os.name", "").toLowerCase().contains("win"));

        // echo -e interprets \e as ESC; wraps text in red colour codes
        PtyProcess p = new PtyProcessBuilder(new String[]{
                "/bin/bash", "-c", "printf '\\e[31mAllow? (y/n)\\e[0m\\n'"})
                .setEnvironment(Map.of("TERM", "xterm"))
                .setDirectory(System.getProperty("java.io.tmpdir"))
                .setConsole(false)
                .setRedirectErrorStream(true)
                .start();

        List<String> received = new ArrayList<>();
        PtyTtyConnector c = new PtyTtyConnector(p);
        c.setLineListener(received::add);

        // Drain all output by reading until EOF
        char[] buf = new char[256];
        try {
            while (c.isConnected() || c.ready()) {
                int n = c.read(buf, 0, buf.length);
                if (n < 0) break;
            }
        } catch (java.io.IOException ignored) {
            // EOF from PTY is expected
        }
        p.waitFor();

        assertTrue(received.stream().anyMatch(line -> line.equals("Allow? (y/n)")),
                "Expected stripped line 'Allow? (y/n)' in: " + received);
    }

    /**
     * Verifies that cursor-forward sequences ESC[NC are expanded to spaces.
     *
     * <p>Claude's TUI renders menu text using ESC[1C instead of literal space
     * characters. Without expansion, "Do you want to proceed?" would arrive as
     * "Doyouwanttoproceed?".
     */
    @Test
    void testCursorForwardExpandedToSpaces() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(
                System.getProperty("os.name", "").toLowerCase().contains("win"));

        // Simulate: "Do\e[1Cyou\e[1Cwant\e[1Cto\e[1Cproceed?" → "Do you want to proceed?"
        // ESC[1C = move cursor right 1 (used by Claude TUI instead of a literal space)
        PtyProcess p = new PtyProcessBuilder(new String[]{
                "/bin/bash", "-c",
                "printf 'Do\\e[1Cyou\\e[1Cwant\\e[1Cto\\e[1Cproceed?\\n'"})
                .setEnvironment(Map.of("TERM", "xterm"))
                .setDirectory(System.getProperty("java.io.tmpdir"))
                .setConsole(false)
                .setRedirectErrorStream(true)
                .start();

        List<String> received = new ArrayList<>();
        PtyTtyConnector c = new PtyTtyConnector(p);
        c.setLineListener(received::add);

        char[] buf = new char[256];
        try {
            while (c.isConnected() || c.ready()) {
                int n = c.read(buf, 0, buf.length);
                if (n < 0) break;
            }
        } catch (java.io.IOException ignored) {}
        p.waitFor();

        assertTrue(received.stream().anyMatch(line -> line.equals("Do you want to proceed?")),
                "Expected spaces from ESC[1C expansion, got: " + received);
    }

    /**
     * Verifies that ESC[NC with N > 1 expands to N spaces.
     */
    @Test
    void testCursorForwardMultipleColumns() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(
                System.getProperty("os.name", "").toLowerCase().contains("win"));

        // ESC[3C = move cursor right 3 → 3 spaces
        PtyProcess p = new PtyProcessBuilder(new String[]{
                "/bin/bash", "-c",
                "printf 'A\\e[3CB\\n'"})
                .setEnvironment(Map.of("TERM", "xterm"))
                .setDirectory(System.getProperty("java.io.tmpdir"))
                .setConsole(false)
                .setRedirectErrorStream(true)
                .start();

        List<String> received = new ArrayList<>();
        PtyTtyConnector c = new PtyTtyConnector(p);
        c.setLineListener(received::add);

        char[] buf = new char[256];
        try {
            while (c.isConnected() || c.ready()) {
                int n = c.read(buf, 0, buf.length);
                if (n < 0) break;
            }
        } catch (java.io.IOException ignored) {}
        p.waitFor();

        assertTrue(received.stream().anyMatch(line -> line.equals("A   B")),
                "Expected ESC[3C → 3 spaces, got: " + received);
    }

    /** Verifies that setting listener to null disables interception without errors. */
    @Test
    void testLineListenerCanBeCleared() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(
                System.getProperty("os.name", "").toLowerCase().contains("win"));

        PtyProcess p = startCat();
        PtyTtyConnector c = new PtyTtyConnector(p);
        c.setLineListener(line -> { throw new AssertionError("Should not be called"); });
        c.setLineListener(null);
        // No listener — just verify no exception on close
        c.close();
        p.waitFor();
    }
}
