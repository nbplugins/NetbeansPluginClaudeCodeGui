package io.github.nbclaudecodegui.controller;

import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jediterm.terminal.ui.JediTermWidget;
import com.pty4j.PtyProcess;
import io.github.nbclaudecodegui.model.ChoiceMenuModel;
import io.github.nbclaudecodegui.model.ClaudeSessionModel;
import io.github.nbclaudecodegui.model.EditMode;
import io.github.nbclaudecodegui.model.SavedSession;
import io.github.nbclaudecodegui.model.SessionLifecycle;
import io.github.nbclaudecodegui.model.SessionMode;
import io.github.nbclaudecodegui.process.ClaudeProcess;
import io.github.nbclaudecodegui.process.ClaudeSessionStore;
import io.github.nbclaudecodegui.process.ModelMenuParser;
import io.github.nbclaudecodegui.process.PtyTtyConnector;
import io.github.nbclaudecodegui.process.ScreenContentDetector;
import io.github.nbclaudecodegui.settings.ClaudeCodePreferences;
import io.github.nbclaudecodegui.settings.ClaudeProfile;
import io.github.nbclaudecodegui.settings.ClaudeProfileStore;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.SwingUtilities;

/**
 * Business logic and process coordination for a Claude Code session.
 *
 * <p>This class owns the PTY process lifecycle, the terminal connector, the
 * screen-polling timers, and all logic that reads from or writes to the PTY.
 * It does not reference any Swing layout components (JPanel, JLabel, etc.);
 * all state changes are communicated back to the View via
 * {@link ClaudeSessionModel} and its listener interface.
 *
 * <p><b>Dependencies:</b>
 * <ul>
 *   <li>{@link ClaudeSessionModel} — receives state updates; fires listener
 *       notifications on the EDT.</li>
 *   <li>{@code screenLines} supplier — provided by the View (from the
 *       {@link JediTermWidget} text buffer); keeps the controller free of
 *       direct widget references after {@link #startProcess} returns.</li>
 * </ul>
 */
public class ClaudeSessionController {

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private static final Logger LOG = Logger.getLogger(ClaudeSessionController.class.getName());

    /** ESC byte sent to the PTY (dismiss autocomplete, close menus). */
    private static final byte[] PTY_ESC = {0x1b};

    /** DEL character (0x7F) — acts as BackSpace in xterm-256color terminals. */
    private static final byte[] PTY_BACKSPACE = {0x7f};

    private static final int MAX_MODEL_DISCOVERY_ATTEMPTS = 1;

    private final ClaudeSessionModel model;

    /**
     * Supplier of current terminal screen lines. Provided by the View so that
     * the controller can inspect the rendered terminal content without holding a
     * reference to {@link JediTermWidget} after start-up.
     */
    private final Supplier<List<String>> screenLines;

    private final ScreenContentDetector screenContentDetector = new ScreenContentDetector();

    private ClaudeProcess claudeProcess;
    private PtyTtyConnector connector;
    private java.io.File workingDir;
    private volatile JediTermWidget terminalWidget;

    /** Polls screen state every 500 ms; owned here to allow proper teardown. */
    private javax.swing.Timer statusTimer;

    /**
     * Fires {@link #flushPendingPrompt()} 400 ms after PTY output goes silent.
     * One-shot (non-repeating): fires once per {@link javax.swing.Timer#restart()}
     * so that a subsequent full-screen redraw (ESC[2J) cannot accidentally dismiss
     * a menu that was already shown.
     */
    private final javax.swing.Timer promptFlushTimer =
            new javax.swing.Timer(400, e -> flushPendingPrompt());
    { promptFlushTimer.setRepeats(false); }

    /** Timestamp (ms) when the PTY process was launched; used for hang detection. */
    private volatile long processStartedAt = 0;

    /** {@code true} once the first byte/line from the PTY has been received. */
    private volatile boolean firstOutputReceived = false;

    /**
     * Called when a hang is detected: receives (command, errorMessage).
     * Invoked on the EDT after the process is stopped.
     */
    private java.util.function.BiConsumer<String, String> hangCallback;

    /** {@code true} while a shift-tab thread is actively switching the CC edit mode. */
    private volatile boolean modeSwitchInProgress = false;

    /**
     * Set to {@code true} when model discovery starts to prevent re-entry;
     * reset to {@code false} if the result is empty (to allow a retry).
     */
    private boolean modelComboPopulated = false;

    /** {@code true} while the model-discovery or model-switch background thread is running. */
    private volatile boolean modelDiscoveryInProgress = false;

    /** Number of model-discovery attempts made since the last session start. */
    private int modelDiscoveryAttempts = 0;

    /** Custom model IDs from the active profile; appended to the model combo after standard models. */
    private List<String> customModelIds = List.of();

    /** Number of standard (CC-discovered) models; used to distinguish custom-model indices in {@link #switchModel}. */
    private int standardModelCount = 0;

    /**
     * Rolling buffer of the last {@link #PTY_LINE_BUFFER_SIZE} stripped PTY lines.
     * Used as a fallback for prompt detection when the JediTerm screen buffer is
     * still empty (e.g. widget not yet laid out, terminal size 0×0 at startup).
     */
    private final java.util.Deque<String> recentPtyLines = new java.util.ArrayDeque<>();
    private static final int PTY_LINE_BUFFER_SIZE = 40;

    /** Package-private for tests: sets hang-detection state as if the process just started. */
    void simulateProcessStart(long startedAt, boolean outputReceived) {
        processStartedAt = startedAt;
        firstOutputReceived = outputReceived;
    }

    /** Package-private for tests: injects a stripped PTY line into the rolling buffer. */
    void simulatePtyLine(String line) {
        synchronized (recentPtyLines) {
            recentPtyLines.addLast(line);
            if (recentPtyLines.size() > PTY_LINE_BUFFER_SIZE) {
                recentPtyLines.removeFirst();
            }
        }
    }

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * Creates a controller bound to the given model and screen-lines supplier.
     *
     * @param model       the session model to update; must not be {@code null}
     * @param screenLines supplier that returns the current terminal screen lines;
     *                    called from background threads and from the EDT
     */
    public ClaudeSessionController(ClaudeSessionModel model,
                                   Supplier<List<String>> screenLines) {
        this.model = model;
        this.screenLines = screenLines;
    }

    // -------------------------------------------------------------------------
    // Process lifecycle
    // -------------------------------------------------------------------------

    /**
     * Starts the Claude PTY process for the given directory and wires it to the
     * supplied terminal widget.
     *
     * <p>Precondition: must be called on the EDT (the widget was created on the EDT).
     *
     * <p>Post-condition: the PTY is running; the status-poll timer is started;
     * the model lifecycle is set to {@link SessionLifecycle#STARTING}.
     *
     * @param dir         working directory to pass to {@code claude}
     * @param profileName name of the profile to use, or {@code null}/{@code ""}
     *                    for the Default profile
     * @param widget      the terminal widget that will render the Claude TUI
     * @throws IOException if the PTY process cannot be started
     */
    public void startProcess(java.io.File dir, String profileName, String extraCliArgs, JediTermWidget widget)
            throws IOException {
        startProcess(dir, profileName, extraCliArgs, SessionMode.NEW, null, widget);
    }

    /**
     * Starts the Claude PTY process with session mode support.
     *
     * @param dir             working directory
     * @param profileName     profile name, or {@code null}/{@code ""} for Default
     * @param extraCliArgs    extra CLI arguments (may be blank)
     * @param mode            session start mode
     * @param resumeSessionId session ID to resume (only used for RESUME_SPECIFIC)
     * @param widget          terminal widget
     * @throws IOException if the PTY process cannot be started
     */
    public void startProcess(java.io.File dir, String profileName, String extraCliArgs,
                             SessionMode mode, String resumeSessionId,
                             JediTermWidget widget) throws IOException {
        String tag = "[" + dir.getName() + "] ";
        workingDir = dir;
        claudeProcess = new ClaudeProcess();
        ClaudeProfile profile = ClaudeProfileStore.findByName(profileName);
        customModelIds = profile != null ? new ArrayList<>(profile.getCustomModels()) : List.of();
        PtyProcess process = claudeProcess.start(dir.getAbsolutePath(), profile,
                extraCliArgs != null ? extraCliArgs : "",
                mode != null ? mode : SessionMode.NEW,
                resumeSessionId);

        processStartedAt = System.currentTimeMillis();
        firstOutputReceived = false;

        connector = new PtyTtyConnector(process);
        connector.setSessionTag(tag);
        screenContentDetector.setSessionTag(tag);

        connector.setLineListener(line -> {
            firstOutputReceived = true;
            LOG.fine(tag + "[PTY line] " + line);
            synchronized (recentPtyLines) {
                recentPtyLines.addLast(line);
                if (recentPtyLines.size() > PTY_LINE_BUFFER_SIZE) {
                    recentPtyLines.removeFirst();
                }
            }
            // While a choice menu is active, suppress timer-based re-detection
            if (model.getActiveChoiceMenu() != null) {
                return;
            }
            SwingUtilities.invokeLater(() -> {
                promptFlushTimer.restart();
                LOG.fine(tag + "[timer] restart, running=" + promptFlushTimer.isRunning());
            });
        });

        this.terminalWidget = widget;
        widget.setTtyConnector(connector);
        widget.start();

        // Ensure the initial edit mode is registered so the hook can read it
        EditMode initialMode = model.getEditMode() != null ? model.getEditMode() : EditMode.DEFAULT;
        model.setEditMode(initialMode);

        modelDiscoveryAttempts = 0;
        modelComboPopulated = false;
        model.clearChoiceMenu();
        model.setLifecycle(SessionLifecycle.STARTING);
        model.setWorkingDirectory(dir);
        model.loadPersistedHistory();

        // Waiter thread: notifies when the process exits
        Thread waiter = new Thread(() -> {
            try {
                process.waitFor();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }, "claude-waiter");
        waiter.setDaemon(true);
        waiter.start();

        startStatusPollTimer();
    }

    /**
     * Stops the PTY process and all background timers.
     *
     * <p>Updates the model: sets lifecycle to STARTING, clears the model list,
     * clears the choice menu, and removes the edit-mode registry entry.
     *
     * <p>Does <em>not</em> close the terminal widget — the View handles that.
     */
    /** Returns the last command attempted to start the process. */
    public String getLastAttemptedCommand() { return claudeProcess.getLastCommand(); }

    public void stopProcess() {
        if (statusTimer != null) {
            statusTimer.stop();
            statusTimer = null;
        }
        promptFlushTimer.stop();
        if (connector != null) {
            connector.setLineListener(null);
        }
        if (claudeProcess != null) {
            claudeProcess.stop();
        }
        connector = null;
        claudeProcess = null;
        terminalWidget = null;
        modelComboPopulated = false;
        modelDiscoveryInProgress = false;
        modelDiscoveryAttempts = 0;
        customModelIds = List.of();
        standardModelCount = 0;
        synchronized (recentPtyLines) { recentPtyLines.clear(); }
        processStartedAt = 0;
        firstOutputReceived = false;

        model.setModelList(List.of(), -1);
        model.clearChoiceMenu();
        model.clearEditModeRegistry();
        model.setLifecycle(SessionLifecycle.STARTING);
    }

    /**
     * Stops the PTY process and, if {@code sessionName} is non-blank, writes a
     * {@code custom-title} entry to the most recent session JSONL file.
     *
     * @param sessionName name to persist, or {@code null}/blank to skip renaming
     */
    public void stopAndRename(String sessionName) {
        java.io.File workingDir = model.getWorkingDirectory();
        stopProcess();
        if (sessionName != null && !sessionName.isBlank() && workingDir != null) {
            try {
                SavedSession recent = ClaudeSessionStore.findMostRecent(
                        workingDir.toPath(), null);
                if (recent != null) {
                    ClaudeSessionStore.renameSession(
                            workingDir.toPath(), null, recent.sessionId(), sessionName);
                    LOG.fine("Renamed session " + recent.sessionId() + " to '" + sessionName + "'");
                }
            } catch (Exception e) {
                LOG.warning("Could not rename session: " + e.getMessage());
            }
        }
    }

    // -------------------------------------------------------------------------
    // PTY writes
    // -------------------------------------------------------------------------

    /**
     * Sends the user's prompt text to the PTY and transitions to WORKING state.
     *
     * <p>The text and the {@code \r} Enter key are sent in a background thread
     * with a 200 ms pause between them to avoid multiline-input timing issues.
     *
     * <p>Also records the prompt in the model's history.
     *
     * @param text the prompt text to send; must not be empty
     */
    public void sendPrompt(String text) {
        if (connector == null) return;
        model.setLifecycle(SessionLifecycle.WORKING);
        if (!text.isBlank()) {
            model.addPromptToHistory(text);
        }

        Thread t = new Thread(() -> {
            try {
                connector.write(text);
                Thread.sleep(200);
                connector.write("\r");
            } catch (IOException ex) {
                LOG.warning("sendPrompt write failed: " + ex.getMessage());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }, "pty-send-prompt");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Sends ESC (0x1B) to the PTY and transitions to READY state.
     * No-op if no PTY connector is active.
     */
    public void cancelPrompt() {
        if (connector == null) return;
        try {
            sendCancelToPty(new byte[]{0x1b});
        } catch (IOException ex) {
            LOG.warning("cancelPrompt failed: " + ex.getMessage());
        }
    }

    /**
     * Transitions the model to {@link SessionLifecycle#READY}.
     * Called by the Stop hook when Claude finishes its turn.
     */
    public void onClaudeIdle() {
        model.setLifecycle(SessionLifecycle.READY);
    }

    /**
     * Triggers an immediate screen scan to detect any pending choice-menu prompt.
     * Schedules {@link #flushPendingPrompt()} on the EDT.
     *
     * <p>Called by the PermissionRequest hook before a PTY permission dialog appears.
     */
    public void triggerPromptScan() {
        SwingUtilities.invokeLater(this::flushPendingPrompt);
    }

    /**
     * Writes the user's answer to the PTY.
     *
     * <p>Handles five cases:
     * <ul>
     *   <li>{@code null} — sends ESC (0x1B) and transitions to READY (Cancel path).</li>
     *   <li>{@code "MULTI:n,m,..."} — toggles checkboxes to reach desired state, then submits with →.</li>
     *   <li>{@code "MULTI_TYPE:checks:N:text"} — toggles checkboxes, navigates to type-input option N, types text, then submits with →.</li>
     *   <li>{@code "TYPE:digit:text"} — activates the type-input option then sends text + \r.</li>
     *   <li>single digit {@code "0"–"9"} — sends the digit without \r (menu selection).</li>
     *   <li>everything else — sends the text followed by \r.</li>
     * </ul>
     *
     * @param answer the answer string from the choice-menu callback
     */
    public void writePtyAnswer(String answer) {
        try {
            if (answer == null) {
                LOG.fine("[PTY write] \\x1b (Cancel/ESC)");
                sendCancelToPty(new byte[]{0x1b});
            } else if (answer.startsWith("MULTI:")) {
                Set<String> wanted = new HashSet<>();
                for (String d : answer.substring(6).split(",")) wanted.add(d.strip());
                // Re-read current screen to get actual checkbox states before sending toggles.
                // Sending a digit toggles the checkbox, so we must only send digits for
                // options whose current state differs from the desired state.
                List<String> lines = screenLines.get();
                Optional<ChoiceMenuModel> currentMenu = screenContentDetector.detectChoiceMenu(lines);
                List<ChoiceMenuModel.Option> currentOptions = currentMenu
                        .map(ChoiceMenuModel::options)
                        .orElseGet(() -> {
                            ChoiceMenuModel m = model.getActiveChoiceMenu();
                            return m != null ? m.options() : List.of();
                        });
                List<String> toggles = computeCheckboxToggles(wanted, currentOptions);
                LOG.fine("[PTY write] MULTI wanted=" + wanted + " toggles=" + toggles);
                Thread t = new Thread(() -> {
                    try {
                        for (String digit : toggles) {
                            connector.write(digit.strip());
                            Thread.sleep(100);
                        }
                        // Right arrow to submit the selection
                        connector.write(new byte[]{0x1b, '[', 'C'});
                    } catch (IOException ex) {
                        LOG.warning("writePtyAnswer MULTI write failed: " + ex.getMessage());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }, "pty-multiselect");
                t.setDaemon(true);
                t.start();
                model.clearChoiceMenu();
            } else if (answer.startsWith("MULTI_TYPE:")) {
                // Format: MULTI_TYPE:checks:typeN:text
                // e.g. MULTI_TYPE:1,3:5:my text  or  MULTI_TYPE::5:my text
                int sep1 = answer.indexOf(':', 11);
                int sep2 = answer.indexOf(':', sep1 + 1);
                String checksStr = answer.substring(11, sep1);
                int typeN = Integer.parseInt(answer.substring(sep1 + 1, sep2));
                String typeText = answer.substring(sep2 + 1);
                Set<String> wanted = checksStr.isBlank() ? Set.of()
                        : new HashSet<>(List.of(checksStr.split(",")));
                LOG.fine("[PTY write] MULTI_TYPE wanted=" + wanted + " typeN=" + typeN + " text=" + typeText);
                Thread t = new Thread(() -> {
                    try {
                        // 1. Toggle non-type checkboxes
                        List<String> lines = screenLines.get();
                        Optional<ChoiceMenuModel> currentMenu = screenContentDetector.detectChoiceMenu(lines);
                        List<ChoiceMenuModel.Option> currentOptions = currentMenu
                                .map(ChoiceMenuModel::options)
                                .orElseGet(() -> {
                                    ChoiceMenuModel m = model.getActiveChoiceMenu();
                                    return m != null ? m.options() : List.of();
                                });
                        for (String digit : computeCheckboxToggles(wanted, currentOptions)) {
                            connector.write(digit.strip());
                            Thread.sleep(100);
                        }
                        // 2. Re-read screen to find current ❯ position
                        Thread.sleep(200);
                        int currentN = findCurrentOptionNum(screenLines.get());
                        // 3. Navigate to type-input option
                        int delta = typeN - currentN;
                        byte[] arrow = delta < 0
                                ? new byte[]{0x1b, '[', 'A'}
                                : new byte[]{0x1b, '[', 'B'};
                        for (int i = 0; i < Math.abs(delta); i++) {
                            connector.write(arrow);
                            Thread.sleep(80);
                        }
                        // 4. Type text
                        Thread.sleep(100);
                        connector.write(typeText);
                        // 5. Move cursor off type-input field (up arrow)
                        Thread.sleep(100);
                        connector.write(new byte[]{0x1b, '[', 'A'});
                        // 6. Submit with right arrow
                        Thread.sleep(100);
                        connector.write(new byte[]{0x1b, '[', 'C'});
                    } catch (IOException ex) {
                        LOG.warning("writePtyAnswer MULTI_TYPE write failed: " + ex.getMessage());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }, "pty-multitype");
                t.setDaemon(true);
                t.start();
                model.clearChoiceMenu();
            } else if (answer.startsWith("TYPE:")) {
                int sep = answer.indexOf(':', 5);
                String digit = answer.substring(5, sep);
                String text = answer.substring(sep + 1);
                LOG.fine("[PTY write] type-input digit=" + digit + " text=" + text);
                Thread t = new Thread(() -> {
                    try {
                        connector.write(digit);
                        Thread.sleep(200);
                        connector.write(text);
                        Thread.sleep(200);
                        connector.write("\r");
                    } catch (IOException ex) {
                        LOG.warning("writePtyAnswer TYPE write failed: " + ex.getMessage());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }, "pty-typeinput");
                t.setDaemon(true);
                t.start();
                model.clearChoiceMenu();
            } else if (answer.startsWith("ARROW:")) {
                int targetIdx = Integer.parseInt(answer.substring(6));
                ChoiceMenuModel current = model.getActiveChoiceMenu();
                int currentIdx = current != null ? current.defaultOptionIndex() : 0;
                int delta = targetIdx - currentIdx;
                LOG.fine("[PTY write] ARROW targetIdx=" + targetIdx + " currentIdx=" + currentIdx + " delta=" + delta);
                model.clearChoiceMenu();
                if (delta == 0) {
                    connector.write("\r");
                } else {
                    byte[] arrow = delta < 0 ? new byte[]{0x1b, '[', 'A'} : new byte[]{0x1b, '[', 'B'};
                    Thread t = new Thread(() -> {
                        try {
                            for (int idx = 0; idx < Math.abs(delta); idx++) {
                                connector.write(arrow);
                                Thread.sleep(80);
                            }
                            connector.write("\r");
                        } catch (IOException ex) {
                            LOG.warning("writePtyAnswer ARROW write failed: " + ex.getMessage());
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }, "pty-arrow-select");
                    t.setDaemon(true);
                    t.start();
                }
            } else {
                boolean isMenuDigit = answer.matches("[0-9]");
                String toWrite = isMenuDigit ? answer : answer + "\r";
                LOG.fine("[PTY write] " + toWrite.replace("\r", "\\r").replace("\n", "\\n"));
                connector.write(toWrite);
                model.clearChoiceMenu();
            }
        } catch (IOException ex) {
            LOG.warning("writePtyAnswer failed: " + ex.getMessage());
        }
    }

    /**
     * Computes which checkbox option responses must be toggled in the PTY to reach
     * {@code desired} from the current states in {@code currentOptions}.
     *
     * <p>Sending a digit to a PTY checkbox menu <em>toggles</em> that item rather
     * than setting it, so we only send digits for options whose current state differs
     * from what the user wants.
     *
     * @param desired        set of option {@code response} values the user wants checked
     * @param currentOptions options with their current {@code checked} state read from screen
     * @return sorted list of option responses to send as toggle keystrokes
     */
    /**
     * Scans screen lines for the currently focused option (❯ marker) and returns its 1-based number.
     * Returns 1 if the marker is not found.
     */
    private int findCurrentOptionNum(List<String> lines) {
        Pattern p = Pattern.compile("❯\\s*(\\d+)\\.");
        for (String line : lines) {
            Matcher m = p.matcher(line);
            if (m.find()) return Integer.parseInt(m.group(1));
        }
        return 1;
    }

    static List<String> computeCheckboxToggles(Set<String> desired, List<ChoiceMenuModel.Option> currentOptions) {
        List<String> toggles = new ArrayList<>();
        for (ChoiceMenuModel.Option opt : currentOptions) {
            if (!opt.hasCheckbox()) continue;
            if (opt.checked() != desired.contains(opt.response())) {
                toggles.add(opt.response());
            }
        }
        toggles.sort(Comparator.comparingInt(s -> Integer.parseInt(s.strip())));
        return toggles;
    }

    // -------------------------------------------------------------------------
    // Edit mode
    // -------------------------------------------------------------------------

    /**
     * Sends a single Shift+Tab (ESC[Z) to the PTY and updates the model's edit mode
     * from the screen 300 ms later.
     *
     * <p>Called when Shift+Tab is pressed anywhere in {@link io.github.nbclaudecodegui.ui.ClaudePromptPanel}.
     * No-op if no connector is active.
     */
    public void sendShiftTab() {
        if (connector == null) return;
        modeSwitchInProgress = true;
        Thread t = new Thread(() -> {
            try {
                connector.write(new byte[]{0x1b, '[', 'Z'});
                Thread.sleep(300);
                screenContentDetector.detectEditMode(screenLines.get())
                        .ifPresent(model::setEditMode);
            } catch (IOException | InterruptedException ex) {
                LOG.warning("sendShiftTab failed: " + ex.getMessage());
            } finally {
                modeSwitchInProgress = false;
            }
        }, "shift-tab");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Called when the user selects a different edit mode from the combo box.
     *
     * <p>If the selected mode differs from the current model state, updates the
     * model and triggers a Shift+Tab sequence to switch Claude's built-in mode.
     *
     * @param newMode the newly selected mode
     */
    public void onEditModeComboChanged(EditMode newMode) {
        if (newMode == null) return;
        if (newMode == model.getEditMode()) return;
        model.setEditMode(newMode);
        sendShiftTabsUntilMode(newMode);
    }

    /**
     * Sends Shift+Tab (ESC[Z) presses until the terminal screen shows
     * {@code targetMode}, or until 5 attempts are exhausted.
     * 5 attempts cover the full 4-mode cycle plus one extra in case of lag.
     *
     * <p>Runs on a daemon thread; sets lifecycle to WORKING at start and
     * READY on completion.
     *
     * @param targetMode the edit mode to reach
     */
    private void sendShiftTabsUntilMode(EditMode targetMode) {
        if (connector == null) return;
        model.setLifecycle(SessionLifecycle.WORKING);
        modeSwitchInProgress = true;
        Thread t = new Thread(() -> {
            try {
                LOG.info("sendShiftTabsUntilMode: target=" + targetMode
                        + " currentModel=" + model.getEditMode());
                for (int attempt = 0; attempt < 5; attempt++) {
                    connector.write(new byte[]{0x1b, '[', 'Z'});
                    Thread.sleep(200);
                    Optional<EditMode> detected =
                            screenContentDetector.detectEditMode(screenLines.get());
                    LOG.fine("sendShiftTabsUntilMode: attempt=" + attempt
                            + " detected=" + detected.map(EditMode::key).orElse("(empty)"));
                    if (detected.isPresent() && detected.get() == targetMode) {
                        LOG.fine("sendShiftTabsUntilMode: reached target on attempt=" + attempt);
                        return;
                    }
                    // DEFAULT mode has no idle-screen marker — empty detection means we are in default
                    if (targetMode == EditMode.DEFAULT && detected.isEmpty()) {
                        LOG.fine("sendShiftTabsUntilMode: no mode marker → treating as default on attempt=" + attempt);
                        return;
                    }
                }
                LOG.warning("sendShiftTabsUntilMode: did not reach " + targetMode
                        + " after 5 attempts");
            } catch (IOException | InterruptedException ex) {
                LOG.warning("sendShiftTabsUntilMode failed: " + ex.getMessage());
            } finally {
                modeSwitchInProgress = false;
                onClaudeIdle();
            }
        }, "shift-tab-edit-mode");
        t.setDaemon(true);
        t.start();
    }

    // -------------------------------------------------------------------------
    // Model switching
    // -------------------------------------------------------------------------

    /**
     * Sends the {@code /model} command to the PTY and selects the model at
     * {@code index} (1-based digit key, no Enter required).
     *
     * <p>Precondition: lifecycle must be {@link SessionLifecycle#READY}.
     *
     * @param index zero-based index into the available-models list
     */
    public void switchModel(int index) {
        if (connector == null) return;
        if (model.getLifecycle() != SessionLifecycle.READY) return;

        modelDiscoveryInProgress = true;
        if (index >= standardModelCount && !customModelIds.isEmpty()) {
            // Custom model: switch via /model <id>\r
            String customId = customModelIds.get(index - standardModelCount);
            Thread t = new Thread(() -> {
                try {
                    connector.write("/model " + customId + "\r");
                    Thread.sleep(500);
                } catch (IOException | InterruptedException ex) {
                    LOG.warning("model switch failed: " + ex.getMessage());
                } finally {
                    try { Thread.sleep(600); } catch (InterruptedException ignored) {}
                    SwingUtilities.invokeLater(() -> modelDiscoveryInProgress = false);
                }
            }, "claude-model-switch");
            t.setDaemon(true);
            t.start();
        } else {
            // Standard model: open /model menu and send 1-based digit
            Thread t = new Thread(() -> {
                try {
                    openModelMenu();
                    connector.write(String.valueOf(index + 1));
                    Thread.sleep(500);
                } catch (IOException | InterruptedException ex) {
                    LOG.warning("model switch failed: " + ex.getMessage());
                } finally {
                    try { Thread.sleep(600); } catch (InterruptedException ignored) {}
                    SwingUtilities.invokeLater(() -> modelDiscoveryInProgress = false);
                }
            }, "claude-model-switch");
            t.setDaemon(true);
            t.start();
        }
    }

    // -------------------------------------------------------------------------
    // Model discovery
    // -------------------------------------------------------------------------

    /**
     * Opens the {@code /model} selection menu, parses the screen, and populates
     * the model list in {@link ClaudeSessionModel}.
     *
     * <p>Runs on a daemon thread. Re-entrant protection via
     * {@link #modelComboPopulated} / {@link #modelDiscoveryInProgress}.
     * Retries up to {@value #MAX_MODEL_DISCOVERY_ATTEMPTS} times if the screen
     * returns no models.
     */
    void discoverModels() {
        if (connector == null || modelComboPopulated || modelDiscoveryInProgress) return;
        if (modelDiscoveryAttempts >= MAX_MODEL_DISCOVERY_ATTEMPTS) return;
        modelComboPopulated = true;
        modelDiscoveryInProgress = true;
        modelDiscoveryAttempts++;
        model.setLifecycle(SessionLifecycle.WORKING);

        Thread t = new Thread(() -> {
            try {
                openModelMenu();

                // Poll the screen until the model selection menu appears.
                // openModelMenu() types /model + Enter, but Claude CLI needs
                // time to render the numbered menu.  Poll up to ~5 s.
                List<String> lines = null;
                Optional<ChoiceMenuModel> menuOpt = Optional.empty();
                for (int poll = 0; poll < 16; poll++) {
                    Thread.sleep(300);
                    lines = screenLines.get();
                    if (LOG.isLoggable(java.util.logging.Level.FINE)) {
                        StringBuilder sb = new StringBuilder("[discoverModels] poll ")
                                .append(poll + 1).append(" screen (")
                                .append(lines.size()).append(" lines):");
                        for (int li = 0; li < lines.size(); li++) {
                            String l = lines.get(li);
                            if (!l.isBlank()) sb.append("\n  ").append(li).append(": ").append(l);
                        }
                        LOG.fine(sb.toString());
                    }
                    menuOpt = screenContentDetector.detectChoiceMenu(lines);
                    if (menuOpt.isPresent() && !menuOpt.get().options().isEmpty()) {
                        LOG.fine("[discoverModels] detectChoiceMenu found menu on poll " + (poll + 1));
                        break;
                    }
                    ModelMenuParser.ModelDiscovery probe = new ModelMenuParser().parse(lines);
                    if (!probe.models().isEmpty()) {
                        LOG.fine("[discoverModels] ModelMenuParser found models on poll " + (poll + 1));
                        break;
                    }
                    // If ❯ /model is still in the prompt with a blank line below it, CR was
                    // absorbed as a newline — retry: BackSpace to remove the blank line, then CR.
                    if (poll < 12 && isModelCommandPendingWithBlankLine(lines)) {
                        LOG.fine("[discoverModels] /model pending with blank line on poll " + (poll + 1) + ", retrying CR");
                        connector.write(PTY_BACKSPACE);
                        Thread.sleep(200);
                        connector.write("\r");
                    }
                }

                // Scan the rolling PTY line buffer for the /model hint line, which is
                // shorter and never truncated (e.g. "/model  … (currently anthropic/claude-sonnet-4.6)").
                String hintCurrentModel = null;
                Pattern hintPat = Pattern.compile("\\(currently\\s+([^)]+?)\\s*\\)");
                synchronized (recentPtyLines) {
                    for (String ptyLine : recentPtyLines) {
                        Matcher hm = hintPat.matcher(ptyLine);
                        if (hm.find()) { hintCurrentModel = hm.group(1); break; }
                    }
                }

                List<String> models;
                int selIdx;
                if (menuOpt.isPresent() && !menuOpt.get().options().isEmpty()) {
                    List<ChoiceMenuModel.Option> opts = menuOpt.get().options();
                    Pattern descPat = Pattern.compile("(?:\u2714\\s+|\\s{2,})(.+?)(?:\\s*[·\u00b7].*)?$");
                    Pattern currentlyPat = Pattern.compile("\\(currently\\s+([^)]+?)\\s*\\)");
                    models = new ArrayList<>();
                    selIdx = -1;
                    for (int i = 0; i < opts.size(); i++) {
                        String display = opts.get(i).display();
                        Matcher dm = descPat.matcher(display);
                        String modelId;
                        if (dm.find()) {
                            String desc = dm.group(1).trim();
                            Matcher cm = currentlyPat.matcher(desc);
                            modelId = cm.find() ? cm.group(1) : desc;
                        } else {
                            modelId = display.strip();
                        }
                        if (modelId.isEmpty()) modelId = display.strip();
                        models.add(modelId);
                        if (display.contains("\u2714")) selIdx = i;
                    }
                } else {
                    ModelMenuParser.ModelDiscovery discovery = new ModelMenuParser().parse(lines);
                    models = discovery.models();
                    selIdx = discovery.currentIndex();
                }

                connector.write(new byte[]{0x1b});  // Esc — dismiss model menu

                // If a menu entry contains "(currently" but is incomplete (truncated at terminal
                // width), replace it with the model name extracted from the hint line above.
                if (hintCurrentModel != null) {
                    for (int i = 0; i < models.size(); i++) {
                        if (models.get(i).contains("(currently")) {
                            models.set(i, hintCurrentModel);
                        }
                    }
                }

                standardModelCount = models.size();
                if (!customModelIds.isEmpty()) {
                    models = new ArrayList<>(models);
                    models.addAll(customModelIds);
                }

                final List<String> finalModels = models;
                final int finalSelIdx = selIdx;
                if (!finalModels.isEmpty()) {
                    model.setModelList(finalModels, finalSelIdx);
                } else {
                    SwingUtilities.invokeLater(() -> modelComboPopulated = false);
                }
            } catch (IOException | InterruptedException ex) {
                LOG.warning("discoverModels failed: " + ex.getMessage());
                SwingUtilities.invokeLater(() -> modelComboPopulated = false);
            } finally {
                // Wait for Claude CLI to finish its normal redraw after Esc,
                // then send a single SIGWINCH to force a complete TUI redraw
                // (the Esc-dismiss redraw often leaves stale /model fragments).
                // Finally wait for the resize-triggered redraw to complete
                // before transitioning to READY — setting READY too early
                // causes pollScreenState to see mid-redraw garbage → WORKING,
                // and the session gets stuck.
                try { Thread.sleep(600); } catch (InterruptedException ignored) {}
                forceTerminalRedraw();
                try { Thread.sleep(400); } catch (InterruptedException ignored) {}
                SwingUtilities.invokeLater(() -> {
                    modelDiscoveryInProgress = false;
                    onClaudeIdle();
                });
            }
        }, "claude-model-discovery");
        t.setDaemon(true);
        t.start();
    }

    // -------------------------------------------------------------------------
    // Screen polling
    // -------------------------------------------------------------------------

    /**
     * Called every 500 ms by {@link #statusTimer}. Reads the current terminal
     * screen and updates the model accordingly.
     *
     * <p>Responsibilities:
     * <ul>
     *   <li>Detect plan name and update model.</li>
     *   <li>Drive STARTING → READY transition on first prompt.</li>
     *   <li>Trigger model discovery and initial edit-mode detection on first READY.</li>
     *   <li>Continuously sync CC's actual edit mode to the model.</li>
     * </ul>
     */
    void pollScreenState() {
        List<String> lines = screenLines.get();

        // Plan name
        Optional<String> plan = screenContentDetector.detectPlanName(lines);
        model.setPlanName(plan.orElse(""));

        // STARTING → READY: one-shot transition
        if (model.getLifecycle() == SessionLifecycle.STARTING
                && screenContentDetector.detectInputPromptReady(lines)) {
            model.setLifecycle(SessionLifecycle.READY);
        }

        // On first READY: discover models and detect initial edit mode.
        // Defer discovery while any blocking dialog is visible — e.g. the
        // "Resuming session" prompt shown on --continue/--resume startup.
        // Sending /model while Claude is blocked by such a dialog causes the
        // model menu to appear only after the dialog is dismissed, by which
        // time discovery has already timed out and given up.
        if (model.getLifecycle() == SessionLifecycle.READY
                && !modelComboPopulated && !modelDiscoveryInProgress
                && model.getActiveChoiceMenu() == null
                && screenContentDetector.detectChoiceMenu(lines).isEmpty()) {
            discoverModels();
            detectAndApplyInitialEditMode(lines);
        }

        // Detect choice menu on every poll as backup for when promptFlushTimer misses it
        // (e.g. thinking spinner keeps PTY active and prevents the 400 ms silence).
        // Also clear stale menu when it disappears from screen — flushPendingPrompt
        // may never fire if the spinner continuously restarts promptFlushTimer.
        if (!modelDiscoveryInProgress) {
            Optional<ChoiceMenuModel> menuOpt = screenContentDetector.detectChoiceMenu(lines);
            if (menuOpt.isPresent()) {
                ChoiceMenuModel newMenu = menuOpt.get();
                ChoiceMenuModel current = model.getActiveChoiceMenu();
                if (current == null || !newMenu.text().equals(current.text()) || !optionsEqual(newMenu.options(), current.options())) {
                    LOG.fine("[pollScreenState] setting choice menu: \"" + newMenu.text() + "\"");
                    model.setActiveChoiceMenu(newMenu);
                }
            } else if (model.getActiveChoiceMenu() != null && !screenContentDetector.detectYesNoPrompt(lines)) {
                // For unnumbered menus (ARROW: responses), don't dismiss from the screen-poll timer.
                // The /resume picker redraws itself during PTY resize (when the choice panel
                // appears and shrinks the terminal), creating a transient state with no ❯ cursor
                // on screen. flushPendingPrompt waits for 400 ms of PTY silence, at which point
                // the screen is stable — let it handle unnumbered menu dismissal exclusively.
                ChoiceMenuModel active = model.getActiveChoiceMenu();
                boolean isUnnumbered = !active.options().isEmpty()
                        && active.options().get(0).response().startsWith("ARROW:");
                if (!isUnnumbered) {
                    LOG.fine("[pollScreenState] menu gone from screen, dismissing");
                    model.clearChoiceMenu();
                }
            }
        }

        // Screen-based state sync (bidirectional READY ↔ WORKING)
        if (model.getLifecycle() != SessionLifecycle.STARTING) {
            ScreenContentDetector.DetectedSessionState detectedScreenState =
                    screenContentDetector.detectSessionState(lines);
            if (detectedScreenState == ScreenContentDetector.DetectedSessionState.READY
                    && model.getLifecycle() == SessionLifecycle.WORKING) {
                LOG.fine("[pollScreenState] screen-detected READY, transitioning WORKING→READY");
                model.setLifecycle(SessionLifecycle.READY);
            } else if (detectedScreenState == ScreenContentDetector.DetectedSessionState.WORKING
                    && model.getLifecycle() == SessionLifecycle.READY) {
                LOG.fine("[pollScreenState] screen-detected WORKING, transitioning READY→WORKING");
                model.setLifecycle(SessionLifecycle.WORKING);
            }
            // UNKNOWN → no transition
        }

        // Hang detection: if no PTY output received within the configured timeout, kill the process
        if (!firstOutputReceived && processStartedAt > 0) {
            int timeoutSec = ClaudeCodePreferences.getHangTimeoutSeconds();
            if (timeoutSec > 0) {
                long elapsed = System.currentTimeMillis() - processStartedAt;
                if (elapsed > timeoutSec * 1000L) {
                    handleHang(timeoutSec);
                    return;
                }
            }
        }

        // Continuously sync CC screen mode → model (skip during switches and discovery)
        if (modelComboPopulated && !modeSwitchInProgress && !modelDiscoveryInProgress) {
            Optional<EditMode> detected = screenContentDetector.detectEditMode(lines);
            LOG.fine("[pollScreenState] editMode sync: detected=" + detected.orElse(null) + " current=" + model.getEditMode());
            if (detected.isPresent()) {
                EditMode mode = detected.get();
                if (mode != model.getEditMode()) {
                    model.setEditMode(mode);
                }
            } else if (model.getLifecycle() != SessionLifecycle.WORKING) {
                // Unknown mode outside WORKING (screen transitioning or idle with no indicator)
                // → treat as Ask/default. During WORKING: preserve current registry value.
                if (model.getEditMode() != EditMode.DEFAULT) {
                    model.setEditMode(EditMode.DEFAULT);
                }
            }
        }
    }

    /**
     * Registers a callback invoked on the EDT when a hang is detected and the
     * process has been stopped.
     *
     * @param callback receives (command, errorMessage); may be {@code null} to clear
     */
    public void setHangCallback(java.util.function.BiConsumer<String, String> callback) {
        this.hangCallback = callback;
    }

    /**
     * Kills the PTY process and resets timers/state, but does NOT call
     * {@link ClaudeProcess#stop()} — so settings.local.json and the temp MCP
     * config file are preserved until the error panel is dismissed and
     * {@link #stopProcess()} is called.
     *
     * <p>{@code claudeProcess} is intentionally kept non-null so that the
     * subsequent {@code stopProcess()} on panel dismiss can still call
     * {@code claudeProcess.stop()} and clean up files.
     */
    private void killProcessForErrorPanel() {
        if (statusTimer != null) {
            statusTimer.stop();
            statusTimer = null;
        }
        promptFlushTimer.stop();
        if (connector != null) {
            connector.setLineListener(null);
        }
        if (claudeProcess != null) {
            claudeProcess.killOnly();
        }
        connector = null;
        // claudeProcess kept — file cleanup deferred to stopProcess() on panel dismiss
        modelComboPopulated = false;
        modelDiscoveryInProgress = false;
        modelDiscoveryAttempts = 0;
        customModelIds = List.of();
        standardModelCount = 0;
        synchronized (recentPtyLines) { recentPtyLines.clear(); }
        processStartedAt = 0;
        firstOutputReceived = false;

        model.setModelList(List.of(), -1);
        model.clearChoiceMenu();
        model.clearEditModeRegistry();
        model.setLifecycle(SessionLifecycle.STARTING);
    }

    /**
     * Called from {@link #pollScreenState()} when the hang watchdog fires.
     * Stops the process and notifies the registered callback on the EDT.
     *
     * @param timeoutSec the configured timeout that elapsed (for the error message)
     */
    private void handleHang(int timeoutSec) {
        LOG.severe("Hang detected in " + workingDir + ": no PTY output received within " + timeoutSec + " s — killing process");
        String command = claudeProcess != null ? claudeProcess.getLastCommand() : "";
        killProcessForErrorPanel();  // kill PTY; file cleanup deferred to panel dismiss
        java.util.function.BiConsumer<String, String> cb = hangCallback;
        if (cb != null) {
            String msg = "No output received within " + timeoutSec
                    + " seconds. Process was killed.";
            cb.accept(command, msg);
        }
    }

    private void detectAndApplyInitialEditMode(List<String> lines) {
        Optional<EditMode> detected = screenContentDetector.detectEditMode(lines);
        List<String> bottom = new java.util.ArrayList<>();
        for (int i = lines.size() - 1; i >= 0 && bottom.size() < 3; i--) {
            if (!lines.get(i).isBlank()) bottom.add(lines.get(i));
        }
        LOG.fine("[detectAndApplyInitialEditMode] detected=" + detected.orElse(null) + " bottom3=" + bottom);
        detected.ifPresent(m -> model.setEditMode(m));
    }

    /**
     * Checks the terminal screen for a pending choice-menu prompt and updates the model.
     *
     * <p>If a menu is currently active in the model and the screen no longer shows one,
     * the model choice menu is cleared (dismiss path).
     * If no menu is active and the screen shows one, the model is updated (show path).
     */
    void flushPendingPrompt() {
        LOG.fine("[flushPendingPrompt] enter, modelDiscoveryInProgress=" + modelDiscoveryInProgress);
        if (modelDiscoveryInProgress) return;

        Optional<ChoiceMenuModel> req = Optional.empty();
        List<String> lines = screenLines.get();
        boolean screenBlank = lines.isEmpty()
                || lines.stream().allMatch(s -> s.trim().isEmpty());
        long nonBlank = lines.stream().filter(s -> !s.trim().isEmpty()).count();
        LOG.fine("[screen prompt flush] screenLines.size()=" + lines.size()
                + " nonBlank=" + nonBlank + " screenBlank=" + screenBlank
                + (lines.size() > 0 && nonBlank == 0 && !lines.isEmpty()
                   ? " firstLineLen=" + lines.get(0).length()
                     + " firstLineChars=" + (lines.get(0).length() > 0
                         ? String.format("0x%04x", (int) lines.get(0).charAt(0)) : "N/A")
                   : ""));
        if (screenBlank) {
            // JediTerm screen buffer is empty or cleared (ESC[2J).
            // Fall back to the rolling PTY line buffer for prompt detection.
            synchronized (recentPtyLines) {
                lines = new java.util.ArrayList<>(recentPtyLines);
            }
            if (!lines.isEmpty()) {
                LOG.fine("[screen prompt flush] screen blank, using PTY buffer (" + lines.size() + " lines)");
            }
        }
        if (!lines.isEmpty()) {
            req = screenContentDetector.detectChoiceMenu(lines);
        }

        if (model.getActiveChoiceMenu() != null) {
            // Menu shown — dismiss if Claude cleared it from screen
            if (req.isEmpty()) {
                // Don't dismiss if a Y/n trust prompt is still on screen
                if (!lines.isEmpty() && screenContentDetector.detectYesNoPrompt(lines)) {
                    return;
                }
                LOG.fine("[screen prompt] menu gone from screen, dismissing");
                model.clearChoiceMenu();
            } else {
                ChoiceMenuModel newMenu = req.get();
                ChoiceMenuModel current = model.getActiveChoiceMenu();
                if (!newMenu.text().equals(current.text()) || !optionsEqual(newMenu.options(), current.options())) {
                    LOG.fine("[screen prompt] menu changed, updating (req=" + newMenu.text() + ")");
                    model.setActiveChoiceMenu(newMenu);
                } else {
                    LOG.fine("[screen prompt] menu still on screen, keeping (req=" + newMenu.text() + ")");
                }
            }
            return;
        }

        // Y/n trust prompt detection (Bug 3: first-run directory-trust dialog)
        if (req.isEmpty() && !lines.isEmpty()
                && screenContentDetector.detectYesNoPrompt(lines)) {
            // Build question from lines above the [Y/n] pattern
            String question = buildYnQuestion(lines);
            ChoiceMenuModel synthetic = new ChoiceMenuModel(
                    question,
                    List.of(
                            new ChoiceMenuModel.Option("Yes", "y"),
                            new ChoiceMenuModel.Option("No", "n")),
                    0);
            LOG.fine("[screen prompt flush] Y/n prompt detected: \"" + question + "\"");
            model.setActiveChoiceMenu(synthetic);
            return;
        }

        req.ifPresent(r -> {
            LOG.fine("[screen prompt flush] setting choice menu: \"" + r.text() + "\" | options=" + r.options());
            model.setActiveChoiceMenu(r);
        });
    }

    /**
     * Extracts the question text from lines above the Y/n prompt line.
     * Returns the last non-blank, non-Y/n line before the prompt.
     */
    /**
     * Compares two option lists for equality.
     *
     * <p>For unnumbered menus (responses starting with {@code "ARROW:"}), only
     * {@code display} and {@code response} are compared — description is ignored.
     * This suppresses re-renders caused by transient description changes during
     * mid-render footer redraws (the picker redraws the footer using cursor-movement
     * sequences, and a screen poll during that window may see the footer at a
     * non-bottom position, causing the last option's description to flicker).
     *
     * <p>For numbered menus, full {@link java.util.Objects#equals} semantics apply.
     */
    private static boolean optionsEqual(List<ChoiceMenuModel.Option> a, List<ChoiceMenuModel.Option> b) {
        if (a.size() != b.size()) return false;
        if (!a.isEmpty() && a.get(0).response().startsWith("ARROW:")) {
            // Unnumbered menu: compare by display+response only
            for (int i = 0; i < a.size(); i++) {
                if (!a.get(i).display().equals(b.get(i).display())) return false;
                if (!a.get(i).response().equals(b.get(i).response())) return false;
            }
            return true;
        }
        return a.equals(b);
    }

    private static final java.util.regex.Pattern YN_LINE_PATTERN =
            java.util.regex.Pattern.compile(
                    "\\[Y/n\\]|\\[y/N\\]|\\[yes/no\\]|\\(y/n\\)",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    private static String buildYnQuestion(List<String> lines) {
        // Find the Y/n line from the bottom
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (YN_LINE_PATTERN.matcher(lines.get(i)).find()) {
                // The question is the Y/n line itself (it contains the prompt text)
                String ynLine = lines.get(i).trim();
                // Also include any preceding non-blank context line
                for (int j = i - 1; j >= 0; j--) {
                    String prev = lines.get(j).trim();
                    if (!prev.isBlank()) {
                        return prev + " " + ynLine;
                    }
                }
                return ynLine;
            }
        }
        return "";
    }

    // -------------------------------------------------------------------------
    // Queries
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if a PTY process is currently running.
     *
     * @return {@code true} if the process is alive
     */
    public boolean hasLiveProcess() {
        return claudeProcess != null && claudeProcess.isRunning();
    }

    /** Returns the timestamp (ms) when the current PTY process was launched, or 0 if none. */
    public long getProcessStartedAt() { return processStartedAt; }

    /**
     * Reads the Claude CLI version string from the running process.
     *
     * <p>Blocking call; suitable for a background thread.
     *
     * @return version string, or empty string if not available
     */
    public String readVersion() {
        ClaudeProcess cp = claudeProcess;
        return cp != null ? cp.readVersion() : "";
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private void startStatusPollTimer() {
        statusTimer = new javax.swing.Timer(500, e -> pollScreenState());
        statusTimer.start();
    }

    /**
     * Returns {@code true} when the screen shows {@code ❯ /model} followed by a blank line
     * and then a separator — meaning CR was absorbed as a newline instead of submitting the
     * command. Only the active input area (cursor line) is checked to avoid false positives
     * when "/model" appears in Claude's output history.
     */
    boolean isModelCommandPendingWithBlankLine(List<String> lines) {
        for (int i = 0; i < lines.size() - 2; i++) {
            String trimmed = lines.get(i).trim();
            if ((trimmed.startsWith("❯") || trimmed.startsWith(">")) && trimmed.contains("/model")) {
                if (lines.get(i + 1).isBlank() && isSeparatorLine(lines.get(i + 2))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isSeparatorLine(String line) {
        String t = line.trim();
        return t.length() >= 4 && t.chars().allMatch(c ->
                c == '─' || c == '━' || c == '—' || c == '-' || c == '=');
    }

    /**
     * Opens the {@code /model} selection menu via a safe three-step sequence:
     * type {@code /model}, wait, ESC to dismiss autocomplete (keeping the
     * command in the input), wait, then \r to execute.
     */
    private void openModelMenu() throws IOException, InterruptedException {
        connector.write("/model");
        Thread.sleep(200);
        connector.write(PTY_ESC);
        Thread.sleep(200);
        connector.write("\r");
        Thread.sleep(200);
    }

    /**
     * Sends {@code bytes} to the PTY and transitions the model to
     * {@link SessionLifecycle#READY}.
     */
    private void sendCancelToPty(byte[] bytes) throws IOException {
        connector.write(bytes);
        onClaudeIdle();
    }

    /**
     * Sends a single SIGWINCH pair (shrink by one row, then restore) to force
     * Claude CLI to fully redraw the TUI.  Used after model discovery Esc
     * dismiss, which often leaves stale content on screen.
     */
    private void forceTerminalRedraw() {
        JediTermWidget w = terminalWidget;
        if (connector == null || w == null) return;
        com.jediterm.core.util.TermSize ts = w.getTerminalPanel()
                .getTerminalSizeFromComponent();
        if (ts == null || ts.getColumns() <= 0 || ts.getRows() <= 1) return;
        connector.resize(new java.awt.Dimension(ts.getColumns(), ts.getRows() - 1));
        connector.resize(new java.awt.Dimension(ts.getColumns(), ts.getRows()));
        LOG.fine("[forceTerminalRedraw] cols=" + ts.getColumns() + " rows=" + ts.getRows());
    }

}
