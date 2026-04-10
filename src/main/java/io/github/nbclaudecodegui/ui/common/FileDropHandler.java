package io.github.nbclaudecodegui.ui.common;

import java.awt.Image;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.image.RenderedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.TransferHandler;
import javax.swing.text.JTextComponent;
import org.openide.filesystems.FileUtil;
import org.openide.nodes.Node;
import org.openide.nodes.NodeTransfer;

/**
 * {@link TransferHandler} that handles drag-and-drop of files onto a text component
 * and clipboard paste of file lists or images.
 * <p>Debug logging (java.util.logging FINE) is emitted for each import operation.
 *
 * <p>All files (inside or outside working dir) and clipboard images are inserted
 * as {@code @path} tokens directly into the text component at the caret position:
 * <ul>
 *   <li>File inside {@code workingDir}: {@code @relative/path} inserted at caret.</li>
 *   <li>File outside {@code workingDir}: {@code @/absolute/path} inserted at caret.</li>
 *   <li>Clipboard image: written to a temp PNG, {@code @/tmp/....png} inserted at caret.</li>
 * </ul>
 *
 * <p>Temp files are tracked in {@link #tempFiles} and deleted by {@link #cleanup()}.
 */
public final class FileDropHandler extends TransferHandler {

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(FileDropHandler.class.getName());

    private static final List<String> SOURCE_ROOTS = List.of(
            "src/main/java/", "src/test/java/",
            "src/main/groovy/", "src/test/groovy/",
            "src/main/kotlin/", "src/test/kotlin/"
    );

    /** Returns the absolute working-directory path, or {@code null} if not yet set. */
    private final java.util.function.Supplier<String> workingDirSupplier;

    /**
     * The original TransferHandler of the text component, used to delegate Cut/Copy
     * (export) operations that this handler does not override.
     */
    private final TransferHandler originalHandler;

    /** Temp PNG files created from clipboard images; deleted by {@link #cleanup()}. */
    private final List<File> tempFiles = new ArrayList<>();

    /**
     * Creates a new {@code FileDropHandler}.
     *
     * @param workingDirSupplier supplier for the current working directory path
     * @param originalHandler    the text component's original TransferHandler (for Cut/Copy delegation)
     */
    public FileDropHandler(java.util.function.Supplier<String> workingDirSupplier,
                           TransferHandler originalHandler) {
        this.workingDirSupplier = workingDirSupplier;
        this.originalHandler    = originalHandler;
    }

    // -------------------------------------------------------------------------
    // TransferHandler export overrides — delegate Cut/Copy to original handler
    // -------------------------------------------------------------------------

    @Override
    public int getSourceActions(javax.swing.JComponent c) {
        return originalHandler != null ? originalHandler.getSourceActions(c) : super.getSourceActions(c);
    }

    @Override
    protected java.awt.datatransfer.Transferable createTransferable(javax.swing.JComponent c) {
        if (originalHandler == null) return super.createTransferable(c);
        return invokeProtected(originalHandler, "createTransferable",
                new Class[]{javax.swing.JComponent.class}, new Object[]{c});
    }

    @Override
    protected void exportDone(javax.swing.JComponent source, java.awt.datatransfer.Transferable data, int action) {
        if (originalHandler == null) { super.exportDone(source, data, action); return; }
        invokeProtected(originalHandler, "exportDone",
                new Class[]{javax.swing.JComponent.class, java.awt.datatransfer.Transferable.class, int.class},
                new Object[]{source, data, action});
    }

    /** Invokes a protected method by traversing the actual class hierarchy. */
    @SuppressWarnings("unchecked")
    private static <T> T invokeProtected(Object target, String methodName, Class<?>[] params, Object[] args) {
        Class<?> cls = target.getClass();
        while (cls != null) {
            try {
                java.lang.reflect.Method m = cls.getDeclaredMethod(methodName, params);
                m.setAccessible(true);
                return (T) m.invoke(target, args);
            } catch (NoSuchMethodException ignored) {
                cls = cls.getSuperclass();
            } catch (Exception ex) {
                return null;
            }
        }
        return null;
    }

    /**
     * Deletes all temp files (clipboard images) created during this session.
     */
    public void cleanup() {
        for (File tmp : tempFiles) {
            try {
                Files.deleteIfExists(tmp.toPath());
            } catch (IOException ignored) {
                // Best-effort
            }
        }
        tempFiles.clear();
    }

    // -------------------------------------------------------------------------
    // TransferHandler overrides — used for DnD
    // -------------------------------------------------------------------------

    @Override
    public boolean canImport(TransferSupport support) {
        Transferable t = support.getTransferable();
        boolean fileList = support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
        boolean image    = support.isDataFlavorSupported(DataFlavor.imageFlavor);
        boolean string   = support.isDataFlavorSupported(DataFlavor.stringFlavor);
        boolean nodeFlav = hasNodeDndFlavor(t);
        LOG.fine("canImport: fileList=" + fileList + " image=" + image + " string=" + string + " node=" + nodeFlav);
        return fileList || image || string || nodeFlav;
    }

    /** Package-private helper used by tests to check flavor support on a bare Transferable. */
    boolean canImportTransferable(Transferable t) {
        return t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
                || t.isDataFlavorSupported(DataFlavor.imageFlavor)
                || t.isDataFlavorSupported(DataFlavor.stringFlavor)
                || hasNodeDndFlavor(t);
    }

    /**
     * Returns {@code true} if the transferable carries a node DnD flavor AND at least one node
     * has a resolvable FileObject (i.e. is not a blank phantom node as produced by the Files window).
     */
    private static boolean hasNodeDndFlavor(Transferable t) {
        Node[] nodes = extractNodes(t);
        if (nodes == null || nodes.length == 0) return false;
        for (Node n : nodes) {
            if (resolveFileObject(n) != null) return true;
        }
        LOG.fine("hasNodeDndFlavor: all nodes have unresolvable FileObject — rejecting");
        return false;
    }

    /**
     * Resolves the {@link org.openide.filesystems.FileObject} for a node using the same
     * priority chain as {@link #importNodes}: FileObject → DataObject → Project.
     * Returns {@code null} if none of the lookup entries yields a FileObject.
     */
    private static org.openide.filesystems.FileObject resolveFileObject(Node n) {
        org.openide.filesystems.FileObject fo = n.getLookup().lookup(org.openide.filesystems.FileObject.class);
        if (fo == null) {
            var dobj = n.getLookup().lookup(org.openide.loaders.DataObject.class);
            if (dobj != null) fo = dobj.getPrimaryFile();
        }
        if (fo == null) {
            var proj = n.getLookup().lookup(org.netbeans.api.project.Project.class);
            if (proj != null) fo = proj.getProjectDirectory();
        }
        return fo;
    }

    /** Extracts {@link Node}s from a transferable, trying standard NodeTransfer first,
     *  then scanning all flavors for any that yield a Node instance. */
    private static Node[] extractNodes(Transferable t) {
        Node[] nodes = NodeTransfer.nodes(t, NodeTransfer.DND_COPY_OR_MOVE);
        if (nodes != null) return nodes;
        for (DataFlavor f : t.getTransferDataFlavors()) {
            try {
                Object data = t.getTransferData(f);
                if (data instanceof Node n) return new Node[]{n};
            } catch (Exception ex) { /* ignore */ }
        }
        return null;
    }

    @Override
    public boolean importData(TransferSupport support) {
        LOG.fine("importData: component=" + support.getComponent().getClass().getSimpleName());
        Transferable t = support.getTransferable();
        JTextComponent textComponent = findTextComponent(support.getComponent());
        int insertPos = textComponent != null ? textComponent.getCaretPosition() : 0;
        return doImport(t, textComponent, insertPos);
    }

    /**
     * Called by our custom {@link java.awt.dnd.DropTargetAdapter} in place of the standard
     * Swing drop pipeline.  Because our adapter never calls {@code setDropLocation}, the caret
     * stays at the position the user left it — so we can read it here and insert there.
     */
    void handleDrop(java.awt.dnd.DropTargetDropEvent dtde, JTextComponent tc) {
        dtde.acceptDrop(java.awt.dnd.DnDConstants.ACTION_COPY_OR_MOVE);
        int insertPos = tc.getCaretPosition();
        boolean ok = doImport(dtde.getTransferable(), tc, insertPos);
        dtde.dropComplete(ok);
    }

    // -------------------------------------------------------------------------
    // Clipboard paste — called explicitly from TextComponentDecorator on Ctrl+V
    // -------------------------------------------------------------------------

    /**
     * Attempts to import file list or image from the system clipboard.
     *
     * @param textComponent the text component into which paths should be inserted
     * @return {@code true} if file/image content was imported; {@code false} if
     *         the clipboard contains only plain text (caller should fall through
     *         to default paste)
     */
    public boolean importFromClipboard(JTextComponent textComponent) {
        java.awt.datatransfer.Clipboard cb =
                java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
        Transferable t = cb.getContents(null);
        if (t == null) return false;
        boolean hasFiles = t.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
        boolean hasImage = t.isDataFlavorSupported(DataFlavor.imageFlavor);
        boolean hasText  = t.isDataFlavorSupported(DataFlavor.stringFlavor);
        LOG.fine("importFromClipboard: hasFiles=" + hasFiles + " hasImage=" + hasImage + " hasText=" + hasText);
        if (!hasFiles && !hasImage && !hasText) return false;
        return doImport(t, textComponent, textComponent.getCaretPosition());
    }

    // -------------------------------------------------------------------------
    // Shared import logic
    // -------------------------------------------------------------------------

    private boolean doImport(Transferable t, JTextComponent textComponent, int insertPos) {
        try {
            Node[] nodes = extractNodes(t);
            if (nodes != null && nodes.length > 0) {
                LOG.fine("doImport: nodes branch, count=" + nodes.length);
                return importNodes(nodes, textComponent, insertPos);
            }
            if (t.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                Image img = (Image) t.getTransferData(DataFlavor.imageFlavor);
                if (img != null) {
                    LOG.fine("doImport: image branch");
                    File tmp = writeTempPng(img);
                    if (textComponent != null) {
                        insertAtCaret(textComponent, "@" + tmp.getAbsolutePath(), insertPos);
                    }
                    return true;
                }
            }
            if (t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                LOG.fine("doImport: javaFileList branch");
                @SuppressWarnings("unchecked")
                List<File> dropped = (List<File>) t.getTransferData(DataFlavor.javaFileListFlavor);
                if (dropped == null || dropped.isEmpty()) return false;

                String wdStr = workingDirSupplier.get();
                Path workDir = wdStr != null ? Path.of(wdStr) : null;

                StringBuilder paths = new StringBuilder();
                for (File f : dropped) {
                    Path filePath = f.toPath().toAbsolutePath().normalize();
                    if (paths.length() > 0) paths.append(" ");
                    paths.append(fileToAtPath(filePath, workDir));
                }

                if (paths.length() > 0 && textComponent != null) {
                    insertAtCaret(textComponent, paths.toString(), insertPos);
                }
                return true;
            }
            if (t.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                LOG.fine("doImport: string branch");
                String s = (String) t.getTransferData(DataFlavor.stringFlavor);
                if (s != null && textComponent != null) { insertAtCaret(textComponent, s, insertPos); return true; }
            }
        } catch (Exception ex) {
            // Import failed; fall through to return false
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Node-transfer import (NetBeans project tree DnD)
    // -------------------------------------------------------------------------

    /**
     * Processes an array of NetBeans {@link Node}s dropped or pasted.
     *
     * <ul>
     *   <li>Package directory inside workingDir (under a known source root):
     *       fully-qualified package name inserted as plain text at caret.</li>
     *   <li>Any directory inside workingDir but not under a source root:
     *       relative path inserted as plain text.</li>
     *   <li>Any file or directory outside workingDir:
     *       {@code @/absolute/path} inserted at caret.</li>
     * </ul>
     */
    boolean importNodes(Node[] nodes, JTextComponent textComponent) {
        int pos = textComponent != null ? textComponent.getCaretPosition() : 0;
        return importNodes(nodes, textComponent, pos);
    }

    private boolean importNodes(Node[] nodes, JTextComponent textComponent, int insertPos) {
        LOG.fine("importNodes: entry, count=" + (nodes == null ? 0 : nodes.length));
        if (nodes == null || nodes.length == 0) return false;
        String wdStr = workingDirSupplier.get();
        Path workDir = wdStr != null ? Path.of(wdStr).normalize() : null;

        StringBuilder inlineText = new StringBuilder();

        for (Node n : nodes) {
            LOG.fine("importNodes: node class=" + n.getClass().getName()
                    + " lookupTypes=" + n.getLookup().lookupAll(Object.class)
                        .stream().map(o -> o.getClass().getName()).toList());
            // Resolve FileObject via priority chain
            org.openide.filesystems.FileObject fo = resolveFileObject(n);
            LOG.fine("importNodes: fo=" + (fo == null ? "null"
                    : fo.getClass().getName() + " path='" + fo.getPath() + "'"));
            if (fo == null) {
                LOG.fine("importNodes: fo=null, node.getName='" + n.getName()
                        + "' displayName='" + n.getDisplayName()
                        + "' shortDesc='" + n.getShortDescription() + "'");
                continue;
            }

            File f = FileUtil.toFile(fo);
            if (f == null) {
                try { f = new File(fo.toURL().toURI()); } catch (Exception ignored) {}
            }
            // For LocalFileSystem-rooted FileObjects (Files window), getPath() returns
            // an absolute OS path even when toFile() / toURL() fail
            if (f == null) {
                String foPath = fo.getPath();
                if (foPath != null && !foPath.isEmpty()) {
                    File candidate = new File(foPath);
                    if (candidate.exists()) f = candidate;
                }
            }
            LOG.fine("importNodes: f=" + f);
            if (f == null) {
                LOG.warning("Cannot resolve FileObject to File: " + fo);
                continue;
            }

            Path filePath = f.toPath().normalize();
            boolean insideWorkDir = workDir != null && filePath.startsWith(workDir);
            LOG.fine("importNodes: filePath=" + filePath + " insideWorkDir=" + insideWorkDir + " isFolder=" + fo.isFolder());

            if (fo.isFolder()) {
                if (insideWorkDir) {
                    String relStr = workDir.relativize(filePath).toString()
                            .replace(File.separatorChar, '/');
                    if (relStr.isEmpty()) {
                        // workDir root itself → insert @./
                        if (inlineText.length() > 0) inlineText.append(" ");
                        inlineText.append("@./");
                        continue;
                    }
                    String withSlash = relStr + "/";
                    String matchedRoot = SOURCE_ROOTS.stream()
                            .filter(withSlash::startsWith)
                            .findFirst().orElse(null);

                    String toInsert;
                    if (matchedRoot != null) {
                        // Package dir under a source root → FQN
                        String pkg = withSlash.substring(matchedRoot.length());
                        if (pkg.endsWith("/")) pkg = pkg.substring(0, pkg.length() - 1);
                        // pkg is empty when the folder IS the source root itself (e.g. src/main/java).
                        // Fall back to the relative path so something is inserted.
                        toInsert = pkg.isEmpty() ? relStr : pkg.replace('/', '.');
                    } else {
                        // Non-source-root directory inside workDir: insert as plain relative path.
                        toInsert = relStr;
                    }
                    if (inlineText.length() > 0) inlineText.append(" ");
                    inlineText.append(toInsert);
                } else {
                    // Outside workDir → @/absolute/path
                    if (inlineText.length() > 0) inlineText.append(" ");
                    inlineText.append("@").append(filePath.toString());
                }
            } else {
                // File node
                if (insideWorkDir) {
                    String rel = workDir.relativize(filePath).toString()
                            .replace(File.separatorChar, '/');
                    if (inlineText.length() > 0) inlineText.append(" ");
                    // NOTE: No @ prefix for files inside workDir is intentional (Node DnD convention).
                    inlineText.append(rel);
                } else {
                    // Outside workDir → @/absolute/path
                    if (inlineText.length() > 0) inlineText.append(" ");
                    inlineText.append("@").append(filePath.toString());
                }
            }
        }

        if (inlineText.length() > 0 && textComponent != null) {
            insertAtCaret(textComponent, inlineText.toString(), insertPos);
        }
        LOG.fine("importNodes: returning " + (inlineText.length() > 0) + ", text='" + inlineText + "'");
        return inlineText.length() > 0;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the {@code @path} token for a file: {@code @relative/path} if inside workDir,
     * {@code @/absolute/path} otherwise. Handles the workDir-root case as {@code @./}.
     */
    private static String fileToAtPath(Path filePath, Path workDir) {
        if (workDir != null && filePath.startsWith(workDir.normalize())) {
            Path rel = workDir.normalize().relativize(filePath);
            String relStr = rel.toString().replace(File.separatorChar, '/');
            if (relStr.isEmpty()) return "@./";
            return "@" + relStr;
        }
        return "@" + filePath.toString();
    }

    /**
     * Writes an AWT {@link Image} to a temp PNG file, registers it for cleanup, and returns it.
     */
    private File writeTempPng(Image img) throws IOException {
        File tmp = File.createTempFile("claude-attach-", ".png");
        tmp.deleteOnExit();
        if (img instanceof RenderedImage ri) {
            ImageIO.write(ri, "png", tmp);
        } else {
            java.awt.image.BufferedImage bi = new java.awt.image.BufferedImage(
                    img.getWidth(null), img.getHeight(null),
                    java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = bi.createGraphics();
            g.drawImage(img, 0, 0, null);
            g.dispose();
            ImageIO.write(bi, "png", tmp);
        }
        tempFiles.add(tmp);
        return tmp;
    }

    private static void insertAtCaret(JTextComponent textComponent, String text, int pos) {
        javax.swing.SwingUtilities.invokeLater(() -> {
            try {
                String before = textComponent.getText(0, pos);
                String toInsert = (!before.isEmpty() && !before.endsWith(" ") && !before.endsWith("\n"))
                        ? " " + text + " "
                        : text + " ";
                textComponent.getDocument().insertString(pos, toInsert, null);
                textComponent.setCaretPosition(pos + toInsert.length());
            } catch (javax.swing.text.BadLocationException ex) {
                textComponent.setText(textComponent.getText() + " " + text + " ");
            }
            textComponent.requestFocusInWindow();
        });
    }

    private static JTextComponent findTextComponent(java.awt.Component c) {
        if (c instanceof JTextComponent tc) return tc;
        if (c instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                JTextComponent found = findTextComponent(child);
                if (found != null) return found;
            }
        }
        return null;
    }
}
