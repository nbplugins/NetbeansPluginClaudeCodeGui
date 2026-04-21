package io.github.nbclaudecodegui.ui;

import io.github.nbclaudecodegui.settings.ClaudeCodePreferences;
import io.github.nbclaudecodegui.ui.common.AtPathHighlighter;
import io.github.nbclaudecodegui.ui.common.DecoratedTextArea;
import io.github.nbclaudecodegui.ui.common.UiUtils;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

/**
 * Pure-UI input panel for a Claude Code session.
 *
 * <p>Renders a multi-line text area, a Send button, and a Cancel button.
 * Contains no references to {@link io.github.nbclaudecodegui.model.ClaudeSessionModel}
 * or {@link io.github.nbclaudecodegui.controller.ClaudeSessionController}; all
 * behaviour is driven through constructor callbacks:
 * <ul>
 *   <li>{@code onSend} — fired with the trimmed input text when the user sends</li>
 *   <li>{@code onCancel} — fired when the user presses Cancel or Esc</li>
 *   <li>{@code promptHistorySupplier} — queried on demand for Ctrl+Up/Down history navigation</li>
 * </ul>
 *
 * <p>The send key (Enter / Ctrl+Enter / Shift+Enter / Alt+Enter) is read from
 * {@link ClaudeCodePreferences#getSendKey()} at the time of each key event.
 *
 * <p>Stage 16: files dropped or pasted are inserted as {@code @path} tokens directly
 * into the textarea; {@code doSend()} sends the textarea text as-is.
 */
public final class ClaudePromptPanel extends JPanel {

    /** Unicode send icon (▶). */
    private static final String ICON_SEND      = "\u25b6";
    /** Unicode cancel icon (✖). */
    private static final String ICON_CANCEL    = "\u2716";
    /** Unicode favorites icon (★). */
    private static final String ICON_FAVORITES = "\u2605";
    /** Unicode history icon (☰). */
    private static final String ICON_HISTORY   = "\u2630";
    /** The prompt input text area with @-path highlighting and shared input features. */
    private final DecoratedTextArea inputArea;
    /** Button that sends the current prompt. */
    private final JButton   sendButton;
    /** Button that cancels the running prompt (Ctrl+C). */
    private final JButton   cancelButton;

    /** Callback invoked with the prompt text when the user sends. */
    private final Consumer<String>       onSend;
    /** Callback invoked when the user cancels. */
    private final Runnable               onCancel;
    /** Callback invoked when Shift+Tab is pressed. */
    private final Runnable               onShiftTab;
    /** Supplies the in-session prompt history for navigation. */
    private final Supplier<List<String>> promptHistorySupplier;
    /** Supplies the current working directory path. */
    private final Supplier<String>       workingDirSupplier;
    /** Callback for "Start New Session" context menu item; may be {@code null}. */
    private final Runnable               onStartNewSession;
    /** Callback for "Switch to Session…" context menu item; may be {@code null}. */
    private final Runnable               onSwitchSession;

    /** Current position in the history list; {@code -1} = newest (empty field). */
    private int historyIndex = -1;

    /** Highlights {@code @path} tokens in the input area. */
    @SuppressWarnings("FieldCanBeLocal")
    private final AtPathHighlighter   pathHighlighter;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * Creates the input panel.
     *
     * @param onSend                 called with the text when the user sends a prompt;
     *                               the text has already been cleared from the field
     * @param onCancel               called when the user presses Cancel or Esc
     * @param onShiftTab             called when Shift+Tab is pressed on any control in
     *                               the panel; used to cycle the Claude Code edit mode
     * @param promptHistorySupplier  returns the current in-session prompt history
     *                               (index 0 = most recent); queried on demand
     * @param workingDirSupplier     returns the current working directory path; used for
     *                               the history/favorites popup and file completion
     * @param onStartNewSession      called when "Start New Session" context menu item is clicked;
     *                               may be {@code null}
     * @param onSwitchSession        called when "Switch to Session…" context menu item is clicked;
     *                               may be {@code null}
     */
    public ClaudePromptPanel(Consumer<String> onSend,
                             Runnable onCancel,
                             Runnable onShiftTab,
                             Supplier<List<String>> promptHistorySupplier,
                             Supplier<String> workingDirSupplier,
                             Runnable onStartNewSession,
                             Runnable onSwitchSession) {
        super(new BorderLayout());
        this.onSend                = onSend;
        this.onCancel              = onCancel;
        this.onShiftTab            = onShiftTab;
        this.promptHistorySupplier = promptHistorySupplier;
        this.workingDirSupplier    = workingDirSupplier;
        this.onStartNewSession     = onStartNewSession;
        this.onSwitchSession       = onSwitchSession;

        inputArea = new DecoratedTextArea(3, 40, workingDirSupplier);
        inputArea.setLineWrap(true);
        inputArea.setWrapStyleWord(true);
        inputArea.setFocusTraversalKeysEnabled(false);
        bindKeys(inputArea);
        attachContextMenu(inputArea);

        // Install @-path highlighter (DecoratedTextArea implements RangeHighlightable)
        pathHighlighter = AtPathHighlighter.install(inputArea);

        sendButton   = new JButton("<html><font color='" + UiUtils.toHex(UiUtils.getPositiveColor()) + "'>" + ICON_SEND   + "</font> Send</html>");
        cancelButton = new JButton("<html><font color='" + UiUtils.toHex(UiUtils.getNegativeColor()) + "'>" + ICON_CANCEL + "</font> Cancel</html>");
        sendButton.setEnabled(false);
        cancelButton.setEnabled(false);
        sendButton.addActionListener(e -> doSend());
        cancelButton.addActionListener(e -> onCancel.run());

        JPanel buttonCol = new JPanel();
        buttonCol.setLayout(new BoxLayout(buttonCol, BoxLayout.Y_AXIS));
        sendButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        cancelButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        buttonCol.add(sendButton);
        buttonCol.add(Box.createRigidArea(new Dimension(0, 4)));
        buttonCol.add(cancelButton);
        buttonCol.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JButton historyButton   = new JButton("<html>" + ICON_HISTORY + " History</html>");
        JButton favoritesButton = new JButton("<html><font color='" + UiUtils.toHex(UiUtils.getWarningColor()) + "'>" + ICON_FAVORITES + "</font> Favorites</html>");
        historyButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        favoritesButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        historyButton.setToolTipText("Browse prompt history");
        favoritesButton.setToolTipText("Browse favorites");

        historyButton.addActionListener(e -> showHistoryDialog(historyButton));
        favoritesButton.addActionListener(e -> showFavoritesDialog(favoritesButton));

        JPanel leftCol = new JPanel();
        leftCol.setLayout(new BoxLayout(leftCol, BoxLayout.Y_AXIS));
        leftCol.add(favoritesButton);
        leftCol.add(Box.createRigidArea(new Dimension(0, 4)));
        leftCol.add(historyButton);
        leftCol.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        // Equalize all button widths and heights.
        // Done after the component is shown so HTML-rendered labels are measured correctly.
        JButton[] allButtons = {sendButton, cancelButton, historyButton, favoritesButton};
        addAncestorListener(new javax.swing.event.AncestorListener() {
            @Override public void ancestorAdded(javax.swing.event.AncestorEvent e) {
                equalizeButtons(allButtons);
                revalidate();
                removeAncestorListener(this);
            }
            @Override public void ancestorRemoved(javax.swing.event.AncestorEvent e) {}
            @Override public void ancestorMoved(javax.swing.event.AncestorEvent e) {}
        });

        JPanel centerCol = new JPanel(new BorderLayout());
        centerCol.add(new JScrollPane(inputArea), BorderLayout.CENTER);

        setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Color.LIGHT_GRAY));
        add(centerCol,   BorderLayout.CENTER);
        add(buttonCol,   BorderLayout.EAST);
        add(leftCol,     BorderLayout.WEST);

        // Shift+Tab on buttons (and any other non-textarea child) → cycle edit mode.
        // The inputArea handles its own Shift+Tab in bindKeys because
        // setFocusTraversalKeysEnabled(false) prevents the InputMap from firing.
        KeyStroke shiftTab = KeyStroke.getKeyStroke(KeyEvent.VK_TAB, InputEvent.SHIFT_DOWN_MASK);
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(shiftTab, "shiftTab");
        getActionMap().put("shiftTab", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                onShiftTab.run();
            }
        });
    }

    @Override
    public java.awt.Dimension getMinimumSize() {
        BorderLayout layout = (BorderLayout) getLayout();
        java.awt.Component east = layout.getLayoutComponent(BorderLayout.EAST);
        java.awt.Component west = layout.getLayoutComponent(BorderLayout.WEST);
        int minH = 0;
        if (east != null) minH = Math.max(minH, east.getMinimumSize().height);
        if (west != null) minH = Math.max(minH, west.getMinimumSize().height);
        java.awt.Insets ins = getInsets();
        return new java.awt.Dimension(0, minH + ins.top + ins.bottom);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Synchronises button state with the session lifecycle.
     *
     * @param ready {@code true} when Claude is idle and ready to accept input
     *              (Send enabled, Cancel disabled); {@code false} when working
     *              (Send disabled, Cancel enabled)
     */
    public void setReadyState(boolean ready) {
        sendButton.setEnabled(ready);
        cancelButton.setEnabled(!ready);
    }

    /**
     * Requests keyboard focus on the text input area.
     */
    public void requestFocusOnInputArea() {
        inputArea.requestFocusInWindow();
    }

    /**
     * Clears the input area and resets history navigation.
     * Also clears file attachments and cleans up temp files.
     * Called by the owning tab when the session stops.
     */
    public void reset() {
        inputArea.setText("");
        historyIndex = -1;
        inputArea.cleanup();
    }

    // -------------------------------------------------------------------------
    // Key bindings
    // -------------------------------------------------------------------------

    private void bindKeys(DecoratedTextArea area) {
        area.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                // Shift+Tab → cycle edit mode
                if (e.getKeyCode() == KeyEvent.VK_TAB && e.isShiftDown()) {
                    e.consume();
                    onShiftTab.run();
                    return;
                }
                // Esc → Cancel
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    e.consume();
                    if (cancelButton.isEnabled()) onCancel.run();
                    return;
                }
                // Ctrl+Up — navigate history backward (older)
                if (e.getKeyCode() == KeyEvent.VK_UP && e.isControlDown()
                        && !e.isShiftDown() && !e.isAltDown()) {
                    e.consume();
                    navigateHistory(1);
                    return;
                }
                // Ctrl+Down — navigate history forward (newer)
                if (e.getKeyCode() == KeyEvent.VK_DOWN && e.isControlDown()
                        && !e.isShiftDown() && !e.isAltDown()) {
                    e.consume();
                    navigateHistory(-1);
                    return;
                }

                String sendKey = ClaudeCodePreferences.getSendKey();
                boolean ctrl  = e.isControlDown();
                boolean shift = e.isShiftDown();
                boolean alt   = e.isAltDown();
                boolean plain = !ctrl && !shift && !alt;
                if (e.getKeyCode() != KeyEvent.VK_ENTER) return;

                boolean match = switch (sendKey) {
                    case ClaudeCodePreferences.ENTER       -> plain;
                    case ClaudeCodePreferences.CTRL_ENTER  -> ctrl && !shift && !alt;
                    case ClaudeCodePreferences.SHIFT_ENTER -> shift && !ctrl && !alt;
                    case ClaudeCodePreferences.ALT_ENTER   -> alt && !ctrl && !shift;
                    default -> false;
                };
                if (match) {
                    e.consume();
                    doSend();
                }
            }
        });
    }

    private void doSend() {
        String text = inputArea.getText();
        if (text.isEmpty()) return;

        inputArea.setText("");
        inputArea.requestFocusInWindow();
        historyIndex = -1;
        onSend.accept(text);
    }

    /** delta=+1 → older, delta=-1 → newer. */
    private void navigateHistory(int delta) {
        List<String> history = promptHistorySupplier.get();
        if (history.isEmpty()) return;
        historyIndex = Math.max(-1, Math.min(history.size() - 1, historyIndex + delta));
        if (historyIndex < 0) {
            inputArea.setText("");
        } else {
            inputArea.setText(history.get(historyIndex));
            inputArea.setCaretPosition(inputArea.getDocument().getLength());
        }
    }

    // -------------------------------------------------------------------------
    // Context menu
    // -------------------------------------------------------------------------

    private void attachContextMenu(DecoratedTextArea area) {
        // DecoratedTextArea already has Cut/Copy/Paste/SelectAll/Clear + AddToFavorites/Favorites...
        // We only need to append history navigation items to the existing menu.
        JPopupMenu menu = area.getContextMenu();

        JMenuItem prevPrompt = new JMenuItem("Previous prompt  (Ctrl+\u2191)");
        prevPrompt.addActionListener(e -> navigateHistory(1));

        JMenuItem nextPrompt = new JMenuItem("Next prompt  (Ctrl+\u2193)");
        nextPrompt.addActionListener(e -> navigateHistory(-1));

        JMenuItem startNewItem = new JMenuItem("Start New Session");
        startNewItem.addActionListener(e -> { if (onStartNewSession != null) onStartNewSession.run(); });

        JMenuItem switchItem = new JMenuItem("Switch to Session\u2026");
        switchItem.addActionListener(e -> { if (onSwitchSession != null) onSwitchSession.run(); });

        menu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                List<String> h = promptHistorySupplier.get();
                prevPrompt.setEnabled(!h.isEmpty() && historyIndex < h.size() - 1);
                nextPrompt.setEnabled(historyIndex > -1);
                startNewItem.setEnabled(onStartNewSession != null);
                switchItem.setEnabled(onSwitchSession != null);
            }
            @Override public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {}
            @Override public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {}
        });

        menu.addSeparator();
        menu.add(prevPrompt);
        menu.add(nextPrompt);
        menu.addSeparator();
        menu.add(startNewItem);
        menu.add(switchItem);
    }

    // -------------------------------------------------------------------------
    // History / Favorites dialogs
    // -------------------------------------------------------------------------

    private void showHistoryDialog(JButton anchor) {
        String wd = workingDirSupplier != null ? workingDirSupplier.get() : null;
        if (wd == null) return;
        Window owner = SwingUtilities.getWindowAncestor(anchor);
        HistoryDialog dlg = new HistoryDialog(owner, Path.of(wd), text -> inputArea.setText(text));
        dlg.setVisible(true);
    }

    private void showFavoritesDialog(JButton anchor) {
        String wd = workingDirSupplier != null ? workingDirSupplier.get() : null;
        if (wd == null) return;
        Window owner = SwingUtilities.getWindowAncestor(anchor);
        FavoritesDialog dlg = new FavoritesDialog(owner, Path.of(wd), text -> inputArea.setText(text));
        dlg.setVisible(true);
    }

    // -------------------------------------------------------------------------
    // File chooser (Attach button)
    // -------------------------------------------------------------------------

    private void showFileChooser(JButton anchor) {
        JFileChooser chooser = new JFileChooser();
        chooser.setMultiSelectionEnabled(true);
        chooser.setDialogTitle("Attach files to prompt");
        Window owner = SwingUtilities.getWindowAncestor(anchor);
        int result = chooser.showOpenDialog(owner);
        if (result == JFileChooser.APPROVE_OPTION) {
            for (File f : chooser.getSelectedFiles()) {
                String wdStr = workingDirSupplier != null ? workingDirSupplier.get() : null;
                Path workDir = wdStr != null ? Path.of(wdStr).normalize() : null;
                Path filePath = f.toPath().toAbsolutePath().normalize();
                String token;
                if (workDir != null && filePath.startsWith(workDir)) {
                    Path rel = workDir.relativize(filePath);
                    String relStr = rel.toString().replace(java.io.File.separatorChar, '/');
                    token = relStr.isEmpty() ? "@./" : "@" + relStr;
                } else {
                    token = "@" + filePath;
                }
                insertTokenAtCaret(token);
            }
        }
    }

    /**
     * Inserts {@code token} at the current caret position in {@code inputArea},
     * prepending a space if needed and always appending a trailing space.
     * Sets the caret position immediately after the inserted text.
     *
     * <p>Package-private for testability.
     */
    void insertTokenAtCaret(String token) {
        int pos = inputArea.getCaretPosition();
        try {
            String before = inputArea.getText(0, pos);
            String prefix = (!before.isEmpty() && !before.endsWith(" ") && !before.endsWith("\n")) ? " " : "";
            String toInsert = prefix + token + " ";
            inputArea.getDocument().insertString(pos, toInsert, null);
            inputArea.setCaretPosition(pos + toInsert.length());
        } catch (javax.swing.text.BadLocationException ex) {
            inputArea.append(" " + token + " ");
        }
    }

    /**
     * Sets preferredSize, minimumSize, and maximumSize on all buttons to the same
     * value (the maximum preferred width/height across all buttons). This prevents
     * layout managers from shrinking buttons below their natural size and causing
     * HTML-rendered icon+text to wrap onto separate lines.
     *
     * <p>Package-private for testability.
     */
    static void equalizeButtons(JButton[] buttons) {
        int maxW = 0, maxH = 0;
        for (JButton b : buttons) {
            maxW = Math.max(maxW, b.getPreferredSize().width);
            maxH = Math.max(maxH, b.getPreferredSize().height);
        }
        Dimension eq = new Dimension(maxW, maxH);
        for (JButton b : buttons) {
            b.setPreferredSize(eq);
            b.setMinimumSize(eq);
            b.setMaximumSize(eq);
        }
    }
}
