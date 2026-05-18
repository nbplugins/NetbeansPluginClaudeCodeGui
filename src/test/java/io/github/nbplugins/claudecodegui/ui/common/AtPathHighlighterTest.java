package io.github.nbplugins.claudecodegui.ui.common;

import io.github.nbplugins.claudecodegui.ui.common.AtPathHighlighter;
import io.github.nbplugins.claudecodegui.ui.common.DecoratedTextArea;
import java.awt.Color;
import java.lang.reflect.Field;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AtPathHighlighter}.
 *
 * <p>Verifies that {@code @path} tokens in the text area are tracked and
 * that ranges are cleared when tokens are removed.
 */
class AtPathHighlighterTest {

    private static DecoratedTextArea newArea() {
        return new DecoratedTextArea(3, 40, () -> null);
    }

    @Test
    void singleAtTokenProducesOneHighlight() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var area = newArea();
            AtPathHighlighter h = AtPathHighlighter.install(area);
            area.setText("@src/Foo.java");
            h.rehighlight();
            assertEquals(1, h.highlightCount(), "One @-token must produce one highlight");
        });
    }

    @Test
    void multipleAtTokensProduceMultipleHighlights() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var area = newArea();
            AtPathHighlighter h = AtPathHighlighter.install(area);
            area.setText("See @src/A.java and @src/B.java for details");
            h.rehighlight();
            assertEquals(2, h.highlightCount(), "Two @-tokens must produce two highlights");
        });
    }

    @Test
    void noAtTokenProducesNoHighlight() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var area = newArea();
            AtPathHighlighter h = AtPathHighlighter.install(area);
            area.setText("No at-tokens here");
            h.rehighlight();
            assertEquals(0, h.highlightCount(), "No @-tokens must produce no highlights");
        });
    }

    @Test
    void removingTokenClearsHighlight() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var area = newArea();
            AtPathHighlighter h = AtPathHighlighter.install(area);
            area.setText("@src/Foo.java");
            h.rehighlight();
            assertEquals(1, h.highlightCount(), "One highlight before token removal");

            area.setText("plain text");
            h.rehighlight();
            assertEquals(0, h.highlightCount(), "Zero highlights after token removed");
        });
    }

    @Test
    void documentListenerTriggersRehighlight() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var area = newArea();
            AtPathHighlighter h = AtPathHighlighter.install(area);
            area.setText("@foo/bar.txt");
            assertEquals(1, h.highlightCount(), "Highlight must be applied via document listener");
        });
    }

    @Test
    void atTokenAtStartOfLine() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var area = newArea();
            AtPathHighlighter h = AtPathHighlighter.install(area);
            area.setText("@/absolute/path/File.java\nsome other text");
            h.rehighlight();
            assertEquals(1, h.highlightCount());
        });
    }

    @Test
    void tokenColorIsBlue() throws Exception {
        Field f = AtPathHighlighter.class.getDeclaredField("AT_TOKEN_COLOR");
        f.setAccessible(true);
        Color c = (Color) f.get(null);
        assertTrue(c.getBlue()  >= 0x80, "Blue component must be >= 0x80");
        assertTrue(c.getRed()   <  0x80, "Red component must be < 0x80 for blue");
        assertTrue(c.getGreen() >= 0x80, "Green component must be >= 0x80 for cyan-blue");
    }

    @Test
    void multilineTextWithMultipleTokens() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var area = newArea();
            AtPathHighlighter h = AtPathHighlighter.install(area);
            area.setText("@/path/A.java\n@/path/B.java\nPlease fix these files.");
            h.rehighlight();
            assertEquals(2, h.highlightCount(), "Two @-tokens on separate lines must produce two highlights");
        });
    }
}
