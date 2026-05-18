package io.github.nbplugins.claudecodegui.ui;

import io.github.nbplugins.claudecodegui.model.FavoriteEntry;
import io.github.nbplugins.claudecodegui.model.PromptFavoritesStore;
import io.github.nbplugins.claudecodegui.ui.common.BasicTextContextMenu;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;

/**
 * Abstract base panel for displaying and managing {@link FavoriteEntry} lists.
 *
 * <p>Extends {@link PromptListPanel} and adds favorites-specific shared behaviour:
 * <ul>
 *   <li>Holds the {@link PromptFavoritesStore} and the current filtered entry list.</li>
 *   <li>Implements {@link #buildTableModel} by delegating to {@link #loadEntries()} and
 *       {@link #buildModel(List)}.</li>
 *   <li>Provides common operations: {@link #doEdit}, {@link #doAssignShortcut},
 *       {@link #doMoveProject}, {@link #doMoveGlobal}, {@link #singleSelectedRow},
 *       {@link #truncate}.</li>
 * </ul>
 *
 * <p>Subclasses must implement:
 * <ul>
 *   <li>{@link #loadEntries()} — returns the full (unfiltered) list for this view.</li>
 *   <li>{@link #buildModel(List)} — constructs the table model for the given entries.</li>
 * </ul>
 */
public abstract class FavoritesPanel extends PromptListPanel {

    /** The favorites store used to persist and retrieve entries. */
    protected final PromptFavoritesStore store;

    /** Currently displayed (possibly filtered) entries. Updated on every refresh. */
    protected List<FavoriteEntry> currentEntries = new ArrayList<>();

    /**
     * Creates a panel backed by the given favorites store.
     *
     * @param store the store used to load and persist favorites
     */
    protected FavoritesPanel(PromptFavoritesStore store) {
        this.store = store;
        // init() must be called here – after this.store is assigned – so that
        // the first buildTableModel() call (inside PromptListPanel.init) can
        // safely reach store via loadEntries().
        init();
    }

    // -------------------------------------------------------------------------
    // Template methods
    // -------------------------------------------------------------------------

    /**
     * Returns the full (unfiltered) entry list for this view.
     * Called by {@link #buildTableModel} on every refresh.
     *
     * @return full list of favorite entries for this view
     */
    protected abstract List<FavoriteEntry> loadEntries();

    /**
     * Builds the concrete {@link AbstractTableModel} for the given entry list.
     * Called by {@link #buildTableModel} after filtering.
     *
     * @param entries the (possibly filtered) list of entries to display
     * @return table model populated with the given entries
     */
    protected abstract AbstractTableModel buildModel(List<FavoriteEntry> entries);

    // -------------------------------------------------------------------------
    // PromptListPanel implementation
    // -------------------------------------------------------------------------

    @Override
    protected final AbstractTableModel buildTableModel(String filter) {
        List<FavoriteEntry> all = loadEntries();
        currentEntries = filter.isBlank() ? new ArrayList<>(all)
                : all.stream()
                        .filter(e -> e.getText().toLowerCase().contains(filter))
                        .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        return buildModel(currentEntries);
    }

    // -------------------------------------------------------------------------
    // Shared operations
    // -------------------------------------------------------------------------

    /**
     * Shows an input dialog to edit the text of the single selected entry,
     * then persists via {@link PromptFavoritesStore#update}.
     *
     * @param dialogParent component used as parent for {@link JOptionPane}
     */
    protected void doEdit(Component dialogParent) {
        int row = singleSelectedRow();
        if (row < 0 || row >= currentEntries.size()) return;
        FavoriteEntry entry = currentEntries.get(row);
        String newText = showMultiLineEditDialog(dialogParent, entry.getText());
        if (newText == null || newText.isBlank()) return;
        entry.setText(newText.trim());
        store.update(entry);
        refreshTable();
    }

    /**
     * Shows a modal multi-line edit dialog.
     *
     * @param parent      component used to determine dialog owner and position
     * @param initialText initial text to populate in the text area
     * @return trimmed text if the user confirmed, or {@code null} if cancelled
     */
    protected String showMultiLineEditDialog(Component parent, String initialText) {
        Window owner = SwingUtilities.getWindowAncestor(parent);
        JDialog dialog = new JDialog(owner, "Edit favorite", java.awt.Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JTextArea textArea = new JTextArea(initialText, 8, 40);
        BasicTextContextMenu.attach(textArea);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        JScrollPane scroll = new JScrollPane(textArea);

        String[] result = {null};

        JButton okBtn = new JButton("OK");
        JButton cancelBtn = new JButton("Cancel");

        okBtn.addActionListener(e -> {
            String text = textArea.getText().trim();
            if (!text.isBlank()) result[0] = text;
            dialog.dispose();
        });
        cancelBtn.addActionListener(e -> dialog.dispose());

        // Ctrl+Enter → confirm
        textArea.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, java.awt.event.InputEvent.CTRL_DOWN_MASK), "confirm");
        textArea.getActionMap().put("confirm", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { okBtn.doClick(); }
        });

        // Escape → cancel
        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW);

        dialog.getRootPane().setDefaultButton(okBtn);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 4));
        btnPanel.add(cancelBtn);
        btnPanel.add(okBtn);

        JPanel content = new JPanel(new BorderLayout(4, 4));
        content.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 8, 4, 8));
        content.add(scroll, BorderLayout.CENTER);
        content.add(btnPanel, BorderLayout.SOUTH);

        dialog.setContentPane(content);
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);

        return result[0];
    }

    /**
     * Opens {@link AssignShortcutDialog} for the single selected entry.
     * Shows a warning if more than one entry is checked.
     *
     * @param dialogParent component used as parent for dialogs
     */
    protected void doAssignShortcut(Component dialogParent) {
        int row = singleSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(dialogParent,
                    "Select exactly one entry to assign a shortcut.",
                    "Assign Shortcut", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (row >= currentEntries.size()) return;
        AssignShortcutDialog dlg = new AssignShortcutDialog(
                SwingUtilities.getWindowAncestor(dialogParent), store, currentEntries.get(row));
        dlg.setVisible(true);
        refreshTable();
    }

    /**
     * Moves the single selected PROJECT-scoped entry up ({@code delta = -1}) or
     * down ({@code delta = +1}) within the project ordering.
     * No-op if the selected entry is GLOBAL or if the move is out of bounds.
     *
     * @param delta {@code -1} to move up, {@code +1} to move down
     */
    protected void doMoveProject(int delta) {
        int row = singleSelectedRow();
        if (row < 0 || row >= currentEntries.size()) return;
        FavoriteEntry entry = currentEntries.get(row);
        if (entry.getScope() != FavoriteEntry.Scope.PROJECT) return;

        List<FavoriteEntry> projectOrder = currentEntries.stream()
                .filter(e -> e.getScope() == FavoriteEntry.Scope.PROJECT)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        int idx = projectOrder.indexOf(entry);
        int newIdx = idx + delta;
        if (newIdx < 0 || newIdx >= projectOrder.size()) return;
        projectOrder.remove(idx);
        projectOrder.add(newIdx, entry);
        store.reorder(projectOrder);
        refreshTable();
        // Re-select the moved entry
        for (int i = 0; i < currentEntries.size(); i++) {
            if (currentEntries.get(i).getId().equals(entry.getId())) {
                table.setRowSelectionInterval(i, i);
                break;
            }
        }
    }

    /**
     * Moves the single selected entry up ({@code delta = -1}) or down ({@code delta = +1})
     * within the global ordering.
     *
     * @param delta {@code -1} to move up, {@code +1} to move down
     */
    protected void doMoveGlobal(int delta) {
        int row = singleSelectedRow();
        if (row < 0 || row >= currentEntries.size()) return;
        int newRow = row + delta;
        if (newRow < 0 || newRow >= currentEntries.size()) return;
        FavoriteEntry entry = currentEntries.get(row);
        List<FavoriteEntry> reordered = new ArrayList<>(currentEntries);
        reordered.remove(row);
        reordered.add(newRow, entry);
        store.reorderGlobal(reordered);
        refreshTable();
        table.setRowSelectionInterval(newRow, newRow);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the index of the single selected row.
     *
     * <p>Priority: the first checked row; otherwise the table's selected row.
     * Returns {@code -1} if more than one row is checked (ambiguous selection).
     *
     * @return selected row index, or {@code -1} if ambiguous or none
     */
    protected int singleSelectedRow() {
        List<Integer> checked = checkedRows();
        if (checked.size() == 1) return checked.get(0);
        if (checked.isEmpty())   return table.getSelectedRow();
        return -1;
    }

    /**
     * Truncates {@code s} to {@code max} characters, appending "…" if needed.
     *
     * @param s   the string to truncate
     * @param max maximum number of characters allowed
     * @return the original string if short enough, otherwise a truncated string ending with "…"
     */
    public static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "\u2026";
    }
}
