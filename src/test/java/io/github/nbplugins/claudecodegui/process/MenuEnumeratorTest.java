package io.github.nbplugins.claudecodegui.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nbplugins.claudecodegui.model.ChoiceMenuModel;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MenuEnumeratorTest {

    /** Simulates Claude's scrolling /model menu. */
    static final class FakeMenu {
        final int n;
        final int window;
        final boolean wraps;
        int cursor;
        int top = 1;

        FakeMenu(int n, int window, boolean wraps, int cursor) {
            this.n = n;
            this.window = window;
            this.wraps = wraps;
            this.cursor = cursor;
            scroll();
        }

        void down() {
            if (cursor < n) cursor++;
            else if (wraps) cursor = 1;
            scroll();
        }

        void up() {
            if (cursor > 1) cursor--;
            else if (wraps) cursor = n;
            scroll();
        }

        private void scroll() {
            if (cursor < top) top = cursor;
            if (cursor > top + window - 1) top = cursor - window + 1;
        }

        List<String> screen() {
            List<String> l = new ArrayList<>();
            l.add("─".repeat(60));
            l.add("  Select model");
            l.add("  Switch between Claude models.");
            l.add("");
            int bottom = Math.min(n, top + window - 1);
            for (int i = top; i <= bottom; i++) {
                String glyph = i == cursor ? "❯" : (i == top && top > 1 ? "↑" : (i == bottom && bottom < n ? "↓" : " "));
                l.add("  " + glyph + " " + i + ".  Model " + i + "          Description " + i);
            }
            if (bottom < n) l.add("     … +" + (n - bottom) + " models");
            l.add("");
            l.add("  Enter to set as default · s to use this session only · Esc to cancel");
            return l;
        }
    }

    private static ChoiceMenuModel enumerate(FakeMenu m, int[] keyCount) throws Exception {
        ScreenContentDetector det = new ScreenContentDetector();
        ChoiceMenuModel initial = det.detectChoiceMenu(m.screen()).orElseThrow();
        MenuEnumerator e = new MenuEnumerator(det, m::screen, new MenuEnumerator.Keys() {
            public void down() { m.down(); keyCount[0]++; }
            public void up() { m.up(); keyCount[0]++; }
        }, ms -> { }, 0);
        return e.enumerate(initial);
    }

    @Test
    void wrappingMenuIsEnumeratedAndCursorRestored() throws Exception {
        FakeMenu m = new FakeMenu(12, 3, true, 5);
        int[] keys = {0};
        ChoiceMenuModel full = enumerate(m, keys);
        assertEquals(12, full.options().size());
        assertEquals("1", full.options().get(0).response());
        assertEquals("12", full.options().get(11).response());
        assertEquals(5, m.cursor, "cursor must be back on the starting item");
        assertEquals(12, keys[0], "one full cycle");
        assertEquals(5, full.cursorNumber());
        assertEquals(4, full.defaultOptionIndex());
        assertTrue(full.scrollable());
    }

    @Test
    void nonWrappingMenuIsEnumeratedAndCursorRestored() throws Exception {
        FakeMenu m = new FakeMenu(12, 3, false, 6);
        ChoiceMenuModel full = enumerate(m, new int[]{0});
        assertEquals(12, full.options().size());
        assertEquals(6, m.cursor);
    }

    @Test
    void cursorAtLastItemWraps() throws Exception {
        FakeMenu m = new FakeMenu(12, 4, true, 12);
        ChoiceMenuModel full = enumerate(m, new int[]{0});
        assertEquals(12, full.options().size());
        assertEquals(12, m.cursor);
    }

    @Test
    void shortMenuIsReturnedUnchanged() throws Exception {
        FakeMenu m = new FakeMenu(3, 5, true, 2);
        ScreenContentDetector det = new ScreenContentDetector();
        ChoiceMenuModel initial = det.detectChoiceMenu(m.screen()).orElseThrow();
        assertFalse(initial.scrollable());
        int[] keys = {0};
        assertEquals(initial.options(), enumerate(m, keys).options());
        assertEquals(0, keys[0]);
    }

    @Test
    void screenLinesAreParsedByModelMenuParser() {
        ChoiceMenuModel menu = new ChoiceMenuModel("t", List.of(
                new ChoiceMenuModel.Option("Opus 5.5                 For complex work", "9"),
                new ChoiceMenuModel.Option("Fable 5.1                For toughest", "10")), 1, true, 10);
        List<String> lines = MenuEnumerator.toScreenLines(menu);
        assertEquals("❯ 10.  Fable 5.1                For toughest", lines.get(1));
        assertEquals(List.of("Opus 5.5", "Fable 5.1"), new ModelMenuParser().parse(lines).models());
    }
}
