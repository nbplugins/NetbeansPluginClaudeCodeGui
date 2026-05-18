package io.github.nbplugins.claudecodegui.ui;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JEditorPane;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSplitPane;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MarkdownDiffPanelTest {

    @Test
    void constructsWithoutError() {
        MarkdownDiffPanel panel = new MarkdownDiffPanel("# Hello", "# World");
        assertNotNull(panel);
    }

    @Test
    void constructsWithNullContent() {
        MarkdownDiffPanel panel = new MarkdownDiffPanel(null, null);
        assertNotNull(panel);
    }

    @Test
    void attachRawDiffSyncFindsNoScrollPaneGracefully() {
        MarkdownDiffPanel panel = new MarkdownDiffPanel("before", "after");
        assertDoesNotThrow(() -> panel.attachRawDiffSync(new JPanel()));
    }

    @Test
    void editorPanesHaveSelectAllAndCopyContextMenu() {
        MarkdownDiffPanel panel = new MarkdownDiffPanel("# Before", "# After");
        List<JEditorPane> panes = new ArrayList<>();
        collectEditorPanes(panel, panes);
        assertEquals(2, panes.size(), "Expected two JEditorPane instances");
        for (JEditorPane pane : panes) {
            JPopupMenu popup = pane.getComponentPopupMenu();
            assertNotNull(popup, "JEditorPane should have a component popup menu");
            List<String> labels = menuItemLabels(popup);
            assertTrue(labels.contains("Select All"), "Context menu should contain 'Select All'");
            assertTrue(labels.contains("Copy"), "Context menu should contain 'Copy'");
        }
    }

    private static List<String> menuItemLabels(JPopupMenu menu) {
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < menu.getComponentCount(); i++) {
            Component c = menu.getComponent(i);
            if (c instanceof JMenuItem) labels.add(((JMenuItem) c).getText());
        }
        return labels;
    }

    @Test
    void hideAndPinItemsPresentInBothEditorPaneMenus() {
        MarkdownDiffPanel panel = new MarkdownDiffPanel("# A", "# B");
        List<JEditorPane> panes = new ArrayList<>();
        collectEditorPanes(panel, panes);
        assertEquals(2, panes.size());
        for (JEditorPane pane : panes) {
            List<String> labels = menuItemLabels(pane.getComponentPopupMenu());
            assertTrue(labels.contains("Hide"), "Context menu should contain 'Hide'");
            assertTrue(labels.contains("Pin Preview"), "Context menu should contain 'Pin Preview'");
        }
    }

    @Test
    void hideCallbackFiredFromMenu() {
        MarkdownDiffPanel panel = new MarkdownDiffPanel("a", "b");
        boolean[] called = { false };
        panel.setOnHide(() -> called[0] = true);
        List<JEditorPane> panes = new ArrayList<>();
        collectEditorPanes(panel, panes);
        JMenuItem hideItem = findMenuItem(panes.get(0).getComponentPopupMenu(), "Hide");
        assertNotNull(hideItem);
        hideItem.doClick();
        assertTrue(called[0]);
    }

    @Test
    void pinPreviewCallbackFiredFromMenu() {
        MarkdownDiffPanel panel = new MarkdownDiffPanel("a", "b");
        boolean[] called = { false };
        panel.setOnPinPreview(() -> called[0] = true);
        List<JEditorPane> panes = new ArrayList<>();
        collectEditorPanes(panel, panes);
        JMenuItem pinItem = findMenuItem(panes.get(0).getComponentPopupMenu(), "Pin Preview");
        assertNotNull(pinItem);
        pinItem.doClick();
        assertTrue(called[0]);
    }

    @Test
    void splitPaneHasComponentPopupMenu() {
        MarkdownDiffPanel panel = new MarkdownDiffPanel("a", "b");
        JSplitPane split = findSplitPane(panel);
        assertNotNull(split);
        assertNotNull(split.getComponentPopupMenu());
    }

    private static JMenuItem findMenuItem(JPopupMenu menu, String text) {
        if (menu == null) return null;
        for (int i = 0; i < menu.getComponentCount(); i++) {
            Component c = menu.getComponent(i);
            if (c instanceof JMenuItem m && text.equals(m.getText())) return m;
        }
        return null;
    }

    private static JSplitPane findSplitPane(Component c) {
        if (c instanceof JSplitPane sp) return sp;
        if (c instanceof Container ct) {
            for (Component child : ct.getComponents()) {
                JSplitPane found = findSplitPane(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void collectEditorPanes(Component c, List<JEditorPane> result) {
        if (c instanceof JEditorPane) result.add((JEditorPane) c);
        if (c instanceof Container) {
            for (Component child : ((Container) c).getComponents()) {
                collectEditorPanes(child, result);
            }
        }
    }
}
