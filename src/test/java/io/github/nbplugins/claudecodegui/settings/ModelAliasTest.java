package io.github.nbplugins.claudecodegui.settings;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ModelAlias#validateAliasUniqueness}.
 */
class ModelAliasTest {

    @Test
    void validateAliasUniqueness_noAliases_returnsNull() {
        List<ModelAlias> models = List.of(
                new ModelAlias("model-a", null, ""),
                new ModelAlias("model-b", null, ""));
        assertNull(ModelAlias.validateAliasUniqueness(models));
    }

    @Test
    void validateAliasUniqueness_uniqueStandardAliases_returnsNull() {
        List<ModelAlias> models = List.of(
                new ModelAlias("claude-sonnet-4-5", null, "sonnet"),
                new ModelAlias("claude-opus-4",     null, "opus"),
                new ModelAlias("claude-haiku-4-5",  null, "haiku"));
        assertNull(ModelAlias.validateAliasUniqueness(models));
    }

    @Test
    void validateAliasUniqueness_duplicateSonnet_returnsError() {
        List<ModelAlias> models = List.of(
                new ModelAlias("claude-sonnet-4-5", null, "sonnet"),
                new ModelAlias("claude-sonnet-4-6", null, "sonnet"));
        assertNotNull(ModelAlias.validateAliasUniqueness(models));
    }

    @Test
    void validateAliasUniqueness_multipleCustom_returnsNull() {
        // "custom" alias may appear on multiple models — not a uniqueness violation
        List<ModelAlias> models = List.of(
                new ModelAlias("openai/gpt-4o",      null, "custom"),
                new ModelAlias("openai/gpt-4-turbo", null, "custom"));
        assertNull(ModelAlias.validateAliasUniqueness(models));
    }

    @Test
    void validateAliasUniqueness_customMixedWithStandard_returnsNull() {
        List<ModelAlias> models = List.of(
                new ModelAlias("claude-sonnet-4-5",  null, "sonnet"),
                new ModelAlias("openai/gpt-4o",      null, "custom"),
                new ModelAlias("openai/gpt-4-turbo", null, "custom"));
        assertNull(ModelAlias.validateAliasUniqueness(models));
    }

    // -------------------------------------------------------------------------
    // parseGptVersion / defaultExplicitPromptCaching
    // -------------------------------------------------------------------------

    @Test
    void parseGptVersion_gpt56Terra_returns56() {
        assertEquals(5.6, ModelAlias.parseGptVersion("gpt-5.6-terra"));
    }

    @Test
    void parseGptVersion_gpt56Sol_returns56() {
        assertEquals(5.6, ModelAlias.parseGptVersion("gpt-5.6-sol"));
    }

    @Test
    void parseGptVersion_futureGpt6_returns6() {
        assertEquals(6.0, ModelAlias.parseGptVersion("gpt-6-sol"));
    }

    @Test
    void parseGptVersion_olderGpt4o_returns4() {
        assertEquals(4.0, ModelAlias.parseGptVersion("gpt-4o"));
    }

    @Test
    void parseGptVersion_nonGptModel_returnsNull() {
        assertNull(ModelAlias.parseGptVersion("grok-4.5"));
        assertNull(ModelAlias.parseGptVersion("gemini-2.5-pro"));
        assertNull(ModelAlias.parseGptVersion("claude-sonnet-4-5"));
    }

    @Test
    void parseGptVersion_nullId_returnsNull() {
        assertNull(ModelAlias.parseGptVersion(null));
    }

    @Test
    void defaultExplicitPromptCaching_gpt56AndNewer_true() {
        assertTrue(ModelAlias.defaultExplicitPromptCaching("gpt-5.6-terra"));
        assertTrue(ModelAlias.defaultExplicitPromptCaching("gpt-5.7-sol"));
        assertTrue(ModelAlias.defaultExplicitPromptCaching("gpt-6-luna"));
    }

    @Test
    void defaultExplicitPromptCaching_olderGptModels_false() {
        assertFalse(ModelAlias.defaultExplicitPromptCaching("gpt-5.5-sol"));
        assertFalse(ModelAlias.defaultExplicitPromptCaching("gpt-4o"));
    }

    @Test
    void defaultExplicitPromptCaching_nonGptOrUnparseable_false() {
        assertFalse(ModelAlias.defaultExplicitPromptCaching("grok-4.5"));
        assertFalse(ModelAlias.defaultExplicitPromptCaching("gemini-2.5-pro"));
        assertFalse(ModelAlias.defaultExplicitPromptCaching(null));
    }

    @Test
    void threeArgConstructor_autoDefaultsExplicitPromptCachingFromId() {
        ModelAlias gpt56 = new ModelAlias("gpt-5.6-terra", null, "");
        ModelAlias grok = new ModelAlias("grok-4.5", null, "custom");

        assertTrue(gpt56.explicitPromptCaching());
        assertFalse(grok.explicitPromptCaching());
    }

    @Test
    void withExplicitPromptCaching_overridesFlagWithoutChangingOtherFields() {
        ModelAlias m = new ModelAlias("gpt-4o", null, "custom");

        ModelAlias enabled = m.withExplicitPromptCaching(true);

        assertFalse(m.explicitPromptCaching());
        assertTrue(enabled.explicitPromptCaching());
        assertEquals(m.id(), enabled.id());
        assertEquals(m.alias(), enabled.alias());
    }
}
