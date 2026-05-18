package io.github.nbplugins.claudecodegui.ui;

import io.github.nbplugins.claudecodegui.model.FavoriteEntry;
import io.github.nbplugins.claudecodegui.model.FavoriteEntry.Scope;
import io.github.nbplugins.claudecodegui.model.PromptFavoritesStore;
import java.awt.Dimension;
import java.awt.Window;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.table.AbstractTableModel;

/**
 * Dialog for browsing and managing favorites.
 *
 * <p>Columns: Checkbox | Text | Shortcut | Scope.
 * Buttons: Send | Edit | To Global | Assign Shortcut | ↑ | ↓ | Delete.
 */
public final class FavoritesDialog extends JDialog {

    /** Last dialog size; preserved across open/close cycles. */
    private static Dimension lastSize = new Dimension(680, 420);

    /** The favorites store for the current working directory. */
    private final PromptFavoritesStore store;
    /** Callback invoked when the user selects a favorite to send. */
    private final Consumer<String>     onSend;

    /** The inner panel containing the project and global favorite tabs. */
    private final DialogPanel panel;

    /**
     * Creates a new favorites dialog.
     *
     * @param owner      the parent window
     * @param workingDir the project working directory (used to scope favorites)
     * @param onSend     callback invoked when the user selects a favorite to send
     */
    public FavoritesDialog(Window owner, Path workingDir, Consumer<String> onSend) {
        super(owner, "Favorites", ModalityType.APPLICATION_MODAL);
        this.store  = PromptFavoritesStore.getInstance(workingDir);
        this.onSend = onSend;

        panel = new DialogPanel(this.store);
        setContentPane(panel);

        setSize(lastSize);
        setLocationRelativeTo(owner);
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent e) {
                lastSize = getSize();
            }
        });
    }

    // -------------------------------------------------------------------------
    // Inner panel
    // -------------------------------------------------------------------------

    private final class DialogPanel extends FavoritesPanel {

        DialogPanel(PromptFavoritesStore s) {
            super(s);

            JButton sendBtn     = new JButton("Send");
            JButton editBtn     = new JButton("Edit");
            JButton toGlobalBtn = new JButton("To Global");
            JButton shortcutBtn = new JButton("Assign Shortcut");
            JButton upBtn       = new JButton("\u2191");
            JButton downBtn     = new JButton("\u2193");
            JButton deleteBtn   = new JButton("Delete");

            sendBtn.addActionListener(e -> doSend());
            editBtn.addActionListener(e -> doEdit(FavoritesDialog.this));
            toGlobalBtn.addActionListener(e -> doToGlobal());
            shortcutBtn.addActionListener(e -> doAssignShortcut(FavoritesDialog.this));
            upBtn.addActionListener(e -> doMoveProject(-1));
            downBtn.addActionListener(e -> doMoveProject(1));
            deleteBtn.addActionListener(e -> doDelete());

            addButton(sendBtn);
            addButton(editBtn);
            addButton(toGlobalBtn);
            addButton(shortcutBtn);
            addButton(upBtn);
            addButton(downBtn);
            addButton(deleteBtn);

            table.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                    if (e.getClickCount() == 2) doSend();
                }
            });
        }

        @Override
        protected List<FavoriteEntry> loadEntries() {
            return store.getAll();
        }

        @Override
        protected AbstractTableModel buildModel(List<FavoriteEntry> entries) {
            return new FavoritesTableModel(entries);
        }

        @Override
        protected void configureColumns(javax.swing.JTable t) {
            if (t.getColumnCount() < 4) return;
            t.getColumnModel().getColumn(0).setMaxWidth(30);
            t.getColumnModel().getColumn(2).setPreferredWidth(130);
            t.getColumnModel().getColumn(2).setMaxWidth(200);
            t.getColumnModel().getColumn(3).setPreferredWidth(70);
            t.getColumnModel().getColumn(3).setMaxWidth(80);
        }

        private void doSend() {
            int row = singleSelectedRow();
            if (row < 0 || row >= currentEntries.size()) return;
            String text = currentEntries.get(row).getText();
            onSend.accept(text);
            dispose();
        }

        private void doToGlobal() {
            List<Integer> checked = checkedRows();
            if (checked.isEmpty()) {
                int row = table.getSelectedRow();
                if (row >= 0) checked = List.of(row);
            }
            // Check that all selected are PROJECT
            for (int row : checked) {
                if (row < currentEntries.size()
                        && currentEntries.get(row).getScope() == Scope.GLOBAL) {
                    JOptionPane.showMessageDialog(FavoritesDialog.this,
                            "Cannot move Global favorites to Global.",
                            "To Global", JOptionPane.WARNING_MESSAGE);
                    return;
                }
            }
            for (int row : checked) {
                if (row < currentEntries.size()) {
                    store.toGlobal(currentEntries.get(row));
                }
            }
            refreshTable();
        }

        private void doDelete() {
            List<Integer> checked = checkedRows();
            if (checked.isEmpty()) {
                int row = table.getSelectedRow();
                if (row >= 0) checked = List.of(row);
            }
            // Validate: only project entries can be deleted here
            List<FavoriteEntry> toDelete = new ArrayList<>();
            for (int row : checked) {
                if (row < currentEntries.size()) {
                    FavoriteEntry e = currentEntries.get(row);
                    if (e.getScope() == Scope.GLOBAL) {
                        JOptionPane.showMessageDialog(FavoritesDialog.this,
                                "Cannot delete Global favorites here.\n"
                                + "Use Settings \u2192 Favorites to manage global favorites.",
                                "Delete", JOptionPane.WARNING_MESSAGE);
                        return;
                    }
                    toDelete.add(e);
                }
            }
            if (toDelete.isEmpty()) return;
            store.delete(toDelete);
            refreshTable();
        }
    }

    // -------------------------------------------------------------------------
    // Table model
    // -------------------------------------------------------------------------

    private static final class FavoritesTableModel extends AbstractTableModel {

        private final List<FavoriteEntry> entries;
        private final boolean[]           checked;

        FavoritesTableModel(List<FavoriteEntry> entries) {
            this.entries = entries;
            this.checked = new boolean[entries.size()];
        }

        @Override public int getRowCount()    { return entries.size(); }
        @Override public int getColumnCount() { return 4; }
        @Override public String getColumnName(int col) {
            return switch (col) { case 0 -> ""; case 1 -> "Text"; case 2 -> "Shortcut"; default -> "Scope"; };
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
                case 2 -> e.getShortcut() != null ? e.getShortcut() : "";
                default -> e.getScope().name();
            };
        }
        @Override public void setValueAt(Object val, int row, int col) {
            if (col == 0) { checked[row] = Boolean.TRUE.equals(val); fireTableCellUpdated(row, col); }
        }
    }
}
