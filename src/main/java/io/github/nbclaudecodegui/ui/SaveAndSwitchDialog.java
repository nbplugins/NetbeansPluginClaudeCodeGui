package io.github.nbclaudecodegui.ui;

import io.github.nbclaudecodegui.model.SessionMode;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Window;
import java.nio.file.Path;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingConstants;

/**
 * Modal dialog for saving the current session name and switching the session mode.
 *
 * <p>Allows the user to:
 * <ul>
 *   <li>Rename the current session (writes a {@code custom-title} entry on confirm)</li>
 *   <li>Choose what happens next: Close only / New session / Continue last / Resume specific</li>
 * </ul>
 *
 * <p>Default selection is "Close only".  OK is disabled when "Resume specific" is
 * selected but no session row is chosen.  Cancel closes the dialog without any action.
 */
public final class SaveAndSwitchDialog extends JDialog {

    /**
     * Functional interface for the confirm callback.
     *
     * @param <A> first param type (session name)
     * @param <B> second param type (session mode)
     * @param <C> third param type (resume session ID)
     */
    @FunctionalInterface
    public interface TriConsumer<A, B, C> {
        void accept(A a, B b, C c);
    }

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final JTextField nameField;
    private final SessionModePanel modePanel;
    private final JButton okButton;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * Creates a Save &amp; Switch dialog.
     *
     * @param owner              parent window
     * @param currentSessionName current session display name (pre-fills the name field)
     * @param currentSessionId   session ID to exclude from the session table
     * @param workingDir         working directory (used to load session list)
     * @param claudeConfigDir    Claude config dir ({@code null} → {@code ~/.claude})
     * @param initialMode        the session mode to pre-select
     * @param onConfirm          called with (sessionName, mode, resumeId) when OK is clicked
     */
    public SaveAndSwitchDialog(Window owner,
                               String currentSessionName,
                               String currentSessionId,
                               Path workingDir,
                               Path claudeConfigDir,
                               SessionMode initialMode,
                               TriConsumer<String, SessionMode, String> onConfirm) {
        super(owner, "Save & Switch", ModalityType.APPLICATION_MODAL);

        // --- Name field ---
        nameField = new JTextField(currentSessionName != null ? currentSessionName : "", 30);

        JPanel nameRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        nameRow.add(new JLabel("Save current session with name:"));
        nameRow.add(nameField);

        // --- Session mode panel ---
        modePanel = new SessionModePanel(workingDir, claudeConfigDir,
                currentSessionId, true);
        modePanel.setMode(initialMode != null ? initialMode : SessionMode.CLOSE_ONLY);

        // --- Buttons ---
        okButton = new JButton("OK");
        JButton cancelButton = new JButton("Cancel");

        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 4));
        buttonRow.add(okButton);
        buttonRow.add(cancelButton);

        // --- Layout ---
        JPanel content = new JPanel(new BorderLayout(4, 4));
        content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        content.add(nameRow, BorderLayout.NORTH);
        content.add(modePanel, BorderLayout.CENTER);
        content.add(buttonRow, BorderLayout.SOUTH);

        setContentPane(content);
        pack();
        setMinimumSize(getPreferredSize());
        setLocationRelativeTo(owner);

        // Double-click on session row → confirm
        modePanel.setOnDoubleClick(() -> okButton.doClick());

        // --- Validation ---
        updateOkButton();
        modePanel.addPropertyChangeListener(e -> updateOkButton());
        // Re-check on radio changes via table selection
        okButton.addPropertyChangeListener(e -> {});

        // Attach mode panel listener by overriding isSelectionValid polling via button
        // We listen to the mode panel's internal radio buttons indirectly:
        // SessionModePanel fires revalidate() which triggers layout, but we need a hook.
        // Use a simple approach: validate on every possible event via a shared runnable.
        Runnable validate = this::updateOkButton;
        for (java.awt.Component c : getAllComponents(modePanel)) {
            if (c instanceof javax.swing.JRadioButton rb) {
                rb.addActionListener(e2 -> validate.run());
            }
            if (c instanceof javax.swing.JTable t) {
                t.getSelectionModel().addListSelectionListener(e2 -> validate.run());
            }
        }

        // --- Actions ---
        okButton.addActionListener(e -> {
            String name = nameField.getText().trim();
            SessionMode mode = modePanel.getSelectedMode();
            String resumeId = modePanel.getSelectedSessionId();
            dispose();
            onConfirm.accept(name, mode, resumeId);
        });

        cancelButton.addActionListener(e -> dispose());

        getRootPane().setDefaultButton(okButton);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void updateOkButton() {
        okButton.setEnabled(modePanel.isSelectionValid());
    }

    /** Returns all nested components of the given container. */
    private static java.util.List<java.awt.Component> getAllComponents(java.awt.Container container) {
        java.util.List<java.awt.Component> list = new java.util.ArrayList<>();
        for (java.awt.Component c : container.getComponents()) {
            list.add(c);
            if (c instanceof java.awt.Container ct) {
                list.addAll(getAllComponents(ct));
            }
        }
        return list;
    }
}
