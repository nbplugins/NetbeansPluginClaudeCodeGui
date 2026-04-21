package io.github.nbclaudecodegui.ui;

import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jediterm.terminal.ui.JediTermWidget;
import io.github.nbclaudecodegui.controller.ClaudeSessionController;
import io.github.nbclaudecodegui.ui.common.BasicTextContextMenu;
import io.github.nbclaudecodegui.model.ChoiceMenuModel;
import io.github.nbclaudecodegui.model.ClaudeSessionModel;
import io.github.nbclaudecodegui.model.SavedSession;
import io.github.nbclaudecodegui.model.SessionLifecycle;
import io.github.nbclaudecodegui.model.SessionMode;
import io.github.nbclaudecodegui.process.ClaudeSessionStore;
import io.github.nbclaudecodegui.settings.ClaudeCodePreferences;
import io.github.nbclaudecodegui.settings.ClaudeProfile;
import io.github.nbclaudecodegui.settings.ClaudeProfileStore;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import org.netbeans.api.options.OptionsDisplayer;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.openide.util.ImageUtilities;
import org.openide.util.NbPreferences;
import org.openide.windows.TopComponent;
import org.openide.windows.WindowManager;

/**
 * TopComponent representing a single Claude Code session.
 *
 * <p>Owns the full session lifecycle: the MVC triple
 * ({@link ClaudeSessionModel}, {@link ClaudeSessionController}),
 * the directory/profile selector ({@link ClaudeSessionSelectorPanel}),
 * the embedded JediTerm terminal, the interactive-choice menu
 * ({@link ChoiceMenuPanel}), the prompt input ({@link ClaudePromptPanel}),
 * and the status bar.
 *
 * <p>Layout while idle (no session):
 * <pre>
 *   NORTH  — ClaudeSessionSelectorPanel
 *   CENTER — placeholder label
 *   SOUTH  — status bar (hidden)
 * </pre>
 *
 * Layout while a session is active:
 * <pre>
 *   NORTH  — (selector hidden)
 *   CENTER — JSplitPane
 *               top    — JediTermWidget
 *               bottom — southStack
 *                          NORTH — ChoiceMenuPanel (visible only during prompts)
 *                          CENTER — ClaudePromptPanel (input area)
 *   SOUTH  — status bar
 * </pre>
 *
 * <p>On IDE restart the working directory and profile name are restored via
 * {@link #readExternal} / {@link #writeExternal}.
 */
@TopComponent.Description(
    preferredID = "ClaudeSessionTopComponent",  // kept for persistence compatibility
    iconBase = "io/github/nbclaudecodegui/icons/claude-icon.png",
    persistenceType = TopComponent.PERSISTENCE_ALWAYS
)
@TopComponent.Registration(
    mode = "editor",
    openAtStartup = false
)
public class ClaudeSessionTab extends TopComponent
        implements ClaudeSessionModel.ClaudeSessionModelListener {

    private static final Logger LOG = Logger.getLogger(ClaudeSessionTab.class.getName());

    private static final String ICON =
            "io/github/nbclaudecodegui/icons/claude-icon.png";

    // -------------------------------------------------------------------------
    // Edit mode constants
    // -------------------------------------------------------------------------

    static final String[] EDIT_MODE_LABELS = {"Plan Mode", "Ask on Edit", "Accept on Edit"};
    static final String[] EDIT_MODE_VALUES = {"plan",      "default",     "acceptEdits"};

    // -------------------------------------------------------------------------
    // South card constants
    // -------------------------------------------------------------------------

    private static final String CARD_PROMPT = "prompt";
    private static final String CARD_CHOICE = "choice";
    private static final String CARD_DIFF   = "diff";
    private static final int    DEFAULT_DIFF_HEIGHT = 300;

    // -------------------------------------------------------------------------
    // Persistence fields
    // -------------------------------------------------------------------------

    /** Set by {@link #componentClosed()} so {@link #componentOpened()} can restart in same dir. */
    private String pathToRestore;

    /** Restored from serialized state after an IDE restart. */
    private String savedPath;

    /** Profile name restored from serialized state; {@code null} = Default. */
    private String savedProfileName;

    /** Extra CLI args restored from serialized state after an IDE restart. */
    private String savedExtraCliArgs;

    /** Resume session ID restored from serialized state; {@code null} if not applicable. */
    private String savedResumeSessionId;

    /** Resume session ID actually used when the current session was started. */
    private String activeResumeSessionId;

    // -------------------------------------------------------------------------
    // MVC
    // -------------------------------------------------------------------------

    private final ClaudeSessionModel      model      = new ClaudeSessionModel();
    private final ClaudeSessionController controller =
            new ClaudeSessionController(model, this::getScreenLines);

    /** Logging/debugging tag, e.g. {@code "[my-project] "}. */
    private String sessionTag = "";

    // -------------------------------------------------------------------------
    // UI — selector + placeholder
    // -------------------------------------------------------------------------

    private final ClaudeSessionSelectorPanel selectorPanel;

    // -------------------------------------------------------------------------
    // UI — session (created once a session starts, nulled on stop)
    // -------------------------------------------------------------------------

    private JediTermWidget  terminalWidget;
    private JSplitPane      splitPane;
    private JPanel          errorPanel;
    JPanel          southCard;
    CardLayout      southCardLayout;
    String          activeCard;
    JPanel          currentDiffCard;
    private Runnable        pendingDiffOnClose;

    // -------------------------------------------------------------------------
    // UI — always present after construction
    // -------------------------------------------------------------------------

    private final ChoiceMenuPanel  choiceMenuPanel;
    private final ClaudePromptPanel promptPanel;

    // -------------------------------------------------------------------------
    // UI — status bar
    // -------------------------------------------------------------------------

    private final JPanel            statusBar;
    private final JComboBox<String> editModeCombo;
    private final JComboBox<String> modelCombo;
    private final JLabel            stateLabel;
    private final JLabel            planLabel;
    private final JLabel            versionLabel;
    private final JButton           stopButton;

    /** Cached lifecycle so {@link #onModelListChanged} can correctly enable the combo. */
    private volatile SessionLifecycle currentLifecycle;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /** Creates a new empty session (no directory selected). */
    public ClaudeSessionTab() {
        this(null);
    }

    /**
     * Creates a session pre-configured for the given directory.
     *
     * @param dir working directory, or {@code null} for none
     */
    public ClaudeSessionTab(File dir) {

        // --- status bar ---
        editModeCombo = new JComboBox<>(EDIT_MODE_LABELS);
        editModeCombo.setMaximumSize(new Dimension(130, 24));
        editModeCombo.setToolTipText("Edit mode");
        editModeCombo.addActionListener(e -> onEditModeComboChanged());

        modelCombo = new JComboBox<>();
        modelCombo.setMaximumSize(new Dimension(200, 24));
        modelCombo.setToolTipText("Active model");
        modelCombo.setEnabled(false);
        modelCombo.addActionListener(e -> onModelComboChanged());

        stateLabel   = new JLabel("Ready");
        planLabel    = new JLabel("");
        versionLabel = new JLabel("");
        stateLabel.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
        planLabel.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
        versionLabel.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
        stateLabel.setToolTipText("Session state: Ready or Working");
        planLabel.setToolTipText("Active plan file (if any)");
        versionLabel.setToolTipText("Claude CLI version");

        stopButton = new JButton("\u23fb"); // ⏻ power symbol
        stopButton.setToolTipText("Close session");
        stopButton.setMargin(new Insets(0, 4, 0, 4));
        stopButton.addActionListener(e -> openSwitchDialog(SessionMode.CLOSE_ONLY));

        statusBar = new JPanel();
        statusBar.setLayout(new BoxLayout(statusBar, BoxLayout.X_AXIS));
        statusBar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Color.LIGHT_GRAY));
        statusBar.add(Box.createRigidArea(new Dimension(4, 0)));
        statusBar.add(editModeCombo);
        statusBar.add(Box.createRigidArea(new Dimension(4, 0)));
        statusBar.add(makeSep());
        statusBar.add(Box.createRigidArea(new Dimension(4, 0)));
        statusBar.add(modelCombo);
        statusBar.add(Box.createHorizontalGlue());
        statusBar.add(stateLabel);
        statusBar.add(makeSep());
        statusBar.add(planLabel);
        statusBar.add(makeSep());
        statusBar.add(versionLabel);
        statusBar.add(Box.createRigidArea(new Dimension(4, 0)));
        statusBar.add(makeSep());
        statusBar.add(Box.createRigidArea(new Dimension(4, 0)));
        statusBar.add(stopButton);
        statusBar.add(Box.createRigidArea(new Dimension(4, 0)));
        statusBar.setVisible(false);

        // --- selector + placeholder ---
        selectorPanel    = new ClaudeSessionSelectorPanel(dir, (d, pn, ea, mode, resumeId) -> onDirectoryOpened(d, pn, ea, mode, resumeId));

        // --- chat UI ---
        choiceMenuPanel = new ChoiceMenuPanel(() -> {
            java.io.File wd = model.getWorkingDirectory();
            return wd != null ? wd.getAbsolutePath() : null;
        });
        promptPanel     = new ClaudePromptPanel(
                this::sendPrompt,
                this::cancelPrompt,
                controller::sendShiftTab,
                model::getPromptHistory,
                () -> {
                    File wd = model.getWorkingDirectory();
                    return wd != null ? wd.getAbsolutePath() : null;
                },
                () -> onSaveAndSwitch("", SessionMode.NEW, null),
                () -> openSwitchDialog(SessionMode.RESUME_SPECIFIC));

        // --- layout ---
        setLayout(new BorderLayout());
        add(selectorPanel, BorderLayout.CENTER);
        add(statusBar,     BorderLayout.SOUTH);

        setIcon(ImageUtilities.loadImage(ICON, true));
        updateDisplayName(dir);
        model.addListener(this);
    }

    // -------------------------------------------------------------------------
    // Static factory helpers
    // -------------------------------------------------------------------------

    /**
     * Opens an empty session (toolbar action).
     *
     * <p>Focuses an existing idle session if one is open; otherwise opens a new one.
     */
    private static void dockAndOpen(ClaudeSessionTab tab) {
        io.github.nbclaudecodegui.ui.common.UiUtils.dockAndOpen(
                tab, ClaudeCodePreferences.getSessionDockMode());
    }

    public static void openNewOrFocus() {
        SwingUtilities.invokeLater(() -> {
            for (TopComponent tc : WindowManager.getDefault().getRegistry().getOpened()) {
                if (tc instanceof ClaudeSessionTab tab) {
                    boolean active = tab.isSessionActive();
                    LOG.fine("openNewOrFocus: found tab path=" + tab.getWorkingDirectory()
                            + " isSessionActive=" + active);
                    if (!active) {
                        LOG.fine("openNewOrFocus: focusing idle tab");
                        tab.requestActive();
                        return;
                    }
                }
            }
            LOG.fine("openNewOrFocus: no idle tab found, opening new tab");
            ClaudeSessionTab tab = new ClaudeSessionTab();
            dockAndOpen(tab);
            tab.requestActive();
        });
    }

    /**
     * Opens a session for {@code dir} using the Default profile.
     *
     * @param dir working directory
     */
    public static void openForDirectory(File dir) {
        openForDirectory(dir, null);
    }

    /**
     * Opens a session for {@code dir} with the specified profile.
     *
     * <p>Focuses an existing session for {@code dir} if one is already open.
     *
     * @param dir         working directory
     * @param profileName profile name, or {@code null} for Default
     */
    public static void openForDirectory(File dir, String profileName) {
        SwingUtilities.invokeLater(() -> {
            for (TopComponent tc : WindowManager.getDefault().getRegistry().getOpened()) {
                if (tc instanceof ClaudeSessionTab tab
                        && dir.equals(tab.getWorkingDirectory())) {
                    tab.requestActive();
                    return;
                }
            }
            ClaudeSessionTab tab = new ClaudeSessionTab(dir);
            dockAndOpen(tab);
            tab.requestActive();
            tab.autoStart(dir, profileName);
        });
    }

    // -------------------------------------------------------------------------
    // TopComponent lifecycle
    // -------------------------------------------------------------------------

    @Override
    protected void componentOpened() {
        super.componentOpened();
        String path        = savedPath != null ? savedPath : pathToRestore;
        String profileName = savedProfileName;
        String extraCliArgs = savedExtraCliArgs;
        String resumeSessionId = savedResumeSessionId;
        savedPath        = null;
        pathToRestore    = null;
        savedProfileName = null;
        savedExtraCliArgs = null;
        savedResumeSessionId = null;

        if (path != null && !path.isBlank()) {
            File dir = new File(path);
            if (dir.isDirectory()) {
                selectorPanel.lock();
                WindowManager.getDefault().invokeWhenUIReady(
                        resumeSessionId != null
                            ? () -> autoStart(dir, profileName, extraCliArgs, SessionMode.RESUME_SPECIFIC, resumeSessionId)
                            : () -> autoStart(dir, profileName, extraCliArgs));
                return;
            }
        }
        updateDisplayName(null);
    }

    @Override
    protected void componentClosed() {
        super.componentClosed();
        File dir = model.getWorkingDirectory();
        pathToRestore = (dir != null) ? dir.getAbsolutePath() : null;
        if (pendingDiffOnClose != null) {
            try { pendingDiffOnClose.run(); } catch (Exception ex) { /* ignore */ }
            pendingDiffOnClose = null;
        }
        stopProcess();
    }

    @Override
    protected void componentActivated() {
        super.componentActivated();
        requestFocusOnInput();
    }

    @Override
    public boolean canClose() {
        return true;
    }

    @Override
    public int getPersistenceType() {
        return TopComponent.PERSISTENCE_ALWAYS;
    }

    // -------------------------------------------------------------------------
    // Persistence (IDE restart)
    // -------------------------------------------------------------------------

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
        super.writeExternal(out);
        File dir = model.getWorkingDirectory();
        out.writeUTF(dir != null ? dir.getAbsolutePath() : "");
        out.writeUTF(selectorPanel.getSelectedProfileName());
        out.writeUTF(selectorPanel.getExtraCliArgs());
        // Only resume ID is saved; on restore, global preference determines mode unless RESUME_SPECIFIC
        out.writeUTF(activeResumeSessionId != null ? activeResumeSessionId : "");
    }

    @Override
    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        super.readExternal(in);
        savedPath = in.readUTF();
        try {
            savedProfileName = in.readUTF();
        } catch (java.io.EOFException ignored) {
            savedProfileName = null;
        }
        try {
            savedExtraCliArgs = in.readUTF();
        } catch (java.io.EOFException ignored) {
            savedExtraCliArgs = null;
        }
        try {
            String resumeId = in.readUTF();
            savedResumeSessionId = resumeId.isBlank() ? null : resumeId;
        } catch (java.io.EOFException ignored) {
            savedResumeSessionId = null;
        }
    }

    // -------------------------------------------------------------------------
    // ClaudeSessionModelListener
    // -------------------------------------------------------------------------

    /** Updates status bar and prompt panel on lifecycle transitions. */
    @Override
    public void onLifecycleChanged(SessionLifecycle s) {
        currentLifecycle = s;
        if (!statusBar.isVisible()) {
            statusBar.setVisible(true);
            Thread vt = new Thread(() -> {
                String ver = controller.readVersion();
                SwingUtilities.invokeLater(() -> versionLabel.setText(ver));
            }, "claude-version");
            vt.setDaemon(true);
            vt.start();
        }
        stateLabel.setText(switch (s) {
            case STARTING -> "Starting";
            case READY    -> "Ready";
            case WORKING  -> "Working";
        });
        boolean ready = s == SessionLifecycle.READY;
        modelCombo.setEnabled(ready && modelCombo.getItemCount() > 0);
        promptPanel.setReadyState(ready);
        if (ready) promptPanel.requestFocusOnInputArea();
    }

    /** Shows / hides the choice-menu using the card layout. */
    @Override
    public void onChoiceMenuChanged(ChoiceMenuModel menu) {
        if (menu != null) {
            LOG.fine(sessionTag + "[onChoiceMenuChanged] showing menu: \"" + menu.text()
                    + "\" EDT=" + SwingUtilities.isEventDispatchThread());
            String focusMode = io.github.nbclaudecodegui.settings.ClaudeCodePreferences.getChoiceMenuFocusMode();
            if (io.github.nbclaudecodegui.settings.ClaudeCodePreferences.CHOICE_MENU_HIDE_MENU.equals(focusMode)) {
                return;
            }
            boolean grabFocus = io.github.nbclaudecodegui.settings.ClaudeCodePreferences.CHOICE_MENU_GRAB_FOCUS.equals(focusMode);
            choiceMenuPanel.show(menu, answer -> {
                LOG.fine(sessionTag + "[PTY prompt answer] " + answer);
                SwingUtilities.invokeLater(() -> switchSouthCard(CARD_PROMPT));
                revalidate();
                repaint();
                controller.writePtyAnswer(answer);
            }, grabFocus);
            LOG.fine(sessionTag + "[onChoiceMenuChanged pre-switch] " + termScrollState());
            switchSouthCard(CARD_CHOICE);
            LOG.fine(sessionTag + "[onChoiceMenuChanged post-switch] " + termScrollState());
        } else {
            if (CARD_CHOICE.equals(activeCard)) {
                choiceMenuPanel.dismiss();
                SwingUtilities.invokeLater(() -> switchSouthCard(CARD_PROMPT));
                revalidate();
                repaint();
            }
        }
    }

    /** Syncs the edit-mode combo without re-triggering the action listener. */
    @Override
    public void onEditModeChanged(String mode) {
        if (mode == null) return;
        int idx = editModeIndexOf(mode);
        if (idx >= 0 && editModeCombo.getSelectedIndex() != idx) {
            editModeCombo.removeActionListener(editModeCombo.getActionListeners()[0]);
            editModeCombo.setSelectedIndex(idx);
            editModeCombo.addActionListener(ae -> onEditModeComboChanged());
        }
    }

    /** Repopulates the model combo. */
    @Override
    public void onModelListChanged(List<String> models, int selectedIdx) {
        DefaultComboBoxModel<String> cbModel = new DefaultComboBoxModel<>();
        for (String m : models) cbModel.addElement(m);
        modelCombo.removeActionListener(modelCombo.getActionListeners().length > 0
                ? modelCombo.getActionListeners()[0] : null);
        modelCombo.setModel(cbModel);
        if (selectedIdx >= 0 && selectedIdx < models.size()) {
            modelCombo.setSelectedIndex(selectedIdx);
        }
        modelCombo.addActionListener(ae -> onModelComboChanged());
        boolean ready = currentLifecycle == SessionLifecycle.READY;
        modelCombo.setEnabled(ready && !models.isEmpty());
    }

    /** Updates the tab title when the working directory is confirmed. */
    @Override
    public void onWorkingDirectoryChanged(File dir) {
        updateDisplayName(dir);
    }

    /** Updates the plan-file label in the status bar. */
    @Override
    public void onPlanNameChanged(String planName) {
        planLabel.setText(planName != null ? planName : "");
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns the working directory of this session, or {@code null} if not yet selected.
     *
     * @return working directory or {@code null}
     */
    public File getWorkingDirectory() {
        return model.getWorkingDirectory();
    }

    /**
     * Programmatically sets the session edit mode (e.g. {@code "acceptEdits"}).
     * No-op if the value is not a known mode or is already selected.
     *
     * @param value the edit mode value (one of {@code EDIT_MODE_VALUES})
     */
    public void setEditMode(String value) {
        int idx = editModeIndexOf(value);
        if (idx >= 0 && editModeCombo.getSelectedIndex() != idx) {
            editModeCombo.removeActionListener(editModeCombo.getActionListeners()[0]);
            editModeCombo.setSelectedIndex(idx);
            editModeCombo.addActionListener(ae -> onEditModeComboChanged());
            controller.onEditModeComboChanged(value);
        }
    }

    /**
     * Returns {@code true} if a session is currently active (controls locked).
     *
     * @return {@code true} when a process is running
     */
    public boolean isSessionActive() {
        return selectorPanel.isLocked();
    }

    /**
     * Starts a session for {@code dir} with {@code profileName} without showing any dialogs.
     *
     * <p>The profile's {@code extraCliArgs} are resolved here so that the global
     * "Extra CLI args" setting in Tools → Options is honoured when a session is
     * launched from the project context menu ("Open with Claude Code").
     * Previously passing {@code null} meant the setting was silently ignored.
     *
     * @param dir         working directory
     * @param profileName profile name, or {@code null} for Default
     */
    public void autoStart(File dir, String profileName) {
        ClaudeProfile profile = ClaudeProfileStore.findByName(profileName);
        autoStart(dir, profileName, profile.getExtraCliArgs());
    }

    public void autoStart(File dir, String profileName, String extraCliArgs) {
        autoStart(dir, profileName, extraCliArgs,
                ClaudeCodePreferences.getContextMenuSessionMode(), null);
    }

    public void autoStart(File dir, String profileName, String extraCliArgs,
                          SessionMode mode, String resumeSessionId) {
        if (dir == null || !dir.isDirectory()) return;
        selectorPanel.setPath(dir.getAbsolutePath());
        selectorPanel.setProfile(profileName);
        selectorPanel.preselectForDirectory(dir);
        selectorPanel.setExtraCliArgs(extraCliArgs);
        selectorPanel.lock();
        startSession(dir, profileName, extraCliArgs != null ? extraCliArgs : "",
                mode != null ? mode : SessionMode.NEW, resumeSessionId);
    }

    /**
     * Sends Ctrl+C to the PTY to interrupt the running prompt.
     */
    public void cancelCurrentPrompt() {
        cancelPrompt();
    }

    /** Called by Stop hook — Claude finished its turn. */
    public void onClaudeIdle() {
        controller.onClaudeIdle();
    }

    /** Called by PermissionRequest hook — triggers screen scan. */
    public void triggerPromptScan() {
        controller.triggerPromptScan();
    }

    /**
     * Returns the current edit mode string.
     *
     * @return edit mode string, or {@code null} if not set
     */
    public String getEditMode() {
        return model.getEditMode();
    }

    /**
     * Returns the name of the currently selected profile.
     *
     * @return profile name; never {@code null}
     */
    public String getSelectedProfileName() {
        return selectorPanel.getSelectedProfileName();
    }

    // -------------------------------------------------------------------------
    // Session management
    // -------------------------------------------------------------------------

    /** Fired by the selector panel when the user confirms a valid directory. */
    private void onDirectoryOpened(File dir, String profileName, String extraCliArgs,
                                   SessionMode mode, String resumeSessionId) {
        selectorPanel.lock();
        startSession(dir, profileName, extraCliArgs, mode, resumeSessionId);
    }

    private void startSession(File dir, String profileName, String extraCliArgs) {
        startSession(dir, profileName, extraCliArgs, SessionMode.NEW, null);
    }

    private void startSession(File dir, String profileName, String extraCliArgs,
                               SessionMode mode, String resumeSessionId) {
        this.activeResumeSessionId = resumeSessionId;
        Path configDir = null;
        if (!ClaudeProfile.DEFAULT_NAME.equals(profileName)) {
            ClaudeProfile profile = ClaudeProfileStore.findByName(profileName);
            if (profile != null && !profile.isDefault()) {
                configDir = ClaudeProfileStore.resolveStorageDir(
                        profile, ClaudeCodePreferences.getProfilesDir());
            }
        }
        final Path capturedConfigDir = configDir;
        controller.setHangCallback(
            (cmd, err) -> showStartError(cmd, err, model.getWorkingDirectory(), capturedConfigDir));
        updateDisplayName(dir);
        sessionTag = "[" + dir.getName() + "] ";
        JediTermWidget widget = new JediTermWidget(new NetBeansSettingsProvider());
        Color termBg = UIManager.getColor("EditorPane.background");
        if (termBg == null) termBg = UIManager.getColor("Panel.background");
        if (termBg != null) {
            widget.setBackground(termBg);
            widget.getTerminalPanel().setBackground(termBg);
        }
        showChatLayout(widget);
        try {
            controller.startProcess(dir, profileName, extraCliArgs, mode, resumeSessionId, widget);
        } catch (IOException ex) {
            LOG.log(Level.SEVERE, "Claude process failed to start in " + dir, ex);
            String command = controller.getLastAttemptedCommand();
            String errorMsg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            showStartError(command, errorMsg, dir, capturedConfigDir);
        }
    }

    private void showChatLayout(JediTermWidget widget) {
        remove(selectorPanel);
        selectorPanel.setVisible(false);
        terminalWidget = widget;

        southCardLayout = new CardLayout();
        southCard = new JPanel(southCardLayout) {
            /**
             * Returns (0, 0) for non-prompt cards so that JSplitPane can collapse
             * the bottom panel freely when the tab is docked as a narrow side panel
             * (issue #19). For the prompt card, delegates to the visible child's
             * minimum height so the divider cannot be dragged below the button column.
             */
            @Override public java.awt.Dimension getMinimumSize() {
                if (CARD_PROMPT.equals(activeCard)) {
                    for (java.awt.Component c : getComponents()) {
                        if (c.isVisible()) return new java.awt.Dimension(0, c.getMinimumSize().height);
                    }
                }
                return new java.awt.Dimension(0, 0);
            }
            @Override public java.awt.Dimension getPreferredSize() {
                for (java.awt.Component c : getComponents()) {
                    if (c.isVisible()) return c.getPreferredSize();
                }
                return super.getPreferredSize();
            }
        };

        JPanel promptCard = new JPanel(new BorderLayout());
        promptCard.add(promptPanel, BorderLayout.CENTER);

        southCard.add(promptCard,    CARD_PROMPT);
        southCard.add(choiceMenuPanel, CARD_CHOICE);
        // CARD_DIFF is added dynamically in showEmbeddedDiff()

        activeCard = CARD_PROMPT;
        southCardLayout.show(southCard, CARD_PROMPT);

        splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, widget, southCard);
        splitPane.setResizeWeight(1.0);
        splitPane.setDividerSize(5);

        final int savedBottomHeight = NbPreferences.forModule(ClaudeSessionTab.class)
                .getInt("bottomHeight", -1);
        final boolean[] savingEnabled = {false};

        splitPane.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                int total = splitPane.getHeight();
                if (total <= 0) return;
                LOG.fine(sessionTag + "[componentResized] activeCard=" + activeCard
                        + " total=" + total + " savedBottomHeight=" + savedBottomHeight);
                applyDividerForCard(activeCard, total, savedBottomHeight);
                savingEnabled[0] = true;
            }
        });
        splitPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
            if (!savingEnabled[0] || !splitPane.isEnabled()) return;
            if (CARD_CHOICE.equals(activeCard)) return;
            int total = splitPane.getHeight();
            int loc   = (int) e.getNewValue();
            if (total > 0 && loc > 0) {
                int bottom = total - splitPane.getDividerSize() - loc;
                if (bottom > 0) NbPreferences.forModule(ClaudeSessionTab.class)
                        .putInt(getSavedCardKey(), bottom);
            }
        });

        add(splitPane, BorderLayout.CENTER);
        revalidate();
        repaint();
        widget.requestFocusInWindow();
    }

    private void showSelectorLayout() {
        if (splitPane != null) {
            remove(splitPane);
            splitPane = null;
        }
        if (errorPanel != null) {
            remove(errorPanel);
            errorPanel = null;
        }
        add(selectorPanel, BorderLayout.CENTER);
        revalidate();
        repaint();
    }

    /**
     * Replaces the current layout with a diagnostics panel describing why the
     * Claude Code process could not be started.
     *
     * @param command         the command that was attempted (may be empty)
     * @param error           the error message
     * @param workingDir      the working directory that was used (may be {@code null})
     * @param claudeConfigDir the {@code CLAUDE_CONFIG_DIR} value in effect;
     *                        {@code null} means the default profile (no override)
     */
    private void showStartError(String command, String error,
                                File workingDir, Path claudeConfigDir) {
        if (splitPane != null) {
            remove(splitPane);
            splitPane = null;
        }

        JTextField dirField = new JTextField(workingDir != null ? workingDir.getAbsolutePath() : "");
        dirField.setEditable(false);
        BasicTextContextMenu.attach(dirField, BasicTextContextMenu.createReadOnly(dirField));

        JTextField cfgDirField = new JTextField(claudeConfigDir != null ? claudeConfigDir.toString() : "");
        cfgDirField.setEditable(false);
        BasicTextContextMenu.attach(cfgDirField, BasicTextContextMenu.createReadOnly(cfgDirField));

        JTextField cmdField = new JTextField(command);
        cmdField.setEditable(false);
        BasicTextContextMenu.attach(cmdField, BasicTextContextMenu.createReadOnly(cmdField));

        JTextField errField = new JTextField(error);
        errField.setEditable(false);
        BasicTextContextMenu.attach(errField, BasicTextContextMenu.createReadOnly(errField));

        JButton settingsBtn = new JButton("Settings...");
        settingsBtn.addActionListener(e -> OptionsDisplayer.getDefault().open("ClaudeCodeGUI"));

        JPanel fieldsPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0; gbc.gridy = 0; gbc.insets = new Insets(4, 0, 4, 8); gbc.anchor = GridBagConstraints.WEST;
        fieldsPanel.add(new JLabel("Working Directory:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        fieldsPanel.add(dirField, gbc);
        gbc.gridx = 0; gbc.gridy = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        JTextField cfgDirLabelField = new JTextField("CLAUDE_CONFIG_DIR");
        cfgDirLabelField.setEditable(false);
        cfgDirLabelField.setBorder(null);
        cfgDirLabelField.setBackground(null);
        cfgDirLabelField.setOpaque(false);
        BasicTextContextMenu.attach(cfgDirLabelField, BasicTextContextMenu.createReadOnly(cfgDirLabelField));
        JPanel cfgDirLabelPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        cfgDirLabelPanel.setOpaque(false);
        cfgDirLabelPanel.add(cfgDirLabelField);
        cfgDirLabelPanel.add(new JLabel(":"));
        fieldsPanel.add(cfgDirLabelPanel, gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        fieldsPanel.add(cfgDirField, gbc);
        gbc.gridx = 0; gbc.gridy = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        fieldsPanel.add(new JLabel("Command:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        fieldsPanel.add(cmdField, gbc);
        gbc.gridx = 0; gbc.gridy = 3; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        fieldsPanel.add(new JLabel("Error:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        fieldsPanel.add(errField, gbc);

        JPanel btnPanel = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 0, 0));
        btnPanel.add(settingsBtn);

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        JLabel titleLabel = new JLabel("Failed to start Claude Code");
        titleLabel.setForeground(Color.RED);
        titleLabel.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(titleLabel);

        fieldsPanel.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(fieldsPanel);

        btnPanel.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(btnPanel);

        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panel.getPreferredSize().height));

        JPanel wrapper = new JPanel();
        wrapper.setLayout(new BoxLayout(wrapper, BoxLayout.Y_AXIS));
        wrapper.add(Box.createVerticalGlue());
        wrapper.add(panel);
        wrapper.add(Box.createVerticalGlue());

        errorPanel = wrapper;
        remove(selectorPanel);
        add(errorPanel, BorderLayout.CENTER);
        revalidate();
        repaint();
    }

    /**
     * Returns the preference key used to save the divider height for the current active card.
     * Uses backward-compatible {@code "bottomHeight"} key for the prompt card.
     *
     * @return preference key string
     */
    private String getSavedCardKey() {
        return getSavedCardKeyFor(activeCard);
    }

    /**
     * Reads the saved divider height for {@code card}, falls back to sensible defaults,
     * sets the divider location, and returns the bottom height used.
     * {@code legacyPromptHeight} is the pre-migration "bottomHeight" value (pass -1 to skip).
     */
    private int applyDividerForCard(String card, int total, int legacyPromptHeight) {
        int saved = NbPreferences.forModule(ClaudeSessionTab.class)
                .getInt(getSavedCardKeyFor(card), -1);
        int bottom = computeBottomHeight(card, total, saved, legacyPromptHeight,
                southCard.getPreferredSize().height);
        int divLoc = total - splitPane.getDividerSize() - bottom;
        LOG.fine(sessionTag + "[applyDivider] card=" + card
                + " total=" + total + " saved=" + saved
                + " legacy=" + legacyPromptHeight
                + " preferred=" + southCard.getPreferredSize().height
                + " bottom=" + bottom + " divLoc=" + divLoc);
        if (divLoc >= 0) splitPane.setDividerLocation(divLoc);
        return bottom;
    }

    /**
     * Pure function: computes the south-panel height for a given card.
     * Extracted for unit testing — no Swing state access.
     *
     * @param card              active card name (CARD_PROMPT or CARD_DIFF)
     * @param total             total splitPane height
     * @param saved             persisted height (negative means not saved)
     * @param legacyPromptHeight "bottomHeight" pref value for backward-compat (negative to skip)
     * @param preferredHeight   southCard preferred height (fallback)
     */
    static int computeBottomHeight(String card, int total, int saved,
                                   int legacyPromptHeight, int preferredHeight) {
        if (saved > 0) return saved;
        if (CARD_DIFF.equals(card)) return total * 2 / 3;
        if (CARD_PROMPT.equals(card) && legacyPromptHeight > 0) return legacyPromptHeight;
        return preferredHeight;
    }

    String getSavedCardKeyFor(String card) {
        if (CARD_PROMPT.equals(card) || card == null) return "bottomHeight";
        return card + "Height";
    }

    void switchSouthCard(String card) {
        if (southCard == null || southCardLayout == null) return;
        activeCard = card;
        southCardLayout.show(southCard, card);
        // Disable only the divider when the choice menu is shown, not the whole
        // splitPane.  setEnabled(false) on the splitPane propagates to all child
        // components including the terminal widget's scrollbar, making it
        // unresponsive.  Disabling only the divider prevents the user from
        // dragging the splitter while leaving the terminal fully interactive.
        if (splitPane.getUI() instanceof javax.swing.plaf.basic.BasicSplitPaneUI ui) {
            ui.getDivider().setEnabled(!CARD_CHOICE.equals(card));
        }
        if (!CARD_CHOICE.equals(card)) {
            // restore divider for this card
            int total = splitPane.getHeight();
            LOG.fine(sessionTag + "[switchSouthCard] card=" + card + " total=" + total);
            if (total > 0) {
                applyDividerForCard(card, total, -1);
            } else {
                SwingUtilities.invokeLater(() -> {
                    int t = splitPane != null ? splitPane.getHeight() : 0;
                    if (t > 0) applyDividerForCard(activeCard, t, -1);
                });
            }
        } else {
            // lock divider for choice menu
            splitPane.validate();
            int total = splitPane.getHeight();
            if (total > 0) {
                int natural = choiceMenuPanel.getPreferredSize().height;
                splitPane.setDividerLocation(total - splitPane.getDividerSize() - natural);
            }
            LOG.fine(sessionTag + "[switchSouthCard CHOICE] total=" + splitPane.getHeight()
                    + " natural=" + choiceMenuPanel.getPreferredSize().height
                    + " choiceVisible=" + choiceMenuPanel.isVisible()
                    + " choiceBounds=" + choiceMenuPanel.getBounds()
                    + " southCardVisible=" + southCard.isVisible()
                    + " southCardBounds=" + southCard.getBounds()
                    + " divLoc=" + splitPane.getDividerLocation()
                    + " " + termScrollState());
            SwingUtilities.invokeLater(() -> LOG.fine(sessionTag + "[switchSouthCard CHOICE post-EDT]"
                    + " choiceShowing=" + choiceMenuPanel.isShowing()
                    + " choiceBounds=" + choiceMenuPanel.getBounds()
                    + " componentCount=" + choiceMenuPanel.getComponentCount()
                    + " " + termScrollState()));
        }
        southCard.revalidate();
        southCard.repaint();
        // Keep the terminal scrolled to the bottom after layout changes caused by
        // setDividerLocation() — JediTerm resets scroll position to top on resize.
        if (terminalWidget != null) {
            SwingUtilities.invokeLater(() -> {
                LOG.fine(sessionTag + "[scrollTerminalToBottom pre] " + termScrollState());
                scrollTerminalToBottom();
                LOG.fine(sessionTag + "[scrollTerminalToBottom post] " + termScrollState());
                SwingUtilities.invokeLater(() ->
                    LOG.fine(sessionTag + "[scrollTerminalToBottom post-post] " + termScrollState()));
            });
        }
    }

    /** Scrolls the JediTerm widget to show the latest output (bottom of scrollback). */
    private void scrollTerminalToBottom() {
        if (terminalWidget == null) return;
        scrollBottomOfComponents(terminalWidget);
    }

    private void scrollBottomOfComponents(java.awt.Container c) {
        for (java.awt.Component child : c.getComponents()) {
            if (child instanceof javax.swing.JScrollBar bar
                    && bar.getOrientation() == javax.swing.JScrollBar.VERTICAL) {
                bar.setValue(bar.getMaximum() - bar.getVisibleAmount());
                return;
            }
            if (child instanceof java.awt.Container sub) {
                scrollBottomOfComponents(sub);
            }
        }
    }

    /**
     * Handles the Save &amp; Switch dialog confirmation.
     *
     * <p>Stops the current session (writing a custom-title if name changed),
     * then either closes the tab or restarts in the same tab with the new mode.
     *
     * @param sessionName new name for the current session (written to JSONL)
     * @param mode        what to do after stopping
     * @param resumeId    session ID to resume (only used for RESUME_SPECIFIC)
     */
    /**
     * Opens the Save &amp; Switch dialog with the given initial mode pre-selected.
     *
     * @param initialMode the session mode to pre-select in the dialog
     */
    public void openSwitchDialog(SessionMode initialMode) {
        File workingDir = model.getWorkingDirectory();
        if (workingDir == null) return;

        String profileName = selectorPanel.getSelectedProfileName();
        Path claudeConfigDir = null;
        if (profileName != null && !io.github.nbclaudecodegui.settings.ClaudeProfile.DEFAULT_NAME.equals(profileName)) {
            io.github.nbclaudecodegui.settings.ClaudeProfile profile =
                    io.github.nbclaudecodegui.settings.ClaudeProfileStore.findByName(profileName);
            if (profile != null) {
                claudeConfigDir = io.github.nbclaudecodegui.settings.ClaudeProfileStore.resolveStorageDir(
                        profile, ClaudeCodePreferences.getProfilesDir());
            }
        }

        io.github.nbclaudecodegui.model.SavedSession recent =
                io.github.nbclaudecodegui.process.ClaudeSessionStore.findMostRecent(
                        workingDir.toPath(), claudeConfigDir);
        long procStart = controller.getProcessStartedAt();
        // If a process is running and the most recent JSONL predates it, it's an OLD session —
        // not the currently running one (which hasn't written its JSONL yet)
        boolean isCurrentSession = recent != null
                && (procStart == 0 || (recent.lastAt() != null
                        && recent.lastAt().toEpochMilli() >= procStart));
        String currentName = isCurrentSession ? recent.displayName() : "";
        String currentId   = isCurrentSession ? recent.sessionId() : null;

        final ClaudeSessionTab self = this;
        final Path configDir = claudeConfigDir;
        java.awt.Frame mainFrame = org.openide.windows.WindowManager.getDefault().getMainWindow();
        SaveAndSwitchDialog dialog = new SaveAndSwitchDialog(
                mainFrame,
                currentName,
                currentId,
                workingDir.toPath(),
                configDir,
                initialMode,
                (name, mode, resumeId) ->
                        SwingUtilities.invokeLater(() -> self.onSaveAndSwitch(name, mode, resumeId))
        );
        dialog.setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setVisible(true);
    }

    public void onSaveAndSwitch(String sessionName, SessionMode mode, String resumeId) {
        File dir = model.getWorkingDirectory();
        String profileName = selectorPanel.getSelectedProfileName();
        String extraCliArgs = selectorPanel.getExtraCliArgs();

        // Stop and rename
        controller.stopAndRename(sessionName);
        cleanupChatLayout();

        if (mode == SessionMode.CLOSE_ONLY) {
            doCloseTab();
            return;
        }

        if (mode == SessionMode.RESTART_ADVANCED) {
            return; // selector already shown by cleanupChatLayout()
        }

        // Restart with new mode
        if (dir != null) {
            startSession(dir, profileName, extraCliArgs, mode, resumeId);
        }
    }

    /** Closes this tab. Protected to allow overriding in tests. */
    protected void doCloseTab() {
        close();
    }

    private void stopProcess() {
        controller.stopProcess();
        cleanupChatLayout();
    }

    /** Tears down the chat UI layout after the PTY has already been stopped. */
    private void cleanupChatLayout() {
        if (terminalWidget != null) {
            terminalWidget.close();
            terminalWidget = null;
        }
        choiceMenuPanel.dismiss();
        promptPanel.reset();
        if (pendingDiffOnClose != null) {
            try { pendingDiffOnClose.run(); } catch (Exception ex) { /* ignore */ }
            pendingDiffOnClose = null;
        }
        if (splitPane != null) {
            remove(splitPane);
            splitPane       = null;
            southCard       = null;
            southCardLayout = null;
            activeCard      = null;
            currentDiffCard = null;
        }
        if (errorPanel != null) {
            remove(errorPanel);
            errorPanel = null;
        }
        selectorPanel.setVisible(true);
        selectorPanel.unlock();
        add(selectorPanel, BorderLayout.CENTER);
        statusBar.setVisible(false);
        currentLifecycle = null;
        stateLabel.setText("Ready");
        versionLabel.setText("");
        planLabel.setText("");
        revalidate();
        repaint();
    }

    // -------------------------------------------------------------------------
    // Input delegation
    // -------------------------------------------------------------------------

    private void sendPrompt(String text) {
        model.addPromptToHistory(text);
        controller.sendPrompt(text);
    }

    private void cancelPrompt() {
        controller.cancelPrompt();
    }

    private void requestFocusOnInput() {
        if (CARD_CHOICE.equals(activeCard) || CARD_DIFF.equals(activeCard)) return;
        if (promptPanel.isVisible()) {
            promptPanel.requestFocusOnInputArea();
        } else if (terminalWidget != null) {
            terminalWidget.requestFocusInWindow();
        }
    }

    // -------------------------------------------------------------------------
    // Embedded diff support
    // -------------------------------------------------------------------------

    /**
     * Shows an embedded diff inside the session tab's south area.
     *
     * @param outsideProject  whether the file is outside the project
     * @param outsideWarning  warning text to show when outside project
     * @param toolbar         the diff view's toolbar (may be empty)
     * @param diffComponent   the diff view component
     * @param permPanel       the permission panel
     * @param onTabClose      called if the session tab is closed before a decision
     * @return {@code true} if the diff was shown; {@code false} if the session is not active
     */
    public boolean showEmbeddedDiff(boolean outsideProject, String outsideWarning,
            javax.swing.JToolBar toolbar, java.awt.Component diffComponent,
            FileDiffPermissionPanel permPanel, Runnable onTabClose) {
        if (southCard == null) return false;

        if (toolbar != null) {
            toolbar.setFloatable(false);
        }
        if (outsideProject) {
            permPanel.showWarning(outsideWarning);
        }

        if (currentDiffCard != null) {
            southCard.remove(currentDiffCard);
            currentDiffCard = null;
        }
        JPanel diffCard = new JPanel(new BorderLayout());
        if (toolbar != null) {
            diffCard.add(toolbar, BorderLayout.NORTH);
        }
        diffCard.add(diffComponent, BorderLayout.CENTER);
        diffCard.add(permPanel,     BorderLayout.SOUTH);

        currentDiffCard = diffCard;
        southCard.add(currentDiffCard, CARD_DIFF);

        pendingDiffOnClose = onTabClose;
        switchSouthCard(CARD_DIFF);
        revalidate();
        repaint();
        SwingUtilities.invokeLater(permPanel::requestAcceptFocus);
        return true;
    }

    /**
     * Hides the embedded diff and switches back to the prompt card.
     */
    public void hideEmbeddedDiff() {
        pendingDiffOnClose = null;
        if (southCard == null) return;
        switchSouthCard(CARD_PROMPT);
        if (currentDiffCard != null) {
            southCard.remove(currentDiffCard);
            currentDiffCard = null;
        }
        revalidate();
        repaint();
    }

    // -------------------------------------------------------------------------
    // Status bar helpers
    // -------------------------------------------------------------------------

    private void onEditModeComboChanged() {
        int idx = editModeCombo.getSelectedIndex();
        if (idx < 0 || idx >= EDIT_MODE_VALUES.length) return;
        controller.onEditModeComboChanged(EDIT_MODE_VALUES[idx]);
    }

    private void onModelComboChanged() {
        int idx = modelCombo.getSelectedIndex();
        if (idx < 0) return;
        controller.switchModel(idx);
    }

    private int editModeIndexOf(String value) {
        for (int i = 0; i < EDIT_MODE_VALUES.length; i++) {
            if (EDIT_MODE_VALUES[i].equals(value)) return i;
        }
        return -1;
    }

    private static JPanel makeSep() {
        JPanel sep = new JPanel();
        sep.setLayout(new BoxLayout(sep, BoxLayout.X_AXIS));
        sep.setOpaque(false);
        JPanel dark = new JPanel();
        dark.setOpaque(true);
        dark.setBackground(UIManager.getColor("controlShadow"));
        dark.setMaximumSize(new Dimension(1, 16));
        dark.setPreferredSize(new Dimension(1, 16));
        JPanel light = new JPanel();
        light.setOpaque(true);
        light.setBackground(UIManager.getColor("controlHighlight"));
        light.setMaximumSize(new Dimension(1, 16));
        light.setPreferredSize(new Dimension(1, 16));
        sep.add(dark);
        sep.add(light);
        sep.setMaximumSize(new Dimension(2, 16));
        sep.setPreferredSize(new Dimension(2, 16));
        return sep;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Collects scroll-related state of the JediTerm terminal widget for diagnostics. */
    private String termScrollState() {
        if (terminalWidget == null) return "terminalWidget=null";
        StringBuilder sb = new StringBuilder();
        sb.append("termSize=").append(terminalWidget.getSize());
        sb.append(" termEnabled=").append(terminalWidget.isEnabled());
        walkScrollComponents(terminalWidget, sb, 0);
        return sb.toString();
    }

    private void walkScrollComponents(java.awt.Container c, StringBuilder sb, int depth) {
        for (java.awt.Component child : c.getComponents()) {
            if (child instanceof javax.swing.JScrollBar bar) {
                sb.append(" scrollBar[enabled=").append(bar.isEnabled())
                  .append(",val=").append(bar.getValue())
                  .append(",min=").append(bar.getMinimum())
                  .append(",max=").append(bar.getMaximum())
                  .append(",vis=").append(bar.getVisibleAmount())
                  .append("]");
            }
            if (child instanceof java.awt.Container sub && depth < 5) {
                walkScrollComponents(sub, sb, depth + 1);
            }
        }
    }

    private List<String> getScreenLines() {
        if (terminalWidget == null) return java.util.Collections.emptyList();
        try {
            TerminalTextBuffer buf = terminalWidget.getTerminalTextBuffer();
            return buf.getScreenBuffer().getLineTexts();
        } catch (NullPointerException e) {
            // JediTerm's LinesStorage may return null entries during terminal
            // initialization. Return empty so pollScreenState retries next tick.
            return java.util.Collections.emptyList();
        }
    }

    private void updateDisplayName(File dir) {
        String name = ClaudeSessionSelectorPanel.resolveTabLabel(dir);
        setDisplayName(name);
        setToolTipText(dir != null ? dir.getAbsolutePath() : "New Claude Code session");
    }
}
