package io.github.nbclaudecodegui.settings;

import java.awt.BorderLayout;
import java.awt.Canvas;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;

/**
 * Modal dialog for choosing the terminal font family and size.
 *
 * <p>Displays all installed font families with an optional "monospaced only"
 * filter. A live preview renders sample text including Claude Code TUI symbols
 * (⏵ ◐) so the user can immediately see whether the chosen font has adequate
 * Unicode coverage.
 *
 * <p>Usage:
 * <pre>{@code
 * TerminalFontChooserDialog dlg = new TerminalFontChooserDialog(parent, fontName, fontSize);
 * dlg.setVisible(true);
 * if (dlg.isConfirmed()) {
 *     String name = dlg.getFontName();   // "" = Auto
 *     int    size = dlg.getFontSize();
 * }
 * }</pre>
 */
final class TerminalFontChooserDialog extends JDialog {

    private static final String AUTO_LABEL = "Auto (detect best available)";
    private static final String PREVIEW_TEXT = "AaBbCc  0O1Il  → ⏵ ◐  ╔═╗  ░▒▓";

    private final DefaultListModel<String> listModel = new DefaultListModel<>();
    private final JList<String> fontList = new JList<>(listModel);
    private final JSpinner sizeSpinner;
    private final JLabel previewLabel = new JLabel(PREVIEW_TEXT);
    private final JCheckBox monoOnlyCheck = new JCheckBox("Show monospaced only", true);

    private List<String> allFonts = new ArrayList<>();
    private List<String> monoFonts = new ArrayList<>();
    private boolean monoFontsReady = false;

    private boolean confirmed = false;

    /**
     * Creates and initialises the dialog.
     *
     * @param owner      parent window for modality
     * @param fontName   current font name, or {@code ""} for Auto
     * @param fontSize   current font size in points
     */
    TerminalFontChooserDialog(Window owner, String fontName, int fontSize) {
        super(owner, "Terminal Font", ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        sizeSpinner = new JSpinner(new SpinnerNumberModel(
                Math.max(8, Math.min(72, fontSize)), 8, 72, 1));

        buildUI();
        loadFontsAsync(fontName);

        pack();
        setMinimumSize(getPreferredSize());
        setLocationRelativeTo(owner);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Returns {@code true} if the user clicked OK. */
    boolean isConfirmed() {
        return confirmed;
    }

    /**
     * Returns the selected font family name, or {@code ""} if "Auto" is selected.
     */
    String getFontName() {
        String sel = fontList.getSelectedValue();
        if (sel == null || AUTO_LABEL.equals(sel)) return "";
        return sel;
    }

    /** Returns the selected font size in points. */
    int getFontSize() {
        return (Integer) sizeSpinner.getValue();
    }

    // -------------------------------------------------------------------------
    // UI construction
    // -------------------------------------------------------------------------

    private void buildUI() {
        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // ---- filter checkbox ----
        monoOnlyCheck.addActionListener(e -> applyFilter());
        content.add(monoOnlyCheck, BorderLayout.NORTH);

        // ---- center: font list + size ----
        JPanel center = new JPanel(new GridBagLayout());

        fontList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        fontList.setVisibleRowCount(16);
        fontList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) updatePreview();
        });
        JScrollPane scroll = new JScrollPane(fontList);
        scroll.setPreferredSize(new java.awt.Dimension(280, 260));

        GridBagConstraints listGbc = new GridBagConstraints();
        listGbc.gridx = 0; listGbc.gridy = 0;
        listGbc.gridheight = 3;
        listGbc.fill = GridBagConstraints.BOTH;
        listGbc.weightx = 1.0; listGbc.weighty = 1.0;
        listGbc.insets = new Insets(0, 0, 0, 8);
        center.add(scroll, listGbc);

        center.add(new JLabel("Size:"), labelGbc(1, 0));
        GridBagConstraints spinnerGbc = new GridBagConstraints();
        spinnerGbc.gridx = 2; spinnerGbc.gridy = 0;
        spinnerGbc.anchor = GridBagConstraints.WEST;
        spinnerGbc.insets = new Insets(0, 0, 4, 0);
        sizeSpinner.addChangeListener(e -> updatePreview());
        center.add(sizeSpinner, spinnerGbc);

        // vertical spacer in size column
        GridBagConstraints spacer = new GridBagConstraints();
        spacer.gridx = 1; spacer.gridy = 1; spacer.gridwidth = 2;
        spacer.weighty = 1.0; spacer.fill = GridBagConstraints.VERTICAL;
        center.add(new JPanel(), spacer);

        content.add(center, BorderLayout.CENTER);

        // ---- preview ----
        previewLabel.setBorder(BorderFactory.createTitledBorder("Preview"));
        previewLabel.setPreferredSize(new java.awt.Dimension(400, 52));
        content.add(previewLabel, BorderLayout.SOUTH);

        // ---- buttons ----
        JButton okBtn = new JButton("OK");
        JButton cancelBtn = new JButton("Cancel");
        okBtn.addActionListener(e -> { confirmed = true; dispose(); });
        cancelBtn.addActionListener(e -> dispose());
        getRootPane().setDefaultButton(okBtn);

        JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 4, 0));
        buttons.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        buttons.add(okBtn);
        buttons.add(cancelBtn);

        JPanel root = new JPanel(new BorderLayout(0, 4));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        root.add(content, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        setContentPane(root);
    }

    private static GridBagConstraints labelGbc(int x, int y) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = x; c.gridy = y;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(0, 0, 4, 4);
        return c;
    }

    // -------------------------------------------------------------------------
    // Font loading
    // -------------------------------------------------------------------------

    private void loadFontsAsync(String initialSelection) {
        listModel.addElement("Loading…");
        fontList.setEnabled(false);
        monoOnlyCheck.setEnabled(false);

        SwingWorker<Void, Void> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                String[] families = java.awt.GraphicsEnvironment
                        .getLocalGraphicsEnvironment()
                        .getAvailableFontFamilyNames();
                allFonts = new ArrayList<>(families.length + 1);
                monoFonts = new ArrayList<>();
                allFonts.add(AUTO_LABEL);

                Canvas canvas = new Canvas();
                for (String family : families) {
                    allFonts.add(family);
                    Font f = new Font(family, Font.PLAIN, 14);
                    FontMetrics fm = canvas.getFontMetrics(f);
                    if (fm.charWidth('M') == fm.charWidth('i')) {
                        monoFonts.add(family);
                    }
                }
                monoFontsReady = true;
                return null;
            }

            @Override
            protected void done() {
                fontList.setEnabled(true);
                monoOnlyCheck.setEnabled(true);
                applyFilter();
                selectFont(initialSelection);
                updatePreview();
            }
        };
        worker.execute();
    }

    private void applyFilter() {
        boolean monoOnly = monoOnlyCheck.isSelected();
        String current = fontList.getSelectedValue();

        listModel.clear();
        listModel.addElement(AUTO_LABEL);

        List<String> source = (monoOnly && monoFontsReady) ? monoFonts : allFonts.subList(1, allFonts.size());
        for (String f : source) {
            listModel.addElement(f);
        }

        // restore selection if still in list
        if (current != null) {
            int idx = findIndex(current);
            if (idx >= 0) fontList.setSelectedIndex(idx);
        }
    }

    private void selectFont(String fontName) {
        if (fontName == null || fontName.isEmpty()) {
            fontList.setSelectedIndex(0); // Auto
            return;
        }
        int idx = findIndex(fontName);
        if (idx >= 0) {
            fontList.setSelectedIndex(idx);
            fontList.ensureIndexIsVisible(idx);
        } else {
            fontList.setSelectedIndex(0);
        }
    }

    private int findIndex(String name) {
        for (int i = 0; i < listModel.size(); i++) {
            if (listModel.getElementAt(i).equals(name)) return i;
        }
        return -1;
    }

    private void updatePreview() {
        String sel = fontList.getSelectedValue();
        int size = (Integer) sizeSpinner.getValue();
        if (sel == null || AUTO_LABEL.equals(sel)) {
            previewLabel.setFont(previewLabel.getFont().deriveFont((float) size));
        } else {
            previewLabel.setFont(new Font(sel, Font.PLAIN, size));
        }
        previewLabel.revalidate();
        previewLabel.repaint();
    }
}
