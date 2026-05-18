package io.github.nbplugins.claudecodegui.process;

import com.jediterm.terminal.TtyConnector;
import com.pty4j.PtyProcess;
import com.pty4j.WinSize;
import io.github.nbplugins.claudecodegui.settings.ClaudeCodePreferences;
import java.awt.Dimension;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Bridges a {@link PtyProcess} (pty4j) to JediTerm's {@link TtyConnector}.
 *
 * <p>Optionally fires a line listener for each complete line of output (after
 * stripping ANSI escape sequences). Use {@link #setLineListener(Consumer)} to
 * hook into the stream for prompt detection.
 */
public final class PtyTtyConnector implements TtyConnector {

    private static final Logger LOG = Logger.getLogger(PtyTtyConnector.class.getName());

    /** Matches ANSI/VT100 escape sequences (CSI, OSC, and single-char escapes). */
    private static final Pattern ANSI_PATTERN =
            Pattern.compile("\u001B(?:\\[[0-?]*[ -/]*[@-~]|[^\\[])");

    /** Matches cursor-forward sequences ESC[NC — Claude TUI uses these instead of literal spaces. */
    private static final Pattern CURSOR_FORWARD =
            Pattern.compile("\u001B\\[(\\d*)C");

    private final PtyProcess process;
    private final InputStreamReader reader;

    private String tag = "";

    /** Accumulates raw chars between newlines for line-based detection. */
    private final StringBuilder lineBuffer = new StringBuilder();

    /** Invoked with each stripped line; {@code null} means no listener. */
    private volatile Consumer<String> lineListener;

    /**
     * Creates a connector for the given PTY process.
     *
     * @param process a running PTY process
     */
    public PtyTtyConnector(PtyProcess process) {
        this.process = process;
        this.reader = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8);
    }

    /**
     * Sets a listener that receives each complete output line with ANSI codes stripped.
     *
     * <p>The listener is called from JediTerm's reader thread; implementations must
     * be thread-safe and should dispatch any UI work to the Event Dispatch Thread.
     *
     * @param listener the listener, or {@code null} to remove
     */
    public void setLineListener(Consumer<String> listener) {
        this.lineListener = listener;
    }

    /**
     * Sets a session tag used as a log prefix for debugging.
     *
     * @param tag the tag string, or {@code null} to clear
     */
    public void setSessionTag(String tag) {
        this.tag = tag == null ? "" : tag;
    }

    @Override
    public int read(char[] buf, int offset, int length) throws IOException {
        int n = reader.read(buf, offset, length);
        if (n > 0 && lineListener != null) {
            for (int i = offset; i < offset + n; i++) {
                char c = buf[i];
                if (c == '\n' || c == '\r') {
                    if (lineBuffer.length() > 0) {
                        String raw = lineBuffer.toString();
                        // Expand ESC[NC (cursor forward) to N spaces before stripping ANSI
                        String expanded = CURSOR_FORWARD.matcher(raw).replaceAll(m -> {
                            String cols = m.group(1);
                            int count = cols.isEmpty() ? 1 : Integer.parseInt(cols);
                            return " ".repeat(count);
                        });
                        String stripped = ANSI_PATTERN.matcher(expanded).replaceAll("").trim();
                        lineBuffer.setLength(0);
                        if (!stripped.isEmpty()) {
                            LOG.fine(tag + "[PTY raw] " + raw.replace("\u001B", "<ESC>"));
                            LOG.fine(tag + "[PTY stripped] " + stripped);
                            Consumer<String> l = lineListener;
                            if (l != null) l.accept(stripped);
                        }
                    }
                } else {
                    lineBuffer.append(c);
                }
            }
        }
        return n;
    }

    @Override
    public void write(byte[] bytes) throws IOException {
        process.getOutputStream().write(bytes);
        process.getOutputStream().flush();
    }

    @Override
    public void write(String string) throws IOException {
        write(string.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public boolean isConnected() {
        return process.isAlive();
    }

    @Override
    public boolean ready() throws IOException {
        return reader.ready();
    }

    @Override
    public void close() {
        process.destroy();
    }

    @Override
    public void resize(Dimension termWinSize) {
        LOG.fine(tag + "[PTY resize] cols=" + termWinSize.width + " rows=" + termWinSize.height);
        if (process.isAlive()) {
            process.setWinSize(
                    new WinSize(termWinSize.width, termWinSize.height));
        }
    }

    @Override
    public String getName() {
        return "claude";
    }

    @Override
    public int waitFor() throws InterruptedException {
        return process.waitFor();
    }
}
