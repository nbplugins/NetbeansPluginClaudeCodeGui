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
import java.util.LinkedHashSet;
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

    /**
     * Maximum number of session files written since a prompt inspected for the
     * tab's own conversation. Reading a JSONL is not free, and the file of the
     * running process is always among the most recently written ones.
     */
    private static final int PROMPT_EVIDENCE_CANDIDATES = 8;

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
    private volatile java.util.function.Consumer<byte[]> ptyWriteHook;
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
    private static final Map<SessionKey, SessionClaims> SESSION_CLAIMS =
            new ConcurrentHashMap<>();

    /**
     * Live controllers writing one session file, split by how strong their hold
     * on it is.
     *
     * <ul>
     *   <li>{@link #owners} — the tab knew this conversation at launch
     *       ({@code --continue}/{@code --resume}) and no other live tab held it
     *       when the claim was made. Only an owner may treat the file as
     *       established, and only ownership elsewhere makes the file foreign to
     *       this tab.</li>
     *   <li>{@code provisional} (a holder in neither set) — a {@code NEW} tab
     *       guessed that this file is its own. A guess is not ownership: it must
     *       never make the file look foreign to the tab that established it,
     *       nor be displaced merely because another unresolved tab follows the
     *       same file (with identical prompts the evidence cannot tell the two
     *       apart).</li>
     *   <li>{@link #borrowed} — the tab joined a file another live tab already
     *       held (an in-process resume into a sibling's conversation, or a NEW
     *       tab following it until its own file appears).</li>
     * </ul>
     */
    private static final class SessionClaims {
        final Set<ClaudeSessionController> holders = ConcurrentHashMap.newKeySet();
        final Set<ClaudeSessionController> owners = ConcurrentHashMap.newKeySet();
        final Set<ClaudeSessionController> borrowed = ConcurrentHashMap.newKeySet();
    }

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
     * {@code true} when the session ID was known before the first prompt (a
     * {@code --continue}/{@code --resume} launch). A {@code NEW} session has no
     * identity up front, so the file it later adopts is only a guess until the
     * tab proves the file is its own — see {@link #claimSession}.
     */
    private volatile boolean launchIdentityKnown;

    /**
     * Session ID the process was launched with ({@code --continue} or
     * {@code --resume}), or {@code null} for a {@code NEW} launch. Kept after
     * the tracked identity changes: when a prompt submitted later turns out to
     * have been appended to the launch conversation, that record proves the
     * conversation never actually switched (see
     * {@link #refreshSessionIdentity()}).
     */
    private volatile String launchSessionId;

    /**
     * Session ID a {@link SessionMode#NEW} run resolved as the file it created
     * for its own submission, or {@code null} before that. The unknown-text
     * own-file priority belongs to that file only: afterwards the run's
     * conversation is established, and a later recalled prompt must be looked
     * up among the run's own and the shared conversations instead of adopting
     * an unrelated unclaimed file that merely received a user record after the
     * submission. The ID is kept even when the tab adopts a borrowed
     * conversation and releases its claim: the own file must stay a candidate
     * for the submission that actually went there (see
     * {@link #refreshSessionIdentity()}).
     */
    private volatile String ownSessionId;

    /**
     * Session IDs this tab established or guessed as its own before it stopped
     * its writer, kept eligible for the final post-stop identity lookup of
     * {@link #stopAndRename(String)}. Stopping the PTY runs the exit callback,
     * which releases this tab's live claims; without the preserved evidence
     * this tab's own conversation would then look like another live tab's file
     * (or like a file established by another tab) and the lookup could pick an
     * unrelated conversation.
     *
     * <p>A conversation the tab only borrowed is not listed: it belongs to
     * another live tab, and preserving it would make the borrowed file win over
     * the file this tab actually created.
     */
    private volatile Set<String> preservedClaims = Set.of();

    /**
     * Time (epoch millis) a prompt was last submitted from the plugin. Used to
     * attribute the session file created by a new process to this tab promptly,
     * before any sibling tab could guess and claim it.
     */
    private volatile long promptSubmittedAt = 0;

    /**
     * Text of the last prompt submitted from the plugin or typed in the
     * terminal. Together with {@link #promptSubmittedAt} it is the evidence that
     * a file written after the prompt contains this tab's own messages — e.g. a
     * conversation resumed in-process — rather than a concurrent turn of a
     * sibling tab.
     */
    private volatile String lastPromptText;

    /**
     * True while the current thread writes to the PTY on behalf of the plugin
     * (see {@link #writePty(byte[])}), so terminal input can be told apart.
     */
    private final ThreadLocal<Boolean> pluginPtyWrite = ThreadLocal.withInitial(() -> false);

    /**
     * Time (epoch millis) of the last line submitted in the terminal rather
     * than sent by the plugin. Enter can act on the TUI (e.g. choose a session
     * in the {@code /resume} picker) without producing a prompt this tab
     * recognises, so prompt evidence captured before it no longer describes the
     * session on screen. Draft edits do not move it: typing the next prompt
     * must not invalidate the evidence of the one already submitted.
     */
    private volatile long externalInputAt;

    /**
     * Time (epoch millis) of the last conversation change driven by a
     * {@code /resume} or {@code /clear} command or by the answer to the
     * {@code /resume} picker, whether sent from the prompt panel or typed in
     * the terminal. Such a command is not a prompt written to the conversation:
     * it switches the conversation the process writes, so prompt evidence
     * captured before it no longer proves the shared launch conversation is the
     * one on screen.
     */
    private volatile long conversationSwitchAt;

    /**
     * Line being typed in the terminal, assembled from the raw PTY input so
     * that a prompt submitted from the keyboard is recognised as evidence for
     * the conversation on screen (see {@link #onTerminalInput(byte[], long)}).
     */
    private final StringBuilder terminalInputLine = new StringBuilder();

    /**
     * Insertion point within {@link #terminalInputLine}, in characters. Cursor
     * keys move it so that text inserted after an edit (typing over a typo,
     * Delete, Backspace) lands where the user sees it.
     */
    private int terminalInputCursor;

    /**
     * Escape-sequence parser state: {@code 0} outside a sequence, {@code 1}
     * right after {@code ESC}, {@code 2} inside a CSI sequence ({@code ESC [}),
     * {@code 3} inside an SS3 sequence ({@code ESC O}).
     *
     * <p>A sequence is skipped as a whole: the {@code [} that opens a CSI is a
     * parameter byte, not a final one, and treating it as final would let
     * cursor keys (e.g. {@code ESC [ D}) leak {@code D} into the typed line.
     */
    private int terminalInputEscape;

    /** Parameters of the CSI sequence being parsed, e.g. {@code 200}. */
    private final StringBuilder terminalEscapeParams = new StringBuilder();

    /**
     * True when the {@code ESC} that may still turn out to be a standalone
     * cancel was pressed while a choice menu was open. Text typed into a
     * picker's search box never reaches a prompt, so when the {@code ESC} is
     * confirmed as a live cancellation the recorded line is discarded.
     */
    private boolean terminalEscapeOnMenu;

    /**
     * The choice menu the terminal input already completed (a digit selected a
     * numbered item, Enter or Right submitted, Esc cancelled) while the model
     * still reports it. The screen is polled only every half second, so the
     * dismissed menu stays in the model for a while; input arriving in that
     * window must not be classified against a menu the TUI no longer shows.
     * Compared by identity: every detected menu is a fresh instance.
     *
     * <p>Guarded by {@link #terminalInputLine}.
     */
    private ChoiceMenuModel terminalCompletedMenu;

    /**
     * True when the last Enter confirmed a choice menu selection instead
     * of submitting a prompt. The {@code /resume} picker changes the
     * conversation that way, even when the command that opened it was recalled
     * from the history or completed by Tab and is therefore not recognised as
     * a command.
     */
    private boolean terminalMenuSelection;

    /**
     * True while a bracketed paste ({@code ESC [ 200 ~} … {@code ESC [ 201 ~})
     * is being recorded: its newlines belong to the pasted prompt and must not
     * submit the line.
     */
    private boolean terminalInputPaste;

    /** True when a pasted CR was recorded and the LF of a CRLF pair must be skipped. */
    private boolean terminalPasteAfterCR;

    /**
     * True after Up/Down was pressed on an empty recorded line: the TUI then
     * fills its input from its prompt history, with text this parser never
     * sees. A following Enter is still a submission, only with unknown text.
     */
    private boolean terminalHistoryRecall;

    /**
     * True after a live Tab: the TUI completes the input from its own
     * suggestions with text this parser never sees, so the recorded line is
     * only a prefix of what will be submitted.
     */
    private boolean terminalCompletionEdited;

    /** Set when Enter submitted a history-recall or completion-edited line the parser did not record. */
    private boolean terminalRecalledSubmission;

    /**
     * True when the terminal received input other than Enter since the
     * recorder was last reset, including edits the recorder cannot see in the
     * buffer: Backspace on an empty recording, cursor keys, Ctrl+U or a Tab
     * completion. Keys typed during the pause between the GUI prompt's text
     * and its Enter are part of the line the TUI submits, so they invalidate
     * the composed evidence even when they leave the recorded line empty.
     */
    private boolean terminalInputSinceGuiSend;

    /**
     * True when Enter was pressed in the terminal since the recorder was last
     * reset, i.e. while the GUI prompt was still waiting for its own Enter.
     * The TUI then submits the line at that Enter, and the panel's later Enter
     * only submits an empty line: its evidence belongs to the first
     * submission and must not be re-timed.
     */
    private boolean terminalEnterSinceGuiSend;

    /**
     * True between a GUI send's prompt write and the Enter that submits it:
     * the panel text is already on the TUI's input line. A terminal Enter in
     * that window submits the panel text together with what was typed there,
     * so the text recorded by the terminal parser is only a part of the
     * prompt and must not be published as its evidence.
     */
    private boolean guiSendAwaitingEnter;

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
        launchIdentityKnown = sessionId != null;
        launchSessionId = sessionId;
        ownSessionId = null;
    }

    /** Package-private for tests: one tick of the periodic identity refresh. */
    void simulateIdentityTick() {
        refreshSessionIdentityIfDue();
    }

    /** Package-private for tests: records a prompt submitted from the plugin. */
    void simulatePromptSubmittedAt(long millis) {
        promptSubmittedAt = millis;
    }

    /** Package-private for tests: records the text of the last submitted prompt. */
    void simulatePromptText(String text) {
        lastPromptText = text;
    }

    /** Package-private for tests: the recorded text of the last submission. */
    String simulateLastPromptText() {
        return lastPromptText;
    }

    /** Package-private for tests: the recorded time of the last submission. */
    long simulatePromptSubmittedAtValue() {
        return promptSubmittedAt;
    }

    /** Package-private for tests: the prompt-evidence part of {@link #sendPrompt(String)}. */
    void simulateSentPrompt(String text, long sentAt) {
        rememberSentPrompt(text, sentAt);
    }

    /** Package-private for tests: the choice-menu bookkeeping of {@link #writePtyAnswer(String)}. */
    void simulateGuiChoiceAnswer(String answer) {
        rememberChoiceMenuAnswer(answer);
    }

    /**
     * Package-private for tests: the recorder and evidence part of
     * {@link #sendPrompt(String)}.
     *
     * @return the line the conversation receives
     */
    String simulateGuiSend(String text, long sentAt) {
        return reconcileGuiSend(text, sentAt);
    }

    /**
     * Package-private for tests: the reconciliation and the initial text write
     * of {@link #sendPrompt(String)}, without the pause and Enter that
     * {@link #completeGuiSend()} adds.
     */
    void simulateGuiSendWithText(String text, long sentAt) throws IOException {
        writeGuiPrompt(text, sentAt);
    }

    /** Package-private for tests: the number of identity scans started. */
    long simulateScanSequence() {
        synchronized (processOwnershipLock) {
            return scanSequence;
        }
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

    /**
     * Package-private for tests: adds a claim on {@code sessionId} to a live
     * session that has already been started, i.e. the bookkeeping a scan
     * publishes after it resolved the conversation.
     */
    void simulateClaimSession(java.nio.file.Path configDir, String sessionId) {
        claimSession(workingDir != null ? workingDir : model.getWorkingDirectory(),
                configDir, sessionId);
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
        startProcess(dir, profileName, extraCliArgs, mode, resumeSessionId,
                prepareLaunchIdentity(dir, profileName, mode, resumeSessionId), widget);
    }

    /**
     * Launch-time session identity, captured before the PTY starts.
     *
     * @param configDir         config dir the process will write to
     * @param preStartSessionIds session IDs that already existed at launch
     * @param sessionId         session ID the process will use, or {@code null}
     *                          when it is only known after the first write
     */
    public record LaunchIdentity(java.nio.file.Path configDir,
                                 Set<String> preStartSessionIds,
                                 String sessionId) { }

    /**
     * Computes the launch-time session identity by scanning the session store.
     *
     * <p>The scan can take hundreds of milliseconds on projects with a large
     * history, so callers on the EDT should run it on a worker thread and pass
     * the result to
     * {@link #startProcess(java.io.File, String, String, SessionMode, String, LaunchIdentity, JediTermWidget)}.
     * The result must describe the store <em>before</em> the process starts.
     *
     * @param dir             working directory
     * @param profileName     profile name, or {@code null}/{@code ""} for Default
     * @param mode            session start mode
     * @param resumeSessionId session ID to resume (only used for RESUME_SPECIFIC)
     * @return the identity the started process will use
     */
    public static LaunchIdentity prepareLaunchIdentity(java.io.File dir, String profileName,
                                                       SessionMode mode,
                                                       String resumeSessionId) {
        ClaudeProfile profile = ClaudeProfileStore.findByName(profileName);
        return prepareLaunchIdentity(dir,
                ClaudeProcess.resolveConfigDir(profile, ClaudeCodePreferences.getProfilesDir()),
                mode, resumeSessionId);
    }

    /** Package-private for tests: captures the identity against an explicit config dir. */
    static LaunchIdentity prepareLaunchIdentity(java.io.File dir,
                                                java.nio.file.Path configDir,
                                                SessionMode mode,
                                                String resumeSessionId) {
        SessionMode effectiveMode = mode != null ? mode : SessionMode.NEW;
        Set<String> preStartSessionIds =
                ClaudeSessionStore.listSessionIds(dir.toPath(), configDir);
        String sessionId = null;
        if (effectiveMode == SessionMode.RESUME_SPECIFIC
                && resumeSessionId != null && !resumeSessionId.isBlank()) {
            sessionId = resumeSessionId;
        } else if (effectiveMode == SessionMode.CONTINUE_LAST) {
            sessionId = ClaudeSessionStore.findLatestSessionId(dir.toPath(), configDir);
        }
        return new LaunchIdentity(configDir, preStartSessionIds, sessionId);
    }

    /**
     * Makes the launch identity describe the store the process will actually
     * write.
     *
     * <p>The identity is prepared on a worker thread, while the spawn re-reads
     * the profile. If the profile's storage directory or
     * {@code CLAUDE_CONFIG_DIR} override changed in between, the prepared
     * snapshot lists the old store's sessions: every file in the new store
     * would look new, and a conversation written there within the file-time
     * slack could be adopted as this tab's own. The snapshot is then
     * recaptured against the spawn store before the process starts writing.
     *
     * @param dir             working directory
     * @param mode            session start mode
     * @param resumeSessionId session ID to resume (only used for RESUME_SPECIFIC)
     * @param prepared        identity prepared earlier; may be {@code null}
     * @param spawnConfigDir  config dir the process will use, or {@code null}
     *                        when it cannot be resolved
     * @return the prepared identity when it already describes
     *         {@code spawnConfigDir}, otherwise a fresh snapshot of that store
     */
    static LaunchIdentity reconcileLaunchIdentity(java.io.File dir, SessionMode mode,
                                                  String resumeSessionId,
                                                  LaunchIdentity prepared,
                                                  java.nio.file.Path spawnConfigDir) {
        if (spawnConfigDir == null) return prepared;
        if (prepared != null && prepared.configDir() != null
                && prepared.configDir().toAbsolutePath().normalize()
                        .equals(spawnConfigDir.toAbsolutePath().normalize())) {
            return prepared;
        }
        return prepareLaunchIdentity(dir, spawnConfigDir, mode, resumeSessionId);
    }

    /**
     * Starts the PTY process using a session identity prepared earlier with
     * {@link #prepareLaunchIdentity(java.io.File, String, SessionMode, String)}.
     *
     * @param dir             working directory
     * @param profileName     profile name, or {@code null}/{@code ""} for Default
     * @param extraCliArgs    extra CLI arguments (may be blank)
     * @param mode            session start mode
     * @param resumeSessionId session ID to resume (only used for RESUME_SPECIFIC)
     * @param identity        launch-time identity, captured before the process starts
     * @param widget          terminal widget
     * @throws IOException if the PTY process cannot be started
     */
    public void startProcess(java.io.File dir, String profileName, String extraCliArgs,
                             SessionMode mode, String resumeSessionId,
                             LaunchIdentity identity, JediTermWidget widget) throws IOException {
        String tag = "[" + dir.getName() + "] ";
        workingDir = dir;
        long generation;
        synchronized (processOwnershipLock) {
            claudeProcess = new ClaudeProcess();
            generation = ++processGeneration;
        }
        ClaudeProfile profile = ClaudeProfileStore.findByName(profileName);
        customModelIds = profile != null ? new ArrayList<>(profile.getComboModelIds()) : List.of();

        // The session identity was captured before launch so that stopping/renaming
        // later targets the session this process will actually use (see stopAndRename).
        // The profile is re-read above for the spawn, so a profile whose storage
        // moved in Options while the background preparation scanned needs the
        // snapshot recaptured in the store the process is about to write.
        SessionMode effectiveMode = mode != null ? mode : SessionMode.NEW;
        LaunchIdentity launch = reconcileLaunchIdentity(dir, effectiveMode, resumeSessionId,
                identity,
                ClaudeProcess.resolveConfigDir(profile, ClaudeCodePreferences.getProfilesDir()));
        preStartSessionIds = launch.preStartSessionIds();
        activeSessionId = launch.sessionId();
        launchIdentityKnown = activeSessionId != null;
        launchSessionId = activeSessionId;
        ownSessionId = null;

        PtyProcess process = claudeProcess.start(dir.getAbsolutePath(), profile,
                extraCliArgs != null ? extraCliArgs : "",
                effectiveMode, resumeSessionId);

        activeConfigDir = claudeProcess.getConfigDir();
        if (activeConfigDir == null) {
            activeConfigDir = launch.configDir();
        }
        processStartedAt = System.currentTimeMillis();
        firstOutputReceived = false;

        LIVE_SESSIONS.add(this);
        claimSession(dir, activeConfigDir, activeSessionId);

        connector = new PtyTtyConnector(process);
        connector.setSessionTag(tag);
        // Terminal input is forwarded and recorded under the same lock the
        // plugin holds while it writes the GUI prompt's Enter, so a write
        // cannot reach the child before that Enter while being recorded after
        // the recorder was reset for it.
        connector.setInputLock(terminalInputLine);
        connector.setInputListener((bytes, sentAt) -> {
            if (Boolean.TRUE.equals(pluginPtyWrite.get())) return;
            onTerminalInput(bytes, sentAt);
        });
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
    /** Returns the last command attempted to start the process, or {@code null} if none was attempted. */
    public String getLastAttemptedCommand() {
        ClaudeProcess p = claudeProcess;
        return p != null ? p.getLastCommand() : null;
    }

    /**
     * Returns the UUID of the active OpenAI-compatible proxy session, or
     * {@code null} if the current session isn't using the OpenAI-compatible or
     * ChatGPT Subscription connection type. See {@link io.github.nbplugins.claudecodegui.process.ClaudeProcess#getOpenAIProxyUuid()}.
     *
     * <p>Returns {@code null} when no process exists (before a launch completes
     * or after a stop), so UI callbacks such as the context menu can query it
     * at any time.
     */
    public String getOpenAIProxyUuid() {
        ClaudeProcess p = claudeProcess;
        return p != null ? p.getOpenAIProxyUuid() : null;
    }

    /**
     * Returns whether a prompt can be written to the session right now.
     *
     * <p>{@link #sendPrompt(String)} silently drops the text when no PTY
     * connector exists yet (the launch is prepared in the background), so the
     * input panel asks this before clearing the field.
     */
    public boolean canSendPrompt() { return connector != null; }

    /**
     * Kills the PTY process and detaches the terminal, but keeps the identity
     * inputs so the conversation that was on screen can still be resolved
     * after the writer is gone.
     */
    void stopWriter() {
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
            processToStop.killOnly();
        }
        connector = null;
    }

    public void stopProcess() {
        stopWriter();
        ClaudeProcess processToStop = claudeProcess;
        if (processToStop != null) {
            processToStop.stop();
        }
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
            launchIdentityKnown = false;
            launchSessionId = null;
            ownSessionId = null;
            promptSubmittedAt = 0;
            lastPromptText = null;
            externalInputAt = 0;
            conversationSwitchAt = 0;
            resetTerminalInputRecording();
            identityPollTicks = 0;
            LIVE_SESSIONS.remove(this);
            releaseAllClaims();
            preservedClaims = Set.of();
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
        boolean rename = sessionName != null && !sessionName.isBlank();
        java.io.File dir = model.getWorkingDirectory();
        java.nio.file.Path configDir = activeConfigDir;
        // Resolving the identity enumerates the session store, which can take
        // long on projects with thousands of conversations. The prompt panel's
        // "New session" action stops with a blank name; no rename will be
        // attempted, so that work is skipped before the PTY is stopped.
        String sessionId = null;
        if (rename) {
            sessionId = resolveActiveSessionId();
            // The writer may still be finishing a turn, and the user may have
            // switched the conversation in the TUI with the new file not yet
            // written. Stop it first, then resolve once more with the same
            // evidence rules: whatever it wrote last is on disk now. The exit
            // callback releases this tab's claims, so the conversations it
            // established or guessed as its own are carried over explicitly.
            // A borrowed conversation is another live tab's file and stays
            // eligible to that tab instead.
            Set<String> held = claimedSessionIds(dir, configDir);
            stopWriter();
            sessionId = resolveIdentityWithPreservedClaims(held);
        }
        stopProcess();

        if (!rename) return;
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
     * Resolves the identity of a tab whose writer has just been stopped, while
     * keeping the conversations it held before the stop eligible.
     *
     * <p>The post-stop lookup must use the pre-stop ownership evidence: once
     * the exit callback has released this tab's claims, a conversation the tab
     * established would otherwise look like another live tab's file, and the
     * lookup could fall back to an unrelated conversation that merely received
     * a user record after the submission.
     *
     * @param heldIds session IDs this tab held before the writer was stopped
     * @return the resolved session ID, or {@code null}
     */
    String resolveIdentityWithPreservedClaims(Set<String> heldIds) {
        Set<String> previous = preservedClaims;
        preservedClaims = heldIds != null ? Set.copyOf(heldIds) : Set.of();
        try {
            return resolveActiveSessionId();
        } finally {
            preservedClaims = previous;
        }
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
        long externalAt;
        long switchAt;
        String promptText;
        String activeId;
        Set<String> exclude = new HashSet<>();
        Set<String> claimed = new HashSet<>();
        Set<String> otherLiveClaims = new HashSet<>();
        Set<String> otherOwned = new HashSet<>();
        synchronized (processOwnershipLock) {
            generation = processGeneration;
            scanId = ++scanSequence;
            dir = workingDir != null ? workingDir : model.getWorkingDirectory();
            configDir = activeConfigDir;
            since = Math.max(processStartedAt, promptSubmittedAt);
            promptAt = promptSubmittedAt;
            externalAt = externalInputAt;
            switchAt = conversationSwitchAt;
            promptText = lastPromptText;
            activeId = activeSessionId;
            if (dir != null && configDir != null && since > 0) {
                claimed.addAll(claimedByOthers(dir, configDir));
                otherLiveClaims.addAll(claimedByOtherLiveTabs(dir, configDir));
                otherOwned.addAll(sessionsOwnedByOtherLiveTabs(dir, configDir));
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
        boolean ownEvidence = false;
        if (dir != null && configDir != null && since > 0) {
            // A conversation owned by a live tab (or by this tab) that already
            // contains the prompt submitted from here is the strongest evidence
            // of an in-process /resume: the process is writing that file. It
            // must win over a merely newer unclaimed file — e.g. a third tab
            // that wrote its own conversation after the prompt and then exited.
            //
            // Text typed in the terminal after that prompt may have switched the
            // conversation through the TUI, in which case the old prompt is no
            // longer evidence of what is on screen: fall back to file times.
            // A line submitted from the terminal itself counts as the latest
            // prompt (same millisecond), so a resume driven entirely from the
            // keyboard is still followed.
            // The tab's own evidence excludes every conversation another live
            // tab established: such a file is not this tab's own, even when it
            // is the identity currently tracked (a false adoption). A merely
            // borrowed or provisionally claimed file does not count: a sibling
            // following this tab's conversation must not cost this tab the
            // session it established.
            Set<String> ownExclude = new HashSet<>(exclude);
            ownExclude.addAll(otherOwned);
            // The launch conversation is this tab's own even when another live
            // tab established it (both resumed the same saved file): a
            // temporary adoption of a sibling's conversation must not make the
            // launch file ineligible, or the sibling keeps winning the search.
            if (launchIdentityKnown && launchSessionId != null) {
                ownExclude.remove(launchSessionId);
            }
            if (promptAt > 0 && promptAt >= externalAt) {
                // First look for the tab's own conversation: a file nobody else
                // claims that already holds this prompt. It must win over a
                // sibling's file with the same text, because a NEW tab's own
                // file appears a moment after the sibling that submitted the
                // same prompt — adopting the sibling then would pin this tab to
                // another tab's conversation.
                // A session another live tab established is not this tab's own,
                // even when it is the identity currently tracked (a false
                // adoption). A merely borrowed or provisionally claimed file
                // does not count: a sibling following this tab's conversation
                // must not cost this tab the session it established.
                //
                // When the tracked file bears this tab's own prompt and no
                // other tab established it, the tab keeps it: with two
                // unresolved NEW tabs submitting identical text, the evidence
                // is indistinguishable, and displacing the current claim would
                // merely swap the two conversations.
                //
                // A launch-known identity (--continue/--resume) is this tab's
                // own conversation even when another live tab shares the same
                // saved file (both resumed it, so one of them only borrows it):
                // it must stay preferred, or an unrelated file carrying the
                // same prompt text would be adopted in its place.
                //
                // That exception belongs to the launch ID only. An identity
                // adopted later (a sibling's conversation followed while both
                // tabs submitted identical text) is the sibling's, and keeping
                // it preferred would pin this tab to it even after its own
                // launch conversation holds the prompt and is strictly newer.
                //
                // The file this run resolved for its own submission is
                // preferred over whichever identity is currently tracked: a
                // borrowed sibling file that matched the identical prompt
                // before this tab's record was flushed must not keep winning
                // once this tab's own file holds the prompt. Only a file no
                // other live tab established and this tab does not merely
                // follow counts: an explicit resume of the same file by
                // another tab owns it.
                //
                // The tracked identity is subject to the same rule only when
                // this tab knows the conversation it launched on: two
                // unresolved tabs that submitted identical text legitimately
                // keep the file one of them owns provisionally, but a
                // launch-known tab has its own conversation to prefer over a
                // provisional file it merely borrows.
                //
                // A launch-known tab's tracked non-launch identity is a guess
                // made from matching prompt text: the sibling that actually
                // writes that file may not have claimed it yet. When the
                // launch conversation itself holds the submission and is
                // strictly newer, it is the stronger evidence — the
                // submission went there, not to the file this tab merely
                // followed — so the guess yields.
                boolean launchOutranksOwn = launchIdentityKnown && launchSessionId != null
                        && ownSessionId != null && !ownSessionId.equals(launchSessionId)
                        && promptText != null && !promptText.isBlank()
                        && ClaudeSessionStore.sessionFileModified(dir.toPath(), configDir,
                                launchSessionId)
                           > ClaudeSessionStore.sessionFileModified(dir.toPath(), configDir,
                                ownSessionId)
                        && containsPrompt(dir, configDir, launchSessionId, promptAt, promptText);
                boolean isActiveYielding = isSharedGuess(activeId, otherLiveClaims)
                        || (launchIdentityKnown && launchSessionId != null
                                && activeId != null && !activeId.equals(launchSessionId)
                                && (!activeId.equals(ownSessionId) || launchOutranksOwn));
                String ownPreferred = null;
                if (ownSessionId != null && !launchOutranksOwn
                        && !otherOwned.contains(ownSessionId)
                        && !isSharedGuess(ownSessionId, otherLiveClaims)
                        && !isBorrowedByThisTab(dir, configDir, ownSessionId)) {
                    ownPreferred = ownSessionId;
                } else if (activeId != null && !isActiveYielding
                        && (activeId.equals(launchSessionId)
                                || !otherOwned.contains(activeId))) {
                    ownPreferred = activeId;
                } else if (launchIdentityKnown && launchSessionId != null) {
                    ownPreferred = launchSessionId;
                }
                newest = findPromptBearingSession(dir, configDir, since, ownExclude,
                        ownPreferred, promptAt, promptText);
                if (newest == null && since > SESSION_MTIME_GRANULARITY_MS) {
                    // Coarse file times can round this tab's own file below the
                    // cutoff even though it already holds the prompt. Its record
                    // still proves the conversation, so retry with the slack
                    // before yielding to a sibling's file that merely carries
                    // the same text.
                    newest = findPromptBearingSession(dir, configDir,
                            since - SESSION_MTIME_GRANULARITY_MS, ownExclude,
                            ownPreferred, promptAt, promptText);
                }
                // A match in a file this tab only follows is not proof that
                // this run created it: it is another live tab's conversation,
                // matched because both tabs submitted the same text.
                boolean ownFileMatch = newest != null
                        && !isSharedGuess(newest, otherLiveClaims)
                        && !isBorrowedByThisTab(dir, configDir, newest);
                if (newest != null && (externalAt > 0 || switchAt > 0)
                        && otherLiveClaims.contains(newest)) {
                    // A conversation another live tab also writes is not proof
                    // that this tab's submission went there: that tab's own turn
                    // can submit the same text. When the user has typed in the
                    // terminal, the TUI may have switched the conversation, and
                    // a file holding the same text and written strictly later
                    // — including one no live tab holds yet, e.g. a saved
                    // conversation resumed with the picker — is then the one on
                    // screen. Every file written since the submission competes
                    // by recency instead of the shared match ending the search.
                    // A switch made with a resume command — sent from the
                    // prompt panel, typed in the terminal, or answered in the
                    // picker — is such a switch as well.
                    // Without either, the launch conversation stays conclusive.
                    String newer = findPromptBearingSession(dir, configDir, since,
                            Set.of(), null, promptAt, promptText);
                    if (newer == null && since > SESSION_MTIME_GRANULARITY_MS) {
                        newer = findPromptBearingSession(dir, configDir,
                                since - SESSION_MTIME_GRANULARITY_MS, Set.of(), null,
                                promptAt, promptText);
                    }
                    if (newer != null && !newer.equals(newest)) {
                        newest = newer;
                        // A conversation written later by someone else is not
                        // the file this run created for its own submission.
                        ownFileMatch = false;
                    }
                }
                if (ownFileMatch) {
                    // The own-prompt evidence found this run's conversation:
                    // a NEW run has created the file it writes to.
                    ownEvidence = true;
                }
                if (newest == null) {
                    // Only then may a conversation another live tab writes be
                    // adopted: that is an in-process /resume from this terminal.
                    // Order the candidates newest first: several live files can
                    // hold the same text, and the one written last is the
                    // conversation the process is on — an older sibling that
                    // submitted the same prompt must not win.
                    Set<String> borrowed = new HashSet<>(claimed);
                    borrowed.addAll(otherLiveClaims);
                    if (activeId != null) borrowed.add(activeId);
                    Set<String> ordered = new LinkedHashSet<>(
                            ClaudeSessionStore.listSessionIdsWrittenSince(
                                    dir.toPath(), configDir, 0L, null, borrowed,
                                    borrowed.size()));
                    // The tracked conversation is preferred here only when it is
                    // the launch identity. A borrowed identity was itself
                    // guessed from the same prompt text, so keeping it
                    // preferred would pin this tab to a sibling's file even
                    // after the conversation it actually joined holds the
                    // submission and is strictly newer.
                    String borrowedPreferred = activeId != null
                            && activeId.equals(launchSessionId) ? activeId : null;
                    newest = findPromptBearingSession(dir, configDir, ordered,
                            borrowedPreferred, promptAt, promptText);
                }
            }
            if (promptAt > 0 && promptAt >= externalAt
                    && (promptText == null || promptText.isBlank())) {
                // A prompt recalled from the TUI history or completed by Tab:
                // its text is unknown, so no file can match it as evidence. The
                // user record is what proves a file was written for this
                // submission.
                //
                // The unclaimed-record priority belongs to a run that has not
                // created a file of its own yet: it may write its first file
                // without any other tab claiming it, while a sibling's record
                // submitted around the same time may just be that tab's own
                // turn. A tab whose conversation was known at launch had
                // nothing new to create, so an unrelated unclaimed human record
                // (e.g. an exited third tab's own prompt) must not displace the
                // shared conversation it switched into. Once a run has resolved
                // its own file, the priority is limited to the conversations
                // this tab holds: a later submission cannot be the one creating
                // the first file, so an unrelated unclaimed record must yield
                // to the shared conversation the run joined.
                //
                // A launch-known run is treated the same way once it has
                // switched to another conversation and resolved it: a later
                // recalled submission must keep that conversation eligible, or
                // the sibling file it temporarily follows is renamed in its
                // place.
                //
                // Its launch conversation is different: a user record appended
                // to that file after the submission suggests the submission
                // went there, so the shared search below keeps it eligible. It
                // must not be conclusive on its own, though: when another live
                // tab launched the same saved file, its own turn also appends a
                // human record there, and a TUI resume into a newer conversation
                // must still win.
                boolean ownRecordMatch = false;
                if (newest == null && (!launchIdentityKnown || ownSessionId != null)) {
                    // A record is not proof of ownership: a sibling's file the
                    // tab borrowed earlier is now held by this tab too, so the
                    // claim-based exclusion no longer hides it. Only a file no
                    // other live tab established may count as this tab's own.
                    Set<String> ownCandidates = null;
                    if (ownSessionId != null) {
                        // The run has already created its own file, so a later
                        // unknown-text submission cannot be the one that
                        // creates it. Only this tab's own conversations keep
                        // the priority: an unrelated unclaimed file (e.g. an
                        // exited tab's record) must yield to the shared
                        // conversation the run joined. The resolved own ID is
                        // listed explicitly: adopting a borrowed conversation
                        // releases its claim, and the file must still be
                        // eligible for the submission that went there.
                        ownCandidates = claimedSessionIds(dir, configDir);
                        ownCandidates.add(ownSessionId);
                        // A file another live tab also writes is not proof of
                        // this tab's submission: that tab's concurrent turn
                        // appends a human record of its own. It competes with
                        // the other shared candidates below by recency.
                        ownCandidates.removeAll(otherLiveClaims);
                    }
                    if (ownCandidates == null) {
                        newest = newestSessionWithUserMessageSince(dir, configDir, since,
                                promptAt, ownExclude);
                        if (newest == null && since > SESSION_MTIME_GRANULARITY_MS) {
                            newest = newestSessionWithUserMessageSince(dir, configDir,
                                    since - SESSION_MTIME_GRANULARITY_MS, promptAt,
                                    ownExclude);
                        }
                        ownEvidence = newest != null;
                        ownRecordMatch = newest != null;
                    } else if (!ownCandidates.isEmpty()) {
                        newest = newestSharedSessionWrittenSince(dir, configDir, since,
                                promptAt, ownCandidates, exclude);
                        if (newest == null && since > SESSION_MTIME_GRANULARITY_MS) {
                            newest = newestSharedSessionWrittenSince(dir, configDir,
                                    since - SESSION_MTIME_GRANULARITY_MS, promptAt,
                                    ownCandidates, exclude);
                        }
                        ownRecordMatch = newest != null;
                    }
                }
                if (newest == null) {
                    // Only then may a conversation another live tab holds be
                    // adopted: that is an in-process /resume from this terminal.
                    // This must run before the file-time fallback: a third tab
                    // that wrote its own conversation after the submission and
                    // then exited is unclaimed, and its newer mtime would
                    // otherwise hide the resumed conversation.
                    //
                    // The launch conversation is a candidate too, even when
                    // another live tab holds it: this tab's submission may have
                    // gone there. The store's newest-first order then decides
                    // between it and a conversation the tab resumed into, so a
                    // shared launch file whose record came from the other live
                    // tab does not win merely by being the launch file.
                    Set<String> shared = new HashSet<>(claimed);
                    shared.addAll(otherLiveClaims);
                    if (activeId != null) shared.add(activeId);
                    if (launchIdentityKnown && launchSessionId != null) {
                        shared.add(launchSessionId);
                    }
                    newest = newestSharedSessionWrittenSince(dir, configDir, since,
                            promptAt, shared, exclude);
                    if (newest == null && since > SESSION_MTIME_GRANULARITY_MS) {
                        // Coarse file times can round the write below the submission;
                        // the record's own timestamp still has to match.
                        newest = newestSharedSessionWrittenSince(dir, configDir,
                                since - SESSION_MTIME_GRANULARITY_MS, promptAt, shared, exclude);
                    }
                }
                if (switchAt > 0 && !ownRecordMatch) {
                    // The conversation was switched with a resume command —
                    // sent from the prompt panel, typed in the terminal or
                    // answered in the picker — and the destination may be a
                    // saved conversation no live tab holds, which the shared
                    // pass above cannot see. A matching record in a shared
                    // file is not conclusive either: a sibling that submitted
                    // the same recalled text writes one too. A record in a
                    // conversation nobody holds can only have come from this
                    // process, so the files another live tab holds are left
                    // out.
                    //
                    // A conversation this pass already established as this
                    // run's own keeps the submission: the switch was followed,
                    // and a sibling that merely appends a later record must
                    // not take the tab over again.
                    Set<String> unheld = new HashSet<>(exclude);
                    unheld.addAll(otherLiveClaims);
                    String switched = newestSessionWithUserMessageSince(dir, configDir,
                            since, promptAt, unheld);
                    if (switched == null && since > SESSION_MTIME_GRANULARITY_MS) {
                        switched = newestSessionWithUserMessageSince(dir, configDir,
                                since - SESSION_MTIME_GRANULARITY_MS, promptAt, unheld);
                    }
                    // Unheld status alone does not prove this process wrote the
                    // file: a tab that submitted its own prompt and then
                    // exited leaves one behind. Only a file written strictly
                    // later than the shared match is the conversation on
                    // screen; otherwise the shared match stands.
                    if (switched != null && (newest == null
                            || ClaudeSessionStore.sessionFileModified(dir.toPath(),
                                    configDir, switched)
                               > ClaudeSessionStore.sessionFileModified(dir.toPath(),
                                    configDir, newest))) {
                        newest = switched;
                        // No other live tab holds the switched-to conversation,
                        // so the record in it can only have come from this
                        // process: it is the file this run resumed into. It
                        // must be remembered as the run's own, or the next
                        // unknown-text submission skips the own-candidate pass
                        // (which requires a resolved own ID) and a sibling's
                        // newer record displaces it.
                        ownEvidence = true;
                    }
                }
            }
            if (newest == null) {
                newest = ClaudeSessionStore.findLatestSessionIdWrittenSince(
                        dir.toPath(), configDir, since, exclude);
            }
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
        }
        return publishIdentityScanResult(generation, scanId, dir, configDir, newest,
                ownEvidence);
    }

    /**
     * Returns the session among {@code candidates} whose JSONL already contains
     * the prompt this tab submitted after {@code promptAt}, or {@code null}.
     *
     * <p>The message proves that the running process has joined that
     * conversation, so it is preferred over an mtime-only guess: a sibling tab
     * finishing its own turn also writes a file after the prompt, but that file
     * holds that tab's messages, not this tab's prompt.
     *
     * <p>The session this tab already tracks is checked first: two tabs can
     * submit the same prompt ({@code continue}), and then the sibling's file
     * matches as well — picking whichever comes first in the set would replace
     * a correct identity with a borrowed one.
     *
     * @param preferred session ID to check first; may be {@code null}
     */
    /**
     * Returns the session among the files written at or after
     * {@code mtimeCutoff}, skipping {@code exclude}, whose JSONL already
     * contains {@code promptText} since {@code promptAt}, or {@code null}.
     *
     * <p>The candidates keep the store's newest-first order: when several
     * unclaimed files hold the same text, the first match must be the one
     * written most recently (this tab's own file), not an older conversation
     * another tab submitted the same prompt to and then left.
     *
     * @param preferred session ID to check first; may be {@code null}
     */
    private String findPromptBearingSession(java.io.File dir, java.nio.file.Path configDir,
                                            long mtimeCutoff, Set<String> exclude,
                                            String preferred, long promptAt, String promptText) {
        Set<String> candidates = new LinkedHashSet<>(
                ClaudeSessionStore.listSessionIdsWrittenSince(
                        dir.toPath(), configDir, mtimeCutoff, exclude,
                        PROMPT_EVIDENCE_CANDIDATES));
        return findPromptBearingSession(dir, configDir, candidates, preferred, promptAt,
                promptText);
    }

    private String findPromptBearingSession(java.io.File dir, java.nio.file.Path configDir,
                                            Set<String> candidates, String preferred,
                                            long promptAt, String promptText) {
        if (candidates.isEmpty() || promptText == null || promptText.isBlank()) return null;
        // The preferred session may have been excluded from the candidates
        // (e.g. it is claimed by another live tab): it is checked first only
        // while it is still eligible. Returning it otherwise would rename that
        // tab's conversation instead of the one this run actually holds.
        if (preferred != null && candidates.contains(preferred)
                && containsPrompt(dir, configDir, preferred, promptAt, promptText)) {
            return preferred;
        }
        for (String sessionId : candidates) {
            if (!sessionId.equals(preferred)
                    && containsPrompt(dir, configDir, sessionId, promptAt, promptText)) {
                return sessionId;
            }
        }
        return null;
    }

    /**
     * Returns the newest session file written at or after {@code mtimeCutoff},
     * skipping {@code exclude}, that holds a user message at or after
     * {@code promptCutoff}, or {@code null}.
     *
     * <p>Used when the submitted prompt's text is unknown: a user record is
     * then the only evidence that a file was written for this submission, and
     * unlike {@link #newestSharedSessionWrittenSince} the file does not have to
     * belong to another live tab.
     */
    private static String newestSessionWithUserMessageSince(java.io.File dir,
                                                            java.nio.file.Path configDir,
                                                            long mtimeCutoff,
                                                            long promptCutoff,
                                                            Set<String> exclude) {
        for (String id : ClaudeSessionStore.listSessionIdsWrittenSince(
                dir.toPath(), configDir, mtimeCutoff, exclude, PROMPT_EVIDENCE_CANDIDATES)) {
            if (ClaudeSessionStore.containsUserMessageSince(
                    dir.toPath(), configDir, id, promptCutoff)) {
                return id;
            }
        }
        return null;
    }

    /**
     * Returns the newest session ID in {@code shared} whose file was written at
     * or after {@code mtimeCutoff} and holds a user message at or after
     * {@code promptCutoff}, or {@code null}.
     *
     * <p>Used when the submitted prompt's text is unknown (a history recall):
     * only a conversation another live tab holds can be the one this process
     * switched into, so unrelated newer files must not be adopted. The user
     * record is required because the other tab may simply be finishing its own
     * turn, which appends assistant output but no user message.
     */
    private static String newestSharedSessionWrittenSince(java.io.File dir,
                                                          java.nio.file.Path configDir,
                                                          long mtimeCutoff,
                                                          long promptCutoff,
                                                          Set<String> shared,
                                                          Set<String> exclude) {
        if (shared.isEmpty()) return null;
        Set<String> sharedExclude = new HashSet<>(exclude);
        sharedExclude.removeAll(shared);
        // Only the shared conversations may be considered: newer unrelated
        // files must not fill the limited candidate list and hide the
        // destination that holds this tab's submission.
        for (String id : ClaudeSessionStore.listSessionIdsWrittenSince(
                dir.toPath(), configDir, mtimeCutoff, sharedExclude, shared,
                PROMPT_EVIDENCE_CANDIDATES)) {
            if (ClaudeSessionStore.containsUserMessageSince(
                    dir.toPath(), configDir, id, promptCutoff)) {
                return id;
            }
        }
        return null;
    }

    /** Returns whether {@code sessionId} holds {@code promptText} since {@code promptAt}. */
    private static boolean containsPrompt(java.io.File dir, java.nio.file.Path configDir,
                                          String sessionId, long promptAt, String promptText) {
        return ClaudeSessionStore.containsUserPromptSince(
                dir.toPath(), configDir, sessionId, promptAt, promptText);
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
                                              String newest, boolean ownEvidence) {
        synchronized (processOwnershipLock) {
            if (generation != processGeneration) {
                return activeSessionId != null;
            }
            if (scanId < lastPublishedScanId) {
                return activeSessionId != null;
            }
            lastPublishedScanId = scanId;
            if (ownEvidence && newest != null) {
                ownSessionId = newest;
            }
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
                null, activeConfigDir, newest, false);
    }

    /** Package-private for tests: publishes a scan result with explicit ordering. */
    boolean simulatePublishScanResult(long generation, long scanId, String newest) {
        return publishIdentityScanResult(generation, scanId, null, activeConfigDir, newest,
                false);
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
     *
     * <p>Nothing is queued once the PTY has exited: the tab can stay open after
     * Claude quits, and a dead process has no conversation to follow. Without
     * this gate each such tab would keep enumerating the session store twice a
     * second (unresolved session) or every five seconds on the single shared
     * worker, delaying live tabs.
     *
     * @return {@code false} when no PTY is running and nothing was queued
     */
    boolean scheduleIdentityRefresh() {
        if (!isProcessLive()) return false;
        if (!identityScanPending.compareAndSet(false, true)) return true;
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
        return true;
    }

    /**
     * Forgets everything recorded about the terminal's input line: the text
     * typed so far, the cursor, a partial escape sequence and the history-recall
     * flags. Called when the plugin itself drives the TUI (a picker selection)
     * or when a run ends, so stale text never becomes prompt evidence.
     */
    private void resetTerminalInputRecording() {
        synchronized (terminalInputLine) {
            terminalInputLine.setLength(0);
            terminalInputCursor = 0;
            terminalInputEscape = 0;
            terminalEscapeParams.setLength(0);
            terminalInputPaste = false;
            terminalPasteAfterCR = false;
            terminalEscapeOnMenu = false;
            terminalMenuSelection = false;
            terminalLineEntered = false;
            terminalEnterOnEmptyLine = false;
            terminalHistoryRecall = false;
            terminalCompletionEdited = false;
            terminalRecalledSubmission = false;
            terminalInputSinceGuiSend = false;
            terminalEnterSinceGuiSend = false;
            guiSendAwaitingEnter = false;
            terminalCompletedMenu = null;
        }
    }

    /**
     * Discards the recorded input after a standalone {@code ESC} cancelled a
     * choice menu: the line then holds the picker's search text, not a prompt
     * draft. Call while holding the {@link #terminalInputLine} lock.
     */
    private void discardPickerSearchText() {
        terminalInputLine.setLength(0);
        terminalInputCursor = 0;
        terminalEscapeOnMenu = false;
        // The cancelled menu stays in the model until the next poll: retire
        // it, or input typed right after the cancellation is still treated as
        // a menu answer.
        completeTerminalMenu(openChoiceMenu());
    }

    /**
     * Returns the choice menu still open on the screen, or {@code null} when
     * the terminal input already completed the menu the model reports.
     *
     * <p>The model is refreshed by the screen poll every half second, so a
     * menu dismissed by a keystroke remains reported for a while. Classifying
     * the following input against it would swallow a real prompt: its digits
     * are dropped and its Enter counts as a menu selection instead of a
     * submission. Call while holding the {@link #terminalInputLine} lock.
     */
    private ChoiceMenuModel openChoiceMenu() {
        ChoiceMenuModel active = model.getActiveChoiceMenu();
        return active == terminalCompletedMenu ? null : active;
    }

    /**
     * Remembers that the terminal input has dismissed {@code menu}, so the
     * stale model entry no longer classifies the following keystrokes. Call
     * while holding the {@link #terminalInputLine} lock.
     */
    private void completeTerminalMenu(ChoiceMenuModel menu) {
        if (menu != null) terminalCompletedMenu = menu;
    }

    /**
     * Returns whether {@code menu} is a checkbox menu, which submits on Right
     * (the answer protocol written by the menu panel) instead of a numbered
     * picker, where Right only moves the cursor inside the search field.
     */
    private static boolean isCheckboxMenu(ChoiceMenuModel menu) {
        for (ChoiceMenuModel.Option option : menu.options()) {
            if (option.hasCheckbox()) return true;
        }
        return false;
    }

    /**
     * Returns whether a digit typed while {@code menu} is open selects an item
     * and dismisses the menu.
     *
     * <p>Only menus whose answers are the item numbers behave that way. An
     * unnumbered picker (the {@code ARROW:} answers of the {@code /resume}
     * list) uses digits as search text and stays open, and a checkbox or
     * text-input menu only moves to an option on a digit, waiting for its
     * submit key — retiring those on the first digit would turn the search
     * text into prompt evidence.
     */
    private static boolean digitSelectionDismissesMenu(ChoiceMenuModel menu) {
        if (menu.options().isEmpty()) return false;
        for (ChoiceMenuModel.Option option : menu.options()) {
            String response = option.response();
            if (response == null || response.isEmpty()) return false;
            for (int i = 0; i < response.length(); i++) {
                char c = response.charAt(i);
                if (c < '0' || c > '9') return false;
            }
            if (option.hasCheckbox() || option.hasTextInput()) return false;
        }
        return true;
    }

    /** Package-private for tests: the PTY is no longer running (Claude exited). */
    void simulateProcessNotRunning() {
        claudeProcess = new ClaudeProcess();
    }

    /**
     * Returns whether this tab only follows {@code sessionId} written by
     * another live tab. A borrowed conversation is not this tab's own, even
     * when its file matched the own-prompt evidence before the tab's own file
     * was flushed.
     */
    private boolean isBorrowedByThisTab(java.io.File dir, java.nio.file.Path configDir,
                                        String sessionId) {
        SessionKey key = keyFor(dir, configDir, sessionId);
        SessionClaims claims = key != null ? SESSION_CLAIMS.get(key) : null;
        return claims != null && claims.borrowed.contains(this);
    }

    /**
     * Returns whether {@code sessionId} is a conversation this launch-known tab
     * can only be following: a file other than the launch identity that another
     * live tab holds as well. The claim registry marks a tab as a borrower only
     * if the other tab had already claimed the file when this tab adopted it,
     * so two tabs submitting the same text at once can both end up as
     * provisional holders; whichever claimed the file first must not let it
     * outrank the launch conversation that actually holds the prompt.
     */
    private boolean isSharedGuess(String sessionId, Set<String> otherLiveClaims) {
        return sessionId != null && launchIdentityKnown && launchSessionId != null
                && !sessionId.equals(launchSessionId)
                && otherLiveClaims.contains(sessionId);
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
        SESSION_CLAIMS.compute(key, (k, claims) -> {
            SessionClaims updated = claims != null ? claims : new SessionClaims();
            if (!updated.holders.contains(this)) {
                // Classify only a new hold: re-claiming the identity on every
                // scan must not turn an owner into a borrower of its own file.
                if (launchIdentityKnown && sessionId.equals(launchSessionId)
                        && !heldAsOwnerByAnotherLiveTab(updated)) {
                    // An explicit --continue/--resume knows the conversation it
                    // launched on, and only that one: a conversation adopted
                    // later (a sibling's file this tab started following) is
                    // not established by it and must stay borrowed, or the
                    // sibling that created it would lose it. A NEW tab's
                    // provisional guess does not establish the file either.
                    updated.owners.add(this);
                } else if (heldByAnotherLiveTab(updated)) {
                    // Another tab is already writing this conversation; this
                    // tab is only following it.
                    updated.borrowed.add(this);
                }
                // Otherwise the claim stays provisional: this NEW tab guessed
                // that the file is its own.
                updated.holders.add(this);
            }
            return updated;
        });
    }

    /** {@code true} if another live tab has established (owns) {@code claims}. */
    private boolean heldAsOwnerByAnotherLiveTab(SessionClaims claims) {
        for (ClaudeSessionController owner : claims.owners) {
            if (owner != this && LIVE_SESSIONS.contains(owner) && owner.isProcessLive()) {
                return true;
            }
        }
        return false;
    }

    /** {@code true} if another live tab already holds {@code claims}. */
    private boolean heldByAnotherLiveTab(SessionClaims claims) {
        for (ClaudeSessionController holder : claims.holders) {
            if (holder != this && LIVE_SESSIONS.contains(holder) && holder.isProcessLive()) {
                return true;
            }
        }
        return false;
    }

    private void releaseClaim(java.io.File dir, java.nio.file.Path configDir,
                              String sessionId) {
        SessionKey key = keyFor(dir, configDir, sessionId);
        if (key == null) return;
        SESSION_CLAIMS.computeIfPresent(key, (k, claims) -> {
            releaseFromClaims(claims);
            return claims.holders.isEmpty() ? null : claims;
        });
    }

    /** Removes all claims held by this controller (called when the process stops). */
    private void releaseAllClaims() {
        for (SessionKey key : SESSION_CLAIMS.keySet()) {
            SESSION_CLAIMS.computeIfPresent(key, (k, claims) -> {
                releaseFromClaims(claims);
                return claims.holders.isEmpty() ? null : claims;
            });
        }
    }

    private void releaseFromClaims(SessionClaims claims) {
        claims.holders.remove(this);
        claims.owners.remove(this);
        claims.borrowed.remove(this);
    }

    /**
     * Session IDs claimed by another live controller in the same store, even
     * when this controller claims them too. A conversation shared with another
     * tab is not this tab's own, so its own-prompt evidence must not be taken
     * from it; {@link #claimedByOthers} answers the different question of which
     * files a scan must not consider at all.
     */
    private Set<String> claimedByOtherLiveTabs(java.io.File dir, java.nio.file.Path configDir) {
        Set<String> ids = new HashSet<>();
        if (dir == null || configDir == null) return ids;
        java.nio.file.Path wantedConfig = configDir.toAbsolutePath().normalize();
        java.nio.file.Path wantedProject = dir.toPath().toAbsolutePath().normalize();
        SESSION_CLAIMS.forEach((key, claims) -> {
            if (!key.configDir().equals(wantedConfig)
                    || !key.projectDir().equals(wantedProject)) {
                return;
            }
            for (ClaudeSessionController holder : claims.holders) {
                if (holder != this && LIVE_SESSIONS.contains(holder) && holder.isProcessLive()) {
                    ids.add(key.sessionId());
                    return;
                }
            }
        });
        return ids;
    }

    /**
     * Session IDs another live controller has <em>established</em> in the same
     * store. A claim that merely follows the conversation (see
     * {@link SessionClaims#borrowed}) is not ownership and is skipped: when a
     * NEW tab borrows a live tab's conversation until its own file appears,
     * that file must still count as the live tab's own, or the live tab would
     * discard its correctly tracked session and adopt the borrower's file.
     */
    private Set<String> sessionsOwnedByOtherLiveTabs(java.io.File dir,
                                                     java.nio.file.Path configDir) {
        Set<String> ids = new HashSet<>();
        if (dir == null || configDir == null) return ids;
        java.nio.file.Path wantedConfig = configDir.toAbsolutePath().normalize();
        java.nio.file.Path wantedProject = dir.toPath().toAbsolutePath().normalize();
        SESSION_CLAIMS.forEach((key, claims) -> {
            if (!key.configDir().equals(wantedConfig)
                    || !key.projectDir().equals(wantedProject)) {
                return;
            }
            // A file this tab held until it stopped its writer is its own, so
            // the post-stop lookup must not treat it as another tab's.
            if (preservedClaims.contains(key.sessionId())) return;
            for (ClaudeSessionController owner : claims.owners) {
                if (owner != this && LIVE_SESSIONS.contains(owner) && owner.isProcessLive()) {
                    ids.add(key.sessionId());
                    return;
                }
            }
        });
        return ids;
    }

    /**
     * Package-private for tests and the stop path: session IDs this controller
     * established or guessed as its own in the given store.
     *
     * <p>A held conversation that this controller only borrowed (another live
     * tab established it or was writing it first) is another tab's file and is
     * not included: preserving it as this tab's own would let it win the final
     * post-stop lookup over the file this tab actually created.
     */
    Set<String> claimedSessionIds(java.io.File dir, java.nio.file.Path configDir) {
        Set<String> ids = new HashSet<>();
        if (dir == null || configDir == null) return ids;
        java.nio.file.Path wantedConfig = configDir.toAbsolutePath().normalize();
        java.nio.file.Path wantedProject = dir.toPath().toAbsolutePath().normalize();
        SESSION_CLAIMS.forEach((key, claims) -> {
            if (key.configDir().equals(wantedConfig)
                    && key.projectDir().equals(wantedProject)
                    && claims.holders.contains(this)
                    && !claims.borrowed.contains(this)) {
                ids.add(key.sessionId());
            }
        });
        return ids;
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
        SESSION_CLAIMS.forEach((key, claims) -> {
            if (!key.configDir().equals(wantedConfig)
                    || !key.projectDir().equals(wantedProject)) {
                return;
            }
            // A session shared with another tab (both resumed the same saved
            // conversation) is still this tab's own conversation: excluding it
            // would make the scan pick an unrelated file. The same holds for a
            // file this tab held before it stopped its writer: the exit
            // callback has already released the claim, but that must not turn
            // the tab's own conversation into another tab's file.
            if (claims.holders.contains(this)
                    || preservedClaims.contains(key.sessionId())) {
                return;
            }
            for (ClaudeSessionController holder : claims.holders) {
                if (holder != this && LIVE_SESSIONS.contains(holder) && holder.isProcessLive()
                        && !followsOnly(holder, key.sessionId(), claims)) {
                    ids.add(key.sessionId());
                    return;
                }
            }
        });
        return ids;
    }

    /**
     * Returns whether {@code holder} only follows {@code sessionId}: it is not
     * one of the conversation's owners and the conversation is not the one it
     * launched on. Such a claim is a guess made from matching prompt text —
     * e.g. a launch-known tab that follows a sibling's file while its own
     * conversation has not been flushed yet — and must not hide that file from
     * the scan of the tab whose process actually writes it, or the creator
     * adopts another conversation and loses its own.
     */
    private static boolean followsOnly(ClaudeSessionController holder, String sessionId,
                                       SessionClaims claims) {
        return !claims.owners.contains(holder)
                && holder.launchIdentityKnown
                && !sessionId.equals(holder.launchSessionId);
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

        Thread t = new Thread(() -> {
            try {
                // Reconciliation and the text write run as one step under the
                // recorder's lock: an Enter arriving in between would submit
                // the terminal draft alone, while the evidence recorded for
                // the send would claim the composed line was submitted.
                writeGuiPrompt(text, System.currentTimeMillis());
                Thread.sleep(200);
            } catch (IOException ex) {
                LOG.warning("sendPrompt write failed: " + ex.getMessage());
                resetTerminalInputRecording();
                return;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                resetTerminalInputRecording();
                return;
            }
            completeGuiSend();
        }, "pty-send-prompt");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Records a prompt sent from the prompt panel and writes its text to the
     * PTY as one step, atomic with respect to terminal input (see
     * {@link #reconcileGuiSend(String, long)}).
     *
     * <p>Only the panel text is written: the terminal's own draft is still in
     * the TUI's input line, so it is already part of the line the Enter
     * submits. Writing the composed line would duplicate it.
     */
    private void writeGuiPrompt(String text, long sentAt) throws IOException {
        synchronized (terminalInputLine) {
            reconcileGuiSend(text, sentAt);
            writePty(text);
        }
    }

    /**
     * Records a prompt sent from the prompt panel as the evidence the identity
     * scan matches, composing it with a draft still open in the terminal.
     *
     * <p>The plugin writes the prompt at the TUI's cursor, so a draft typed
     * there is part of the line the conversation receives. That draft is
     * recorded as part of the evidence and then forgotten: the panel's
     * submission consumed the terminal line, so it must not prefix the next
     * line typed in the terminal either.
     *
     * @param text   prompt sent from the prompt panel
     * @param sentAt submission time
     * @return the line the conversation receives
     */
    private String reconcileGuiSend(String text, long sentAt) {
        String submitted;
        boolean isTextExact;
        synchronized (terminalInputLine) {
            if (terminalInputEscape == 1 && terminalEscapeOnMenu) {
                // A standalone Esc cancelled the picker and nothing has been
                // typed since to consume it: the GUI send is the next input,
                // and the picker's search text must not become part of the
                // submitted line.
                discardPickerSearchText();
                terminalInputEscape = 0;
            }
            String draft = terminalInputLine.toString();
            int cursor = Math.min(terminalInputCursor, draft.length());
            submitted = draft.substring(0, cursor) + text + draft.substring(cursor);
            // A history recall or a Tab completion leaves only a part of the
            // line in the recorder: composing it would publish text the
            // conversation never received, and the exact-match search would
            // miss the real prompt.
            isTextExact = !terminalHistoryRecall && !terminalCompletionEdited;
            // Reset under the same lock: a raw terminal write interleaved
            // between the snapshot and the reset would reach the TUI after
            // `submitted` was computed, yet its recording would be deleted,
            // leaving evidence that omits characters the conversation got.
            resetTerminalInputRecording();
            // The panel text stays on the TUI's input line until
            // completeGuiSend() sends its Enter. Until then a terminal Enter
            // submits that text together with the keys typed here, so the
            // terminal parser's line is only a part of the prompt.
            guiSendAwaitingEnter = true;
        }
        if (isConversationCommand(isTextExact ? submitted : text)) {
            // A TUI command is not a prompt written to the current
            // conversation: /resume and /clear can switch it, and these
            // writes are made by the plugin, so no terminal input marks
            // the switch. The line the process submits is the composed one,
            // so a command split across a terminal draft and the panel text
            // counts; a recalled draft makes the composition inexact, and
            // then only the panel text is trusted.
            conversationSwitchAt = sentAt;
        }
        rememberSentPrompt(submitted, sentAt, isTextExact);
        return submitted;
    }

    /**
     * Writes the Enter that submits a prompt sent from the panel and closes the
     * terminal recording for it. The second half of {@link #sendPrompt(String)}.
     *
     * <p>The Enter and the reset of the recorder run under the recorder's lock,
     * so keys typed between the prompt write and this Enter — which are part of
     * the line the TUI submits — cannot stay behind as the draft of the next
     * line. Any such input, even an edit that leaves the recorded line empty
     * (Backspace, cursor keys, Tab), makes the submitted text unknown: the
     * composed evidence recorded by {@link #reconcileGuiSend} no longer
     * describes that line.
     *
     * <p>A terminal Enter pressed during that pause submits the line first, and
     * this Enter then submits an empty one. The evidence of the first
     * submission is kept as it is — its time is never later than the record
     * the child wrote for it, and the panel text is still exact when the line
     * was not edited.
     */
    void completeGuiSend() {
        boolean edited;
        boolean alreadySubmitted;
        long submittedAt;
        synchronized (terminalInputLine) {
            // Sampled before the Enter is forwarded: the child may append the
            // submitted prompt to its session file as soon as it reads the
            // bytes, and a record written then must still pass the submission
            // cutoff used by the evidence lookup.
            submittedAt = System.currentTimeMillis();
            try {
                writePty("\r");
            } catch (IOException ex) {
                LOG.warning("sendPrompt write failed: " + ex.getMessage());
                resetTerminalInputRecording();
                return;
            }
            edited = terminalInputSinceGuiSend;
            alreadySubmitted = terminalEnterSinceGuiSend;
            resetTerminalInputRecording();
        }
        if (alreadySubmitted) {
            // The terminal's own Enter submitted the line before the panel's
            // Enter reached the TUI. That submission's time — recorded by the
            // terminal path, or the panel's send time when the Enter hit the
            // freshly reset recorder — is kept: re-timing it at the no-op
            // Enter would put the cutoff after the user record the child
            // already wrote. Text typed before that Enter is only a part of
            // the submitted line (the panel text is not recorded), so it
            // cannot serve as evidence.
            if (edited) {
                lastPromptText = null;
            }
            scheduleIdentityRefresh();
            return;
        }
        if (edited) {
            // Keys arrived while the panel text was being submitted: their
            // position relative to the panel text is not known, so only the
            // file times and user records can identify the conversation the
            // line went to.
            lastPromptText = null;
            promptSubmittedAt = submittedAt;
            scheduleIdentityRefresh();
        }
    }

    /**
     * Records a prompt sent from the plugin as the evidence the identity scan
     * matches: its text and the submission time.
     *
     * <p>Blank text is not a prompt of this tab. The GUI still permits sending
     * it (a lone Enter), but recording it would turn the submission into an
     * unknown-text one: with no text of ours in this tab's file, a sibling's
     * newer prompt would then win the shared search. The previous evidence is
     * kept instead.
     */
    private void rememberSentPrompt(String text, long sentAt) {
        rememberSentPrompt(text, sentAt, true);
    }

    /**
     * Records a prompt sent from the plugin as the evidence the identity scan
     * matches, or, when only a part of its line is known, as a submission of
     * unknown text.
     *
     * @param text        the prompt text, or the part of it that is known
     * @param sentAt      submission time
     * @param isTextExact {@code false} when {@code text} is only a part of the
     *                    submitted line (a history recall or a Tab completion)
     */
    private void rememberSentPrompt(String text, long sentAt, boolean isTextExact) {
        if (!isTextExact) {
            // The recorder saw only a part of the line: publishing it as the
            // prompt would make the exact-match search look for text the
            // conversation never received. The submission is unknown-text
            // instead, so the file times and the user records decide.
            lastPromptText = null;
            promptSubmittedAt = sentAt;
            scheduleIdentityRefresh();
        } else if (!text.isBlank()) {
            lastPromptText = text;
            promptSubmittedAt = sentAt;
            scheduleIdentityRefresh();
        }
    }

    /**
     * Returns {@code true} when the prompt-panel text is a TUI command that
     * changes the conversation the process writes rather than a message in it.
     *
     * @param text text submitted from the prompt panel
     */
    private static boolean isConversationCommand(String text) {
        String command = text.stripLeading();
        return command.startsWith("/resume") || command.startsWith("/clear");
    }

    /**
     * Records that the GUI answered a choice menu. An {@code ARROW:n} answer
     * selects an entry in an unnumbered picker (e.g. the {@code /resume}
     * session list), which changes the conversation the process writes; the
     * write is made by the plugin, so it leaves the terminal-input time alone.
     *
     * @param answer answer passed to {@link #writePtyAnswer(String)}
     */
    void rememberChoiceMenuAnswer(String answer) {
        if (answer != null && answer.startsWith("ARROW:")) {
            conversationSwitchAt = System.currentTimeMillis();
        }
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
        rememberChoiceMenuAnswer(answer);
        // Choosing an option is the GUI consuming the picker: text typed into
        // its search box belongs to the picker, not to the next prompt.
        resetTerminalInputRecording();
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
                            writePty(digit.strip());
                            Thread.sleep(100);
                        }
                        // Right arrow to submit the selection
                        writePty(new byte[]{0x1b, '[', 'C'});
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
                            writePty(digit.strip());
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
                            writePty(arrow);
                            Thread.sleep(80);
                        }
                        // 4. Type text
                        Thread.sleep(100);
                        writePty(typeText);
                        // 5. Move cursor off type-input field (up arrow)
                        Thread.sleep(100);
                        writePty(new byte[]{0x1b, '[', 'A'});
                        // 6. Submit with right arrow
                        Thread.sleep(100);
                        writePty(new byte[]{0x1b, '[', 'C'});
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
                                    writePty(arrow);
                                    Thread.sleep(80);
                                }
                                Thread.sleep(100);
                            }
                            writePty(text);
                            Thread.sleep(200);
                            writePty("\r");
                        } else {
                            writePty(digit);
                            Thread.sleep(200);
                            writePty(text);
                            Thread.sleep(200);
                            writePty("\r");
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
                    writePty("\r");
                } else {
                    byte[] arrow = delta < 0 ? new byte[]{0x1b, '[', 'A'} : new byte[]{0x1b, '[', 'B'};
                    Thread t = new Thread(() -> {
                        try {
                            for (int idx = 0; idx < Math.abs(delta); idx++) {
                                writePty(arrow);
                                Thread.sleep(80);
                            }
                            writePty("\r");
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
                        writePty("\r");
                    } else {
                        byte[] arrow = delta < 0 ? new byte[]{0x1b, '[', 'A'} : new byte[]{0x1b, '[', 'B'};
                        Thread t = new Thread(() -> {
                            try {
                                for (int i = 0; i < Math.abs(delta); i++) {
                                    writePty(arrow);
                                    Thread.sleep(80);
                                }
                                writePty("\r");
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
                    writePty(toWrite);
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
                    writePty(arrow);
                    Thread.sleep(80);
                }
                writePty("\r");
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
                writePty(new byte[]{0x1b, '[', 'Z'});
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
        writePty(new byte[]{0x1b, '[', 'Z'});
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
                    writePty("/model " + customId + "\r");
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
                        writePty(String.valueOf(index + 1));
                    } else {
                        // Items 10+ cannot be typed: move the cursor onto the item, then Enter.
                        int cursor = waitForModelMenuCursor();
                        if (cursor > 0) {
                            int delta = index + 1 - cursor;
                            byte[] arrow = delta < 0 ? PTY_ARROW_UP : PTY_ARROW_DOWN;
                            for (int i = 0; i < Math.abs(delta); i++) {
                                writePty(arrow);
                                Thread.sleep(80);
                            }
                            writePty("\r");
                        } else {
                            LOG.warning("model switch: /model menu not visible, cannot select item " + (index + 1));
                            writePty(PTY_ESC);
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
                        writePty(PTY_BACKSPACE);
                        Thread.sleep(200);
                        writePty("\r");
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

                writePty(new byte[]{0x1b});  // Esc — dismiss model menu

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
            @Override public void down() throws IOException { writePty(PTY_ARROW_DOWN); }
            @Override public void up() throws IOException { writePty(PTY_ARROW_UP); }
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
        writePty("/model");
        Thread.sleep(200);
        writePty(PTY_ESC);
        Thread.sleep(200);
        writePty("\r");
        Thread.sleep(200);
    }

    /**
     * Sends {@code bytes} to the PTY and transitions the model to
     * {@link SessionLifecycle#READY}.
     */
    private void sendCancelToPty(byte[] bytes) throws IOException {
        writePty(bytes);
        onClaudeIdle();
    }

    /**
     * Writes bytes to the PTY on behalf of the plugin.
     *
     * <p>Plugin writes are marked on the calling thread so that
     * {@link PtyTtyConnector#setInputListener} can tell them apart from text
     * typed directly into the terminal, which can change the conversation
     * (e.g. an in-process {@code /resume}) and invalidates the prompt evidence
     * captured by {@link #sendPrompt(String)}.
     */
    private void writePty(byte[] bytes) throws IOException {
        java.util.function.Consumer<byte[]> hook = ptyWriteHook;
        if (hook != null) {
            hook.accept(bytes);
            return;
        }
        if (connector == null) {
            // No PTY yet (the launch is still being prepared): drop the write,
            // as sendPrompt() does.
            LOG.fine("[PTY write] dropped, no connector");
            return;
        }
        boolean previous = pluginPtyWrite.get();
        pluginPtyWrite.set(true);
        try {
            connector.write(bytes);
        } finally {
            pluginPtyWrite.set(previous);
        }
    }

    /**
     * Package-private for tests: replaces the PTY write, so a test can make the
     * write take time or observe when it happens. A {@code null} hook restores
     * the real connector.
     */
    void simulatePtyWrite(java.util.function.Consumer<byte[]> hook) {
        ptyWriteHook = hook;
    }

    /** Writes text to the PTY on behalf of the plugin. See {@link #writePty(byte[])}. */
    private void writePty(String text) throws IOException {
        writePty(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Records input typed in the terminal (called from the PTY connector, on
     * the thread that wrote to the PTY).
     *
     * <p>Any such input invalidates the prompt evidence captured before it: the
     * text may drive Claude's own {@code /resume} picker and switch the
     * conversation. A complete line submitted with Enter is the exception — it
     * is a prompt written to the conversation on screen, so it becomes the new
     * evidence, which is what lets a resume driven entirely from the keyboard
     * be followed. A resume command typed there is the other exception: it
     * switches the conversation instead of writing to it, and so does an Enter
     * that answers the {@code /resume} picker.
     *
     * @param bytes raw input bytes
     * @param now   time of the input (epoch millis)
     * @return the line submitted with this input, or {@code null}
     */
    private String onTerminalInput(byte[] bytes, long now) {
        String submitted;
        boolean entered;
        boolean emptyEnter;
        boolean recalled;
        boolean menuSelection;
        synchronized (terminalInputLine) {
            terminalLineEntered = false;
            terminalEnterOnEmptyLine = false;
            submitted = appendTerminalInput(bytes);
            entered = terminalLineEntered;
            terminalLineEntered = false;
            emptyEnter = terminalEnterOnEmptyLine;
            terminalEnterOnEmptyLine = false;
            recalled = terminalRecalledSubmission;
            terminalRecalledSubmission = false;
            menuSelection = terminalMenuSelection;
            terminalMenuSelection = false;
        }
        if (entered && !emptyEnter) {
            externalInputAt = now;
        }
        if (menuSelection) {
            // Enter with a choice menu open selected an item. The /resume
            // picker changes the conversation that way, and nothing else in the
            // input tells which conversation was picked.
            conversationSwitchAt = now;
        }
        if (submitted != null) {
            if (isConversationCommand(submitted)) {
                // A resume command typed in the terminal is not a prompt the
                // conversation receives: it switches the conversation, and the
                // destination may be a saved one no live tab holds. Its text
                // must not become the prompt evidence either — no file holds
                // it — so the submission is recorded as unknown-text.
                conversationSwitchAt = now;
                lastPromptText = null;
            } else {
                lastPromptText = submitted;
            }
            promptSubmittedAt = now;
            scheduleIdentityRefresh();
        } else if (recalled) {
            // The prompt came from the TUI's history (Up/Down): its text is
            // unknown to the plugin, but the submission is real. Previous text
            // evidence no longer describes what was submitted, and the time
            // lets the scan follow the file the process writes next.
            lastPromptText = null;
            promptSubmittedAt = now;
            scheduleIdentityRefresh();
        }
        return submitted;
    }

    /**
     * Consumes {@code bytes} into {@link #terminalInputLine} and returns the
     * line when Enter was pressed, or {@code null}.
     *
     * <p>Escape sequences (cursor keys, the {@code /resume} picker) and control
     * characters are dropped so that only typed text is kept; the cursor keys
     * that edit the line (Left/Right/Home/End/Delete) are applied to it.
     * Inside a bracketed paste the text is recorded as typed — including its
     * newlines, which are part of the prompt rather than submissions.
     */
    private String appendTerminalInput(byte[] bytes) {
        String submitted = null;
        String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        // An Esc that was the last byte of the previous write is standalone
        // unless this write continues a CSI/SS3 sequence: a terminal sends
        // Alt+char as ESC+char in one write, while Esc alone (dismissing the
        // autocomplete) arrives on its own and the next keystroke follows in a
        // separate write.
        boolean carriedEscape = terminalInputEscape == 1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // Any input changes the line the TUI holds — including edits that
            // leave the recorded buffer unchanged or empty (Backspace, cursor
            // keys, Ctrl+U, Tab) — so a GUI submission still waiting for its
            // Enter can no longer rely on the text it composed. Enter is
            // tracked separately: it submits the line instead of editing it.
            if (c == '\r' || c == '\n') {
                if (terminalInputPaste) {
                    // A line break inside a paste is prompt text, not a
                    // submission, but it still changes the line.
                    terminalInputSinceGuiSend = true;
                }
            } else {
                terminalInputSinceGuiSend = true;
            }
            if (c == 0x1b) {
                if (carriedEscape && terminalEscapeOnMenu) {
                    // The Esc carried from the previous write was a standalone
                    // cancellation of a choice menu, and this Esc begins a new
                    // sequence: the cancellation is complete, so the picker's
                    // search text must not become prompt draft.
                    discardPickerSearchText();
                }
                terminalInputEscape = 1;
                terminalEscapeParams.setLength(0);
                terminalPasteAfterCR = false;
                // A standalone Esc cancels a choice menu (the /resume picker):
                // the recorded line then holds the picker's search text, not a
                // prompt draft.
                terminalEscapeOnMenu = openChoiceMenu() != null;
                // A new Esc belongs to this write, so it is not the carried
                // standalone one and may start a sequence even right after it.
                carriedEscape = false;
            } else if (terminalInputEscape == 1) {
                // ESC [ starts a CSI sequence, ESC O an SS3 one. Any other
                // character ends a two-character escape (ESC x, e.g. Alt+x).
                // When the Esc was carried over from the previous write it was
                // standalone instead — the TUI uses it to dismiss autocomplete
                // — and this write starts the actual input: a real CSI/SS3
                // sequence is written in one piece, so '[' or 'O' typed later
                // are ordinary characters too. The character is then processed
                // again as input.
                if (carriedEscape) {
                    terminalInputEscape = 0;
                    i--;
                    if (terminalEscapeOnMenu) {
                        // The carried Esc was a standalone cancellation of a
                        // choice menu: discard the picker's search text so it
                        // cannot prefix the next prompt.
                        discardPickerSearchText();
                    }
                } else if (c == '[') {
                    terminalInputEscape = 2;
                } else if (c == 'O') {
                    terminalInputEscape = 3;
                } else {
                    terminalInputEscape = 0;
                }
                carriedEscape = false;
            } else if (terminalInputEscape == 2) {
                // CSI: parameter and intermediate bytes (0x20–0x3f) precede
                // the final byte (0x40–0x7e), which ends the sequence.
                if (c >= 0x40 && c <= 0x7e) {
                    terminalInputEscape = 0;
                    onCsiFinal(c, terminalEscapeParams.toString());
                } else {
                    terminalEscapeParams.append(c);
                }
            } else if (terminalInputEscape == 3) {
                terminalInputEscape = 0; // SS3: exactly one character after ESC O
                if (!terminalInputPaste) applyCursorKey(c);
            } else if (terminalInputPaste) {
                // Inside a paste every newline is part of the prompt, not a
                // submission of the line typed so far. Collect the whole
                // textual run — printable characters, tabs (literal text in a
                // paste, while the same key typed live completes the input)
                // and line breaks, with CRLF normalized to one newline — and
                // insert it in a single operation. Inserting one character or
                // one line break at a time copies the still-unchanged suffix
                // of the draft for every insertion, which makes a large paste
                // before long existing text quadratic. The run stops before an
                // escape sequence (the end-of-paste marker) or any other
                // control character, which are still parsed on their own.
                StringBuilder pasted = new StringBuilder();
                boolean afterCR = terminalPasteAfterCR;
                int end = i;
                while (end < text.length()) {
                    char next = text.charAt(end);
                    if (next == '\r') {
                        pasted.append('\n');
                        afterCR = true;
                        end++;
                    } else if (next == '\n') {
                        // The LF of a CRLF pair split across writes is part of
                        // the same line break.
                        if (!afterCR) pasted.append('\n');
                        afterCR = false;
                        end++;
                    } else if (next == '\t' || next >= 0x20) {
                        pasted.append(next);
                        afterCR = false;
                        end++;
                    } else {
                        break;
                    }
                }
                if (end > i) {
                    insertTerminalText(pasted);
                    terminalPasteAfterCR = afterCR;
                    i = end - 1;
                }
            } else if (c == '\r' || c == '\n') {
                String line = terminalInputLine.toString().trim();
                boolean unknownText = terminalHistoryRecall || terminalCompletionEdited;
                ChoiceMenuModel openMenu = openChoiceMenu();
                boolean onMenu = openMenu != null;
                // Keys typed during the GUI send's pause edited the line the
                // panel text was written to, but the recorder holds none of
                // that text: what this Enter submits is unknown even when the
                // recorded line is empty (a Backspace on it deletes a
                // character of the panel text).
                boolean gapEdited = guiSendAwaitingEnter && terminalInputSinceGuiSend;
                terminalEnterSinceGuiSend = true;
                terminalHistoryRecall = false;
                terminalCompletionEdited = false;
                terminalInputLine.setLength(0);
                terminalInputCursor = 0;
                terminalLineEntered = true;
                // An Enter on the still-empty line does nothing on the TUI
                // unless a choice menu is open: it is neither a prompt nor a
                // history recall nor a picker selection, so it must not
                // invalidate the evidence of the prompt already submitted.
                terminalEnterOnEmptyLine = line.isEmpty() && !unknownText
                        && !onMenu && !gapEdited;
                terminalMenuSelection = onMenu;
                if (onMenu && !isCheckboxMenu(openMenu)) {
                    // A numbered menu closes on the selection Enter (a
                    // checkbox menu only toggles and stays open until Right),
                    // so the model's stale entry must stop classifying the
                    // input that follows.
                    completeTerminalMenu(openMenu);
                }
                if (unknownText) {
                    // The line was filled from the TUI history (Up/Down) or
                    // completed by Tab and may have been edited since: the
                    // characters recorded here are only a part of the prompt,
                    // so its text is unknown and an in-process resume must be
                    // followed by file times.
                    terminalRecalledSubmission = true;
                } else if (!onMenu && (gapEdited || !line.isEmpty())) {
                    // With a choice menu open Enter selects an item instead of
                    // submitting the line: what was typed there is the picker's
                    // search filter, not a prompt.
                    if (guiSendAwaitingEnter) {
                        // A prompt sent from the panel is already written on
                        // the TUI's line: this Enter submits it together with
                        // the recorded text, which is only a part of the
                        // prompt. Publishing it as the evidence would let an
                        // identity scan match another conversation's file for
                        // the partial text before the panel's Enter is sent.
                        terminalRecalledSubmission = true;
                    } else {
                        submitted = line;
                    }
                }
            } else if (c == 0x7f || c == 0x08) {
                deleteTerminalCharBeforeCursor();
            } else if (c == 0x03 || c == 0x15) { // Ctrl+C, Ctrl+U
                terminalInputLine.setLength(0);
                terminalInputCursor = 0;
                // Clearing the input also discards a recalled or completed
                // prompt, so what is typed next is known in full again.
                terminalHistoryRecall = false;
                terminalCompletionEdited = false;
            } else if (c == '\t') {
                // A live Tab makes the TUI complete the input from its file or
                // command suggestions; the inserted text never reaches this
                // parser, so the line recorded so far is only a prefix. While a
                // choice menu is open Tab belongs to the menu ("Tab to amend")
                // and Enter selects an item instead of submitting a prompt, so
                // the menu context is not completion evidence.
                if (openChoiceMenu() == null) {
                    terminalCompletionEdited = true;
                }
            } else if (c >= 0x20) {
                // While a choice menu is open, a printed digit selects an item
                // immediately (see writePtyAnswer) and never becomes part of a
                // prompt: recording it would prefix the next prompt's evidence
                // with the selection and make the actual text unmatchable.
                ChoiceMenuModel openMenu = openChoiceMenu();
                if (openMenu != null && c >= '0' && c <= '9') {
                    if (digitSelectionDismissesMenu(openMenu)) {
                        completeTerminalMenu(openMenu);
                    }
                } else {
                    insertTerminalChar(c);
                }
            }
        }
        return submitted;
    }

    /** Set when {@link #appendTerminalInput(byte[])} saw Enter, cleared by the caller. */
    private boolean terminalLineEntered;

    /**
     * Set when that Enter closed a line that was still empty and no choice menu
     * was open, i.e. it acted on nothing: cleared by the caller like
     * {@link #terminalLineEntered}.
     */
    private boolean terminalEnterOnEmptyLine;

    /** Inserts {@code c} at the cursor and advances it. */
    private void insertTerminalChar(char c) {
        int at = Math.min(terminalInputCursor, terminalInputLine.length());
        terminalInputLine.insert(at, c);
        terminalInputCursor = at + 1;
    }

    /** Inserts {@code text} at the cursor in one operation and advances it. */
    private void insertTerminalText(CharSequence text) {
        int at = Math.min(terminalInputCursor, terminalInputLine.length());
        terminalInputLine.insert(at, text);
        terminalInputCursor = at + text.length();
    }

    /**
     * Applies a horizontal cursor key (Left/Right/Home/End) to the line being
     * recorded, so that text typed after it lands where the user sees it.
     * Left/Right step over whole Unicode characters: a supplementary character
     * occupies two UTF-16 code units, and stopping between them would let a
     * later edit split the pair.
     *
     * @param key CSI/SS3 final byte: {@code D}, {@code C}, {@code H} or {@code F}
     */
    private void applyCursorKey(char key) {
        switch (key) {
            case 'D' -> {
                int at = Math.min(terminalInputCursor, terminalInputLine.length());
                terminalInputCursor =
                        at <= 0 ? 0 : terminalInputLine.offsetByCodePoints(at, -1);
            }
            case 'C' -> {
                ChoiceMenuModel menu = openChoiceMenu();
                if (menu != null && isCheckboxMenu(menu)) {
                    // Right submits a checkbox menu (the answer protocol the
                    // menu panel writes): the menu is dismissed, so the text
                    // typed into it — an option's free-text answer — must not
                    // prefix the next prompt.
                    discardPickerSearchText();
                    return;
                }
                int at = Math.min(terminalInputCursor, terminalInputLine.length());
                terminalInputCursor = at >= terminalInputLine.length()
                        ? terminalInputLine.length()
                        : terminalInputLine.offsetByCodePoints(at, 1);
            }
            case 'H' -> terminalInputCursor = 0;
            case 'F' -> terminalInputCursor = terminalInputLine.length();
            case 'A', 'B' -> {
                // Up/Down recall a prompt from the TUI history into its input;
                // the text never reaches this parser. Remember that whatever is
                // submitted next was not typed here. While a choice menu is
                // open the same keys move its cursor instead — the line stays
                // empty too, but Enter selects a menu item rather than a
                // recalled prompt, so it must not be recorded as a submission.
                if (terminalInputLine.isEmpty() && openChoiceMenu() == null) {
                    terminalHistoryRecall = true;
                }
            }
            default -> { /* other keys do not edit the line */ }
        }
    }

    /**
     * Removes the whole character before the cursor (the Backspace key),
     * including both halves of a supplementary Unicode character.
     */
    private void deleteTerminalCharBeforeCursor() {
        int end = Math.min(terminalInputCursor, terminalInputLine.length());
        if (end <= 0) return;
        int start = terminalInputLine.offsetByCodePoints(end, -1);
        terminalInputLine.delete(start, end);
        terminalInputCursor = start;
    }

    /**
     * Deletes the whole character under the cursor (the Delete key), including
     * both halves of a supplementary Unicode character.
     */
    private void deleteTerminalCharAtCursor() {
        int at = Math.min(terminalInputCursor, terminalInputLine.length());
        if (at >= terminalInputLine.length()) return;
        int end = terminalInputLine.offsetByCodePoints(at, 1);
        terminalInputLine.delete(at, end);
        terminalInputCursor = at;
    }

    /**
     * Handles the final byte of a CSI sequence: the bracketed-paste markers
     * {@code ESC [ 200 ~} / {@code ESC [ 201 ~} switch paste recording on and
     * off, cursor keys edit the recorded line, everything else is dropped.
     *
     * @param finalByte final byte of the sequence
     * @param params    parameter bytes collected before it
     */
    private void onCsiFinal(char finalByte, String params) {
        if (finalByte == '~') {
            if ("200".equals(params)) {
                terminalInputPaste = true;
                terminalPasteAfterCR = false;
            } else if ("201".equals(params)) {
                terminalInputPaste = false;
                terminalPasteAfterCR = false;
            } else if (!terminalInputPaste) {
                if ("3".equals(params)) {
                    deleteTerminalCharAtCursor();
                } else if ("1".equals(params) || "7".equals(params)) {
                    applyCursorKey('H');
                } else if ("4".equals(params) || "8".equals(params)) {
                    applyCursorKey('F');
                }
            }
        } else if (!terminalInputPaste) {
            applyCursorKey(finalByte); // Left/Right/Home/End; others are ignored
        }
    }

    /** Package-private for tests: simulates typing in the terminal at {@code millis}. */
    void simulateExternalInput(long millis) {
        externalInputAt = millis;
    }

    /**
     * Package-private for tests: simulates raw terminal input arriving at
     * {@code millis}.
     *
     * @return the line submitted by this input, or {@code null}
     */
    String simulateTerminalInput(byte[] bytes, long millis) {
        return onTerminalInput(bytes, millis);
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
