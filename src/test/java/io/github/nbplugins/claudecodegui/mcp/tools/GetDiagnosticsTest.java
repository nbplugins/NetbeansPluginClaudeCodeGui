package io.github.nbplugins.claudecodegui.mcp.tools;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.netbeans.spi.editor.hints.ErrorDescription;
import org.openide.filesystems.FileObject;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TC-11 regression: getDiagnostics returned empty results.
 *
 * Root cause (final): org.netbeans.editor.Annotations only stores
 * AnnotationDesc objects (bookmarks, breakpoints, etc.) — NOT the
 * error/warning hints produced by the Java compiler and other language
 * parsers. Those hints are stored in AnnotationHolder (editor.hints module),
 * accessible via AnnotationHolder.getInstance(FileObject).getErrors().
 *
 * Fix: use the overridable getErrorDescriptions(FileObject) which calls
 * AnnotationHolder via reflection (AnnotationHolder is in a private
 * implementation package, direct reference is not allowed by the NBM module
 * system).
 */
public class GetDiagnosticsTest {

    /** In-memory FileObject stub — avoids local filesystem dependency in tests. */
    private static final FileObject STUB_FO;
    static {
        try {
            STUB_FO = org.openide.filesystems.FileUtil.createMemoryFileSystem()
                    .getRoot().createData("Test", "java");
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Verifies that extractDiagnosticsFromFile calls the overridable
     * getErrorDescriptions(FileObject) hook — not the old Annotations API.
     */
    @Test
    public void testUsesGetErrorDescriptionsHook() throws Exception {
        AtomicBoolean hookCalled = new AtomicBoolean(false);
        AtomicReference<FileObject> receivedFo = new AtomicReference<>();

        GetDiagnostics tool = new GetDiagnostics() {
            @Override
            protected FileObject toFileObject(String filePath) {
                return STUB_FO;
            }

            @Override
            protected List<ErrorDescription> getErrorDescriptions(FileObject fo) {
                hookCalled.set(true);
                receivedFo.set(fo);
                return Collections.emptyList();
            }
        };

        tool.extractDiagnosticsFromFile("/fake/Test.java");

        assertTrue(hookCalled.get(),
                "Must call getErrorDescriptions(FileObject) — the AnnotationHolder-based hook. "
                + "TC-11 root cause: org.netbeans.editor.Annotations contains only "
                + "AnnotationDesc (bookmarks/breakpoints), not error/warning hints.");
        assertSame(STUB_FO, receivedFo.get(),
                "getErrorDescriptions must receive the FileObject from toFileObject().");
    }

    /**
     * Verifies that an empty errors list from the hook produces an empty result.
     */
    @Test
    public void testEmptyErrorsListProducesEmptyResult() throws Exception {
        GetDiagnostics tool = new GetDiagnostics() {
            @Override
            protected FileObject toFileObject(String filePath) { return STUB_FO; }

            @Override
            protected List<ErrorDescription> getErrorDescriptions(FileObject fo) {
                return Collections.emptyList();
            }
        };

        List<org.openbeans.claude.netbeans.tools.params.Diagnostic> result =
                tool.extractDiagnosticsFromFile("/fake/Test.java");

        assertNotNull(result);
        assertTrue(result.isEmpty(), "Empty errors list must produce empty diagnostics");
    }
}
