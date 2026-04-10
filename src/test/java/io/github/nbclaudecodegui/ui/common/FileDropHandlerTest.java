package io.github.nbclaudecodegui.ui.common;

import io.github.nbclaudecodegui.ui.common.FileDropHandler;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.LocalFileSystem;
import org.openide.loaders.DataFolder;
import org.openide.nodes.AbstractNode;
import org.openide.nodes.Children;
import org.openide.nodes.Node;
import org.openide.util.lookup.Lookups;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link FileDropHandler}.
 *
 * <p>Tests the {@code importFromClipboard} and the shared {@code doImport} logic
 * via a test transferable.
 */
class FileDropHandlerTest {

    @TempDir
    File workDir;

    private FileDropHandler handler;
    private JTextArea       area;

    @BeforeEach
    void setUp() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            handler = new FileDropHandler(() -> workDir.getAbsolutePath(), null);
            area    = new JTextArea();
        });
    }

    // -------------------------------------------------------------------------
    // Helper: build a Transferable with a file list
    // -------------------------------------------------------------------------

    private static java.awt.datatransfer.Transferable fileListTransferable(List<File> files) {
        return new java.awt.datatransfer.Transferable() {
            @Override
            public java.awt.datatransfer.DataFlavor[] getTransferDataFlavors() {
                return new java.awt.datatransfer.DataFlavor[]{
                        java.awt.datatransfer.DataFlavor.javaFileListFlavor
                };
            }
            @Override
            public boolean isDataFlavorSupported(java.awt.datatransfer.DataFlavor flavor) {
                return java.awt.datatransfer.DataFlavor.javaFileListFlavor.equals(flavor);
            }
            @Override
            public Object getTransferData(java.awt.datatransfer.DataFlavor flavor) {
                return files;
            }
        };
    }

    private static java.awt.datatransfer.Transferable imageTransferable(java.awt.Image img) {
        return new java.awt.datatransfer.Transferable() {
            @Override
            public java.awt.datatransfer.DataFlavor[] getTransferDataFlavors() {
                return new java.awt.datatransfer.DataFlavor[]{
                        java.awt.datatransfer.DataFlavor.imageFlavor
                };
            }
            @Override
            public boolean isDataFlavorSupported(java.awt.datatransfer.DataFlavor flavor) {
                return java.awt.datatransfer.DataFlavor.imageFlavor.equals(flavor);
            }
            @Override
            public Object getTransferData(java.awt.datatransfer.DataFlavor flavor) {
                return img;
            }
        };
    }

    // -------------------------------------------------------------------------
    // Tests: file inside workingDir → @relative/path inserted into textarea
    // -------------------------------------------------------------------------

    @Test
    void dropFileInsideWorkDirInsertsAtRelativePath() throws Exception {
        File inside = new File(workDir, "src/Foo.java");
        inside.getParentFile().mkdirs();
        inside.createNewFile();

        SwingUtilities.invokeAndWait(() -> {
            java.awt.datatransfer.Transferable t = fileListTransferable(List.of(inside));
            boolean result = callDoImport(handler, t, area);
            assertTrue(result, "Should return true for a file list");
        });

        SwingUtilities.invokeAndWait(() -> {
            String text = area.getText();
            assertTrue(text.contains("@src/Foo.java") || text.contains("@src" + File.separator + "Foo.java"),
                    "@-relative path must appear in textarea, got: " + text);
        });
    }

    @Test
    void dropFileOutsideWorkDirInsertsAtAbsolutePath() throws Exception {
        File outside = File.createTempFile("outside", ".txt");
        outside.deleteOnExit();

        SwingUtilities.invokeAndWait(() -> {
            java.awt.datatransfer.Transferable t = fileListTransferable(List.of(outside));
            boolean result = callDoImport(handler, t, area);
            assertTrue(result);
        });

        SwingUtilities.invokeAndWait(() -> {
            String text = area.getText();
            assertTrue(text.contains("@" + outside.getAbsolutePath()),
                    "Outside-workDir file must be inserted as @/absolute/path, got: " + text);
        });
    }

    @Test
    void dropTwoInsideFilesInsertsBothPaths() throws Exception {
        File a = new File(workDir, "a.txt"); a.createNewFile();
        File b = new File(workDir, "b.txt"); b.createNewFile();

        SwingUtilities.invokeAndWait(() -> {
            java.awt.datatransfer.Transferable t = fileListTransferable(List.of(a, b));
            callDoImport(handler, t, area);
        });

        SwingUtilities.invokeAndWait(() -> {
            String text = area.getText();
            assertTrue(text.contains("a.txt"), "First file must appear in textarea");
            assertTrue(text.contains("b.txt"), "Second file must appear in textarea");
        });
    }

    @Test
    void dropInsideAndOutsideFilesInsertsBothPaths() throws Exception {
        File inside  = new File(workDir, "inner.txt"); inside.createNewFile();
        File outside = File.createTempFile("outer", ".txt"); outside.deleteOnExit();

        SwingUtilities.invokeAndWait(() -> {
            java.awt.datatransfer.Transferable t = fileListTransferable(List.of(inside, outside));
            callDoImport(handler, t, area);
        });

        SwingUtilities.invokeAndWait(() -> {
            String text = area.getText();
            assertTrue(text.contains("inner.txt"), "Inner file must be in textarea");
            assertTrue(text.contains("@" + outside.getAbsolutePath()),
                    "Outer file must be inserted as @/absolute/path, got: " + text);
        });
    }

    // -------------------------------------------------------------------------
    // Tests: image → @/tmp/....png inserted into textarea
    // -------------------------------------------------------------------------

    @Test
    void pasteImageInsertsAtTempPath() throws Exception {
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);

        SwingUtilities.invokeAndWait(() -> {
            java.awt.datatransfer.Transferable t = imageTransferable(img);
            boolean result = callDoImport(handler, t, area);
            assertTrue(result, "Image import must return true");
        });

        SwingUtilities.invokeAndWait(() -> {
            String text = area.getText();
            assertTrue(text.contains("@") && text.contains(".png"),
                    "Image must be inserted as @/tmp/...png in textarea, got: " + text);
        });
    }

    @Test
    void pasteImageCleanupDeletesTempFile() throws Exception {
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);

        // Extract the temp file path from textarea after import
        final String[] insertedPath = new String[1];
        SwingUtilities.invokeAndWait(() -> {
            java.awt.datatransfer.Transferable t = imageTransferable(img);
            callDoImport(handler, t, area);
        });
        SwingUtilities.invokeAndWait(() -> {
            String text = area.getText().trim();
            // text looks like "@/tmp/claude-attach-xxx.png"
            insertedPath[0] = text.startsWith("@") ? text.substring(1).trim() : text;
        });

        File tmp = new File(insertedPath[0]);
        assertTrue(tmp.exists(), "Temp file must exist before cleanup");
        handler.cleanup();
        assertFalse(tmp.exists(), "Temp file must be deleted after cleanup");
    }

    // -------------------------------------------------------------------------
    // Tests: plain-text transferable → inserted into textarea
    // -------------------------------------------------------------------------

    @Test
    void doImportPlainTextInsertsTextIntoTextArea() throws Exception {
        String plainText = "hello world";
        java.awt.datatransfer.Transferable t = stringTransferable(plainText);

        SwingUtilities.invokeAndWait(() -> {
            boolean result = callDoImport(handler, t, area);
            assertTrue(result, "Plain-text import must return true");
        });

        SwingUtilities.invokeAndWait(() -> {
            assertTrue(area.getText().contains(plainText),
                    "Plain text must appear in textarea, got: " + area.getText());
        });
    }

    @Test
    void canImportAcceptsStringFlavor() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            java.awt.datatransfer.Transferable t = stringTransferable("test");
            boolean result = callDoImport(handler, t, area);
            assertTrue(result, "doImport must accept stringFlavor");
        });
    }

    private static java.awt.datatransfer.Transferable stringTransferable(String text) {
        return new java.awt.datatransfer.Transferable() {
            @Override
            public java.awt.datatransfer.DataFlavor[] getTransferDataFlavors() {
                return new java.awt.datatransfer.DataFlavor[]{
                        java.awt.datatransfer.DataFlavor.stringFlavor
                };
            }
            @Override
            public boolean isDataFlavorSupported(java.awt.datatransfer.DataFlavor flavor) {
                return java.awt.datatransfer.DataFlavor.stringFlavor.equals(flavor);
            }
            @Override
            public Object getTransferData(java.awt.datatransfer.DataFlavor flavor)
                    throws java.awt.datatransfer.UnsupportedFlavorException {
                if (!isDataFlavorSupported(flavor)) throw new java.awt.datatransfer.UnsupportedFlavorException(flavor);
                return text;
            }
        };
    }

    // -------------------------------------------------------------------------
    // Tests: NetBeans Node transfers (package nodes and project directory nodes)
    // -------------------------------------------------------------------------

    /**
     * Creates an AbstractNode whose Lookup contains a FileObject for {@code dir},
     * using a LocalFileSystem (works without the full NetBeans runtime).
     */
    private static Node nodeForDir(File dir) throws Exception {
        dir.mkdirs();
        LocalFileSystem lfs = new LocalFileSystem();
        lfs.setRootDirectory(dir);
        var fo = lfs.getRoot();
        assertNotNull(fo, "LocalFileSystem root must be non-null for " + dir);
        return new AbstractNode(Children.LEAF, Lookups.singleton(fo));
    }

    @Test
    void importNodesPackageDirInWorkDirInsertsFqn() throws Exception {
        // Create src/main/java/com/example/pkg directory inside workDir
        File pkgDir = new File(workDir, "src/main/java/com/example/pkg");
        pkgDir.mkdirs();
        Node node = nodeForDir(pkgDir);

        SwingUtilities.invokeAndWait(() -> {
            boolean result = callImportNodes(handler, new Node[]{node}, area);
            assertTrue(result, "importNodes must return true for package dir inside workDir");
        });

        SwingUtilities.invokeAndWait(() -> {
            String text = area.getText();
            assertTrue(text.contains("com.example.pkg"),
                    "FQN must be inserted into textarea, got: " + text);
        });
    }

    @Test
    void importNodesSourceRootFolderItselfInsertsRelativePath() throws Exception {
        // Drop src/main/java itself (the source root, not a subpackage).
        // Previously this produced an empty pkg string and inserted nothing.
        File srcRoot = new File(workDir, "src/main/java");
        srcRoot.mkdirs();
        Node node = nodeForDir(srcRoot);

        SwingUtilities.invokeAndWait(() -> {
            boolean result = callImportNodes(handler, new Node[]{node}, area);
            assertTrue(result, "importNodes must return true for source-root folder itself");
        });

        SwingUtilities.invokeAndWait(() -> {
            String text = area.getText();
            assertTrue(text.contains("src/main/java") || text.contains("src" + File.separator + "main"),
                    "Relative path of source root must be inserted, got: " + text);
        });
    }

    @Test
    void importNodesPackageDirOutsideWorkDirInsertsAtAbsolutePath() throws Exception {
        File outsideDir = File.createTempFile("outside-pkg-", "");
        outsideDir.delete();
        outsideDir = new File(outsideDir.getParentFile(), outsideDir.getName());
        outsideDir.mkdirs();
        outsideDir.deleteOnExit();
        File pkgDir = new File(outsideDir, "src/main/java/com/other/pkg");
        pkgDir.mkdirs();
        Node node = nodeForDir(pkgDir);

        final File finalPkgDir = pkgDir;
        final Node finalNode = node;
        SwingUtilities.invokeAndWait(() -> {
            boolean result = callImportNodes(handler, new Node[]{finalNode}, area);
            assertTrue(result, "importNodes must return true for dir outside workDir");
        });

        SwingUtilities.invokeAndWait(() -> {
            String text = area.getText();
            assertTrue(text.contains("@") && text.contains(finalPkgDir.getAbsolutePath()),
                    "Outside-workDir dir must be inserted as @/absolute/path, got: " + text);
        });
    }

    @Test
    void importNodesProjectDirInsideWorkDirInsertsRelativePath() throws Exception {
        File subdir = new File(workDir, "docs/api");
        subdir.mkdirs();
        Node node = nodeForDir(subdir);

        SwingUtilities.invokeAndWait(() -> {
            boolean result = callImportNodes(handler, new Node[]{node}, area);
            assertTrue(result, "importNodes must return true for dir inside workDir");
        });

        SwingUtilities.invokeAndWait(() -> {
            String text = area.getText();
            assertTrue(text.contains("docs/api") || text.contains("docs" + File.separator + "api"),
                    "Relative path must be inserted for non-source-root dir, got: " + text);
        });
    }

    @Test
    void importNodesMultiplePackagesInsertsFqnsSpaceSeparated() throws Exception {
        File pkg1 = new File(workDir, "src/main/java/com/a");
        File pkg2 = new File(workDir, "src/test/java/com/b");
        pkg1.mkdirs();
        pkg2.mkdirs();
        Node n1 = nodeForDir(pkg1);
        Node n2 = nodeForDir(pkg2);

        SwingUtilities.invokeAndWait(() -> {
            callImportNodes(handler, new Node[]{n1, n2}, area);
        });

        SwingUtilities.invokeAndWait(() -> {
            String text = area.getText();
            assertTrue(text.contains("com.a"), "First FQN must appear, got: " + text);
            assertTrue(text.contains("com.b"), "Second FQN must appear, got: " + text);
        });
    }

    /**
     * Creates an AbstractNode whose Lookup contains a FileObject for a *file*
     * (not a directory), using a LocalFileSystem.
     */
    private static Node nodeForFile(File file) throws Exception {
        file.getParentFile().mkdirs();
        file.createNewFile();
        LocalFileSystem lfs = new LocalFileSystem();
        lfs.setRootDirectory(file.getParentFile());
        var fo = lfs.findResource(file.getName());
        assertNotNull(fo, "LocalFileSystem must find file " + file.getName());
        return new AbstractNode(Children.LEAF, Lookups.singleton(fo));
    }

    @Test
    void importNodesFileNodeInsideWorkDirInsertsRelativePath() throws Exception {
        File javaFile = new File(workDir, "src/main/java/Foo.java");
        Node node = nodeForFile(javaFile);

        SwingUtilities.invokeAndWait(() -> {
            boolean result = callImportNodes(handler, new Node[]{node}, area);
            assertTrue(result, "importNodes must return true for file node inside workDir");
        });

        SwingUtilities.invokeAndWait(() -> {
            String text = area.getText();
            assertTrue(text.contains("src/main/java/Foo.java") || text.contains("Foo.java"),
                    "Relative path must be inserted for file node inside workDir, got: " + text);
        });
    }

    @Test
    void importNodesFileNodeOutsideWorkDirInsertsAtAbsolutePath() throws Exception {
        File outsideFile = File.createTempFile("outside-node-", ".java");
        outsideFile.deleteOnExit();
        Node node = nodeForFile(outsideFile);

        SwingUtilities.invokeAndWait(() -> {
            boolean result = callImportNodes(handler, new Node[]{node}, area);
            assertTrue(result, "importNodes must return true for file node outside workDir");
        });

        SwingUtilities.invokeAndWait(() -> {
            String text = area.getText();
            assertTrue(text.contains("@" + outsideFile.getAbsolutePath()),
                    "File node outside workDir must be inserted as @/absolute/path, got: " + text);
        });
    }

    @Test
    void importNodesProjectNodeInsideWorkDirInsertsRelativePath() throws Exception {
        File subProjectDir = new File(workDir, "subproject");
        subProjectDir.mkdirs();
        LocalFileSystem lfs = new LocalFileSystem();
        lfs.setRootDirectory(subProjectDir);
        FileObject projectFo = lfs.getRoot();
        org.netbeans.api.project.Project fakeProject = new org.netbeans.api.project.Project() {
            @Override public FileObject getProjectDirectory() { return projectFo; }
            @Override public org.openide.util.Lookup getLookup() { return org.openide.util.Lookup.EMPTY; }
        };
        Node node = new AbstractNode(Children.LEAF, Lookups.singleton(fakeProject));

        SwingUtilities.invokeAndWait(() -> {
            boolean result = callImportNodes(handler, new Node[]{node}, area);
            assertTrue(result, "importNodes must return true for project node inside workDir");
        });

        SwingUtilities.invokeAndWait(() -> {
            String text = area.getText();
            assertTrue(text.contains("subproject"),
                    "Relative path of project dir must be inserted, got: " + text);
        });
    }

    // -------------------------------------------------------------------------
    // Tests: project root → @./
    // -------------------------------------------------------------------------

    @Test
    void testProjectRootFileListInsertsAtDotSlash() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            java.awt.datatransfer.Transferable t = fileListTransferable(List.of(workDir));
            boolean result = callDoImport(handler, t, area);
            assertTrue(result, "Import of workDir root must return true");
        });

        SwingUtilities.invokeAndWait(() -> {
            String text = area.getText();
            assertTrue(text.contains("@./"),
                    "workDir root must insert @./ into textarea, got: " + text);
        });
    }

    @Test
    void testProjectRootNodeInsertsAtDotSlash() throws Exception {
        Node node = nodeForDir(workDir);

        SwingUtilities.invokeAndWait(() -> {
            boolean result = callImportNodes(handler, new Node[]{node}, area);
            assertTrue(result, "importNodes for workDir root must return true");
        });

        SwingUtilities.invokeAndWait(() -> {
            String text = area.getText();
            assertTrue(text.contains("@./"),
                    "workDir root node must insert @./ into textarea, got: " + text);
        });
    }

    // -------------------------------------------------------------------------
    // Tests: trailing space and caret position after insertion
    // -------------------------------------------------------------------------

    @Test
    void dropFileInsertsTrailingSpace() throws Exception {
        File inside = new File(workDir, "Readme.md");
        inside.createNewFile();

        SwingUtilities.invokeAndWait(() -> {
            java.awt.datatransfer.Transferable t = fileListTransferable(List.of(inside));
            callDoImport(handler, t, area);
        });

        SwingUtilities.invokeAndWait(() -> {
            String text = area.getText();
            assertTrue(text.endsWith(" "),
                    "Inserted path must be followed by a trailing space, got: '" + text + "'");
        });
    }

    @Test
    void dropFileCaretPositionedAfterTrailingSpace() throws Exception {
        File inside = new File(workDir, "build.xml");
        inside.createNewFile();

        SwingUtilities.invokeAndWait(() -> {
            java.awt.datatransfer.Transferable t = fileListTransferable(List.of(inside));
            callDoImport(handler, t, area);
        });

        SwingUtilities.invokeAndWait(() -> {
            int caretPos = area.getCaretPosition();
            int textLen  = area.getText().length();
            assertEquals(textLen, caretPos,
                    "Caret must be at the end of text (after trailing space), got caret=" + caretPos + " textLen=" + textLen);
        });
    }

    @Test
    void dropFileWhenAreaHasTextPrependsSeparatorAndTrailingSpace() throws Exception {
        File inside = new File(workDir, "pom.xml");
        inside.createNewFile();

        SwingUtilities.invokeAndWait(() -> {
            area.setText("fix");
            area.setCaretPosition(area.getText().length());
            java.awt.datatransfer.Transferable t = fileListTransferable(List.of(inside));
            callDoImport(handler, t, area);
        });

        SwingUtilities.invokeAndWait(() -> {
            String text = area.getText();
            assertTrue(text.startsWith("fix "),
                    "Space must be prepended when area already has text, got: '" + text + "'");
            assertTrue(text.endsWith(" "),
                    "Trailing space must be present, got: '" + text + "'");
        });
    }

    // -------------------------------------------------------------------------
    // Tests: package-node DnD flavor (custom MIME, not openide-nodednd)
    // -------------------------------------------------------------------------

    private static java.awt.datatransfer.Transferable singleFlavorTransferable(
            java.awt.datatransfer.DataFlavor flavor, Object data) {
        return new java.awt.datatransfer.Transferable() {
            @Override
            public java.awt.datatransfer.DataFlavor[] getTransferDataFlavors() {
                return new java.awt.datatransfer.DataFlavor[]{flavor};
            }
            @Override
            public boolean isDataFlavorSupported(java.awt.datatransfer.DataFlavor f) {
                return flavor.equals(f);
            }
            @Override
            public Object getTransferData(java.awt.datatransfer.DataFlavor f) {
                return data;
            }
        };
    }

    @Test
    void canImportPackageNodeFlavor() throws Exception {
        File pkgDir = new File(workDir, "src/main/java/com/example/pkg");
        pkgDir.mkdirs();
        Node packageNode = nodeForDir(pkgDir);
        java.awt.datatransfer.DataFlavor pkgFlavor = new java.awt.datatransfer.DataFlavor(
                "application/x-java-org-netbeans-modules-java-project-packagenodednd" +
                ";class=org.openide.nodes.AbstractNode",
                "Package Node DnD", Thread.currentThread().getContextClassLoader());
        java.awt.datatransfer.Transferable t = singleFlavorTransferable(pkgFlavor, packageNode);
        SwingUtilities.invokeAndWait(() ->
            assertTrue(callCanImportTransferable(handler, t),
                    "canImport must accept package-node flavor"));
    }

    @Test
    void doImportPackageNodeInsertsPackageFqn() throws Exception {
        File pkgDir = new File(workDir, "src/main/java/com/example/pkg");
        pkgDir.mkdirs();
        Node packageNode = nodeForDir(pkgDir);
        java.awt.datatransfer.DataFlavor pkgFlavor = new java.awt.datatransfer.DataFlavor(
                "application/x-java-org-netbeans-modules-java-project-packagenodednd" +
                ";class=org.openide.nodes.AbstractNode",
                "Package Node DnD", Thread.currentThread().getContextClassLoader());
        java.awt.datatransfer.Transferable t = singleFlavorTransferable(pkgFlavor, packageNode);
        SwingUtilities.invokeAndWait(() ->
            assertTrue(callDoImport(handler, t, area)));
        SwingUtilities.invokeAndWait(() ->
            assertTrue(area.getText().contains("com.example.pkg"),
                    "FQN must be inserted, got: " + area.getText()));
    }

    // -------------------------------------------------------------------------
    // Tests: canImport with NodeTransfer.nodeFlavor
    // -------------------------------------------------------------------------

    @Test
    void testCanImportNodeFlavor() throws Exception {
        java.awt.datatransfer.DataFlavor nodeDndFlavor = new java.awt.datatransfer.DataFlavor(
                "application/x-java-openide-nodednd;class=org.openide.nodes.Node;mask=2",
                "NetBeans Node DnD", Thread.currentThread().getContextClassLoader());
        java.awt.datatransfer.Transferable t = new java.awt.datatransfer.Transferable() {
            @Override
            public java.awt.datatransfer.DataFlavor[] getTransferDataFlavors() {
                return new java.awt.datatransfer.DataFlavor[]{nodeDndFlavor};
            }
            @Override
            public boolean isDataFlavorSupported(java.awt.datatransfer.DataFlavor flavor) {
                return nodeDndFlavor.equals(flavor);
            }
            @Override
            public Object getTransferData(java.awt.datatransfer.DataFlavor flavor)
                    throws java.awt.datatransfer.UnsupportedFlavorException {
                throw new java.awt.datatransfer.UnsupportedFlavorException(flavor);
            }
        };

        // If getTransferData throws, we cannot extract any node → cannot determine resolvability → reject
        SwingUtilities.invokeAndWait(() -> {
            boolean result = callCanImportTransferable(handler, t);
            assertFalse(result, "canImport must return false when node data cannot be retrieved");
        });
    }

    /** Calls the package-private {@code canImportTransferable(Transferable)} via reflection. */
    private static boolean callCanImportTransferable(FileDropHandler handler, java.awt.datatransfer.Transferable t) {
        try {
            java.lang.reflect.Method m = FileDropHandler.class.getDeclaredMethod(
                    "canImportTransferable", java.awt.datatransfer.Transferable.class);
            m.setAccessible(true);
            return (boolean) m.invoke(handler, t);
        } catch (Exception ex) {
            throw new RuntimeException("Could not invoke FileDropHandler.canImportTransferable", ex);
        }
    }

    /** Calls the package-private {@code importNodes(Node[], JTextComponent)} via reflection. */
    private static boolean callImportNodes(FileDropHandler handler, Node[] nodes, JTextArea area) {
        try {
            java.lang.reflect.Method m = FileDropHandler.class.getDeclaredMethod(
                    "importNodes", Node[].class, javax.swing.text.JTextComponent.class);
            m.setAccessible(true);
            return (boolean) m.invoke(handler, nodes, area);
        } catch (Exception ex) {
            throw new RuntimeException("Could not invoke FileDropHandler.importNodes", ex);
        }
    }

    // -------------------------------------------------------------------------
    // Tests: LocalFileSystem-rooted node (Files panel scenario)
    // -------------------------------------------------------------------------

    /**
     * Regression test: Files panel DnD nodes are backed by a LocalFileSystem rooted
     * at the directory itself, which means {@code FileUtil.toFile(fo)} may return null
     * for the root FileObject. The fallback via {@code fo.toURL().toURI()} must recover.
     */
    @Test
    void importNodesLocalFileSystemRootedNodeInsertsRelativePath() throws Exception {
        // Simulate the Files panel scenario: a directory is mounted as a LocalFileSystem root.
        File srcDir = new File(workDir, "src/main/java");
        srcDir.mkdirs();

        // Mount the deep directory itself as the LocalFileSystem root (Files-panel style).
        LocalFileSystem lfs = new LocalFileSystem();
        lfs.setRootDirectory(srcDir);
        FileObject fo = lfs.getRoot();
        assertNotNull(fo, "LocalFileSystem root must be non-null");

        Node node = new AbstractNode(Children.LEAF, Lookups.singleton(fo));

        SwingUtilities.invokeAndWait(() -> {
            boolean result = callImportNodes(handler, new Node[]{node}, area);
            assertTrue(result, "importNodes must return true for LocalFileSystem-rooted node");
        });

        SwingUtilities.invokeAndWait(() -> {
            String text = area.getText();
            assertFalse(text.isBlank(),
                    "Inserted text must not be empty for LocalFileSystem-rooted node, got: '" + text + "'");
            // Must contain some recognizable path component
            assertTrue(text.contains("src") || text.contains("java"),
                    "Path must reference the directory, got: '" + text + "'");
        });
    }

    // -------------------------------------------------------------------------
    // Tests: Files-window DnD — fo.getPath() fallback
    // -------------------------------------------------------------------------

    /**
     * Stub FileObject whose getFileSystem() throws FileStateInvalidException, which causes
     * both FileUtil.toFile() and the toURL().toURI() fallback to fail (return null / throw),
     * but whose getPath() returns a caller-supplied absolute OS path — simulating a
     * Files-window DnD node.
     */
    private static org.openide.filesystems.FileObject stubFoWithPath(String absolutePath, boolean isFolder) {
        return new org.openide.filesystems.FileObject() {
            @Override public String getPath() { return absolutePath; }
            @Override public boolean isFolder() { return isFolder; }
            @Override public boolean isData() { return !isFolder; }
            @Override public boolean isValid() { return true; }
            @Override public boolean isRoot() { return false; }
            @Override public boolean isReadOnly() { return true; }
            @Override public String getName() { return new File(absolutePath).getName(); }
            @Override public String getExt() { return ""; }
            @Override public void rename(org.openide.filesystems.FileLock lock, String name, String ext) {}
            // Throwing here causes FileUtil.toFile() to return null (it catches the exception)
            @Override public org.openide.filesystems.FileSystem getFileSystem()
                    throws org.openide.filesystems.FileStateInvalidException {
                throw new org.openide.filesystems.FileStateInvalidException("stub: no FS");
            }
            @Override public org.openide.filesystems.FileObject getParent() { return null; }
            @Override public org.openide.filesystems.FileObject[] getChildren() { return new org.openide.filesystems.FileObject[0]; }
            @Override public java.util.Enumeration<? extends org.openide.filesystems.FileObject> getChildren(boolean rec) { return java.util.Collections.emptyEnumeration(); }
            @Override public java.util.Date lastModified() { return new java.util.Date(); }
            @Override public org.openide.filesystems.FileObject getFileObject(String name, String ext) { return null; }
            @Override public org.openide.filesystems.FileObject createFolder(String name) { return null; }
            @Override public org.openide.filesystems.FileObject createData(String name, String ext) { return null; }
            @Override public org.openide.filesystems.FileLock lock() { return org.openide.filesystems.FileLock.NONE; }
            @Override public void setImportant(boolean b) {}
            @Override public void delete(org.openide.filesystems.FileLock lock) {}
            @Override public Object getAttribute(String attrName) { return null; }
            @Override public void setAttribute(String attrName, Object val) {}
            @Override public java.util.Enumeration<String> getAttributes() { return java.util.Collections.emptyEnumeration(); }
            @Override public void addFileChangeListener(org.openide.filesystems.FileChangeListener fcl) {}
            @Override public void removeFileChangeListener(org.openide.filesystems.FileChangeListener fcl) {}
            @Override public long getSize() { return 0; }
            @Override public java.io.InputStream getInputStream() throws java.io.FileNotFoundException { throw new java.io.FileNotFoundException("stub"); }
            @Override public java.io.OutputStream getOutputStream(org.openide.filesystems.FileLock lock) throws java.io.IOException { throw new java.io.IOException("stub"); }
        };
    }

    @Test
    void importNodesFilesWindowNodeOutsideWorkDirInsertsAtAbsolutePath() throws Exception {
        // Simulate a Files-window node: toURL() is broken, but getPath() returns an absolute path.
        File outsideDir = File.createTempFile("files-window-", "");
        outsideDir.delete();
        outsideDir.mkdirs();
        outsideDir.deleteOnExit();

        org.openide.filesystems.FileObject fo = stubFoWithPath(outsideDir.getAbsolutePath(), true);
        Node node = new AbstractNode(Children.LEAF, Lookups.singleton(fo));
        final File expectedDir = outsideDir;

        SwingUtilities.invokeAndWait(() -> {
            boolean result = callImportNodes(handler, new Node[]{node}, area);
            assertTrue(result, "importNodes must return true for Files-window node outside workDir");
        });

        SwingUtilities.invokeAndWait(() -> {
            String text = area.getText();
            assertTrue(text.contains("@" + expectedDir.getAbsolutePath()),
                    "Files-window node outside workDir must insert @/absolute/path, got: " + text);
        });
    }

    @Test
    void importNodesFilesWindowNodeInsideWorkDirInsertsRelativePath() throws Exception {
        // Simulate a Files-window node inside workDir: toURL() is broken, getPath() is absolute.
        File insideDir = new File(workDir, "src/api");
        insideDir.mkdirs();

        org.openide.filesystems.FileObject fo = stubFoWithPath(insideDir.getAbsolutePath(), true);
        Node node = new AbstractNode(Children.LEAF, Lookups.singleton(fo));

        SwingUtilities.invokeAndWait(() -> {
            boolean result = callImportNodes(handler, new Node[]{node}, area);
            assertTrue(result, "importNodes must return true for Files-window node inside workDir");
        });

        SwingUtilities.invokeAndWait(() -> {
            String text = area.getText();
            assertTrue(text.contains("src/api") || text.contains("src" + File.separator + "api"),
                    "Files-window node inside workDir must insert relative path, got: " + text);
            assertFalse(text.contains("@/") || text.contains("@" + File.separator),
                    "Inside-workDir node must not use absolute @-path, got: " + text);
        });
    }

    // -------------------------------------------------------------------------
    // Tests: insert at explicit position (DnD caret-save fix)
    // -------------------------------------------------------------------------

    /**
     * Verifies that doImport(Transferable, JTextComponent, int) inserts at the
     * given position, NOT at the text component's current caret position.
     * This is the core of the DnD caret-save fix: the drag-hover caret movement
     * must be ignored; insertion must happen where the caret was before the drag.
     */
    @Test
    void dropFileInsertsAtExplicitPositionIgnoringCurrentCaret() throws Exception {
        File inside = new File(workDir, "file.txt");
        inside.createNewFile();

        SwingUtilities.invokeAndWait(() -> {
            area.setText("hello world");
            // Simulate Swing moving the caret to the drop location (end of text)
            area.setCaretPosition(11);
            // Call doImport with insertPos=5 (the saved pre-drag caret position)
            java.awt.datatransfer.Transferable t = fileListTransferable(List.of(inside));
            callDoImportAt(handler, t, area, 5);
        });

        SwingUtilities.invokeAndWait(() -> {
            String text = area.getText();
            assertTrue(text.contains("file.txt"), "path must appear in text, got: '" + text + "'");
            // The path must be inserted inside "hello world", not appended at the end
            assertTrue(text.startsWith("hello"),
                    "text must start with 'hello' (insertion at pos 5), got: '" + text + "'");
            int pathIdx = text.indexOf("file.txt");
            assertTrue(pathIdx > 0 && pathIdx < text.indexOf("world"),
                    "path must be inserted between 'hello' and 'world', got: '" + text + "'");
        });
    }

    /**
     * Calls the package-private {@code doImport(Transferable, JTextComponent, int)} via reflection.
     */
    private static boolean callDoImportAt(FileDropHandler handler,
                                          java.awt.datatransfer.Transferable t,
                                          JTextArea area, int insertPos) {
        try {
            java.lang.reflect.Method m = FileDropHandler.class.getDeclaredMethod(
                    "doImport",
                    java.awt.datatransfer.Transferable.class,
                    javax.swing.text.JTextComponent.class,
                    int.class);
            m.setAccessible(true);
            return (boolean) m.invoke(handler, t, area, insertPos);
        } catch (Exception ex) {
            throw new RuntimeException("Could not invoke FileDropHandler.doImport(t, tc, pos)", ex);
        }
    }

    // -------------------------------------------------------------------------
    // Tests: Cut/Copy — export delegation to original TransferHandler
    // -------------------------------------------------------------------------

    @Test
    void getSourceActionsWithNullOriginalHandlerReturnsNone() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            // null originalHandler — should fall back to super (NONE = 0)
            int actions = handler.getSourceActions(area);
            assertEquals(0, actions,
                    "getSourceActions with null original must return NONE (0), got: " + actions);
        });
    }

    @Test
    void getSourceActionsWithOriginalHandlerDelegatesToOriginal() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextArea textArea = new JTextArea("hello");
            javax.swing.TransferHandler original = textArea.getTransferHandler();
            FileDropHandler h = new FileDropHandler(() -> workDir.getAbsolutePath(), original);
            textArea.setTransferHandler(h);
            int actions = h.getSourceActions(textArea);
            assertTrue(actions > 0,
                    "getSourceActions must delegate to original JTextArea handler, got: " + actions);
        });
    }

    // -------------------------------------------------------------------------
    // Tests: blank-node DnD rejected by canImport (Files-window phantom nodes)
    // -------------------------------------------------------------------------

    /**
     * Builds a Transferable that carries an {@code application/x-java-openide-nodednd} flavor
     * whose data is the supplied node (simulates NetBeans node DnD).
     */
    private static java.awt.datatransfer.Transferable nodeDndTransferable(Node node) {
        java.awt.datatransfer.DataFlavor flavor;
        try {
            flavor = new java.awt.datatransfer.DataFlavor(
                    "application/x-java-openide-nodednd;mask=1;class=org.openide.nodes.Node",
                    "NetBeans Node DnD", Thread.currentThread().getContextClassLoader());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        final java.awt.datatransfer.DataFlavor f = flavor;
        return new java.awt.datatransfer.Transferable() {
            @Override public java.awt.datatransfer.DataFlavor[] getTransferDataFlavors() { return new java.awt.datatransfer.DataFlavor[]{f}; }
            @Override public boolean isDataFlavorSupported(java.awt.datatransfer.DataFlavor fl) { return f.equals(fl); }
            @Override public Object getTransferData(java.awt.datatransfer.DataFlavor fl) { return node; }
        };
    }

    @Test
    void canImportBlankAbstractNodeReturnsFalse() throws Exception {
        // Blank AbstractNode — as produced by Files-window DnD: no FileObject/DataObject/Project in lookup
        Node blank = new AbstractNode(Children.LEAF);
        java.awt.datatransfer.Transferable t = nodeDndTransferable(blank);
        SwingUtilities.invokeAndWait(() ->
            assertFalse(callCanImportTransferable(handler, t),
                    "canImport must return false for blank AbstractNode (Files-window phantom)"));
    }

    /**
     * Calls the package-private {@code doImport(Transferable, JTextComponent, int)} via reflection,
     * using the current caret position as the insert position.
     */
    private static boolean callDoImport(FileDropHandler handler,
                                        java.awt.datatransfer.Transferable t,
                                        JTextArea area) {
        try {
            java.lang.reflect.Method m = FileDropHandler.class.getDeclaredMethod(
                    "doImport",
                    java.awt.datatransfer.Transferable.class,
                    javax.swing.text.JTextComponent.class,
                    int.class);
            m.setAccessible(true);
            return (boolean) m.invoke(handler, t, area, area.getCaretPosition());
        } catch (Exception ex) {
            throw new RuntimeException("Could not invoke FileDropHandler.doImport", ex);
        }
    }
}
