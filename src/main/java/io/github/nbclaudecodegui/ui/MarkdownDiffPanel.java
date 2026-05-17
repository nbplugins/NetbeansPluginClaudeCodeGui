package io.github.nbclaudecodegui.ui;

import io.github.nbclaudecodegui.ui.common.MarkdownRenderer;
import io.github.nbclaudecodegui.ui.common.markdown.MarkdownFindBar;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import org.openide.util.NbPreferences;

/**
 * Side-by-side rendered markdown preview panel for before/after diff comparison.
 * Provides percentage-based scroll sync between the before and after panes.
 */
public class MarkdownDiffPanel extends JPanel {

    private static final String PREF_DIVIDER = "mdSplitDivider";

    private final JEditorPane mdBeforePane;
    private final JEditorPane mdAfterPane;
    private final JScrollPane beforeScroll;
    private final JScrollPane afterScroll;
    private final JSplitPane splitPane;
    private boolean syncActive = false;

    private Runnable onHide;
    private Runnable onPinPreview;

    /**
     * Sets the callback invoked when the user clicks "Hide" in the context menu.
     * The callback is responsible for collapsing or removing this panel from its parent.
     *
     * @param r the hide action; may be {@code null} to clear
     */
    public void setOnHide(Runnable r)       { this.onHide = r; }

    /**
     * Sets the callback invoked when the user clicks "Pin Preview" in the context menu.
     * The callback is responsible for opening or re-activating a persistent preview tab
     * showing the rendered after-state of the diffed file.
     *
     * @param r the pin-preview action; may be {@code null} to clear
     */
    public void setOnPinPreview(Runnable r) { this.onPinPreview = r; }

    /**
     * Creates a side-by-side markdown diff panel.
     *
     * @param before markdown text for the "before" (original) state
     * @param after  markdown text for the "after" (modified) state
     */
    public MarkdownDiffPanel(String before, String after) {
        super(new BorderLayout());

        String beforeHtml = MarkdownRenderer.toHtml(before != null ? before : "");
        String afterHtml  = MarkdownRenderer.toHtml(after  != null ? after  : "");

        mdBeforePane = MarkdownRenderer.createOutputPane(beforeHtml);
        mdAfterPane  = MarkdownRenderer.createOutputPane(afterHtml);

        beforeScroll = new JScrollPane(mdBeforePane);
        afterScroll  = new JScrollPane(mdAfterPane);

        MarkdownFindBar beforeFindBar = new MarkdownFindBar(mdBeforePane);
        MarkdownFindBar afterFindBar  = new MarkdownFindBar(mdAfterPane);

        JPanel beforePanel = new JPanel(new BorderLayout());
        beforePanel.add(beforeScroll, BorderLayout.CENTER);
        beforePanel.add(beforeFindBar, BorderLayout.SOUTH);

        JPanel afterPanel = new JPanel(new BorderLayout());
        afterPanel.add(afterScroll, BorderLayout.CENTER);
        afterPanel.add(afterFindBar, BorderLayout.SOUTH);

        splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, beforePanel, afterPanel);
        splitPane.setResizeWeight(0.5);

        // restore divider
        SwingUtilities.invokeLater(() -> {
            int w = splitPane.getWidth();
            if (w > 0) {
                int saved = NbPreferences.forModule(MarkdownDiffPanel.class)
                        .getInt(PREF_DIVIDER, w / 2);
                splitPane.setDividerLocation(saved);
            }
        });

        splitPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e ->
            NbPreferences.forModule(MarkdownDiffPanel.class)
                    .putInt(PREF_DIVIDER, (int) e.getNewValue()));

        add(splitPane, BorderLayout.CENTER);
        wireScrollSync();

        // Add "Find…", "Hide", and "Pin Preview" to the existing popup menus on both panes
        for (int i = 0; i < 2; i++) {
            JEditorPane pane = i == 0 ? mdBeforePane : mdAfterPane;
            MarkdownFindBar fb = i == 0 ? beforeFindBar : afterFindBar;
            JPopupMenu menu = pane.getComponentPopupMenu();
            if (menu == null) { menu = new JPopupMenu(); pane.setComponentPopupMenu(menu); }
            JMenuItem findItem = new JMenuItem("Find…");
            findItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK));
            findItem.addActionListener(e -> fb.gainFocus());
            menu.add(findItem);
            menu.addSeparator();
            JMenuItem hideItem = new JMenuItem("Hide");
            hideItem.addActionListener(e -> { if (onHide != null) onHide.run(); });
            JMenuItem pinItem = new JMenuItem("Pin Preview");
            pinItem.addActionListener(e -> { if (onPinPreview != null) onPinPreview.run(); });
            menu.add(hideItem);
            menu.add(pinItem);
            bindFindBarKeys(pane, fb);
        }
        // Let right-click on the divider also show the menu
        splitPane.setComponentPopupMenu(mdBeforePane.getComponentPopupMenu());
    }

    private static void bindFindBarKeys(JEditorPane pane, MarkdownFindBar fb) {
        KeyStroke ctrlF   = KeyStroke.getKeyStroke(KeyEvent.VK_F,  InputEvent.CTRL_DOWN_MASK);
        KeyStroke f3      = KeyStroke.getKeyStroke(KeyEvent.VK_F3, 0);
        KeyStroke shiftF3 = KeyStroke.getKeyStroke(KeyEvent.VK_F3, InputEvent.SHIFT_DOWN_MASK);
        pane.getInputMap(JComponent.WHEN_FOCUSED).put(ctrlF, "mddiff-find-show");
        pane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(ctrlF,   "mddiff-find-show");
        pane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(f3,      "mddiff-find-next");
        pane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(shiftF3, "mddiff-find-prev");
        pane.getActionMap().put("mddiff-find-show", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { fb.gainFocus(); }
        });
        pane.getActionMap().put("mddiff-find-next", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                if (fb.isVisible()) fb.findNext(); else fb.searchAndFindNext();
            }
        });
        pane.getActionMap().put("mddiff-find-prev", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                if (fb.isVisible()) fb.findPrev(); else fb.searchAndFindPrev();
            }
        });
    }

    private void wireScrollSync() {
        beforeScroll.getViewport().addChangeListener(e -> {
            if (syncActive) return;
            syncActive = true;
            try { syncViewport(beforeScroll, afterScroll); }
            finally { syncActive = false; }
        });
        afterScroll.getViewport().addChangeListener(e -> {
            if (syncActive) return;
            syncActive = true;
            try { syncViewport(afterScroll, beforeScroll); }
            finally { syncActive = false; }
        });
    }

    private static void syncViewport(JScrollPane src, JScrollPane dst) {
        int srcMax = src.getVerticalScrollBar().getMaximum()
                   - src.getVerticalScrollBar().getVisibleAmount();
        if (srcMax <= 0) return;
        double ratio = (double) src.getVerticalScrollBar().getValue() / srcMax;
        int dstMax = dst.getVerticalScrollBar().getMaximum()
                   - dst.getVerticalScrollBar().getVisibleAmount();
        dst.getVerticalScrollBar().setValue((int) (ratio * dstMax));
    }

    /**
     * Tries to attach bidirectional percentage scroll sync between the raw diff view
     * component and the markdown panes. Silently does nothing if no JScrollPane is found.
     *
     * @param diffViewComponent the raw diff view component (may contain a JScrollPane)
     */
    public void attachRawDiffSync(Component diffViewComponent) {
        JScrollPane rawScroll = findScrollPane(diffViewComponent);
        if (rawScroll == null) return;

        // before → raw
        beforeScroll.getViewport().addChangeListener(e -> {
            if (syncActive) return;
            syncActive = true;
            try { syncViewport(beforeScroll, rawScroll); }
            finally { syncActive = false; }
        });
        // after → raw
        afterScroll.getViewport().addChangeListener(e -> {
            if (syncActive) return;
            syncActive = true;
            try { syncViewport(afterScroll, rawScroll); }
            finally { syncActive = false; }
        });
        // raw → before and after
        rawScroll.getViewport().addChangeListener(e -> {
            if (syncActive) return;
            syncActive = true;
            try {
                syncViewport(rawScroll, beforeScroll);
                syncViewport(rawScroll, afterScroll);
            } finally { syncActive = false; }
        });
    }

    private static JScrollPane findScrollPane(Component c) {
        if (c instanceof JScrollPane) return (JScrollPane) c;
        if (c instanceof Container) {
            for (Component child : ((Container) c).getComponents()) {
                JScrollPane found = findScrollPane(child);
                if (found != null) return found;
            }
        }
        return null;
    }
}
