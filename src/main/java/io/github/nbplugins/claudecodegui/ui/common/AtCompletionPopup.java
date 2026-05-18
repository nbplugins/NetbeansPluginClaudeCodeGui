package io.github.nbplugins.claudecodegui.ui.common;

import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import javax.swing.DefaultListModel;
import javax.swing.JList;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.text.BadLocationException;
import javax.swing.text.JTextComponent;

/**
 * {@code @}-completion popup for a prompt text component.
 *
 * <p>When the user types {@code @} in the text component, this popup appears and
 * offers file paths relative to the working directory as completions.
 * Additional characters after {@code @} filter the list. Up/Down navigates;
 * Enter or Tab inserts; Escape dismisses.
 *
 * <p>Install with {@link #install(JTextComponent, Supplier)}.
 */
public final class AtCompletionPopup {

    private final JTextComponent       tc;
    private final Supplier<String>     workingDirSupplier;

    private final JPopupMenu              popup;
    private final DefaultListModel<String> listModel;
    private final JList<String>           list;

    private AtCompletionPopup(JTextComponent tc, Supplier<String> workingDirSupplier) {
        this.tc                 = tc;
        this.workingDirSupplier = workingDirSupplier;

        listModel = new DefaultListModel<>();
        list      = new JList<>(listModel);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFont(list.getFont().deriveFont(Font.PLAIN, list.getFont().getSize() - 1f));

        // Double-click inserts
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) insertSelected();
            }
        });

        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(350, 180));

        popup = new JPopupMenu();
        popup.add(scroll);
        popup.setFocusable(false);   // keep focus in text component
    }

    /**
     * Installs an {@code AtCompletionPopup} on the given text component.
     *
     * @param tc                 the text component to monitor
     * @param workingDirSupplier returns the working directory path
     * @return the installed popup
     */
    public static AtCompletionPopup install(JTextComponent tc, Supplier<String> workingDirSupplier) {
        AtCompletionPopup popup = new AtCompletionPopup(tc, workingDirSupplier);
        tc.addKeyListener(new KeyAdapter() {
            @Override
            public void keyReleased(KeyEvent e) {
                popup.onKeyReleased(e);
            }
            @Override
            public void keyPressed(KeyEvent e) {
                popup.onKeyPressed(e);
            }
        });
        return popup;
    }

    // -------------------------------------------------------------------------
    // Key handling
    // -------------------------------------------------------------------------

    private void onKeyReleased(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.VK_ESCAPE
                || code == KeyEvent.VK_ENTER
                || code == KeyEvent.VK_TAB
                || code == KeyEvent.VK_UP
                || code == KeyEvent.VK_DOWN
                || code == KeyEvent.VK_SPACE) {
            return;
        }
        updatePopup();
    }

    private void onKeyPressed(KeyEvent e) {
        if (!popup.isVisible()) return;
        switch (e.getKeyCode()) {
            case KeyEvent.VK_UP -> {
                e.consume();
                int idx = list.getSelectedIndex();
                if (idx > 0) list.setSelectedIndex(idx - 1);
                list.ensureIndexIsVisible(list.getSelectedIndex());
            }
            case KeyEvent.VK_DOWN -> {
                e.consume();
                int idx = list.getSelectedIndex();
                if (idx < listModel.getSize() - 1) list.setSelectedIndex(idx + 1);
                list.ensureIndexIsVisible(list.getSelectedIndex());
            }
            case KeyEvent.VK_ENTER, KeyEvent.VK_TAB -> {
                if (popup.isVisible() && list.getSelectedIndex() >= 0) {
                    e.consume();
                    insertSelected();
                }
            }
            case KeyEvent.VK_SPACE -> {
                e.consume();
                insertAsFile();
            }
            case KeyEvent.VK_ESCAPE -> {
                e.consume();
                popup.setVisible(false);
            }
            default -> { /* handled by keyReleased */ }
        }
    }

    // -------------------------------------------------------------------------
    // Popup update
    // -------------------------------------------------------------------------

    private void updatePopup() {
        String token = currentAtToken();
        if (token == null) {
            popup.setVisible(false);
            return;
        }

        String typed    = token.substring(1);           // text after '@'
        int lastSlash   = typed.lastIndexOf('/');
        String currentDir = lastSlash >= 0 ? typed.substring(0, lastSlash + 1) : "";
        String filter     = lastSlash >= 0 ? typed.substring(lastSlash + 1)    : typed;
        String filterLc   = filter.toLowerCase(Locale.ROOT);

        String wdStr = workingDirSupplier.get();
        if (wdStr == null) { popup.setVisible(false); return; }
        Path wdPath = Path.of(wdStr);
        Path targetDir = wdPath.resolve(currentDir).normalize();

        File[] entries = targetDir.toFile().listFiles();
        if (entries == null) { popup.setVisible(false); return; }

        List<String> dirs  = new ArrayList<>();
        List<String> files = new ArrayList<>();
        for (File entry : entries) {
            if (entry.getName().startsWith(".")) continue;
            if (!entry.getName().toLowerCase(Locale.ROOT).startsWith(filterLc)) continue;
            if (entry.isDirectory()) dirs.add(entry.getName() + "/");
            else                     files.add(entry.getName());
        }
        Collections.sort(dirs);
        Collections.sort(files);

        if (dirs.isEmpty() && files.isEmpty()) {
            listModel.clear();
            popup.setVisible(false);
            return;
        }

        listModel.clear();
        listModel.addElement("..");       // always present
        for (String d : dirs)  listModel.addElement(d);
        for (String f : files) listModel.addElement(f);

        list.setSelectedIndex(0);

        if (!popup.isVisible()) {
            try {
                int caretPos = tc.getCaretPosition();
                java.awt.Rectangle r = tc.modelToView2D(caretPos).getBounds();
                popup.show(tc, r.x, r.y + r.height);
            } catch (BadLocationException ex) {
                popup.show(tc, 0, tc.getHeight());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Insertion
    // -------------------------------------------------------------------------

    /** Insert selected item, navigating into directories or completing files. */
    private void insertSelected() {
        String selected = list.getSelectedValue();
        if (selected == null) return;

        String token     = currentAtToken();
        if (token == null) { popup.setVisible(false); return; }
        String typed     = token.substring(1);
        int lastSlash    = typed.lastIndexOf('/');
        String currentDir = lastSlash >= 0 ? typed.substring(0, lastSlash + 1) : "";

        if ("..".equals(selected)) {
            // Navigate up: strip the last path component from currentDir
            String parent = parentOf(currentDir);
            replaceToken("@" + parent);
            updatePopup();
        } else if (selected.endsWith("/")) {
            // Navigate into directory
            replaceToken("@" + currentDir + selected);
            updatePopup();
        } else {
            // Complete file — insert with trailing space and close popup
            popup.setVisible(false);
            replaceToken("@" + currentDir + selected + " ");
        }
    }

    /** Insert selected value as-is (with trailing space), regardless of whether it's a dir. */
    private void insertAsFile() {
        String selected = list.getSelectedValue();
        if (selected == null) return;
        popup.setVisible(false);

        String token = currentAtToken();
        if (token == null) return;
        String typed      = token.substring(1);
        int lastSlash     = typed.lastIndexOf('/');
        String currentDir = lastSlash >= 0 ? typed.substring(0, lastSlash + 1) : "";

        String value = "..".equals(selected) ? parentOf(currentDir) : currentDir + selected;
        replaceToken("@" + value + " ");
    }

    private void replaceToken(String newText) {
        String text     = tc.getText();
        int caretPos    = tc.getCaretPosition();
        int atIdx       = -1;
        for (int i = caretPos - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '@') { atIdx = i; break; }
            if (Character.isWhitespace(c)) break;
        }
        if (atIdx < 0) return;
        // Find end of token (walk forward past non-whitespace)
        int end = caretPos;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end))) end++;
        try {
            tc.getDocument().remove(atIdx, end - atIdx);
            tc.getDocument().insertString(atIdx, newText, null);
            // Place caret at end of inserted text
            tc.setCaretPosition(atIdx + newText.length());
        } catch (BadLocationException ex) {
            tc.setText(tc.getText() + newText);
        }
    }

    /**
     * Returns the parent directory path (with trailing slash).
     * At the workdir root (""), returns "../" to go above the working directory.
     */
    private static String parentOf(String currentDir) {
        if (currentDir.isEmpty()) return "../";
        String trimmed = currentDir.substring(0, currentDir.length() - 1);
        int idx = trimmed.lastIndexOf('/');
        if (idx >= 0) return trimmed.substring(0, idx + 1);
        return "..".equals(trimmed) ? "../../" : "";
    }

    // -------------------------------------------------------------------------
    // Token detection
    // -------------------------------------------------------------------------

    /**
     * Returns the {@code @}-prefixed token under the caret, or {@code null} if
     * the caret is not inside an {@code @\S+} token.
     */
    private String currentAtToken() {
        String text = tc.getText();
        int pos = tc.getCaretPosition();
        if (pos <= 0 || pos > text.length()) return null;

        // Walk back to find @
        int start = -1;
        for (int i = pos - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '@') { start = i; break; }
            if (Character.isWhitespace(c)) break;
        }
        if (start < 0) return null;

        // Walk forward to find end of token
        int end = pos;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end))) end++;

        return text.substring(start, end);
    }

    /**
     * No-op stub kept for binary compatibility — pre-scan cache has been removed.
     * Calling this method has no effect.
     */
    public void clearCache() {
        // No-op: directory listing is now on-demand, no cache to clear.
    }
}
