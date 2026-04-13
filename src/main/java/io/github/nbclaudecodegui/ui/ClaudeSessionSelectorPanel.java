package io.github.nbclaudecodegui.ui;

import io.github.nbclaudecodegui.model.SessionMode;
import io.github.nbclaudecodegui.settings.ClaudeCodePreferences;
import io.github.nbclaudecodegui.settings.ClaudeProfile;
import io.github.nbclaudecodegui.settings.ClaudeProfileStore;
import io.github.nbclaudecodegui.settings.ClaudeProjectProperties;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Logger;
import java.util.prefs.Preferences;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import org.netbeans.api.options.OptionsDisplayer;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ProjectUtils;
import org.netbeans.api.project.ui.OpenProjects;
import org.openide.filesystems.FileUtil;
import org.openide.util.NbBundle;
import org.openide.util.NbPreferences;

/**
 * Top bar panel that lets the user pick a working directory and connection
 * profile before starting a Claude Code session.
 *
 * <p>Contains a project combo, a path combo with browsing, a profile combo,
 * an Open button, and a Settings gear button. Once the user clicks Open (or
 * {@link #lock()} is called programmatically), all controls are disabled until
 * {@link #unlock()} is called — typically when the session ends.
 *
 * <p>Fires {@link OpenListener#onOpen} with the validated directory and
 * selected profile name when the user confirms a valid path via the Open button.
 *
 * <p>Path history is persisted in {@link NbPreferences} across IDE restarts.
 */
public final class ClaudeSessionSelectorPanel extends JPanel {

    private static final Logger LOG =
            Logger.getLogger(ClaudeSessionSelectorPanel.class.getName());

    // -------------------------------------------------------------------------
    // History persistence constants
    // -------------------------------------------------------------------------

    /** Preferences key used to store the recent path list. */
    private static final String PREF_RECENT = "recentPaths";

    /** Maximum number of entries kept in the recent-path history. */
    private static final int HISTORY_SIZE = 10;

    /** Separator character used when joining the history list into a single string. */
    private static final String HISTORY_SEP = "\n";

    // -------------------------------------------------------------------------
    // Listener interface
    // -------------------------------------------------------------------------

    /**
     * Callback fired when the user confirms a valid working directory by
     * pressing the Open button.
     */
    public interface OpenListener {

        /**
         * Called on the EDT after the selected path has been validated as an
         * existing directory.
         *
         * @param dir             the selected working directory
         * @param profileName     the selected profile name (never {@code null};
         *                        use {@link ClaudeProfile#DEFAULT_NAME} to check
         *                        for the default)
         * @param extraCliArgs    extra CLI arguments from the args field (may be empty)
         * @param mode            the selected session start mode
         * @param resumeSessionId session ID to resume (only used for RESUME_SPECIFIC)
         */
        void onOpen(File dir, String profileName, String extraCliArgs,
                    SessionMode mode, String resumeSessionId);
    }

    // -------------------------------------------------------------------------
    // UI components
    // -------------------------------------------------------------------------

    /** Combo for quickly selecting an open NetBeans project. */
    private final JComboBox<ProjectItem> projectCombo;

    /**
     * Editable combo for the working-directory path; pre-populated with
     * recently used paths from preferences.
     */
    private final JComboBox<String> pathCombo;

    /** Opens a directory chooser dialog to pick the working directory. */
    private final JButton browseButton;

    /** Selects which Claude Code connection profile to use. */
    private final JComboBox<String> profileCombo;

    /** Validates the path and fires {@link OpenListener#onOpen}. */
    private final JButton openButton;

    /** Editable field for extra CLI arguments; pre-filled from the selected profile. */
    private final JTextField extraArgsField;

    /** Displays validation errors (path empty, directory not found). */
    private final JLabel errorLabel;

    /** Panel for choosing session start mode (New / Continue last / Resume specific). */
    private final SessionModePanel sessionModePanel;

    /** Suppresses the project-selection listener while the combo is populated programmatically. */
    private boolean suppressProjectListener;

    /** Notified when the user confirms a valid directory. */
    private final OpenListener openListener;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * Creates a new selector panel.
     *
     * @param initialDirectory pre-selected directory, or {@code null} for none
     * @param openListener     callback fired when the user opens a valid directory
     */
    public ClaudeSessionSelectorPanel(File initialDirectory, OpenListener openListener) {
        super(new BorderLayout());
        this.openListener = openListener;

        // --- project combo ---
        projectCombo = new JComboBox<>();
        projectCombo.setRenderer(new ProjectItemRenderer());
        populateProjectCombo();
        if (initialDirectory != null) {
            preselectProjectForDirectory(initialDirectory);
        }
        projectCombo.addActionListener(e -> onProjectSelected());

        // --- path combo ---
        pathCombo = new JComboBox<>();
        pathCombo.setEditable(true);
        pathCombo.setPrototypeDisplayValue("MMMMMMMMMMMMMMMMMMMMMMMMMMMMMM");
        populatePathHistory();
        if (initialDirectory != null) {
            pathCombo.setSelectedItem(initialDirectory.getAbsolutePath());
        }
        pathCombo.addActionListener(e -> reloadSessionModePanel());

        // --- browse button ---
        browseButton = new JButton(NbBundle.getMessage(ClaudeSessionSelectorPanel.class, "BTN_Browse"));
        browseButton.addActionListener(e -> onBrowse());

        // --- profile combo ---
        profileCombo = new JComboBox<>();
        profileCombo.setToolTipText("Connection profile");
        populateProfileCombo();

        // --- extra args field ---
        extraArgsField = new JTextField(30);
        extraArgsField.setToolTipText("Extra CLI arguments for this session (e.g. --verbose)");

        // Pre-fill from selected profile; update when profile changes
        profileCombo.addActionListener(e -> onProfileSelected());
        onProfileSelected();

        // --- open button ---
        openButton = new JButton(NbBundle.getMessage(ClaudeSessionSelectorPanel.class, "BTN_Open"));
        openButton.addActionListener(e -> onOpen());

        // --- settings button ---
        JButton settingsButton = new JButton("\u2699");
        settingsButton.setToolTipText(
                NbBundle.getMessage(ClaudeSessionSelectorPanel.class, "TIP_Settings"));
        settingsButton.addActionListener(e ->
                OptionsDisplayer.getDefault().open("ClaudeCodeGUI"));

        // --- error label ---
        errorLabel = new JLabel();
        errorLabel.setForeground(Color.RED);
        errorLabel.setVisible(false);

        // --- layout ---
        JPanel leftBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        leftBar.add(projectCombo);
        leftBar.add(pathCombo);
        leftBar.add(browseButton);
        leftBar.add(profileCombo);

        JPanel rightBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 4));
        rightBar.add(openButton);
        rightBar.add(settingsButton);

        JPanel controlBar = new JPanel(new BorderLayout());
        controlBar.add(leftBar, BorderLayout.CENTER);
        controlBar.add(rightBar, BorderLayout.EAST);

        JPanel argsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        argsRow.add(new JLabel("Extra args:"));
        argsRow.add(extraArgsField);

        // --- session mode panel ---
        sessionModePanel = new SessionModePanel(
                resolveDir() != null ? resolveDir().toPath() : null,
                resolveClaudeConfigDir(),
                null, false);
        SessionMode defaultMode = ClaudeCodePreferences.getContextMenuSessionMode();
        sessionModePanel.setMode(defaultMode);

        sessionModePanel.setOnDoubleClick(this::onOpen);

        JPanel topBar = new JPanel();
        topBar.setLayout(new javax.swing.BoxLayout(topBar, javax.swing.BoxLayout.Y_AXIS));
        topBar.add(controlBar);
        topBar.add(argsRow);

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Color.LIGHT_GRAY));
        wrapper.add(topBar, BorderLayout.NORTH);
        wrapper.add(sessionModePanel, BorderLayout.CENTER);
        wrapper.add(errorLabel, BorderLayout.SOUTH);
        add(wrapper, BorderLayout.CENTER);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Disables all controls — called when a session becomes active.
     *
     * <p>While locked, the selector visually indicates that a session is
     * running and prevents the user from accidentally starting another.
     */
    public void lock() {
        setControlsEnabled(false);
    }

    /**
     * Re-enables all controls — called when a session ends.
     */
    public void unlock() {
        setControlsEnabled(true);
    }

    /**
     * Returns {@code true} if the controls are currently locked (a session is active).
     *
     * @return {@code true} when locked
     */
    public boolean isLocked() {
        return !openButton.isEnabled();
    }

    /**
     * Returns the name of the currently selected profile.
     *
     * @return profile name; never {@code null} — returns
     *         {@link ClaudeProfile#DEFAULT_NAME} when nothing is selected
     */
    public String getSelectedProfileName() {
        Object sel = profileCombo.getSelectedItem();
        return sel != null ? sel.toString() : ClaudeProfile.DEFAULT_NAME;
    }

    public String getExtraCliArgs() {
        return extraArgsField.getText().trim();
    }

    public void setExtraCliArgs(String args) {
        extraArgsField.setText(args != null ? args : "");
    }

    /**
     * Sets the path combo to the given path string without triggering validation.
     * Used by {@code autoStart} to pre-populate the combo before locking.
     *
     * @param path absolute path to display
     */
    public void setPath(String path) {
        pathCombo.setSelectedItem(path);
    }

    /**
     * Selects the profile by name.  If {@code name} is {@code null} or blank
     * the first profile (Default) is selected.
     *
     * @param name profile name, or {@code null} for Default
     */
    public void setProfile(String name) {
        if (name != null && !name.isBlank()) {
            profileCombo.setSelectedItem(name);
        } else {
            profileCombo.setSelectedIndex(0);
        }
        onProfileSelected();
    }

    /**
     * Attempts to highlight the open project whose directory matches {@code dir}
     * in the project combo.  If no project matches, the "Select a project"
     * placeholder is left selected.
     *
     * @param dir directory to match against open project directories
     */
    public void preselectForDirectory(File dir) {
        preselectProjectForDirectory(dir);
    }

    /**
     * Resolves the tab label for a session directory.
     *
     * <p>Returns the NetBeans project display name when {@code dir} matches an
     * open project, or {@code "Claude Code"} as a fallback.  Returns the
     * localised "New Session" string when {@code dir} is {@code null}.
     *
     * @param dir working directory, or {@code null} for a new empty session
     * @return human-readable tab label; never {@code null}
     */
    public static String resolveTabLabel(File dir) {
        if (dir == null) {
            return NbBundle.getMessage(ClaudeSessionSelectorPanel.class, "TAB_NewSession");
        }
        Project[] openProjects = OpenProjects.getDefault().getOpenProjects();
        LOG.info("resolveTabLabel: dir=" + dir.getAbsolutePath()
                + " openProjects=" + openProjects.length);
        for (Project p : openProjects) {
            File projDir = FileUtil.toFile(p.getProjectDirectory());
            String projName = ProjectUtils.getInformation(p).getDisplayName();
            LOG.info("  comparing with project '" + projName
                    + "' dir=" + (projDir != null ? projDir.getAbsolutePath() : "null")
                    + " equal=" + dir.equals(projDir));
        }
        for (Project p : openProjects) {
            File projDir = FileUtil.toFile(p.getProjectDirectory());
            if (projDir == null) continue;
            if (dir.equals(projDir)) {
                return ProjectUtils.getInformation(p).getDisplayName();
            }
            try {
                if (dir.getCanonicalPath().equals(projDir.getCanonicalPath())) {
                    LOG.info("  canonical match found for project '"
                            + ProjectUtils.getInformation(p).getDisplayName() + "'");
                    return ProjectUtils.getInformation(p).getDisplayName();
                }
            } catch (IOException e) {
                LOG.fine("canonical path comparison failed: " + e.getMessage());
            }
        }
        LOG.warning("resolveTabLabel: no project match for " + dir.getAbsolutePath());
        return "Claude Code";
    }

    // -------------------------------------------------------------------------
    // Event handlers
    // -------------------------------------------------------------------------

    private void onProfileSelected() {
        String name = getSelectedProfileName();
        ClaudeProfile p = ClaudeProfileStore.findByName(name);
        extraArgsField.setText(p != null ? p.getExtraCliArgs() : "");
        reloadSessionModePanel();
    }

    private void onProjectSelected() {
        if (suppressProjectListener) return;
        Object sel = projectCombo.getSelectedItem();
        if (sel instanceof ProjectItem item && item.project() != null) {
            File dir = FileUtil.toFile(item.project().getProjectDirectory());
            if (dir != null) {
                pathCombo.setSelectedItem(dir.getAbsolutePath());
                String assignedProfile = ClaudeProjectProperties.getProfileName(dir);
                if (!assignedProfile.isBlank()) {
                    profileCombo.setSelectedItem(assignedProfile);
                } else {
                    profileCombo.setSelectedIndex(0);
                }
                reloadSessionModePanel();
            }
        }
    }

    private void reloadSessionModePanel() {
        if (sessionModePanel != null) {
            sessionModePanel.reload(
                    resolveDir() != null ? resolveDir().toPath() : null,
                    resolveClaudeConfigDir(),
                    null);
        }
    }

    private File resolveDir() {
        Object item = pathCombo != null ? pathCombo.getSelectedItem() : null;
        if (item == null || item.toString().isBlank()) return null;
        File f = new File(item.toString().trim());
        return f.isDirectory() ? f : null;
    }

    private Path resolveClaudeConfigDir() {
        String profileName = getSelectedProfileName();
        if (ClaudeProfile.DEFAULT_NAME.equals(profileName)) return null;
        ClaudeProfile p = ClaudeProfileStore.findByName(profileName);
        if (p == null) return null;
        return io.github.nbclaudecodegui.settings.ClaudeProfileStore
                .resolveStorageDir(p, ClaudeCodePreferences.getProfilesDir());
    }

    private void onBrowse() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        Object cur = pathCombo.getSelectedItem();
        if (cur != null && !cur.toString().isBlank()) {
            File f = new File(cur.toString().trim());
            if (f.isDirectory()) chooser.setCurrentDirectory(f);
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            pathCombo.setSelectedItem(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void onOpen() {
        Object item = pathCombo.getSelectedItem();
        if (item == null || item.toString().isBlank()) {
            showError(NbBundle.getMessage(ClaudeSessionSelectorPanel.class, "ERR_PathEmpty"));
            return;
        }
        String text = item.toString().trim();
        File dir = new File(text);
        if (!dir.exists() || !dir.isDirectory()) {
            showError(NbBundle.getMessage(ClaudeSessionSelectorPanel.class, "ERR_PathNotFound"));
            return;
        }

        if (!isProjectDirectory(dir)) {
            saveToHistory(dir.getAbsolutePath());
        }

        errorLabel.setVisible(false);
        openListener.onOpen(dir, getSelectedProfileName(), extraArgsField.getText().trim(),
                sessionModePanel.getSelectedMode(),
                sessionModePanel.getSelectedSessionId());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Displays an error message below the control bar.
     *
     * @param message text to show (will be indented by two spaces)
     */
    public void showError(String message) {
        errorLabel.setText("  " + message);
        errorLabel.setVisible(true);
        revalidate();
        repaint();
    }

    private void setControlsEnabled(boolean enabled) {
        projectCombo.setEnabled(enabled);
        pathCombo.setEnabled(enabled);
        browseButton.setEnabled(enabled);
        openButton.setEnabled(enabled);
        extraArgsField.setEnabled(enabled);
        sessionModePanel.setEnabled(enabled);
    }

    private boolean isProjectDirectory(File dir) {
        for (Project p : OpenProjects.getDefault().getOpenProjects()) {
            if (dir.equals(FileUtil.toFile(p.getProjectDirectory()))) {
                return true;
            }
        }
        return false;
    }

    private void populateProjectCombo() {
        DefaultComboBoxModel<ProjectItem> cbModel = new DefaultComboBoxModel<>();
        cbModel.addElement(new ProjectItem(null,
                NbBundle.getMessage(ClaudeSessionSelectorPanel.class, "LBL_SelectProject")));
        // Sort projects alphabetically (case-insensitive) so the user can find
        // their project quickly regardless of the order NetBeans returns them.
        Project[] projects = OpenProjects.getDefault().getOpenProjects();
        Arrays.sort(projects, Comparator.comparing(
                p -> ProjectUtils.getInformation(p).getDisplayName(),
                String.CASE_INSENSITIVE_ORDER));
        for (Project p : projects) {
            cbModel.addElement(new ProjectItem(p,
                    ProjectUtils.getInformation(p).getDisplayName()));
        }
        projectCombo.setModel(cbModel);
    }

    private void populatePathHistory() {
        for (String p : loadHistory()) {
            pathCombo.addItem(p);
        }
    }

    private void populateProfileCombo() {
        DefaultComboBoxModel<String> cbModel = new DefaultComboBoxModel<>();
        for (ClaudeProfile p : ClaudeProfileStore.getProfiles()) {
            cbModel.addElement(p.getName());
        }
        profileCombo.setModel(cbModel);
    }

    private void preselectProjectForDirectory(File directory) {
        suppressProjectListener = true;
        try {
            for (int i = 0; i < projectCombo.getItemCount(); i++) {
                ProjectItem item = projectCombo.getItemAt(i);
                if (item.project() != null) {
                    File projDir = FileUtil.toFile(item.project().getProjectDirectory());
                    if (directory.equals(projDir)) {
                        projectCombo.setSelectedIndex(i);
                        return;
                    }
                }
            }
        } finally {
            suppressProjectListener = false;
        }
    }

    // -------------------------------------------------------------------------
    // History persistence
    // -------------------------------------------------------------------------

    /**
     * Loads the recently used path list from preferences.
     *
     * @return mutable list of path strings, possibly empty
     */
    static List<String> loadHistory() {
        Preferences prefs = NbPreferences.forModule(ClaudeSessionSelectorPanel.class);
        String raw = prefs.get(PREF_RECENT, "");
        List<String> list = new ArrayList<>();
        for (String p : raw.split(HISTORY_SEP, -1)) {
            if (!p.isBlank()) list.add(p);
        }
        return list;
    }

    /**
     * Prepends {@code path} to the persisted recent-path list, removing any
     * existing occurrence and trimming to {@link #HISTORY_SIZE} entries.
     *
     * @param path absolute path to record
     */
    static void saveToHistory(String path) {
        List<String> history = loadHistory();
        history.remove(path);
        history.add(0, path);
        if (history.size() > HISTORY_SIZE) {
            history = history.subList(0, HISTORY_SIZE);
        }
        NbPreferences.forModule(ClaudeSessionSelectorPanel.class)
                .put(PREF_RECENT, String.join(HISTORY_SEP, history));
    }

    // -------------------------------------------------------------------------
    // Inner types
    // -------------------------------------------------------------------------

    /** Associates a NetBeans {@link Project} with its display label. */
    private record ProjectItem(Project project, String label) {
        @Override public String toString() { return label; }
    }

    /** Renders {@link ProjectItem} entries in the project combo. */
    private static final class ProjectItemRenderer
            extends JLabel implements ListCellRenderer<ProjectItem> {

        ProjectItemRenderer() { setOpaque(true); }

        @Override
        public Component getListCellRendererComponent(
                JList<? extends ProjectItem> list, ProjectItem value,
                int index, boolean isSelected, boolean cellHasFocus) {
            setText(value == null ? "" : value.label());
            boolean placeholder = value == null || value.project() == null;
            setForeground(placeholder
                    ? (isSelected ? list.getSelectionForeground() : Color.GRAY)
                    : (isSelected ? list.getSelectionForeground() : list.getForeground()));
            setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
            setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
            return this;
        }
    }
}
