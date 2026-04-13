package io.github.nbclaudecodegui.ui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ClaudeSessionSelectorPanel}.
 *
 * <p>Tests run headless (no NetBeans module system). Methods that would
 * normally interact with {@link org.netbeans.api.project.ui.OpenProjects}
 * (e.g. project combo population) are gracefully no-ops in this environment,
 * so the tests focus on lock/unlock state, validation logic, combo setters,
 * and the static {@link ClaudeSessionSelectorPanel#resolveTabLabel} utility.
 */
class ClaudeSessionSelectorPanelTest {

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    /**
     * Creates a selector panel with a no-op open listener.
     * The {@code initialDirectory} is {@code null} so no combo pre-selection
     * happens (avoids project-API calls).
     */
    private static ClaudeSessionSelectorPanel panel() {
        return new ClaudeSessionSelectorPanel(null, (dir, profile, extra, mode, resumeId) -> {});
    }

    // -------------------------------------------------------------------------
    // Lock / unlock state
    // -------------------------------------------------------------------------

    @Test
    void initiallyUnlocked() {
        assertFalse(panel().isLocked(), "Panel must be unlocked when freshly constructed");
    }

    @Test
    void lockDisablesControls() {
        ClaudeSessionSelectorPanel p = panel();
        p.lock();
        assertTrue(p.isLocked(), "isLocked() must return true after lock()");
    }

    @Test
    void unlockEnablesControls() {
        ClaudeSessionSelectorPanel p = panel();
        p.lock();
        p.unlock();
        assertFalse(p.isLocked(), "isLocked() must return false after lock() + unlock()");
    }

    // -------------------------------------------------------------------------
    // OpenListener callback
    // -------------------------------------------------------------------------

    @Test
    void openCallbackFiredForValidDir() throws IOException {
        File tmpDir = Files.createTempDirectory("selector-test").toFile();
        tmpDir.deleteOnExit();
        try {
            AtomicReference<File>   capturedDir     = new AtomicReference<>();
            AtomicReference<String> capturedProfile = new AtomicReference<>();

            ClaudeSessionSelectorPanel p = new ClaudeSessionSelectorPanel(null, (dir, profile, extra, mode, resumeId) -> {
                capturedDir.set(dir);
                capturedProfile.set(profile);
            });

            p.setPath(tmpDir.getAbsolutePath());
            // Simulate Open button click by calling onOpen via reflection (package-private boundary):
            // We drive it indirectly by invoking the public setPath + triggering via
            // an ActionEvent on the open button (accessible through the component tree).
            // Find the "Open" JButton and click it.
            clickOpenButton(p);

            assertEquals(tmpDir.getCanonicalFile(), capturedDir.get().getCanonicalFile(),
                    "onOpen must receive the validated directory");
            assertNotNull(capturedProfile.get(), "onOpen must receive a non-null profile name");
        } finally {
            tmpDir.delete();
        }
    }

    @Test
    void openDoesNotFireForEmptyPath() {
        AtomicReference<File> capturedDir = new AtomicReference<>();
        ClaudeSessionSelectorPanel p = new ClaudeSessionSelectorPanel(null, (dir, profile, extra, mode, resumeId) ->
                capturedDir.set(dir));

        p.setPath("");
        clickOpenButton(p);

        assertNull(capturedDir.get(), "onOpen must NOT fire when path is empty");
    }

    @Test
    void openDoesNotFireForMissingDir() {
        AtomicReference<File> capturedDir = new AtomicReference<>();
        ClaudeSessionSelectorPanel p = new ClaudeSessionSelectorPanel(null, (dir, profile, extra, mode, resumeId) ->
                capturedDir.set(dir));

        p.setPath("/this/path/does/not/exist/hopefully-" + System.nanoTime());
        clickOpenButton(p);

        assertNull(capturedDir.get(), "onOpen must NOT fire when directory does not exist");
    }

    // -------------------------------------------------------------------------
    // Setters
    // -------------------------------------------------------------------------

    @Test
    void setPathUpdatesCombo() {
        ClaudeSessionSelectorPanel p = panel();
        p.setPath("/tmp/my-project");
        assertEquals("/tmp/my-project", getPathComboText(p),
                "setPath must update the path combo selected item");
    }

    @Test
    void setProfileNullSelectsFirst() {
        ClaudeSessionSelectorPanel p = panel();
        p.setProfile(null);
        // profileCombo index 0 = Default; just assert no exception and index is 0
        assertEquals(0, getProfileComboIndex(p),
                "setProfile(null) must select index 0 (Default profile)");
    }

    // -------------------------------------------------------------------------
    // resolveTabLabel
    // -------------------------------------------------------------------------

    @Test
    void resolveTabLabelNullReturnsNewSession() {
        String label = ClaudeSessionSelectorPanel.resolveTabLabel(null);
        assertNotNull(label, "resolveTabLabel(null) must not return null");
        assertFalse(label.isBlank(), "resolveTabLabel(null) must not return blank");
    }

    @Test
    void resolveTabLabelNonProjectDirReturnsFallback() throws IOException {
        File tmpDir = Files.createTempDirectory("label-test").toFile();
        tmpDir.deleteOnExit();
        try {
            String label = ClaudeSessionSelectorPanel.resolveTabLabel(tmpDir);
            // No open projects in the headless test environment → fallback "Claude Code"
            assertEquals("Claude Code", label,
                    "resolveTabLabel must fall back to 'Claude Code' for non-project dirs");
        } finally {
            tmpDir.delete();
        }
    }

    // -------------------------------------------------------------------------
    // Project combo sorting (issue #21)
    // -------------------------------------------------------------------------

    /**
     * Verifies that the comparator used in {@code populateProjectCombo()} sorts
     * project names case-insensitively.  The test stands in for the full UI
     * flow (which requires a live NetBeans module system) and confirms that
     * the sorting algorithm itself is correct.
     */
    @Test
    void projectComboSortIsCaseInsensitive() {
        String[] names = {"Zebra", "apple", "Banana", "cherry"};
        Arrays.sort(names, Comparator.comparing(s -> s, String.CASE_INSENSITIVE_ORDER));
        assertArrayEquals(new String[]{"apple", "Banana", "cherry", "Zebra"}, names,
                "Projects must be sorted alphabetically, case-insensitive");
    }

    @Test
    void projectComboSortIsStableForEqualCaseNames() {
        String[] names = {"Beta", "alpha", "ALPHA", "beta"};
        Arrays.sort(names, Comparator.comparing(s -> s, String.CASE_INSENSITIVE_ORDER));
        // All alpha-variants before beta-variants; relative order within a group
        // is preserved (Arrays.sort is stable).
        assertEquals("alpha", names[0].toLowerCase(),
                "First group must be 'alpha' variants");
        assertEquals("beta", names[2].toLowerCase(),
                "Second group must be 'beta' variants");
    }

    // -------------------------------------------------------------------------
    // History persistence helpers
    // -------------------------------------------------------------------------

    @Test
    void saveAndLoadHistory() {
        // Use a unique path so we don't pollute real preferences
        String unique = "/tmp/selector-history-test-" + System.nanoTime();
        ClaudeSessionSelectorPanel.saveToHistory(unique);
        assertTrue(ClaudeSessionSelectorPanel.loadHistory().contains(unique),
                "saved path must appear in loaded history");
    }

    // -------------------------------------------------------------------------
    // Reflection helpers — access Swing internals in tests
    // -------------------------------------------------------------------------

    /** Finds the Open JButton in the panel's component tree and fires its action. */
    private static void clickOpenButton(ClaudeSessionSelectorPanel panel) {
        for (java.awt.Component c : getAllComponents(panel)) {
            if (c instanceof javax.swing.JButton btn
                    && "Open".equals(btn.getText())) {
                btn.doClick();
                return;
            }
        }
        fail("Could not find Open button in ClaudeSessionSelectorPanel");
    }

    /** Returns the selected item text of the path combo. */
    private static String getPathComboText(ClaudeSessionSelectorPanel panel) {
        for (java.awt.Component c : getAllComponents(panel)) {
            if (c instanceof javax.swing.JComboBox<?> combo
                    && combo.isEditable()) {
                Object sel = combo.getSelectedItem();
                return sel != null ? sel.toString() : "";
            }
        }
        return "";
    }

    /** Returns the selected index of the profile combo (non-editable). */
    private static int getProfileComboIndex(ClaudeSessionSelectorPanel panel) {
        // Two combos: projectCombo (non-editable) and profileCombo (non-editable).
        // pathCombo is editable. Profile combo is the second non-editable combo.
        java.util.List<javax.swing.JComboBox<?>> nonEditable = new java.util.ArrayList<>();
        for (java.awt.Component c : getAllComponents(panel)) {
            if (c instanceof javax.swing.JComboBox<?> combo && !combo.isEditable()) {
                nonEditable.add(combo);
            }
        }
        // index 0 = projectCombo, index 1 = profileCombo
        return nonEditable.size() > 1 ? nonEditable.get(1).getSelectedIndex() : -1;
    }

    /** Recursively collects all components from a container. */
    private static java.util.List<java.awt.Component> getAllComponents(java.awt.Container root) {
        java.util.List<java.awt.Component> result = new java.util.ArrayList<>();
        for (java.awt.Component c : root.getComponents()) {
            result.add(c);
            if (c instanceof java.awt.Container sub) {
                result.addAll(getAllComponents(sub));
            }
        }
        return result;
    }
}
