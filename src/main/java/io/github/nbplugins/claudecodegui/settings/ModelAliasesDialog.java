package io.github.nbplugins.claudecodegui.settings;

import io.github.nbplugins.claudecodegui.ui.common.BasicTextContextMenu;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

/**
 * Modal dialog for managing the model alias list for a connection profile.
 */
public final class ModelAliasesDialog extends JDialog {

    private static final Logger LOG =
            Logger.getLogger(ModelAliasesDialog.class.getName());

    private static final String[] ALIASES = {"", "sonnet", "opus", "haiku", "custom"};

    /**
     * Fetches the list of available model IDs for the "Fetch" button.
     * Called off the EDT, inside this dialog's own {@link SwingWorker} — implementations
     * may perform blocking network I/O directly.
     */
    public interface ModelFetcher {
        /**
         * Fetches the current list of available model IDs.
         *
         * @return model IDs; never {@code null}
         * @throws Exception on any fetch failure (network, auth, parse, etc.) —
         *         the message is shown to the user via the dialog's status label
         */
        List<String> fetch() throws Exception;
    }

    /** Strategy used by the "Fetch" button. */
    private final ModelFetcher fetcher;

    /** Table model for the model alias rows. */
    private DefaultTableModel tableModel;
    /** Table displaying model aliases. */
    private JTable table;
    /**
     * Read-only status area (fetch progress / errors). Implemented as a selectable
     * {@link JTextArea} so the user can copy error text to the clipboard.
     */
    private JTextArea statusArea;

    /** Non-null after the user clicks OK; null if cancelled. */
    private List<ModelAlias> result = null;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * Creates a new model aliases dialog that fetches from an OpenAI-compatible
     * {@code /v1/models} endpoint using an API key.
     *
     * @param parent  parent component for centering
     * @param baseUrl base URL of the Other API endpoint
     * @param apiKey  API key used in the Authorization header
     * @param initial pre-existing model aliases to populate the table with
     */
    public ModelAliasesDialog(Component parent, String baseUrl, String apiKey,
                              List<ModelAlias> initial) {
        this(parent, httpModelFetcher(baseUrl, apiKey), initial);
    }

    /**
     * Creates a new model aliases dialog with a custom fetch strategy — used for
     * connection types (e.g. ChatGPT Subscription) that don't fetch via a plain
     * API-key-authenticated {@code /v1/models} endpoint.
     *
     * @param parent  parent component for centering
     * @param fetcher strategy invoked by the "Fetch" button
     * @param initial pre-existing model aliases to populate the table with
     */
    public ModelAliasesDialog(Component parent, ModelFetcher fetcher, List<ModelAlias> initial) {
        super(JOptionPane.getFrameForComponent(parent), "Model Aliases", true);
        this.fetcher = fetcher;

        initComponents(initial);
        pack();
        setMinimumSize(new Dimension(600, 400));
        setLocationRelativeTo(parent);
    }

    /**
     * Default {@link ModelFetcher} for OpenAI-compatible providers: {@code GET
     * {baseUrl}/v1/models} with {@code Authorization: Bearer <apiKey>}.
     */
    private static ModelFetcher httpModelFetcher(String baseUrl, String apiKey) {
        String url = baseUrl == null ? "" : baseUrl.trim();
        String key = apiKey  == null ? "" : apiKey.trim();
        return () -> {
            if (url.isBlank()) {
                throw new IllegalStateException("Base URL is empty — cannot fetch.");
            }
            String fetchUrl = url.replaceAll("/+$", "") + "/v1/models";
            LOG.fine("Fetch models: GET " + fetchUrl);

            HttpURLConnection conn = (HttpURLConnection) new URL(fetchUrl).openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(15_000);
            conn.setRequestMethod("GET");
            if (!key.isBlank()) {
                conn.setRequestProperty("Authorization", "Bearer " + key);
            }
            conn.setRequestProperty("Accept", "application/json");

            int status = conn.getResponseCode();
            String body;
            try (InputStream is = status < 400 ? conn.getInputStream() : conn.getErrorStream()) {
                body = is == null ? "" : new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            LOG.fine("Fetch models: response body: " + body);
            if (status < 200 || status >= 300) {
                throw new IOException("Fetch models: failed HTTP " + status);
            }
            return parseModelIds(body);
        };
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns the model alias list accepted by the user, or {@code null} if cancelled.
     *
     * @return accepted model alias list, or {@code null} if the dialog was cancelled
     */
    public List<ModelAlias> getModels() {
        return result;
    }

    // -------------------------------------------------------------------------
    // UI construction
    // -------------------------------------------------------------------------

    private void initComponents(List<ModelAlias> initial) {
        setLayout(new BorderLayout(8, 8));
        getRootPane().setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // --- Table ---
        tableModel = new DefaultTableModel(new String[]{"ID", "Available", "Alias"}, 0) {
            @Override public boolean isCellEditable(int row, int col) { return col == 2; }
            @Override public Class<?> getColumnClass(int col) {
                return col == 1 ? Boolean.class : String.class;
            }
        };
        table = new JTable(tableModel);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(0).setPreferredWidth(240);
        table.getColumnModel().getColumn(1).setPreferredWidth(70);
        table.getColumnModel().getColumn(2).setPreferredWidth(90);

        // Available column renderer: null→"", true→✓ green, false→✗ red
        table.getColumnModel().getColumn(1).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value,
                    boolean selected, boolean focus, int row, int col) {
                super.getTableCellRendererComponent(t, "", selected, focus, row, col);
                setHorizontalAlignment(CENTER);
                if (Boolean.TRUE.equals(value)) {
                    setText("\u2713");
                    setForeground(selected ? getForeground() : new java.awt.Color(0, 140, 0));
                } else if (Boolean.FALSE.equals(value)) {
                    setText("\u2717");
                    setForeground(selected ? getForeground() : java.awt.Color.RED);
                } else {
                    setText("");
                    setForeground(getForeground());
                }
                return this;
            }
        });

        // Alias column: JComboBox editor
        JComboBox<String> aliasCombo = new JComboBox<>(ALIASES);
        table.getColumnModel().getColumn(2).setCellEditor(new DefaultCellEditor(aliasCombo));

        // Drag-and-drop reordering
        table.setDragEnabled(true);
        table.setDropMode(javax.swing.DropMode.INSERT_ROWS);
        table.setTransferHandler(new TableRowTransferHandler(table));

        // Populate
        for (ModelAlias m : initial) {
            tableModel.addRow(new Object[]{m.id(), m.available(), m.alias()});
        }

        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(440, 220));

        // --- Button panel (right, vertical) ---
        JButton fetchBtn  = new JButton("Fetch");
        JButton addBtn    = new JButton("Add");
        JButton renameBtn = new JButton("Rename");
        JButton upBtn     = new JButton("\u2191");
        JButton downBtn   = new JButton("\u2193");
        JButton sortBtn   = new JButton("Sort");
        JButton deleteBtn = new JButton("Delete");
        JButton pruneBtn  = new JButton("Prune");

        for (JButton b : new JButton[]{fetchBtn, addBtn, renameBtn, upBtn, downBtn, sortBtn, deleteBtn, pruneBtn}) {
            b.setAlignmentX(Component.CENTER_ALIGNMENT);
            b.setMaximumSize(new Dimension(80, 28));
        }

        fetchBtn .addActionListener(e -> onFetch());
        addBtn   .addActionListener(e -> onAdd());
        renameBtn.addActionListener(e -> onRename());
        upBtn    .addActionListener(e -> moveRow(-1));
        downBtn  .addActionListener(e -> moveRow(+1));
        sortBtn  .addActionListener(e -> onSort());
        deleteBtn.addActionListener(e -> onDelete());
        pruneBtn .addActionListener(e -> onPrune());

        JPanel btnPanel = new JPanel();
        btnPanel.setLayout(new BoxLayout(btnPanel, BoxLayout.Y_AXIS));
        btnPanel.add(fetchBtn);
        btnPanel.add(Box.createVerticalStrut(4));
        btnPanel.add(addBtn);
        btnPanel.add(Box.createVerticalStrut(2));
        btnPanel.add(renameBtn);
        btnPanel.add(Box.createVerticalStrut(4));
        btnPanel.add(upBtn);
        btnPanel.add(Box.createVerticalStrut(2));
        btnPanel.add(downBtn);
        btnPanel.add(Box.createVerticalStrut(4));
        btnPanel.add(sortBtn);
        btnPanel.add(Box.createVerticalStrut(4));
        btnPanel.add(deleteBtn);
        btnPanel.add(Box.createVerticalStrut(4));
        btnPanel.add(pruneBtn);
        btnPanel.add(Box.createVerticalGlue());

        JPanel center = new JPanel(new BorderLayout(4, 0));
        center.add(scroll, BorderLayout.CENTER);
        center.add(btnPanel, BorderLayout.EAST);
        add(center, BorderLayout.CENTER);

        // --- Status area (wraps long error text; selectable/copyable) and OK / Cancel.
        // Both go into one SOUTH panel — BorderLayout.SOUTH and PAGE_END are the
        // same slot, so putting them separately made the status line invisible.
        statusArea = new JTextArea(" ");
        statusArea.setEditable(false);
        statusArea.setFocusable(true);
        statusArea.setLineWrap(true);
        statusArea.setWrapStyleWord(true);
        statusArea.setOpaque(false);
        statusArea.setBorder(new EmptyBorder(2, 2, 2, 2));
        statusArea.setRows(3);
        // Match dialog body font rather than monospaced editor default.
        statusArea.setFont(UIManager.getFont("Label.font"));
        statusArea.setForeground(UIManager.getColor("Label.foreground"));
        BasicTextContextMenu.attach(statusArea, BasicTextContextMenu.createReadOnly(statusArea));

        JButton okBtn     = new JButton("OK");
        JButton cancelBtn = new JButton("Cancel");
        okBtn    .addActionListener(e -> onOk());
        cancelBtn.addActionListener(e -> dispose());
        getRootPane().setDefaultButton(okBtn);

        JPanel okPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        okPanel.add(okBtn);
        okPanel.add(cancelBtn);

        JPanel south = new JPanel(new BorderLayout(0, 4));
        south.add(new JScrollPane(statusArea,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER), BorderLayout.CENTER);
        south.add(okPanel, BorderLayout.SOUTH);
        add(south, BorderLayout.SOUTH);
    }

    // -------------------------------------------------------------------------
    // Button handlers
    // -------------------------------------------------------------------------

    private void onFetch() {
        setStatus("Fetching...", false);

        new SwingWorker<List<String>, Void>() {
            @Override protected List<String> doInBackground() throws Exception {
                return fetcher.fetch();
            }

            @Override protected void done() {
                try {
                    List<String> ids = get();
                    applyFetchedIds(ids);
                    LOG.info("Fetch models: " + ids.size() + " models returned");
                    setStatus("Fetched " + ids.size() + " models, " + countAvailable() + " available", false);
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    String msg = "Fetch models: failed — " + cause.getMessage();
                    LOG.warning(msg);
                    setStatus(msg, true);
                }
            }
        }.execute();
    }

    /**
     * Sets the status text. The area is selectable / copyable (Ctrl+C or
     * right-click → Copy via {@link BasicTextContextMenu#createReadOnly}) so
     * upstream error bodies can be pasted into a bug report. Errors show in red.
     *
     * @param text  status / error text
     * @param error {@code true} to render in red
     */
    private void setStatus(String text, boolean error) {
        statusArea.setText(text == null || text.isBlank() ? " " : text);
        statusArea.setCaretPosition(0);
        statusArea.setToolTipText(text);
        Color fg = error ? Color.RED.darker() : UIManager.getColor("Label.foreground");
        statusArea.setForeground(fg != null ? fg : Color.BLACK);
    }

    private void setStatus(String text) {
        setStatus(text, false);
    }

    /** Parses {@code data[].id} from an OpenAI-compatible {@code /v1/models} response. */
    static List<String> parseModelIds(String json) {
        List<String> ids = new ArrayList<>();
        if (json == null || json.isBlank()) return ids;
        // Simple regex-free scan: find all "id":"..." inside the "data" array
        int dataIdx = json.indexOf("\"data\"");
        if (dataIdx < 0) dataIdx = 0;
        int pos = dataIdx;
        while (true) {
            int idIdx = json.indexOf("\"id\"", pos);
            if (idIdx < 0) break;
            int colon = json.indexOf(':', idIdx + 4);
            if (colon < 0) break;
            // skip whitespace
            int start = colon + 1;
            while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
            if (start >= json.length() || json.charAt(start) != '"') { pos = colon + 1; continue; }
            int end = json.indexOf('"', start + 1);
            if (end < 0) break;
            String id = json.substring(start + 1, end);
            if (!id.isBlank()) ids.add(id);
            pos = end + 1;
        }
        return ids;
    }

    /** Updates the Available column: marks existing rows, appends new ones. */
    private void applyFetchedIds(List<String> fetchedIds) {
        Set<String> fetched = new HashSet<>(fetchedIds);

        // Mark existing rows
        Set<String> existing = new HashSet<>();
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            String id = (String) tableModel.getValueAt(i, 0);
            existing.add(id);
            tableModel.setValueAt(fetched.contains(id), i, 1);
        }

        // Append new rows not already present
        for (String id : fetchedIds) {
            if (!existing.contains(id)) {
                tableModel.addRow(new Object[]{id, Boolean.TRUE, ""});
            }
        }
    }

    private int countAvailable() {
        int count = 0;
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            if (Boolean.TRUE.equals(tableModel.getValueAt(i, 1))) count++;
        }
        return count;
    }

    private void moveRow(int delta) {
        stopEditing();
        int sel = table.getSelectedRow();
        if (sel < 0) return;
        int target = sel + delta;
        if (target < 0 || target >= tableModel.getRowCount()) return;
        tableModel.moveRow(sel, sel, target);
        table.setRowSelectionInterval(target, target);
    }

    private void onSort() {
        stopEditing();
        List<ModelAlias> rows = collectModels();
        rows.sort((a, b) -> a.id().compareToIgnoreCase(b.id()));
        tableModel.setRowCount(0);
        for (ModelAlias m : rows) {
            tableModel.addRow(new Object[]{m.id(), m.available(), m.alias()});
        }
    }

    private void onDelete() {
        stopEditing();
        int sel = table.getSelectedRow();
        if (sel >= 0) tableModel.removeRow(sel);
    }

    private void onPrune() {
        stopEditing();
        for (int i = tableModel.getRowCount() - 1; i >= 0; i--) {
            if (Boolean.FALSE.equals(tableModel.getValueAt(i, 1))) {
                tableModel.removeRow(i);
            }
        }
    }

    private void onAdd() {
        String id = JOptionPane.showInputDialog(this, "Model ID:", "Add Model", JOptionPane.PLAIN_MESSAGE);
        if (id == null) return;
        id = id.trim();
        String error = validateModelId(id, -1);
        if (error != null) { setStatus(error); return; }
        tableModel.addRow(new Object[]{id, null, ""});
        int newRow = tableModel.getRowCount() - 1;
        table.setRowSelectionInterval(newRow, newRow);
        table.scrollRectToVisible(table.getCellRect(newRow, 0, true));
        setStatus(" ");
    }

    private void onRename() {
        stopEditing();
        int sel = table.getSelectedRow();
        if (sel < 0) return;
        String current = (String) tableModel.getValueAt(sel, 0);
        String newId = (String) JOptionPane.showInputDialog(
                this, "Model ID:", "Rename Model", JOptionPane.PLAIN_MESSAGE, null, null, current);
        if (newId == null) return;
        newId = newId.trim();
        if (newId.equals(current)) return;
        String error = validateModelId(newId, sel);
        if (error != null) { setStatus(error); return; }
        tableModel.setValueAt(newId, sel, 0);
        tableModel.setValueAt(null, sel, 1);
        setStatus(" ");
    }

    String validateModelId(String id, int skipRow) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            ids.add((String) tableModel.getValueAt(i, 0));
        }
        return validateModelId(id, ids, skipRow);
    }

    /**
     * Returns an error message if {@code id} is blank or already exists in
     * {@code existingIds} (excluding {@code skipRow}), or {@code null} if valid.
     */
    static String validateModelId(String id, List<String> existingIds, int skipRow) {
        if (id.isBlank()) return "Model ID must not be blank.";
        for (int i = 0; i < existingIds.size(); i++) {
            if (i != skipRow && id.equals(existingIds.get(i))) {
                return "Model already in the list: " + id;
            }
        }
        return null;
    }

    private void onOk() {
        stopEditing();
        List<ModelAlias> models = collectModels();

        // Validate alias uniqueness
        String error = ModelAlias.validateAliasUniqueness(models);
        if (error != null) {
            JOptionPane.showMessageDialog(this, error, "Duplicate Alias", JOptionPane.ERROR_MESSAGE);
            return;
        }

        result = models;
        dispose();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void stopEditing() {
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
    }

    private List<ModelAlias> collectModels() {
        List<ModelAlias> models = new ArrayList<>();
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            String id    = (String)  tableModel.getValueAt(i, 0);
            Boolean avail = (Boolean) tableModel.getValueAt(i, 1);
            String alias = (String)  tableModel.getValueAt(i, 2);
            if (id != null && !id.isBlank()) {
                models.add(new ModelAlias(id, avail, alias == null ? "" : alias));
            }
        }
        return models;
    }

    // -------------------------------------------------------------------------
    // Drag-and-drop TransferHandler for row reordering
    // -------------------------------------------------------------------------

    private static final class TableRowTransferHandler extends javax.swing.TransferHandler {
        private final JTable target;
        private int[] rows = null;
        private int addIndex = -1;

        TableRowTransferHandler(JTable table) {
            this.target = table;
        }

        @Override
        public int getSourceActions(javax.swing.JComponent c) {
            return MOVE;
        }

        @Override
        protected java.awt.datatransfer.Transferable createTransferable(
                javax.swing.JComponent c) {
            rows = target.getSelectedRows();
            return new java.awt.datatransfer.StringSelection(
                    rows != null && rows.length > 0 ? String.valueOf(rows[0]) : "");
        }

        @Override
        public boolean canImport(TransferSupport info) {
            return info.isDrop() && info.getComponent() == target;
        }

        @Override
        public boolean importData(TransferSupport info) {
            if (!canImport(info) || rows == null || rows.length == 0) return false;
            JTable.DropLocation dl = (JTable.DropLocation) info.getDropLocation();
            int dest = dl.getRow();
            DefaultTableModel model = (DefaultTableModel) target.getModel();
            int rowCount = model.getRowCount();
            if (dest < 0 || dest > rowCount) return false;
            // Move row
            addIndex = dest;
            int src = rows[0];
            if (src == dest || src + 1 == dest) return false;
            model.moveRow(src, src, dest > src ? dest - 1 : dest);
            int newSel = dest > src ? dest - 1 : dest;
            target.setRowSelectionInterval(newSel, newSel);
            return true;
        }

        @Override
        protected void exportDone(javax.swing.JComponent c,
                java.awt.datatransfer.Transferable data, int action) {
            rows = null;
            addIndex = -1;
        }
    }
}
