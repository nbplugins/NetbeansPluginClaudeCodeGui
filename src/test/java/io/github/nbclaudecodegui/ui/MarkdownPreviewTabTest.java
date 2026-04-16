package io.github.nbclaudecodegui.ui;

import io.github.nbclaudecodegui.ui.common.MarkdownRenderer;
import java.awt.Component;
import org.openide.windows.TopComponent;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JEditorPane;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that panes created for MarkdownPreviewTab (via createOutputPane())
 * have a context menu with Select All and Copy, and that live-update logic works.
 */
class MarkdownPreviewTabTest {

    @TempDir
    File tempDir;

    @BeforeEach
    void clearOpenTabs() {
        MarkdownPreviewTab.clearOpenTabsForTest();
    }

    @Test
    void createOutputPaneHasSelectAllAndCopyContextMenu() {
        JEditorPane pane = MarkdownRenderer.createOutputPane();
        JPopupMenu popup = pane.getComponentPopupMenu();
        assertNotNull(popup, "createOutputPane() should attach a component popup menu");
        List<String> labels = menuItemLabels(popup);
        assertTrue(labels.contains("Select All"), "Context menu should contain 'Select All'");
        assertTrue(labels.contains("Copy"), "Context menu should contain 'Copy'");
    }

    @Test
    void createOutputPaneIsNotEditable() {
        JEditorPane pane = MarkdownRenderer.createOutputPane();
        assertFalse(pane.isEditable());
    }

    @Test
    void updateContentChangesPaneText() throws Exception {
        MarkdownPreviewTab tab = new MarkdownPreviewTab();
        JEditorPane pane = new JEditorPane();
        pane.setContentType("text/html");
        pane.setText("<html>old</html>");
        tab.setPaneForTest(pane);

        tab.updateContent("# New content");

        // updateContent schedules on EDT — flush it
        SwingUtilities.invokeAndWait(() -> {});

        String text = pane.getText();
        assertFalse(text.contains("old"), "Pane should no longer contain old content after updateContent");
    }

    @Test
    void componentClosedRemovesFromOpenTabs() {
        MarkdownPreviewTab tab = new MarkdownPreviewTab();
        tab.setFilePathForTest("/some/file.md");
        MarkdownPreviewTab.getOpenTabsForTest().put("/some/file.md", tab);

        tab.componentClosedForTest();

        assertFalse(MarkdownPreviewTab.getOpenTabsForTest().containsKey("/some/file.md"),
                "OPEN_TABS should not contain the tab after componentClosed");
    }

    @Test
    void openTabsInitiallyEmptyAfterClear() {
        assertTrue(MarkdownPreviewTab.getOpenTabsForTest().isEmpty());
    }

    // --- Navigation tests ---

    @Test
    void resolveLinkReturnsNullForHttp() {
        MarkdownPreviewTab tab = new MarkdownPreviewTab();
        tab.setFilePathForTest("/some/file.md");
        assertNull(tab.resolveLink("https://example.com"));
        assertNull(tab.resolveLink("http://example.com/page"));
    }

    @Test
    void resolveLinkReturnsNullForNonMarkdown() throws Exception {
        File txt = new File(tempDir, "foo.txt");
        txt.createNewFile();
        MarkdownPreviewTab tab = new MarkdownPreviewTab();
        tab.setFilePathForTest(new File(tempDir, "index.md").getAbsolutePath());
        assertNull(tab.resolveLink("foo.txt"));
    }

    @Test
    void resolveLinkResolvesRelativeMdPath() throws Exception {
        File other = new File(tempDir, "other.md");
        other.createNewFile();
        MarkdownPreviewTab tab = new MarkdownPreviewTab();
        tab.setFilePathForTest(new File(tempDir, "index.md").getAbsolutePath());
        File resolved = tab.resolveLink("other.md");
        assertNotNull(resolved);
        assertEquals(other.getCanonicalPath(), resolved.getCanonicalPath());
    }

    @Test
    void canGoBackFalseInitially() {
        MarkdownPreviewTab tab = new MarkdownPreviewTab();
        tab.historyPaths.add("/some/file.md");
        tab.historyFos.add(null);
        tab.historyIndex = 0;
        assertFalse(tab.canGoBack());
    }

    @Test
    void canGoForwardFalseInitially() {
        MarkdownPreviewTab tab = new MarkdownPreviewTab();
        tab.historyPaths.add("/some/file.md");
        tab.historyFos.add(null);
        tab.historyIndex = 0;
        assertFalse(tab.canGoForward());
    }

    @Test
    void canGoBackTrueAfterNavigate() {
        MarkdownPreviewTab tab = new MarkdownPreviewTab();
        tab.historyPaths.add("/first.md");
        tab.historyFos.add(null);
        tab.historyIndex = 0;
        tab.setFilePathForTest("/first.md");
        // Simulate addToHistory=true
        tab.historyPaths.add("/second.md");
        tab.historyFos.add(null);
        tab.historyIndex = 1;
        assertTrue(tab.canGoBack());
    }

    @Test
    void canGoForwardTrueAfterBack() {
        MarkdownPreviewTab tab = new MarkdownPreviewTab();
        tab.historyPaths.add("/first.md");
        tab.historyFos.add(null);
        tab.historyPaths.add("/second.md");
        tab.historyFos.add(null);
        tab.historyIndex = 1;
        // Go back
        tab.historyIndex = 0;
        assertTrue(tab.canGoForward());
    }

    @Test
    void contextMenuContainsBackAndForward() {
        MarkdownPreviewTab tab = new MarkdownPreviewTab();
        tab.historyPaths.add("/file.md");
        tab.historyFos.add(null);
        tab.historyIndex = 0;
        tab.setPaneForTest(new JEditorPane());
        JPopupMenu menu = tab.buildContextMenu();
        List<String> labels = menuItemLabels(menu);
        assertTrue(labels.stream().anyMatch(l -> l.contains("Back")), "Menu should contain Back");
        assertTrue(labels.stream().anyMatch(l -> l.contains("Forward")), "Menu should contain Forward");
    }

    // --- Context menu enabled/visible by link type ---

    private static JMenuItem findItem(JPopupMenu menu, String text) {
        for (int i = 0; i < menu.getComponentCount(); i++) {
            Component c = menu.getComponent(i);
            if (c instanceof JMenuItem item && text.equals(item.getText())) return item;
        }
        return null;
    }

    private static void fireWillBecomeVisible(JPopupMenu menu) {
        menu.getPopupMenuListeners()[0].popupMenuWillBecomeVisible(null);
    }

    @Test
    void contextMenuEnablesTabItemsForLocalMd() throws Exception {
        File other = new File(tempDir, "other.md");
        other.createNewFile();
        MarkdownPreviewTab tab = new MarkdownPreviewTab();
        tab.setFilePathForTest(new File(tempDir, "index.md").getAbsolutePath());
        tab.setPaneForTest(new JEditorPane());
        JPopupMenu menu = tab.buildContextMenu();
        tab.currentHoverHref = "other.md";
        fireWillBecomeVisible(menu);

        JMenuItem openSame    = findItem(menu, "Open in Same Tab");
        JMenuItem openNew     = findItem(menu, "Open in New Tab");
        JMenuItem openBrowser = findItem(menu, "Open in Browser");
        assertTrue(openSame.isVisible(),     "openSame must be visible for local md");
        assertTrue(openSame.isEnabled(),     "openSame must be enabled for local md");
        assertTrue(openNew.isVisible(),      "openNew must be visible for local md");
        assertTrue(openNew.isEnabled(),      "openNew must be enabled for local md");
        assertTrue(openBrowser.isVisible(),  "openBrowser must be visible for local md");
        assertFalse(openBrowser.isEnabled(), "openBrowser must be disabled for local md");
    }

    @Test
    void contextMenuEnablesBrowserForExternalLink() {
        MarkdownPreviewTab tab = new MarkdownPreviewTab();
        tab.setFilePathForTest("/some/index.md");
        tab.setPaneForTest(new JEditorPane());
        JPopupMenu menu = tab.buildContextMenu();
        tab.currentHoverHref = "https://example.com";
        fireWillBecomeVisible(menu);

        JMenuItem openSame    = findItem(menu, "Open in Same Tab");
        JMenuItem openNew     = findItem(menu, "Open in New Tab");
        JMenuItem openBrowser = findItem(menu, "Open in Browser");
        assertTrue(openBrowser.isVisible(),  "openBrowser must be visible for external link");
        assertTrue(openBrowser.isEnabled(),  "openBrowser must be enabled for external link");
        assertTrue(openSame.isVisible(),     "openSame must be visible for external link");
        assertFalse(openSame.isEnabled(),    "openSame must be disabled for external link");
        assertTrue(openNew.isVisible(),      "openNew must be visible for external link");
        assertFalse(openNew.isEnabled(),     "openNew must be disabled for external link");
    }

    @Test
    void contextMenuHidesLinkItemsWhenNoHover() {
        MarkdownPreviewTab tab = new MarkdownPreviewTab();
        tab.setPaneForTest(new JEditorPane());
        JPopupMenu menu = tab.buildContextMenu();
        tab.currentHoverHref = null;
        fireWillBecomeVisible(menu);

        assertFalse(findItem(menu, "Open in Same Tab").isVisible(),  "openSame must be hidden when no hover");
        assertFalse(findItem(menu, "Open in New Tab").isVisible(),   "openNew must be hidden when no hover");
        assertFalse(findItem(menu, "Open in Browser").isVisible(),   "openBrowser must be hidden when no hover");
    }

    @Test
    void contextMenuDisablesAllForUnresolvableLink() throws Exception {
        MarkdownPreviewTab tab = new MarkdownPreviewTab();
        tab.setFilePathForTest(new File(tempDir, "index.md").getAbsolutePath());
        tab.setPaneForTest(new JEditorPane());
        JPopupMenu menu = tab.buildContextMenu();
        tab.currentHoverHref = "foo.txt";  // non-md, won't resolve
        fireWillBecomeVisible(menu);

        JMenuItem openSame    = findItem(menu, "Open in Same Tab");
        JMenuItem openNew     = findItem(menu, "Open in New Tab");
        JMenuItem openBrowser = findItem(menu, "Open in Browser");
        assertTrue(openSame.isVisible(),     "openSame must be visible");
        assertFalse(openSame.isEnabled(),    "openSame must be disabled for non-md link");
        assertTrue(openNew.isVisible(),      "openNew must be visible");
        assertFalse(openNew.isEnabled(),     "openNew must be disabled for non-md link");
        assertTrue(openBrowser.isVisible(),  "openBrowser must be visible");
        assertFalse(openBrowser.isEnabled(), "openBrowser must be disabled for non-md link");
    }

    @Test
    void contextMenuActionListenersAttachedAtBuildTime() {
        // Verify that openSame/openNew/openBrowser have exactly one action listener
        // after buildContextMenu() — they must NOT use dynamic per-popup listeners.
        MarkdownPreviewTab tab = new MarkdownPreviewTab();
        tab.historyPaths.add("/file.md");
        tab.historyFos.add(null);
        tab.historyIndex = 0;
        tab.setPaneForTest(new JEditorPane());
        JPopupMenu menu = tab.buildContextMenu();

        // Find openSame / openNew / openBrowser items
        JMenuItem openSame = null, openNew = null, openBrowser = null;
        for (int i = 0; i < menu.getComponentCount(); i++) {
            Component c = menu.getComponent(i);
            if (c instanceof JMenuItem item) {
                if ("Open in Same Tab".equals(item.getText())) openSame = item;
                else if ("Open in New Tab".equals(item.getText())) openNew = item;
                else if ("Open in Browser".equals(item.getText())) openBrowser = item;
            }
        }
        assertNotNull(openSame, "Menu should have 'Open in Same Tab'");
        assertNotNull(openNew, "Menu should have 'Open in New Tab'");
        assertNotNull(openBrowser, "Menu should have 'Open in Browser'");

        // Each item must have exactly 1 listener registered at build time
        assertEquals(1, openSame.getActionListeners().length,
                "openSame must have exactly 1 action listener after buildContextMenu()");
        assertEquals(1, openNew.getActionListeners().length,
                "openNew must have exactly 1 action listener after buildContextMenu()");
        assertEquals(1, openBrowser.getActionListeners().length,
                "openBrowser must have exactly 1 action listener after buildContextMenu()");
    }

    @Test
    void persistenceTypeIsAlways() {
        MarkdownPreviewTab tab = new MarkdownPreviewTab();
        assertEquals(TopComponent.PERSISTENCE_ALWAYS, tab.getPersistenceType(),
                "MarkdownPreviewTab must be persisted so tabs restore after IDE restart");
    }

    @Test
    void writeExternalRoundTripsFilePath() throws Exception {
        MarkdownPreviewTab tab = new MarkdownPreviewTab();
        tab.setFilePathForTest("/some/doc.md");

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(buf)) {
            tab.writeExternal(oos);
        }

        MarkdownPreviewTab restored = new MarkdownPreviewTab();
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(buf.toByteArray()))) {
            restored.readExternal(ois);
        }

        assertEquals("/some/doc.md", restored.savedFilePath,
                "savedFilePath must survive writeExternal/readExternal round-trip");
        assertEquals(0.0, restored.savedScrollRatio, 1e-9,
                "savedScrollRatio must be 0.0 when no scrollPane is present");
    }

    // --- Anchor history tests ---

    @Test
    void canGoBackTrueAfterNavigateToAnchor() {
        MarkdownPreviewTab tab = new MarkdownPreviewTab();
        tab.historyPaths.add("/file.md");
        tab.historyFos.add(null);
        tab.historyAnchors.add(null);
        tab.historyIndex = 0;
        tab.setFilePathForTest("/file.md");
        tab.setPaneForTest(new JEditorPane());

        tab.navigateToAnchor("section");

        assertTrue(tab.canGoBack(), "canGoBack() should be true after navigateToAnchor()");
    }

    @Test
    void canGoBackFalseAfterNavigateToAnchorThenBack() {
        MarkdownPreviewTab tab = new MarkdownPreviewTab();
        tab.historyPaths.add("/file.md");
        tab.historyFos.add(null);
        tab.historyAnchors.add(null);
        tab.historyIndex = 0;
        tab.setFilePathForTest("/file.md");
        tab.setPaneForTest(new JEditorPane());

        tab.navigateToAnchor("section");
        tab.navigateBack();

        assertFalse(tab.canGoBack(), "canGoBack() should be false after navigating back to initial entry");
    }

    @Test
    void forwardTruncationClearsAnchorHistory() {
        MarkdownPreviewTab tab = new MarkdownPreviewTab();
        tab.historyPaths.add("/file.md");
        tab.historyFos.add(null);
        tab.historyAnchors.add(null);
        tab.historyPaths.add("/file.md");
        tab.historyFos.add(null);
        tab.historyAnchors.add("old-anchor");
        tab.historyIndex = 0;
        tab.setFilePathForTest("/file.md");
        tab.setPaneForTest(new JEditorPane());

        // Navigate to new anchor (should truncate forward entry)
        tab.navigateToAnchor("new-anchor");

        assertEquals(2, tab.historyAnchors.size(), "History should have 2 entries after truncation and new push");
        assertNull(tab.historyAnchors.get(0), "First entry anchor should be null");
        assertEquals("new-anchor", tab.historyAnchors.get(1), "Second entry should be new anchor");
    }

    private static List<String> menuItemLabels(JPopupMenu menu) {
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < menu.getComponentCount(); i++) {
            Component c = menu.getComponent(i);
            if (c instanceof JMenuItem) labels.add(((JMenuItem) c).getText());
        }
        return labels;
    }
}
