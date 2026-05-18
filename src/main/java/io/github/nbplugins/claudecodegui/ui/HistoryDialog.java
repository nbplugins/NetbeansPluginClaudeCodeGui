package io.github.nbplugins.claudecodegui.ui;

import io.github.nbplugins.claudecodegui.model.FavoriteEntry;
import io.github.nbplugins.claudecodegui.model.HistoryEntry;
import io.github.nbplugins.claudecodegui.model.PromptFavoritesStore;
import io.github.nbplugins.claudecodegui.model.PromptHistoryStore;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Window;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;

/**
 * Dialog for browsing and managing prompt history.
 *
 * <p>Columns: Checkbox | Text | Time.
 * Buttons: Send | To Favorites | Delete | Clear older.
 */
public final class HistoryDialog extends JDialog {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /** Last dialog size; preserved across open/close cycles. */
    private static Dimension lastSize = new Dimension(620, 400);

    /** The prompt history store for the current working directory. */
    private final PromptHistoryStore   historyStore;
    /** The favorites store for the current working directory. */
    private final PromptFavoritesStore favStore;
    /** Callback invoked when the user selects an entry to send. */
    private final Consumer<String>     onSend;

    /** The inner panel containing the history list and action buttons. */
    private final HistoryPanel panel;

    /**
     * Creates a new prompt history dialog.
     *
     * @param owner      the parent window
     * @param workingDir the project working directory (used to scope history and favorites)
     * @param onSend     callback invoked when the user selects a history entry to send
     */
    public HistoryDialog(Window owner, Path workingDir, Consumer<String> onSend) {
        super(owner, "Prompt History", ModalityType.APPLICATION_MODAL);
        this.historyStore = PromptHistoryStore.getInstance(workingDir);
        this.favStore     = PromptFavoritesStore.getInstance(workingDir);
        this.onSend       = onSend;

        panel = new HistoryPanel();
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

    private final class HistoryPanel extends PromptListPanel {

        private List<HistoryEntry> currentEntries = new ArrayList<>();

        HistoryPanel() {
            JButton sendBtn        = new JButton("Send");
            JButton toFavBtn       = new JButton("To Favorites");
            JButton deleteBtn      = new JButton("Delete");
            JButton clearOlderBtn  = new JButton("Clear older\u2026");

            sendBtn.addActionListener(e -> doSend());
            toFavBtn.addActionListener(e -> doToFavorites());
            deleteBtn.addActionListener(e -> doDelete());
            clearOlderBtn.addActionListener(e -> doClearOlder());

            addButton(sendBtn);
            addButton(toFavBtn);
            addButton(deleteBtn);
            addButton(clearOlderBtn);

            table.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                    if (e.getClickCount() == 2) doSend();
                }
            });

            init();
        }

        @Override
        protected AbstractTableModel buildTableModel(String filter) {
            List<HistoryEntry> all = historyStore.getAll();
            currentEntries = filter.isBlank() ? new ArrayList<>(all)
                    : all.stream().filter(e -> e.getText().toLowerCase().contains(filter))
                            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            // Sort newest-first (already sorted by store, but keep explicit)
            return new HistoryTableModel(currentEntries);
        }

        @Override
        protected void configureColumns(javax.swing.JTable t) {
            if (t.getColumnCount() < 3) return;
            t.getColumnModel().getColumn(0).setMaxWidth(30);
            t.getColumnModel().getColumn(2).setPreferredWidth(130);
            t.getColumnModel().getColumn(2).setMaxWidth(160);
        }

        private void doSend() {
            List<Integer> checked = checkedRows();
            int row = checked.isEmpty() ? table.getSelectedRow() : checked.get(0);
            if (row < 0 || row >= currentEntries.size()) return;
            String text = currentEntries.get(row).getText();
            onSend.accept(text);
            dispose();
        }

        private void doToFavorites() {
            List<Integer> checked = checkedRows();
            if (checked.isEmpty()) {
                int row = table.getSelectedRow();
                if (row >= 0) checked = List.of(row);
            }
            for (int row : checked) {
                if (row < currentEntries.size()) {
                    favStore.addProject(FavoriteEntry.ofProject(currentEntries.get(row).getText()));
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
            List<HistoryEntry> toDelete = new ArrayList<>();
            for (int row : checked) {
                if (row < currentEntries.size()) toDelete.add(currentEntries.get(row));
            }
            historyStore.delete(toDelete);
            refreshTable();
        }

        private void doClearOlder() {
            int row = table.getSelectedRow();
            if (row < 0) {
                JOptionPane.showMessageDialog(HistoryDialog.this,
                        "Select a row first.", "No selection", JOptionPane.WARNING_MESSAGE);
                return;
            }
            java.time.Instant entryTime = currentEntries.get(row).getTime();
            String timeStr = entryTime.atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            int confirm = JOptionPane.showConfirmDialog(HistoryDialog.this,
                    "Delete all entries from " + timeStr + " and older?",
                    "Confirm", JOptionPane.OK_CANCEL_OPTION);
            if (confirm == JOptionPane.OK_OPTION) {
                java.time.Instant cutoff = entryTime.plusNanos(1);
                historyStore.deleteOlderThan(cutoff);
                refreshTable();
            }
        }
    }

    // -------------------------------------------------------------------------
    // Table model
    // -------------------------------------------------------------------------

    private static final class HistoryTableModel extends AbstractTableModel {

        private final List<HistoryEntry> entries;
        private final boolean[]          checked;

        HistoryTableModel(List<HistoryEntry> entries) {
            this.entries = entries;
            this.checked = new boolean[entries.size()];
        }

        @Override public int getRowCount()    { return entries.size(); }
        @Override public int getColumnCount() { return 3; }
        @Override public String getColumnName(int col) {
            return switch (col) { case 0 -> ""; case 1 -> "Prompt"; default -> "Time"; };
        }
        @Override public Class<?> getColumnClass(int col) {
            return col == 0 ? Boolean.class : String.class;
        }
        @Override public boolean isCellEditable(int row, int col) { return col == 0; }
        @Override public Object getValueAt(int row, int col) {
            return switch (col) {
                case 0 -> checked[row];
                case 1 -> truncate(entries.get(row).getText(), 120);
                default -> FMT.format(entries.get(row).getTime());
            };
        }
        @Override public void setValueAt(Object val, int row, int col) {
            if (col == 0) { checked[row] = Boolean.TRUE.equals(val); fireTableCellUpdated(row, col); }
        }

        private static String truncate(String s, int max) {
            return s.length() <= max ? s : s.substring(0, max) + "\u2026";
        }
    }
}
