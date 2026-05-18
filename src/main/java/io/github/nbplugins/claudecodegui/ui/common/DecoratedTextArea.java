package io.github.nbplugins.claudecodegui.ui.common;

import java.awt.Graphics;
import java.util.List;
import java.util.function.Supplier;
import javax.swing.JPopupMenu;
import javax.swing.JTextArea;

/**
 * A {@link JTextArea} that has all shared input features pre-wired via
 * {@link TextComponentDecorator} and supports {@code @path} token highlighting
 * via {@link AtPathHighlighter}.
 *
 * <p>Features: Cut/Copy/Paste context menu with Favorites items, file DnD and
 * Ctrl+V paste, {@code @}-completion popup, favorites shortcut matching.
 */
public final class DecoratedTextArea extends JTextArea implements RangeHighlightable {

    private final TextComponentDecorator decorator;
    private List<int[]> ranges = List.of();

    /**
     * Creates a decorated text area with the given dimensions.
     *
     * @param rows       number of visible rows
     * @param cols       number of visible columns
     * @param wdSupplier supplies the current working directory path (may return {@code null})
     */
    public DecoratedTextArea(int rows, int cols, Supplier<String> wdSupplier) {
        super(rows, cols);
        decorator = new TextComponentDecorator(this, wdSupplier);
        decorator.setup();
    }

    @Override
    public void setRanges(List<int[]> r) {
        this.ranges = r;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        AtPathHighlighter.paintHighlights(g, this, ranges);
    }

    /**
     * Returns the context menu so callers may append additional items.
     *
     * @return the context menu
     */
    public JPopupMenu getContextMenu() {
        return decorator.getContextMenu();
    }

    /**
     * Releases resources (temp PNG files). Call when the area is no longer needed.
     */
    public void cleanup() {
        decorator.cleanup();
    }
}
