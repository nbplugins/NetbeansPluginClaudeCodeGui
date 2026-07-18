package io.github.nbplugins.claudecodegui.settings;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.JComboBox;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
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

    /**
     * Regression for stale model aliases after profile type switch:
     * when a profile had model aliases set under "Claude Compatible API" and then
     * the user switches to "Managed by Claude", flushFormToCurrentProfile must
     * clear the stored modelAliases and customModels so they don't appear in the
     * model combo box in the next session.
     */
    @Test
    void flush_clearsModelAliasesWhenSwitchingToManaged() throws Exception {
        ClaudeProfile work = ClaudeProfile.createNamed("Work");
        work.setApiKey("sk-old");
        work.setBaseUrl("https://old.example.com");
        work.setModelAliases(Map.of("sonnet", "old-model"));
        work.setCustomModels(List.of("old-custom-1", "old-custom-2"));

        ClaudeProfilesPanel panel = new ClaudeProfilesPanel();
        setField(panel, "profiles", new ArrayList<>(List.of(ClaudeProfile.createDefault(), work)));
        rebuildCombo(panel);
        invoke(panel, "loadProfileIntoForm", ClaudeProfile.class, work);

        // Switch to "Managed by Claude" — clear API key and base URL
        JPasswordField apiKeyField = getField(panel, "apiKeyField", JPasswordField.class);
        apiKeyField.setText("");
        JTextField baseUrlField = getField(panel, "baseUrlField", JTextField.class);
        baseUrlField.setText("");
        JRadioButton rbManaged = getField(panel, "rbManaged", JRadioButton.class);
        rbManaged.setSelected(true);
        // deselect others
        for (String rb : new String[]{"rbSubscription", "rbClaudeApi", "rbOtherApi", "rbOpenAIProxy"}) {
            getField(panel, rb, JRadioButton.class).setSelected(false);
        }

        invoke(panel, "flushFormToCurrentProfile");

        assertTrue(work.getModelAliases().isEmpty(),
                "modelAliases must be cleared when switching to Managed by Claude");
        assertTrue(work.getCustomModels().isEmpty(),
                "customModels must be cleared when switching to Managed by Claude");
    }

    @Test
    void flush_keepsModelAliasesForOtherApiConnection() throws Exception {
        ClaudeProfile work = ClaudeProfile.createNamed("Work");
        work.setApiKey("sk-test");
        work.setBaseUrl("https://example.com");
        work.setModelAliases(Map.of("sonnet", "my-sonnet"));
        work.setCustomModels(List.of("custom-1"));

        ClaudeProfilesPanel panel = new ClaudeProfilesPanel();
        setField(panel, "profiles", new ArrayList<>(List.of(ClaudeProfile.createDefault(), work)));
        rebuildCombo(panel);
        invoke(panel, "loadProfileIntoForm", ClaudeProfile.class, work);

        // Keep "Other API" selected
        JPasswordField apiKeyField = getField(panel, "apiKeyField", JPasswordField.class);
        apiKeyField.setText("sk-test");
        JTextField baseUrlField = getField(panel, "baseUrlField", JTextField.class);
        baseUrlField.setText("https://example.com");
        JRadioButton rbOtherApi = getField(panel, "rbOtherApi", JRadioButton.class);
        rbOtherApi.setSelected(true);
        for (String rb : new String[]{"rbManaged", "rbSubscription", "rbClaudeApi", "rbOpenAIProxy"}) {
            getField(panel, rb, JRadioButton.class).setSelected(false);
        }

        invoke(panel, "flushFormToCurrentProfile");

        // Model aliases must NOT be cleared when staying on Other API
        assertFalse(work.getModelAliases().isEmpty(),
                "modelAliases must be kept when connection type supports them");
        assertFalse(work.getCustomModels().isEmpty(),
                "customModels must be kept when connection type supports them");
    }

    @Test
    void flush_chatgptSubscription_setsFlagAndClearsOtherAuthFields() throws Exception {
        ClaudeProfile work = ClaudeProfile.createNamed("Work");
        work.setApiKey("sk-old");
        work.setBaseUrl("https://old.example.com");

        ClaudeProfilesPanel panel = new ClaudeProfilesPanel();
        setField(panel, "profiles", new ArrayList<>(List.of(ClaudeProfile.createDefault(), work)));
        rebuildCombo(panel);
        invoke(panel, "loadProfileIntoForm", ClaudeProfile.class, work);

        JRadioButton rbChatgptSubscription = getField(panel, "rbChatgptSubscription", JRadioButton.class);
        rbChatgptSubscription.setSelected(true);

        invoke(panel, "flushFormToCurrentProfile");

        assertTrue(work.isOpenaiSubscription());
        assertFalse(work.isOpenaiProxy());
        assertEquals("", work.getApiKey());
        assertEquals("", work.getBaseUrl());
        assertEquals("", work.getToken());
    }

    /**
     * Regression: {@code flushFormToCurrentProfile()} used to force-set
     * {@code customModels} to a hardcoded Codex model list on switching to
     * ChatGPT Subscription. Model discovery is now live (via "Model
     * Aliases…" → Fetch), so the flush must leave {@code customModels}
     * untouched.
     */
    @Test
    void flush_chatgptSubscription_doesNotAutoPopulateCustomModels() throws Exception {
        ClaudeProfile work = ClaudeProfile.createNamed("Work");

        ClaudeProfilesPanel panel = new ClaudeProfilesPanel();
        setField(panel, "profiles", new ArrayList<>(List.of(ClaudeProfile.createDefault(), work)));
        rebuildCombo(panel);
        invoke(panel, "loadProfileIntoForm", ClaudeProfile.class, work);

        JRadioButton rbChatgptSubscription = getField(panel, "rbChatgptSubscription", JRadioButton.class);
        rbChatgptSubscription.setSelected(true);

        invoke(panel, "flushFormToCurrentProfile");

        assertTrue(work.getCustomModels().isEmpty(),
                "customModels must not be auto-populated with a hardcoded list");
    }

    @Test
    void flush_switchingAwayFromChatgptSubscription_clearsFlag() throws Exception {
        ClaudeProfile work = ClaudeProfile.createNamed("Work");
        work.setOpenaiSubscription(true);
        work.setChatgptAccessToken("access");
        work.setChatgptRefreshToken("refresh");

        ClaudeProfilesPanel panel = new ClaudeProfilesPanel();
        setField(panel, "profiles", new ArrayList<>(List.of(ClaudeProfile.createDefault(), work)));
        rebuildCombo(panel);
        invoke(panel, "loadProfileIntoForm", ClaudeProfile.class, work);

        JRadioButton rbManaged = getField(panel, "rbManaged", JRadioButton.class);
        rbManaged.setSelected(true);

        invoke(panel, "flushFormToCurrentProfile");

        assertFalse(work.isOpenaiSubscription());
        // Switching connection type does not sign the user out — chatgpt* tokens
        // are only cleared explicitly via the "Sign out" button.
        assertTrue(work.isSignedIntoChatgpt());
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
