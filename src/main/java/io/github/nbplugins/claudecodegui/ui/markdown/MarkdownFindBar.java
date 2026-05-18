package io.github.nbplugins.claudecodegui.ui.markdown;

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
import javax.swing.DefaultComboBoxModel;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.MutableComboBoxModel;
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
import org.openide.util.NbPreferences;

/**
 * Find bar for Markdown Preview, mirroring the NetBeans SearchBar UI layout and icons.
 * Icons are loaded from the {@code org-netbeans-modules-editor-search} module JAR at runtime.
 */
public class MarkdownFindBar extends JPanel {

    private static final String ICON_BASE = "org/netbeans/modules/editor/search/resources/";
    private static final Insets BTN_INSETS = new Insets(2, 1, 0, 1);
    private static final int COMBO_MIN_WIDTH = 200;
    private static final int COMBO_MAX_WIDTH = 350;

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

    private final List<int[]> matchPositions = new ArrayList<>();
    private int currentMatchIndex = -1;

    private final List<Object> allHighlightTags = new ArrayList<>();
    private Object currentHighlightTag = null;

    public MarkdownFindBar(JTextComponent targetPane) {
        this.targetPane = targetPane;

        setLayout(new BoxLayout(this, BoxLayout.LINE_AXIS));

        findLabel = new JLabel("Find:");

        // Combo sized like SearchComboBox: min 200, max 350, dynamic
        incSearchComboBox = new JComboBox<String>(new DefaultComboBoxModel<>()) {
            @Override public Dimension getPreferredSize() {
                int editW = getEditor().getEditorComponent().getPreferredSize().width + 10;
                int w = editW > COMBO_MIN_WIDTH
                        ? Math.min(editW, COMBO_MAX_WIDTH)
                        : COMBO_MIN_WIDTH;
                return new Dimension(w, super.getPreferredSize().height);
            }
            @Override public Dimension getMinimumSize() { return getPreferredSize(); }
            @Override public Dimension getMaximumSize() { return getPreferredSize(); }
        };
        incSearchComboBox.setEditable(true);

        // Buttons with icon + text label, same as NetBeans SearchBar
        findPreviousButton = createButton("find_previous.png", "Previous");
        findPreviousButton.setToolTipText("Find Previous (Shift+F3)");
        findNextButton = createButton("find_next.png", "Next");
        findNextButton.setToolTipText("Find Next (F3)");
        selectAllButton = createButton("select_all.png", "Select");
        selectAllButton.setToolTipText("Select All (Alt+Enter)");

        matchCase  = createToggleButton("matchCase.png");
        matchCase.setToolTipText("Match Case");
        wholeWords = createToggleButton("wholeWord.png");
        wholeWords.setToolTipText("Whole Words");
        regexp     = createToggleButton("regexp.png");
        regexp.setToolTipText("Regular Expression");
        highlight  = createToggleButton("highlight.png");
        highlight.setToolTipText("Highlight All");
        setToggleSelected(highlight, true);
        wrapAround = createToggleButton("wrapAround.png");
        wrapAround.setToolTipText("Wrap Around");
        setToggleSelected(wrapAround, true);

        matches = new JLabel();
        closeButton = CloseButtonFactory.createBigCloseButton();
        closeButton.setToolTipText("Close (Escape)");

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
        loadHistory();
        setVisible(false);
    }

    // -------------------------------------------------------------------------
    // Button factories
    // -------------------------------------------------------------------------

    private static JButton createButton(String iconFile, String text) {
        Icon icon = ImageUtilities.loadImageIcon(ICON_BASE + iconFile, false);
        JButton btn = icon != null ? new JButton(text, icon) : new JButton(text);
        styleButton(btn);
        return btn;
    }

    private static JToggleButton createToggleButton(String iconFile) {
        Icon icon = ImageUtilities.loadImageIcon(ICON_BASE + iconFile, false);
        JToggleButton btn = icon != null ? new JToggleButton(icon) : new JToggleButton(iconFile);
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setMargin(BTN_INSETS);
        btn.setFocusable(false);
        btn.addMouseListener(HOVER_LISTENER);
        btn.addChangeListener(e -> {
            if (!btn.getModel().isRollover()) {
                btn.setContentAreaFilled(btn.isSelected());
                btn.setBorderPainted(btn.isSelected());
            }
        });
        return btn;
    }

    private static void setToggleSelected(JToggleButton btn, boolean selected) {
        btn.setSelected(selected);
        btn.setContentAreaFilled(selected);
        btn.setBorderPainted(selected);
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

    // -------------------------------------------------------------------------
    // Wiring
    // -------------------------------------------------------------------------

    private void wireActions() {
        getTextField().getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e)  { search(); }
            @Override public void removeUpdate(DocumentEvent e)  { search(); }
            @Override public void changedUpdate(DocumentEvent e) { search(); }
        });

        matchCase.addActionListener(e  -> search());
        wholeWords.addActionListener(e -> search());
        regexp.addActionListener(e     -> search());
        highlight.addActionListener(e  -> repaintHighlights());

        findNextButton.addActionListener(e     -> findNext());
        findPreviousButton.addActionListener(e -> findPrev());
        selectAllButton.addActionListener(e    -> selectAll());
        closeButton.addActionListener(e        -> looseFocus());

        // Text-field keyboard shortcuts (WHEN_FOCUSED covers find bar text field)
        addTextFieldKeystroke(KeyEvent.VK_ENTER,  0,                          () -> findNext());
        addTextFieldKeystroke(KeyEvent.VK_ENTER,  KeyEvent.SHIFT_DOWN_MASK,   () -> findPrev());
        addTextFieldKeystroke(KeyEvent.VK_ENTER,  KeyEvent.ALT_DOWN_MASK,     () -> selectAll());
        addTextFieldKeystroke(KeyEvent.VK_ESCAPE, 0,                          () -> looseFocus());
        // F3/Shift+F3 also work while text field has focus
        addTextFieldKeystroke(KeyEvent.VK_F3, 0,                          () -> findNext());
        addTextFieldKeystroke(KeyEvent.VK_F3, KeyEvent.SHIFT_DOWN_MASK,   () -> findPrev());
    }

    private void addTextFieldKeystroke(int key, int modifiers, Runnable action) {
        KeyStroke ks = KeyStroke.getKeyStroke(key, modifiers);
        String name = "mdfindbar-" + key + "-" + modifiers;
        getTextField().getInputMap(JComponent.WHEN_FOCUSED).put(ks, name);
        getTextField().getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { action.run(); }
        });
    }

    public javax.swing.text.JTextComponent getTextField() {
        return (javax.swing.text.JTextComponent) incSearchComboBox.getEditor().getEditorComponent();
    }

    // -------------------------------------------------------------------------
    // Visibility
    // -------------------------------------------------------------------------

    /** Shows the bar and focuses the search text field. */
    public void gainFocus() {
        setVisible(true);
        SwingUtilities.invokeLater(() -> {
            getTextField().requestFocusInWindow();
            getTextField().selectAll();
        });
    }

    /**
     * Hides the bar and returns focus to the target pane.
     * Highlights are cleared but the query and match positions are preserved so
     * that F3 can resume navigation from the correct position after re-opening.
     */
    public void looseFocus() {
        setVisible(false);
        clearHighlights();
        // Keep matchPositions and currentMatchIndex so F3 can resume
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

        String docText = getDocText();
        if (docText == null) { matches.setText(""); return; }

        collectMatches(query, docText);

        if (matchPositions.isEmpty()) {
            matches.setText("No matches");
            return;
        }

        // Start at match nearest to caret
        currentMatchIndex = indexNearestToCaret();
        repaintHighlights();
        scrollToCurrentMatch();
        updateMatchLabel();
    }

    /**
     * Searches using the current query (re-collects matches) and navigates to
     * the first match at or after the caret position.  Used when F3 is pressed
     * while the bar is hidden — so the user doesn't need to press F3 twice.
     */
    public void searchAndFindNext() {
        String query = getTextField().getText();
        if (query == null || query.isEmpty()) {
            gainFocus();
            return;
        }
        String docText = getDocText();
        if (docText == null) return;

        clearHighlights();
        matchPositions.clear();
        collectMatches(query, docText);

        if (matchPositions.isEmpty()) {
            gainFocus();
            matches.setText("No matches");
            return;
        }

        addHistory(query);
        // Use selectionEnd so repeated F3 advances past the already-highlighted match
        int after = Math.max(targetPane.getSelectionEnd(), targetPane.getCaretPosition());
        currentMatchIndex = 0;
        boolean foundNext = false;
        for (int i = 0; i < matchPositions.size(); i++) {
            if (matchPositions.get(i)[0] >= after) { currentMatchIndex = i; foundNext = true; break; }
        }
        if (!foundNext) currentMatchIndex = wrapAround.isSelected() ? 0 : matchPositions.size() - 1;
        repaintHighlights();
        scrollToCurrentMatch();
        updateMatchLabel();
    }

    public void searchAndFindPrev() {
        String query = getTextField().getText();
        if (query == null || query.isEmpty()) {
            gainFocus();
            return;
        }
        String docText = getDocText();
        if (docText == null) return;

        clearHighlights();
        matchPositions.clear();
        collectMatches(query, docText);

        if (matchPositions.isEmpty()) {
            gainFocus();
            matches.setText("No matches");
            return;
        }

        addHistory(query);
        // Use selectionStart so repeated Shift+F3 goes back past the current match
        int before = Math.min(targetPane.getSelectionStart(), targetPane.getCaretPosition());
        currentMatchIndex = matchPositions.size() - 1;
        boolean foundPrev = false;
        for (int i = matchPositions.size() - 1; i >= 0; i--) {
            if (matchPositions.get(i)[1] <= before) { currentMatchIndex = i; foundPrev = true; break; }
        }
        if (!foundPrev) currentMatchIndex = wrapAround.isSelected() ? matchPositions.size() - 1 : 0;
        repaintHighlights();
        scrollToCurrentMatch();
        updateMatchLabel();
    }

    private String getDocText() {
        try {
            return targetPane.getDocument().getText(0, targetPane.getDocument().getLength());
        } catch (BadLocationException e) {
            return null;
        }
    }

    private int indexNearestToCaret() {
        int caret = targetPane.getCaretPosition();
        for (int i = 0; i < matchPositions.size(); i++) {
            if (matchPositions.get(i)[0] >= caret) return i;
        }
        return 0; // wrap
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
            } catch (PatternSyntaxException ignored) {}
        } else {
            String searchText  = matchCase.isSelected() ? docText : docText.toLowerCase();
            String searchQuery = matchCase.isSelected() ? query   : query.toLowerCase();
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

    private static final String PREF_HISTORY_PREFIX = "markdownFindHistory.";
    private static final int HISTORY_MAX = 20;

    /** Adds a non-empty query to the combo history (deduplicates, most recent first) and persists it. */
    private void addHistory(String query) {
        if (query == null || query.isEmpty()) return;
        MutableComboBoxModel<String> model = (MutableComboBoxModel<String>) incSearchComboBox.getModel();
        for (int i = model.getSize() - 1; i >= 0; i--) {
            if (query.equals(model.getElementAt(i))) model.removeElementAt(i);
        }
        model.insertElementAt(query, 0);
        while (model.getSize() > HISTORY_MAX) model.removeElementAt(model.getSize() - 1);
        incSearchComboBox.setSelectedIndex(0);
        saveHistory();
    }

    private void saveHistory() {
        MutableComboBoxModel<String> model = (MutableComboBoxModel<String>) incSearchComboBox.getModel();
        var prefs = NbPreferences.forModule(MarkdownFindBar.class);
        prefs.putInt(PREF_HISTORY_PREFIX + "size", model.getSize());
        for (int i = 0; i < model.getSize(); i++) {
            prefs.put(PREF_HISTORY_PREFIX + i, model.getElementAt(i));
        }
    }

    private void loadHistory() {
        var prefs = NbPreferences.forModule(MarkdownFindBar.class);
        int size = prefs.getInt(PREF_HISTORY_PREFIX + "size", 0);
        MutableComboBoxModel<String> model = (MutableComboBoxModel<String>) incSearchComboBox.getModel();
        for (int i = 0; i < size; i++) {
            String val = prefs.get(PREF_HISTORY_PREFIX + i, null);
            if (val != null && !val.isEmpty()) model.addElement(val);
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
                    allHighlightTags.add(h.addHighlight(matchPositions.get(i)[0], matchPositions.get(i)[1], allPainter));
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
        for (Object tag : allHighlightTags) h.removeHighlight(tag);
        allHighlightTags.clear();
        if (currentHighlightTag != null) { h.removeHighlight(currentHighlightTag); currentHighlightTag = null; }
    }

    private void scrollToCurrentMatch() {
        if (currentMatchIndex < 0 || currentMatchIndex >= matchPositions.size()) return;
        int[] pos = matchPositions.get(currentMatchIndex);
        targetPane.setCaretPosition(pos[1]);
        targetPane.moveCaretPosition(pos[0]);
    }

    private void updateMatchLabel() {
        matches.setText(matchPositions.isEmpty() ? "No matches"
                : (currentMatchIndex + 1) + " of " + matchPositions.size());
    }

    // -------------------------------------------------------------------------
    // Navigation
    // -------------------------------------------------------------------------

    public void findNext() {
        if (matchPositions.isEmpty()) { search(); return; }
        String query = getTextField().getText();
        if (query != null && !query.isEmpty()) addHistory(query);
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
        if (matchPositions.isEmpty()) { search(); return; }
        String query = getTextField().getText();
        if (query != null && !query.isEmpty()) addHistory(query);
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
        if (matchPositions.isEmpty()) { search(); return; }
        setToggleSelected(highlight, true);
        repaintHighlights();
        updateMatchLabel();
    }

    // -------------------------------------------------------------------------
    // Test accessors
    // -------------------------------------------------------------------------

    List<int[]> getMatchPositions() { return matchPositions; }
    int getCurrentMatchIndex()      { return currentMatchIndex; }
}
