package io.github.nbplugins.claudecodegui.process;

import io.github.nbplugins.claudecodegui.model.ChoiceMenuModel;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Collects the full item list of a scrolling numbered menu (e.g. {@code /model}) by moving
 * Claude's cursor with the arrow keys and merging every window it shows.
 *
 * <p>Claude draws only as many items as fit the terminal. The cursor is moved down one item
 * at a time (Claude's menus wrap from the last item to the first), each window is merged by
 * item number, and the walk stops when the cursor is back on the item it started on — so the
 * menu is left exactly as it was found. If the menu does not wrap, the cursor is walked to
 * both ends and back instead.
 */
public final class MenuEnumerator {

    private static final Logger LOG = Logger.getLogger(MenuEnumerator.class.getName());

    /** Upper bound on key presses, so a misbehaving menu can never loop forever. */
    static final int MAX_STEPS = 300;
    private static final int READ_RETRIES = 6;

    /** Sends cursor-movement keys to Claude. */
    public interface Keys {
        void down() throws IOException, InterruptedException;
        void up() throws IOException, InterruptedException;
    }

    /** Pause between a key press and reading the screen. */
    public interface Pause {
        void sleep(long millis) throws InterruptedException;
    }

    private final ScreenContentDetector detector;
    private final Supplier<List<String>> screen;
    private final Keys keys;
    private final Pause pause;
    private final long settleMs;

    /**
     * Creates an enumerator.
     *
     * @param detector detector used to parse each window
     * @param screen   supplies the current screen lines
     * @param keys     sends arrow keys
     * @param pause    sleeps for the given time (injectable for tests)
     * @param settleMs time to wait after a key press before reading the screen
     */
    public MenuEnumerator(ScreenContentDetector detector, Supplier<List<String>> screen,
                          Keys keys, Pause pause, long settleMs) {
        this.detector = detector;
        this.screen = screen;
        this.keys = keys;
        this.pause = pause;
        this.settleMs = settleMs;
    }

    /**
     * Returns the complete menu for a scrollable {@code initial} window, with the cursor left on
     * the item it was on. A menu that is not scrollable, or whose cursor cannot be tracked, is
     * returned unchanged.
     *
     * @param initial the window currently shown on screen
     * @return the merged menu
     * @throws IOException          if a key cannot be written
     * @throws InterruptedException if the thread is interrupted
     */
    public ChoiceMenuModel enumerate(ChoiceMenuModel initial) throws IOException, InterruptedException {
        int start = initial.cursorNumber();
        if (!initial.scrollable() || start <= 0) return initial;

        TreeMap<Integer, ChoiceMenuModel.Option> byNumber = new TreeMap<>();
        merge(byNumber, initial);

        int steps = 0;
        int prev = start;
        boolean wraps = true;
        while (steps++ < MAX_STEPS) {
            keys.down();
            ChoiceMenuModel w = readWindow();
            if (w == null) return finish(initial, byNumber, start);
            merge(byNumber, w);
            int cur = w.cursorNumber();
            if (cur == start) return finish(initial, byNumber, start);
            if (cur == prev) {
                wraps = false;
                break;
            }
            prev = cur;
        }
        if (!wraps) {
            // No wrap-around: walk to the top, merging, then back down to the start item.
            prev = -1;
            while (steps++ < MAX_STEPS) {
                keys.up();
                ChoiceMenuModel w = readWindow();
                if (w == null) return finish(initial, byNumber, start);
                merge(byNumber, w);
                if (w.cursorNumber() == prev) break;
                prev = w.cursorNumber();
            }
            while (steps++ < MAX_STEPS) {
                ChoiceMenuModel w = readWindow();
                if (w == null || w.cursorNumber() == start) break;
                if (w.cursorNumber() > start) {
                    keys.up();
                } else {
                    keys.down();
                }
            }
        }
        return finish(initial, byNumber, start);
    }

    /** Reads the window after a key press; {@code null} if no menu with a cursor is visible. */
    private ChoiceMenuModel readWindow() throws InterruptedException {
        for (int i = 0; i < READ_RETRIES; i++) {
            pause.sleep(settleMs);
            Optional<ChoiceMenuModel> m = detector.detectChoiceMenu(screen.get());
            if (m.isPresent() && m.get().cursorNumber() > 0) return m.get();
        }
        LOG.fine("[MenuEnumerator] menu not readable, giving up");
        return null;
    }

    private static void merge(TreeMap<Integer, ChoiceMenuModel.Option> byNumber, ChoiceMenuModel window) {
        for (ChoiceMenuModel.Option o : window.options()) {
            try {
                byNumber.put(Integer.parseInt(o.response()), o);
            } catch (NumberFormatException ignored) {
                // not a numbered item
            }
        }
    }

    private static ChoiceMenuModel finish(ChoiceMenuModel initial,
                                          TreeMap<Integer, ChoiceMenuModel.Option> byNumber, int start) {
        List<ChoiceMenuModel.Option> options = new ArrayList<>(byNumber.values());
        int idx = 0;
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).response().equals(String.valueOf(start))) idx = i;
        }
        LOG.fine("[MenuEnumerator] enumerated " + options.size() + " items, cursor=" + start);
        return new ChoiceMenuModel(initial.text(), options, idx, true, start);
    }

    /**
     * Renders a numbered menu as screen lines ({@code ❯ 3.  Sonnet 5.5 …}), so line-based
     * parsers such as {@link ModelMenuParser} can read the merged list.
     *
     * @param menu the menu
     * @return one line per option
     */
    public static List<String> toScreenLines(ChoiceMenuModel menu) {
        List<String> lines = new ArrayList<>();
        for (ChoiceMenuModel.Option o : menu.options()) {
            boolean cursor = o.response().equals(String.valueOf(menu.cursorNumber()));
            lines.add((cursor ? "❯ " : "  ") + o.response() + ".  " + o.display());
        }
        return lines;
    }
}
