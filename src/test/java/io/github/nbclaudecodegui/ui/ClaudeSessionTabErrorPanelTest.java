package io.github.nbclaudecodegui.ui;

import io.github.nbclaudecodegui.model.SessionMode;
import java.awt.BorderLayout;
import java.awt.Component;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the error panel in {@link ClaudeSessionTab}.
 */
class ClaudeSessionTabErrorPanelTest {

    /** Reflectively invoke {@code showStartError} with all four parameters. */
    private static void showStartError(ClaudeSessionTab tab,
                                       String command, String error,
                                       File workingDir, Path claudeConfigDir)
            throws Exception {
        Method m = ClaudeSessionTab.class.getDeclaredMethod(
                "showStartError", String.class, String.class, File.class, Path.class);
        m.setAccessible(true);
        m.invoke(tab, command, error, workingDir, claudeConfigDir);
    }

    /** Reflectively read the {@code errorPanel} field. */
    private static JPanel getErrorPanel(ClaudeSessionTab tab) throws Exception {
        Field f = ClaudeSessionTab.class.getDeclaredField("errorPanel");
        f.setAccessible(true);
        return (JPanel) f.get(tab);
    }

    /** Collect all JTextFields recursively from a container. */
    private static java.util.List<JTextField> collectFields(Component c) {
        java.util.List<JTextField> result = new java.util.ArrayList<>();
        collectFields(c, result);
        return result;
    }

    private static void collectFields(Component c, java.util.List<JTextField> result) {
        if (c instanceof JTextField tf) {
            result.add(tf);
        } else if (c instanceof java.awt.Container container) {
            for (Component child : container.getComponents()) {
                collectFields(child, result);
            }
        }
    }

    /** Collect all JLabels recursively from a container. */
    private static java.util.List<JLabel> collectLabels(Component c) {
        java.util.List<JLabel> result = new java.util.ArrayList<>();
        collectLabels(c, result);
        return result;
    }

    private static void collectLabels(Component c, java.util.List<JLabel> result) {
        if (c instanceof JLabel lbl) {
            result.add(lbl);
        } else if (c instanceof java.awt.Container container) {
            for (Component child : container.getComponents()) {
                collectLabels(child, result);
            }
        }
    }

    @Test
    void showStartError_errorPanelIsCenterComponent() throws Exception {
        ClaudeSessionTab tab = new ClaudeSessionTab();
        File dir = new File("/tmp/test-dir");
        Path cfgDir = Path.of("/home/user/.config/claude/profiles/myprofile");

        showStartError(tab, "claude --arg", "Connection refused", dir, cfgDir);

        BorderLayout layout = (BorderLayout) tab.getLayout();
        Component center = layout.getLayoutComponent(BorderLayout.CENTER);
        JPanel errorPanel = getErrorPanel(tab);

        assertNotNull(errorPanel, "errorPanel field must be set");
        assertSame(errorPanel, center, "errorPanel must be the CENTER component");
    }

    @Test
    void showStartError_fieldOrderAndValues() throws Exception {
        ClaudeSessionTab tab = new ClaudeSessionTab();
        File dir = new File("/tmp/my-project");
        Path cfgDir = Path.of("/home/user/.config/claude/profiles/work");

        showStartError(tab, "claude --verbose", "No such file", dir, cfgDir);

        JPanel errorPanel = getErrorPanel(tab);
        assertNotNull(errorPanel);

        java.util.List<JLabel> labels = collectLabels(errorPanel);
        // Filter out the title label (it has no colon)
        java.util.List<String> fieldLabels = labels.stream()
                .map(JLabel::getText)
                .filter(t -> t.contains(":"))
                .toList();

        // 3 colon-containing labels: "Working Directory:", ":", "Command:", "Error:"
        // Note: CLAUDE_CONFIG_DIR row uses a JTextField for the env var name + a ":" JLabel
        assertEquals(4, fieldLabels.size(), "Must have 4 colon-containing labels");
        assertEquals("Working Directory:", fieldLabels.get(0));
        assertEquals(":",                  fieldLabels.get(1));
        assertEquals("Command:",           fieldLabels.get(2));
        assertEquals("Error:",             fieldLabels.get(3));

        // 5 JTextFields: dirField, cfgDirLabelField("CLAUDE_CONFIG_DIR"), cfgDirField, cmdField, errField
        java.util.List<JTextField> fields = collectFields(errorPanel);
        assertEquals(5, fields.size(), "Must have 5 text fields");
        assertEquals(dir.getAbsolutePath(),  fields.get(0).getText());
        assertEquals("CLAUDE_CONFIG_DIR",    fields.get(1).getText());
        assertEquals(cfgDir.toString(),      fields.get(2).getText());
        assertEquals("claude --verbose",     fields.get(3).getText());
        assertEquals("No such file",         fields.get(4).getText());
    }

    @Test
    void showStartError_defaultProfile_configDirIsBlank() throws Exception {
        ClaudeSessionTab tab = new ClaudeSessionTab();
        File dir = new File("/tmp/proj");

        // null claudeConfigDir → default profile
        showStartError(tab, "claude", "error", dir, null);

        JPanel errorPanel = getErrorPanel(tab);
        assertNotNull(errorPanel);

        java.util.List<JTextField> fields = collectFields(errorPanel);
        assertEquals(5, fields.size());
        assertEquals("", fields.get(2).getText(), "CLAUDE_CONFIG_DIR value field must be empty for default profile");
    }

    @Test
    void restartAdvanced_afterError_clearsErrorPanel() throws Exception {
        ClaudeSessionTab tab = new ClaudeSessionTab();
        File dir = new File("/tmp/proj");

        showStartError(tab, "claude", "fail", dir, null);
        assertNotNull(getErrorPanel(tab), "errorPanel must be set after showStartError");

        tab.onSaveAndSwitch("", SessionMode.RESTART_ADVANCED, null);

        assertNull(getErrorPanel(tab), "errorPanel must be null after RESTART_ADVANCED");

        BorderLayout layout = (BorderLayout) tab.getLayout();
        Component center = layout.getLayoutComponent(BorderLayout.CENTER);
        assertNotNull(center, "A CENTER component must be present after RESTART_ADVANCED");
        assertFalse(center instanceof JPanel ep && ep == getErrorPanel(tab),
                "CENTER must not be the error panel");
    }
}
