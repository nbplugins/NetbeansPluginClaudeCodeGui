package io.github.nbclaudecodegui.ui;

import io.github.nbclaudecodegui.ui.common.MarkdownRenderer;
import java.awt.BorderLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import org.openide.awt.HtmlBrowser;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import java.io.File;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JEditorPane;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.event.HyperlinkEvent;
import javax.swing.event.HyperlinkListener;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import org.openide.filesystems.FileChangeAdapter;
import org.openide.filesystems.FileChangeListener;
import org.openide.filesystems.FileEvent;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.windows.TopComponent;

/**
 * TopComponent that shows a rendered markdown preview.
 * Can display a before/after side-by-side view (from diff) or a single live-updating file view.
 *
 * <p>The {@link #openLive} factory deduplicates tabs by file path and updates existing
 * tabs when called again for the same path.  File-system changes are tracked via
 * {@link FileChangeListener} when a {@link FileObject} is provided.
 *
 * <p>Hyperlink navigation is supported: clicking a link opens it in the same tab;
 * right-clicking shows a context menu with same-tab, new-tab, and browser options,
 * plus back/forward navigation.
 */
@TopComponent.Description(
    preferredID = "MarkdownPreviewTopComponent",
    persistenceType = TopComponent.PERSISTENCE_ALWAYS
)
public class MarkdownPreviewTab extends TopComponent {

    /**
     * Open live-preview tabs keyed by absolute file path.
     * WeakHashMap so that closed / GC'd TCs are eventually removed automatically.
     */
    private static final Map<String, MarkdownPreviewTab> OPEN_TABS =
            Collections.synchronizedMap(new HashMap<>());

    /** The editor pane that renders HTML-converted markdown. */
    JEditorPane pane;

    /** The scroll pane wrapping {@link #pane}, used to preserve scroll position on updates. */
    private JScrollPane scrollPane;

    /** Listener attached to the backing FileObject, if any. */
    private FileChangeListener fileListener;

    /** The FileObject being monitored (may be null for diff-pinned previews). */
    private FileObject fileObject;

    /** Absolute path key used to identify this tab in {@link #OPEN_TABS}. */
    String filePath;

    // Navigation history
    final List<String> historyPaths = new ArrayList<>();
    final List<FileObject> historyFos = new ArrayList<>();
    int historyIndex = -1;

    /** The href under the mouse at last ENTERED event; null when not hovering a link. */
    String currentHoverHref = null;

    /** Snapshot of the href captured when the context menu opens; read by action listeners. */
    String menuHref = null;

    /** File path saved by writeExternal; applied in componentOpened() after IDE restart. */
    String savedFilePath = null;

    /** Scroll ratio saved by writeExternal; applied in componentOpened() after IDE restart. */
    double savedScrollRatio = 0.0;

    /** Creates a new empty tab instance; content and title are configured by factory methods. */
    MarkdownPreviewTab() {
    }

    /**
     * Opens (or re-activates) a live-updating markdown preview tab for {@code filePath}.
     *
     * <ul>
     *   <li>If an open tab already exists for {@code filePath} it is updated with
     *       {@code content} and brought to the front — no new tab is created.</li>
     *   <li>Otherwise a new tab is created.  If {@code fo} is non-null a
     *       {@link FileChangeListener} is attached so the tab refreshes automatically
     *       when the file changes on disk.</li>
     * </ul>
     *
     * @param filePath absolute path of the file (used as dedup key and tab title)
     * @param content  initial markdown text to render
     * @param fo       optional {@link FileObject} to watch for file-system changes
     */
    public static void openLive(String filePath, String content, FileObject fo) {
        MarkdownPreviewTab existing = OPEN_TABS.get(filePath);
        if (existing != null && existing.isOpened()) {
            existing.updateContent(content);
            if (existing.fileObject == null && fo != null) {
                existing.fileObject = fo;
                existing.fileListener = makeFileListener(existing, fo);
                fo.addFileChangeListener(existing.fileListener);
            }
            existing.requestActive();
            return;
        }

        MarkdownPreviewTab tab = new MarkdownPreviewTab();
        tab.filePath = filePath;
        tab.fileObject = fo;

        String html = MarkdownRenderer.toHtml(content != null ? content : "");
        tab.pane = MarkdownRenderer.createOutputPane(html, new java.io.File(filePath).getParent());

        String name = new java.io.File(filePath).getName();
        tab.setDisplayName("Preview: " + name);
        tab.setLayout(new BorderLayout());
        tab.scrollPane = new JScrollPane(tab.pane);
        tab.add(tab.scrollPane, BorderLayout.CENTER);

        // Attach hyperlink listener and context menu
        tab.attachHyperlinkListener();
        tab.bindRefreshKey(tab.pane);
        tab.pane.setComponentPopupMenu(tab.buildContextMenu());

        if (fo != null) {
            tab.fileListener = makeFileListener(tab, fo);
            fo.addFileChangeListener(tab.fileListener);
        }

        // Push initial entry into history
        tab.historyPaths.add(filePath);
        tab.historyFos.add(fo);
        tab.historyIndex = 0;

        OPEN_TABS.put(filePath, tab);
        tab.open();
        tab.requestActive();
    }

    /**
     * Updates the rendered content of this preview tab.
     * Schedules the update on the EDT if not already there.
     *
     * @param markdown markdown text to render
     */
    public void updateContent(String markdown) {
        String html = MarkdownRenderer.toHtml(markdown != null ? markdown : "");
        SwingUtilities.invokeLater(() -> {
            if (pane == null) return;
            if (scrollPane == null) {
                pane.setText(html);
                return;
            }
            JViewport viewport = scrollPane.getViewport();
            Dimension viewSize = viewport.getViewSize();
            double relY = viewSize.height > 0
                    ? (double) viewport.getViewPosition().y / viewSize.height : 0.0;
            pane.setText(html);
            SwingUtilities.invokeLater(() -> {
                Dimension newSize = viewport.getViewSize();
                int newY = (int) (relY * newSize.height);
                viewport.setViewPosition(new Point(0, newY));
            });
        });
    }

    @Override
    public void componentClosed() {
        super.componentClosed();
        if (fileObject != null && fileListener != null) {
            fileObject.removeFileChangeListener(fileListener);
        }
        if (filePath != null) {
            OPEN_TABS.remove(filePath);
        }
    }

    /**
     * Creates a {@link FileChangeListener} that refreshes {@code tab} whenever
     * {@code fo} changes on disk.  Handles both in-place writes ({@code fileChanged})
     * and atomic-replace writes ({@code fileDataCreated}) and calls
     * {@link FileObject#refresh} before reading to avoid stale VFS cache.
     */
    private static FileChangeListener makeFileListener(MarkdownPreviewTab tab, FileObject fo) {
        return new FileChangeAdapter() {
            @Override
            public void fileChanged(FileEvent fe) {
                reload();
            }
            @Override
            public void fileDataCreated(FileEvent fe) {
                reload();
            }
            private void reload() {
                try {
                    fo.refresh(false);
                    tab.updateContent(fo.asText());
                } catch (IOException e) {
                    // silently ignore
                }
            }
        };
    }

    /**
     * Opens a single-pane markdown preview for a file.
     * Uses {@link #openLive} so the tab deduplicates and refreshes on file changes.
     *
     * @param fo the file object to preview
     */
    public static void openFile(FileObject fo) {
        try {
            openLive(fo.getPath(), fo.asText(), fo);
        } catch (Exception e) {
            // silently ignore
        }
    }

    /**
     * Opens a before/after side-by-side markdown preview for a diff.
     *
     * @param filePath the file path (used for tab title)
     * @param before   the original markdown content
     * @param after    the modified markdown content
     */
    public static void open(String filePath, String before, String after) {
        MarkdownPreviewTab tab = new MarkdownPreviewTab();
        String name = filePath.contains("/")
                ? filePath.substring(filePath.lastIndexOf('/') + 1) : filePath;
        tab.setDisplayName("Preview: " + name);
        tab.setLayout(new BorderLayout());
        tab.add(new MarkdownDiffPanel(before, after), BorderLayout.CENTER);
        tab.open();
        tab.requestActive();
    }

    // -------------------------------------------------------------------------
    // Navigation
    // -------------------------------------------------------------------------

    /**
     * Navigates to a new file in this tab.
     *
     * @param newPath      absolute path of the new file
     * @param newFo        FileObject of the new file
     * @param addToHistory true when the user clicked a link; false for back/forward
     */
    void navigateTo(String newPath, FileObject newFo, boolean addToHistory) {
        // Detach old file listener
        if (fileObject != null && fileListener != null) {
            fileObject.removeFileChangeListener(fileListener);
            fileListener = null;
        }

        if (addToHistory) {
            // Truncate forward entries
            while (historyPaths.size() > historyIndex + 1) {
                historyPaths.remove(historyPaths.size() - 1);
                historyFos.remove(historyFos.size() - 1);
            }
            historyPaths.add(newPath);
            historyFos.add(newFo);
            historyIndex++;
        }

        // Remove old path from OPEN_TABS and register new path
        if (filePath != null) {
            OPEN_TABS.remove(filePath);
        }
        this.filePath = newPath;
        this.fileObject = newFo;
        OPEN_TABS.put(newPath, this);

        setDisplayName("Preview: " + new File(newPath).getName());

        try {
            if (newFo != null) {
                updateContent(newFo.asText());
                fileListener = makeFileListener(this, newFo);
                newFo.addFileChangeListener(fileListener);
            }
        } catch (IOException e) {
            // silently ignore
        }
    }

    boolean canGoBack() {
        return historyIndex > 0;
    }

    boolean canGoForward() {
        return historyIndex < historyPaths.size() - 1;
    }

    void navigateBack() {
        if (canGoBack()) {
            historyIndex--;
            navigateTo(historyPaths.get(historyIndex), historyFos.get(historyIndex), false);
        }
    }

    void navigateForward() {
        if (canGoForward()) {
            historyIndex++;
            navigateTo(historyPaths.get(historyIndex), historyFos.get(historyIndex), false);
        }
    }

    /**
     * Resolves an href relative to the current file.
     *
     * @param href the link target
     * @return the resolved File if it exists and has a .md/.markdown extension; null otherwise
     */
    File resolveLink(String href) {
        if (href == null) return null;
        if (href.startsWith("http://") || href.startsWith("https://")) return null;
        File parent = filePath != null ? new File(filePath).getParentFile() : null;
        if (parent == null) return null;
        File resolved = new File(parent, href);
        if (!resolved.exists()) return null;
        String lower = resolved.getName().toLowerCase();
        if (!lower.endsWith(".md") && !lower.endsWith(".markdown")) return null;
        return resolved;
    }

    void openLinkInSameTab(String href) {
        File resolved = resolveLink(href);
        if (resolved != null) {
            FileObject fo = FileUtil.toFileObject(resolved);
            navigateTo(resolved.getAbsolutePath(), fo, true);
        } else if (href != null && (href.startsWith("http://") || href.startsWith("https://"))) {
            openInBrowser(href);
        }
    }

    void openLinkInNewTab(String href) {
        File resolved = resolveLink(href);
        if (resolved != null) {
            FileObject fo = FileUtil.toFileObject(resolved);
            try {
                openLive(resolved.getAbsolutePath(), fo != null ? fo.asText() : "", fo);
            } catch (IOException e) {
                // silently ignore
            }
        } else if (href != null && (href.startsWith("http://") || href.startsWith("https://"))) {
            openInBrowser(href);
        }
    }

    private void copyUrlToClipboard(String href) {
        if (href == null) return;
        File resolved = resolveLink(href);
        String text = resolved != null ? resolved.getAbsolutePath() : href;
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(text), null);
    }

    private static void openInBrowser(String href) {
        try {
            HtmlBrowser.URLDisplayer.getDefault().showURL(new URI(href).toURL());
        } catch (Exception e) {
            // silently ignore
        }
    }

    private void attachHyperlinkListener() {
        pane.addHyperlinkListener((HyperlinkEvent e) -> {
            if (e.getEventType() == HyperlinkEvent.EventType.ENTERED) {
                currentHoverHref = e.getDescription();
            } else if (e.getEventType() == HyperlinkEvent.EventType.EXITED) {
                currentHoverHref = null;
            } else if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                openLinkInSameTab(e.getDescription());
            }
        });
    }

    JPopupMenu buildContextMenu() {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem openSame = new JMenuItem("Open in Same Tab");
        JMenuItem openNew  = new JMenuItem("Open in New Tab");
        JMenuItem openBrowser = new JMenuItem("Open in Browser");
        JMenuItem copyUrl = new JMenuItem("Copy URL");
        JPopupMenu.Separator linkSep = new JPopupMenu.Separator();

        JMenuItem back    = new JMenuItem("\u2190 Back");
        JMenuItem forward = new JMenuItem("\u2192 Forward");
        back.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, InputEvent.ALT_DOWN_MASK));
        forward.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, InputEvent.ALT_DOWN_MASK));

        JMenuItem refresh = new JMenuItem("Refresh");
        refresh.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0));
        refresh.addActionListener(e -> doRefresh());

        JMenuItem selectAll = new JMenuItem("Select All");
        selectAll.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK));
        selectAll.addActionListener(e -> { if (pane != null) pane.selectAll(); });

        JMenuItem copy = new JMenuItem("Copy");
        copy.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK));
        copy.addActionListener(e -> { if (pane != null) pane.copy(); });

        // Listeners registered once at build time; menuHref is set in popupMenuWillBecomeVisible.
        // This avoids the Swing event-order bug where popupMenuWillBecomeInvisible fires
        // before actionPerformed, causing dynamically-added listeners to be removed too early.
        openSame.addActionListener(e -> openLinkInSameTab(menuHref));
        openNew.addActionListener(e -> openLinkInNewTab(menuHref));
        openBrowser.addActionListener(e -> openInBrowser(menuHref));
        copyUrl.addActionListener(e -> copyUrlToClipboard(menuHref));

        menu.add(openSame);
        menu.add(openNew);
        menu.add(openBrowser);
        menu.add(copyUrl);
        menu.add(linkSep);
        menu.add(back);
        menu.add(forward);
        menu.add(refresh);
        menu.addSeparator();
        menu.add(selectAll);
        menu.add(copy);

        menu.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                menuHref = currentHoverHref;
                boolean hasLink    = menuHref != null;
                boolean isExternal = hasLink && (menuHref.startsWith("http://") || menuHref.startsWith("https://"));
                boolean isLocalMd  = hasLink && !isExternal && resolveLink(menuHref) != null;
                openSame.setVisible(hasLink);
                openNew.setVisible(hasLink);
                openBrowser.setVisible(hasLink);
                copyUrl.setVisible(hasLink);
                linkSep.setVisible(hasLink);
                openSame.setEnabled(isLocalMd);
                openNew.setEnabled(isLocalMd);
                openBrowser.setEnabled(isExternal);
                copyUrl.setEnabled(hasLink);
                back.setEnabled(canGoBack());
                forward.setEnabled(canGoForward());
                refresh.setVisible(fileObject != null);
                copy.setEnabled(pane != null
                        && pane.getSelectionStart() != pane.getSelectionEnd());
            }
            @Override public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {}
            @Override public void popupMenuCanceled(PopupMenuEvent e) {}
        });

        back.addActionListener(e -> navigateBack());
        forward.addActionListener(e -> navigateForward());

        return menu;
    }

    private void bindRefreshKey(JEditorPane p) {
        KeyStroke f5 = KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0);
        p.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(f5, "md-refresh");
        p.getActionMap().put("md-refresh", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { doRefresh(); }
        });
    }

    void doRefresh() {
        if (fileObject == null) return;
        try {
            fileObject.refresh(false);
            updateContent(fileObject.asText());
        } catch (IOException e) {
            // silently ignore
        }
    }

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
        super.writeExternal(out);
        out.writeUTF(filePath != null ? filePath : "");
        double ratio = 0.0;
        if (scrollPane != null) {
            JViewport vp = scrollPane.getViewport();
            Dimension vs = vp.getViewSize();
            if (vs.height > 0) ratio = (double) vp.getViewPosition().y / vs.height;
        }
        out.writeDouble(ratio);
    }

    @Override
    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        super.readExternal(in);
        savedFilePath = in.readUTF();
        try {
            savedScrollRatio = in.readDouble();
        } catch (java.io.EOFException ignored) {
            savedScrollRatio = 0.0;
        }
    }

    @Override
    protected void componentOpened() {
        super.componentOpened();
        if (savedFilePath == null || savedFilePath.isBlank()) return;
        String path = savedFilePath;
        double ratio = savedScrollRatio;
        savedFilePath = null;
        savedScrollRatio = 0.0;

        File f = new File(path);
        if (!f.exists()) return;

        FileObject fo = FileUtil.toFileObject(f);
        if (pane == null) {
            try {
                String content = fo != null ? fo.asText() : "";
                pane = MarkdownRenderer.createOutputPane(MarkdownRenderer.toHtml(content));
            } catch (IOException e) {
                return;
            }
            setLayout(new BorderLayout());
            scrollPane = new JScrollPane(pane);
            add(scrollPane, BorderLayout.CENTER);
            attachHyperlinkListener();
            bindRefreshKey(pane);
            pane.setComponentPopupMenu(buildContextMenu());
        }

        filePath = path;
        fileObject = fo;
        setDisplayName("Preview: " + f.getName());

        if (historyPaths.isEmpty()) {
            historyPaths.add(path);
            historyFos.add(fo);
            historyIndex = 0;
        }

        OPEN_TABS.put(path, this);

        if (fo != null) {
            fileListener = makeFileListener(this, fo);
            fo.addFileChangeListener(fileListener);
        }

        if (ratio > 0.0) {
            SwingUtilities.invokeLater(() -> SwingUtilities.invokeLater(() -> {
                if (scrollPane == null) return;
                JViewport vp = scrollPane.getViewport();
                int y = (int) (ratio * vp.getViewSize().height);
                vp.setViewPosition(new Point(0, y));
            }));
        }
    }

    // --- Test hooks (package-private) ---

    static void clearOpenTabsForTest() {
        OPEN_TABS.clear();
    }

    static Map<String, MarkdownPreviewTab> getOpenTabsForTest() {
        return OPEN_TABS;
    }

    void setPaneForTest(JEditorPane testPane) {
        this.pane = testPane;
    }

    void setFilePathForTest(String path) {
        this.filePath = path;
    }

    /** Calls the componentClosed logic without invoking the NB TC lifecycle. */
    void componentClosedForTest() {
        if (fileObject != null && fileListener != null) {
            fileObject.removeFileChangeListener(fileListener);
        }
        if (filePath != null) {
            OPEN_TABS.remove(filePath);
        }
    }
}
