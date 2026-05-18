// Originally forked from https://github.com/emilianbold/claude-code-netbeans
// Original: src/main/java/org/openbeans/claude/netbeans/tools/GetDiagnostics.java
package io.github.nbplugins.claudecodegui.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.netbeans.spi.editor.hints.ErrorDescription;
import org.netbeans.spi.editor.hints.Severity;
import org.openbeans.claude.netbeans.NbUtils;
import org.openbeans.claude.netbeans.tools.Tool;
import org.openbeans.claude.netbeans.tools.params.Diagnostic;
import org.openbeans.claude.netbeans.tools.params.DiagnosticsResponse;
import org.openbeans.claude.netbeans.tools.params.GetDiagnosticsParams;
import org.openbeans.claude.netbeans.tools.params.Range;
import org.openbeans.claude.netbeans.tools.params.Start;
import org.openbeans.claude.netbeans.tools.params.End;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.loaders.DataObject;
import org.openide.nodes.Node;
import org.openide.text.PositionBounds;
import org.openide.windows.TopComponent;

/**
 * Tool to get diagnostic information (errors, warnings) for files.
 *
 * Uses AnnotationHolder (accessed via reflection because it is in a private
 * implementation package) which is the authoritative store of all hint-based
 * diagnostics in the NetBeans editor — errors and warnings from the Java
 * compiler, CSL parsers, LSP clients, etc.
 *
 * TC-11 root cause: the previous implementation used
 * org.netbeans.editor.Annotations which only stores AnnotationDesc objects
 * (bookmarks, breakpoints) — NOT the error/warning hints produced by language
 * parsers. Those hints live exclusively in AnnotationHolder.
 */
public class GetDiagnostics implements Tool<GetDiagnosticsParams, String> {

    /** Creates a new instance of this tool. */
    public GetDiagnostics() {}

    private static final Logger LOGGER = Logger.getLogger(GetDiagnostics.class.getName());

    @Override
    public String getName() {
        return "getDiagnostics";
    }

    @Override
    public String getDescription() {
        return "Get diagnostic information (errors, warnings) for files";
    }

    @Override
    public Class<GetDiagnosticsParams> getParameterClass() {
        return GetDiagnosticsParams.class;
    }

    /**
     * Overridable: converts a file path to a FileObject (bypassed in tests).
     *
     * @param filePath absolute path to the file
     * @return corresponding {@link FileObject}, or {@code null} if not found
     */
    protected FileObject toFileObject(String filePath) {
        return FileUtil.toFileObject(new File(filePath));
    }

    /**
     * Overridable: returns ErrorDescriptions for a FileObject via
     * AnnotationHolder (bypassed in tests).
     * Uses reflection to access the package-private AnnotationHolder class.
     *
     * @param fo the file to query
     * @return list of error descriptions, never {@code null}
     */
    @SuppressWarnings("unchecked")
    protected List<ErrorDescription> getErrorDescriptions(FileObject fo) {
        try {
            // Use ErrorDescription's classloader: AnnotationHolder lives in the
            // same module (org-netbeans-spi-editor-hints) but in a private package.
            // Class.forName() with our module's classloader throws ClassNotFoundException
            // because the package is not exported to us.  Using the classloader of
            // a public class from the same module bypasses the restriction.
            ClassLoader cl = ErrorDescription.class.getClassLoader();
            Class<?> ahClass = Class.forName(
                    "org.netbeans.modules.editor.hints.AnnotationHolder", true, cl);
            Method getInstance = ahClass.getMethod("getInstance", FileObject.class);
            Object holder = getInstance.invoke(null, fo);
            if (holder == null) return Collections.emptyList();
            Method getErrors = ahClass.getMethod("getErrors");
            List<ErrorDescription> result = (List<ErrorDescription>) getErrors.invoke(holder);
            return result != null ? result : Collections.emptyList();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not access AnnotationHolder for {0}: {1}",
                    new Object[]{fo, e.getMessage()});
            return Collections.emptyList();
        }
    }

    private List<DiagnosticsResponse> _run(GetDiagnosticsParams params) throws Exception {
        String uri = params.getUri();
        try {
            if (uri != null) {
                return getDiagnosticsForFile(uri);
            } else {
                return getDiagnosticsForAllFiles();
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to get diagnostics", e);
            return new ArrayList<>();
        }
    }

    @Override
    public String run(GetDiagnosticsParams params) throws Exception {
        try {
            List<DiagnosticsResponse> diagnostics = _run(params);
            ObjectMapper mapper = new ObjectMapper();
            return mapper.writeValueAsString(diagnostics);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to serialize diagnostics", e);
            return "[]";
        }
    }

    private List<DiagnosticsResponse> getDiagnosticsForFile(String uri) {
        try {
            String filePath = uri.startsWith("file://") ? uri.substring(7) : uri;

            if (!NbUtils.isPathWithinOpenProjects(filePath)) {
                throw new SecurityException(
                        "File access denied: Path is not within any open project directory: "
                        + filePath);
            }

            List<Diagnostic> diagnostics = extractDiagnosticsFromFile(filePath);
            if (!diagnostics.isEmpty()) {
                DiagnosticsResponse response = new DiagnosticsResponse();
                response.setUri(URI.create("file://" + filePath));
                response.setDiagnostics(diagnostics);
                return List.of(response);
            }
            return new ArrayList<>();

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to get diagnostics for file: " + uri, e);
            return new ArrayList<>();
        }
    }

    private List<DiagnosticsResponse> getDiagnosticsForAllFiles() {
        try {
            List<DiagnosticsResponse> allResponses = new ArrayList<>();

            for (TopComponent tc : TopComponent.getRegistry().getOpened()) {
                Node[] nodes = tc.getActivatedNodes();
                if (nodes != null && nodes.length > 0) {
                    DataObject dataObject = nodes[0].getLookup().lookup(DataObject.class);
                    if (dataObject != null) {
                        FileObject fileObject = dataObject.getPrimaryFile();
                        if (fileObject != null) {
                            File file = FileUtil.toFile(fileObject);
                            if (file != null) {
                                List<Diagnostic> fileDiagnostics =
                                        extractDiagnosticsFromFile(file.getAbsolutePath());
                                if (!fileDiagnostics.isEmpty()) {
                                    DiagnosticsResponse response = new DiagnosticsResponse();
                                    response.setUri(URI.create("file://" + file.getAbsolutePath()));
                                    response.setDiagnostics(fileDiagnostics);
                                    allResponses.add(response);
                                }
                            }
                        }
                    }
                }
            }

            return allResponses;

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to get diagnostics for all files", e);
            return new ArrayList<>();
        }
    }

    /**
     * Extracts diagnostics for a file via getErrorDescriptions(FileObject).
     *
     * @param filePath absolute path of the file to inspect
     * @return list of {@link Diagnostic} entries, never {@code null}
     */
    protected List<Diagnostic> extractDiagnosticsFromFile(String filePath) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        try {
            FileObject fileObject = toFileObject(filePath);
            if (fileObject == null) {
                LOGGER.log(Level.WARNING, "getDiagnostics: no FileObject for {0}", filePath);
                return diagnostics;
            }

            List<ErrorDescription> errors = getErrorDescriptions(fileObject);
            LOGGER.log(Level.INFO, "getDiagnostics: {0} errors for {1}",
                    new Object[]{errors.size(), filePath});

            for (ErrorDescription ed : errors) {
                Diagnostic diagnostic = convertErrorDescription(ed);
                if (diagnostic != null) {
                    diagnostics.add(diagnostic);
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not extract diagnostics from file: " + filePath, e);
        }
        return diagnostics;
    }

    private Diagnostic convertErrorDescription(ErrorDescription ed) {
        if (ed == null) return null;

        String message = ed.getDescription();
        if (message == null || message.trim().isEmpty()) return null;

        Severity sev = ed.getSeverity();
        String severity = "info";
        String source = "netbeans";
        if (sev == Severity.ERROR || sev == Severity.VERIFIER) {
            severity = "error";
            source = "compiler";
        } else if (sev == Severity.WARNING) {
            severity = "warning";
            source = "compiler";
        } else if (sev == Severity.HINT) {
            severity = "hint";
            source = "editor";
        }

        int line = 0;
        int column = 0;
        try {
            PositionBounds range = ed.getRange();
            if (range != null) {
                line = range.getBegin().getLine() + 1; // convert to 1-based
                column = range.getBegin().getColumn();
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Could not get position for: " + message, e);
        }

        String code = null;
        if (message.contains("[") && message.contains("]")) {
            int start = message.lastIndexOf("[");
            int end = message.lastIndexOf("]");
            if (start < end) {
                code = message.substring(start + 1, end);
            }
        }

        Diagnostic diagnostic = new Diagnostic();
        diagnostic.setMessage(message);

        Diagnostic.Severity severityEnum;
        if ("error".equalsIgnoreCase(severity)) {
            severityEnum = Diagnostic.Severity.ERROR;
        } else if ("warning".equalsIgnoreCase(severity)) {
            severityEnum = Diagnostic.Severity.WARNING;
        } else {
            severityEnum = Diagnostic.Severity.INFO;
        }
        diagnostic.setSeverity(severityEnum);
        diagnostic.setSource(source);
        diagnostic.setCode(code);

        Start start = new Start();
        start.setLine(line > 0 ? line - 1 : 0);
        start.setCharacter(column);
        End end = new End();
        end.setLine(line > 0 ? line - 1 : 0);
        end.setCharacter(column);
        Range r = new Range();
        r.setStart(start);
        r.setEnd(end);
        diagnostic.setRange(r);

        return diagnostic;
    }
}
