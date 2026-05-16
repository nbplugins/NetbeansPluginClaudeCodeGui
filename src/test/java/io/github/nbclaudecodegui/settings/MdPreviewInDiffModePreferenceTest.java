package io.github.nbclaudecodegui.settings;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link MdPreviewInDiffMode} preference and {@code autoPlanPreview} preference
 * in {@link ClaudeCodePreferences}.
 */
class MdPreviewInDiffModePreferenceTest {

    @AfterEach
    void tearDown() {
        ClaudeCodePreferences.setMdPreviewInDiffMode(ClaudeCodePreferences.DEFAULT_MD_PREVIEW_IN_DIFF_MODE);
        ClaudeCodePreferences.setAutoPlanPreview(ClaudeCodePreferences.DEFAULT_AUTO_PLAN_PREVIEW);
    }

    @Test
    void mdPreviewInDiffMode_defaultIsAlways() {
        assertEquals(MdPreviewInDiffMode.ALWAYS, ClaudeCodePreferences.DEFAULT_MD_PREVIEW_IN_DIFF_MODE);
        assertEquals(MdPreviewInDiffMode.ALWAYS, ClaudeCodePreferences.getMdPreviewInDiffMode());
    }

    @Test
    void mdPreviewInDiffMode_roundTrip_never() {
        ClaudeCodePreferences.setMdPreviewInDiffMode(MdPreviewInDiffMode.NEVER);
        assertEquals(MdPreviewInDiffMode.NEVER, ClaudeCodePreferences.getMdPreviewInDiffMode());
    }

    @Test
    void mdPreviewInDiffMode_roundTrip_exceptPlan() {
        ClaudeCodePreferences.setMdPreviewInDiffMode(MdPreviewInDiffMode.EXCEPT_PLAN);
        assertEquals(MdPreviewInDiffMode.EXCEPT_PLAN, ClaudeCodePreferences.getMdPreviewInDiffMode());
    }

    @Test
    void mdPreviewInDiffMode_unknownValueFallsBackToDefault() {
        // Write an invalid raw value directly via NbPreferences, then verify fallback
        org.openide.util.NbPreferences.forModule(ClaudeCodePreferences.class)
                .put(ClaudeCodePreferences.KEY_MD_PREVIEW_IN_DIFF_MODE, "INVALID_VALUE");
        assertEquals(ClaudeCodePreferences.DEFAULT_MD_PREVIEW_IN_DIFF_MODE,
                ClaudeCodePreferences.getMdPreviewInDiffMode());
    }

    @Test
    void isMdPreviewInDiff_deprecatedWrapper_returnsTrueForAlways() {
        ClaudeCodePreferences.setMdPreviewInDiffMode(MdPreviewInDiffMode.ALWAYS);
        assertTrue(ClaudeCodePreferences.isMdPreviewInDiff());
    }

    @Test
    void isMdPreviewInDiff_deprecatedWrapper_returnsFalseForNever() {
        ClaudeCodePreferences.setMdPreviewInDiffMode(MdPreviewInDiffMode.NEVER);
        assertFalse(ClaudeCodePreferences.isMdPreviewInDiff());
    }

    @Test
    void isMdPreviewInDiff_deprecatedWrapper_returnsTrueForExceptPlan() {
        ClaudeCodePreferences.setMdPreviewInDiffMode(MdPreviewInDiffMode.EXCEPT_PLAN);
        assertTrue(ClaudeCodePreferences.isMdPreviewInDiff());
    }

    @Test
    void autoPlanPreview_defaultIsTrue() {
        assertTrue(ClaudeCodePreferences.DEFAULT_AUTO_PLAN_PREVIEW);
        assertTrue(ClaudeCodePreferences.isAutoPlanPreview());
    }

    @Test
    void autoPlanPreview_roundTrip_false() {
        ClaudeCodePreferences.setAutoPlanPreview(false);
        assertFalse(ClaudeCodePreferences.isAutoPlanPreview());
    }

    @Test
    void autoPlanPreview_roundTrip_true() {
        ClaudeCodePreferences.setAutoPlanPreview(false);
        ClaudeCodePreferences.setAutoPlanPreview(true);
        assertTrue(ClaudeCodePreferences.isAutoPlanPreview());
    }
}
