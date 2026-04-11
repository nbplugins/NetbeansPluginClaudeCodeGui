package io.github.nbclaudecodegui.settings;

import io.github.nbclaudecodegui.settings.ClaudeProfile.ProxyMode;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import java.net.URI;
import java.util.logging.Logger;
import org.openide.awt.HtmlBrowser;

/**
 * Settings panel for managing Claude Code connection profiles.
 *
 * <p>Displayed as the "Profiles" tab inside
 * {@link ClaudeCodeOptionsPanel}.  Allows users to:
 * <ul>
 *   <li>Set the base directory for profile config dirs</li>
 *   <li>Create, copy, rename, and delete named profiles</li>
 *   <li>Configure connection type (Claude managed / Subscription / Claude API / Other API)</li>
 *   <li>Configure proxy settings</li>
 *   <li>Add arbitrary extra environment variables</li>
 * </ul>
 *
 * <p>Changes are kept in memory until {@link #store()} is called (OK / Apply).
 */
public final class ClaudeProfilesPanel extends JPanel {

    private static final Logger LOG =
            Logger.getLogger(ClaudeProfilesPanel.class.getName());

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    private static final String CONN_MANAGED     = "Claude managed";
    private static final String CONN_SUBSCRIPTION = "Subscription";
    private static final String CONN_CLAUDE_API  = "Claude API";
    private static final String CONN_OTHER_API   = "Other API";

    private static final String PROXY_CARD_NONE   = "proxy_none";
    private static final String PROXY_CARD_CUSTOM = "proxy_custom";

    // -------------------------------------------------------------------------
    // Top-level fields
    // -------------------------------------------------------------------------

    /** Field showing the directory where profiles are stored. */
    private JTextField profilesDirField;
    /** Combo box for selecting the active profile. */
    private JComboBox<String> profileCombo;
    /** Button to create a new profile. */
    private JButton newButton;
    /** Button to copy the current profile. */
    private JButton copyButton;
    /** Button to rename the current profile. */
    private JButton renameButton;
    /** Button to delete the current profile. */
    private JButton deleteButton;
    /** Field showing the storage directory for the selected profile. */
    private JTextField storageDirField;
    /** Button to set a custom storage directory for the selected profile. */
    private JButton changeStorageDirButton;
    /** Button to reset the storage directory to the computed default. */
    private JButton resetStorageDirButton;
    /** Field for extra CLI arguments passed to the claude process. */
    private JTextField extraCliArgsField;

    // Connection type
    /** Radio button for system-managed (Claude account) connection. */
    private JRadioButton rbManaged;
    /** Radio button for subscription (OAuth token) connection. */
    private JRadioButton rbSubscription;
    /** Radio button for Anthropic API key connection. */
    private JRadioButton rbClaudeApi;
    /** Radio button for other (custom base URL) API connection. */
    private JRadioButton rbOtherApi;
    /** Password field for the OAuth token. */
    private JPasswordField tokenField;
    /** Password field for the Anthropic API key. */
    private JPasswordField apiKeyField;
    /** Text field for the custom base URL. */
    private JTextField baseUrlField;
    /** Button to open the model aliases dialog. */
    private JButton modelAliasesBtn;
    /** Button to toggle visibility of the token field. */
    private JButton tokenShowBtn;
    /** Button to toggle visibility of the API key field. */
    private JButton apiKeyShowBtn;
    /** Context menu item to copy the Base URL to clipboard. */
    private JMenuItem copyUrlItem;
    /** Context menu item to open the Base URL in the browser. */
    private JMenuItem openInBrowserItem;
    /** Popup menu attached to baseUrlField. */
    private JPopupMenu baseUrlMenu;
    /** Link button opening claude.ai — shown only for Subscription type. */
    private JButton claudeAiBtn;
    /** Link button opening console.anthropic.com — shown only for Claude API type. */
    private JButton consoleAnthropicBtn;

    // Proxy
    /** Radio button for system-managed proxy. */
    private JRadioButton rbProxySystem;
    /** Radio button for no proxy. */
    private JRadioButton rbProxyNone;
    /** Radio button for custom proxy settings. */
    private JRadioButton rbProxyCustom;
    /** Card panel that switches between proxy configuration cards. */
    private JPanel proxyCardPanel;
    /** Layout manager for the proxy card panel. */
    private CardLayout proxyCardLayout;
    /** Text field for the HTTP proxy value. */
    private JTextField httpProxyField;
    /** Text field for the HTTPS proxy value. */
    private JTextField httpsProxyField;
    /** Text field for the NO_PROXY value. */
    private JTextField noProxyField;

    // Extra env vars
    /** Table model for extra environment variables. */
    private DefaultTableModel extraEnvModel;
    /** Table for editing extra environment variables. */
    private JTable extraEnvTable;

    // -------------------------------------------------------------------------
    // In-memory state
    // -------------------------------------------------------------------------

    /** Mutable working copy of all profiles (including Default at index 0). */
    private List<ClaudeProfile> profiles = new ArrayList<>();

    /** True while the combo is being repopulated programmatically; suppresses onProfileSelected. */
    private boolean suppressProfileChange = false;

    /** Profile object whose data is currently loaded in the form. */
    private ClaudeProfile currentFormProfile;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * Constructs the Profiles panel and initialises all UI components.
     */
    public ClaudeProfilesPanel() {
        setLayout(new BorderLayout(0, 8));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        initComponents();
    }

    @Override
    public Dimension getMinimumSize() {
        return getLayout().minimumLayoutSize(this);
    }

    // -------------------------------------------------------------------------
    // UI construction
    // -------------------------------------------------------------------------

    private void initComponents() {
        JPanel mainPanel = new JPanel(new GridBagLayout());
        int row = 0;

        // --- Profiles directory ---
        mainPanel.add(new JLabel("New Profiles Directory:"), gbc(0, row, false));
        profilesDirField = new JTextField(40);
        profilesDirField.setEditable(false);
        profilesDirField.setToolTipText("Base directory for per-profile CLAUDE_CONFIG_DIR");
        mainPanel.add(profilesDirField, gbcFill(1, row));
        JButton changeDirButton = new JButton("Change\u2026");
        changeDirButton.addActionListener(e -> onChangeProfilesDir());
        mainPanel.add(changeDirButton, gbc(2, row, false));
        row++;

        // --- Profile selector row ---
        JPanel profileRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        profileRow.add(new JLabel("Profile:"));
        profileCombo = new JComboBox<>();
        profileCombo.setPrototypeDisplayValue("MMMMMMMMMMMMMMMMMMMMMMM");
        profileCombo.addActionListener(e -> onProfileSelected());
        profileRow.add(profileCombo);
        newButton    = new JButton("New");
        copyButton   = new JButton("Copy");
        renameButton = new JButton("Rename");
        deleteButton = new JButton("Delete");
        newButton.addActionListener(e    -> onNew());
        copyButton.addActionListener(e   -> onCopy());
        renameButton.addActionListener(e -> onRename());
        deleteButton.addActionListener(e -> onDelete());
        for (JButton b : new JButton[]{newButton, copyButton, renameButton, deleteButton}) {
            profileRow.add(b);
        }
        GridBagConstraints rowGbc = new GridBagConstraints();
        rowGbc.gridx = 0; rowGbc.gridy = row; rowGbc.gridwidth = 3;
        rowGbc.anchor = GridBagConstraints.WEST;
        rowGbc.insets = new Insets(8, 0, 4, 0);
        mainPanel.add(profileRow, rowGbc);
        row++;

        // --- Storage directory row ---
        mainPanel.add(new JLabel("Profile Storage Directory:"), gbc(0, row, false));
        storageDirField = new JTextField(40);
        storageDirField.setEditable(false);
        mainPanel.add(storageDirField, gbcFill(1, row));
        JPanel storageDirButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        changeStorageDirButton = new JButton("Change\u2026");
        changeStorageDirButton.addActionListener(e -> onChangeStorageDir());
        resetStorageDirButton = new JButton("Reset");
        resetStorageDirButton.addActionListener(e -> onResetStorageDir());
        storageDirButtons.add(changeStorageDirButton);
        storageDirButtons.add(resetStorageDirButton);
        mainPanel.add(storageDirButtons, gbc(2, row, false));
        row++;

        // --- Connection type section ---
        JPanel connSection = buildConnectionTypeSection();
        GridBagConstraints connGbc = new GridBagConstraints();
        connGbc.gridx = 0; connGbc.gridy = row; connGbc.gridwidth = 3;
        connGbc.fill = GridBagConstraints.HORIZONTAL;
        connGbc.anchor = GridBagConstraints.WEST;
        connGbc.insets = new Insets(8, 0, 4, 0);
        mainPanel.add(connSection, connGbc);
        row++;

        // --- Proxy settings section ---
        JPanel proxySection = buildProxySection();
        GridBagConstraints proxyGbc = new GridBagConstraints();
        proxyGbc.gridx = 0; proxyGbc.gridy = row; proxyGbc.gridwidth = 3;
        proxyGbc.fill = GridBagConstraints.HORIZONTAL;
        proxyGbc.anchor = GridBagConstraints.WEST;
        proxyGbc.insets = new Insets(4, 0, 4, 0);
        mainPanel.add(proxySection, proxyGbc);
        row++;

        // --- Extra env vars table ---
        JPanel extraSection = buildExtraEnvSection();
        GridBagConstraints extraGbc = new GridBagConstraints();
        extraGbc.gridx = 0; extraGbc.gridy = row; extraGbc.gridwidth = 3;
        extraGbc.fill = GridBagConstraints.BOTH;
        extraGbc.weightx = 1.0; extraGbc.weighty = 1.0;
        extraGbc.anchor = GridBagConstraints.WEST;
        extraGbc.insets = new Insets(4, 0, 0, 0);
        mainPanel.add(extraSection, extraGbc);
        row++;

        // --- Extra CLI args ---
        extraCliArgsField = new JTextField(40);
        extraCliArgsField.setToolTipText("Additional command-line arguments passed to the claude process");
        mainPanel.add(new JLabel("Extra CLI args:"), gbc(0, row, false));
        GridBagConstraints cliGbc = gbcFill(1, row);
        cliGbc.gridwidth = 2;
        mainPanel.add(extraCliArgsField, cliGbc);

        add(mainPanel, BorderLayout.CENTER);
    }

    private JPanel buildConnectionTypeSection() {
        rbManaged      = new JRadioButton(CONN_MANAGED);
        rbSubscription = new JRadioButton(CONN_SUBSCRIPTION);
        rbClaudeApi    = new JRadioButton(CONN_CLAUDE_API);
        rbOtherApi     = new JRadioButton(CONN_OTHER_API);
        ButtonGroup bg = new ButtonGroup();
        for (JRadioButton rb : new JRadioButton[]{rbManaged, rbSubscription, rbClaudeApi, rbOtherApi}) {
            bg.add(rb);
        }

        tokenField  = new JPasswordField(28);
        apiKeyField = new JPasswordField(28);
        baseUrlField = new JTextField(28);
        modelAliasesBtn = new JButton("Model Aliases\u2026");
        modelAliasesBtn.addActionListener(e -> onModelAliases());

        // Context menu for baseUrlField
        baseUrlMenu = new JPopupMenu();
        copyUrlItem       = new JMenuItem("Copy URL");
        openInBrowserItem = new JMenuItem("Open in Browser");
        baseUrlMenu.add(copyUrlItem);
        baseUrlMenu.add(openInBrowserItem);
        copyUrlItem.addActionListener(e -> {
            String text = baseUrlField.getText().trim();
            if (!text.isBlank()) {
                java.awt.Toolkit.getDefaultToolkit()
                    .getSystemClipboard()
                    .setContents(new java.awt.datatransfer.StringSelection(text), null);
            }
        });
        openInBrowserItem.addActionListener(e -> {
            String text = baseUrlField.getText().trim();
            if (!text.isBlank()) {
                try {
                    HtmlBrowser.URLDisplayer.getDefault().showURL(new URI(text).toURL());
                } catch (Exception ex) {
                    LOG.warning("Could not open URL: " + text + " — " + ex.getMessage());
                }
            }
        });
        baseUrlField.setComponentPopupMenu(baseUrlMenu);

        DocumentListener docL = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e)  { updateModelAliasesBtn(); }
            @Override public void removeUpdate(DocumentEvent e)  { updateModelAliasesBtn(); }
            @Override public void changedUpdate(DocumentEvent e) { updateModelAliasesBtn(); }
        };
        apiKeyField.getDocument().addDocumentListener(docL);
        baseUrlField.getDocument().addDocumentListener(docL);

        rbManaged.addActionListener(e      -> updateFieldEnablement());
        rbSubscription.addActionListener(e -> updateFieldEnablement());
        rbClaudeApi.addActionListener(e    -> updateFieldEnablement());
        rbOtherApi.addActionListener(e     -> updateFieldEnablement());

        // Grid: col0=radio, col1=label, col2=field(fill), col3=button
        JPanel grid = new JPanel(new GridBagLayout());
        grid.setBorder(BorderFactory.createEmptyBorder(0, 16, 0, 0));

        // row 0: rbManaged spans all columns
        GridBagConstraints rbMgdGbc = new GridBagConstraints();
        rbMgdGbc.gridx = 0; rbMgdGbc.gridy = 0; rbMgdGbc.gridwidth = 4;
        rbMgdGbc.anchor = GridBagConstraints.NORTHWEST;
        rbMgdGbc.insets = new Insets(2, 0, 2, 0);
        grid.add(rbManaged, rbMgdGbc);

        // row 1: rbSubscription + Token label + tokenField + [Show] + [claude.ai]
        grid.add(rbSubscription, inlineRbGbc(0, 1));
        grid.add(new JLabel("Token:"), inlineLblGbc(1, 1));
        grid.add(tokenField, inlineFieldGbc(2, 1));
        tokenShowBtn = buildShowHideButton(tokenField);
        grid.add(tokenShowBtn, inlineBtnGbc(3, 1));
        claudeAiBtn = buildLinkButton("claude.ai", "https://claude.ai");
        grid.add(claudeAiBtn, inlineBtnGbc(4, 1));

        // row 2: rbClaudeApi + API Key label + apiKeyField + [Show] + [console.anthropic.com]
        grid.add(rbClaudeApi, inlineRbGbc(0, 2));
        grid.add(new JLabel("API Key:"), inlineLblGbc(1, 2));
        grid.add(apiKeyField, inlineFieldGbc(2, 2));
        apiKeyShowBtn = buildShowHideButton(apiKeyField);
        grid.add(apiKeyShowBtn, inlineBtnGbc(3, 2));
        consoleAnthropicBtn = buildLinkButton("console.anthropic.com", "https://console.anthropic.com");
        grid.add(consoleAnthropicBtn, inlineBtnGbc(4, 2));

        // row 3: rbOtherApi + Base URL label + baseUrlField + [Custom Models…]
        grid.add(rbOtherApi, inlineRbGbc(0, 3));
        grid.add(new JLabel("Base URL:"), inlineLblGbc(1, 3));
        grid.add(baseUrlField, inlineFieldGbc(2, 3));
        grid.add(modelAliasesBtn, inlineBtnGbc(3, 3));

        JPanel outer = new JPanel(new BorderLayout(0, 4));
        outer.add(new JLabel("Connection Type:"), BorderLayout.NORTH);
        outer.add(grid, BorderLayout.CENTER);
        return outer;
    }

    private void updateFieldEnablement() {
        boolean sub      = rbSubscription.isSelected();
        boolean api      = rbClaudeApi.isSelected() || rbOtherApi.isSelected();
        boolean otherApi = rbOtherApi.isSelected();
        tokenField.setEnabled(sub);
        tokenShowBtn.setEnabled(sub);
        apiKeyField.setEnabled(api);
        apiKeyShowBtn.setEnabled(api);
        baseUrlField.setEditable(otherApi);
        if (claudeAiBtn != null) {
            claudeAiBtn.setVisible(sub);
            consoleAnthropicBtn.setVisible(rbClaudeApi.isSelected());
        }
        updateModelAliasesBtn();
    }

    private void updateModelAliasesBtn() {
        boolean other  = rbOtherApi.isSelected();
        boolean hasKey = !new String(apiKeyField.getPassword()).isBlank();
        boolean hasUrl = !baseUrlField.getText().isBlank();
        modelAliasesBtn.setEnabled(other && hasKey && hasUrl);
    }

    private JPanel buildProxySection() {
        JPanel radioCol = new JPanel(new GridBagLayout());
        rbProxySystem = new JRadioButton("System Managed");
        rbProxyNone   = new JRadioButton("No Proxy");
        rbProxyCustom = new JRadioButton("Custom");
        ButtonGroup bg = new ButtonGroup();
        int r = 0;
        for (JRadioButton rb : new JRadioButton[]{rbProxySystem, rbProxyNone, rbProxyCustom}) {
            bg.add(rb);
            radioCol.add(rb, connRbGbc(r++));
        }
        rbProxySystem.addActionListener(e -> showProxyCard(PROXY_CARD_NONE));
        rbProxyNone.addActionListener(e   -> showProxyCard(PROXY_CARD_NONE));
        rbProxyCustom.addActionListener(e -> showProxyCard(PROXY_CARD_CUSTOM));

        proxyCardLayout = new CardLayout();
        proxyCardPanel  = new JPanel(proxyCardLayout);
        proxyCardPanel.add(new JPanel(), PROXY_CARD_NONE);
        proxyCardPanel.add(buildCustomProxyCard(), PROXY_CARD_CUSTOM);

        JPanel content = new JPanel(new BorderLayout(8, 0));
        content.setBorder(BorderFactory.createEmptyBorder(0, 16, 0, 0));
        content.add(radioCol, BorderLayout.WEST);
        content.add(proxyCardPanel, BorderLayout.CENTER);

        JPanel outer = new JPanel(new BorderLayout(0, 4));
        outer.add(new JLabel("Proxy Settings:"), BorderLayout.NORTH);
        outer.add(content, BorderLayout.CENTER);
        return outer;
    }

    private JPanel buildCustomProxyCard() {
        JPanel p = new JPanel(new GridBagLayout());
        p.add(new JLabel("HTTP Proxy:"),  connRbGbc(0));
        httpProxyField = new JTextField(28);
        p.add(httpProxyField, connFieldGbc(0));
        p.add(new JLabel("HTTPS Proxy:"), connRbGbc(1));
        httpsProxyField = new JTextField(28);
        p.add(httpsProxyField, connFieldGbc(1));
        p.add(new JLabel("NO_PROXY:"),    connRbGbc(2));
        noProxyField = new JTextField(28);
        noProxyField.setToolTipText("e.g. localhost,127.0.0.1");
        p.add(noProxyField, connFieldGbc(2));
        return p;
    }

    private JPanel buildExtraEnvSection() {
        extraEnvModel = new DefaultTableModel(new String[]{"Variable", "Value"}, 0) {
            @Override public boolean isCellEditable(int row, int col) { return true; }
        };
        extraEnvTable = new JTable(extraEnvModel);
        extraEnvTable.getColumnModel().getColumn(0).setPreferredWidth(150);
        extraEnvTable.getColumnModel().getColumn(1).setPreferredWidth(250);

        JButton addRow = new JButton("+");
        JButton delRow = new JButton("-");
        addRow.addActionListener(e -> extraEnvModel.addRow(new String[]{"", ""}));
        delRow.addActionListener(e -> {
            int sel = extraEnvTable.getSelectedRow();
            if (sel >= 0) extraEnvModel.removeRow(sel);
        });

        // +/- buttons stacked vertically to the right of the table
        JPanel btns = new JPanel();
        btns.setLayout(new BoxLayout(btns, BoxLayout.Y_AXIS));
        btns.add(addRow);
        btns.add(Box.createVerticalStrut(2));
        btns.add(delRow);
        btns.add(Box.createVerticalGlue());

        JPanel tableWithBtns = new JPanel(new BorderLayout(4, 0));
        tableWithBtns.add(new JScrollPane(extraEnvTable), BorderLayout.CENTER);
        tableWithBtns.add(btns, BorderLayout.EAST);

        // Label on the left, table+buttons on the right
        JLabel lbl = new JLabel("Extra variables:");
        lbl.setVerticalAlignment(JLabel.TOP);

        JPanel outer = new JPanel(new BorderLayout(8, 0));
        outer.add(lbl, BorderLayout.WEST);
        outer.add(tableWithBtns, BorderLayout.CENTER);
        outer.setPreferredSize(new Dimension(0, 130));
        outer.setMinimumSize(new Dimension(300, 100));
        return outer;
    }

    // -------------------------------------------------------------------------
    // load / store / valid
    // -------------------------------------------------------------------------

    /**
     * Loads profiles from {@link ClaudeProfileStore} and preference values
     * into the UI controls.
     */
    void load() {
        profiles = ClaudeProfileStore.getProfiles();
        profilesDirField.setText(ClaudeCodePreferences.getProfilesDir().toString());
        suppressProfileChange = true;
        rebuildProfileCombo();
        suppressProfileChange = false;
        if (!profiles.isEmpty()) {
            profileCombo.setSelectedIndex(0);
            loadProfileIntoForm(profiles.get(0));
        }
    }

    /**
     * Persists the current in-memory state to {@link ClaudeProfileStore}
     * and {@link ClaudeCodePreferences}.
     */
    void store() {
        // Flush any unsaved edits from the current profile form
        flushFormToCurrentProfile();
        ClaudeProfileStore.saveProfiles(profiles);
        String dirText = profilesDirField.getText().trim();
        if (!dirText.isBlank()) {
            ClaudeCodePreferences.setProfilesDir(Path.of(dirText));
        }
    }

    /**
     * Validates the panel state.
     *
     * @return {@code true} — always valid (individual field errors shown inline)
     */
    boolean valid() {
        return true;
    }

    // -------------------------------------------------------------------------
    // Profile combo / selection
    // -------------------------------------------------------------------------

    private void rebuildProfileCombo() {
        String current = (String) profileCombo.getSelectedItem();
        DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
        for (ClaudeProfile p : profiles) {
            model.addElement(p.getName());
        }
        profileCombo.setModel(model);
        if (current != null) {
            profileCombo.setSelectedItem(current);
        }
    }

    private void onProfileSelected() {
        if (suppressProfileChange) return;
        flushFormToCurrentProfile();
        int idx = profileCombo.getSelectedIndex();
        if (idx >= 0 && idx < profiles.size()) {
            loadProfileIntoForm(profiles.get(idx));
        }
    }

    private ClaudeProfile currentProfile() {
        int idx = profileCombo.getSelectedIndex();
        return (idx >= 0 && idx < profiles.size()) ? profiles.get(idx) : null;
    }

    // -------------------------------------------------------------------------
    // Form → model
    // -------------------------------------------------------------------------

    private void flushFormToCurrentProfile() {
        ClaudeProfile p = currentFormProfile;
        LOG.fine("flushFormToCurrentProfile → target: " + (p != null ? p.getName() : "null"));
        if (p == null || extraEnvTable == null) return;

        // Stop any active cell editing
        if (extraEnvTable.isEditing()) {
            extraEnvTable.getCellEditor().stopCellEditing();
        }

        // Auth
        if (rbSubscription.isSelected()) {
            p.setToken(new String(tokenField.getPassword()));
            p.setApiKey("");
            p.setBaseUrl("");
        } else if (rbClaudeApi.isSelected()) {
            p.setApiKey(new String(apiKeyField.getPassword()));
            p.setToken("");
            p.setBaseUrl("");
        } else if (rbOtherApi.isSelected()) {
            p.setApiKey(new String(apiKeyField.getPassword()));
            p.setBaseUrl(baseUrlField.getText().trim());
            p.setToken("");
        } else {
            p.setToken("");
            p.setApiKey("");
            p.setBaseUrl("");
        }

        // Proxy
        if (rbProxyNone.isSelected()) {
            p.setProxyMode(ProxyMode.NO_PROXY);
        } else if (rbProxyCustom.isSelected()) {
            p.setProxyMode(ProxyMode.CUSTOM);
            p.setHttpProxy(httpProxyField.getText().trim());
            p.setHttpsProxy(httpsProxyField.getText().trim());
            p.setNoProxy(noProxyField.getText().trim());
        } else {
            p.setProxyMode(ProxyMode.SYSTEM_MANAGED);
        }

        // Extra env vars
        List<String[]> extra = new ArrayList<>();
        for (int r = 0; r < extraEnvModel.getRowCount(); r++) {
            String k = (String) extraEnvModel.getValueAt(r, 0);
            String v = (String) extraEnvModel.getValueAt(r, 1);
            if (k != null && !k.isBlank()) {
                extra.add(new String[]{k, v != null ? v : ""});
            }
        }
        p.setExtraEnvVars(extra);

        // Extra CLI args
        if (extraCliArgsField != null) {
            p.setExtraCliArgs(extraCliArgsField.getText().trim());
        }
    }

    // -------------------------------------------------------------------------
    // Model → form
    // -------------------------------------------------------------------------

    private void loadProfileIntoForm(ClaudeProfile p) {
        LOG.fine("loadProfileIntoForm → " + p.getName());
        currentFormProfile = p;
        boolean def = p.isDefault();
        renameButton.setEnabled(!def);
        deleteButton.setEnabled(!def);

        // Storage directory
        refreshStorageDirField(p);

        // Connection type
        ClaudeProfile.ConnectionType ct = p.computeConnectionType();
        tokenField.setText("");
        apiKeyField.setText("");
        baseUrlField.setText("");
        switch (ct) {
            case SUBSCRIPTION -> {
                rbSubscription.setSelected(true);
                tokenField.setText(p.getToken());
            }
            case CLAUDE_API -> {
                rbClaudeApi.setSelected(true);
                apiKeyField.setText(p.getApiKey());
            }
            case OTHER_API -> {
                rbOtherApi.setSelected(true);
                apiKeyField.setText(p.getApiKey());
                baseUrlField.setText(p.getBaseUrl());
            }
            default -> rbManaged.setSelected(true);
        }
        updateFieldEnablement();

        // Proxy
        switch (p.getProxyMode()) {
            case NO_PROXY -> {
                rbProxyNone.setSelected(true);
                showProxyCard(PROXY_CARD_NONE);
            }
            case CUSTOM -> {
                rbProxyCustom.setSelected(true);
                httpProxyField.setText(p.getHttpProxy());
                httpsProxyField.setText(p.getHttpsProxy());
                noProxyField.setText(p.getNoProxy());
                showProxyCard(PROXY_CARD_CUSTOM);
            }
            default -> {
                rbProxySystem.setSelected(true);
                showProxyCard(PROXY_CARD_NONE);
            }
        }

        // Extra env vars
        extraEnvModel.setRowCount(0);
        for (String[] kv : p.getExtraEnvVars()) {
            extraEnvModel.addRow(kv);
        }

        // Extra CLI args
        if (extraCliArgsField != null) {
            extraCliArgsField.setText(p.getExtraCliArgs());
        }
    }

    // -------------------------------------------------------------------------
    // Profile operations
    // -------------------------------------------------------------------------

    private void onNew() {
        flushFormToCurrentProfile();
        String name = promptProfileName("New Profile", nextDefaultName(), null);
        if (name == null) return;
        ClaudeProfile p = ClaudeProfile.createNamed(name);
        profiles.add(p);
        suppressProfileChange = true;
        rebuildProfileCombo();
        profileCombo.setSelectedItem(name);
        suppressProfileChange = false;
        loadProfileIntoForm(p);
    }

    private void onCopy() {
        flushFormToCurrentProfile();
        ClaudeProfile src = currentProfile();
        if (src == null) return;
        String name = promptProfileName("Copy Profile", nextDefaultName(), null);
        if (name == null) return;
        // Deep-copy
        ClaudeProfile copy = ClaudeProfile.createNamed(name);
        copy.setToken(src.getToken());
        copy.setApiKey(src.getApiKey());
        copy.setBaseUrl(src.getBaseUrl());
        copy.setProxyMode(src.getProxyMode());
        copy.setHttpProxy(src.getHttpProxy());
        copy.setHttpsProxy(src.getHttpsProxy());
        copy.setNoProxy(src.getNoProxy());
        copy.setExtraEnvVars(new ArrayList<>(src.getExtraEnvVars()));
        copy.setModelAliases(new java.util.HashMap<>(src.getModelAliases()));
        copy.setExtraCliArgs(src.getExtraCliArgs());
        profiles.add(copy);
        suppressProfileChange = true;
        rebuildProfileCombo();
        profileCombo.setSelectedItem(name);
        suppressProfileChange = false;
        loadProfileIntoForm(copy);
    }

    private void onRename() {
        ClaudeProfile p = currentProfile();
        if (p == null || p.isDefault()) return;
        String oldName = p.getName();
        String name = promptProfileName("Rename Profile", oldName, oldName);
        if (name == null || name.equals(oldName)) return;
        // Update project assignments before renaming
        ClaudeProjectProperties.renameAssignments(oldName, name);
        p.setId(name);
        p.setName(name);
        suppressProfileChange = true;
        rebuildProfileCombo();
        profileCombo.setSelectedItem(name);
        suppressProfileChange = false;
    }

    private void onDelete() {
        ClaudeProfile p = currentProfile();
        if (p == null || p.isDefault()) return;
        int choice = JOptionPane.showConfirmDialog(this,
                "Delete profile \"" + p.getName() + "\"?",
                "Delete Profile", JOptionPane.YES_NO_OPTION);
        if (choice != JOptionPane.YES_OPTION) return;
        // Clear project assignments
        ClaudeProjectProperties.clearAssignmentsForProfile(p.getName());
        profiles.remove(p);
        suppressProfileChange = true;
        rebuildProfileCombo();
        suppressProfileChange = false;
        if (!profiles.isEmpty()) {
            profileCombo.setSelectedIndex(0);
            loadProfileIntoForm(profiles.get(0));
        }
    }

    private void onModelAliases() {
        flushFormToCurrentProfile();
        ClaudeProfile p = currentFormProfile;
        if (p == null) return;
        // Reconstruct display list from stored alias map (sonnet/opus/haiku) and custom list
        java.util.List<ModelAlias> existing = new ArrayList<>();
        for (java.util.Map.Entry<String, String> e : p.getModelAliases().entrySet()) {
            existing.add(new ModelAlias(e.getValue(), null, e.getKey()));
        }
        for (String id : p.getCustomModels()) {
            existing.add(new ModelAlias(id, null, "custom"));
        }
        ModelAliasesDialog dlg = new ModelAliasesDialog(
                this,
                p.getBaseUrl(),
                p.getApiKey(),
                existing);
        dlg.setVisible(true);
        java.util.List<ModelAlias> chosen = dlg.getModels();
        if (chosen != null) {
            java.util.Map<String, String> aliasMap = new java.util.LinkedHashMap<>();
            java.util.List<String> customList = new ArrayList<>();
            for (ModelAlias m : chosen) {
                if ("custom".equals(m.alias())) {
                    customList.add(m.id());
                } else if (m.alias() != null && !m.alias().isBlank()) {
                    aliasMap.put(m.alias(), m.id());
                }
            }
            p.setModelAliases(aliasMap);
            p.setCustomModels(customList);
        }
    }

    private void onChangeProfilesDir() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        String current = profilesDirField.getText().trim();
        if (!current.isBlank()) chooser.setCurrentDirectory(new File(current));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            profilesDirField.setText(chooser.getSelectedFile().getAbsolutePath());
            // Refresh config dir display for current profile
            ClaudeProfile p = currentProfile();
            if (p != null) loadProfileIntoForm(p);
        }
    }

    private void onChangeStorageDir() {
        ClaudeProfile p = currentFormProfile;
        if (p == null || p.isDefault()) return;
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        String current = p.getStorageDir();
        if (!current.isBlank()) {
            chooser.setCurrentDirectory(new File(current));
        } else {
            Path profilesDir = ClaudeCodePreferences.getProfilesDir();
            chooser.setCurrentDirectory(ClaudeProfileStore.resolveStorageDir(p, profilesDir).toFile());
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            p.withStorageDir(chooser.getSelectedFile().getAbsolutePath());
            refreshStorageDirField(p);
        }
    }

    private void onResetStorageDir() {
        ClaudeProfile p = currentFormProfile;
        if (p == null || p.isDefault()) return;
        p.withStorageDir("");
        refreshStorageDirField(p);
    }

    private void refreshStorageDirField(ClaudeProfile p) {
        if (p.isDefault()) {
            storageDirField.setText("~/.claude  (not overridden)");
            changeStorageDirButton.setEnabled(false);
            resetStorageDirButton.setEnabled(false);
        } else {
            Path profilesDir = ClaudeCodePreferences.getProfilesDir();
            String resolved = ClaudeProfileStore.resolveStorageDir(p, profilesDir).toAbsolutePath().toString();
            boolean isCustom = !p.getStorageDir().isBlank();
            storageDirField.setText(isCustom ? resolved + "  (custom)" : resolved);
            changeStorageDirButton.setEnabled(true);
            resetStorageDirButton.setEnabled(isCustom);
        }
    }

    // -------------------------------------------------------------------------
    // Profile name dialog
    // -------------------------------------------------------------------------

    private String promptProfileName(String title, String initial, String reserved) {
        while (true) {
            Object raw = JOptionPane.showInputDialog(this, "Profile name:", title,
                    JOptionPane.PLAIN_MESSAGE, null, null, initial);
            if (raw == null) return null; // cancelled
            String name = raw.toString().trim();
            String err = ClaudeProfile.validateName(name);
            if (err != null) {
                JOptionPane.showMessageDialog(this, err, "Invalid Name", JOptionPane.ERROR_MESSAGE);
                initial = name;
                continue;
            }
            // Uniqueness check
            for (ClaudeProfile p : profiles) {
                if (p.getName().equals(name) && !name.equals(reserved)) {
                    JOptionPane.showMessageDialog(this, "A profile named \"" + name + "\" already exists.",
                            "Duplicate Name", JOptionPane.ERROR_MESSAGE);
                    initial = name;
                    name = null;
                    break;
                }
            }
            if (name != null) return name;
        }
    }

    private String nextDefaultName() {
        for (int i = 1; i <= 99; i++) {
            String candidate = "Profile" + i;
            boolean found = false;
            for (ClaudeProfile p : profiles) {
                if (p.getName().equals(candidate)) { found = true; break; }
            }
            if (!found) return candidate;
        }
        return "NewProfile";
    }

    // -------------------------------------------------------------------------
    // Show/hide password fields
    // -------------------------------------------------------------------------

    private static JButton buildShowHideButton(JPasswordField field) {
        JButton btn = new JButton("Show");
        btn.addActionListener(e -> {
            if ("Show".equals(btn.getText())) {
                field.setEchoChar((char) 0);
                btn.setText("Hide");
            } else {
                field.setEchoChar('\u2022');
                btn.setText("Show");
            }
        });
        return btn;
    }

    // -------------------------------------------------------------------------
    // Card helpers
    // -------------------------------------------------------------------------

    private void showProxyCard(String card) {
        proxyCardLayout.show(proxyCardPanel, card);
    }

    // -------------------------------------------------------------------------
    // GridBagConstraints helpers
    // -------------------------------------------------------------------------

    private static GridBagConstraints gbc(int x, int y, boolean fill) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = x; c.gridy = y;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(4, x == 0 ? 0 : 4, 2, x == 2 ? 0 : 4);
        return c;
    }

    private static GridBagConstraints gbcFill(int x, int y) {
        GridBagConstraints c = gbc(x, y, true);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        return c;
    }

    private static GridBagConstraints connRbGbc(int row) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.gridy = row;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(2, 4, 2, 4);
        return c;
    }

    private static GridBagConstraints connFieldGbc(int row) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 1; c.gridy = row;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(2, 0, 2, 4);
        return c;
    }

    private static GridBagConstraints connBtn(int row) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 2; c.gridy = row;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(2, 0, 2, 0);
        return c;
    }

    private static GridBagConstraints inlineRbGbc(int col, int row) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = col; c.gridy = row;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(2, 0, 2, 4);
        return c;
    }

    private static GridBagConstraints inlineLblGbc(int col, int row) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = col; c.gridy = row;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(4, 0, 2, 4);
        return c;
    }

    private static GridBagConstraints inlineFieldGbc(int col, int row) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = col; c.gridy = row;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(2, 0, 2, 4);
        return c;
    }

    private static JButton buildLinkButton(String label, String url) {
        JButton btn = new JButton("<html><a href=''>" + label + "</a></html>");
        btn.setBorderPainted(false);
        btn.setContentAreaFilled(false);
        btn.setOpaque(false);
        btn.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        btn.setToolTipText(url);
        btn.addActionListener(e -> openUrl(url));

        JPopupMenu menu = new JPopupMenu();
        JMenuItem copyItem = new JMenuItem("Copy URL");
        JMenuItem openItem = new JMenuItem("Open in Browser");
        copyItem.addActionListener(e ->
                java.awt.Toolkit.getDefaultToolkit()
                        .getSystemClipboard()
                        .setContents(new java.awt.datatransfer.StringSelection(url), null));
        openItem.addActionListener(e -> openUrl(url));
        menu.add(copyItem);
        menu.add(openItem);
        btn.setComponentPopupMenu(menu);
        return btn;
    }

    private static void openUrl(String url) {
        try {
            HtmlBrowser.URLDisplayer.getDefault().showURL(new URI(url).toURL());
        } catch (Exception ex) {
            LOG.warning("Could not open URL: " + url + " — " + ex.getMessage());
        }
    }

    private static GridBagConstraints inlineBtnGbc(int col, int row) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = col; c.gridy = row;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(2, 0, 2, 0);
        return c;
    }
}
