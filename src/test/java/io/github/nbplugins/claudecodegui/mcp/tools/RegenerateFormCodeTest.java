package io.github.nbplugins.claudecodegui.mcp.tools;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RegenerateFormCode}: resolution of the .java/.form pair,
 * the stale-document check, and the run() wiring. The form editor itself
 * needs a NetBeans runtime and is not exercised here.
 */
public class RegenerateFormCodeTest {

    @TempDir
    Path dir;

    private void createForm(String baseName) throws Exception {
        Files.writeString(dir.resolve(baseName + ".java"), "class " + baseName + " {}");
        Files.writeString(dir.resolve(baseName + ".form"), "<Form/>");
    }

    @Test
    public void resolvesPairFromFormFile() throws Exception {
        createForm("MyFrame");
        RegenerateFormCode.FormFiles files =
                RegenerateFormCode.resolveFormFiles(dir.resolve("MyFrame.form").toString());
        assertEquals(dir.resolve("MyFrame.java").toFile().getAbsoluteFile(), files.javaFile());
        assertEquals(dir.resolve("MyFrame.form").toFile().getAbsoluteFile(), files.formFile());
    }

    @Test
    public void resolvesPairFromJavaFile() throws Exception {
        createForm("MyFrame");
        RegenerateFormCode.FormFiles files =
                RegenerateFormCode.resolveFormFiles(dir.resolve("MyFrame.java").toString());
        assertEquals(dir.resolve("MyFrame.form").toFile().getAbsoluteFile(), files.formFile());
    }

    @Test
    public void rejectsJavaFileWithoutForm() throws Exception {
        Files.writeString(dir.resolve("Plain.java"), "class Plain {}");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> RegenerateFormCode.resolveFormFiles(dir.resolve("Plain.java").toString()));
        assertTrue(e.getMessage().contains("not a NetBeans GUI Builder form"), e.getMessage());
    }

    @Test
    public void rejectsFormWithoutJava() throws Exception {
        Files.writeString(dir.resolve("Orphan.form"), "<Form/>");
        assertThrows(IllegalArgumentException.class,
                () -> RegenerateFormCode.resolveFormFiles(dir.resolve("Orphan.form").toString()));
    }

    @Test
    public void rejectsOtherExtensions() {
        assertThrows(IllegalArgumentException.class,
                () -> RegenerateFormCode.resolveFormFiles(dir.resolve("notes.txt").toString()));
    }

    @Test
    public void documentWithoutGuardMarkersMatchesDisk() {
        String disk = "    // <editor-fold defaultstate=\"collapsed\" desc=\"Generated Code\">//GEN-BEGIN:initComponents\r\n"
                + "    private void initComponents() {\r\n"
                + "        jButton1 = new javax.swing.JButton();\r\n"
                + "    }// </editor-fold>//GEN-END:initComponents\r\n"
                + "    private void jButton1ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButton1ActionPerformed\r\n"
                + "    }//GEN-LAST:event_jButton1ActionPerformed\r\n";
        String document = "    // <editor-fold defaultstate=\"collapsed\" desc=\"Generated Code\">\n"
                + "    private void initComponents() {\n"
                + "        jButton1 = new javax.swing.JButton();\n"
                + "    }// </editor-fold>\n"
                + "    private void jButton1ActionPerformed(java.awt.event.ActionEvent evt) {\n"
                + "    }\n";
        assertTrue(RegenerateFormCode.documentMatchesDisk(document, disk));
    }

    @Test
    public void staleDocumentDoesNotMatchDisk() {
        String disk = "private void initComponents() {\n    jButton1 = new javax.swing.JButton();\n}\n";
        String stale = "private void initComponents() {\n}\n";
        assertFalse(RegenerateFormCode.documentMatchesDisk(stale, disk));
    }

    @Test
    public void findsExistingEventHandlersByTheirGuardedHeader() {
        String javaText = "    private void jButton1ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButton1ActionPerformed\n"
                + "    }//GEN-LAST:event_jButton1ActionPerformed\n"
                + "    private void my$Handler(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_my$Handler\n"
                + "    }//GEN-LAST:event_my$Handler\n"
                + "    private void notGuarded(java.awt.event.ActionEvent evt) {\n"
                + "    }\n";
        assertEquals(java.util.Set.of("jButton1ActionPerformed", "my$Handler"),
                RegenerateFormCode.existingEventHandlers(javaText));
    }

    @Test
    public void runChecksAccessToBothFilesBeforeRegenerating() throws Exception {
        createForm("MyFrame");
        List<File> checked = new ArrayList<>();
        AtomicReference<RegenerateFormCode.FormFiles> regenerated = new AtomicReference<>();
        RegenerateFormCode tool = new RegenerateFormCode() {
            @Override
            protected void checkAccess(File file) { checked.add(file); }

            @Override
            protected String regenerate(FormFiles files) {
                regenerated.set(files);
                return "ok";
            }
        };

        assertEquals("ok", tool.run(new RegenerateFormCode.Params(dir.resolve("MyFrame.form").toString())));
        assertEquals(2, checked.size());
        assertTrue(checked.contains(regenerated.get().javaFile()));
        assertTrue(checked.contains(regenerated.get().formFile()));
    }

    @Test
    public void runDoesNotRegenerateWhenAccessDenied() throws Exception {
        createForm("MyFrame");
        AtomicReference<Boolean> regenerated = new AtomicReference<>(false);
        RegenerateFormCode tool = new RegenerateFormCode() {
            @Override
            protected void checkAccess(File file) { throw new SecurityException("denied"); }

            @Override
            protected String regenerate(FormFiles files) {
                regenerated.set(true);
                return "ok";
            }
        };

        assertThrows(SecurityException.class,
                () -> tool.run(new RegenerateFormCode.Params(dir.resolve("MyFrame.form").toString())));
        assertFalse(regenerated.get());
    }

    @Test
    public void runRequiresFilePath() {
        assertThrows(IllegalArgumentException.class,
                () -> new RegenerateFormCode().run(new RegenerateFormCode.Params(" ")));
    }
}
