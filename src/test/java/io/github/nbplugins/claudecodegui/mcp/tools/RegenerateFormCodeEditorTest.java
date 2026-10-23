package io.github.nbplugins.claudecodegui.mcp.tools;

import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.PlainDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives {@link RegenerateFormCode#regenerateInFormEditor} with fakes that expose
 * the same public methods as the NetBeans form module classes and reproduce their
 * behavior (verified against the NetBeans 27 bytecode):
 * <ul>
 *   <li>{@code JavaCodeGenerator.codeUpToDate} starts {@code true}, so right after a
 *       form is loaded {@code regenerateCodeIfNeeded()} does nothing;</li>
 *   <li>only a modifying form event clears it - {@code fireFormChanged(true)} delivers
 *       it immediately, {@code fireFormChanged(false)} queues it via invokeLater, i.e.
 *       after the caller's EDT task;</li>
 *   <li>a form change marks the form modified; {@code saveSourceOnly()} saves just the
 *       .java, {@code saveDocument()} saves .form and .java.</li>
 * </ul>
 */
public class RegenerateFormCodeEditorTest {

    private static final String OLD_CODE = "class MyFrame { /* old initComponents */ }";
    private static final String NEW_CODE = "class MyFrame { /* initComponents with jButton1 */ }";

    /** Fake {@code FormModel}. */
    public static class FakeFormModel {
        FakeFormEditor editor;
        boolean queuedEvent;

        public boolean isReadOnly() { return false; }

        public Object fireFormChanged(boolean immediately) {
            if (immediately) {
                editor.codeUpToDate = false;
                editor.support.formModified = true;
            } else {
                queuedEvent = true; // delivered only after the current EDT task
            }
            return null;
        }
    }

    /** Fake {@code FormEditor} with the {@code JavaCodeGenerator} up-to-date flag. */
    public static class FakeFormEditor {
        final FakeEditorSupport support;
        final FakeFormModel model = new FakeFormModel();
        boolean loaded;
        boolean closed;
        boolean codeUpToDate = true; // JavaCodeGenerator's constructor default
        String generatedCode = NEW_CODE;

        FakeFormEditor(FakeEditorSupport support) {
            this.support = support;
            model.editor = this;
        }

        public boolean isFormLoaded() { return loaded; }
        public boolean anyPersistenceError() { return false; }
        public Object getFormModel() { return model; }
        public void closeForm() { closed = true; loaded = false; }

        public void regenerateCodeIfNeeded() throws BadLocationException {
            if (!codeUpToDate) {
                codeUpToDate = true;
                support.document.remove(0, support.document.getLength());
                support.document.insertString(0, generatedCode, null);
            }
        }
    }

    /** Fake {@code FormEditorSupport}. */
    public static class FakeEditorSupport {
        final PlainDocument document = new PlainDocument();
        final FakeFormEditor formEditor = new FakeFormEditor(this);
        final boolean designerOpen;
        String savedJava = OLD_CODE;
        boolean formModified;
        boolean formSaved;
        boolean reloaded;

        FakeEditorSupport(boolean designerOpen) throws BadLocationException {
            this.designerOpen = designerOpen;
            document.insertString(0, OLD_CODE, null);
            formEditor.loaded = designerOpen;
        }

        public boolean isOpened() { return formEditor.loaded; }
        public boolean loadForm() { formEditor.loaded = true; return true; }
        public Object getFormEditor() { return formEditor; }
        public Object reloadFormEditor() { reloaded = true; formEditor.codeUpToDate = true; return formEditor; }
        public Document getDocument() { return document; }

        public void saveSourceOnly() throws BadLocationException {
            savedJava = document.getText(0, document.getLength());
        }

        public void saveDocument() throws BadLocationException {
            saveSourceOnly();
            formSaved = true;
            formModified = false;
        }
    }

    @Test
    public void designerClosedRegeneratesAndSavesJava() throws Exception {
        FakeEditorSupport support = new FakeEditorSupport(false);

        RegenerateFormCode.regenerateInFormEditor(support, "MyFrame.java", "MyFrame.form");

        assertEquals(NEW_CODE, support.savedJava,
                "a freshly loaded form is considered up to date - the form must be marked changed "
                + "(immediately, not queued) before regenerating, or nothing is written");
        assertTrue(support.formEditor.closed, "a form loaded only for regeneration must be closed again");
        assertFalse(support.formModified, "the form must not be left with unsaved changes");
    }

    @Test
    public void designerOpenReloadsRegeneratesAndSavesJava() throws Exception {
        FakeEditorSupport support = new FakeEditorSupport(true);

        RegenerateFormCode.regenerateInFormEditor(support, "MyFrame.java", "MyFrame.form");

        assertTrue(support.reloaded, "an open designer must be reloaded from the edited .form");
        assertEquals(NEW_CODE, support.savedJava);
        assertFalse(support.formEditor.closed, "an open designer must stay open");
        assertFalse(support.formModified, "the open designer must not be left with unsaved changes");
    }

    @Test
    public void reportsUnchangedCodeInsteadOfSuccess() throws Exception {
        FakeEditorSupport support = new FakeEditorSupport(false);
        support.formEditor.generatedCode = OLD_CODE;

        RegenerateFormCode.Outcome outcome =
                RegenerateFormCode.regenerateInFormEditor(support, "MyFrame.java", "MyFrame.form");

        assertFalse(outcome.codeChanged());
        assertEquals(OLD_CODE, outcome.generatedText());
    }
}
