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
}
