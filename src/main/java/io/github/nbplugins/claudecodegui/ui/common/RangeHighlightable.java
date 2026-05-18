package io.github.nbplugins.claudecodegui.ui.common;

import java.util.List;

/**
 * Implemented by text components that support {@code @path} token range highlighting.
 */
public interface RangeHighlightable {

    /**
     * Sets the current highlighted ranges.
     *
     * @param ranges list of {@code [start, end)} character-offset pairs
     */
    void setRanges(List<int[]> ranges);
}
