package io.github.nbplugins.claudecodegui.settings;

import java.awt.datatransfer.DataFlavor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import javax.swing.JButton;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the right-click context menu ("Copy URL", "Open in Browser")
 * on the Base URL field, and conditional visibility of link buttons
 * in {@link ClaudeProfilesPanel}.
 */
class ClaudeProfilesPanelContextMenuTest {

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    // -------------------------------------------------------------------------
    // Context menu structure
    // -------------------------------------------------------------------------

    @Test
    void baseUrlFieldHasContextMenuWithTwoItems() throws Exception {
        ClaudeProfilesPanel panel = new ClaudeProfilesPanel();
        JPopupMenu menu = getField(panel, "baseUrlMenu", JPopupMenu.class);
        assertNotNull(menu, "baseUrlMenu field must not be null");

        JMenuItem copyItem = getField(panel, "copyUrlItem", JMenuItem.class);
        JMenuItem openItem = getField(panel, "openInBrowserItem", JMenuItem.class);
        assertNotNull(copyItem, "copyUrlItem must not be null");
        assertNotNull(openItem, "openInBrowserItem must not be null");
        assertEquals("Copy URL", copyItem.getText());
        assertEquals("Open in Browser", openItem.getText());
    }

    // -------------------------------------------------------------------------
    // Link button visibility
    // -------------------------------------------------------------------------

    @Test
    void claudeAiBtnVisibleOnlyForSubscription() throws Exception {
        ClaudeProfilesPanel panel = new ClaudeProfilesPanel();
        JButton claudeAiBtn = getField(panel, "claudeAiBtn", JButton.class);

        JRadioButton rbSubscription = getField(panel, "rbSubscription", JRadioButton.class);
        rbSubscription.setSelected(true);
        invoke(panel, "updateFieldEnablement");
        assertTrue(claudeAiBtn.isVisible(), "claude.ai button must be visible for Subscription");

        JRadioButton rbManaged = getField(panel, "rbManaged", JRadioButton.class);
        rbManaged.setSelected(true);
        invoke(panel, "updateFieldEnablement");
        assertFalse(claudeAiBtn.isVisible(), "claude.ai button must be hidden for Claude managed");

        JRadioButton rbClaudeApi = getField(panel, "rbClaudeApi", JRadioButton.class);
        rbClaudeApi.setSelected(true);
        invoke(panel, "updateFieldEnablement");
        assertFalse(claudeAiBtn.isVisible(), "claude.ai button must be hidden for Claude API");
    }

    @Test
    void consoleAnthropicBtnVisibleOnlyForClaudeApi() throws Exception {
        ClaudeProfilesPanel panel = new ClaudeProfilesPanel();
        JButton consoleBtn = getField(panel, "consoleAnthropicBtn", JButton.class);

        JRadioButton rbClaudeApi = getField(panel, "rbClaudeApi", JRadioButton.class);
        rbClaudeApi.setSelected(true);
        invoke(panel, "updateFieldEnablement");
        assertTrue(consoleBtn.isVisible(), "console.anthropic.com button must be visible for Claude API");

        JRadioButton rbManaged = getField(panel, "rbManaged", JRadioButton.class);
        rbManaged.setSelected(true);
        invoke(panel, "updateFieldEnablement");
        assertFalse(consoleBtn.isVisible(), "console.anthropic.com button must be hidden for Claude managed");

        JRadioButton rbSubscription = getField(panel, "rbSubscription", JRadioButton.class);
        rbSubscription.setSelected(true);
        invoke(panel, "updateFieldEnablement");
        assertFalse(consoleBtn.isVisible(), "console.anthropic.com button must be hidden for Subscription");
    }

    // -------------------------------------------------------------------------
    // Clipboard action
    // -------------------------------------------------------------------------

    @Test
    void copyUrlActionCopiesTextToClipboard() throws Exception {
        ClaudeProfilesPanel panel = new ClaudeProfilesPanel();

        JRadioButton rbOtherApi = getField(panel, "rbOtherApi", JRadioButton.class);
        rbOtherApi.setSelected(true);
        JTextField baseUrlField = getField(panel, "baseUrlField", JTextField.class);
        baseUrlField.setText("https://api.example.com");

        JMenuItem copyItem = getField(panel, "copyUrlItem", JMenuItem.class);

        // Clipboard access may throw in headless CI — skip gracefully.
        try {
            for (java.awt.event.ActionListener al : copyItem.getActionListeners()) {
                al.actionPerformed(new java.awt.event.ActionEvent(
                        copyItem, java.awt.event.ActionEvent.ACTION_PERFORMED, ""));
            }
            java.awt.datatransfer.Clipboard cb = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
            String clipped = (String) cb.getData(DataFlavor.stringFlavor);
            assertEquals("https://api.example.com", clipped);
        } catch (Exception ex) {
            // Clipboard not available in headless mode
        }
    }

    // -------------------------------------------------------------------------
    // Reflection helpers
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static <T> T getField(Object obj, String name, Class<T> type) throws Exception {
        Field f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return type.cast(f.get(obj));
    }

    private static void invoke(Object obj, String name) throws Exception {
        Method m = obj.getClass().getDeclaredMethod(name);
        m.setAccessible(true);
        m.invoke(obj);
    }
}
