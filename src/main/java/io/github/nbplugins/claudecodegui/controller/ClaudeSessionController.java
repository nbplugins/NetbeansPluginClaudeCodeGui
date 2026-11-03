package io.github.nbplugins.claudecodegui.controller;

import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jediterm.terminal.ui.JediTermWidget;
import com.pty4j.PtyProcess;
import io.github.nbplugins.claudecodegui.model.ChoiceMenuModel;
import io.github.nbplugins.claudecodegui.model.ClaudeSessionModel;
import io.github.nbplugins.claudecodegui.model.EditMode;
import io.github.nbplugins.claudecodegui.model.SavedSession;
import io.github.nbplugins.claudecodegui.model.SessionLifecycle;
import io.github.nbplugins.claudecodegui.model.SessionMode;
import io.github.nbplugins.claudecodegui.process.ClaudeProcess;
import io.github.nbplugins.claudecodegui.process.ClaudeSessionStore;
import io.github.nbplugins.claudecodegui.process.MenuEnumerator;
import io.github.nbplugins.claudecodegui.process.ModelMenuParser;
import io.github.nbplugins.claudecodegui.process.PtyTtyConnector;
import io.github.nbplugins.claudecodegui.process.ScreenContentDetector;
import io.github.nbplugins.claudecodegui.settings.ClaudeCodePreferences;
import io.github.nbplugins.claudecodegui.settings.ClaudeProfile;
import io.github.nbplugins.claudecodegui.settings.ClaudeProfileStore;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
    private static final byte[] PTY_ARROW_UP = {0x1b, '[', 'A'};
    private static final byte[] PTY_ARROW_DOWN = {0x1b, '[', 'B'};
    /** Pause after an arrow key before the screen is read while enumerating a scrolling menu. */
    private static final long MENU_ENUMERATION_SETTLE_MS = 120;

    private static final int MAX_MODEL_DISCOVERY_ATTEMPTS = 1;

    /**
     * Slack for the filesystem's modification-time granularity when matching a
     * session file against a launch/prompt time. FAT and some network
     * filesystems round mtimes down to 1–2 s, so a file created just after a
     * prompt — including a saved conversation resumed in-process — can be
     * stamped before it. Applied as a second identity-scan pass (see
     * {@link #refreshSessionIdentity()}).
     */
    private static final long SESSION_MTIME_GRANULARITY_MS = 2_000L;

    private final ClaudeSessionModel model;

    /**
     * Supplier of current terminal screen lines. Provided by the View so that
     * the controller can inspect the rendered terminal content without holding a
     * reference to {@link JediTermWidget} after start-up.
     */
    private final Supplier<List<String>> screenLines;

    private final ScreenContentDetector screenContentDetector = new ScreenContentDetector();

    private volatile ClaudeProcess claudeProcess;
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
     * Controllers whose PTY process is currently running. Tabs that share a
     * project / config store use this set to publish (and claim) their own
     * session before another tab decides which file to rename.
     */
    private static final Set<ClaudeSessionController> LIVE_SESSIONS =
            ConcurrentHashMap.newKeySet();

    /**
     * A session file, identified by its store: the same session ID can exist in
     * several config dirs / projects (e.g. a JSONL copied between stores), and
     * a claim must only hide the file it actually belongs to.
     */
    private record SessionKey(java.nio.file.Path configDir,
                              java.nio.file.Path projectDir,
                              String sessionId) { }

    /** Session file → live controllers running exactly that file. */
    private static final Map<SessionKey, Set<ClaudeSessionController>> SESSION_CLAIMS =
            new ConcurrentHashMap<>();

    /**
     * Worker for the periodic session-store scans, shared by all controllers so
     * that the Swing EDT never enumerates the session history.
     */
    private static final java.util.concurrent.ExecutorService IDENTITY_SCANNER =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "session-id-scan");
                t.setDaemon(true);
                return t;
            });

    /**
     * Guards {@link #claudeProcess} replacement against a late exit callback:
     * every started process gets a new {@link #processGeneration}, and the
     * callback only releases ownership when its generation is still current.
     */
    private final Object processOwnershipLock = new Object();
    private long processGeneration;

    /**
     * Monotonic scan counter. Scans of the same process run can overlap (the
     * dialog resolves synchronously while the worker scans); a result is only
     * published if no newer scan published before it.
     */
    private long scanSequence;
    private long lastPublishedScanId;

    /** True while a background identity scan of this controller is queued. */
    private final java.util.concurrent.atomic.AtomicBoolean identityScanPending =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Effective Claude config dir of the running session, captured at launch.
     * Used so that renaming targets the same store the process writes to.
     */
    private volatile java.nio.file.Path activeConfigDir;

    /**
     * Session ID known at launch: the resume ID for {@link SessionMode#RESUME_SPECIFIC}
     * and the newest existing session for {@link SessionMode#CONTINUE_LAST}.  For
     * {@link SessionMode#NEW} this is {@code null} until the session file appears.
     */
    private volatile String activeSessionId;

    /**
     * Session IDs that already existed when the current session was launched.
     * They are only ineligible for an unresolved {@code NEW} session and only
     * before the plugin has submitted a prompt (see
     * {@link #refreshSessionIdentity()}).
     */
    private volatile Set<String> preStartSessionIds = Set.of();

    /**
     * Time (epoch millis) a prompt was last submitted from the plugin. Used to
     * attribute the session file created by a new process to this tab promptly,
     * before any sibling tab could guess and claim it.
     */
    private volatile long promptSubmittedAt = 0;

    /** Screen-poll ticks since the last background session-identity scan. */
    private int identityPollTicks = 0;

    /**
     * Called when a hang is detected: receives (command, errorMessage).
     * Invoked on the EDT after the process is stopped.
     */
    private java.util.function.BiConsumer<String, String> hangCallback;

    /** {@code true} while a shift-tab thread is actively switching the CC edit mode. */
    private volatile boolean modeSwitchInProgress = false;

    /** Guards {@link #editModeSwitchTarget}. */
    private final Object editModeSwitchLock = new Object();

    /**
     * Mode the running {@link #sendShiftTabsUntilMode} worker is steering toward;
     * {@code null} when no worker runs. Guarded by {@link #editModeSwitchLock}.
     */
    private EditMode editModeSwitchTarget;

    /** Incremented on every switch request; tells a newer request from an equal one. Guarded by the lock. */
    private long editModeSwitchGeneration;

    /** Poll interval (ms) per read attempt inside {@link #trySendShiftTabsUntilMode}; 1 in tests. */
    int modeSwitchPollMs = 100;
    /**
     * Max polls waiting for a concrete mode change after each Shift+Tab.
     * 15 × 100 ms = 1 500 ms — enough for CC to process a buffered keystroke during WORKING.
     */
    int modeSwitchPollCount = 15;

    /**
     * Set to {@code true} when model discovery starts to prevent re-entry;
     * reset to {@code false} if the result is empty (to allow a retry).
     */
    private boolean modelComboPopulated = false;

    /** {@code true} while the model-discovery or model-switch background thread is running. */
    private volatile boolean modelDiscoveryInProgress = false;
    /** True while {@link MenuEnumerator} walks the cursor through a scrolling menu. */
    private volatile boolean menuEnumerationInProgress = false;

    /** Number of model-discovery attempts made since the last session start. */
    private int modelDiscoveryAttempts = 0;

    /** Custom model IDs from the active profile; appended to the model combo after standard models. */
    private List<String> customModelIds = List.of();

    /** Number of standard (CC-discovered) models; used to distinguish custom-model indices in {@link #switchModel}. */
    private int standardModelCount = 0;

    /**
     * A newly detected choice menu that differs from the currently active one, held
     * pending a second consecutive confirming detection before it replaces the active
     * menu. See {@link #applyDetectedChoiceMenu}.
     */
    private ChoiceMenuModel pendingChoiceMenuCandidate;

    /**
     * A newly detected edit mode that differs from the model's current mode, held
     * pending a second consecutive confirming detection before it replaces the
     * current mode. See {@link #applyDetectedEditMode}.
     */
    private Optional<EditMode> pendingEditModeCandidate = Optional.empty();

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

    /** Package-private for tests: sets the session identity captured at launch. */
    void simulateActiveSession(java.nio.file.Path configDir, String sessionId,
                               Set<String> preStartIds) {
        activeConfigDir = configDir;
        activeSessionId = sessionId;
        preStartSessionIds = preStartIds != null ? Set.copyOf(preStartIds) : Set.of();
    }

    /** Package-private for tests: one tick of the periodic identity refresh. */
    void simulateIdentityTick() {
        refreshSessionIdentityIfDue();
    }

    /** Package-private for tests: records a prompt submitted from the plugin. */
    void simulatePromptSubmittedAt(long millis) {
        promptSubmittedAt = millis;
    }

    /**
     * Package-private for tests: marks this controller as a live session, so
     * that other controllers treat {@code sessionId} as owned by another tab.
     */
    void simulateLiveSession(java.nio.file.Path configDir, String sessionId,
                             Set<String> preStartIds) {
        simulateActiveSession(configDir, sessionId, preStartIds);
        workingDir = model.getWorkingDirectory();
        LIVE_SESSIONS.add(this);
        claimSession(workingDir, configDir, sessionId);
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
        long generation;
        synchronized (processOwnershipLock) {
            claudeProcess = new ClaudeProcess();
            generation = ++processGeneration;
        }
        ClaudeProfile profile = ClaudeProfileStore.findByName(profileName);
        customModelIds = profile != null ? new ArrayList<>(profile.getComboModelIds()) : List.of();

        // Capture the session identity before launch so that stopping/renaming later
        // targets the session this process will actually use (see stopAndRename).
        SessionMode effectiveMode = mode != null ? mode : SessionMode.NEW;
        java.nio.file.Path projectedConfigDir =
                ClaudeProcess.resolveConfigDir(profile, ClaudeCodePreferences.getProfilesDir());
        preStartSessionIds = ClaudeSessionStore.listSessionIds(dir.toPath(), projectedConfigDir);
        if (effectiveMode == SessionMode.RESUME_SPECIFIC
                && resumeSessionId != null && !resumeSessionId.isBlank()) {
            activeSessionId = resumeSessionId;
        } else if (effectiveMode == SessionMode.CONTINUE_LAST) {
            activeSessionId = ClaudeSessionStore.findLatestSessionId(dir.toPath(), projectedConfigDir);
        } else {
            activeSessionId = null;
        }

        PtyProcess process = claudeProcess.start(dir.getAbsolutePath(), profile,
                extraCliArgs != null ? extraCliArgs : "",
                effectiveMode, resumeSessionId);

        activeConfigDir = claudeProcess.getConfigDir();
        if (activeConfigDir == null) {
            activeConfigDir = projectedConfigDir;
        }
        processStartedAt = System.currentTimeMillis();
        firstOutputReceived = false;

        LIVE_SESSIONS.add(this);
        claimSession(dir, activeConfigDir, activeSessionId);

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
        clearActiveChoiceMenu();
        model.setLifecycle(SessionLifecycle.STARTING);
        model.setWorkingDirectory(dir);
        model.loadPersistedHistory();

        // Waiter thread: releases the session claims as soon as the process exits,
        // even when it exits from inside the terminal without an explicit stop.
        long exitedGeneration = generation;
        Thread waiter = new Thread(() -> {
            try {
                process.waitFor();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                onProcessExited(exitedGeneration);
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

    /**
     * Returns the UUID of the active OpenAI-compatible proxy session, or
     * {@code null} if the current session isn't using the OpenAI-compatible or
     * ChatGPT Subscription connection type. See {@link io.github.nbplugins.claudecodegui.process.ClaudeProcess#getOpenAIProxyUuid()}.
     */
    public String getOpenAIProxyUuid() { return claudeProcess.getOpenAIProxyUuid(); }

    public void stopProcess() {
        if (statusTimer != null) {
            statusTimer.stop();
            statusTimer = null;
        }
        promptFlushTimer.stop();
        if (connector != null) {
            connector.setLineListener(null);
        }
        ClaudeProcess processToStop = claudeProcess;
        if (processToStop != null) {
            processToStop.stop();
        }
        connector = null;
        // The generation bump, the identity inputs and the ownership bookkeeping
        // are reset under one lock: a scan queued between the bump and the reset
        // would otherwise capture the new generation together with the old run's
        // inputs and could republish (and re-claim) the stopped identity.
        synchronized (processOwnershipLock) {
            claudeProcess = null;
            processGeneration++;
            processStartedAt = 0;
            firstOutputReceived = false;
            activeConfigDir = null;
            activeSessionId = null;
            preStartSessionIds = Set.of();
            promptSubmittedAt = 0;
            identityPollTicks = 0;
            LIVE_SESSIONS.remove(this);
            releaseAllClaims();
        }
        terminalWidget = null;
        modelComboPopulated = false;
        modelDiscoveryInProgress = false;
        modelDiscoveryAttempts = 0;
        customModelIds = List.of();
        standardModelCount = 0;
        synchronized (recentPtyLines) { recentPtyLines.clear(); }

        model.setModelList(List.of(), -1);
        clearActiveChoiceMenu();
        model.clearEditModeRegistry();
        model.setLifecycle(SessionLifecycle.STARTING);
    }

    /**
     * Stops the PTY process and, if {@code sessionName} is non-blank, appends a
     * {@code custom-title} entry to the JSONL file of the session that was running.
     *
     * <p>The target session and config dir are tracked from launch, so the rename
     * works for named profiles (isolated config dir), profiles that map to
     * {@code ~/.claude}, several sessions sharing the same project, and
     * conversations switched with {@code /resume} inside the running process.
     *
     * @param sessionName name to persist, or {@code null}/blank to skip renaming
     */
    public void stopAndRename(String sessionName) {
        java.io.File dir = model.getWorkingDirectory();
        java.nio.file.Path configDir = activeConfigDir;
        String sessionId = resolveActiveSessionId();
        stopProcess();

        if (sessionName == null || sessionName.isBlank()) return;
        if (dir == null || configDir == null || sessionId == null) {
            LOG.warning("Could not rename session: no active session file found in "
                    + ClaudeSessionStore.sessionDirForLog(
                            dir != null ? dir.toPath() : null, configDir)
                    + " (dir=" + dir + ", configDir=" + configDir + ")");
            return;
        }
        String name = sessionName.trim();
        try {
            SavedSession current = ClaudeSessionStore.findSession(dir.toPath(), configDir, sessionId);
            if (current != null && name.equals(current.customTitle())) {
                LOG.fine("Session " + sessionId + " already titled '" + name + "'; nothing to do");
                return;
            }
            ClaudeSessionStore.renameSession(dir.toPath(), configDir, sessionId, name);
            LOG.fine("Renamed session " + sessionId + " to '" + name + "' in " + configDir);
        } catch (Exception e) {
            LOG.warning("Could not rename session " + sessionId + " in " + configDir + ": " + e.getMessage());
        }
    }

    /**
     * Returns the ID of the session currently being displayed, resolving the
     * file created by this run for {@link SessionMode#NEW} sessions.
     *
     * <p>The identity is not fixed at launch: Claude can switch conversations
     * inside the running process (its own {@code /resume} picker), in which case
     * the file written most recently belongs to the new session. Sessions
     * claimed by other live tabs are never taken over (see
     * {@link #refreshSessionIdentity()}).
     *
     * @return session ID, or {@code null} if it cannot be determined
     */
    public String resolveActiveSessionId() {
        refreshSessionIdentity();
        return activeSessionId;
    }

    /**
     * Re-resolves the session the running process is writing and keeps the
     * cross-tab claim registry in sync: when Claude switches conversations
     * (in-process {@code /resume}), the claim on the previous session is
     * released so a sibling tab can take it, and the new one is claimed.
     *
     * <p>The identity is the newest session file written since the launch (or
     * since the last prompt submitted from the plugin) that is not owned by
     * another live tab. When no such file exists yet — e.g. a continue/resume
     * launch that has not written anything — the identity captured at launch is
     * kept.
     *
     * <p>Safe to call from any thread: the parameters are captured and the
     * result is published under {@link #processOwnershipLock}, and a scan that
     * started before a process restart is discarded instead of overwriting the
     * new run's identity. The filesystem lookup itself runs outside the lock.
     *
     * @return {@code true} if an identity is known
     */
    boolean refreshSessionIdentity() {
        long generation;
        long scanId;
        java.io.File dir;
        java.nio.file.Path configDir;
        long since;
        long promptAt;
        String activeId;
        Set<String> exclude = new HashSet<>();
        Set<String> claimed = new HashSet<>();
        synchronized (processOwnershipLock) {
            generation = processGeneration;
            scanId = ++scanSequence;
            dir = workingDir != null ? workingDir : model.getWorkingDirectory();
            configDir = activeConfigDir;
            since = Math.max(processStartedAt, promptSubmittedAt);
            promptAt = promptSubmittedAt;
            activeId = activeSessionId;
            if (dir != null && configDir != null && since > 0) {
                claimed.addAll(claimedByOthers(dir, configDir));
                exclude.addAll(claimed);
                if (activeSessionId == null && promptSubmittedAt <= 0) {
                    // Without a prompt of our own there is nothing that proves a
                    // write belongs to this tab, so a file that existed before
                    // the launch stays ineligible: it may have been touched by
                    // another Claude process. After a prompt, anything written
                    // since it is ours — including a saved conversation resumed
                    // from the TUI before this session created a file of its own.
                    exclude.addAll(preStartSessionIds);
                }
            }
        }
        String newest = null;
        if (dir != null && configDir != null && since > 0) {
            newest = ClaudeSessionStore.findLatestSessionIdWrittenSince(
                    dir.toPath(), configDir, since, exclude);
            if (newest == null && since > SESSION_MTIME_GRANULARITY_MS) {
                // Coarse filesystem timestamps can round a file written right
                // after the prompt below its cutoff. Retry with a slack so a
                // saved conversation resumed in-process (which existed before
                // the launch) is still recognised. Without a prompt of our own
                // the pre-launch snapshot stays excluded: the extra second must
                // not make a file touched by another process eligible.
                Set<String> relaxedExclude = exclude;
                if (promptAt <= 0) {
                    relaxedExclude = new HashSet<>(exclude);
                    relaxedExclude.addAll(preStartSessionIds);
                }
                newest = ClaudeSessionStore.findLatestSessionIdWrittenSince(
                        dir.toPath(), configDir,
                        since - SESSION_MTIME_GRANULARITY_MS, relaxedExclude);
            }
            if (newest == null && promptAt > 0) {
                // The process may have joined another live tab's conversation
                // from its own resume picker: its previous file (if any) stops
                // changing, so only a file owned by the other tab is written
                // since the prompt. Follow the switch and share the ownership,
                // instead of keeping the conversation the tab had left. A NEW
                // tab can do this before it has created a file of its own, so
                // an unknown identity must be allowed as well.
                String switched = ClaudeSessionStore.findLatestSessionIdWrittenSince(
                        dir.toPath(), configDir, since, Set.of());
                if (switched != null && !switched.equals(activeId)
                        && claimed.contains(switched)) {
                    newest = switched;
                }
            }
        }
        return publishIdentityScanResult(generation, scanId, dir, configDir, newest);
    }

    /**
     * Publishes a scan result, unless it was superseded: a scan started before a
     * process restart describes a store the current run does not use, and a scan
     * started before another one that already published must not undo the newer
     * result (the synchronous dialog path and the background worker can scan
     * concurrently within the same process run).
     */
    private boolean publishIdentityScanResult(long generation, long scanId,
                                              java.io.File dir,
                                              java.nio.file.Path configDir,
                                              String newest) {
        synchronized (processOwnershipLock) {
            if (generation != processGeneration) {
                return activeSessionId != null;
            }
            if (scanId < lastPublishedScanId) {
                return activeSessionId != null;
            }
            lastPublishedScanId = scanId;
            if (newest != null && !newest.equals(activeSessionId)) {
                String previous = activeSessionId;
                activeSessionId = newest;
                claimSession(dir, configDir, newest);
                releaseClaim(dir, configDir, previous);
                LOG.fine("Session of " + dir + " is now " + newest
                        + (previous != null ? " (was " + previous + ")" : ""));
            }
            claimSession(dir, configDir, activeSessionId);
            return activeSessionId != null;
        }
    }

    /** Package-private for tests: reserves a scan slot and returns its sequence. */
    long simulateBeginIdentityScan() {
        synchronized (processOwnershipLock) {
            return ++scanSequence;
        }
    }

    /** Package-private for tests: publishes a result scanned for an older process run. */
    boolean simulatePublishStaleScanResult(long generation, String newest) {
        return publishIdentityScanResult(generation, simulateBeginIdentityScan(),
                null, activeConfigDir, newest);
    }

    /** Package-private for tests: publishes a scan result with explicit ordering. */
    boolean simulatePublishScanResult(long generation, long scanId, String newest) {
        return publishIdentityScanResult(generation, scanId, null, activeConfigDir, newest);
    }

    /** Package-private for tests: the identity inputs {@link #refreshSessionIdentity()} captures. */
    record IdentityCapture(long generation, boolean hasConfigDir, long since) { }

    /** Package-private for tests: samples the identity inputs under the ownership lock. */
    IdentityCapture simulateCaptureIdentityInputs() {
        synchronized (processOwnershipLock) {
            return new IdentityCapture(processGeneration, activeConfigDir != null,
                    Math.max(processStartedAt, promptSubmittedAt));
        }
    }

    /**
     * Runs the periodic identity refresh when it is due: on every call while a
     * {@code NEW} session is still unknown after a prompt, otherwise every
     * tenth call (~5 s at the 500 ms poll cadence). Call from a worker thread —
     * it touches the session directory.
     */
    void refreshSessionIdentityIfDue() {
        boolean unresolvedAfterPrompt = activeSessionId == null && promptSubmittedAt > 0;
        if (unresolvedAfterPrompt || ++identityPollTicks % 10 == 0) {
            refreshSessionIdentity();
        }
    }

    /**
     * Queues one periodic identity refresh on the shared worker, so that the
     * screen poll never enumerates the session store on the EDT. At most one
     * scan per controller is in flight at a time.
     */
    private void scheduleIdentityRefresh() {
        if (!identityScanPending.compareAndSet(false, true)) return;
        try {
            IDENTITY_SCANNER.execute(() -> {
                try {
                    refreshSessionIdentityIfDue();
                } catch (RuntimeException e) {
                    LOG.fine("Session identity scan failed: " + e);
                } finally {
                    identityScanPending.set(false);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            identityScanPending.set(false);
        }
    }

    private static SessionKey keyFor(java.io.File dir, java.nio.file.Path configDir,
                                     String sessionId) {
        if (dir == null || configDir == null || sessionId == null) return null;
        return new SessionKey(configDir.toAbsolutePath().normalize(),
                dir.toPath().toAbsolutePath().normalize(), sessionId);
    }

    private void claimSession(java.io.File dir, java.nio.file.Path configDir,
                              String sessionId) {
        SessionKey key = keyFor(dir, configDir, sessionId);
        if (key == null) return;
        // Membership and empty-entry cleanup stay inside compute() so a claim
        // cannot be added to an owner set that a concurrent release just
        // detached from the map (which would silently lose the claim).
        SESSION_CLAIMS.compute(key, (k, owners) -> {
            Set<ClaudeSessionController> updated =
                    owners != null ? owners : ConcurrentHashMap.newKeySet();
            updated.add(this);
            return updated;
        });
    }

    private void releaseClaim(java.io.File dir, java.nio.file.Path configDir,
                              String sessionId) {
        SessionKey key = keyFor(dir, configDir, sessionId);
        if (key == null) return;
        SESSION_CLAIMS.computeIfPresent(key, (k, owners) -> {
            owners.remove(this);
            return owners.isEmpty() ? null : owners;
        });
    }

    /** Removes all claims held by this controller (called when the process stops). */
    private void releaseAllClaims() {
        for (SessionKey key : SESSION_CLAIMS.keySet()) {
            SESSION_CLAIMS.computeIfPresent(key, (k, owners) -> {
                owners.remove(this);
                return owners.isEmpty() ? null : owners;
            });
        }
    }

    /**
     * Session IDs claimed by other live controllers <em>in the same store</em>.
     * A session ID is not unique across config dirs (a JSONL can be copied
     * between the default and an isolated profile), so a claim from another
     * store must not hide this store's file.
     */
    private Set<String> claimedByOthers(java.io.File dir, java.nio.file.Path configDir) {
        Set<String> ids = new HashSet<>();
        if (dir == null || configDir == null) return ids;
        java.nio.file.Path wantedConfig = configDir.toAbsolutePath().normalize();
        java.nio.file.Path wantedProject = dir.toPath().toAbsolutePath().normalize();
        SESSION_CLAIMS.forEach((key, owners) -> {
            if (!key.configDir().equals(wantedConfig)
                    || !key.projectDir().equals(wantedProject)) {
                return;
            }
            // A session shared with another tab (both resumed the same saved
            // conversation) is still this tab's own conversation: excluding it
            // would make the scan pick an unrelated file.
            if (owners.contains(this)) {
                return;
            }
            for (ClaudeSessionController owner : owners) {
                if (owner != this && LIVE_SESSIONS.contains(owner) && owner.isProcessLive()) {
                    ids.add(key.sessionId());
                    return;
                }
            }
        });
        return ids;
    }

    /**
     * Package-private for tests: session IDs this controller treats as claimed
     * by other live tabs in the same store.
     */
    Set<String> simulateClaimedByOthers(java.io.File dir, java.nio.file.Path configDir) {
        return claimedByOthers(dir, configDir);
    }

    /**
     * Returns {@code true} while this controller's PTY is running. Controllers
     * without a process (test seams) count as live.
     */
    private boolean isProcessLive() {
        ClaudeProcess cp = claudeProcess;
        return cp == null || cp.isRunning();
    }

    /**
     * Releases live-session bookkeeping when the PTY of {@code generation} exits
     * on its own (Claude quit or crashed while the tab stayed open). The check
     * and the cleanup are atomic with process replacement, so a late callback
     * from a process that has already been stopped and replaced by a restart
     * cannot wipe the new run's ownership.
     */
    private void onProcessExited(long generation) {
        synchronized (processOwnershipLock) {
            if (generation != processGeneration) return;
            LIVE_SESSIONS.remove(this);
            releaseAllClaims();
        }
    }

    /** Package-private for tests: simulates the PTY exiting without stopProcess(). */
    void simulateProcessExit() {
        simulateProcessExitAt(simulateProcessGeneration());
    }

    /** Package-private for tests: the ownership generation of the current run. */
    long simulateProcessGeneration() {
        synchronized (processOwnershipLock) {
            return processGeneration;
        }
    }

    /** Package-private for tests: replays the exit callback of an older run. */
    void simulateProcessExitAt(long generation) {
        onProcessExited(generation);
    }

    /**
     * Returns the effective config dir of the running session.
     *
     * @return config dir, or {@code null} when no session was started
     */
    public java.nio.file.Path getActiveConfigDir() {
        return activeConfigDir;
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
        promptSubmittedAt = System.currentTimeMillis();
        scheduleIdentityRefresh();

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
                clearActiveChoiceMenu();
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
                clearActiveChoiceMenu();
            } else if (answer.startsWith("TYPE:")) {
                int sep = answer.indexOf(':', 5);
                String digit = answer.substring(5, sep);
                String text = answer.substring(sep + 1);
                ChoiceMenuModel current = detectCurrentMenu();
                int targetIdx = Integer.parseInt(digit) - 1;
                // currentIdx = actual PTY cursor position (re-read from screen); currentHasTextInput
                // = cursor is on a text-input option (digits go to its text field, not to navigation).
                final int currentIdx = current != null ? current.defaultOptionIndex() : targetIdx;
                boolean currentHasTextInput = current != null
                        && isTextInputOption(current.options().get(currentIdx));
                LOG.fine("[PTY write] type-input digit=" + digit + " text=" + text
                        + " currentHasTextInput=" + currentHasTextInput);
                Thread t = new Thread(() -> {
                    try {
                        if (currentHasTextInput) {
                            int delta = targetIdx - currentIdx;
                            if (delta != 0) {
                                byte[] arrow = delta < 0 ? new byte[]{0x1b, '[', 'A'} : new byte[]{0x1b, '[', 'B'};
                                for (int i = 0; i < Math.abs(delta); i++) {
                                    connector.write(arrow);
                                    Thread.sleep(80);
                                }
                                Thread.sleep(100);
                            }
                            connector.write(text);
                            Thread.sleep(200);
                            connector.write("\r");
                        } else {
                            connector.write(digit);
                            Thread.sleep(200);
                            connector.write(text);
                            Thread.sleep(200);
                            connector.write("\r");
                        }
                    } catch (IOException ex) {
                        LOG.warning("writePtyAnswer TYPE write failed: " + ex.getMessage());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }, "pty-typeinput");
                t.setDaemon(true);
                t.start();
                clearActiveChoiceMenu();
            } else if (answer.startsWith("ARROW:")) {
                int targetIdx = Integer.parseInt(answer.substring(6));
                ChoiceMenuModel current = model.getActiveChoiceMenu();
                int currentIdx = current != null ? current.defaultOptionIndex() : 0;
                int delta = targetIdx - currentIdx;
                LOG.fine("[PTY write] ARROW targetIdx=" + targetIdx + " currentIdx=" + currentIdx + " delta=" + delta);
                clearActiveChoiceMenu();
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
                ChoiceMenuModel current = detectCurrentMenu();
                // currentHasTextInput = PTY cursor (❯) is on a text-input option (re-read from
                // screen); digits go into its text field, so use arrow-key navigation instead.
                int currentIdx2 = current != null ? current.defaultOptionIndex() : 0;
                boolean currentHasTextInput = current != null
                        && isTextInputOption(current.options().get(currentIdx2));
                LOG.fine("[PTY write] else-branch answer=" + answer + " currentIdx=" + currentIdx2 + " currentHasTextInput=" + currentHasTextInput);
                if (currentHasTextInput && answer.matches("[0-9]")) {
                    int targetIdx = answer.charAt(0) - '1';
                    int currentIdx = currentIdx2;
                    int delta = targetIdx - currentIdx;
                    LOG.fine("[PTY write] ARROW-digit targetIdx=" + targetIdx + " currentIdx=" + currentIdx + " delta=" + delta);
                    clearActiveChoiceMenu();
                    if (delta == 0) {
                        connector.write("\r");
                    } else {
                        byte[] arrow = delta < 0 ? new byte[]{0x1b, '[', 'A'} : new byte[]{0x1b, '[', 'B'};
                        Thread t = new Thread(() -> {
                            try {
                                for (int i = 0; i < Math.abs(delta); i++) {
                                    connector.write(arrow);
                                    Thread.sleep(80);
                                }
                                connector.write("\r");
                            } catch (IOException ex) {
                                LOG.warning("writePtyAnswer ARROW-digit write failed: " + ex.getMessage());
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                            }
                        }, "pty-arrow-digit");
                        t.setDaemon(true);
                        t.start();
                    }
                } else if (answer.matches("[0-9]{2,}") && menuCursorNumber(current) > 0) {
                    // Items 10+ cannot be typed (a digit selects immediately): move the PTY
                    // cursor onto the item with arrow keys, then Enter.
                    int delta = Integer.parseInt(answer) - menuCursorNumber(current);
                    LOG.fine("[PTY write] ARROW-number answer=" + answer + " delta=" + delta);
                    clearActiveChoiceMenu();
                    sendArrowsThenEnter(delta, "pty-arrow-number");
                } else {
                    boolean isMenuDigit = answer.matches("[0-9]");
                    String toWrite = isMenuDigit ? answer : answer + "\r";
                    LOG.fine("[PTY write] " + toWrite.replace("\r", "\\r").replace("\n", "\\n"));
                    connector.write(toWrite);
                    clearActiveChoiceMenu();
                }
            }
        } catch (IOException ex) {
            LOG.warning("writePtyAnswer failed: " + ex.getMessage());
        }
    }

    /** Menu number under the PTY cursor according to {@code menu}, or 0 if unknown. */
    private int menuCursorNumber(ChoiceMenuModel menu) {
        if (menu != null && menu.cursorNumber() > 0) return menu.cursorNumber();
        ChoiceMenuModel active = model.getActiveChoiceMenu();
        return active != null ? active.cursorNumber() : 0;
    }

    /** Presses Down (delta &gt; 0) or Up (delta &lt; 0) {@code |delta|} times, then Enter, on a daemon thread. */
    private void sendArrowsThenEnter(int delta, String threadName) {
        Thread t = new Thread(() -> {
            try {
                byte[] arrow = delta < 0 ? PTY_ARROW_UP : PTY_ARROW_DOWN;
                for (int i = 0; i < Math.abs(delta); i++) {
                    connector.write(arrow);
                    Thread.sleep(80);
                }
                connector.write("\r");
            } catch (IOException ex) {
                LOG.warning(threadName + " write failed: " + ex.getMessage());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }, threadName);
        t.setDaemon(true);
        t.start();
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
     * <p>Called when Shift+Tab is pressed anywhere in {@link io.github.nbplugins.claudecodegui.ui.ClaudePromptPanel}.
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
                // a running sendShiftTabsUntilMode worker owns the flag
                synchronized (editModeSwitchLock) {
                    if (editModeSwitchTarget == null) modeSwitchInProgress = false;
                }
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
     * <p>When {@code targetMode} is {@link EditMode#AUTO} and the mode cannot be
     * reached (older Claude Code that does not support auto mode), falls back to
     * {@link EditMode#BYPASS_PERMISSIONS} if available, then {@link EditMode#ACCEPT_EDITS}.
     *
     * <p>Runs on a daemon thread; sets lifecycle to WORKING at start and
     * READY on completion. At most one worker runs at a time: a request made while
     * a switch is in progress only replaces its target, and the running worker
     * continues to the new target once the current one is handled. (Two concurrent
     * workers would each press Shift+Tab toward their own target, cycling endlessly.)
     *
     * @param targetMode the edit mode to reach
     */
    private void sendShiftTabsUntilMode(EditMode targetMode) {
        if (!isConnected()) return;
        final long startGeneration;
        synchronized (editModeSwitchLock) {
            boolean running = editModeSwitchTarget != null;
            editModeSwitchTarget = targetMode;
            startGeneration = ++editModeSwitchGeneration;
            if (running) {
                LOG.fine("sendShiftTabsUntilMode: switch in progress, retargeting to " + targetMode);
                return;
            }
            modeSwitchInProgress = true;
        }
        model.setLifecycle(SessionLifecycle.WORKING);
        Thread t = new Thread(() -> {
            EditMode target = targetMode;
            long generation = startGeneration;
            try {
                while (target != null) {
                    try {
                        switchEditMode(target);
                    } catch (IOException | RuntimeException ex) {
                        // a failed switch must not drop a newer request queued meanwhile
                        LOG.warning("sendShiftTabsUntilMode failed: " + ex.getMessage());
                    }
                    synchronized (editModeSwitchLock) {
                        if (editModeSwitchGeneration == generation) {
                            editModeSwitchTarget = null;
                            modeSwitchInProgress = false;
                        }
                        target = editModeSwitchTarget;
                        generation = editModeSwitchGeneration;
                    }
                }
            } catch (InterruptedException ex) {
                LOG.warning("sendShiftTabsUntilMode interrupted");
                synchronized (editModeSwitchLock) {
                    editModeSwitchTarget = null;
                    modeSwitchInProgress = false;
                }
            } finally {
                onClaudeIdle();
            }
        }, "shift-tab-edit-mode");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Presses Shift+Tab until {@code targetMode} is reached; for {@link EditMode#AUTO}
     * falls back to {@link EditMode#BYPASS_PERMISSIONS}, then {@link EditMode#ACCEPT_EDITS}.
     */
    private void switchEditMode(EditMode targetMode) throws IOException, InterruptedException {
        LOG.fine("sendShiftTabsUntilMode: target=" + targetMode
                + " currentModel=" + model.getEditMode());
        if (trySendShiftTabsUntilMode(targetMode)) return;
        // AUTO mode not found — older CC doesn't support it; try fallbacks
        if (targetMode == EditMode.AUTO) {
            LOG.warning("sendShiftTabsUntilMode: auto mode not available, trying fallbacks");
            if (trySendShiftTabsUntilMode(EditMode.BYPASS_PERMISSIONS)) return;
            trySendShiftTabsUntilMode(EditMode.ACCEPT_EDITS);
        }
    }

    /**
     * Inner loop: sends up to 5 Shift+Tab presses and returns {@code true} when
     * {@code targetMode} is detected on screen, {@code false} if not reached.
     *
     * <p>Key design: after each Shift+Tab press the loop waits for a
     * <em>concrete mode change</em> (a non-empty {@link EditMode} that differs from
     * the last known mode) rather than polling for a fixed number of intervals.
     * This prevents tab accumulation — the next tab is only sent once the current
     * tab's effect has been observed, so CC never receives multiple buffered
     * keystrokes that it processes in rapid succession (which would cause PLAN to
     * flash for 0 ms and be immediately overridden by the next tab).
     *
     * <p>If no concrete change is seen within {@link #modeSwitchPollCount} ×
     * {@link #modeSwitchPollMs} ms (default 1 500 ms), the tab is assumed to have
     * been processed into a mode that produces no on-screen marker (e.g. DEFAULT
     * during WORKING state), and the next tab is sent.
     */
    boolean trySendShiftTabsUntilMode(EditMode targetMode)
            throws IOException, InterruptedException {
        Optional<EditMode> initial = detectCurrentMode();
        LOG.fine("trySendShiftTabsUntilMode: target=" + targetMode
                + " initial=" + initial.map(EditMode::key).orElse("(empty)"));
        if (isModeAtTarget(initial, targetMode)) return true;

        Optional<EditMode> lastConcrete = initial;
        for (int attempt = 0; attempt < 5; attempt++) {
            sendOneShiftTab();
            Optional<EditMode> next = waitForConcreteModeChange(lastConcrete);
            LOG.fine("trySendShiftTabsUntilMode: target=" + targetMode
                    + " attempt=" + attempt
                    + " before=" + lastConcrete.map(EditMode::key).orElse("(empty)")
                    + " after=" + next.map(EditMode::key).orElse("(timeout)"));
            if (isModeAtTarget(next, targetMode)) return true;
            if (next.isPresent()) {
                lastConcrete = next;
            }
            // next.isEmpty() = timed out: tab was processed but result is invisible
            // (e.g. DEFAULT during WORKING). Proceed to next tab.
        }
        LOG.warning("trySendShiftTabsUntilMode: did not reach " + targetMode + " after 5 attempts");
        return false;
    }

    /**
     * Polls the screen up to {@link #modeSwitchPollCount} times, returning as soon
     * as a concrete (non-empty) mode that differs from {@code before} is detected on
     * two consecutive polls. Returns {@link Optional#empty()} on timeout.
     *
     * <p>Requiring two consecutive agreeing polls (rather than acting on the first
     * differing reading) guards against a single screen-poll tick catching Claude's
     * Ink renderer mid-repaint of the footer — a one-off glitch frame that shows an
     * unrelated mode would otherwise be mistaken for the Shift+Tab's real effect,
     * desynchronizing this loop's notion of "current mode" from what CC actually
     * shows and causing the final mode to land somewhere other than {@code targetMode}.
     */
    private Optional<EditMode> waitForConcreteModeChange(Optional<EditMode> before)
            throws InterruptedException {
        Optional<EditMode> candidate = Optional.empty();
        for (int poll = 0; poll < modeSwitchPollCount; poll++) {
            Thread.sleep(modeSwitchPollMs);
            Optional<EditMode> detected = detectCurrentMode();
            LOG.fine("trySendShiftTabsUntilMode poll=" + poll
                    + " detected=" + detected.map(EditMode::key).orElse("(empty)"));
            if (detected.isPresent() && !detected.equals(before)) {
                if (candidate.isPresent() && candidate.equals(detected)) {
                    return detected;
                }
                candidate = detected;
            } else {
                candidate = Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** Returns {@code true} if {@code detected} corresponds to {@code target}. */
    private boolean isModeAtTarget(Optional<EditMode> detected, EditMode target) {
        if (detected.isPresent() && detected.get() == target) return true;
        // DEFAULT has no on-screen marker; empty detection = in default.
        return target == EditMode.DEFAULT && detected.isEmpty();
    }

    /** Package-private for testing: reads the current edit mode from the terminal screen. */
    Optional<EditMode> detectCurrentMode() {
        return screenContentDetector.detectEditMode(screenLines.get());
    }

    /** Package-private for testing: whether a PTY connector is active. */
    boolean isConnected() {
        return connector != null;
    }

    /** Package-private for testing: sends one Shift+Tab (ESC [ Z) to the PTY. */
    void sendOneShiftTab() throws IOException {
        connector.write(new byte[]{0x1b, '[', 'Z'});
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
                    if (index < 9) {
                        connector.write(String.valueOf(index + 1));
                    } else {
                        // Items 10+ cannot be typed: move the cursor onto the item, then Enter.
                        int cursor = waitForModelMenuCursor();
                        if (cursor > 0) {
                            int delta = index + 1 - cursor;
                            byte[] arrow = delta < 0 ? PTY_ARROW_UP : PTY_ARROW_DOWN;
                            for (int i = 0; i < Math.abs(delta); i++) {
                                connector.write(arrow);
                                Thread.sleep(80);
                            }
                            connector.write("\r");
                        } else {
                            LOG.warning("model switch: /model menu not visible, cannot select item " + (index + 1));
                            connector.write(PTY_ESC);
                        }
                    }
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

    /** Waits (up to ~3 s) for the {@code /model} menu and returns the number under the cursor, or 0. */
    private int waitForModelMenuCursor() throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            Optional<ChoiceMenuModel> m = screenContentDetector.detectChoiceMenu(screenLines.get());
            if (m.isPresent() && m.get().cursorNumber() > 0) return m.get().cursorNumber();
            Thread.sleep(150);
        }
        return 0;
    }

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

                // A long menu shows only a window of the models: walk the cursor through the
                // whole list (and back) so the combo gets every model, not just the visible ones.
                if (menuOpt.isPresent() && menuOpt.get().scrollable()) {
                    ChoiceMenuModel full = newMenuEnumerator().enumerate(menuOpt.get());
                    menuOpt = Optional.of(full);
                    lines = MenuEnumerator.toScreenLines(full);
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
                // Always prefer ModelMenuParser — it is fixture-tested and handles all known
                // formats including 1M-context variants. The ChoiceMenuModel path is kept as
                // a fallback for formats that ModelMenuParser does not recognise.
                ModelMenuParser.ModelDiscovery discovery = new ModelMenuParser().parse(lines);
                if (!discovery.models().isEmpty()) {
                    models = discovery.models();
                    selIdx = discovery.currentIndex();
                } else if (menuOpt.isPresent() && !menuOpt.get().options().isEmpty()) {
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
                    models = List.of();
                    selIdx = -1;
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
        // Keep the session identity — and the cross-tab claims that follow from
        // it — in sync with the file Claude is writing, so a conversation switch
        // made inside the TUI is noticed by every tab. The scan enumerates the
        // session store, so it runs on a worker instead of this (EDT) tick.
        scheduleIdentityRefresh();
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
        // Also defer when the screen shows "esc to interrupt": detectInputPromptReady
        // returns true even while Claude is WORKING (the ❯ area is always visible),
        // so without this guard, discoverModels() would fire immediately, send /model
        // to a busy Claude, time out, and leave the session permanently stuck in WORKING.
        if (model.getLifecycle() == SessionLifecycle.READY
                && !modelComboPopulated && !modelDiscoveryInProgress
                && model.getActiveChoiceMenu() == null
                && screenContentDetector.detectChoiceMenu(lines).isEmpty()
                && screenContentDetector.detectSessionState(lines) != ScreenContentDetector.DetectedSessionState.WORKING) {
            discoverModels();
            detectAndApplyInitialEditMode(lines);
        }

        // Detect choice menu on every poll as backup for when promptFlushTimer misses it
        // (e.g. thinking spinner keeps PTY active and prevents the 400 ms silence).
        // Also clear stale menu when it disappears from screen — flushPendingPrompt
        // may never fire if the spinner continuously restarts promptFlushTimer.
        if (!modelDiscoveryInProgress && !menuEnumerationInProgress) {
            Optional<ChoiceMenuModel> menuOpt = screenContentDetector.detectChoiceMenu(lines);
            if (menuOpt.isPresent()) {
                applyDetectedChoiceMenu(menuOpt.get(), "[pollScreenState] ");
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
                    clearActiveChoiceMenu();
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
            applyDetectedEditMode(screenContentDetector.detectEditMode(lines));
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
        clearActiveChoiceMenu();
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
        if (modelDiscoveryInProgress || menuEnumerationInProgress) return;

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
                clearActiveChoiceMenu();
            } else {
                applyDetectedChoiceMenu(req.get(), "[screen prompt] ");
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
            showChoiceMenu(r);
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
    private static boolean isTextInputOption(ChoiceMenuModel.Option opt) {
        return opt.hasTextInput() || opt.display().trim().toLowerCase().startsWith("type");
    }

    /** Re-reads the current screen to get the up-to-date choice menu (PTY cursor may have moved). */
    private ChoiceMenuModel detectCurrentMenu() {
        List<String> lines = screenLines.get();
        return screenContentDetector.detectChoiceMenu(lines).orElse(model.getActiveChoiceMenu());
    }

    /**
     * Applies a newly detected choice menu to the model, or — if a <em>different</em>
     * menu is already active — defers applying it until the same replacement candidate
     * is confirmed by a second consecutive detection.
     *
     * <p>Showing a choice menu resizes the terminal to fit {@code ChoiceMenuPanel}
     * (see {@code ClaudeSessionTab#switchSouthCard}), which makes Claude's Ink renderer
     * re-wrap the same dialog at the new column width. Without this debounce, that
     * transiently different-looking reflow would immediately replace the menu just
     * shown, triggering another divider resize, another reflow, and so on — a visible
     * flicker loop. Withholding the replacement until it is confirmed twice means the
     * destabilizing resize never happens for a one-off reflow artifact, so the screen
     * naturally reverts to matching the currently active menu on the next poll.
     *
     * @param newMenu the menu detected on this poll
     * @param logTag  short prefix identifying the calling poll path, for LOG.fine messages
     */
    /**
     * Applies a newly detected edit mode to the model, or — if it differs from both
     * the current mode and any already-pending candidate — withholds it until the
     * same mode is confirmed by a second consecutive detection.
     *
     * <p>Claude's Ink renderer repaints its footer using cursor-movement escape
     * sequences; a single screen-poll tick can catch that repaint mid-flight and read
     * a reflowed or stale line, producing a one-off incorrect {@link EditMode} even
     * though the real footer (and the mode itself) never actually changed. Applying
     * every single-poll detection immediately (as before this debounce) could flip
     * the model to that glitch mode; the next poll would then see the real mode again
     * and flip back, visible to the user as the mode briefly cycling through unrelated
     * values. Requiring the same new mode on two consecutive polls — the same pattern
     * used for choice-menu replacement in {@link #applyDetectedChoiceMenu} — means a
     * one-off glitch frame never survives to the next poll and is never applied.
     *
     * @param detected the mode detected on this poll, or empty if the screen shows no
     *                 recognizable mode indicator (idle/transitioning, or WORKING with
     *                 no visible marker)
     */
    private void applyDetectedEditMode(Optional<EditMode> detected) {
        LOG.fine("[pollScreenState] editMode sync: detected=" + detected.orElse(null) + " current=" + model.getEditMode());
        if (detected.isEmpty()) {
            if (model.getLifecycle() == SessionLifecycle.WORKING) {
                // No on-screen marker while working is the expected steady state
                // (not a glitch) — preserve the current mode and drop any pending candidate.
                pendingEditModeCandidate = Optional.empty();
                return;
            }
            // Unknown mode outside WORKING (screen transitioning or idle with no indicator)
            // → treat as Ask/default.
            detected = Optional.of(EditMode.DEFAULT);
        }
        EditMode mode = detected.get();
        if (mode == model.getEditMode()) {
            pendingEditModeCandidate = Optional.empty();
            return;
        }
        if (pendingEditModeCandidate.isPresent() && pendingEditModeCandidate.get() == mode) {
            LOG.fine("[pollScreenState] editMode confirmed: " + mode);
            pendingEditModeCandidate = Optional.empty();
            model.setEditMode(mode);
        } else {
            LOG.fine("[pollScreenState] editMode candidate, awaiting confirmation: " + mode);
            pendingEditModeCandidate = Optional.of(mode);
        }
    }

    /**
     * Shows {@code menu} in the choice panel. A scrolling menu (only a window of a longer
     * list is on screen) is first completed on a daemon thread by {@link MenuEnumerator},
     * which walks the PTY cursor through the whole list and back; the poll timers are
     * suppressed meanwhile, and the panel is shown only once the full list is known.
     */
    private void showChoiceMenu(ChoiceMenuModel menu) {
        if (!menu.scrollable() || connector == null || menuEnumerationInProgress) {
            model.setActiveChoiceMenu(menu);
            return;
        }
        menuEnumerationInProgress = true;
        Thread t = new Thread(() -> {
            ChoiceMenuModel full = menu;
            try {
                full = newMenuEnumerator().enumerate(menu);
            } catch (IOException ex) {
                LOG.warning("menu enumeration failed: " + ex.getMessage());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } finally {
                final ChoiceMenuModel result = full;
                SwingUtilities.invokeLater(() -> {
                    menuEnumerationInProgress = false;
                    if (model.getActiveChoiceMenu() == null) model.setActiveChoiceMenu(result);
                });
            }
        }, "claude-menu-enumeration");
        t.setDaemon(true);
        t.start();
    }

    private MenuEnumerator newMenuEnumerator() {
        return new MenuEnumerator(screenContentDetector, screenLines, new MenuEnumerator.Keys() {
            @Override public void down() throws IOException { connector.write(PTY_ARROW_DOWN); }
            @Override public void up() throws IOException { connector.write(PTY_ARROW_UP); }
        }, Thread::sleep, MENU_ENUMERATION_SETTLE_MS);
    }

    private void applyDetectedChoiceMenu(ChoiceMenuModel newMenu, String logTag) {
        ChoiceMenuModel current = model.getActiveChoiceMenu();
        if (current == null) {
            pendingChoiceMenuCandidate = null;
            LOG.fine(logTag + "setting choice menu: \"" + newMenu.text() + "\"");
            showChoiceMenu(newMenu);
            return;
        }
        if (sameMenu(newMenu, current) || isShrunkViewOf(newMenu, current)) {
            pendingChoiceMenuCandidate = null;
            return;
        }
        if (pendingChoiceMenuCandidate != null && sameMenu(newMenu, pendingChoiceMenuCandidate)) {
            LOG.fine(logTag + "confirmed replacement menu: \"" + newMenu.text() + "\"");
            pendingChoiceMenuCandidate = null;
            model.setActiveChoiceMenu(newMenu);
        } else {
            LOG.fine(logTag + "candidate replacement menu, awaiting confirmation: \"" + newMenu.text() + "\"");
            pendingChoiceMenuCandidate = newMenu;
        }
    }

    /**
     * Returns {@code true} when {@code newMenu} is just a shorter window onto the same
     * scrolling list as {@code current}: same question (or both "question" lines are
     * scroll indicators such as {@code ↑ 8. Fable 5}, which change with the scroll
     * position) and every option matches an option of {@code current} by response and
     * display, with fewer options in total.
     *
     * <p>Scrolling pickers such as {@code /model} draw as many items as fit the terminal.
     * The choice panel shrinks the terminal, which makes Claude draw fewer items (and
     * move the scroll window), which shrinks the panel, which grows the terminal again —
     * an endless flicker. Keeping the larger option list breaks that loop.
     */
    static boolean isShrunkViewOf(ChoiceMenuModel newMenu, ChoiceMenuModel current) {
        List<ChoiceMenuModel.Option> n = newMenu.options();
        List<ChoiceMenuModel.Option> c = current.options();
        if (n.size() >= c.size()) return false;
        boolean sameText = newMenu.text().equals(current.text())
                || (isScrollIndicator(newMenu.text()) && isScrollIndicator(current.text()));
        if (!sameText) return false;
        for (ChoiceMenuModel.Option no : n) {
            boolean found = false;
            for (ChoiceMenuModel.Option co : c) {
                if (no.display().equals(co.display()) && no.response().equals(co.response())) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    private static boolean isScrollIndicator(String text) {
        return text.startsWith("\u2191") || text.startsWith("\u2193");
    }

    private static boolean sameMenu(ChoiceMenuModel a, ChoiceMenuModel b) {
        return a.text().equals(b.text()) && optionsEqual(a.options(), b.options());
    }

    /**
     * Clears the active choice menu and any pending replacement candidate held by
     * {@link #applyDetectedChoiceMenu}, so a stale candidate from before the menu was
     * dismissed can never leak into whatever is shown next.
     */
    private void clearActiveChoiceMenu() {
        pendingChoiceMenuCandidate = null;
        model.clearChoiceMenu();
    }

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
