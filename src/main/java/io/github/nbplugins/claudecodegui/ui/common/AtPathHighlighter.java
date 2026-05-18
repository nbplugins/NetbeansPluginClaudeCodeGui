package io.github.nbplugins.claudecodegui.ui.common;

import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.JTextComponent;

/**
 * Highlights {@code @path} tokens in a {@link JTextComponent} with a blue foreground.
 *
 * <p>The component must also implement {@link RangeHighlightable} and override
 * {@code paintComponent()} to call {@link #paintHighlights} after the default
 * rendering, ensuring the colour always appears on top of default glyphs and
 * selection highlights.
 *
 * <p>Install with {@link #install(JTextComponent)}.
 */
public final class AtPathHighlighter {

    /** Matches @-tokens: @ followed by one or more non-whitespace characters. */
    private static final Pattern AT_TOKEN = Pattern.compile("@\\S+");

    /** Blue foreground used for {@code @path} tokens. */
    public static final Color AT_TOKEN_COLOR = new Color(0x4A, 0x9E, 0xCD);

    private final JTextComponent tc;
    /** Current token ranges [start, end) — kept for {@link #highlightCount()}. */
    private final List<int[]> ranges = new ArrayList<>();

    private AtPathHighlighter(JTextComponent tc) {
        this.tc = tc;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Installs an {@code AtPathHighlighter} on the given text component.
     * The component must also implement {@link RangeHighlightable}.
     *
     * @param tc the text component to highlight (must implement {@link RangeHighlightable})
     * @return the installed highlighter (kept for testing)
     * @throws ClassCastException if {@code tc} does not implement {@link RangeHighlightable}
     */
    public static AtPathHighlighter install(JTextComponent tc) {
        AtPathHighlighter h = new AtPathHighlighter(tc);
        tc.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e)  { h.rehighlight(); }
            @Override public void removeUpdate(DocumentEvent e)  { h.rehighlight(); }
            @Override public void changedUpdate(DocumentEvent e) { h.rehighlight(); }
        });
        // Repaint on selection changes so selected vs unselected portions update correctly.
        tc.addCaretListener(e -> tc.repaint());
        return h;
    }

    /**
     * Re-computes token ranges from the current document text and triggers a repaint.
     * Called automatically by the document listener; also available for direct call.
     */
    public void rehighlight() {
        ranges.clear();
        String text;
        try {
            text = tc.getDocument().getText(0, tc.getDocument().getLength());
        } catch (BadLocationException ex) {
            ((RangeHighlightable) tc).setRanges(ranges);
            tc.repaint();
            return;
        }
        Matcher m = AT_TOKEN.matcher(text);
        while (m.find()) {
            ranges.add(new int[]{m.start(), m.end()});
        }
        ((RangeHighlightable) tc).setRanges(new ArrayList<>(ranges));
        tc.repaint();
    }

    /**
     * Returns the number of currently active token ranges.
     *
     * @return the number of highlighted ranges
     */
    public int highlightCount() {
        return ranges.size();
    }

    // -------------------------------------------------------------------------
    // Static painting helpers — called from RangeHighlightable components
    // -------------------------------------------------------------------------

    /**
     * Paints {@code @path} token ranges in {@link #AT_TOKEN_COLOR} over the already-rendered
     * text component.  Must be called from {@code paintComponent(Graphics g)} <em>after</em>
     * {@code super.paintComponent(g)}.
     *
     * @param g      the graphics context
     * @param tc     the text component being painted
     * @param ranges the token ranges to highlight
     */
    public static void paintHighlights(Graphics g, JTextComponent tc, List<int[]> ranges) {
        if (ranges.isEmpty()) return;
        FontMetrics fm = g.getFontMetrics(tc.getFont());
        int selStart = tc.getSelectionStart();
        int selEnd   = tc.getSelectionEnd();
        for (int[] range : ranges) {
            paintPart(g, fm, tc, range[0],                     Math.min(range[1], selStart), false);
            paintPart(g, fm, tc, Math.max(range[0], selStart), Math.min(range[1], selEnd),   true);
            paintPart(g, fm, tc, Math.max(range[0], selEnd),   range[1],                     false);
        }
    }

    private static void paintPart(Graphics g, FontMetrics fm, JTextComponent tc,
                                   int start, int end, boolean selected) {
        if (start >= end) return;
        try {
            Rectangle r0 = tc.modelToView(start);
            Rectangle r1 = tc.modelToView(end);
            if (r0 == null || r1 == null) return;
            int x = r0.x, y = r0.y, w = r1.x - r0.x, h = r0.height;
            if (!selected) {
                g.setColor(tc.getBackground());
                g.fillRect(x, y, w, h);
            }
            g.setColor(AT_TOKEN_COLOR);
            g.setFont(tc.getFont());
            g.drawString(tc.getDocument().getText(start, end - start), x, y + fm.getAscent());
        } catch (BadLocationException ex) { /* skip */ }
    }
}
