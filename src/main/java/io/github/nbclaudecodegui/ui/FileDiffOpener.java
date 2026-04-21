package io.github.nbclaudecodegui.ui;

import io.github.nbclaudecodegui.settings.ClaudeCodePreferences;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.Preferences;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JToolBar;
import javax.swing.SwingUtilities;
import org.netbeans.api.diff.Diff;
import org.netbeans.api.diff.DiffView;
import org.netbeans.api.diff.Difference;
import org.netbeans.api.diff.StreamSource;
import org.openide.util.Lookup;
import org.openide.util.NbPreferences;
import org.openide.windows.TopComponent;
import org.openide.windows.WindowManager;

/**
 * Opens a NetBeans editor tab showing a diff of two file versions together with
 * a {@link FileDiffPermissionPanel} at the bottom.
 *
 * <p>Used by both the PreToolUse HTTP hook handler and the {@code permission_prompt}
 * MCP tool so that both paths share identical UI.
 */
public final class FileDiffOpener {

    private static final Logger LOGGER = Logger.getLogger(FileDiffOpener.class.getName());

    /** NbPreferences key for the vertical split-pane divider position (pixels). */
    private static final String PREF_VERT_DIVIDER = "mdVertSplitDivider";

    private FileDiffOpener() {}

    /**
     * Returns {@code true} if {@code filePath} is located inside {@code dirPath}
     * (or equals it).
     *
     * @param filePath path of the file to check
     * @param dirPath  path of the directory to check against
     * @return {@code true} if the file is inside the directory or equals it
     */
    public static boolean isFileUnderDirectory(String filePath, String dirPath) {
        if (filePath == null || dirPath == null) return false;
        String absFile = new java.io.File(filePath).getAbsolutePath();
        String absDir  = new java.io.File(dirPath).getAbsolutePath();
        return absFile.equals(absDir)
                || absFile.startsWith(absDir + java.io.File.separator);
    }

    /**
     * Opens the diff tab on the EDT.  All callbacks are invoked on the EDT.
     *
     * <p>After any callback (including {@code onClose}) the Claude session tab
     * whose working directory is an ancestor of {@code filePath} is activated
     * automatically.
     *
     * @param filePath     path of the file being changed (used for tab title and session lookup)
     * @param before       file content before the change
     * @param after        file content after the change
     * @param tabName      display name of the diff tab
     * @param confirmedDir if non-null, the session's working directory (hook case); the warning
     *                     is shown when the file is outside this directory. If null (MCP case),
     *                     the warning is shown when the file is outside all open sessions.
     * @param onAccept     called when the user clicks Accept
     * @param onDecline    called with the (possibly empty) reason when the user clicks Decline
     * @param onCancel     called when the user clicks Cancel (caller should also send Ctrl+C)
     * @param onClose      called when the tab is closed via the × button without a decision
     */
    public static void open(
            String filePath, String before, String after, String tabName,
            String confirmedDir,
            Runnable onAccept,
            Consumer<String> onDecline,
            Runnable onCancel,
            Runnable onClose) {

        SwingUtilities.invokeLater(() -> {
            // Determine if the file is outside the relevant project directory.
            // confirmedDir != null (hook): check against that specific directory.
            // confirmedDir == null (MCP): check against all open sessions.
            final boolean outsideProject;
            final String outsideWarning;
            if (confirmedDir != null) {
                outsideProject = !isFileUnderDirectory(filePath, confirmedDir);
                outsideWarning = "⚠ This file is outside the current project";
            } else {
                boolean insideAny = false;
                for (TopComponent tc : WindowManager.getDefault().getRegistry().getOpened()) {
                    if (tc instanceof ClaudeSessionTab stc) {
                        java.io.File dir = stc.getWorkingDirectory();
                        if (dir != null && isFileUnderDirectory(filePath, dir.getAbsolutePath())) {
                            insideAny = true;
                            break;
                        }
                    }
                }
                outsideProject = !insideAny;
                outsideWarning = "⚠ This file is outside any open project";
            }

            Diff diffService = Lookup.getDefault().lookup(Diff.class);
            if (diffService == null) {
                LOGGER.warning("Diff service not available; falling back to onClose for: " + tabName);
                onClose.run();
                activateSessionForFile(filePath);
                return;
            }

            String fileName = new java.io.File(filePath).getName();
            StreamSource oldSource = textSource(fileName + " (before)", filePath, before);
            StreamSource newSource = textSource(fileName + " (after)",  filePath, after);

            final DiffView diffView;
            try {
                diffView = diffService.createDiff(oldSource, newSource);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error creating diff view for: " + tabName, e);
                onClose.run();
                activateSessionForFile(filePath);
                return;
            }

            if (diffView == null) {
                LOGGER.warning("createDiff returned null; falling back to onClose for: " + tabName);
                onClose.run();
                activateSessionForFile(filePath);
                return;
            }

            // Flag set to true once a button is clicked — prevents onClose from
            // firing again when the diff is closed.
            AtomicBoolean decided = new AtomicBoolean(false);

            // Look up the session whose working directory matches the file being edited.
            // confirmedDir != null (hook case): use exact directory match.
            // confirmedDir == null (MCP case): fall back to isFileUnderDirectory.
            final ClaudeSessionTab sessionTab = WindowManager.getDefault().getRegistry().getOpened().stream()
                    .map(tc -> {
                        if (!(tc instanceof ClaudeSessionTab s)) return null;
                        File dir = s.getWorkingDirectory();
                        if (dir == null) return null;
                        String abs = dir.getAbsolutePath();
                        return (confirmedDir != null ? abs.equals(confirmedDir)
                                : isFileUnderDirectory(filePath, abs)) ? s : null;
                    })
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);

            String workingDir = sessionTab != null && sessionTab.getWorkingDirectory() != null
                    ? sessionTab.getWorkingDirectory().getAbsolutePath()
                    : null;

            // Forward reference for the close action (resolved below for each branch).
            Runnable[] closeDiffRef = { null };

            FileDiffPermissionPanel permPanel = new FileDiffPermissionPanel(
                () -> {
                    decided.set(true);
                    onAccept.run();
                    closeDiffRef[0].run();
                    activateSessionForFile(filePath);
                },
                reason -> {
                    decided.set(true);
                    onDecline.accept(reason);
                    closeDiffRef[0].run();
                    activateSessionForFile(filePath);
                },
                sessionTab == null ? null : () -> {
                    sessionTab.setEditMode("acceptEdits");
                    decided.set(true);
                    onAccept.run();
                    closeDiffRef[0].run();
                    activateSessionForFile(filePath);
                },
                sessionTab == null ? null : () -> {
                    decided.set(true);
                    onCancel.run();
                    closeDiffRef[0].run();
                    activateSessionForFile(filePath);
                },
                workingDir
            );

            boolean isMdFile = filePath.toLowerCase().endsWith(".md")
                    || filePath.toLowerCase().endsWith(".markdown");
            LOGGER.fine("FileDiffOpener.open: isMdFile=" + isMdFile + " filePath=" + filePath);

            // Keep the DiffView's own navigation toolbar (prev/next difference buttons)
            // Null means this DiffView provider offers no toolbar — don't add an empty one.
            JToolBar toolbar = diffView.getToolBar();

            // One-element arrays for mutable references captured by lambdas
            final MarkdownDiffPanel[] mdPanelRef = { null };
            final javax.swing.JSplitPane[] mdSplitRef = { null };
            // Persists the vertical divider position across hide/show within this session
            final int[] savedVertDivider = { -1 };

            // Build the wrapper panel that holds either the raw diff or the MD split
            java.awt.Component initialContent = diffView.getComponent();
            if (isMdFile && ClaudeCodePreferences.isMdPreviewInDiff()) {
                MarkdownDiffPanel mdPanel = new MarkdownDiffPanel(before, after);
                mdPanel.attachRawDiffSync(diffView.getComponent());
                javax.swing.JSplitPane mdSplit = new javax.swing.JSplitPane(
                        javax.swing.JSplitPane.VERTICAL_SPLIT, mdPanel, diffView.getComponent());
                mdSplit.setResizeWeight(0.5);
                mdPanelRef[0] = mdPanel;
                mdSplitRef[0] = mdSplit;
                initialContent = mdSplit;
                // Restore saved divider position after initial layout
                int savedDiv = loadDividerPref();
                SwingUtilities.invokeLater(() -> {
                    if (savedDiv >= 0) {
                        mdSplit.setDividerLocation(savedDiv);
                    }
                    // else let resizeWeight(0.5) handle default
                });
                // Auto-save on user drag
                mdSplit.addPropertyChangeListener(javax.swing.JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
                    int loc = mdSplit.getDividerLocation();
                    int h   = mdSplit.getHeight();
                    if (h > 0 && loc > 0 && loc < h) {
                        savedVertDivider[0] = loc;
                        saveDividerPref(loc);
                    }
                });
            }

            final javax.swing.JPanel wrapperPanel =
                    new javax.swing.JPanel(new java.awt.BorderLayout());
            wrapperPanel.add(initialContent, java.awt.BorderLayout.CENTER);

            // "Preview Markdown" checkbox item (only for .md files)
            final JCheckBoxMenuItem[] previewItemRef = { null };
            // Idempotent cleanup: removes the AWTEventListener once, then becomes a no-op.
            final Runnable[] removeAwtListener = { null };
            removeAwtListener[0] = () -> {};
            if (isMdFile) {
                JCheckBoxMenuItem previewItem =
                        new JCheckBoxMenuItem("Preview Markdown");
                previewItem.setSelected(ClaudeCodePreferences.isMdPreviewInDiff());
                previewItemRef[0] = previewItem;

                previewItem.addActionListener(e ->
                    toggleMdPreview(previewItem.isSelected(), before, after,
                            diffView, wrapperPanel, mdPanelRef, mdSplitRef,
                            filePath, previewItemRef, savedVertDivider));

                java.awt.Component diffComp = diffView.getComponent();
                // We need to inject "Preview Markdown" into the right-click popup that NetBeans'
                // diff component shows natively. PopupMenuListener cannot be used because
                // JPopupMenu.COMPONENT_SHOWN never fires for this component. Instead we listen
                // for the popup-trigger mouse event via AWTEventListener (which fires before the
                // popup is built) and schedule the injection via two nested invokeLater calls:
                //   - first invokeLater: runs after the current event is fully dispatched (popup is shown)
                //   - second invokeLater: runs one more EDT cycle later, by which time
                //     MenuSelectionManager has registered the popup in its selected path
                //     (getSelectedPath() returns non-empty)
                // A single invokeLater is not enough — getSelectedPath() still returns [] on the
                // first cycle.
                java.awt.event.AWTEventListener awtL = awtEvent -> {
                    if (!(awtEvent instanceof java.awt.event.MouseEvent me) || !me.isPopupTrigger()) return;
                    java.awt.Component src = me.getComponent();
                    if (src == null || !SwingUtilities.isDescendingFrom(src, diffComp)) return;
                    SwingUtilities.invokeLater(() -> SwingUtilities.invokeLater(() -> {
                        for (javax.swing.MenuElement el :
                                javax.swing.MenuSelectionManager.defaultManager().getSelectedPath()) {
                            if (el instanceof JPopupMenu menu) {
                                addPreviewItemToPopup(menu, previewItem);
                                break;
                            }
                        }
                    }));
                };
                java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(
                        awtL, java.awt.AWTEvent.MOUSE_EVENT_MASK);
                removeAwtListener[0] = () -> {
                    removeAwtListener[0] = () -> {};
                    java.awt.Toolkit.getDefaultToolkit().removeAWTEventListener(awtL);
                };
            }

            // Wire MarkdownDiffPanel callbacks
            if (isMdFile && mdPanelRef[0] != null) {
                mdPanelRef[0].setOnHide(() -> {
                    if (previewItemRef[0] != null) previewItemRef[0].setSelected(false);
                    toggleMdPreview(false, before, after,
                            diffView, wrapperPanel, mdPanelRef, mdSplitRef,
                            filePath, previewItemRef, savedVertDivider);
                });
                mdPanelRef[0].setOnPinPreview(() ->
                        MarkdownPreviewTab.openLive(filePath, after,
                                org.openide.filesystems.FileUtil.toFileObject(new java.io.File(filePath))));
            }

            // --- SESSION branch ---
            if (!ClaudeCodePreferences.isOpenDiffInSeparateTab()
                    && sessionTab != null) {
                closeDiffRef[0] = sessionTab::hideEmbeddedDiff;
                closeDiffRef[0] = withCleanup(closeDiffRef[0], removeAwtListener);
                boolean shown = sessionTab.showEmbeddedDiff(
                        outsideProject, outsideWarning,
                        toolbar, wrapperPanel, permPanel,
                        () -> { removeAwtListener[0].run(); if (!decided.get()) { onClose.run(); activateSessionForFile(filePath); } });
                if (shown) return;
            }

            // --- TOPLEVEL branch ---
            TopComponent diffTC = new TopComponent() {
                @Override
                public void componentClosed() {
                    super.componentClosed();
                    removeAwtListener[0].run();
                    if (!decided.get()) {
                        onClose.run();
                        activateSessionForFile(filePath);
                    }
                }
            };
            closeDiffRef[0] = diffTC::close;
            diffTC.setDisplayName(tabName);
            diffTC.setLayout(new java.awt.BorderLayout());

            if (toolbar != null) {
                toolbar.setFloatable(false);
                diffTC.add(toolbar, java.awt.BorderLayout.NORTH);
            }
            diffTC.add(wrapperPanel, java.awt.BorderLayout.CENTER);
            if (outsideProject) {
                permPanel.showWarning(outsideWarning);
            }
            diffTC.add(permPanel, java.awt.BorderLayout.SOUTH);

            io.github.nbclaudecodegui.ui.common.UiUtils.dockAndOpen(
                    diffTC, ClaudeCodePreferences.getFileDiffDockMode());
            diffTC.requestActive();
            SwingUtilities.invokeLater(permPanel::requestAcceptFocus);
        });
    }

    /**
     * Adds a separator and a clone of {@code previewItem} to {@code menu},
     * unless a "Preview Markdown" item is already present (guard against duplicates).
     */
    static void addPreviewItemToPopup(JPopupMenu menu, JCheckBoxMenuItem previewItem) {
        for (java.awt.Component c : menu.getComponents()) {
            if (c instanceof JCheckBoxMenuItem cb
                    && "Preview Markdown".equals(cb.getText())) return;
        }
        menu.addSeparator();
        menu.add(cloneCheckItem(previewItem));
        LOGGER.fine("addPreviewItemToPopup: added to " + menu);
    }

    /**
     * Returns a {@link Runnable} that first runs {@code cleanupRef[0]} (idempotent),
     * then runs {@code action}.  Used to attach AWTEventListener cleanup to
     * {@code closeDiffRef[0]} without duplicating it in every decision callback.
     */
    private static Runnable withCleanup(Runnable action, Runnable[] cleanupRef) {
        return () -> { cleanupRef[0].run(); action.run(); };
    }

    /**
     * Creates a {@link JCheckBoxMenuItem} that mirrors the state of {@code src}.
     * <ul>
     *   <li>Clicking the clone sets the clone's selection, copies it to {@code src},
     *       and fires {@code src}'s first {@code ActionListener}.</li>
     *   <li>Programmatic changes to {@code src}'s selected state are reflected in the
     *       clone via a {@code PropertyChangeListener} on the {@code "selected"} property.</li>
     * </ul>
     *
     * @param src the authoritative checkbox item
     * @return a new linked clone
     */
    static JCheckBoxMenuItem cloneCheckItem(JCheckBoxMenuItem src) {
        JCheckBoxMenuItem copy = new JCheckBoxMenuItem(src.getText(), src.isSelected());
        copy.addActionListener(e -> {
            src.setSelected(copy.isSelected());
            if (src.getActionListeners().length > 0) {
                src.getActionListeners()[0].actionPerformed(e);
            }
        });
        // Track programmatic selection changes via the button model's ChangeListener
        src.getModel().addChangeListener(e -> copy.setSelected(src.isSelected()));
        return copy;
    }

    /**
     * Swaps the content of {@code wrapperPanel} to show or hide the markdown diff preview.
     * When {@code show} is {@code true} and no {@link MarkdownDiffPanel} exists yet,
     * one is created lazily and its "Hide" / "Pin Preview" callbacks are wired up.
     *
     * @param show          {@code true} to show the markdown preview, {@code false} to hide it
     * @param before        original file content (used when lazily creating the panel)
     * @param after         modified file content
     * @param diffView      the underlying NetBeans diff view
     * @param wrapperPanel  the single-layer wrapper whose content is swapped
     * @param mdPanelRef    one-element array holding the (possibly null) {@link MarkdownDiffPanel}
     * @param mdSplitRef    one-element array holding the (possibly null) vertical {@link javax.swing.JSplitPane}
     * @param filePath      absolute path of the file being diffed (for the pin-preview action)
     * @param previewItemRef one-element array holding the "Preview Markdown" checkbox item
     */
    private static void toggleMdPreview(
            boolean show, String before, String after,
            DiffView diffView, javax.swing.JPanel wrapperPanel,
            MarkdownDiffPanel[] mdPanelRef, javax.swing.JSplitPane[] mdSplitRef,
            String filePath, JCheckBoxMenuItem[] previewItemRef,
            int[] savedVertDivider) {

        LOGGER.fine("toggleMdPreview show=" + show
                + " savedVertDivider=" + savedVertDivider[0]
                + " mdPanelRef!=null=" + (mdPanelRef[0] != null));
        if (show) {
            if (mdPanelRef[0] == null) {
                MarkdownDiffPanel mdPanel = new MarkdownDiffPanel(before, after);
                mdPanel.attachRawDiffSync(diffView.getComponent());
                javax.swing.JSplitPane mdSplit = new javax.swing.JSplitPane(
                        javax.swing.JSplitPane.VERTICAL_SPLIT,
                        mdPanel, diffView.getComponent());
                mdSplit.setResizeWeight(0.5);
                mdPanelRef[0] = mdPanel;
                mdSplitRef[0] = mdSplit;
                // Auto-save on user drag
                mdSplit.addPropertyChangeListener(javax.swing.JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
                    int loc = mdSplit.getDividerLocation();
                    int h   = mdSplit.getHeight();
                    if (h > 0 && loc > 0 && loc < h) {
                        savedVertDivider[0] = loc;
                        saveDividerPref(loc);
                    }
                });
                mdPanel.setOnHide(() -> {
                    if (previewItemRef[0] != null) previewItemRef[0].setSelected(false);
                    toggleMdPreview(false, before, after, diffView, wrapperPanel,
                            mdPanelRef, mdSplitRef, filePath, previewItemRef, savedVertDivider);
                });
                mdPanel.setOnPinPreview(() ->
                        MarkdownPreviewTab.openLive(filePath, after,
                                org.openide.filesystems.FileUtil.toFileObject(new java.io.File(filePath))));
            }
            // Re-attach diff view — it was reparented to wrapperPanel during hide
            LOGGER.fine("toggleMdPreview: re-attaching diffView to split bottom, splitH="
                    + mdSplitRef[0].getHeight() + " divider=" + mdSplitRef[0].getDividerLocation());
            mdSplitRef[0].setBottomComponent(diffView.getComponent());
            LOGGER.fine("toggleMdPreview: setBottomComponent done, splitH="
                    + mdSplitRef[0].getHeight() + " divider=" + mdSplitRef[0].getDividerLocation());
            wrapperPanel.removeAll();
            wrapperPanel.add(mdSplitRef[0], java.awt.BorderLayout.CENTER);
            // Restore divider position after layout
            int divToRestore = savedVertDivider[0];
            SwingUtilities.invokeLater(() -> restoreDivider(mdSplitRef[0], divToRestore));
        } else {
            // Save divider position before removing the split from view
            if (mdSplitRef[0] != null) {
                int loc = mdSplitRef[0].getDividerLocation();
                LOGGER.fine("toggleMdPreview hide: saving divider loc=" + loc
                        + " previous savedVertDivider=" + savedVertDivider[0]);
                savedVertDivider[0] = loc;
                saveDividerPref(loc);
                LOGGER.fine("toggleMdPreview hide: saved divider=" + savedVertDivider[0]);
            }
            wrapperPanel.removeAll();
            wrapperPanel.add(diffView.getComponent(), java.awt.BorderLayout.CENTER);
        }
        wrapperPanel.revalidate();
        wrapperPanel.repaint();
    }

    // --- Divider persistence helpers ---

    private static void saveDividerPref(int location) {
        try {
            NbPreferences.forModule(FileDiffOpener.class).putInt(PREF_VERT_DIVIDER, location);
        } catch (Exception ex) {
            LOGGER.fine("Could not save divider pref: " + ex.getMessage());
        }
    }

    private static int loadDividerPref() {
        try {
            return NbPreferences.forModule(FileDiffOpener.class).getInt(PREF_VERT_DIVIDER, -1);
        } catch (Exception ex) {
            LOGGER.fine("Could not load divider pref: " + ex.getMessage());
            return -1;
        }
    }

    private static void restoreDivider(javax.swing.JSplitPane split, int location) {
        LOGGER.fine("restoreDivider location=" + location + " splitH=" + split.getHeight());
        if (location >= 0) {
            LOGGER.fine("restoreDivider: setting divider to " + location);
            split.setDividerLocation(location);
        } else {
            int prefLoc = loadDividerPref();
            LOGGER.fine("restoreDivider: no in-memory location, prefLoc=" + prefLoc);
            if (prefLoc >= 0) {
                split.setDividerLocation(prefLoc);
            }
            // else leave at resizeWeight default
        }
    }

    // --- Test-accessible wrappers (package-private) ---

    /** Package-private test hook for {@link #cloneCheckItem}. */
    static JCheckBoxMenuItem cloneCheckItemForTest(JCheckBoxMenuItem src) {
        return cloneCheckItem(src);
    }

    /** Package-private test hook: saves split divider location to {@code savedVertDivider[0]},
     *  applying the same pre-layout guard used in the real PropertyChangeListener. */
    static void saveDividerForTest(javax.swing.JSplitPane split, int[] savedVertDivider) {
        int loc = split.getDividerLocation();
        int h   = split.getHeight();
        if (h > 0 && loc > 0 && loc < h) {
            savedVertDivider[0] = loc;
        }
    }

    /** Package-private test hook: restores split divider location from {@code savedVertDivider[0]}. */
    static void restoreDividerForTest(javax.swing.JSplitPane split, int[] savedVertDivider) {
        if (savedVertDivider[0] >= 0) {
            split.setDividerLocation(savedVertDivider[0]);
        }
    }

    /**
     * Activates the Claude Code session TC whose working directory is an
     * ancestor of {@code filePath}.  Must be called on the EDT.
     */
    static void activateSessionForFile(String filePath) {
        java.io.File file = new java.io.File(filePath);
        for (TopComponent tc : WindowManager.getDefault().getRegistry().getOpened()) {
            if (tc instanceof ClaudeSessionTab stc) {
                java.io.File dir = stc.getWorkingDirectory();
                if (dir != null && file.getAbsolutePath().startsWith(dir.getAbsolutePath())) {
                    stc.requestActive();
                    return;
                }
            }
        }
    }

    /**
     * Sends Ctrl+C (0x03) to the Claude PTY session associated with {@code filePath}.
     * Must be called on the EDT.
     *
     * @param filePath absolute path of the file whose session should be interrupted
     */
    public static void cancelCurrentPromptForFile(String filePath) {
        java.io.File file = new java.io.File(filePath);
        for (TopComponent tc : WindowManager.getDefault().getRegistry().getOpened()) {
            if (tc instanceof ClaudeSessionTab stc) {
                java.io.File dir = stc.getWorkingDirectory();
                if (dir != null && file.getAbsolutePath().startsWith(dir.getAbsolutePath())) {
                    stc.cancelCurrentPrompt();
                    return;
                }
            }
        }
    }

    private static StreamSource textSource(String name, String filePath, String content) {
        return new StreamSource() {
            @Override public String getName()     { return name; }
            @Override public String getTitle()    { return filePath; }
            @Override public String getMIMEType() { return "text/plain"; }
            @Override public Reader createReader()  { return new StringReader(content); }
            @Override public Writer createWriter(Difference[] conflicts) throws IOException {
                throw new IOException("Writing not supported");
            }
        };
    }
}
