package io.github.nbplugins.claudecodegui.ui.common;

import io.github.nbplugins.claudecodegui.model.FavoriteEntry;
import io.github.nbplugins.claudecodegui.model.PromptFavoritesStore;
import io.github.nbplugins.claudecodegui.ui.common.ShortcutMatcher;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.util.UUID;
import javax.swing.JTextArea;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ShortcutMatcher}.
 *
 * <p>Key scenario: while a multi-key shortcut is being accumulated (capturing mode),
 * {@code isCapturing()} must return {@code true} so that the caller can suppress
 * {@code KEY_TYPED} events and prevent stray characters from leaking into the textarea.
 */
class ShortcutMatcherTest {

    @TempDir Path tempDir;

    private JTextArea inputArea;
    private PromptFavoritesStore store;
    private ShortcutMatcher matcher;

    @BeforeEach
    void setUp() throws Exception {
        inputArea = new JTextArea();

        store = new PromptFavoritesStore(
                tempDir.resolve("favorites.json"),
                tempDir.resolve("global-favorites.json"));

        // Register a two-key shortcut: Ctrl+K Ctrl+F → "Hello"
        FavoriteEntry entry = new FavoriteEntry("Hello", UUID.randomUUID(), "Ctrl+K Ctrl+F",
                FavoriteEntry.Scope.GLOBAL);
        store.addGlobal(entry);

        matcher = new ShortcutMatcher(inputArea, store);
    }

    // -------------------------------------------------------------------------
    // isCapturing — leaking keyTyped regression
    // -------------------------------------------------------------------------

    @Test
    void notCapturingBeforeAnyKey() {
        assertFalse(matcher.isCapturing());
    }

    @Test
    void capturingAfterFirstKeyOfSequence() {
        KeyEvent ctrlK = makeKeyPressed(KeyEvent.VK_K, KeyEvent.CTRL_DOWN_MASK);
        assertTrue(matcher.keyPressed(ctrlK), "Ctrl+K should be consumed (starts a shortcut)");
        assertTrue(matcher.isCapturing(), "isCapturing() must be true after first key of sequence");
    }

    @Test
    void notCapturingAfterFullMatch() {
        matcher.keyPressed(makeKeyPressed(KeyEvent.VK_K, KeyEvent.CTRL_DOWN_MASK));
        matcher.keyPressed(makeKeyPressed(KeyEvent.VK_F, KeyEvent.CTRL_DOWN_MASK));
        assertFalse(matcher.isCapturing(), "isCapturing() must be false after full match");
        assertEquals("Hello", inputArea.getText());
    }

    @Test
    void notCapturingAfterNoMatchReplay() {
        // Ctrl+K Ctrl+X — no shortcut matches; should replay and reset
        matcher.keyPressed(makeKeyPressed(KeyEvent.VK_K, KeyEvent.CTRL_DOWN_MASK));
        matcher.keyPressed(makeKeyPressed(KeyEvent.VK_X, KeyEvent.CTRL_DOWN_MASK));
        assertFalse(matcher.isCapturing(), "isCapturing() must be false after failed sequence");
    }

    @Test
    void isCapturingMidSequencePreventKeyTypedLeak() {
        // Simulate: user presses Ctrl+K (first key of two-key shortcut).
        // KEY_PRESSED is consumed by ShortcutMatcher → isCapturing() == true.
        // Caller must suppress the KEY_TYPED event to prevent 'k' leaking into the textarea.
        KeyEvent ctrlK = makeKeyPressed(KeyEvent.VK_K, KeyEvent.CTRL_DOWN_MASK);
        matcher.keyPressed(ctrlK);

        // The test verifies that isCapturing() returns true — so the caller knows
        // it must consume the KEY_TYPED event. Without isCapturing() the caller has
        // no way to suppress KEY_TYPED and 'k' would appear in the textarea.
        assertTrue(matcher.isCapturing(),
                "isCapturing() must return true so caller can suppress KEY_TYPED events");

        // Simulate caller suppressing KEY_TYPED (as the fix requires)
        // => inputArea remains empty
        assertEquals("", inputArea.getText(), "No characters should leak into textarea mid-sequence");
    }

    // -------------------------------------------------------------------------
    // shouldSuppressKeyTyped — last-key leak regression
    // -------------------------------------------------------------------------

    @Test
    void shouldSuppressKeyTypedAfterFullMatch() throws Exception {
        // Register a single-key shortcut: Ctrl+Shift+P → "World"
        FavoriteEntry entry = new FavoriteEntry("World", UUID.randomUUID(), "Ctrl+Shift+P",
                FavoriteEntry.Scope.GLOBAL);
        store.addGlobal(entry);

        KeyEvent ctrlShiftP = makeKeyPressed(KeyEvent.VK_P,
                KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK);
        assertTrue(matcher.keyPressed(ctrlShiftP), "Ctrl+Shift+P should be consumed");
        assertEquals("World", inputArea.getText(), "Prompt must be inserted");
        assertFalse(matcher.isCapturing(), "capturing must be false after match");

        // shouldSuppressKeyTyped() must return true once (to suppress the KEY_TYPED for 'P')
        assertTrue(matcher.shouldSuppressKeyTyped(), "first call should return true (suppress KEY_TYPED)");
        // and then false (flag cleared)
        assertFalse(matcher.shouldSuppressKeyTyped(), "second call should return false (flag cleared)");
    }

    @Test
    void shouldSuppressKeyTypedWhileCapturing() {
        KeyEvent ctrlK = makeKeyPressed(KeyEvent.VK_K, KeyEvent.CTRL_DOWN_MASK);
        matcher.keyPressed(ctrlK);
        assertTrue(matcher.shouldSuppressKeyTyped(), "must suppress KEY_TYPED mid-sequence");
        // calling again still true (still capturing)
        assertTrue(matcher.shouldSuppressKeyTyped(), "still capturing — still suppress");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static KeyEvent makeKeyPressed(int keyCode, int modifiers) {
        JTextArea src = new JTextArea();
        return new KeyEvent(src, KeyEvent.KEY_PRESSED,
                System.currentTimeMillis(), modifiers, keyCode,
                KeyEvent.CHAR_UNDEFINED);
    }
}
