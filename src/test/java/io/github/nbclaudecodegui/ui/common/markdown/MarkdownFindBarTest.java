package io.github.nbclaudecodegui.ui.common.markdown;

import java.util.List;
import javax.swing.JEditorPane;
import javax.swing.text.html.HTMLEditorKit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MarkdownFindBarTest {

    private JEditorPane pane;
    private MarkdownFindBar bar;

    @BeforeEach
    void setUp() {
        pane = new JEditorPane();
        pane.setEditorKit(new HTMLEditorKit());
        pane.setText("<html><body>hello world Hello HELLO foo hello</body></html>");
        bar = new MarkdownFindBar(pane);
    }

    private void setQuery(String query) {
        bar.getTextField().setText(query);
    }

    @Test
    void findsMatchesInDocument() {
        setQuery("hello");
        List<int[]> matches = bar.getMatchPositions();
        // "hello world Hello HELLO foo hello" contains hello (case-insensitive) 4 times
        assertEquals(4, matches.size());
    }

    @Test
    void matchesLabelShowsCount() {
        setQuery("hello");
        assertEquals("1 of 4", bar.matches.getText());
    }

    @Test
    void noMatchesLabel() {
        setQuery("zzznomatch");
        assertEquals("No matches", bar.matches.getText());
    }

    @Test
    void findNextWrapsAround() {
        bar.wrapAround.setSelected(true);
        setQuery("hello");
        assertEquals(4, bar.getMatchPositions().size());
        assertEquals(0, bar.getCurrentMatchIndex());
        bar.findNext();
        assertEquals(1, bar.getCurrentMatchIndex());
        bar.findNext();
        bar.findNext();
        assertEquals(3, bar.getCurrentMatchIndex());
        bar.findNext(); // wraps
        assertEquals(0, bar.getCurrentMatchIndex());
    }

    @Test
    void findPrevWrapsAround() {
        bar.wrapAround.setSelected(true);
        setQuery("hello");
        assertEquals(0, bar.getCurrentMatchIndex());
        bar.findPrev(); // wraps to last
        assertEquals(3, bar.getCurrentMatchIndex());
    }

    @Test
    void caseInsensitiveByDefault() {
        bar.matchCase.setSelected(false);
        setQuery("hello");
        // matches "hello", "Hello", "HELLO", "hello" = 4
        assertEquals(4, bar.getMatchPositions().size());
    }

    @Test
    void matchCaseToggle() {
        bar.matchCase.setSelected(true);
        setQuery("hello");
        // only lowercase "hello" matches (appears 2 times: "hello world" and "foo hello")
        assertEquals(2, bar.getMatchPositions().size());
    }

    @Test
    void emptyQueryClearsMatches() {
        setQuery("hello");
        assertEquals(4, bar.getMatchPositions().size());
        setQuery("");
        assertEquals(0, bar.getMatchPositions().size());
        assertEquals("", bar.matches.getText());
    }

    @Test
    void hideHidesBarPreservesPositionsForResumedSearch() {
        bar.gainFocus();
        assertTrue(bar.isVisible());
        setQuery("hello");
        assertEquals(4, bar.getMatchPositions().size());
        bar.looseFocus();
        assertFalse(bar.isVisible());
        // matchPositions preserved so F3 can resume from correct position
        assertEquals(4, bar.getMatchPositions().size());
    }

    @Test
    void regexpSearch() {
        bar.regexp.setSelected(true);
        setQuery("hel+o");
        // matches hello/Hello/HELLO case-insensitively with regexp
        assertEquals(4, bar.getMatchPositions().size());
    }

    @Test
    void wholeWordsFilter() {
        bar.wholeWords.setSelected(true);
        bar.matchCase.setSelected(false);
        setQuery("hello");
        // "hello", "Hello", "HELLO", "hello" are all whole words here
        assertEquals(4, bar.getMatchPositions().size());
        setQuery("hell");
        // "hell" is NOT a whole word in any of the tokens
        assertEquals(0, bar.getMatchPositions().size());
    }
}
