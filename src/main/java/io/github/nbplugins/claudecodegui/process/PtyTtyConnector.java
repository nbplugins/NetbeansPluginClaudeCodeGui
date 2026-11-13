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

    /** Time source for the submission timestamps passed to the input listener. */
    private final java.util.function.LongSupplier clock;

    private String tag = "";

    /** Accumulates raw chars between newlines for line-based detection. */
    private final StringBuilder lineBuffer = new StringBuilder();

    /** Invoked with each stripped line; {@code null} means no listener. */
    private volatile Consumer<String> lineListener;

    /** Invoked with every byte block written to the PTY and the time the write
     *  started; {@code null} means no listener. */
    private volatile java.util.function.BiConsumer<byte[], Long> inputListener;

    /**
     * Lock held while bytes are forwarded to the child and the input listener
     * is notified; {@code null} means no serialization. The controller passes
     * the lock it also holds while it writes the GUI prompt's Enter, so
     * terminal input cannot reach the child on one side of that Enter while
     * being recorded on the other.
     */
    private volatile Object inputLock;

    /**
     * Sets the lock that serializes forwarding with the plugin's own writes.
     *
     * @param lock the lock, or {@code null} for none
     */
    public void setInputLock(Object lock) {
        this.inputLock = lock;
    }

    /**
     * Creates a connector for the given PTY process.
     *
     * @param process a running PTY process
     */
    public PtyTtyConnector(PtyProcess process) {
        this(process, System::currentTimeMillis);
    }

    /** Package-private for tests: injects the clock used for terminal submissions. */
    PtyTtyConnector(PtyProcess process, java.util.function.LongSupplier clock) {
        this.process = process;
        this.clock = clock;
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
     * Sets a listener that receives every block of bytes written to the PTY —
     * both plugin-sent input and text typed in the terminal — together with the
     * time the write started.
     *
     * <p>The timestamp is sampled before the bytes are forwarded so that a
     * child which appends the submitted prompt to its session file right after
     * reading them cannot produce a record later than the reported time.
     *
     * @param listener the listener, or {@code null} to clear
     */
    public void setInputListener(java.util.function.BiConsumer<byte[], Long> listener) {
        this.inputListener = listener;
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
        // Terminal input and the plugin's own writes (in particular the GUI
        // prompt's Enter) must reach the child in the same order in which they
        // are recorded: forwarding bytes before the GUI Enter while notifying
        // the listener only after the plugin reset its recorder would leave
        // those bytes behind as the draft of the next line. The controller
        // takes the same lock for the GUI Enter.
        Object lock = inputLock;
        if (lock == null) {
            forward(bytes);
            return;
        }
        synchronized (lock) {
            forward(bytes);
        }
    }

    private void forward(byte[] bytes) throws IOException {
        // Sampled before the bytes are forwarded: the child can append the
        // submitted prompt to its session file as soon as it reads them, so a
        // timestamp taken after the write/listener could be later than the
        // record the controller uses as evidence.
        long sentAt = clock.getAsLong();
        process.getOutputStream().write(bytes);
        process.getOutputStream().flush();
        java.util.function.BiConsumer<byte[], Long> l = inputListener;
        if (l != null) l.accept(bytes, sentAt);
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
