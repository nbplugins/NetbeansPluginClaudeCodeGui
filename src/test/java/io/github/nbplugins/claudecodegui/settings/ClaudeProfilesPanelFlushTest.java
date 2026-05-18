package io.github.nbplugins.claudecodegui.settings;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComboBox;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that {@code ClaudeProfilesPanel.flushFormToCurrentProfile()} writes to
 * the profile that was loaded into the form, not to the profile currently
 * selected in the combo box.
 *
 * <p>Regression for the bug where switching the combo to Default caused the
 * previous profile's form data to be written into Default instead of the
 * original profile.
 */
class ClaudeProfilesPanelFlushTest {

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @Test
    void flushWritesToFormProfile_notComboSelection() throws Exception {
        ClaudeProfile defaultProfile = ClaudeProfile.createDefault();
        ClaudeProfile work = ClaudeProfile.createNamed("Work");

        ClaudeProfilesPanel panel = new ClaudeProfilesPanel();

        // Inject profiles list so the combo has two entries
        setField(panel, "profiles", new ArrayList<>(List.of(defaultProfile, work)));
        rebuildCombo(panel);

        // Load Work into form — currentFormProfile should now be Work
        invoke(panel, "loadProfileIntoForm", ClaudeProfile.class, work);

        // Simulate user typing an API key for Work
        JPasswordField apiKeyField = getField(panel, "apiKeyField", JPasswordField.class);
        apiKeyField.setText("sk-work-key");
        JRadioButton rbClaudeApi = getField(panel, "rbClaudeApi", JRadioButton.class);
        rbClaudeApi.setSelected(true);

        // Move combo to Default (index 0) WITHOUT calling loadProfileIntoForm for Default
        // This is the state when onProfileSelected() fires after the combo changes
        JComboBox<?> combo = getField(panel, "profileCombo", JComboBox.class);
        // suppress the actionListener so we control exactly what happens
        setField(panel, "suppressProfileChange", true);
        combo.setSelectedIndex(0);
        setField(panel, "suppressProfileChange", false);

        // Now call flush — should go to Work (currentFormProfile), NOT to Default
        invoke(panel, "flushFormToCurrentProfile");

        assertEquals("sk-work-key", work.getApiKey(),
                "Work profile must retain apiKey after flush");
        assertEquals("", defaultProfile.getApiKey(),
                "Default profile must not receive Work's apiKey");
    }

    // -------------------------------------------------------------------------
    // Reflection helpers
    // -------------------------------------------------------------------------

    private static void rebuildCombo(ClaudeProfilesPanel panel) throws Exception {
        Method m = ClaudeProfilesPanel.class.getDeclaredMethod("rebuildProfileCombo");
        m.setAccessible(true);
        // suppress events during rebuild
        setField(panel, "suppressProfileChange", true);
        m.invoke(panel);
        setField(panel, "suppressProfileChange", false);
    }

    @SuppressWarnings("unchecked")
    private static <T> T getField(Object obj, String name, Class<T> type) throws Exception {
        Field f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return type.cast(f.get(obj));
    }

    private static void setField(Object obj, String name, Object value) throws Exception {
        Field f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(obj, value);
    }

    private static void invoke(Object obj, String name) throws Exception {
        Method m = obj.getClass().getDeclaredMethod(name);
        m.setAccessible(true);
        m.invoke(obj);
    }

    private static void invoke(Object obj, String name, Class<?> paramType, Object arg)
            throws Exception {
        Method m = obj.getClass().getDeclaredMethod(name, paramType);
        m.setAccessible(true);
        m.invoke(obj, arg);
    }
}
