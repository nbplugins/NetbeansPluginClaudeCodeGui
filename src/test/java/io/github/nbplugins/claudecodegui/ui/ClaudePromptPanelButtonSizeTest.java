package io.github.nbplugins.claudecodegui.ui;

import java.awt.Component;
import java.awt.Dimension;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test: Favorites button icon and text must not wrap to separate lines.
 *
 * <p>The bug: the equalization block called setPreferredSize and setMaximumSize
 * but did NOT call setMinimumSize. Without an explicit minimumSize, the default
 * L&F minimum (much smaller than preferred) allowed layout managers to shrink
 * the button, causing the HTML-rendered icon (★) and text ("Favorites") to wrap
 * onto separate lines.
 *
 * <p>Fix: {@link ClaudePromptPanel#equalizeButtons(JButton[])} now calls
 * {@code setMinimumSize(eq)} alongside {@code setPreferredSize}/{@code setMaximumSize}.
 */
class ClaudePromptPanelButtonSizeTest {

    /**
     * Verifies that {@code equalizeButtons} sets preferredSize, minimumSize, and
     * maximumSize to the same value on all buttons.
     *
     * <p>Before the fix, only preferredSize and maximumSize were set, leaving
     * minimumSize at the small L&F default.
     */
    @Test
    void equalizeButtonsSetsMinimumSizeEqualToPreferredSize() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JButton history   = new JButton("\u2630 History");
            JButton favorites = new JButton("<html><font color='#CC6600'>\u2605</font> Favorites</html>");
            JButton send      = new JButton("<html><font color='#228B22'>\u25b6</font> Send</html>");
            JButton cancel    = new JButton("<html><font color='#B22222'>\u2716</font> Cancel</html>");

            // Show them in a frame so L&F can measure preferred sizes
            JFrame frame = new JFrame();
            javax.swing.JPanel p = new javax.swing.JPanel();
            p.add(history); p.add(favorites); p.add(send); p.add(cancel);
            frame.add(p);
            frame.setSize(600, 100);
            frame.setVisible(true);
            frame.validate();

            JButton[] buttons = {send, cancel, history, favorites};
            ClaudePromptPanel.equalizeButtons(buttons);

            Dimension minFav  = favorites.getMinimumSize();
            Dimension prefFav = favorites.getPreferredSize();
            Dimension maxFav  = favorites.getMaximumSize();

            // After equalization, min == pref == max for all buttons
            assertEquals(prefFav, minFav,
                    "After equalizeButtons, minimumSize must equal preferredSize for Favorites. "
                    + "Before fix: setMinimumSize was not called, leaving minimumSize at L&F default.");
            assertEquals(prefFav, maxFav,
                    "After equalizeButtons, maximumSize must equal preferredSize for Favorites.");

            // All four buttons must have identical sizes
            for (JButton b : buttons) {
                assertEquals(minFav, b.getMinimumSize(),
                        "All buttons must have equal minimumSize after equalization");
                assertEquals(prefFav, b.getPreferredSize(),
                        "All buttons must have equal preferredSize after equalization");
            }

            frame.dispose();
        });
    }

    /**
     * Verifies that History button minimumSize matches Favorites after equalization.
     *
     * <p>This is the concrete symptom observed: History had minimumSize ~78 (L&F default)
     * while Favorites preferred was ~104, so Favorites got squashed to 78 in a narrow layout.
     */
    @Test
    void historyAndFavoritesMinimumSizeAreEqualAfterEqualization() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JButton history   = new JButton("\u2630 History");
            JButton favorites = new JButton("<html><font color='#CC6600'>\u2605</font> Favorites</html>");

            JFrame frame = new JFrame();
            javax.swing.JPanel p = new javax.swing.JPanel();
            p.add(history); p.add(favorites);
            frame.add(p);
            frame.setSize(600, 100);
            frame.setVisible(true);
            frame.validate();

            // Before equalization: History minimumSize is L&F default (smaller than Favorites preferred)
            Dimension histMinBefore = history.getMinimumSize();
            Dimension favPrefBefore = favorites.getPreferredSize();
            // This documents the pre-fix state: min < pref for Favorites
            assertTrue(histMinBefore.width < favPrefBefore.width || !history.isMinimumSizeSet(),
                    "Before equalization, History minimumSize (" + histMinBefore.width
                    + ") should be smaller than Favorites preferredSize (" + favPrefBefore.width + ")");

            ClaudePromptPanel.equalizeButtons(new JButton[]{history, favorites});

            // After equalization: both must have the same minimumSize (= max of all preferredSizes)
            assertEquals(history.getMinimumSize(), favorites.getMinimumSize(),
                    "After equalizeButtons, History and Favorites must have the same minimumSize. "
                    + "History=" + history.getMinimumSize()
                    + ", Favorites=" + favorites.getMinimumSize());

            // And minimumSize must be >= any button's natural preferred width
            int minW = history.getMinimumSize().width;
            assertTrue(minW >= histMinBefore.width,
                    "Equalized minimumSize must be >= original preferred widths");

            frame.dispose();
        });
    }
}
