package io.github.nbclaudecodegui.settings;

import io.github.nbclaudecodegui.model.FavoriteEntry;
import io.github.nbclaudecodegui.model.PromptFavoritesStore;
import io.github.nbclaudecodegui.ui.FavoritesPanel;
import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.table.AbstractTableModel;

/**
 * Settings panel displayed inside Tools → Options → Claude Code.
 *
 * <p>Contains three tabs:
 * <ul>
 *   <li><b>General</b> — Claude CLI path, MCP port, send/newline key bindings, debug mode</li>
 *   <li><b>Profiles</b> — named connection profiles with auth, proxy, and extra env vars
 *       (see {@link ClaudeProfilesPanel})</li>
 *   <li><b>Favorites</b> — manage global favorites (text, shortcut, ordering)</li>
 * </ul>
 *
 * <p>{@link #load()} and {@link #store()} delegate to all tabs.
 */
public final class ClaudeCodeOptionsPanel extends JPanel {

    private static final String[] CHOICE_MENU_FOCUS_VALUES = {
        ClaudeCodePreferences.CHOICE_MENU_GRAB_FOCUS,
        ClaudeCodePreferences.CHOICE_MENU_SHOW_NO_FOCUS,
        ClaudeCodePreferences.CHOICE_MENU_HIDE_MENU
    };
    private static final String[] CHOICE_MENU_FOCUS_LABELS = {
        "Grab focus (default)", "Show without grabbing focus", "Hide menu"
    };

    private static final String[] KEY_VALUES = {
        ClaudeCodePreferences.ENTER,
        ClaudeCodePreferences.SHIFT_ENTER,
        ClaudeCodePreferences.CTRL_ENTER,
        ClaudeCodePreferences.ALT_ENTER
    };
    private static final String[] KEY_LABELS = {
        "Enter", "Shift+Enter", "Ctrl+Enter", "Alt+Enter"
    };

    /** Field for the claude executable path. */
    private JTextField executablePathField;
    /** Spinner for the MCP server port. */
    private JSpinner mcpPortSpinner;
    /** Spinner for the maximum number of history entries. */
    private JSpinner historyMaxDepthSpinner;
    /** Spinner for the history time-to-live in days. */
    private JSpinner historyTtlDaysSpinner;
    /** Checkbox to enable debug logging. */
    private javax.swing.JCheckBox debugCheckBox;
    /** Checkbox to open diff in a separate tab. */
    private javax.swing.JCheckBox diffInSessionCheck;
    /** Checkbox to show markdown preview for .md files in diff. */
    private javax.swing.JCheckBox mdPreviewInDiffCheck;
    /** Checkbox for context-menu session mode (checked = New, unchecked = Continue last). */
    private javax.swing.JCheckBox startNewSessionCheck;
    /** Spinner for the maximum number of sessions shown in the session list. */
    private JSpinner sessionListLimitSpinner;
    /** Dropdown for the dock position of the Claude Code session tab. */
    private javax.swing.JComboBox<String> dockModeCombo;
    /** Dropdown for the dock position of the Markdown Preview tab. */
    private javax.swing.JComboBox<String> mdPreviewDockModeCombo;
    /** Label for the Markdown Preview dock position combo. */
    private javax.swing.JLabel mdPreviewDockModeLabel;
    /** Dropdown for the dock position of the File Diff tab. */
    private javax.swing.JComboBox<String> fileDiffDockModeCombo;
    /** Label for the File Diff dock position combo. */
    private javax.swing.JLabel fileDiffDockModeLabel;
    /** Dropdown for the choice menu focus behavior. */
    private javax.swing.JComboBox<String> choiceMenuFocusCombo;
    /** Spinner for the hang-detection timeout in seconds (0 = disabled). */
    private JSpinner hangTimeoutSpinner;
    /** Checkbox to enable MCP integration (pass --mcp-config flag). */
    private javax.swing.JCheckBox mcpEnabledCheckBox;

    /** send-key radio buttons: value → button */
    private final Map<String, JRadioButton> sendRadios = new LinkedHashMap<>();
    /** newline-key radio buttons: value → button */
    private final Map<String, JRadioButton> newlineRadios = new LinkedHashMap<>();

    /** Button group for the send-key radios. */
    private final ButtonGroup sendGroup    = new ButtonGroup();
    /** Button group for the newline-key radios. */
    private final ButtonGroup newlineGroup = new ButtonGroup();

    /** Profiles tab panel. */
    private final ClaudeProfilesPanel profilesPanel = new ClaudeProfilesPanel();

    /** Favorites tab panel. */
    private final GlobalFavoritesPanel favoritesPanel = new GlobalFavoritesPanel();

    /**
     * Creates the panel and initialises all UI components.
     */
    public ClaudeCodeOptionsPanel() {
        initComponents();
    }

    private void initComponents() {
        setLayout(new BorderLayout());

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("General", buildGeneralPanel());
        tabs.addTab("Advanced", buildAdvancedPanel());
        tabs.addTab("Favorites", favoritesPanel);
        tabs.addTab("Profiles", profilesPanel);
        add(tabs, BorderLayout.CENTER);
    }

    /**
     * Builds the General tab containing the original settings controls.
     *
     * @return the General tab panel
     */
    private JPanel buildGeneralPanel() {
        JPanel form = new JPanel(new GridBagLayout());
        int row = 0;

        // --- executable path ---
        form.add(new JLabel("Claude CLI path:"), gbc(0, row, false));
        executablePathField = new JTextField(30);
        executablePathField.setToolTipText("Leave empty to detect 'claude' from PATH");
        form.add(executablePathField, gbcFill(1, row));
        JButton browseButton = new JButton("Browse\u2026");
        browseButton.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                executablePathField.setText(
                        chooser.getSelectedFile().getAbsolutePath());
            }
        });
        form.add(browseButton, gbc(2, row, false));
        row++;

        // --- MCP port ---
        form.add(new JLabel("MCP server port:"), gbc(0, row, false));
        mcpPortSpinner = new JSpinner(new SpinnerNumberModel(
                ClaudeCodePreferences.DEFAULT_MCP_PORT, 1024, 65535, 1));
        mcpPortSpinner.setToolTipText("Port for the NetBeans MCP SSE server (restart required)");
        form.add(mcpPortSpinner, gbc(1, row, false));
        row++;

        // --- history max depth ---
        form.add(new JLabel("History max depth:"), gbc(0, row, false));
        historyMaxDepthSpinner = new JSpinner(new SpinnerNumberModel(
                ClaudeCodePreferences.DEFAULT_HISTORY_MAX_DEPTH, 1, 2000, 10));
        historyMaxDepthSpinner.setToolTipText("Maximum number of history entries to keep per project");
        form.add(historyMaxDepthSpinner, gbc(1, row, false));
        row++;

        // --- history TTL ---
        form.add(new JLabel("History TTL (days, 0 = keep forever):"), gbc(0, row, false));
        historyTtlDaysSpinner = new JSpinner(new SpinnerNumberModel(
                ClaudeCodePreferences.DEFAULT_HISTORY_TTL_DAYS, 0, 3650, 1));
        historyTtlDaysSpinner.setToolTipText("Delete history entries older than this many days (0 = keep forever)");
        form.add(historyTtlDaysSpinner, gbc(1, row, false));
        row++;

        // --- send key ---
        JPanel sendPanel = buildKeyPanel("Send prompt:", sendRadios, sendGroup);
        GridBagConstraints sendGbc = new GridBagConstraints();
        sendGbc.gridx = 0; sendGbc.gridy = row;
        sendGbc.gridwidth = 3;
        sendGbc.anchor = GridBagConstraints.WEST;
        sendGbc.insets = new Insets(12, 8, 4, 8);
        form.add(sendPanel, sendGbc);
        row++;

        // --- newline key ---
        JPanel newlinePanel = buildKeyPanel("Insert newline:", newlineRadios, newlineGroup);
        GridBagConstraints nlGbc = new GridBagConstraints();
        nlGbc.gridx = 0; nlGbc.gridy = row;
        nlGbc.gridwidth = 3;
        nlGbc.anchor = GridBagConstraints.WEST;
        nlGbc.insets = new Insets(4, 8, 4, 8);
        form.add(newlinePanel, nlGbc);
        row++;

        // mutual exclusion: selecting in one group disables that option in the other
        for (int i = 0; i < KEY_VALUES.length; i++) {
            final String val = KEY_VALUES[i];
            sendRadios.get(val).addActionListener(e -> syncExclusion(val, true));
            newlineRadios.get(val).addActionListener(e -> syncExclusion(val, false));
        }

        // --- context-menu session mode ---
        startNewSessionCheck = new javax.swing.JCheckBox("Start new session when opening with Claude");
        GridBagConstraints cmGbc = new GridBagConstraints();
        cmGbc.gridx = 0; cmGbc.gridy = row;
        cmGbc.gridwidth = 3;
        cmGbc.anchor = GridBagConstraints.WEST;
        cmGbc.insets = new Insets(4, 8, 4, 8);
        form.add(startNewSessionCheck, cmGbc);
        row++;

        // --- session list limit ---
        form.add(new JLabel("Session list limit:"), gbc(0, row, false));
        sessionListLimitSpinner = new JSpinner(new SpinnerNumberModel(
                ClaudeCodePreferences.DEFAULT_SESSION_LIST_LIMIT, 1, 500, 5));
        sessionListLimitSpinner.setToolTipText("Maximum number of sessions shown in the session list");
        form.add(sessionListLimitSpinner, gbc(1, row, false));
        row++;

        // --- session tab dock position ---
        form.add(new JLabel("Session tab dock position:"), gbc(0, row, false));
        dockModeCombo = new javax.swing.JComboBox<>(
                DockMode.labels(ClaudeCodePreferences.DEFAULT_SESSION_DOCK_MODE));
        dockModeCombo.setToolTipText("Where to dock the Claude Code session tab when opening it");
        form.add(dockModeCombo, gbc(1, row, false));
        row++;

        // --- diff in session ---
        diffInSessionCheck = new javax.swing.JCheckBox("Open file diff in a separate tab");
        GridBagConstraints diffGbc = new GridBagConstraints();
        diffGbc.gridx = 0; diffGbc.gridy = row;
        diffGbc.gridwidth = 3;
        diffGbc.anchor = GridBagConstraints.WEST;
        diffGbc.insets = new Insets(4, 8, 4, 8);
        form.add(diffInSessionCheck, diffGbc);
        diffInSessionCheck.addActionListener(e -> updateFileDiffDockEnabled());
        row++;

        // --- file diff dock mode (enabled only when diff is in separate tab) ---
        fileDiffDockModeLabel = new javax.swing.JLabel("File diff tab dock position:");
        form.add(fileDiffDockModeLabel, gbc(0, row, false));
        fileDiffDockModeCombo = new javax.swing.JComboBox<>(
                DockMode.labels(ClaudeCodePreferences.DEFAULT_FILE_DIFF_DOCK_MODE));
        fileDiffDockModeCombo.setToolTipText(
                "Where to dock the File Diff tab when opening it in a separate tab");
        form.add(fileDiffDockModeCombo, gbc(1, row, false));
        row++;

        // --- md preview in diff ---
        mdPreviewInDiffCheck = new javax.swing.JCheckBox("Show markdown preview for .md files in diff");
        GridBagConstraints mdGbc = new GridBagConstraints();
        mdGbc.gridx = 0; mdGbc.gridy = row;
        mdGbc.gridwidth = 3;
        mdGbc.anchor = GridBagConstraints.WEST;
        mdGbc.insets = new Insets(4, 8, 4, 8);
        form.add(mdPreviewInDiffCheck, mdGbc);
        row++;

        // --- markdown preview dock mode ---
        mdPreviewDockModeLabel = new javax.swing.JLabel("Markdown preview dock position:");
        form.add(mdPreviewDockModeLabel, gbc(0, row, false));
        mdPreviewDockModeCombo = new javax.swing.JComboBox<>(
                DockMode.labels(ClaudeCodePreferences.DEFAULT_MARKDOWN_PREVIEW_DOCK_MODE));
        mdPreviewDockModeCombo.setToolTipText(
                "Where to dock the Markdown Preview tab when first opened");
        form.add(mdPreviewDockModeCombo, gbc(1, row, false));
        row++;

        // --- choice menu focus mode ---
        form.add(new JLabel("Choice menu focus:"), gbc(0, row, false));
        choiceMenuFocusCombo = new javax.swing.JComboBox<>(CHOICE_MENU_FOCUS_LABELS);
        choiceMenuFocusCombo.setToolTipText("Controls whether the choice menu grabs keyboard focus when it appears");
        form.add(choiceMenuFocusCombo, gbc(1, row, false));
        row++;

        // spacer
        GridBagConstraints spacer = new GridBagConstraints();
        spacer.gridx = 0; spacer.gridy = row;
        spacer.gridwidth = 3; spacer.weighty = 1.0;
        spacer.fill = GridBagConstraints.VERTICAL;
        form.add(new JPanel(), spacer);

        return form;
    }

    /**
     * Builds the Advanced tab containing debug and hang-timeout settings.
     *
     * @return the Advanced tab panel
     */
    private JPanel buildAdvancedPanel() {
        JPanel form = new JPanel(new GridBagLayout());
        int row = 0;

        // --- debug mode ---
        debugCheckBox = new javax.swing.JCheckBox("Debug mode (log all claude I/O to NetBeans log and output area)");
        GridBagConstraints dbgGbc = new GridBagConstraints();
        dbgGbc.gridx = 0; dbgGbc.gridy = row;
        dbgGbc.gridwidth = 2;
        dbgGbc.anchor = GridBagConstraints.WEST;
        dbgGbc.insets = new Insets(8, 8, 4, 8);
        form.add(debugCheckBox, dbgGbc);
        row++;

        // --- hang timeout ---
        form.add(new JLabel("Hang timeout (seconds, 0=disabled):"), gbc(0, row, false));
        hangTimeoutSpinner = new JSpinner(new SpinnerNumberModel(
                ClaudeCodePreferences.DEFAULT_HANG_TIMEOUT_SECONDS, 0, 3600, 5));
        hangTimeoutSpinner.setToolTipText("Kill the process if no PTY output is received within this many seconds after launch (0 = disabled)");
        GridBagConstraints hangSpinnerGbc = gbc(1, row, false);
        hangSpinnerGbc.weightx = 1.0;
        form.add(hangTimeoutSpinner, hangSpinnerGbc);
        row++;

        // --- MCP enabled ---
        mcpEnabledCheckBox = new javax.swing.JCheckBox("Enable MCP integration");
        GridBagConstraints mcpGbc = new GridBagConstraints();
        mcpGbc.gridx = 0; mcpGbc.gridy = row;
        mcpGbc.gridwidth = 2;
        mcpGbc.anchor = GridBagConstraints.WEST;
        mcpGbc.insets = new Insets(12, 8, 0, 8);
        form.add(mcpEnabledCheckBox, mcpGbc);
        row++;

        JLabel mcpDesc = new JLabel("<html><small>When disabled, the --mcp-config flag is not passed to claude. Hooks are always configured.</small></html>");
        GridBagConstraints mcpDescGbc = new GridBagConstraints();
        mcpDescGbc.gridx = 0; mcpDescGbc.gridy = row;
        mcpDescGbc.gridwidth = 2;
        mcpDescGbc.anchor = GridBagConstraints.WEST;
        mcpDescGbc.insets = new Insets(0, 28, 4, 8);
        form.add(mcpDesc, mcpDescGbc);
        row++;

        // spacer
        GridBagConstraints spacer = new GridBagConstraints();
        spacer.gridx = 0; spacer.gridy = row;
        spacer.gridwidth = 2; spacer.weighty = 1.0;
        spacer.fill = GridBagConstraints.VERTICAL;
        form.add(new JPanel(), spacer);

        return form;
    }

    private JPanel buildKeyPanel(String title,
            Map<String, JRadioButton> radios, ButtonGroup group) {
        JPanel panel = new JPanel(new java.awt.FlowLayout(
                java.awt.FlowLayout.LEFT, 8, 0));
        panel.setBorder(BorderFactory.createTitledBorder(title));
        for (int i = 0; i < KEY_VALUES.length; i++) {
            JRadioButton rb = new JRadioButton(KEY_LABELS[i]);
            radios.put(KEY_VALUES[i], rb);
            group.add(rb);
            panel.add(rb);
        }
        return panel;
    }

    /**
     * Called when a radio button is selected: disables that value in the
     * opposite group (and re-enables the previously-disabled one).
     *
     * @param selectedValue the just-selected key value
     * @param inSendGroup   {@code true} if the event came from the send group
     */
    private void syncExclusion(String selectedValue, boolean inSendGroup) {
        Map<String, JRadioButton> opposite =
                inSendGroup ? newlineRadios : sendRadios;

        // re-enable all in opposite group first
        opposite.values().forEach(rb -> rb.setEnabled(true));

        // disable the one that matches the selected value
        JRadioButton toDisable = opposite.get(selectedValue);
        if (toDisable != null) {
            toDisable.setEnabled(false);
            // if it was selected, move selection to the first available option
            if (toDisable.isSelected()) {
                ButtonGroup grp = inSendGroup ? newlineGroup : sendGroup;
                for (JRadioButton rb : opposite.values()) {
                    if (rb.isEnabled()) {
                        rb.setSelected(true);
                        grp.setSelected(rb.getModel(), true);
                        break;
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // load / store / valid
    // -------------------------------------------------------------------------

    /**
     * Loads current preference values into the UI controls (both tabs).
     */
    void load() {
        executablePathField.setText(
                ClaudeCodePreferences.getClaudeExecutablePath());
        mcpPortSpinner.setValue(ClaudeCodePreferences.getMcpPort());
        historyMaxDepthSpinner.setValue(ClaudeCodePreferences.getHistoryMaxDepth());
        historyTtlDaysSpinner.setValue(ClaudeCodePreferences.getHistoryTtlDays());
        debugCheckBox.setSelected(ClaudeCodePreferences.isDebugMode());
        diffInSessionCheck.setSelected(
                ClaudeCodePreferences.isOpenDiffInSeparateTab());
        mdPreviewInDiffCheck.setSelected(ClaudeCodePreferences.isMdPreviewInDiff());
        startNewSessionCheck.setSelected(
                ClaudeCodePreferences.getContextMenuSessionMode()
                        == io.github.nbclaudecodegui.model.SessionMode.NEW);
        sessionListLimitSpinner.setValue(ClaudeCodePreferences.getSessionListLimit());
        dockModeCombo.setSelectedIndex(ClaudeCodePreferences.getSessionDockMode().ordinal());
        mdPreviewDockModeCombo.setSelectedIndex(
                ClaudeCodePreferences.getMarkdownPreviewDockMode().ordinal());
        fileDiffDockModeCombo.setSelectedIndex(
                ClaudeCodePreferences.getFileDiffDockMode().ordinal());
        updateFileDiffDockEnabled();
        String focusMode = ClaudeCodePreferences.getChoiceMenuFocusMode();
        int focusIdx = java.util.Arrays.asList(CHOICE_MENU_FOCUS_VALUES).indexOf(focusMode);
        choiceMenuFocusCombo.setSelectedIndex(focusIdx >= 0 ? focusIdx : 0);
        hangTimeoutSpinner.setValue(ClaudeCodePreferences.getHangTimeoutSeconds());
        mcpEnabledCheckBox.setSelected(ClaudeCodePreferences.isMcpEnabled());

        String sendVal    = ClaudeCodePreferences.getSendKey();
        String newlineVal = ClaudeCodePreferences.getNewlineKey();

        selectRadio(sendRadios,    sendGroup,    sendVal);
        selectRadio(newlineRadios, newlineGroup, newlineVal);

        // apply mutual exclusion for the loaded values
        syncExclusion(sendVal, true);
        syncExclusion(newlineVal, false);

        profilesPanel.load();
    }

    /**
     * Persists the values currently shown in the UI controls to preferences
     * (both tabs).
     */
    void store() {
        ClaudeCodePreferences.setClaudeExecutablePath(
                executablePathField.getText().trim());
        ClaudeCodePreferences.setMcpPort((Integer) mcpPortSpinner.getValue());
        ClaudeCodePreferences.setHistoryMaxDepth((Integer) historyMaxDepthSpinner.getValue());
        ClaudeCodePreferences.setHistoryTtlDays((Integer) historyTtlDaysSpinner.getValue());
        ClaudeCodePreferences.setDebugMode(debugCheckBox.isSelected());
        ClaudeCodePreferences.setOpenDiffInSeparateTab(diffInSessionCheck.isSelected());
        ClaudeCodePreferences.setMdPreviewInDiff(mdPreviewInDiffCheck.isSelected());
        ClaudeCodePreferences.setContextMenuSessionMode(
                startNewSessionCheck.isSelected()
                        ? io.github.nbclaudecodegui.model.SessionMode.NEW
                        : io.github.nbclaudecodegui.model.SessionMode.CONTINUE_LAST);
        ClaudeCodePreferences.setSessionListLimit((Integer) sessionListLimitSpinner.getValue());
        ClaudeCodePreferences.setSessionDockMode(dockModeFromCombo(dockModeCombo));
        ClaudeCodePreferences.setMarkdownPreviewDockMode(dockModeFromCombo(mdPreviewDockModeCombo));
        ClaudeCodePreferences.setFileDiffDockMode(dockModeFromCombo(fileDiffDockModeCombo));
        int focusSel = choiceMenuFocusCombo.getSelectedIndex();
        ClaudeCodePreferences.setChoiceMenuFocusMode(
                focusSel >= 0 && focusSel < CHOICE_MENU_FOCUS_VALUES.length
                        ? CHOICE_MENU_FOCUS_VALUES[focusSel]
                        : ClaudeCodePreferences.DEFAULT_CHOICE_MENU_FOCUS_MODE);
        ClaudeCodePreferences.setHangTimeoutSeconds((Integer) hangTimeoutSpinner.getValue());
        ClaudeCodePreferences.setMcpEnabled(mcpEnabledCheckBox.isSelected());
        ClaudeCodePreferences.setSendKey(selectedValue(sendRadios));
        ClaudeCodePreferences.setNewlineKey(selectedValue(newlineRadios));

        profilesPanel.store();
    }

    /**
     * Validates the current panel state.
     *
     * @return {@code true} — the panel is always valid
     */
    boolean valid() {
        return true;
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private static void selectRadio(Map<String, JRadioButton> radios,
            ButtonGroup group, String value) {
        JRadioButton rb = radios.get(value);
        if (rb != null) {
            rb.setSelected(true);
            group.setSelected(rb.getModel(), true);
        }
    }

    private static String selectedValue(Map<String, JRadioButton> radios) {
        for (Map.Entry<String, JRadioButton> e : radios.entrySet()) {
            if (e.getValue().isSelected()) return e.getKey();
        }
        return ClaudeCodePreferences.DEFAULT_SEND_KEY;
    }

    private static DockMode dockModeFromCombo(javax.swing.JComboBox<?> combo) {
        int sel = combo.getSelectedIndex();
        DockMode[] vals = DockMode.values();
        return (sel >= 0 && sel < vals.length) ? vals[sel] : DockMode.EDITOR;
    }

    private void updateFileDiffDockEnabled() {
        boolean sep = diffInSessionCheck.isSelected();
        fileDiffDockModeLabel.setEnabled(sep);
        fileDiffDockModeCombo.setEnabled(sep);
    }

    private static GridBagConstraints gbc(int x, int y, boolean fill) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = x; c.gridy = y;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(8, x == 0 ? 8 : 0, 4, x == 2 ? 8 : 4);
        return c;
    }

    private static GridBagConstraints gbcFill(int x, int y) {
        GridBagConstraints c = gbc(x, y, true);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        return c;
    }

    // -------------------------------------------------------------------------
    // Favorites tab
    // -------------------------------------------------------------------------

    /**
     * Panel for managing global favorites (shown in the Favorites settings tab).
     * Extends {@link FavoritesPanel} and adds Add/Delete buttons for global entries.
     */
    private final class GlobalFavoritesPanel extends FavoritesPanel {

        GlobalFavoritesPanel() {
            super(PromptFavoritesStore.getInstance(
                    java.nio.file.Path.of(System.getProperty("user.home"))));

            JButton addBtn      = new JButton("Add");
            JButton editBtn     = new JButton("Edit");
            JButton shortcutBtn = new JButton("Assign Shortcut");
            JButton upBtn       = new JButton("\u2191");
            JButton downBtn     = new JButton("\u2193");
            JButton deleteBtn   = new JButton("Delete");

            addBtn.addActionListener(e -> doAdd());
            editBtn.addActionListener(e -> doEdit(this));
            shortcutBtn.addActionListener(e -> doAssignShortcut(this));
            upBtn.addActionListener(e -> doMoveGlobal(-1));
            downBtn.addActionListener(e -> doMoveGlobal(1));
            deleteBtn.addActionListener(e -> doDelete());

            addButton(addBtn);
            addButton(editBtn);
            addButton(shortcutBtn);
            addButton(upBtn);
            addButton(downBtn);
            addButton(deleteBtn);
        }

        @Override
        protected List<FavoriteEntry> loadEntries() {
            return store.getGlobal();
        }

        @Override
        protected AbstractTableModel buildModel(List<FavoriteEntry> entries) {
            return new GlobalFavTableModel(entries);
        }

        @Override
        protected void configureColumns(javax.swing.JTable t) {
            if (t.getColumnCount() < 3) return;
            t.getColumnModel().getColumn(0).setMaxWidth(30);
            t.getColumnModel().getColumn(2).setPreferredWidth(150);
            t.getColumnModel().getColumn(2).setMaxWidth(220);
        }

        private void doAdd() {
            String text = JOptionPane.showInputDialog(
                    GlobalFavoritesPanel.this.getTopLevelAncestor(),
                    "Enter favorite text:", "Add Global Favorite", JOptionPane.PLAIN_MESSAGE);
            if (text == null || text.isBlank()) return;
            store.addGlobal(FavoriteEntry.ofGlobal(text.trim()));
            refreshTable();
        }

        private void doDelete() {
            List<Integer> checked = checkedRows();
            if (checked.isEmpty()) {
                int row = table.getSelectedRow();
                if (row >= 0) checked = List.of(row);
            }
            List<FavoriteEntry> toDelete = new ArrayList<>();
            for (int row : checked) {
                if (row < currentEntries.size()) toDelete.add(currentEntries.get(row));
            }
            if (toDelete.isEmpty()) return;
            store.deleteGlobal(toDelete);
            refreshTable();
        }
    }

    private static final class GlobalFavTableModel extends AbstractTableModel {

        private final List<FavoriteEntry> entries;
        private final boolean[]           checked;

        GlobalFavTableModel(List<FavoriteEntry> entries) {
            this.entries = entries;
            this.checked = new boolean[entries.size()];
        }

        @Override public int getRowCount()    { return entries.size(); }
        @Override public int getColumnCount() { return 3; }
        @Override public String getColumnName(int col) {
            return switch (col) { case 0 -> ""; case 1 -> "Text"; default -> "Shortcut"; };
        }
        @Override public Class<?> getColumnClass(int col) {
            return col == 0 ? Boolean.class : String.class;
        }
        @Override public boolean isCellEditable(int row, int col) { return col == 0; }
        @Override public Object getValueAt(int row, int col) {
            FavoriteEntry e = entries.get(row);
            return switch (col) {
                case 0 -> checked[row];
                case 1 -> FavoritesPanel.truncate(e.getText(), 100);
                default -> e.getShortcut() != null ? e.getShortcut() : "";
            };
        }
        @Override public void setValueAt(Object val, int row, int col) {
            if (col == 0) { checked[row] = Boolean.TRUE.equals(val); fireTableCellUpdated(row, col); }
        }
    }
}
