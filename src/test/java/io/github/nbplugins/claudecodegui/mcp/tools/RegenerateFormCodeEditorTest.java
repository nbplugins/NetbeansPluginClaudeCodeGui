package io.github.nbplugins.claudecodegui.mcp.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.PlainDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives {@link RegenerateFormCode#regenerateInFormEditor} with fakes that expose
 * the same public methods as the NetBeans form module classes and reproduce their
 * behavior (verified against the NetBeans 31 bytecode). Like the real classes, the
 * getters declare the concrete form editor / form model types, from which
 * {@link RegenerateFormCode.FormEditorApi} resolves the methods up front:
 * <ul>
 *   <li>{@code JavaCodeGenerator.codeUpToDate} starts {@code true}, so right after a
 *       form is loaded {@code regenerateCodeIfNeeded()} does nothing;</li>
 *   <li>only a modifying form event clears it - {@code fireFormChanged(true)} delivers
 *       it immediately, {@code fireFormChanged(false)} queues it via invokeLater, i.e.
 *       after the caller's EDT task;</li>
 *   <li>a form change marks the form modified; {@code saveSourceOnly()} saves just the
 *       .java, {@code saveDocument()} saves .form and .java;</li>
 *   <li>{@code fireEventHandlerAdded()} is a modifying event too, so on the EDT it is
 *       queued; the code generator writes the handler method when the next immediate
 *       event delivers the pending events.</li>
 * </ul>
 */
public class RegenerateFormCodeEditorTest {

    private static final String OLD_CODE = "class MyFrame { /* old initComponents */ }";
    private static final String NEW_CODE = "class MyFrame { /* initComponents with jButton1 */ }";

    /** Fake {@code Event}. */
    public static class FakeEvent {
    }

    /** Fake {@code FormEvents}: the event handlers declared in the .form. */
    public static class FakeFormEvents {
        final List<String> handlers = new ArrayList<>();

        public String[] getAllEventHandlers() { return handlers.toArray(new String[0]); }

        public FakeEvent[] getEventsForHandler(String handler) {
            return handlers.contains(handler) ? new FakeEvent[] {new FakeEvent()} : new FakeEvent[0];
        }
    }

    /** Fake {@code FormModel}. */
    public static class FakeFormModel {
        FakeFormEditor editor;
        final FakeFormEvents events = new FakeFormEvents();
        boolean queuedEvent;
        /** Handler-added events: queued on the EDT like other modifying events. */
        final List<String> queuedHandlers = new ArrayList<>();

        public boolean isReadOnly() { return false; }

        public FakeFormEvents getFormEvents() { return events; }

        public Object fireEventHandlerAdded(FakeEvent event, String handler, String bodyText,
                                           String annotationText, boolean createdNew) {
            assertNotNull(event);
            assertNull(bodyText, "the IDE's default handler body must be used");
            assertTrue(createdNew);
            queuedHandlers.add(handler);
            return null;
        }

        public Object fireFormChanged(boolean immediately) {
            if (immediately) {
                // delivers the pending events in one batch
                editor.generatedHandlers.addAll(queuedHandlers);
                queuedHandlers.clear();
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
        boolean failRegeneration;
        String generatedCode = NEW_CODE;
        /** Handler methods written by the code generator when the handler-added events are delivered. */
        final List<String> generatedHandlers = new ArrayList<>();

        FakeFormEditor(FakeEditorSupport support) {
            this.support = support;
            model.editor = this;
        }

        public boolean isFormLoaded() { return loaded; }
        public boolean anyPersistenceError() { return false; }
        public FakeFormModel getFormModel() { return model; }
        public void closeForm() { closed = true; loaded = false; }

        public void regenerateCodeIfNeeded() throws BadLocationException {
            if (failRegeneration) {
                throw new IllegalStateException("code generation failed");
            }
            if (!codeUpToDate) {
                codeUpToDate = true;
                support.document.remove(0, support.document.getLength());
                support.document.insertString(0, generatedCode, null);
                for (String handler : generatedHandlers) {
                    support.document.insertString(support.document.getLength(), " handler:" + handler, null);
                }
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
        public FakeFormEditor getFormEditor() { return formEditor; }
        public FakeFormEditor reloadFormEditor() { reloaded = true; formEditor.codeUpToDate = true; return formEditor; }
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

    /** Resolves the form editor API from the support's runtime class first, as the tool does. */
    private static RegenerateFormCode.Outcome regenerate(Object support, String javaName, String formName,
                                                         Set<String> existingHandlers) throws Exception {
        return RegenerateFormCode.regenerateInFormEditor(RegenerateFormCode.FormEditorApi.resolve(support.getClass()),
                support, javaName, formName, existingHandlers);
    }

    @Test
    public void designerClosedRegeneratesAndSavesJava() throws Exception {
        FakeEditorSupport support = new FakeEditorSupport(false);

        regenerate(support, "MyFrame.java", "MyFrame.form", Set.of());

        assertEquals(NEW_CODE, support.savedJava,
                "a freshly loaded form is considered up to date - the form must be marked changed "
                + "(immediately, not queued) before regenerating, or nothing is written");
        assertTrue(support.formEditor.closed, "a form loaded only for regeneration must be closed again");
        assertFalse(support.formModified, "the form must not be left with unsaved changes");
    }

    @Test
    public void designerOpenReloadsRegeneratesAndSavesJava() throws Exception {
        FakeEditorSupport support = new FakeEditorSupport(true);

        regenerate(support, "MyFrame.java", "MyFrame.form", Set.of());

        assertTrue(support.reloaded, "an open designer must be reloaded from the edited .form");
        assertEquals(NEW_CODE, support.savedJava);
        assertFalse(support.formEditor.closed, "an open designer must stay open");
        assertFalse(support.formModified, "the open designer must not be left with unsaved changes");
    }

    @Test
    public void generatesOnlyEventHandlersMissingInJava() throws Exception {
        FakeEditorSupport support = new FakeEditorSupport(false);
        support.formEditor.model.events.handlers.addAll(List.of("otherNewHandler", "existingHandler", "newHandler"));

        RegenerateFormCode.Outcome outcome = regenerate(
                support, "MyFrame.java", "MyFrame.form", Set.of("existingHandler"));

        assertEquals(List.of("newHandler", "otherNewHandler"), outcome.addedHandlers(), "added sorted by name");
        assertEquals(NEW_CODE + " handler:newHandler handler:otherNewHandler", support.savedJava,
                "the queued handler events must be delivered before regenerating; an existing handler must not be re-added");
        assertTrue(support.formEditor.model.queuedHandlers.isEmpty(), "no handler event may be left queued");
    }

    @Test
    public void addsNoEventHandlersWhenAllExist() throws Exception {
        FakeEditorSupport support = new FakeEditorSupport(true);
        support.formEditor.model.events.handlers.add("existingHandler");

        RegenerateFormCode.Outcome outcome = regenerate(
                support, "MyFrame.java", "MyFrame.form", Set.of("existingHandler"));

        assertEquals(List.of(), outcome.addedHandlers());
        assertEquals(NEW_CODE, support.savedJava);
    }

    @Test
    public void reportsUnchangedCodeInsteadOfSuccess() throws Exception {
        FakeEditorSupport support = new FakeEditorSupport(false);
        support.formEditor.generatedCode = OLD_CODE;

        RegenerateFormCode.Outcome outcome =
                regenerate(support, "MyFrame.java", "MyFrame.form", Set.of());

        assertFalse(outcome.codeChanged());
        assertEquals(OLD_CODE, outcome.generatedText());
    }

    @Test
    public void closesFormLoadedForRegenerationWhenRegenerationFails() throws Exception {
        FakeEditorSupport support = new FakeEditorSupport(false);
        support.formEditor.failRegeneration = true;

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> regenerate(support, "MyFrame.java", "MyFrame.form", Set.of()));

        assertEquals("code generation failed", e.getMessage());
        assertTrue(support.formEditor.closed, "a form loaded only for regeneration must be closed even on failure");
    }

    @Test
    public void keepsOpenDesignerOpenWhenRegenerationFails() throws Exception {
        FakeEditorSupport support = new FakeEditorSupport(true);
        support.formEditor.failRegeneration = true;

        assertThrows(IllegalStateException.class,
                () -> regenerate(support, "MyFrame.java", "MyFrame.form", Set.of()));

        assertFalse(support.formEditor.closed, "the user's open designer must not be closed");
    }

    /** Fake of a form editor support from an incompatible NetBeans version: {@code saveDocument()} is missing. */
    public static class IncompatibleEditorSupport {
        final FakeEditorSupport delegate;
        boolean touched;

        IncompatibleEditorSupport() throws BadLocationException {
            delegate = new FakeEditorSupport(false);
        }

        public boolean isOpened() { touched = true; return false; }
        public boolean loadForm() { touched = true; return delegate.loadForm(); }
        public FakeFormEditor getFormEditor() { touched = true; return delegate.formEditor; }
        public FakeFormEditor reloadFormEditor() { touched = true; return delegate.formEditor; }
        public Document getDocument() { touched = true; return delegate.document; }
    }

    @Test
    public void incompatibleVersionFailsBeforeTouchingTheForm() throws Exception {
        IncompatibleEditorSupport support = new IncompatibleEditorSupport();

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> regenerate(support, "MyFrame.java", "MyFrame.form", Set.of()));

        assertTrue(e.getMessage().startsWith("Unsupported NetBeans form editor version"), e.getMessage());
        assertTrue(e.getMessage().contains("saveDocument"), e.getMessage());
        assertFalse(support.touched, "no form editor method may be called before all are resolved");
        assertFalse(support.delegate.formEditor.loaded);
    }

    /** Non-public class: its public methods cannot be invoked reflectively from outside. */
    static class NonPublicEditorSupport {
        public FakeFormEditor getFormEditor() { return null; }
    }

    @Test
    public void nonPublicClassIsReportedAsUnsupportedVersion() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> regenerate(new NonPublicEditorSupport(), "MyFrame.java", "MyFrame.form", Set.of()));

        assertTrue(e.getMessage().startsWith("Unsupported NetBeans form editor version"), e.getMessage());
        assertTrue(e.getMessage().contains("is not accessible"), e.getMessage());
    }
}
