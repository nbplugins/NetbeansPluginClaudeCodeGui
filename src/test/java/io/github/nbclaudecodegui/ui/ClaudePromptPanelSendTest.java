package io.github.nbclaudecodegui.ui;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the {@code doSend()} behaviour of {@link ClaudePromptPanel}.
 *
 * <p>Since Stage 16, file paths are inserted directly into the textarea text
 * as {@code @path} tokens; there is no separate attachment model.
 * {@code doSend()} sends {@code inputArea.getText()} as-is.
 */
class ClaudePromptPanelSendTest {

    @TempDir
    File tempDir;

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private AtomicReference<String> sentText;
    private ClaudePromptPanel       panel;

    private void buildPanel(String workingDir) throws Exception {
        sentText = new AtomicReference<>();
        Supplier<List<String>> hist = Collections::emptyList;
        Supplier<String>       wd   = () -> workingDir;
        Consumer<String>       send = sentText::set;
        Runnable               noop = () -> {};

        SwingUtilities.invokeAndWait(() -> {
            panel = new ClaudePromptPanel(send, noop, noop, hist, wd, null, null);
        });
    }

    /** Calls the private {@code doSend()} via reflection. */
    private void callDoSend() throws Exception {
        java.lang.reflect.Method m = ClaudePromptPanel.class.getDeclaredMethod("doSend");
        m.setAccessible(true);
        SwingUtilities.invokeAndWait(() -> {
            try {
                m.invoke(panel);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
    }

    /** Sets the text area content. */
    private void setInputText(String text) throws Exception {
        java.lang.reflect.Field f = ClaudePromptPanel.class.getDeclaredField("inputArea");
        f.setAccessible(true);
        javax.swing.JTextArea area = (javax.swing.JTextArea) f.get(panel);
        SwingUtilities.invokeAndWait(() -> area.setText(text));
    }

    private String getInputText() throws Exception {
        java.lang.reflect.Field f = ClaudePromptPanel.class.getDeclaredField("inputArea");
        f.setAccessible(true);
        javax.swing.JTextArea area = (javax.swing.JTextArea) f.get(panel);
        final String[] result = new String[1];
        SwingUtilities.invokeAndWait(() -> result[0] = area.getText());
        return result[0];
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    void atPathsInTextAreaAreSentAsIs() throws Exception {
        buildPanel(tempDir.getAbsolutePath());

        File f1 = new File(tempDir, "path1.txt"); f1.createNewFile();
        File f2 = new File(tempDir, "path2.txt"); f2.createNewFile();
        // @paths are already in the textarea (inserted by FileDropHandler)
        setInputText("@" + f1.getAbsolutePath() + "\n@" + f2.getAbsolutePath() + "\ndo the thing");

        callDoSend();

        String sent = sentText.get();
        assertNotNull(sent, "onSend must have been called");
        assertTrue(sent.contains("@" + f1.getAbsolutePath()), "Sent text must contain @path1");
        assertTrue(sent.contains("@" + f2.getAbsolutePath()), "Sent text must contain @path2");
        assertTrue(sent.contains("do the thing"), "User text must be present");
    }

    @Test
    void noFilesAndTextSendsPlainText() throws Exception {
        buildPanel(tempDir.getAbsolutePath());
        setInputText("hello claude");

        callDoSend();

        String sent = sentText.get();
        assertNotNull(sent);
        assertEquals("hello claude", sent,
                "With no attachments, sent text must equal the plain input");
    }

    @Test
    void textWithAtPathSentDirectly() throws Exception {
        buildPanel(tempDir.getAbsolutePath());

        File f = new File(tempDir, "only.txt"); f.createNewFile();
        setInputText("@" + f.getAbsolutePath() + "\n");

        callDoSend();

        String sent = sentText.get();
        assertNotNull(sent, "onSend must be called when text is non-empty");
        assertTrue(sent.contains("@" + f.getAbsolutePath()),
                "Sent text must contain the @path");
    }

    @Test
    void noAttachButtonPresent() throws Exception {
        buildPanel(tempDir.getAbsolutePath());
        SwingUtilities.invokeAndWait(() -> {
            boolean found = hasButtonWithText(panel, "Attach");
            assertFalse(found, "Attach button must NOT be present in the panel");
        });
    }

    /** Recursively searches for a JButton whose text contains the given substring. */
    private static boolean hasButtonWithText(java.awt.Container container, String substring) {
        for (java.awt.Component c : container.getComponents()) {
            if (c instanceof javax.swing.JButton btn) {
                String text = btn.getText();
                if (text != null && text.contains(substring)) return true;
            }
            if (c instanceof java.awt.Container child) {
                if (hasButtonWithText(child, substring)) return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Tests: file chooser insertion — trailing space and caret position
    // -------------------------------------------------------------------------

    /** Calls the package-private {@code insertTokenAtCaret(String)} via reflection. */
    private void callInsertTokenAtCaret(String token) throws Exception {
        java.lang.reflect.Method m = ClaudePromptPanel.class.getDeclaredMethod("insertTokenAtCaret", String.class);
        m.setAccessible(true);
        SwingUtilities.invokeAndWait(() -> {
            try {
                m.invoke(panel, token);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
    }

    /** Returns the current caret position of the inputArea. */
    private int getCaretPosition() throws Exception {
        java.lang.reflect.Field f = ClaudePromptPanel.class.getDeclaredField("inputArea");
        f.setAccessible(true);
        javax.swing.JTextArea area = (javax.swing.JTextArea) f.get(panel);
        final int[] result = new int[1];
        SwingUtilities.invokeAndWait(() -> result[0] = area.getCaretPosition());
        return result[0];
    }

    @Test
    void insertTokenAtCaretAddsTrailingSpace() throws Exception {
        buildPanel(tempDir.getAbsolutePath());
        setInputText("");
        callInsertTokenAtCaret("@src/Foo.java");
        String text = getInputText();
        assertTrue(text.endsWith(" "),
                "insertTokenAtCaret must add trailing space, got: '" + text + "'");
    }

    @Test
    void insertTokenAtCaretSetsCaretAfterTrailingSpace() throws Exception {
        buildPanel(tempDir.getAbsolutePath());
        setInputText("");
        callInsertTokenAtCaret("@src/Foo.java");
        int caret = getCaretPosition();
        int len   = getInputText().length();
        assertEquals(len, caret,
                "Caret must be at end of text after insertion, got caret=" + caret + " len=" + len);
    }

    @Test
    void insertTokenAtCaretPrependsSpaceWhenAreaHasText() throws Exception {
        buildPanel(tempDir.getAbsolutePath());
        setInputText("please fix");
        // move caret to end
        java.lang.reflect.Field f = ClaudePromptPanel.class.getDeclaredField("inputArea");
        f.setAccessible(true);
        javax.swing.JTextArea area = (javax.swing.JTextArea) f.get(panel);
        SwingUtilities.invokeAndWait(() -> area.setCaretPosition(area.getText().length()));
        callInsertTokenAtCaret("@pom.xml");
        String text = getInputText();
        assertTrue(text.startsWith("please fix "),
                "Space must be prepended when existing text has no trailing space, got: '" + text + "'");
        assertTrue(text.endsWith(" "),
                "Trailing space must be present, got: '" + text + "'");
    }

    @Test
    void emptyTextDoesNotFire() throws Exception {
        buildPanel(tempDir.getAbsolutePath());
        setInputText("");

        callDoSend();

        assertNull(sentText.get(), "onSend must NOT be called when text is empty");
    }

    @Test
    void afterSendInputAreaIsCleared() throws Exception {
        buildPanel(tempDir.getAbsolutePath());
        setInputText("@/some/path\ntest");

        callDoSend();

        assertEquals("", getInputText(), "Input area must be cleared after send");
    }
}
