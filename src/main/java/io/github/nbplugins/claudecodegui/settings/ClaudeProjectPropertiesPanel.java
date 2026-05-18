package io.github.nbplugins.claudecodegui.settings;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.File;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * Project Properties panel for assigning a Claude Code profile to a project.
 *
 * <p>Shown under the "Claude Code" category in the Project Properties dialog.
 * The user selects a profile from a combo box that lists all configured
 * profiles; the Default profile is always first.  Selecting Default removes
 * any existing project-specific assignment.
 *
 * <p>The panel is instantiated and managed by
 * {@link ClaudeProjectPropertiesPanelProvider}.
 */
public final class ClaudeProjectPropertiesPanel extends JPanel {

    /** The project root directory. */
    private final File projectDir;
    /** Combo box for selecting the Claude profile to use for this project. */
    private final JComboBox<String> profileCombo;

    /**
     * Constructs the panel for the given project directory.
     *
     * @param projectDir project root directory; must not be {@code null}
     */
    public ClaudeProjectPropertiesPanel(File projectDir) {
        this.projectDir = projectDir;
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row.add(new JLabel("Profile:"));
        profileCombo = new JComboBox<>();
        profileCombo.setPrototypeDisplayValue("MMMMMMMMMMMMMMMMMMMMMMM");
        row.add(profileCombo);
        add(row, BorderLayout.NORTH);

        load();
    }

    // -------------------------------------------------------------------------
    // Load / store
    // -------------------------------------------------------------------------

    /**
     * Populates the combo box from {@link ClaudeProfileStore} and selects the
     * profile currently assigned to the project.
     */
    public void load() {
        List<ClaudeProfile> profiles = ClaudeProfileStore.getProfiles();
        DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
        for (ClaudeProfile p : profiles) {
            model.addElement(p.getName());
        }
        profileCombo.setModel(model);

        String assigned = ClaudeProjectProperties.getProfileName(projectDir);
        if (assigned.isBlank()) {
            profileCombo.setSelectedIndex(0); // Default
        } else {
            profileCombo.setSelectedItem(assigned);
            if (profileCombo.getSelectedIndex() < 0) {
                // Assigned profile no longer exists; fall back to Default
                profileCombo.setSelectedIndex(0);
            }
        }
    }

    /**
     * Persists the selected profile assignment for the project.
     */
    public void store() {
        String selected = (String) profileCombo.getSelectedItem();
        ClaudeProjectProperties.setProfileName(projectDir, selected);
    }

    /**
     * Returns the name of the currently selected profile.
     *
     * @return selected profile name, or {@code "Default"} if none selected
     */
    public String getSelectedProfileName() {
        Object sel = profileCombo.getSelectedItem();
        return sel != null ? sel.toString() : ClaudeProfile.DEFAULT_NAME;
    }
}
