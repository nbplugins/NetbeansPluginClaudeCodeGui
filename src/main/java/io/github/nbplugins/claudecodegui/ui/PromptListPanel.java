package io.github.nbplugins.claudecodegui.ui;

import io.github.nbplugins.claudecodegui.model.PromptEntry;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;

/**
 * Reusable panel that provides:
 * <ul>
 *   <li>A search row (text field + Search button)</li>
 *   <li>A JTable with a checkbox first column and additional columns defined by subclasses</li>
 *   <li>A button row at the bottom (populated by subclasses via {@link #addButton})</li>
 * </ul>
 *
 * <p>Subclasses provide the table model and column names.
 * Multiple rows can be selected via checkboxes.
 */
public abstract class PromptListPanel extends JPanel {

    /** The search text field used to filter the list. */
    protected final JTextField searchField  = new JTextField(20);
    /** The button that triggers the search filter. */
    protected final JButton    searchButton = new JButton("Search");
    /** The table displaying the prompt entries. */
    protected final JTable     table;
    /** The panel containing action buttons below the table. */
    protected final JPanel     buttonPanel  = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));

    /** Creates a new panel and initializes the UI components. */
    protected PromptListPanel() {
        setLayout(new BorderLayout(0, 4));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // --- search row ---
        JPanel searchRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        searchRow.add(searchField);
        searchRow.add(searchButton);
        add(searchRow, BorderLayout.NORTH);

        // --- table: initialised with an empty model; subclass calls init() after
        //     its own fields are set to avoid virtual-method-in-constructor NPE ---
        table = new JTable();
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setRowHeight(22);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(560, 280));
        add(scroll, BorderLayout.CENTER);

        // --- button row ---
        add(buttonPanel, BorderLayout.SOUTH);

        searchButton.addActionListener(e -> refreshTable());
        searchField.addActionListener(e -> refreshTable());
    }

    /**
     * Must be called by each concrete subclass at the end of its own constructor,
     * after all its fields have been assigned.  Populates the table for the first time.
     */
    protected final void init() {
        table.setModel(buildTableModel(""));
        configureColumns(table);
    }

    // -------------------------------------------------------------------------
    // Template methods for subclasses
    // -------------------------------------------------------------------------

    /**
     * Builds the table model for the given filter string.
     *
     * @param filter the current search filter (lower-cased, trimmed)
     * @return the table model to display
     */
    protected abstract AbstractTableModel buildTableModel(String filter);

    /**
     * Called after the table model is set — configure column widths, renderers, etc.
     *
     * @param t the table whose columns should be configured
     */
    protected void configureColumns(JTable t) {}

    // -------------------------------------------------------------------------
    // Public helpers
    // -------------------------------------------------------------------------

    /**
     * Adds a button to the button panel.
     *
     * @param btn the button to add
     */
    protected void addButton(JButton btn) {
        buttonPanel.add(btn);
    }

    /** Rebuilds the table with the current search filter. */
    protected void refreshTable() {
        String filter = searchField.getText().trim().toLowerCase();
        table.setModel(buildTableModel(filter));
        configureColumns(table);
    }

    /**
     * Returns the row indices that are checked (checkbox column = 0).
     *
     * @return list of checked row indices
     */
    protected List<Integer> checkedRows() {
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < table.getRowCount(); i++) {
            Object val = table.getValueAt(i, 0);
            if (Boolean.TRUE.equals(val)) result.add(i);
        }
        return result;
    }
}
