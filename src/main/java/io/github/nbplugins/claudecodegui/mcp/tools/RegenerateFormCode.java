package io.github.nbplugins.claudecodegui.mcp.tools;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.awt.EventQueue;
import java.io.File;
import java.io.FileNotFoundException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import org.openbeans.claude.netbeans.NbUtils;
import org.openbeans.claude.netbeans.tools.Tool;
import org.openide.cookies.EditorCookie;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.loaders.DataObject;

/**
 * MCP tool that regenerates the GUI Builder guarded code ({@code initComponents()}
 * and the variables declaration block) of a {@code .java} file from its
 * {@code .form} file, exactly as the NetBeans form editor does.
 *
 * <p>The form module ({@code org.netbeans.modules.form}) has no public API, so the
 * form editor support is driven via reflection on the live objects. The call
 * sequence mirrors the one the form refactoring module uses:
 * <ul>
 *   <li>designer closed: {@code FormEditorSupport.loadForm()} → mark changed →
 *       {@code FormEditor.regenerateCodeIfNeeded()} → {@code saveDocument()} →
 *       {@code FormEditor.closeForm()}</li>
 *   <li>designer open: {@code FormEditorSupport.reloadFormEditor()} (re-reads the
 *       edited {@code .form} from disk) → mark changed → {@code regenerateCodeIfNeeded()} →
 *       {@code saveDocument()}</li>
 * </ul>
 * "Mark changed" is {@code FormModel.fireFormChanged(true)}: the code generator treats
 * a freshly loaded form as up to date, so without a modifying form event
 * {@code regenerateCodeIfNeeded()} does nothing. The event must be delivered
 * immediately ({@code true}); a queued one arrives after this EDT task. Since the
 * event marks the form modified, both files are saved like the IDE's Save does,
 * which re-serializes the {@code .form} file.
 *
 * <p>Event handler methods are not part of the regenerated code: the form editor writes
 * a handler method only when an event handler is added to the form. So before marking
 * the form changed, {@code FormModel.fireEventHandlerAdded()} is fired for each handler
 * of the {@code .form} that has no {@code //GEN-FIRST:event_<handler>} section in the
 * {@code .java} yet, as the GUI Builder does when the user adds an event. On the EDT
 * these events are queued; {@code fireFormChanged(true)} delivers them in one batch,
 * which generates the handler methods with the IDE's default body.
 */
public class RegenerateFormCode implements Tool<RegenerateFormCode.Params, String> {

    private static final Logger LOGGER = Logger.getLogger(RegenerateFormCode.class.getName());

    /** How long to wait for an open {@code .java} editor to pick up an on-disk change. */
    private static final long DOCUMENT_SYNC_TIMEOUT_MS = 5000;

    /** Guarded-section markers that the guarded reader strips from the editor document. */
    private static final Pattern GUARD_MARKER = Pattern.compile("//GEN-[A-Z]+:\\S*");

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** The form module's data object of a {@code .java} / {@code .form} pair. */
    private static final String FORM_DATA_OBJECT = "org.netbeans.modules.form.FormDataObject";

    /** Guarded header of an event handler method, capturing the handler name. */
    private static final Pattern EVENT_HANDLER_HEADER = Pattern.compile("//GEN-FIRST:event_([\\w$]+)");

    /** Tool parameters. */
    public static class Params {
        @JsonProperty("filePath")
        private String filePath;

        /** Creates empty parameters (used by Jackson). */
        public Params() {}

        /**
         * Creates parameters for the given file.
         *
         * @param filePath absolute path of the {@code .form} or {@code .java} file
         */
        public Params(String filePath) { this.filePath = filePath; }

        /** @return absolute path of the {@code .form} or {@code .java} file */
        public String getFilePath() { return filePath; }
    }

    /** The {@code .java} / {@code .form} pair of one GUI form. */
    record FormFiles(File javaFile, File formFile) {}

    /**
     * Result of a regeneration in the form editor.
     *
     * @param designerOpen   whether the GUI Builder designer was open
     * @param codeChanged    whether the regenerated code differs from the previous code
     * @param generatedText  the {@code .java} document text after regeneration
     * @param addedHandlers  event handler methods that were missing in the {@code .java} and were generated
     */
    record Outcome(boolean designerOpen, boolean codeChanged, String generatedText, List<String> addedHandlers) {}

    /** Creates a new instance of this tool. */
    public RegenerateFormCode() {}

    @Override
    public String getName() { return "regenerate_form_code"; }

    @Override
    public String getDescription() {
        return "Regenerate the NetBeans GUI Builder generated code (initComponents() and the "
             + "variables declaration block, i.e. the //GEN-BEGIN/GEN-END guarded sections) of a "
             + "Swing form's .java file from its .form file, exactly as the NetBeans form editor does, "
             + "and save it. ALWAYS call this after creating or modifying a .form file "
             + "instead of editing the guarded sections in the .java file by hand. "
             + "Event handler methods of the .form that are missing in the .java are generated "
             + "with a TODO body (//GEN-FIRST/GEN-LAST guarded header and footer); existing handler bodies are kept. "
             + "NetBeans also re-saves the .form file in its own formatting. "
             + "Re-read both files afterwards before editing them again.";
    }

    @Override
    public Class<Params> getParameterClass() { return Params.class; }

    @Override
    public String run(Params params) throws Exception {
        String filePath = params.getFilePath();
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("filePath is required");
        }
        FormFiles files = resolveFormFiles(filePath);
        checkAccess(files.javaFile());
        checkAccess(files.formFile());
        LOGGER.fine("regenerate_form_code: " + files.javaFile());
        return regenerate(files);
    }

    /**
     * Resolves the {@code .java} / {@code .form} pair for a path pointing to either of them.
     *
     * @param filePath path of the {@code .form} or {@code .java} file
     * @return both files of the form
     * @throws IllegalArgumentException if the path is not a {@code .form}/{@code .java}
     *         file or either file of the pair does not exist
     */
    static FormFiles resolveFormFiles(String filePath) {
        File file = new File(filePath).getAbsoluteFile();
        String name = file.getName();
        String baseName;
        if (name.endsWith(".form")) {
            baseName = name.substring(0, name.length() - ".form".length());
        } else if (name.endsWith(".java")) {
            baseName = name.substring(0, name.length() - ".java".length());
        } else {
            throw new IllegalArgumentException("Expected a .form or .java file: " + filePath);
        }
        File javaFile = new File(file.getParentFile(), baseName + ".java");
        File formFile = new File(file.getParentFile(), baseName + ".form");
        if (!javaFile.isFile()) {
            throw new IllegalArgumentException("Java source of the form does not exist: " + javaFile);
        }
        if (!formFile.isFile()) {
            throw new IllegalArgumentException("No .form file next to " + javaFile.getName()
                    + " - it is not a NetBeans GUI Builder form: " + formFile);
        }
        return new FormFiles(javaFile, formFile);
    }

    /**
     * Returns {@code true} when the editor document text matches the file content on
     * disk, ignoring whitespace and the {@code //GEN-...} guard markers that the
     * guarded-section reader strips when loading the document.
     *
     * @param documentText text of the loaded editor document
     * @param diskText     content of the file on disk
     * @return whether the document reflects the file on disk
     */
    static boolean documentMatchesDisk(String documentText, String diskText) {
        return normalize(documentText).equals(normalize(diskText));
    }

    private static String normalize(String text) {
        return WHITESPACE.matcher(GUARD_MARKER.matcher(text).replaceAll("")).replaceAll("");
    }

    /**
     * Returns the names of the event handler methods that have a guarded
     * {@code //GEN-FIRST:event_<handler>} header in the {@code .java} file.
     *
     * @param javaText content of the {@code .java} file on disk
     * @return the handler names
     */
    static Set<String> existingEventHandlers(String javaText) {
        Set<String> handlers = new HashSet<>();
        Matcher matcher = EVENT_HANDLER_HEADER.matcher(javaText);
        while (matcher.find()) {
            handlers.add(matcher.group(1));
        }
        return handlers;
    }

    /**
     * Rejects files outside the open projects.
     * Protected so tests can bypass the open-projects lookup.
     *
     * @param file file to check
     */
    protected void checkAccess(File file) {
        if (!NbUtils.isPathWithinOpenProjects(file.getPath())) {
            throw new SecurityException("Form regeneration denied: Path is not within any open project directory: " + file);
        }
    }

    /**
     * Loads the form in the NetBeans form editor, regenerates the guarded code and
     * saves the {@code .java} file. Protected so tests can bypass the IDE runtime.
     *
     * @param files the form's {@code .java} / {@code .form} pair
     * @return result message for Claude
     * @throws Exception if the form cannot be loaded or the code cannot be regenerated
     */
    protected String regenerate(FormFiles files) throws Exception {
        String javaName = files.javaFile().getName();
        String formName = files.formFile().getName();
        FileObject javaFo = FileUtil.toFileObject(FileUtil.normalizeFile(files.javaFile()));
        FileObject formFo = FileUtil.toFileObject(FileUtil.normalizeFile(files.formFile()));
        if (javaFo == null || formFo == null) {
            throw new FileNotFoundException(javaFo == null ? files.javaFile().getPath() : files.formFile().getPath());
        }
        // Claude edits the files behind the IDE's back - let the filesystem notice the change.
        formFo.refresh();
        javaFo.refresh();

        String javaText = javaFo.asText();
        if (!javaText.contains("//GEN-BEGIN:initComponents")) {
            throw new IllegalStateException(javaName + " has no generated initComponents() guarded block "
                    + "(//GEN-BEGIN:initComponents ... //GEN-END:initComponents); the form editor cannot generate code into it");
        }

        DataObject dataObject = DataObject.find(javaFo);
        Object editorSupport = formEditorSupport(dataObject);
        if (editorSupport == null) {
            throw new IllegalStateException(javaName + " is not recognized by NetBeans as a GUI Builder form "
                    + "(is the Swing GUI Builder / form module enabled?)");
        }
        // An unsupported NetBeans version fails here, before the form is touched.
        FormEditorApi api = FormEditorApi.resolve(editorSupport.getClass());
        if (dataObject.isModified()) {
            throw new IllegalStateException(javaName + " has unsaved changes in the IDE. "
                    + "Save them (saveDocument) or ask the user to revert them, then call regenerate_form_code again.");
        }
        waitForDocumentInSync(dataObject.getLookup().lookup(EditorCookie.class), javaFo, javaName);

        Set<String> existingHandlers = existingEventHandlers(javaText);
        Outcome outcome = onEdt(() -> regenerateInFormEditor(api, editorSupport, javaName, formName, existingHandlers));
        String designerNote = outcome.designerOpen()
                ? " The open GUI Builder was reloaded from the edited .form." : "";
        if (!outcome.codeChanged()) {
            return "The generated code in " + javaName + " already matches " + formName
                    + "; nothing was regenerated." + designerNote;
        }
        if (!documentMatchesDisk(outcome.generatedText(), javaFo.asText())) {
            throw new IllegalStateException("The code was regenerated in the IDE but " + javaName
                    + " on disk was not updated. Ask the user to save it in the IDE.");
        }
        String handlersNote = outcome.addedHandlers().isEmpty() ? ""
                : " Added the missing event handler methods " + String.join(", ", outcome.addedHandlers())
                        + " with a TODO body; write their code between the //GEN-FIRST and //GEN-LAST lines.";
        return "Regenerated initComponents() and the variables declaration of " + javaName
                + " from " + formName + " and saved both files." + handlersNote + designerNote
                + " Re-read " + javaName + " and " + formName + " before editing them again.";
    }

    /**
     * Loads (or reloads) the form, regenerates its guarded code and saves it.
     * Package-private so tests can drive it with fake form-editor objects that
     * expose the same public methods.
     *
     * <p>Must run on the EDT, including the disk I/O of {@code saveDocument()}: the form
     * module confines the form model to the EDT and does not switch threads itself -
     * {@code FormEditorSupport.saveDocument()} serializes the model into the {@code .form}
     * file ({@code FormEditor.saveFormData()}) and may report saving errors in dialogs.
     * Validation and waiting for the editor are done by the caller, off the EDT.
     *
     * <p>A form loaded only for the regeneration (designer closed) is always closed again,
     * also when the regeneration fails.
     *
     * @param api              the form module methods, resolved by the caller before the form is touched
     * @param editorSupport    the form module's {@code FormEditorSupport}
     * @param javaName         name of the {@code .java} file, for messages
     * @param formName         name of the {@code .form} file, for messages
     * @param existingHandlers event handler methods the {@code .java} already has (see {@link #existingEventHandlers})
     * @return what happened
     * @throws Exception if the form cannot be loaded or the code cannot be regenerated
     */
    static Outcome regenerateInFormEditor(FormEditorApi api, Object editorSupport, String javaName, String formName,
                                          Set<String> existingHandlers) throws Exception {
        boolean open = (Boolean) invoke(api.isOpened(), editorSupport);
        Object formEditor;
        if (open) {
            formEditor = invoke(api.reloadFormEditor(), editorSupport);
        } else {
            invoke(api.loadForm(), editorSupport);
            formEditor = invoke(api.getFormEditor(), editorSupport);
        }
        if (formEditor == null) {
            throw formLoadError(formName);
        }
        try {
            if (!(Boolean) invoke(api.isFormLoaded(), formEditor)
                    || (Boolean) invoke(api.anyPersistenceError(), formEditor)) {
                throw formLoadError(formName);
            }
            Object formModel = invoke(api.getFormModel(), formEditor);
            if (formModel == null || (Boolean) invoke(api.isReadOnly(), formModel)) {
                throw new IllegalStateException(formName + " was loaded read-only (missing or damaged guarded "
                        + "sections in " + javaName + ", or a read-only file); code was not regenerated.");
            }
            Document document = (Document) invoke(api.getDocument(), editorSupport);
            String before = documentText(document);
            List<String> addedHandlers = addMissingEventHandlers(api, formModel, existingHandlers);
            // Also delivers the queued handler events, in the same batch.
            invoke(api.fireFormChanged(), formModel, true);
            invoke(api.regenerateCodeIfNeeded(), formEditor);
            String after = documentText(document);
            invoke(api.saveDocument(), editorSupport);
            return new Outcome(open, !before.equals(after), after, addedHandlers);
        } finally {
            if (!open) {
                invoke(api.closeForm(), formEditor);
            }
        }
    }

    /**
     * Fires {@code FormModel.fireEventHandlerAdded(event, handler, null, null, true)} for
     * each event handler of the form that the {@code .java} does not have yet - what the
     * GUI Builder does when the user adds an event handler. On the EDT the form model
     * queues these events; the code generator writes the handler methods (with the IDE's
     * default body) when the next immediate event delivers the batch. Delivered in a batch,
     * they also do not open the editor at the new handler, as a single added handler would.
     * The form keeps its handlers in a hash map, so they are added sorted by name, which
     * is also the order of the generated methods.
     *
     * @return the handlers that were added, sorted by name
     */
    private static List<String> addMissingEventHandlers(FormEditorApi api, Object formModel,
                                                        Set<String> existingHandlers) throws Exception {
        Object formEvents = invoke(api.getFormEvents(), formModel);
        String[] handlers = (String[]) invoke(api.getAllEventHandlers(), formEvents);
        Arrays.sort(handlers);
        List<String> added = new ArrayList<>();
        for (String handler : handlers) {
            if (existingHandlers.contains(handler)) {
                continue;
            }
            Object[] events = (Object[]) invoke(api.getEventsForHandler(), formEvents, handler);
            if (events.length == 0) {
                continue;
            }
            invoke(api.fireEventHandlerAdded(), formModel, events[0], handler, null, null, true);
            added.add(handler);
        }
        return added;
    }

    private static IllegalStateException formLoadError(String formName) {
        return new IllegalStateException("NetBeans could not load " + formName
                + " without errors. Check the .form XML, or open the form in the GUI Builder (Design view) to see the errors.");
    }

    /**
     * The form module methods this tool invokes on the form editor support. They are
     * all resolved, with their return types checked, right after the editor support is
     * obtained and before any of them is called, so that an incompatible NetBeans
     * version fails with "Unsupported NetBeans form editor version" before the form is
     * touched. The {@code FormEditor}, {@code FormModel}, {@code FormEvents} and
     * {@code Event} classes are taken from the declared return types of
     * {@code getFormEditor()}, {@code getFormModel()}, {@code getFormEvents()} and
     * {@code getEventsForHandler()}.
     *
     * <p>Verified against NetBeans 23 and 31 ({@code org.netbeans.modules.form.FormDataObject},
     * {@code org.netbeans.modules.nbform.FormEditorSupport}, {@code org.netbeans.modules.form.FormEditor},
     * {@code FormModel}, {@code FormEvents}, {@code Event}): the method signatures and the
     * form model's event queuing are the same in both. {@code RegenerateFormCodeIT} runs
     * the tool against the real form editor of NetBeans 23, the compile-time version.
     */
    record FormEditorApi(Method isOpened, Method loadForm, Method reloadFormEditor, Method getFormEditor,
                         Method getDocument, Method saveDocument,
                         Method isFormLoaded, Method anyPersistenceError, Method getFormModel,
                         Method regenerateCodeIfNeeded, Method closeForm,
                         Method isReadOnly, Method fireFormChanged,
                         Method getFormEvents, Method getAllEventHandlers, Method getEventsForHandler,
                         Method fireEventHandlerAdded) {

        /**
         * Resolves the form module methods starting from the runtime class of the form editor support.
         *
         * @param supportClass runtime class of the form module's {@code FormEditorSupport}
         * @return the resolved methods
         * @throws IllegalStateException if a method is missing, not accessible or has an unexpected return type
         */
        static FormEditorApi resolve(Class<?> supportClass) {
            Method getFormEditor = method(supportClass, "getFormEditor", Object.class);
            Class<?> editorClass = getFormEditor.getReturnType();
            Method getFormModel = method(editorClass, "getFormModel", Object.class);
            Class<?> modelClass = getFormModel.getReturnType();
            Method getFormEvents = method(modelClass, "getFormEvents", Object.class);
            Class<?> eventsClass = getFormEvents.getReturnType();
            Method getEventsForHandler = method(eventsClass, "getEventsForHandler", Object[].class, String.class);
            Class<?> eventClass = getEventsForHandler.getReturnType().getComponentType();
            return new FormEditorApi(
                    method(supportClass, "isOpened", boolean.class),
                    method(supportClass, "loadForm", null),
                    method(supportClass, "reloadFormEditor", editorClass),
                    getFormEditor,
                    method(supportClass, "getDocument", Document.class),
                    method(supportClass, "saveDocument", null),
                    method(editorClass, "isFormLoaded", boolean.class),
                    method(editorClass, "anyPersistenceError", boolean.class),
                    getFormModel,
                    method(editorClass, "regenerateCodeIfNeeded", null),
                    method(editorClass, "closeForm", null),
                    method(modelClass, "isReadOnly", boolean.class),
                    method(modelClass, "fireFormChanged", null, boolean.class),
                    getFormEvents,
                    method(eventsClass, "getAllEventHandlers", String[].class),
                    getEventsForHandler,
                    method(modelClass, "fireEventHandlerAdded", null,
                            eventClass, String.class, String.class, String.class, boolean.class));
        }

        /**
         * Looks up a public method of a public class.
         *
         * @param returnType required return type, or {@code null} if the result is not used
         */
        private static Method method(Class<?> owner, String name, Class<?> returnType, Class<?>... paramTypes) {
            Method method;
            try {
                method = owner.getMethod(name, paramTypes);
            } catch (NoSuchMethodException e) {
                throw unsupported(owner, name, "not found", e);
            }
            if (!Modifier.isPublic(method.getDeclaringClass().getModifiers())) {
                throw unsupported(owner, name, "is not accessible", null);
            }
            if (returnType != null && !returnType.isAssignableFrom(method.getReturnType())) {
                throw unsupported(owner, name, "returns " + method.getReturnType().getName(), null);
            }
            return method;
        }
    }

    private static IllegalStateException unsupported(Class<?> owner, String methodName, String problem, Exception cause) {
        return new IllegalStateException("Unsupported NetBeans form editor version: "
                + owner.getName() + "." + methodName + "() " + problem, cause);
    }

    /**
     * Returns the form module's {@code EditorSupport}, or {@code null} for a non-form data
     * object. {@code FormDataObject.getFormEditorSupport()} only creates the support
     * object; it does not load the form.
     *
     * @throws IllegalStateException if the data object is the form module's
     *         {@code FormDataObject} but has no {@code getFormEditorSupport()}
     */
    private static Object formEditorSupport(DataObject dataObject) throws Exception {
        Method getFormEditorSupport;
        try {
            getFormEditorSupport = dataObject.getClass().getMethod("getFormEditorSupport");
        } catch (NoSuchMethodException e) {
            if (isFormDataObject(dataObject.getClass())) {
                throw unsupported(dataObject.getClass(), "getFormEditorSupport", "not found", e);
            }
            return null;
        }
        return invoke(getFormEditorSupport, dataObject);
    }

    private static boolean isFormDataObject(Class<?> dataObjectClass) {
        for (Class<?> c = dataObjectClass; c != null; c = c.getSuperclass()) {
            if (c.getName().equals(FORM_DATA_OBJECT)) {
                return true;
            }
        }
        return false;
    }

    /**
     * An unmodified open editor reloads on its own after an external change, but
     * asynchronously. Waits until it shows the content on disk so the regenerated
     * code is not merged into, and saved over, a stale document.
     *
     * <p>The comparison ignores whitespace (see {@link #documentMatchesDisk}), so an
     * external edit that changed only whitespace counts as in sync; the form editor
     * then regenerates into the document without that change. Polls with
     * {@code Thread.sleep} and waits for the EDT, so it must never be called on the EDT.
     */
    private static void waitForDocumentInSync(EditorCookie editorCookie, FileObject javaFo, String javaName)
            throws Exception {
        Document document = editorCookie == null ? null : editorCookie.getDocument();
        if (document == null) {
            return; // not loaded - the form editor reads it from disk
        }
        String diskText = javaFo.asText();
        long deadline = System.currentTimeMillis() + DOCUMENT_SYNC_TIMEOUT_MS;
        while (!documentMatchesDisk(documentText(document), diskText)) {
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("The open editor of " + javaName + " still differs from the file on disk. "
                        + "Ask the user to reload or close it in the IDE, then call regenerate_form_code again.");
            }
            Thread.sleep(100);
        }
        // Let a form reload queued by that document reload run before ours.
        onEdt(() -> null);
    }

    private static String documentText(Document document) throws BadLocationException {
        AtomicReference<String> text = new AtomicReference<>();
        AtomicReference<BadLocationException> error = new AtomicReference<>();
        document.render(() -> {
            try {
                text.set(document.getText(0, document.getLength()));
            } catch (BadLocationException e) {
                error.set(e);
            }
        });
        if (error.get() != null) {
            throw error.get();
        }
        return text.get();
    }

    /** Invokes a method of a non-API form module class, rethrowing what the method threw. */
    private static Object invoke(Method method, Object target, Object... args) throws Exception {
        try {
            return method.invoke(target, args);
        } catch (IllegalAccessException e) {
            throw unsupported(target.getClass(), method.getName(), "is not accessible", e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw e;
        }
    }

    private static <T> T onEdt(Callable<T> task) throws Exception {
        if (EventQueue.isDispatchThread()) {
            return task.call();
        }
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        EventQueue.invokeAndWait(() -> {
            try {
                result.set(task.call());
            } catch (Exception e) {
                error.set(e);
            }
        });
        if (error.get() != null) {
            throw error.get();
        }
        return result.get();
    }
}
