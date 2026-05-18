package io.github.nbplugins.claudecodegui.ui.common;

import io.github.nbplugins.claudecodegui.ui.common.AtCompletionPopup;
import java.io.File;
import java.lang.reflect.Method;
import javax.swing.DefaultListModel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

/**
 * Unit tests for {@link AtCompletionPopup} — one-level directory navigation.
 *
 * <p>Tests use a real {@code @TempDir} so that on-demand {@code listFiles()} in
 * {@code updatePopup()} can discover actual filesystem entries.
 */
class AtCompletionPopupTest {

    @TempDir
    File workDir;

    private JTextArea area;
    private AtCompletionPopup popup;
    private DefaultListModel<String> listModel;

    @BeforeEach
    void setUp() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            area  = new JTextArea();
            popup = AtCompletionPopup.install(area, () -> workDir.getAbsolutePath());
        });
        listModel = getListModel(popup);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static DefaultListModel<String> getListModel(AtCompletionPopup p) throws Exception {
        java.lang.reflect.Field f = AtCompletionPopup.class.getDeclaredField("listModel");
        f.setAccessible(true);
        return (DefaultListModel<String>) f.get(p);
    }

    private void callUpdatePopup() throws Exception {
        Method m = AtCompletionPopup.class.getDeclaredMethod("updatePopup");
        m.setAccessible(true);
        SwingUtilities.invokeAndWait(() -> {
            try { m.invoke(popup); }
            catch (java.lang.reflect.InvocationTargetException ex) {
                // In headless tests, modelToView2D returns null (no visible frame) —
                // the list model is populated before that call so this is expected.
                Throwable cause = ex.getCause();
                if (!(cause instanceof NullPointerException)) {
                    throw new RuntimeException(ex);
                }
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
    }

    private List<String> listItems() {
        List<String> result = new java.util.ArrayList<>();
        for (int i = 0; i < listModel.getSize(); i++) result.add(listModel.getElementAt(i));
        return result;
    }

    // -------------------------------------------------------------------------
    // Tests: root level — only top-level items shown, ".." always present
    // -------------------------------------------------------------------------

    @Test
    void atRootShowsTopLevelDirsAndFiles() throws Exception {
        // Create a couple of files and directories in workDir
        new File(workDir, "src").mkdirs();
        new File(workDir, "pom.xml").createNewFile();
        new File(workDir, "README.md").createNewFile();

        SwingUtilities.invokeAndWait(() -> area.setText("@"));

        callUpdatePopup();

        List<String> items = listItems();
        assertTrue(items.contains(".."), "'..' must be shown even at root level");
        assertTrue(items.contains("src/"), "top-level dir 'src/' must appear");
        assertTrue(items.contains("pom.xml"), "top-level file must appear");
        assertTrue(items.contains("README.md"), "top-level file must appear");
    }

    @Test
    void atSubdirShowsOnlyImmediateChildren() throws Exception {
        // Create src/main/java/ and src/main/resources/ — this is the key regression test
        new File(workDir, "src/main/java").mkdirs();
        new File(workDir, "src/main/resources").mkdirs();
        new File(workDir, "src/test/java").mkdirs();

        SwingUtilities.invokeAndWait(() -> area.setText("@src/main/"));

        callUpdatePopup();

        List<String> items = listItems();
        assertTrue(items.contains(".."), "'..' must be present when inside subdir");
        assertTrue(items.contains("java/"), "'java/' dir must appear");
        assertTrue(items.contains("resources/"), "'resources/' dir must appear");
        assertFalse(items.stream().anyMatch(s -> s.contains("java/") && !s.equals("java/")),
                "nested paths must not be shown");
    }

    @Test
    void srcMainJavaAndResourcesAreVisible() throws Exception {
        // Regression: pre-scan with MAX_DEPTH=5 missed these because there were
        // no files (only dirs) up to that depth in a typical project layout.
        new File(workDir, "src/main/java/com/example").mkdirs();
        new File(workDir, "src/main/resources/META-INF").mkdirs();
        new File(workDir, "src/main/nbm/manifest.mf").getParentFile().mkdirs();
        new File(workDir, "src/main/nbm/manifest.mf").createNewFile();

        // At @src/main/ both java/ and resources/ must be visible
        SwingUtilities.invokeAndWait(() -> area.setText("@src/main/"));

        callUpdatePopup();

        List<String> items = listItems();
        assertTrue(items.contains("java/"), "'java/' must appear under src/main/");
        assertTrue(items.contains("resources/"), "'resources/' must appear under src/main/");
        assertTrue(items.contains("nbm/"), "'nbm/' must appear under src/main/");
    }

    @Test
    void filterNarrowsResults() throws Exception {
        new File(workDir, "src/main").mkdirs();
        new File(workDir, "src/main/Foo.java").createNewFile();
        new File(workDir, "src/main/Bar.java").createNewFile();

        SwingUtilities.invokeAndWait(() -> area.setText("@src/main/F"));

        callUpdatePopup();

        List<String> items = listItems();
        assertTrue(items.contains("Foo.java"), "Foo.java must match filter 'F'");
        assertFalse(items.contains("Bar.java"), "Bar.java must NOT match filter 'F'");
    }

    // -------------------------------------------------------------------------
    // Tests: parentOf — navigation helper
    // -------------------------------------------------------------------------

    private static String callParentOf(String currentDir) throws Exception {
        Method m = AtCompletionPopup.class.getDeclaredMethod("parentOf", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, currentDir);
    }

    @Test
    void parentOfEmptyReturnsParentDotDot() throws Exception {
        assertEquals("../", callParentOf(""), "parentOf(\"\") must return \"../\" to go above workdir");
    }

    @Test
    void parentOfDotDotReturnsDoubleDotDot() throws Exception {
        assertEquals("../../", callParentOf("../"), "parentOf(\"../\") must return \"../../\"");
    }

    @Test
    void parentOfSubdirReturnsRoot() throws Exception {
        assertEquals("", callParentOf("a/"), "parentOf(\"a/\") must return \"\" (back to workdir root)");
    }

    @Test
    void parentOfNestedSubdirReturnsParent() throws Exception {
        assertEquals("a/", callParentOf("a/b/"), "parentOf(\"a/b/\") must return \"a/\"");
    }

    // -------------------------------------------------------------------------
    // Tests: no-match hides popup
    // -------------------------------------------------------------------------

    @Test
    void noMatchHidesPopup() throws Exception {
        new File(workDir, "src").mkdirs();
        new File(workDir, "pom.xml").createNewFile();

        SwingUtilities.invokeAndWait(() -> area.setText("@zzznomatch"));

        callUpdatePopup();

        assertEquals(0, listModel.getSize(), "listModel must be empty when no paths match the filter");
    }

    @Test
    void directoriesListedBeforeFiles() throws Exception {
        new File(workDir, "src").mkdirs();
        new File(workDir, "pom.xml").createNewFile();

        SwingUtilities.invokeAndWait(() -> area.setText("@"));

        callUpdatePopup();

        List<String> items = listItems();
        int dotDotIdx = items.indexOf("..");
        int srcIdx    = items.indexOf("src/");
        int pomIdx    = items.indexOf("pom.xml");

        assertTrue(dotDotIdx >= 0, "'..' must be present");
        assertTrue(srcIdx    >= 0, "'src/' must be present");
        assertTrue(pomIdx    >= 0, "'pom.xml' must be present");
        assertTrue(dotDotIdx < srcIdx, "'..' must come before 'src/'");
        assertTrue(srcIdx    < pomIdx, "directories must come before files");
    }
}
