package io.github.nbclaudecodegui.ui;

import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link FileDiffOpener} static helpers.
 */
class FileDiffOpenerTest {

    @Test
    void fileInsideDirectory() {
        assertTrue(FileDiffOpener.isFileUnderDirectory("/home/user/project/src/Foo.java", "/home/user/project"));
    }

    @Test
    void fileIsDirectory() {
        assertTrue(FileDiffOpener.isFileUnderDirectory("/home/user/project", "/home/user/project"));
    }

    @Test
    void fileInSubdirectory() {
        assertTrue(FileDiffOpener.isFileUnderDirectory("/home/user/project/a/b/c/D.java", "/home/user/project"));
    }

    @Test
    void fileOutsideDirectory() {
        assertFalse(FileDiffOpener.isFileUnderDirectory("/home/user/other/Foo.java", "/home/user/project"));
    }

    @Test
    void prefixNotSeparator() {
        // "/home/user/project2" should NOT match dir "/home/user/project"
        assertFalse(FileDiffOpener.isFileUnderDirectory("/home/user/project2/Foo.java", "/home/user/project"));
    }

    @Test
    void nullFilePath() {
        assertFalse(FileDiffOpener.isFileUnderDirectory(null, "/home/user/project"));
    }

    @Test
    void nullDirPath() {
        assertFalse(FileDiffOpener.isFileUnderDirectory("/home/user/project/Foo.java", null));
    }

    @Test
    void bothNull() {
        assertFalse(FileDiffOpener.isFileUnderDirectory(null, null));
    }

    // --- cloneCheckItem ---

    @Test
    void cloneCheckItemSyncsSelectedStateFromOriginal() {
        JCheckBoxMenuItem original = new JCheckBoxMenuItem("X", false);
        original.addActionListener(e -> {});
        JCheckBoxMenuItem clone = FileDiffOpener.cloneCheckItemForTest(original);

        original.setSelected(true);
        assertTrue(clone.isSelected());

        original.setSelected(false);
        assertFalse(clone.isSelected());
    }

    // --- addPreviewItemToPopup ---

    @Test
    void addPreviewItemToPopupAddsItem() {
        JPopupMenu menu = menuWithCopy();
        JCheckBoxMenuItem previewItem = previewTemplate();

        FileDiffOpener.addPreviewItemToPopup(menu, previewItem);

        assertNotNull(findCheckItem(menu, "Preview Markdown"));
    }

    @Test
    void addPreviewItemToPopupNotDuplicated() {
        JPopupMenu menu = menuWithCopy();
        JCheckBoxMenuItem previewItem = previewTemplate();

        FileDiffOpener.addPreviewItemToPopup(menu, previewItem);
        FileDiffOpener.addPreviewItemToPopup(menu, previewItem);

        long count = countCheckItems(menu, "Preview Markdown");
        assertEquals(1, count, "Preview Markdown should appear exactly once");
    }

    @Test
    void addPreviewItemToPopupCloneSyncsWithOriginal() {
        JPopupMenu menu = menuWithCopy();
        JCheckBoxMenuItem previewItem = previewTemplate();

        FileDiffOpener.addPreviewItemToPopup(menu, previewItem);
        JCheckBoxMenuItem clone = findCheckItem(menu, "Preview Markdown");
        assertNotNull(clone);

        previewItem.setSelected(true);
        assertTrue(clone.isSelected());

        previewItem.setSelected(false);
        assertFalse(clone.isSelected());
    }

    @Test
    void addPreviewItemToPopupAddsSeparatorBeforeItem() {
        JPopupMenu menu = menuWithCopy();
        JCheckBoxMenuItem previewItem = previewTemplate();

        FileDiffOpener.addPreviewItemToPopup(menu, previewItem);

        // Separator must appear before the "Preview Markdown" item
        boolean seenSeparator = false;
        for (int i = 0; i < menu.getComponentCount(); i++) {
            Component c = menu.getComponent(i);
            if (c instanceof JPopupMenu.Separator) { seenSeparator = true; }
            if (c instanceof JCheckBoxMenuItem cb && "Preview Markdown".equals(cb.getText())) {
                assertTrue(seenSeparator, "Separator must precede the Preview Markdown item");
                return;
            }
        }
        fail("Preview Markdown item not found");
    }

    // --- savedVertDivider logic ---

    @Test
    void saveDividerLocationToArray() {
        javax.swing.JSplitPane split = new javax.swing.JSplitPane(javax.swing.JSplitPane.VERTICAL_SPLIT);
        split.setSize(400, 300);
        split.setDividerLocation(120);
        int[] savedVertDivider = {-1};

        // Simulate what toggleMdPreview(false) does: save divider to array
        FileDiffOpener.saveDividerForTest(split, savedVertDivider);

        assertEquals(120, savedVertDivider[0]);
    }

    @Test
    void saveDividerDoesNotSaveWhenHeightZero() {
        javax.swing.JSplitPane split = new javax.swing.JSplitPane(javax.swing.JSplitPane.VERTICAL_SPLIT);
        // No size set — height stays 0 (pre-layout state, garbage divider value)
        split.setDividerLocation(120);
        int[] savedVertDivider = {-1};

        FileDiffOpener.saveDividerForTest(split, savedVertDivider);

        assertEquals(-1, savedVertDivider[0], "Should not save when JSplitPane has no height yet");
    }

    @Test
    void saveDividerDoesNotSaveWhenLocNegative() {
        javax.swing.JSplitPane split = new javax.swing.JSplitPane(javax.swing.JSplitPane.VERTICAL_SPLIT);
        split.setSize(400, 300);
        // dividerLocation stays -1 (not yet explicitly set)
        int[] savedVertDivider = {-1};

        FileDiffOpener.saveDividerForTest(split, savedVertDivider);

        assertEquals(-1, savedVertDivider[0], "Should not save when divider location is -1");
    }

    @Test
    void restoreDividerFromArray() {
        javax.swing.JSplitPane split = new javax.swing.JSplitPane(javax.swing.JSplitPane.VERTICAL_SPLIT);
        split.setSize(400, 300);
        int[] savedVertDivider = {200};

        // Simulate what toggleMdPreview(true) does: restore divider from array
        FileDiffOpener.restoreDividerForTest(split, savedVertDivider);

        assertEquals(200, split.getDividerLocation());
    }

    // --- session tab lookup for hook case ---
    // The lookup condition is: isFileUnderDirectory(hookCwd, tabWorkingDir)
    // i.e. hookCwd starts with tabWorkingDir — tab's dir is ancestor of hookCwd.

    @Test
    void sessionTabMatchWhenHookCwdEqualsTabDir() {
        // hookCwd == tabWorkingDir: should match (equals is ancestor-or-equal)
        String tabDir  = "/home/user/project";
        String hookCwd = "/home/user/project";
        assertTrue(FileDiffOpener.isFileUnderDirectory(hookCwd, tabDir),
                "Tab dir equals hookCwd — must match");
    }

    @Test
    void sessionTabMatchWhenHookCwdIsSubdirOfTabDir() {
        // Bug case: Claude cd'd into a subdir; tab was opened at project root
        String tabDir  = "/home/user/project";
        String hookCwd = "/home/user/project/claude-launch-tests";
        assertTrue(FileDiffOpener.isFileUnderDirectory(hookCwd, tabDir),
                "hookCwd is a subdir of tabDir — must match");
    }

    @Test
    void sessionTabNoMatchWhenHookCwdUnrelated() {
        String tabDir  = "/home/user/project";
        String hookCwd = "/home/user/other";
        assertFalse(FileDiffOpener.isFileUnderDirectory(hookCwd, tabDir),
                "hookCwd is unrelated to tabDir — must not match");
    }

    @Test
    void sessionTabNoMatchWhenHookCwdIsParentOfTabDir() {
        // tabDir must be an ancestor of hookCwd, not the other way around
        String tabDir  = "/home/user/project/subdir";
        String hookCwd = "/home/user/project";
        assertFalse(FileDiffOpener.isFileUnderDirectory(hookCwd, tabDir),
                "tabDir is a child of hookCwd — must not match");
    }

    // --- outsideProject calculation after fix ---
    // After fix: outsideProject uses tabWorkingDir (not hookCwd) as project root.

    @Test
    void fileInsideTabDirNotOutsideProject() {
        // file is under tabWorkingDir → not outside project
        String tabDir  = "/home/user/project";
        String filePath = "/home/user/project/src/Foo.java";
        assertFalse(!FileDiffOpener.isFileUnderDirectory(filePath, tabDir));
    }

    @Test
    void fileInsideTabDirButOutsideHookCwd() {
        // Bug case: file under tabDir but not under hookCwd (subdir).
        // After fix we check against tabDir, so no warning.
        String tabDir   = "/home/user/project";
        String hookCwd  = "/home/user/project/claude-launch-tests";
        String filePath = "/home/user/project/src/Foo.java";
        boolean outsideHookCwd  = !FileDiffOpener.isFileUnderDirectory(filePath, hookCwd);
        boolean outsideTabDir   = !FileDiffOpener.isFileUnderDirectory(filePath, tabDir);
        assertTrue(outsideHookCwd,  "file IS outside hookCwd (subdir) — old logic would warn");
        assertFalse(outsideTabDir,  "file is NOT outside tabDir (project root) — new logic: no warn");
    }

    @Test
    void fileTrulyOutsideProject() {
        String tabDir  = "/home/user/project";
        String filePath = "/home/other/file.java";
        assertTrue(!FileDiffOpener.isFileUnderDirectory(filePath, tabDir),
                "file outside project root — should warn");
    }

    // --- helpers ---

    private static JPopupMenu menuWithCopy() {
        JPopupMenu menu = new JPopupMenu();
        menu.add(new JMenuItem("Copy"));
        return menu;
    }

    private static JCheckBoxMenuItem previewTemplate() {
        JCheckBoxMenuItem item = new JCheckBoxMenuItem("Preview Markdown", false);
        item.addActionListener(e -> {});
        return item;
    }

    private static JCheckBoxMenuItem findCheckItem(JPopupMenu menu, String text) {
        for (int i = 0; i < menu.getComponentCount(); i++) {
            Component c = menu.getComponent(i);
            if (c instanceof JCheckBoxMenuItem m && text.equals(m.getText())) return m;
        }
        return null;
    }

    private static long countCheckItems(JPopupMenu menu, String text) {
        long count = 0;
        for (int i = 0; i < menu.getComponentCount(); i++) {
            Component c = menu.getComponent(i);
            if (c instanceof JCheckBoxMenuItem m && text.equals(m.getText())) count++;
        }
        return count;
    }
}
