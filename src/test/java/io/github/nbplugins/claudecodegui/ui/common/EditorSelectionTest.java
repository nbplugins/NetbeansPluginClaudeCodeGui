package io.github.nbplugins.claudecodegui.ui.common;

import java.io.File;
import javax.swing.text.PlainDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link EditorSelection}: line computation from document offsets
 * and the {@code @path#L..} mention format understood by Claude Code.
 */
class EditorSelectionTest {

    @TempDir
    File tempDir;

    /** Lines: "l1\n" (0-2), "l2\n" (3-5), "l3\n" (6-8), "l4" (9-10). */
    private static PlainDocument doc() throws Exception {
        PlainDocument d = new PlainDocument();
        d.insertString(0, "l1\nl2\nl3\nl4", null);
        return d;
    }

    @Test
    void emptySelectionIsNull() throws Exception {
        assertNull(EditorSelection.of("/a/B.java", doc(), 4, 4));
    }

    @Test
    void documentWithoutFileIsNull() throws Exception {
        assertNull(EditorSelection.of(null, doc(), 0, 5));
    }

    @Test
    void partialSelectionWithinOneLine() throws Exception {
        EditorSelection s = EditorSelection.of("/a/B.java", doc(), 3, 4);
        assertEquals(2, s.startLine());
        assertEquals(2, s.endLine());
    }

    @Test
    void multiLineSelection() throws Exception {
        EditorSelection s = EditorSelection.of("/a/B.java", doc(), 1, 7);
        assertEquals(1, s.startLine());
        assertEquals(3, s.endLine());
    }

    @Test
    void selectionEndingAtColumnZeroExcludesThatLine() throws Exception {
        // Whole lines 2-3 selected: end offset is the start of line 4
        EditorSelection s = EditorSelection.of("/a/B.java", doc(), 3, 9);
        assertEquals(2, s.startLine());
        assertEquals(3, s.endLine());
    }

    @Test
    void wholeSingleLineSelectionStaysOneLine() throws Exception {
        // Line 2 selected including its newline: end at column 0 of line 3
        EditorSelection s = EditorSelection.of("/a/B.java", doc(), 3, 6);
        assertEquals(2, s.startLine());
        assertEquals(2, s.endLine());
    }

    @Test
    void mentionIsRelativeInsideWorkingDir() {
        File file = new File(tempDir, "src" + File.separator + "Foo.java");
        EditorSelection s = new EditorSelection(file.getAbsolutePath(), 12, 20);
        assertEquals("@src/Foo.java#L12-20", s.toMention(tempDir.getAbsolutePath()));
    }

    @Test
    void mentionForSingleLineHasNoRange() {
        File file = new File(tempDir, "Foo.java");
        EditorSelection s = new EditorSelection(file.getAbsolutePath(), 7, 7);
        assertEquals("@Foo.java#L7", s.toMention(tempDir.getAbsolutePath()));
    }

    @Test
    void mentionIsAbsoluteOutsideWorkingDir() {
        File other = new File(tempDir, "other");
        File file  = new File(tempDir, "project" + File.separator + "Foo.java");
        EditorSelection s = new EditorSelection(file.getAbsolutePath(), 1, 2);
        String expected = "@" + file.getAbsolutePath().replace('\\', '/') + "#L1-2";
        assertEquals(expected, s.toMention(other.getAbsolutePath()));
    }

    @Test
    void mentionIsAbsoluteWithoutWorkingDir() {
        File file = new File(tempDir, "Foo.java");
        EditorSelection s = new EditorSelection(file.getAbsolutePath(), 3, 4);
        String expected = "@" + file.getAbsolutePath().replace('\\', '/') + "#L3-4";
        assertEquals(expected, s.toMention(null));
    }

    @Test
    void siblingDirWithSamePrefixIsNotTreatedAsInside() {
        File wd   = new File(tempDir, "app");
        File file = new File(tempDir, "app-old" + File.separator + "Foo.java");
        EditorSelection s = new EditorSelection(file.getAbsolutePath(), 1, 1);
        assertTrue(s.toMention(wd.getAbsolutePath()).contains("app-old/Foo.java"),
                "Path must stay absolute for a sibling directory sharing the prefix");
        assertFalse(s.toMention(wd.getAbsolutePath()).startsWith("@-old"));
    }
}
