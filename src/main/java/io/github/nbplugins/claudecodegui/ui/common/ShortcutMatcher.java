package io.github.nbplugins.claudecodegui.ui.common;

import io.github.nbplugins.claudecodegui.model.FavoriteEntry;
import io.github.nbplugins.claudecodegui.model.FavoriteEntry.Scope;
import io.github.nbplugins.claudecodegui.model.PromptFavoritesStore;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.text.JTextComponent;

/**
 * Matches keyboard shortcut sequences against favorites and inserts the
 * corresponding text into the input component.
 *
 * <p>After the first key press that could start a known shortcut the matcher
 * enters "capturing" mode and the input component suspends normal character input.
 * When a full sequence matches a favorite its text is inserted.
 * When no shortcut can match the accumulated events are replayed as ordinary
 * key-typed events so no characters are lost.
 *
 * <p>Project-scoped shortcuts take priority over global ones.
 */
public final class ShortcutMatcher {

    private final JTextComponent       inputComponent;
    private final PromptFavoritesStore store;
    private final Consumer<String>     onInsert;

    /** Accumulated combo strings (e.g. ["Ctrl+K", "Ctrl+F"]). */
    private final List<String>   accumulated = new ArrayList<>();
    /** Raw KeyEvents accumulated while in capturing mode, for replay. */
    private final List<KeyEvent> rawEvents   = new ArrayList<>();

    private boolean capturing   = false;
    private boolean justMatched = false;

    /**
     * Returns {@code true} while a shortcut sequence is being accumulated.
     *
     * @return {@code true} if capturing mode is active
     */
    public boolean isCapturing() { return capturing; }

    /**
     * Returns {@code true} if the caller should consume the next KEY_TYPED event —
     * either because a sequence is still being accumulated, or because a match just
     * fired (one-shot: the flag is cleared after the first call that returns true).
     *
     * @return {@code true} if the next KEY_TYPED event should be suppressed
     */
    public boolean shouldSuppressKeyTyped() {
        if (capturing) return true;
        if (justMatched) { justMatched = false; return true; }
        return false;
    }

    /**
     * Creates a new {@code ShortcutMatcher}.
     *
     * @param inputComponent the text component to insert text into
     * @param store          the favorites store to read shortcuts from
     * @param onInsert       called with the matched text (after insert in inputComponent);
     *                       may be null
     */
    public ShortcutMatcher(JTextComponent inputComponent, PromptFavoritesStore store,
                            Consumer<String> onInsert) {
        this.inputComponent = inputComponent;
        this.store          = store;
        this.onInsert       = onInsert;
    }

    /**
     * Convenience constructor with no external callback.
     *
     * @param inputComponent the text component to insert text into
     * @param store          the favorites store to read shortcuts from
     */
    public ShortcutMatcher(JTextComponent inputComponent, PromptFavoritesStore store) {
        this(inputComponent, store, null);
    }

    // -------------------------------------------------------------------------
    // Public API — call from KeyListener.keyPressed
    // -------------------------------------------------------------------------

    /**
     * Processes a key-pressed event.
     *
     * @param e the event
     * @return {@code true} if the event was consumed by the shortcut matcher
     *         (caller should call {@code e.consume()} and skip normal handling);
     *         {@code false} if the caller should process it normally.
     */
    public boolean keyPressed(KeyEvent e) {
        int code = e.getKeyCode();

        // Ignore lone modifier keys
        if (code == KeyEvent.VK_CONTROL || code == KeyEvent.VK_SHIFT
                || code == KeyEvent.VK_ALT || code == KeyEvent.VK_META) {
            return false;
        }

        // If not capturing and the key doesn't start any shortcut, pass through
        String combo = buildCombo(e);
        List<FavoriteEntry> all = store.getAllWithShortcuts();
        if (!capturing && !couldStart(combo, all)) {
            return false;
        }

        // Enter / continue capturing
        capturing = true;
        accumulated.add(combo);
        rawEvents.add(e);

        String sequence = String.join(" ", accumulated);

        // Check for exact match
        FavoriteEntry match = findMatch(sequence, all);
        if (match != null) {
            reset();
            justMatched = true;
            String text = match.getText();
            inputComponent.setText(text);
            inputComponent.setCaretPosition(inputComponent.getDocument().getLength());
            if (onInsert != null) onInsert.accept(text);
            return true;
        }

        // Check if any shortcut still starts with our sequence
        if (couldContinue(sequence, all)) {
            return true; // wait for more keys
        }

        // No possible match — replay accumulated events as normal input
        replay();
        return true; // the current event is part of the replayed set
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private void reset() {
        accumulated.clear();
        rawEvents.clear();
        capturing = false;
    }

    /** Replays accumulated raw events as key-typed by dispatching them to the input component. */
    private void replay() {
        List<KeyEvent> toReplay = new ArrayList<>(rawEvents);
        reset();
        for (KeyEvent ev : toReplay) {
            // Dispatch a KEY_TYPED event for printable characters
            char ch = ev.getKeyChar();
            if (ch != KeyEvent.CHAR_UNDEFINED && ch >= 32) {
                inputComponent.replaceSelection(String.valueOf(ch));
            }
        }
    }

    /** True if {@code combo} is the beginning of any known shortcut's first token. */
    private static boolean couldStart(String combo, List<FavoriteEntry> all) {
        for (FavoriteEntry e : all) {
            String sc = e.getShortcut();
            if (sc != null && (sc.equals(combo) || sc.startsWith(combo + " "))) return true;
        }
        return false;
    }

    /** True if {@code sequence} is a prefix of any known shortcut. */
    private static boolean couldContinue(String sequence, List<FavoriteEntry> all) {
        for (FavoriteEntry e : all) {
            String sc = e.getShortcut();
            if (sc != null && (sc.equals(sequence) || sc.startsWith(sequence + " "))) return true;
        }
        return false;
    }

    /**
     * Finds a favorite whose shortcut exactly matches {@code sequence}.
     * Project-scoped entries win over global ones.
     */
    private static FavoriteEntry findMatch(String sequence, List<FavoriteEntry> all) {
        FavoriteEntry projectMatch = null;
        FavoriteEntry globalMatch  = null;
        for (FavoriteEntry e : all) {
            if (sequence.equals(e.getShortcut())) {
                if (e.getScope() == Scope.PROJECT) projectMatch = e;
                else if (globalMatch == null)       globalMatch  = e;
            }
        }
        return projectMatch != null ? projectMatch : globalMatch;
    }

    private static String buildCombo(KeyEvent e) {
        StringBuilder sb = new StringBuilder();
        if (e.isControlDown()) sb.append("Ctrl+");
        if (e.isShiftDown())   sb.append("Shift+");
        if (e.isAltDown())     sb.append("Alt+");
        if (e.isMetaDown())    sb.append("Meta+");
        sb.append(KeyEvent.getKeyText(e.getKeyCode()));
        return sb.toString();
    }
}
