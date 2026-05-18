package io.github.nbplugins.claudecodegui.ui;

import io.github.nbplugins.claudecodegui.model.FavoriteEntry;
import io.github.nbplugins.claudecodegui.model.PromptFavoritesStore;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

/**
 * Dialog for assigning a keyboard shortcut to a favorite entry.
 *
 * <p>The user presses key combinations; the field accumulates them.
 * Format: {@code Ctrl+K Ctrl+F} (+ between modifiers/key inside, space between combos).
 * Conflict detection prevents assigning a shortcut already used by another entry.
 */
public final class AssignShortcutDialog extends JDialog {

    /** The favorites store used for conflict detection. */
    private final PromptFavoritesStore store;
    /** The favorite entry being edited. */
    private final FavoriteEntry        target;

    /** Field displaying the current shortcut sequence being recorded. */
    private final JTextField displayField;
    /** Label shown when a conflict is detected. */
    private final JLabel     conflictLabel;
    /** OK button, enabled only when no conflict exists. */
    private final JButton    okButton;

    /** Accumulated key combo strings (e.g. ["Ctrl+K", "Ctrl+F"]). */
    private final List<String> combos = new ArrayList<>();
    /** Whether key-press capturing is currently active. */
    private boolean            capturing = false;

    /**
     * Creates the dialog.
     *
     * @param owner  parent window (for modality)
     * @param store  favorites store used for conflict detection
     * @param target the favorite entry being edited
     */
    public AssignShortcutDialog(Window owner, PromptFavoritesStore store, FavoriteEntry target) {
        super(owner, "Assign Shortcut", ModalityType.APPLICATION_MODAL);
        this.store  = store;
        this.target = target;

        // Display field — shows current shortcut, read-only for editing
        displayField = new JTextField(24);
        displayField.setEditable(false);
        displayField.setFocusable(true);
        if (target.getShortcut() != null) {
            displayField.setText(target.getShortcut());
        }

        conflictLabel = new JLabel(" ");
        conflictLabel.setForeground(java.awt.Color.RED);

        okButton = new JButton("OK");
        JButton clearBtn  = new JButton("Clear");
        JButton cancelBtn = new JButton("Cancel");

        okButton.addActionListener(e -> doOk());
        clearBtn.addActionListener(e -> doClear());
        cancelBtn.addActionListener(e -> dispose());

        displayField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                e.consume();
                int code = e.getKeyCode();
                // Ignore lone modifier keys
                if (code == KeyEvent.VK_CONTROL || code == KeyEvent.VK_SHIFT
                        || code == KeyEvent.VK_ALT || code == KeyEvent.VK_META) return;
                if (!capturing) {
                    combos.clear();
                    capturing = true;
                }
                combos.add(buildComboString(e));
                String shortcut = String.join(" ", combos);
                displayField.setText(shortcut);
                checkConflict(shortcut);
            }
        });

        // Layout
        JPanel top = new JPanel(new BorderLayout(4, 4));
        top.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
        top.add(new JLabel("Press key combination(s), then click OK:"), BorderLayout.NORTH);
        top.add(displayField, BorderLayout.CENTER);
        top.add(conflictLabel, BorderLayout.SOUTH);

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 4));
        btnRow.add(clearBtn);
        btnRow.add(okButton);
        btnRow.add(cancelBtn);

        setLayout(new BorderLayout());
        add(top, BorderLayout.CENTER);
        add(btnRow, BorderLayout.SOUTH);

        pack();
        setResizable(false);
        setLocationRelativeTo(owner);
        displayField.requestFocusInWindow();

        checkConflict(displayField.getText());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String buildComboString(KeyEvent e) {
        StringBuilder sb = new StringBuilder();
        if (e.isControlDown()) sb.append("Ctrl+");
        if (e.isShiftDown())   sb.append("Shift+");
        if (e.isAltDown())     sb.append("Alt+");
        if (e.isMetaDown())    sb.append("Meta+");
        sb.append(KeyEvent.getKeyText(e.getKeyCode()));
        return sb.toString();
    }

    private void checkConflict(String shortcut) {
        if (shortcut == null || shortcut.isBlank()) {
            conflictLabel.setText(" ");
            okButton.setEnabled(true);
            return;
        }
        // Project entries take priority; check all entries for conflict
        for (FavoriteEntry e : store.getAllWithShortcuts()) {
            if (e.getId().equals(target.getId())) continue; // same entry, not a conflict
            if (shortcut.equals(e.getShortcut())) {
                conflictLabel.setText("Conflict: already used by \""
                        + truncate(e.getText(), 30) + "\" (" + e.getScope().name() + ")");
                okButton.setEnabled(false);
                return;
            }
        }
        conflictLabel.setText(" ");
        okButton.setEnabled(true);
    }

    private void doClear() {
        combos.clear();
        capturing = false;
        displayField.setText("");
        conflictLabel.setText(" ");
        okButton.setEnabled(true);
        displayField.requestFocusInWindow();
    }

    private void doOk() {
        String shortcut = displayField.getText().trim();
        target.setShortcut(shortcut.isEmpty() ? null : shortcut);
        store.update(target);
        dispose();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "\u2026";
    }
}
