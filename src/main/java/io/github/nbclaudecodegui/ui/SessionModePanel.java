package io.github.nbclaudecodegui.ui;

import io.github.nbclaudecodegui.model.SavedSession;
import io.github.nbclaudecodegui.model.SessionMode;
import io.github.nbclaudecodegui.process.ClaudeSessionStore;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.logging.Logger;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;

/**
 * Reusable panel for selecting how a Claude Code session is started.
 *
 * <p>Shows radio buttons for session mode and, when "Resume specific" is
 * selected, a table of available sessions.
 *
 * <p>Construct with {@code showCloseOnly=false} for the session selector (no
 * "Close only" option) and {@code showCloseOnly=true} for the Save &amp; Switch
 * dialog.
 */
public final class SessionModePanel extends JPanel {

    private static final Logger LOG = Logger.getLogger(SessionModePanel.class.getName());
    private static final SimpleDateFormat DATE_FMT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm");

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private Path workingDir;
    private Path claudeConfigDir;
    private String excludeSession;

    // -------------------------------------------------------------------------
    // UI
    // -------------------------------------------------------------------------

    private final JRadioButton closeOnlyRadio;
    private final JRadioButton newRadio;
    private final JRadioButton continueRadio;
    private final JRadioButton resumeRadio;

    private final JTable sessionTable;
    private final SessionTableModel tableModel;
    private final JScrollPane tableScroll;
    private final JButton renameButton;

    /** Called on double-click when resumeRadio is selected; may be {@code null}. */
    private Runnable onDoubleClick;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * Creates a new session-mode panel.
     *
     * @param workingDir      session working directory; may be {@code null}
     * @param claudeConfigDir resolved config dir for the active profile; {@code null} → {@code ~/.claude}
     * @param excludeSession  session ID to exclude from the table (current session), or {@code null}
     * @param showCloseOnly   if {@code true}, adds a "Close only" radio at the top
     */
    public SessionModePanel(Path workingDir, Path claudeConfigDir,
                            String excludeSession, boolean showCloseOnly) {
        this.workingDir = workingDir;
        this.claudeConfigDir = claudeConfigDir;
        this.excludeSession = excludeSession;

        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));

        ButtonGroup group = new ButtonGroup();

        closeOnlyRadio = new JRadioButton("Close only");
        newRadio       = new JRadioButton("New session");
        continueRadio  = new JRadioButton("Continue last");
        resumeRadio    = new JRadioButton("Resume specific");

        group.add(closeOnlyRadio);
        group.add(newRadio);
        group.add(continueRadio);
        group.add(resumeRadio);

        // Radio panel (NORTH) — keeps all radios grouped together
        JPanel radioPanel = new JPanel();
        radioPanel.setLayout(new BoxLayout(radioPanel, BoxLayout.Y_AXIS));

        if (showCloseOnly) {
            radioPanel.add(row(closeOnlyRadio));
        }
        radioPanel.add(row(newRadio));
        radioPanel.add(row(continueRadio));

        // Resume specific row with Rename button
        JPanel resumeRow = new JPanel(new BorderLayout());
        resumeRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        resumeRow.add(resumeRadio, BorderLayout.WEST);
        renameButton = new JButton("Rename");
        renameButton.setEnabled(false);
        renameButton.setVisible(false);
        JPanel renamePad = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        renamePad.add(renameButton);
        resumeRow.add(renamePad, BorderLayout.EAST);
        radioPanel.add(resumeRow);

        add(radioPanel, BorderLayout.NORTH);

        // Session table (CENTER) — shown/hidden when Resume specific is selected
        tableModel  = new SessionTableModel();
        sessionTable = new JTable(tableModel);
        sessionTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        sessionTable.setAutoCreateRowSorter(true);

        TableRowSorter<SessionTableModel> sorter = new TableRowSorter<>(tableModel);
        sessionTable.setRowSorter(sorter);
        List<RowSorter.SortKey> sortKeys = new ArrayList<>();
        sortKeys.add(new RowSorter.SortKey(0, SortOrder.DESCENDING));
        sorter.setSortKeys(sortKeys);

        tableScroll = new JScrollPane(sessionTable);
        tableScroll.setPreferredSize(new java.awt.Dimension(400, 150));
        tableScroll.setVisible(false);
        add(tableScroll, BorderLayout.CENTER);

        tableScroll.getViewport().addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                int w = tableScroll.getViewport().getWidth();
                if (w <= 0) return;
                int c0 = w / 6;
                int c1 = w * 2 / 6;
                int c2 = w - c0 - c1;
                javax.swing.table.TableColumnModel cm = sessionTable.getColumnModel();
                cm.getColumn(0).setPreferredWidth(c0);
                cm.getColumn(1).setPreferredWidth(c1);
                cm.getColumn(2).setPreferredWidth(c2);
            }
        });

        // Default selection
        if (showCloseOnly) {
            closeOnlyRadio.setSelected(true);
        } else {
            continueRadio.setSelected(true);
        }

        // Radio listeners
        closeOnlyRadio.addActionListener(e -> onRadioChanged());
        newRadio.addActionListener(e -> onRadioChanged());
        continueRadio.addActionListener(e -> onRadioChanged());
        resumeRadio.addActionListener(e -> onRadioChanged());

        // Table selection listener → enable Rename
        sessionTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                renameButton.setEnabled(sessionTable.getSelectedRow() >= 0);
            }
        });

        // Rename button
        renameButton.addActionListener(e -> onRename());

        // Double-click on session row → fire onDoubleClick (if set and resumeRadio is selected)
        sessionTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && resumeRadio.isSelected() && onDoubleClick != null) {
                    onDoubleClick.run();
                }
            }
        });

        refreshTable();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns the currently selected session mode.
     *
     * @return selected {@link SessionMode}
     */
    public SessionMode getSelectedMode() {
        if (closeOnlyRadio.isSelected()) return SessionMode.CLOSE_ONLY;
        if (newRadio.isSelected())       return SessionMode.NEW;
        if (continueRadio.isSelected())  return SessionMode.CONTINUE_LAST;
        return SessionMode.RESUME_SPECIFIC;
    }

    /**
     * Returns the selected session ID, or {@code null} if mode is not
     * {@link SessionMode#RESUME_SPECIFIC} or no row is selected.
     *
     * @return session ID or {@code null}
     */
    public String getSelectedSessionId() {
        if (!resumeRadio.isSelected()) return null;
        int viewRow = sessionTable.getSelectedRow();
        if (viewRow < 0) return null;
        int modelRow = sessionTable.convertRowIndexToModel(viewRow);
        return tableModel.getSessionId(modelRow);
    }

    /**
     * Programmatically sets the selected mode.
     *
     * @param mode mode to select
     */
    public void setMode(SessionMode mode) {
        switch (mode) {
            case CLOSE_ONLY       -> closeOnlyRadio.setSelected(true);
            case NEW              -> newRadio.setSelected(true);
            case CONTINUE_LAST    -> continueRadio.setSelected(true);
            case RESUME_SPECIFIC  -> resumeRadio.setSelected(true);
        }
        onRadioChanged();
    }

    /**
     * Sets a callback to be called when the user double-clicks a row in the
     * session table while the "Resume specific" radio is selected.
     *
     * @param action callback, or {@code null} to remove
     */
    public void setOnDoubleClick(Runnable action) {
        this.onDoubleClick = action;
    }

    /**
     * Reloads the session list for a new directory / profile combination.
     *
     * @param newWorkingDir      new working directory
     * @param newClaudeConfigDir new config dir (null → ~/.claude)
     * @param newExcludeSession  session ID to exclude, or null
     */
    public void reload(Path newWorkingDir, Path newClaudeConfigDir, String newExcludeSession) {
        this.workingDir      = newWorkingDir;
        this.claudeConfigDir = newClaudeConfigDir;
        this.excludeSession  = newExcludeSession;
        refreshTable();
    }

    /**
     * Returns {@code true} if the current selection is valid: any mode except
     * {@link SessionMode#RESUME_SPECIFIC}, or RESUME_SPECIFIC with a row selected.
     *
     * @return {@code true} if a valid selection is made
     */
    public boolean isSelectionValid() {
        if (resumeRadio.isSelected()) {
            return sessionTable.getSelectedRow() >= 0;
        }
        return true;
    }

    boolean isRenameButtonVisible() {
        return renameButton.isVisible();
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private JPanel row(JRadioButton radio) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(radio);
        return p;
    }

    private void onRadioChanged() {
        boolean showTable = resumeRadio.isSelected();
        tableScroll.setVisible(showTable);
        renameButton.setVisible(showTable);
        if (showTable) {
            refreshTable();
        }
        revalidate();
        repaint();
        java.awt.Window w = javax.swing.SwingUtilities.getWindowAncestor(this);
        if (w instanceof javax.swing.JDialog) w.pack();
    }

    private void refreshTable() {
        if (workingDir == null) {
            tableModel.setSessions(List.of());
            return;
        }
        List<SavedSession> sessions = ClaudeSessionStore.listSessions(workingDir, claudeConfigDir);
        if (excludeSession != null) {
            sessions.removeIf(s -> excludeSession.equals(s.sessionId()));
        }
        tableModel.setSessions(sessions);
    }

    private void onRename() {
        int viewRow = sessionTable.getSelectedRow();
        if (viewRow < 0) return;
        int modelRow = sessionTable.convertRowIndexToModel(viewRow);
        SavedSession session = tableModel.getSession(modelRow);
        if (session == null) return;

        String current = session.displayName();
        String newName = JOptionPane.showInputDialog(
                this, "Rename session:", current);
        if (newName == null || newName.isBlank()) return;

        try {
            ClaudeSessionStore.renameSession(workingDir, claudeConfigDir,
                    session.sessionId(), newName.trim());
            refreshTable();
        } catch (Exception e) {
            LOG.warning("Could not rename session: " + e.getMessage());
            JOptionPane.showMessageDialog(this,
                    "Could not rename session: " + e.getMessage(),
                    "Rename Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // -------------------------------------------------------------------------
    // Table model
    // -------------------------------------------------------------------------

    private static final class SessionTableModel extends AbstractTableModel {

        private List<SavedSession> sessions = new ArrayList<>();

        private static final String[] COLUMNS = {"Date/Time", "Name", "First prompt"};

        void setSessions(List<SavedSession> list) {
            this.sessions = new ArrayList<>(list);
            fireTableDataChanged();
        }

        SavedSession getSession(int modelRow) {
            if (modelRow < 0 || modelRow >= sessions.size()) return null;
            return sessions.get(modelRow);
        }

        String getSessionId(int modelRow) {
            SavedSession s = getSession(modelRow);
            return s != null ? s.sessionId() : null;
        }

        @Override public int getRowCount()    { return sessions.size(); }
        @Override public int getColumnCount() { return COLUMNS.length; }
        @Override public String getColumnName(int col) { return COLUMNS[col]; }

        @Override
        public Object getValueAt(int row, int col) {
            SavedSession s = sessions.get(row);
            return switch (col) {
                case 0 -> s.lastAt() != null
                        ? DATE_FMT.format(Date.from(s.lastAt()))
                        : "";
                case 1 -> s.displayName();
                case 2 -> s.firstPrompt() != null ? s.firstPrompt() : "";
                default -> "";
            };
        }

        @Override
        public Class<?> getColumnClass(int col) {
            return String.class;
        }
    }
}
