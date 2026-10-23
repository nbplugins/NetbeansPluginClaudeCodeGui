package io.github.nbplugins.claudecodegui.mcp.tools;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.awt.EventQueue;
import java.io.File;
import java.io.FileNotFoundException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
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
 */
public class RegenerateFormCode implements Tool<RegenerateFormCode.Params, String> {

    private static final Logger LOGGER = Logger.getLogger(RegenerateFormCode.class.getName());

    /** How long to wait for an open {@code .java} editor to pick up an on-disk change. */
    private static final long DOCUMENT_SYNC_TIMEOUT_MS = 5000;

    /** Guarded-section markers that the guarded reader strips from the editor document. */
    private static final Pattern GUARD_MARKER = Pattern.compile("//GEN-[A-Z]+:\\S*");

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

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
     * @param designerOpen  whether the GUI Builder designer was open
     * @param codeChanged   whether the regenerated code differs from the previous code
     * @param generatedText the {@code .java} document text after regeneration
     */
    record Outcome(boolean designerOpen, boolean codeChanged, String generatedText) {}

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

        if (!javaFo.asText().contains("//GEN-BEGIN:initComponents")) {
            throw new IllegalStateException(javaName + " has no generated initComponents() guarded block "
                    + "(//GEN-BEGIN:initComponents ... //GEN-END:initComponents); the form editor cannot generate code into it");
        }

        DataObject dataObject = DataObject.find(javaFo);
        Object editorSupport = formEditorSupport(dataObject);
        if (editorSupport == null) {
            throw new IllegalStateException(javaName + " is not recognized by NetBeans as a GUI Builder form "
                    + "(is the Swing GUI Builder / form module enabled?)");
        }
        if (dataObject.isModified()) {
            throw new IllegalStateException(javaName + " has unsaved changes in the IDE. "
                    + "Save them (saveDocument) or ask the user to revert them, then call regenerate_form_code again.");
        }
        waitForDocumentInSync(dataObject.getLookup().lookup(EditorCookie.class), javaFo, javaName);

        Outcome outcome = onEdt(() -> regenerateInFormEditor(editorSupport, javaName, formName));
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
        return "Regenerated initComponents() and the variables declaration of " + javaName
                + " from " + formName + " and saved both files." + designerNote
                + " Re-read " + javaName + " and " + formName + " before editing them again.";
    }

    /**
     * Loads (or reloads) the form, regenerates its guarded code and saves it.
     * Must run on the EDT. Package-private so tests can drive it with fake
     * form-editor objects that expose the same public methods.
     *
     * @param editorSupport the form module's {@code FormEditorSupport}
     * @param javaName      name of the {@code .java} file, for messages
     * @param formName      name of the {@code .form} file, for messages
     * @return what happened
     * @throws Exception if the form cannot be loaded or the code cannot be regenerated
     */
    static Outcome regenerateInFormEditor(Object editorSupport, String javaName, String formName) throws Exception {
        boolean open = (Boolean) call(editorSupport, "isOpened");
        Object formEditor;
        if (open) {
            formEditor = call(editorSupport, "reloadFormEditor");
        } else {
            call(editorSupport, "loadForm");
            formEditor = call(editorSupport, "getFormEditor");
        }
        if (formEditor == null || !(Boolean) call(formEditor, "isFormLoaded")
                || (Boolean) call(formEditor, "anyPersistenceError")) {
            if (!open && formEditor != null) {
                call(formEditor, "closeForm");
            }
            throw new IllegalStateException("NetBeans could not load " + formName
                    + " without errors. Check the .form XML, or open the form in the GUI Builder (Design view) to see the errors.");
        }
        Object formModel = call(formEditor, "getFormModel");
        if (formModel == null || (Boolean) call(formModel, "isReadOnly")) {
            if (!open) {
                call(formEditor, "closeForm");
            }
            throw new IllegalStateException(formName + " was loaded read-only (missing or damaged guarded "
                    + "sections in " + javaName + ", or a read-only file); code was not regenerated.");
        }
        Document document = (Document) call(editorSupport, "getDocument");
        String before = documentText(document);
        call(formModel, "fireFormChanged", boolean.class, true);
        call(formEditor, "regenerateCodeIfNeeded");
        String after = documentText(document);
        call(editorSupport, "saveDocument");
        if (!open) {
            call(formEditor, "closeForm");
        }
        return new Outcome(open, !before.equals(after), after);
    }

    /** Returns the form module's {@code EditorSupport}, or {@code null} for a non-form data object. */
    private static Object formEditorSupport(DataObject dataObject) throws Exception {
        try {
            dataObject.getClass().getMethod("getFormEditorSupport");
        } catch (NoSuchMethodException e) {
            return null;
        }
        return call(dataObject, "getFormEditorSupport");
    }

    /**
     * An unmodified open editor reloads on its own after an external change, but
     * asynchronously. Waits until it shows the content on disk so the regenerated
     * code is not merged into, and saved over, a stale document.
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

    /** Invokes a public no-arg method on an object of a non-API form module class. */
    private static Object call(Object target, String methodName) throws Exception {
        return call(target, methodName, null, null);
    }

    /** Invokes a public method with at most one parameter on an object of a non-API form module class. */
    private static Object call(Object target, String methodName, Class<?> paramType, Object arg) throws Exception {
        Method method;
        try {
            method = paramType == null
                    ? target.getClass().getMethod(methodName)
                    : target.getClass().getMethod(methodName, paramType);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Unsupported NetBeans form editor version: "
                    + target.getClass().getName() + "." + methodName + "() not found", e);
        }
        try {
            return paramType == null ? method.invoke(target) : method.invoke(target, arg);
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
