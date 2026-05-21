package io.github.nbplugins.claudecodegui.settings;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ModelAliasesDialog} helpers.
 */
class ModelAliasesDialogTest {

    // -------------------------------------------------------------------------
    // parseModelIds
    // -------------------------------------------------------------------------

    @Test
    void parseModelIds_emptyString_returnsEmpty() {
        assertTrue(ModelAliasesDialog.parseModelIds("").isEmpty());
    }

    @Test
    void parseModelIds_nullInput_returnsEmpty() {
        assertTrue(ModelAliasesDialog.parseModelIds(null).isEmpty());
    }

    @Test
    void parseModelIds_validResponse_returnsIds() {
        String json = "{\"data\":[{\"id\":\"claude-sonnet-4-5\"},{\"id\":\"claude-opus-4\"}]}";
        List<String> ids = ModelAliasesDialog.parseModelIds(json);
        assertEquals(List.of("claude-sonnet-4-5", "claude-opus-4"), ids);
    }

    @Test
    void parseModelIds_blankId_skipped() {
        String json = "{\"data\":[{\"id\":\"\"},{\"id\":\"claude-haiku-4-5\"}]}";
        List<String> ids = ModelAliasesDialog.parseModelIds(json);
        assertEquals(List.of("claude-haiku-4-5"), ids);
    }

    @Test
    void parseModelIds_noDataKey_stillFindsIds() {
        String json = "{\"id\":\"model-x\"}";
        List<String> ids = ModelAliasesDialog.parseModelIds(json);
        assertEquals(List.of("model-x"), ids);
    }

    // -------------------------------------------------------------------------
    // validateModelId
    // -------------------------------------------------------------------------

    @Test
    void validateModelId_blank_returnsError() {
        assertNotNull(ModelAliasesDialog.validateModelId("", List.of(), -1));
        assertNotNull(ModelAliasesDialog.validateModelId("   ", List.of(), -1));
    }

    @Test
    void validateModelId_uniqueId_returnsNull() {
        assertNull(ModelAliasesDialog.validateModelId("new-model", List.of("existing"), -1));
    }

    @Test
    void validateModelId_duplicateId_returnsError() {
        assertNotNull(ModelAliasesDialog.validateModelId("existing", List.of("existing"), -1));
    }

    @Test
    void validateModelId_skipRowAllowsSameId() {
        // Rename: skipping row 0 means the same name at row 0 is not a conflict
        assertNull(ModelAliasesDialog.validateModelId("existing", List.of("existing"), 0));
    }

    @Test
    void validateModelId_skipRowStillCatchesDuplicateInOtherRow() {
        // Renaming row 0 to "b" (which is at row 1) → conflict
        assertNotNull(ModelAliasesDialog.validateModelId("b", List.of("a", "b"), 0));
    }
}
