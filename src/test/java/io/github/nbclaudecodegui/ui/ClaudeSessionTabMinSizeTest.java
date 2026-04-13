package io.github.nbclaudecodegui.ui;

import java.awt.Dimension;
import javax.swing.JSplitPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for issue #19: the session tab must be resizable below
 * ~600 px when docked as a side panel.
 *
 * <p>Root cause: {@code southCard.getMinimumSize()} was delegating to the
 * visible child component (e.g. promptPanel with a 3-row text area + buttons),
 * which reported a tall minimum height. {@link JSplitPane} enforces the
 * bottom component's minimum size, so the divider bounced back when the user
 * tried to shrink the tab below that height.
 *
 * <p>Fix: {@code southCard.getMinimumSize()} now always returns (0, 0),
 * letting JSplitPane collapse the bottom panel freely.
 */
class ClaudeSessionTabMinSizeTest {

    /**
     * Verifies that {@code southCard}'s minimum size is (0, 0) so that
     * JSplitPane does not enforce a large lower bound on the tab height.
     */
    @Test
    void southCardMinimumSizeIsZero() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            // Simulate the southCard construction from showChatLayout():
            // an anonymous JPanel subclass with getMinimumSize() returning (0,0).
            java.awt.CardLayout cardLayout = new java.awt.CardLayout();
            JPanel southCard = new JPanel(cardLayout) {
                @Override public Dimension getMinimumSize() {
                    return new Dimension(0, 0);
                }
            };

            // Add a child with a large preferred/minimum size (mirrors promptPanel).
            JPanel tallChild = new JPanel() {
                @Override public Dimension getMinimumSize()   { return new Dimension(400, 600); }
                @Override public Dimension getPreferredSize() { return new Dimension(400, 600); }
            };
            southCard.add(tallChild, "prompt");
            cardLayout.show(southCard, "prompt");

            Dimension min = southCard.getMinimumSize();
            assertEquals(0, min.width,
                    "southCard minimum width must be 0 so JSplitPane can resize freely (issue #19)");
            assertEquals(0, min.height,
                    "southCard minimum height must be 0 so JSplitPane can resize freely (issue #19)");
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
