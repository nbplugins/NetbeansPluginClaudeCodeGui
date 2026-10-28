package io.github.nbplugins.claudecodegui.mcp.tools;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import junit.framework.Test;
import org.netbeans.junit.NbModuleSuite;
import org.netbeans.junit.NbTestCase;

/**
 * Runs {@link RegenerateFormCode} against the real NetBeans form editor (form + form.nb
 * modules) inside an {@link NbModuleSuite} runtime.
 *
 * <p>Each test copies a {@code .form} / {@code .java} fixture pair from
 * {@code forms/} into the work directory, regenerates it, and compares the resulting
 * {@code .java} with {@code forms/<Name>.java.expected} (line endings ignored). When the
 * generated code differs, the actual file is left in the test's work directory - after
 * reviewing it, it can be copied over the expected file (e.g. after a NetBeans upgrade).
 *
 * <p>The fixture files are not in an open project, so the open-projects access check is
 * bypassed; it is covered by {@code RegenerateFormCodeTest}.
 */
public class RegenerateFormCodeIT extends NbTestCase {

    private static final String FIXTURES = "forms/";

    /**
     * @return the suite running this class inside a NetBeans module system with all
     *         modules from the test classpath enabled
     */
    public static Test suite() {
        return NbModuleSuite.createConfiguration(RegenerateFormCodeIT.class)
                .clusters(".*")
                .enableModules(".*")
                .gui(false)
                .suite();
    }

    /**
     * @param name test method name passed by the JUnit runner
     */
    public RegenerateFormCodeIT(String name) {
        super(name);
    }

    @Override
    protected void setUp() throws Exception {
        clearWorkDir();
    }

    /** A Free Design (GroupLayout) JFrame form is generated into empty guarded sections. */
    public void testPlainFormIsRegenerated() throws Exception {
        File java = copyFixture("PlainFrame");

        String result = regenerate(java);

        assertTrue(result, result.startsWith("Regenerated initComponents() and the variables declaration of PlainFrame.java"));
        String code = assertMatchesExpected("PlainFrame", java);
        String init = guardedSection(code, "initComponents");
        assertTrue(init, init.contains("jButton1.setText(\"OK\");"));
        assertTrue(init, init.contains("javax.swing.GroupLayout layout = new javax.swing.GroupLayout(getContentPane());"));
        assertTrue(init, init.contains("pack();"));
        assertDeclarations(code, "private javax.swing.JButton jButton1;",
                "private javax.swing.JLabel jLabel1;",
                "private javax.swing.JTextField jTextField1;");
        assertTrue("the .form must still describe the components",
                read(new File(getWorkDir(), "PlainFrame.form")).contains("name=\"jTextField1\""));
    }

    /** Regenerating a form whose code is already up to date changes nothing. */
    public void testUpToDateFormIsReportedUnchanged() throws Exception {
        File java = copyFixture("PlainFrame");
        regenerate(java);
        String regenerated = read(java);

        String result = regenerate(java);

        assertTrue(result, result.startsWith("The generated code in PlainFrame.java already matches PlainFrame.form"));
        assertEquals(regenerated, read(java));
    }

    /**
     * Hand edits inside the guarded sections are replaced by code generated from the
     * .form; code outside the guarded sections is kept.
     */
    public void testHandEditedGuardedSectionsAreReplaced() throws Exception {
        File java = copyFixture("ModifiedPanel");

        regenerate(java);

        String code = assertMatchesExpected("ModifiedPanel", java);
        assertFalse(code, code.contains("Hand edited"));
        assertFalse(code, code.contains("setEnabled(false)"));
        assertFalse("a component not in the .form must be removed", code.contains("removedLabel"));
        assertFalse("a changed variable type must be restored", code.contains("JToggleButton"));
        assertTrue(code, guardedSection(code, "initComponents").contains("jButton1.setText(\"OK\");"));
        assertDeclarations(code, "private javax.swing.JButton jButton1;", "private javax.swing.JLabel jLabel1;");
        assertTrue(code, code.contains("setToolTipText(\"Kept: code outside the guarded sections\");"));
        assertTrue(code, code.contains("return \"Kept: a method outside the guarded sections\";"));
    }

    /**
     * Event handlers of the .form get listener registrations in initComponents() and
     * guarded handler methods: missing ones are generated with the IDE's TODO body,
     * an existing one keeps its body.
     */
    public void testEventHandlerSectionsAreGenerated() throws Exception {
        File java = copyFixture("EventsPanel");

        String result = regenerate(java);

        assertTrue(result, result.contains(
                "Added the missing event handler methods jLabel1MouseClicked, jTextField1ActionPerformed with a TODO body"));
        String code = assertMatchesExpected("EventsPanel", java);
        assertEquals("the new handlers get the default body", 2, count(code, "// TODO add your handling code here:"));
        String init = guardedSection(code, "initComponents");
        assertTrue(init, init.contains("jLabel1.addMouseListener("));
        assertTrue(init, init.contains("jTextField1.addActionListener("));
        assertTrue(init, init.contains("jButton1.addActionListener("));
        assertTrue(init, init.contains("jButton2.addActionListener("));
        assertTrue(init, init.contains("jLabel1MouseClicked(evt);"));
        assertTrue(init, init.contains("jTextField1ActionPerformed(evt);"));
        assertEquals("jButton1 and jButton2 share one handler", 2, count(init, "jButton1ActionPerformed(evt);"));

        assertHandler(code, "jLabel1MouseClicked", "java.awt.event.MouseEvent");
        assertHandler(code, "jTextField1ActionPerformed", "java.awt.event.ActionEvent");
        assertHandler(code, "jButton1ActionPerformed", "java.awt.event.ActionEvent");
        assertTrue("the body of an existing handler must be kept",
                code.contains("System.out.println(\"Kept: user code of an existing handler\");"));
        assertEquals("a shared handler is generated once", 1, count(code, "private void jButton1ActionPerformed("));
    }

    /** Per-component variable modifiers and local variables. */
    public void testVariableModifiersAndLocalVariables() throws Exception {
        File java = copyFixture("VariablesPanel");

        regenerate(java);

        String code = assertMatchesExpected("VariablesPanel", java);
        assertDeclarations(code,
                "private javax.swing.JLabel defaultLabel;",
                "public javax.swing.JButton publicButton;",
                "protected javax.swing.JTextField protectedField;",
                "javax.swing.JCheckBox packageCheckBox;",
                "private static javax.swing.JLabel staticLabel;");
        String variables = guardedSection(code, "variables");
        assertFalse(variables, variables.contains("localLabel"));
        assertFalse(variables, variables.contains("finalLocalLabel"));
        String init = guardedSection(code, "initComponents");
        assertTrue(init, containsLine(init, "javax.swing.JLabel localLabel = new javax.swing.JLabel();"));
        assertTrue(init, containsLine(init, "final javax.swing.JLabel finalLocalLabel = new javax.swing.JLabel();"));
        assertTrue(init, containsLine(init, "publicButton = new javax.swing.JButton();"));
    }

    /** Form-wide default of local variables, overridden by one component that is a public field. */
    public void testFormWideLocalVariablesDefault() throws Exception {
        File java = copyFixture("LocalDefaultsPanel");

        regenerate(java);

        String code = assertMatchesExpected("LocalDefaultsPanel", java);
        String init = guardedSection(code, "initComponents");
        assertTrue(init, containsLine(init, "javax.swing.JLabel defaultLocalLabel = new javax.swing.JLabel();"));
        assertTrue(init, containsLine(init, "publicFieldButton = new javax.swing.JButton();"));
        assertDeclarations(code, "public javax.swing.JButton publicFieldButton;");
        assertFalse(code, guardedSection(code, "variables").contains("defaultLocalLabel"));
    }

    private File copyFixture(String name) throws IOException {
        File java = new File(getWorkDir(), name + ".java");
        Files.writeString(java.toPath(), resource(name + ".java.input"), StandardCharsets.UTF_8);
        Files.writeString(new File(getWorkDir(), name + ".form").toPath(), resource(name + ".form"), StandardCharsets.UTF_8);
        return java;
    }

    private static String regenerate(File java) throws Exception {
        RegenerateFormCode tool = new RegenerateFormCode() {
            @Override
            protected void checkAccess(File file) {
                // fixtures are not in an open project
            }
        };
        return tool.run(new RegenerateFormCode.Params(java.getPath()));
    }

    /** Compares the generated file with {@code <name>.java.expected}, ignoring line endings. */
    private static String assertMatchesExpected(String name, File java) throws IOException {
        String actual = read(java);
        assertEquals("Generated code of " + name + " differs from " + FIXTURES + name
                + ".java.expected; actual file: " + java, resource(name + ".java.expected"), actual);
        return actual;
    }

    private static void assertDeclarations(String code, String... declarations) {
        String variables = guardedSection(code, "variables");
        for (String declaration : declarations) {
            assertTrue("missing '" + declaration + "' in:\n" + variables, containsLine(variables, declaration));
        }
    }

    private static void assertHandler(String code, String handler, String eventClass) {
        String header = "private void " + handler + "(" + eventClass + " evt) {//GEN-FIRST:event_" + handler;
        assertTrue("missing guarded header of " + handler + " in:\n" + code, code.contains(header));
        assertTrue("missing guarded footer of " + handler + " in:\n" + code,
                code.contains("}//GEN-LAST:event_" + handler));
    }

    /** Returns the text between {@code //GEN-BEGIN:<id>} and {@code //GEN-END:<id>}. */
    private static String guardedSection(String code, String id) {
        int begin = code.indexOf("//GEN-BEGIN:" + id);
        int end = code.indexOf("//GEN-END:" + id);
        assertTrue("guarded section " + id + " not found in:\n" + code, begin >= 0 && end > begin);
        return code.substring(begin, end);
    }

    private static boolean containsLine(String text, String line) {
        return text.lines().anyMatch(l -> l.strip().equals(line));
    }

    private static int count(String text, String part) {
        int count = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + part.length())) {
            count++;
        }
        return count;
    }

    private static String read(File file) throws IOException {
        return normalize(Files.readString(file.toPath(), StandardCharsets.UTF_8));
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = RegenerateFormCodeIT.class.getResourceAsStream(FIXTURES + name)) {
            assertNotNull("missing test resource " + FIXTURES + name, in);
            return normalize(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n");
    }
}
