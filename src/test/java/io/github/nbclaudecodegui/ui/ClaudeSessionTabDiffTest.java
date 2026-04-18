package io.github.nbclaudecodegui.ui;

import java.awt.CardLayout;
import java.awt.Component;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the two embedded-diff bugs fixed in 0.22.2:
 * <ol>
 *   <li>Stale {@code CARD_DIFF} accumulates in the CardLayout when
 *       {@link ClaudeSessionTab#showEmbeddedDiff} is called multiple times.</li>
 *   <li>Wrong divider position when the diff card first appears ({@link
 *       ClaudeSessionTab#computeBottomHeight} default logic).</li>
 * </ol>
 */
class ClaudeSessionTabDiffTest {

    // -------------------------------------------------------------------------
    // Bug 1 — computeBottomHeight default values
    // -------------------------------------------------------------------------

    @Test
    void computeBottomHeight_savedValue_returnsSaved() {
        assertEquals(123, ClaudeSessionTab.computeBottomHeight("diff", 900, 123, -1, 50));
    }

    @Test
    void computeBottomHeight_diffCard_noSaved_returnsTwoThirdsTotal() {
        int total = 900;
        int expected = total * 2 / 3;  // 600
        assertEquals(expected, ClaudeSessionTab.computeBottomHeight("diff", total, -1, -1, 50));
    }

    @Test
    void computeBottomHeight_promptCard_noSaved_legacyPresent_returnsLegacy() {
        assertEquals(200, ClaudeSessionTab.computeBottomHeight("prompt", 900, -1, 200, 50));
    }

    @Test
    void computeBottomHeight_promptCard_noSaved_noLegacy_returnsPreferred() {
        assertEquals(50, ClaudeSessionTab.computeBottomHeight("prompt", 900, -1, -1, 50));
    }

    @Test
    void computeBottomHeight_diffCard_savedZeroIgnored_returnsTwoThirds() {
        // saved == 0 is treated as "not saved" (condition is saved > 0)
        int total = 600;
        assertEquals(total * 2 / 3, ClaudeSessionTab.computeBottomHeight("diff", total, 0, -1, 50));
    }

    // -------------------------------------------------------------------------
    // getSavedCardKeyFor
    // -------------------------------------------------------------------------

    @Test
    void savedCardKey_promptCard_returnsBackwardCompatKey() {
        ClaudeSessionTab tab = new ClaudeSessionTab();
        assertEquals("bottomHeight", tab.getSavedCardKeyFor("prompt"));
    }

    @Test
    void savedCardKey_nullCard_returnsBackwardCompatKey() {
        ClaudeSessionTab tab = new ClaudeSessionTab();
        assertEquals("bottomHeight", tab.getSavedCardKeyFor(null));
    }

    @Test
    void savedCardKey_diffCard_returnsDiffHeight() {
        ClaudeSessionTab tab = new ClaudeSessionTab();
        assertEquals("diffHeight", tab.getSavedCardKeyFor("diff"));
    }

    // -------------------------------------------------------------------------
    // Bug 2 — stale CARD_DIFF accumulates in CardLayout
    // -------------------------------------------------------------------------

    /**
     * Sets up a minimal south-card environment on a tab (without starting a PTY)
     * so that {@link ClaudeSessionTab#showEmbeddedDiff} can be called.
     * {@code switchSouthCard} is overridden to a no-op to avoid needing a full
     * JediTerm widget and splitPane.
     */
    private ClaudeSessionTab tabWithSouthCard() {
        ClaudeSessionTab tab = new ClaudeSessionTab() {
            @Override
            void switchSouthCard(String card) {
                // no-op: avoids splitPane / terminalWidget dependency in tests
                activeCard = card;
            }
        };
        CardLayout layout = new CardLayout();
        JPanel south = new JPanel(layout);
        tab.southCard = south;
        tab.southCardLayout = layout;
        tab.activeCard = "prompt";
        return tab;
    }

    @Test
    void showEmbeddedDiff_firstCall_addsDiffCard() {
        ClaudeSessionTab tab = tabWithSouthCard();

        boolean shown = tab.showEmbeddedDiff(false, null, null,
                new JLabel("diff1"),
                new FileDiffPermissionPanel(() -> {}, r -> {}, () -> {}, () -> {}, null),
                null);

        assertTrue(shown, "showEmbeddedDiff must return true when southCard is set");
        assertNotNull(tab.currentDiffCard, "currentDiffCard must be set after first call");
        // southCard now contains exactly one CARD_DIFF panel
        assertEquals(1, countComponents(tab.southCard));
    }

    @Test
    void showEmbeddedDiff_secondCall_replacesOldCard() {
        ClaudeSessionTab tab = tabWithSouthCard();

        tab.showEmbeddedDiff(false, null, null,
                new JLabel("diff1"),
                new FileDiffPermissionPanel(() -> {}, r -> {}, () -> {}, () -> {}, null),
                null);
        JPanel firstCard = tab.currentDiffCard;

        tab.showEmbeddedDiff(false, null, null,
                new JLabel("diff2"),
                new FileDiffPermissionPanel(() -> {}, r -> {}, () -> {}, () -> {}, null),
                null);
        JPanel secondCard = tab.currentDiffCard;

        assertNotSame(firstCard, secondCard, "currentDiffCard must be replaced on second call");
        // Only the new card should remain in southCard — no stale accumulation
        assertEquals(1, countComponents(tab.southCard),
                "southCard must contain exactly one diff card after second showEmbeddedDiff");
        assertFalse(isChildOf(tab.southCard, firstCard),
                "Old diff card must be removed from southCard");
        assertTrue(isChildOf(tab.southCard, secondCard),
                "New diff card must be present in southCard");
    }

    @Test
    void hideEmbeddedDiff_removesCardAndClearsField() {
        ClaudeSessionTab tab = tabWithSouthCard();

        tab.showEmbeddedDiff(false, null, null,
                new JLabel("diff"),
                new FileDiffPermissionPanel(() -> {}, r -> {}, () -> {}, () -> {}, null),
                null);

        tab.hideEmbeddedDiff();

        assertNull(tab.currentDiffCard, "currentDiffCard must be null after hide");
        assertEquals(0, countComponents(tab.southCard),
                "southCard must have no diff card after hide");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static int countComponents(JPanel panel) {
        return panel.getComponentCount();
    }

    private static boolean isChildOf(JPanel parent, Component child) {
        for (int i = 0; i < parent.getComponentCount(); i++) {
            if (parent.getComponent(i) == child) return true;
        }
        return false;
    }
}
