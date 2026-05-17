package io.github.nbclaudecodegui.ui.common.markdown;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javax.swing.AbstractAction;
import javax.swing.AbstractButton;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import javax.swing.text.JTextComponent;
import org.openide.awt.CloseButtonFactory;
import org.openide.util.ImageUtilities;

/**
 * Find bar for Markdown Preview, mirroring the NetBeans SearchBar UI layout and icons.
 * Attach to a {@link JTextComponent} (typically a JEditorPane with HTMLEditorKit).
 * Icons are loaded from the {@code org-netbeans-modules-editor-search} module JAR at runtime.
 */
public class MarkdownFindBar extends JPanel {

    private static final String ICON_BASE = "org/netbeans/modules/editor/search/resources/";
    private static final Insets BTN_INSETS = new Insets(2, 1, 0, 1);

    private static final Color HIGHLIGHT_ALL_COLOR = new Color(255, 255, 160);
    private static final Color HIGHLIGHT_CURRENT_COLOR = new Color(255, 165, 0);

    private final JTextComponent targetPane;

    final JLabel findLabel;
    final JComboBox<String> incSearchComboBox;
    final JButton findPreviousButton;
    final JButton findNextButton;
    final JButton selectAllButton;
    final JToggleButton matchCase;
    final JToggleButton wholeWords;
    final JToggleButton regexp;
    final JToggleButton highlight;
    final JToggleButton wrapAround;
    final JLabel matches;
    final JButton closeButton;

    private final Highlighter.HighlightPainter allPainter =
            new DefaultHighlighter.DefaultHighlightPainter(HIGHLIGHT_ALL_COLOR);
    private final Highlighter.HighlightPainter currentPainter =
            new DefaultHighlighter.DefaultHighlightPainter(HIGHLIGHT_CURRENT_COLOR);

    /** Positions of all current matches as [start, end] pairs. */
    private final List<int[]> matchPositions = new ArrayList<>();
    private int currentMatchIndex = -1;

    /** Highlight tags for all-match highlights (to remove them later). */
    private final List<Object> allHighlightTags = new ArrayList<>();
    private Object currentHighlightTag = null;

    public MarkdownFindBar(JTextComponent targetPane) {
        this.targetPane = targetPane;

        setLayout(new BoxLayout(this, BoxLayout.LINE_AXIS));

        findLabel = new JLabel("Find:");
        incSearchComboBox = new JComboBox<>();
        incSearchComboBox.setEditable(true);
        incSearchComboBox.setMaximumSize(new Dimension(300, incSearchComboBox.getPreferredSize().height));

        findPreviousButton = createButton("find_previous.png");
        findPreviousButton.setToolTipText("Find Previous (Shift+F3)");
        findNextButton = createButton("find_next.png");
        findNextButton.setToolTipText("Find Next (F3)");
        selectAllButton = createButton("select_all.png");
        selectAllButton.setToolTipText("Select All (Alt+Enter)");

        matchCase  = createToggleButton("matchCase.png");
        matchCase.setToolTipText("Match Case");
        wholeWords = createToggleButton("wholeWord.png");
        wholeWords.setToolTipText("Whole Words");
        regexp     = createToggleButton("regexp.png");
        regexp.setToolTipText("Regular Expression");
        highlight  = createToggleButton("highlight.png");
        highlight.setToolTipText("Highlight All");
        highlight.setSelected(true);
        highlight.setContentAreaFilled(true);
        highlight.setBorderPainted(true);
        wrapAround = createToggleButton("wrapAround.png");
        wrapAround.setToolTipText("Wrap Around");
        wrapAround.setSelected(true);
        wrapAround.setContentAreaFilled(true);
        wrapAround.setBorderPainted(true);

        matches = new JLabel();
        closeButton = CloseButtonFactory.createBigCloseButton();
        closeButton.setToolTipText("Close (Escape)");

        // Layout: mirrors NetBeans SearchBar order
        add(Box.createHorizontalStrut(8));
        add(findLabel);
        add(Box.createHorizontalStrut(4));
        add(incSearchComboBox);
        add(Box.createHorizontalStrut(4));
        add(createVerticalSeparator());
        add(findPreviousButton);
        add(findNextButton);
        add(selectAllButton);
        add(createVerticalSeparator());
        add(matchCase);
        add(wholeWords);
        add(regexp);
        add(highlight);
        add(wrapAround);
        add(Box.createHorizontalGlue());
        add(matches);
        add(Box.createHorizontalStrut(8));
        add(closeButton);

        wireActions();
        setVisible(false);
    }

    private static JButton createButton(String iconFile) {
        Icon icon = ImageUtilities.loadImageIcon(ICON_BASE + iconFile, false);
        JButton btn = icon != null ? new JButton(icon) : new JButton(iconFile);
        styleButton(btn);
        return btn;
    }

    private static JToggleButton createToggleButton(String iconFile) {
        Icon icon = ImageUtilities.loadImageIcon(ICON_BASE + iconFile, false);
        JToggleButton btn = icon != null ? new JToggleButton(icon) : new JToggleButton(iconFile);
        btn.addMouseListener(HOVER_LISTENER);
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setMargin(BTN_INSETS);
        btn.setFocusable(false);
        // When selected, keep border/fill shown
        btn.addChangeListener(e -> {
            if (!btn.getModel().isRollover()) {
                btn.setContentAreaFilled(btn.isSelected());
                btn.setBorderPainted(btn.isSelected());
            }
        });
        return btn;
    }

    private static void styleButton(AbstractButton btn) {
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setMargin(BTN_INSETS);
        btn.setFocusable(false);
        btn.addMouseListener(HOVER_LISTENER);
    }

    private static final MouseAdapter HOVER_LISTENER = new MouseAdapter() {
        @Override public void mouseEntered(MouseEvent e) {
            AbstractButton btn = (AbstractButton) e.getSource();
            if (btn.isEnabled()) { btn.setContentAreaFilled(true); btn.setBorderPainted(true); }
        }
        @Override public void mouseExited(MouseEvent e) {
            AbstractButton btn = (AbstractButton) e.getSource();
            if (!(btn instanceof JToggleButton) || !btn.isSelected()) {
                btn.setContentAreaFilled(false);
                btn.setBorderPainted(false);
            }
        }
    };

    private static JSeparator createVerticalSeparator() {
        JSeparator sep = new JSeparator(SwingConstants.VERTICAL);
        sep.setMaximumSize(new Dimension(2, 20));
        return sep;
    }

    private void wireActions() {
        // Text field document listener → incremental search
        getTextField().getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e)  { search(); }
            @Override public void removeUpdate(DocumentEvent e)  { search(); }
            @Override public void changedUpdate(DocumentEvent e) { search(); }
        });

        // Toggle buttons → re-search
        matchCase.addActionListener(e  -> search());
        wholeWords.addActionListener(e -> search());
        regexp.addActionListener(e     -> search());
        highlight.addActionListener(e  -> repaintHighlights());

        // Buttons
        findNextButton.addActionListener(e -> findNext());
        findPreviousButton.addActionListener(e -> findPrev());
        selectAllButton.addActionListener(e -> selectAll());
        closeButton.addActionListener(e -> looseFocus());

        // Text field keyboard shortcuts
        addTextFieldKeystroke(KeyEvent.VK_ENTER, 0, () -> findNext());
        addTextFieldKeystroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK, () -> findPrev());
        addTextFieldKeystroke(KeyEvent.VK_ENTER, KeyEvent.ALT_DOWN_MASK, () -> selectAll());
        addTextFieldKeystroke(KeyEvent.VK_ESCAPE, 0, () -> looseFocus());
    }

    private void addTextFieldKeystroke(int key, int modifiers, Runnable action) {
        KeyStroke ks = KeyStroke.getKeyStroke(key, modifiers);
        String name = "mdfindbar-" + key + "-" + modifiers;
        getTextField().getInputMap(JComponent.WHEN_FOCUSED).put(ks, name);
        getTextField().getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { action.run(); }
        });
    }

    /** Returns the editable text field inside the combo box. */
    public javax.swing.text.JTextComponent getTextField() {
        return (javax.swing.text.JTextComponent) incSearchComboBox.getEditor().getEditorComponent();
    }

    /** Shows the bar and focuses the search text field. */
    public void gainFocus() {
        setVisible(true);
        SwingUtilities.invokeLater(() -> {
            getTextField().requestFocusInWindow();
            getTextField().selectAll();
        });
    }

    /** Hides the bar, clears all highlights, and returns focus to the target pane. */
    public void looseFocus() {
        setVisible(false);
        clearHighlights();
        matchPositions.clear();
        currentMatchIndex = -1;
        matches.setText("");
        targetPane.requestFocusInWindow();
    }

    // -------------------------------------------------------------------------
    // Search
    // -------------------------------------------------------------------------

    void search() {
        String query = getTextField().getText();
        clearHighlights();
        matchPositions.clear();
        currentMatchIndex = -1;

        if (query == null || query.isEmpty()) {
            matches.setText("");
            return;
        }

        String docText;
        try {
            docText = targetPane.getDocument().getText(0, targetPane.getDocument().getLength());
        } catch (BadLocationException e) {
            matches.setText("");
            return;
        }

        collectMatches(query, docText);

        if (matchPositions.isEmpty()) {
            matches.setText("No matches");
            return;
        }

        currentMatchIndex = 0;
        repaintHighlights();
        scrollToCurrentMatch();
        updateMatchLabel();
    }

    private void collectMatches(String query, String docText) {
        if (regexp.isSelected()) {
            try {
                int flags = matchCase.isSelected() ? 0 : Pattern.CASE_INSENSITIVE;
                Pattern p = Pattern.compile(query, flags);
                Matcher m = p.matcher(docText);
                while (m.find()) {
                    if (wholeWords.isSelected()) {
                        boolean startOk = m.start() == 0 || !Character.isLetterOrDigit(docText.charAt(m.start() - 1));
                        boolean endOk   = m.end() == docText.length() || !Character.isLetterOrDigit(docText.charAt(m.end()));
                        if (!startOk || !endOk) continue;
                    }
                    matchPositions.add(new int[]{m.start(), m.end()});
                }
            } catch (PatternSyntaxException ignored) {
                // Bad regex — show no matches
            }
        } else {
            String searchText = matchCase.isSelected() ? docText : docText.toLowerCase();
            String searchQuery = matchCase.isSelected() ? query : query.toLowerCase();
            int idx = 0;
            while ((idx = searchText.indexOf(searchQuery, idx)) >= 0) {
                int end = idx + searchQuery.length();
                if (wholeWords.isSelected()) {
                    boolean startOk = idx == 0 || !Character.isLetterOrDigit(docText.charAt(idx - 1));
                    boolean endOk   = end == docText.length() || !Character.isLetterOrDigit(docText.charAt(end));
                    if (!startOk || !endOk) { idx++; continue; }
                }
                matchPositions.add(new int[]{idx, end});
                idx++;
            }
        }
    }

    private void repaintHighlights() {
        clearHighlights();
        if (matchPositions.isEmpty()) return;
        Highlighter h = targetPane.getHighlighter();
        if (highlight.isSelected()) {
            for (int i = 0; i < matchPositions.size(); i++) {
                if (i == currentMatchIndex) continue;
                try {
                    Object tag = h.addHighlight(matchPositions.get(i)[0], matchPositions.get(i)[1], allPainter);
                    allHighlightTags.add(tag);
                } catch (BadLocationException ignored) {}
            }
        }
        if (currentMatchIndex >= 0 && currentMatchIndex < matchPositions.size()) {
            try {
                currentHighlightTag = h.addHighlight(
                        matchPositions.get(currentMatchIndex)[0],
                        matchPositions.get(currentMatchIndex)[1],
                        currentPainter);
            } catch (BadLocationException ignored) {}
        }
    }

    private void clearHighlights() {
        Highlighter h = targetPane.getHighlighter();
        for (Object tag : allHighlightTags) {
            h.removeHighlight(tag);
        }
        allHighlightTags.clear();
        if (currentHighlightTag != null) {
            h.removeHighlight(currentHighlightTag);
            currentHighlightTag = null;
        }
    }

    private void scrollToCurrentMatch() {
        if (currentMatchIndex < 0 || currentMatchIndex >= matchPositions.size()) return;
        int[] pos = matchPositions.get(currentMatchIndex);
        // Select the match so the pane scrolls to it
        targetPane.setCaretPosition(pos[1]);
        targetPane.moveCaretPosition(pos[0]);
    }

    private void updateMatchLabel() {
        if (matchPositions.isEmpty()) {
            matches.setText("No matches");
        } else {
            matches.setText((currentMatchIndex + 1) + " of " + matchPositions.size());
        }
    }

    // -------------------------------------------------------------------------
    // Navigation
    // -------------------------------------------------------------------------

    public void findNext() {
        if (matchPositions.isEmpty()) {
            search();
            return;
        }
        if (currentMatchIndex < matchPositions.size() - 1) {
            currentMatchIndex++;
        } else if (wrapAround.isSelected()) {
            currentMatchIndex = 0;
        } else {
            return;
        }
        repaintHighlights();
        scrollToCurrentMatch();
        updateMatchLabel();
    }

    public void findPrev() {
        if (matchPositions.isEmpty()) {
            search();
            return;
        }
        if (currentMatchIndex > 0) {
            currentMatchIndex--;
        } else if (wrapAround.isSelected()) {
            currentMatchIndex = matchPositions.size() - 1;
        } else {
            return;
        }
        repaintHighlights();
        scrollToCurrentMatch();
        updateMatchLabel();
    }

    private void selectAll() {
        if (matchPositions.isEmpty()) {
            search();
            return;
        }
        // Highlight all and update count without changing current index
        highlight.setSelected(true);
        repaintHighlights();
        updateMatchLabel();
    }

    // -------------------------------------------------------------------------
    // Package-private accessors for tests
    // -------------------------------------------------------------------------

    List<int[]> getMatchPositions() { return matchPositions; }
    int getCurrentMatchIndex() { return currentMatchIndex; }
}
