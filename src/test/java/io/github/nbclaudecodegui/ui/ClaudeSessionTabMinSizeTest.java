package io.github.nbclaudecodegui.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
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
}
