package io.github.nbplugins.claudecodegui.mcp.tools;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.openide.cookies.EditorCookie;
import org.openbeans.claude.netbeans.tools.params.OpenFileParams;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TC-08 regression (round 2): openFile called navigateTo but used
 * EditorRegistry.lastFocusedComponent() — which returned the Claude terminal,
 * not the just-opened editor file, so the cursor never moved.
 *
 * Fix: change navigateTo signature to accept the EditorCookie returned by
 * doOpenFile() and call editorCookie.getOpenedPanes() to get the correct pane.
 */
public class OpenFileTest {

    /** Minimal stub so tests compile without a NetBeans runtime. */
    private static final EditorCookie STUB_COOKIE = new EditorCookie() {
        @Override public void open() {}
        @Override public boolean close() { return true; }
        @Override public org.openide.util.Task prepareDocument() { return org.openide.util.Task.EMPTY; }
        @Override public javax.swing.text.StyledDocument openDocument() { return null; }
        @Override public javax.swing.text.StyledDocument getDocument() { return null; }
        @Override public void saveDocument() {}
        @Override public boolean isModified() { return false; }
        @Override public javax.swing.JEditorPane[] getOpenedPanes() { return new javax.swing.JEditorPane[0]; }
        @Override public org.openide.text.Line.Set getLineSet() { return null; }
    };

    @Test
    public void testNavigatesToPatternWithCorrectEditorCookie() throws Exception {
        AtomicBoolean navigateCalled = new AtomicBoolean(false);
        AtomicReference<EditorCookie> capturedCookie = new AtomicReference<>();
        AtomicReference<String> capturedPattern = new AtomicReference<>();

        OpenFile tool = new OpenFile() {
            @Override
            protected EditorCookie doOpenFile(String filePath) { return STUB_COOKIE; }

            @Override
            protected void navigateTo(EditorCookie ec, String pattern) {
                navigateCalled.set(true);
                capturedCookie.set(ec);
                capturedPattern.set(pattern);
            }
        };

        tool.run(new OpenFileParams("/fake/Test.java", false, "vectorize"));

        assertTrue(navigateCalled.get(),
                "navigateTo(EditorCookie, String) must be called when pattern is provided.");
        assertSame(STUB_COOKIE, capturedCookie.get(),
                "navigateTo must receive the EditorCookie from doOpenFile(), not a lookup "
                + "via EditorRegistry — the registry returns the terminal when focus is there.");
        assertEquals("vectorize", capturedPattern.get());
    }

    @Test
    public void testNoNavigationWhenPatternAbsent() throws Exception {
        AtomicBoolean navigateCalled = new AtomicBoolean(false);

        OpenFile tool = new OpenFile() {
            @Override
            protected EditorCookie doOpenFile(String filePath) { return STUB_COOKIE; }

            @Override
            protected void navigateTo(EditorCookie ec, String pattern) {
                navigateCalled.set(true);
            }
        };

        tool.run(new OpenFileParams("/fake/Test.java", false, null));

        assertFalse(navigateCalled.get(),
                "navigateTo must NOT be called when pattern is absent.");
    }
}
