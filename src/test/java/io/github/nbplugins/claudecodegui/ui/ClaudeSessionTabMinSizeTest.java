package io.github.nbplugins.claudecodegui.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JSplitPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for prompt-area resize behaviour.
 *
 * <p>Issue #19: the session tab must be resizable below ~600 px when docked as
 * a side panel — fixed by returning (0, 0) from {@code southCard.getMinimumSize()}.
 *
 * <p>Follow-up fix (scroll-lock): the prompt area must not be collapsible to
 * zero height — fixed by having {@code ClaudePromptPanel.getMinimumSize()}
 * return the height of its button columns, and by having {@code southCard}
 * delegate to the visible child's minimum when {@code CARD_PROMPT} is active.
 */
class ClaudeSessionTabMinSizeTest {

    /**
     * Verifies that {@code southCard}'s minimum size is (0, 0) when a non-prompt
     * card is active (e.g. CARD_CHOICE), so JSplitPane does not enforce a large
     * lower bound on the tab height (issue #19).
     */
    @Test
    void southCardMinimumSizeIsZeroForChoiceCard() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            String[] activeCard = {"choice"};
            java.awt.CardLayout cardLayout = new java.awt.CardLayout();
            JPanel southCard = new JPanel(cardLayout) {
                @Override public Dimension getMinimumSize() {
                    if ("prompt".equals(activeCard[0])) {
                        for (java.awt.Component c : getComponents()) {
                            if (c.isVisible()) return new Dimension(0, c.getMinimumSize().height);
                        }
                    }
                    return new Dimension(0, 0);
                }
            };

            JPanel choiceChild = new JPanel() {
                @Override public Dimension getMinimumSize()   { return new Dimension(400, 600); }
                @Override public Dimension getPreferredSize() { return new Dimension(400, 600); }
            };
            southCard.add(choiceChild, "choice");
            cardLayout.show(southCard, "choice");

            Dimension min = southCard.getMinimumSize();
            assertEquals(0, min.width,  "southCard min width must be 0 for choice card (issue #19)");
            assertEquals(0, min.height, "southCard min height must be 0 for choice card (issue #19)");
        });
    }

    /**
     * Verifies that {@code southCard}'s minimum height is driven by the visible
     * child when {@code CARD_PROMPT} is active — so the divider cannot be
     * dragged below the button column height.
     */
    @Test
    void southCardMinimumHeightIsFromChildWhenPromptCardActive() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            String[] activeCard = {"prompt"};
            java.awt.CardLayout cardLayout = new java.awt.CardLayout();
            JPanel southCard = new JPanel(cardLayout) {
                @Override public Dimension getMinimumSize() {
                    if ("prompt".equals(activeCard[0])) {
                        for (java.awt.Component c : getComponents()) {
                            if (c.isVisible()) return new Dimension(0, c.getMinimumSize().height);
                        }
                    }
                    return new Dimension(0, 0);
                }
            };

            JPanel promptChild = new JPanel() {
                @Override public Dimension getMinimumSize()   { return new Dimension(400, 42); }
                @Override public Dimension getPreferredSize() { return new Dimension(400, 100); }
            };
            southCard.add(promptChild, "prompt");
            cardLayout.show(southCard, "prompt");

            Dimension min = southCard.getMinimumSize();
            assertEquals(0,  min.width,  "southCard min width must be 0 even for prompt card");
            assertEquals(42, min.height, "southCard min height must match button column height when prompt card active");
        });
    }

    /**
     * Verifies that {@code ClaudePromptPanel.getMinimumSize()} returns a height
     * driven by the EAST/WEST button columns, not the CENTER scroll pane.
     */
    @Test
    void promptPanel_minimumHeight_isDrivenByButtonColumns() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel promptPanel = new JPanel(new BorderLayout()) {
                @Override
                public Dimension getMinimumSize() {
                    BorderLayout layout = (BorderLayout) getLayout();
                    java.awt.Component east = layout.getLayoutComponent(BorderLayout.EAST);
                    java.awt.Component west = layout.getLayoutComponent(BorderLayout.WEST);
                    int minH = 0;
                    if (east != null) minH = Math.max(minH, east.getMinimumSize().height);
                    if (west != null) minH = Math.max(minH, west.getMinimumSize().height);
                    java.awt.Insets ins = getInsets();
                    return new Dimension(0, minH + ins.top + ins.bottom);
                }
            };

            // CENTER: large textarea (should NOT drive minimum)
            JPanel center = new JPanel() {
                @Override public Dimension getMinimumSize() { return new Dimension(400, 600); }
            };
            // EAST: button column
            JPanel east = new JPanel() {
                @Override public Dimension getMinimumSize() { return new Dimension(30, 55); }
            };
            // WEST: button column
            JPanel west = new JPanel() {
                @Override public Dimension getMinimumSize() { return new Dimension(30, 40); }
            };

            promptPanel.add(center, BorderLayout.CENTER);
            promptPanel.add(east,   BorderLayout.EAST);
            promptPanel.add(west,   BorderLayout.WEST);

            Dimension min = promptPanel.getMinimumSize();
            assertEquals(0, min.width, "promptPanel minimum width must be 0");
            assertTrue(min.height >= 55,
                    "promptPanel minimum height must be >= east button column height (55), got " + min.height);
            assertTrue(min.height < 600,
                    "promptPanel minimum height must not be driven by CENTER textarea (600), got " + min.height);
        });
    }

    /**
     * Verifies that JSplitPane respects a (0,0) minimum on the bottom component
     * and does not enforce the child's own large minimum size.
     */
    @Test
    void splitPaneAllowsCollapseWhenMinSizeIsZero() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel bottomPanel = new JPanel() {
                @Override public Dimension getMinimumSize() { return new Dimension(0, 0); }
            };

            JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                    new JPanel(), bottomPanel);
            splitPane.setResizeWeight(1.0);

            // The minimum size of the split pane should allow it to be very small.
            Dimension min = splitPane.getMinimumSize();
            assertTrue(min.height < 100,
                    "JSplitPane minimum height must be small when bottom component min size is (0,0) — " +
                    "got " + min.height + " px; large value means resize is blocked (issue #19)");
        });
    }

    /**
     * Regression for issue #176: the model dropdown's minimum width must be capped (here at
     * 100px, mirroring {@code ClaudeSessionTab.MODEL_COMBO_MIN_WIDTH}) whenever its content
     * would otherwise demand more — including a misparsed, oversized entry — so the panel can
     * always be shrunk. But the cap must never exceed the combo's own (content-driven, dynamic)
     * preferred width, so the combo still grows to show genuinely short content in full, and
     * still grows further when the window is widened, up to its {@code setMaximumSize} cap.
     */
    @Test
    void modelComboMinimumWidthTracksContentButIsCappedAt100() throws Exception {
        final int cap = 100;
        SwingUtilities.invokeAndWait(() -> {
            JComboBox<String> combo = new JComboBox<>() {
                @Override
                public Dimension getMinimumSize() {
                    Dimension pref = getPreferredSize();
                    return new Dimension(Math.min(pref.width, cap), pref.height);
                }
            };

            // Short content: minimum should track the (smaller) preferred width, not the cap.
            combo.setModel(new DefaultComboBoxModel<>(new String[]{"Opus 5.5"}));
            int shortPrefWidth = combo.getPreferredSize().width;
            assertTrue(shortPrefWidth < cap,
                    "test assumption broken: short item's preferred width (" + shortPrefWidth +
                    ") should be under the cap (" + cap + ")");
            assertEquals(shortPrefWidth, combo.getMinimumSize().width,
                    "minimum width must equal the (smaller) preferred width for short content, " +
                    "so the combo isn't forced wider than its content needs");

            // Long / misparsed content: minimum must be capped at 100, not follow the huge preferred width.
            String longItem = "Opus 5.5 ✔             For complex work and everyday tasks describing the model in detail";
            combo.setModel(new DefaultComboBoxModel<>(new String[]{longItem}));
            int longPrefWidth = combo.getPreferredSize().width;
            assertTrue(longPrefWidth > cap,
                    "test assumption broken: long item's preferred width (" + longPrefWidth +
                    ") should exceed the cap (" + cap + ")");
            assertEquals(cap, combo.getMinimumSize().width,
                    "minimum width must be capped at 100 even though preferred width (" + longPrefWidth +
                    ") is much larger (issue #176) — otherwise the panel can't be shrunk");
            assertEquals(longPrefWidth, combo.getPreferredSize().width,
                    "preferred width itself must stay content-driven (uncapped) so the combo can still " +
                    "grow to show the full content when the window is widened");
        });
    }
}
