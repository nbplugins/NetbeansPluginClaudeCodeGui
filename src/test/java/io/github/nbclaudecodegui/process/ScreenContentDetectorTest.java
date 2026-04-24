package io.github.nbclaudecodegui.process;

import io.github.nbclaudecodegui.model.ChoiceMenuModel;
import io.github.nbclaudecodegui.model.EditMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parameterized unit tests for {@link ScreenContentDetector#detectChoiceMenu}.
 *
 * <p>Each test case is defined by a pair of resource files under
 * {@code io/github/nbclaudecodegui/process/screen-content-detector/detect-choice-menu/}:
 * <ul>
 *   <li>{@code <case>.src.txt} — the rendered terminal screen</li>
 *   <li>{@code <case>.expectedRes.json} — expected {@link ChoiceMenuModel} (absent = expect empty)</li>
 * </ul>
 */
class ScreenContentDetectorTest {

    private static final String RESOURCE_DIR =
            "io/github/nbclaudecodegui/process/screen-content-detector/detect-choice-menu";

    private ScreenContentDetector detector;

    @BeforeEach
    void setUp() {
        detector = new ScreenContentDetector();
    }

    static Stream<Object[]> testCases() throws Exception {
        URL dirUrl = ScreenContentDetectorTest.class.getClassLoader().getResource(RESOURCE_DIR);
        assertNotNull(dirUrl, "Resource directory not found: " + RESOURCE_DIR);
        File dir = new File(dirUrl.toURI());
        List<Object[]> cases = new ArrayList<>();
        File[] srcFiles = dir.listFiles((d, name) -> name.endsWith(".src.txt"));
        assertNotNull(srcFiles);
        for (File src : srcFiles) {
            String caseName = src.getName().replace(".src.txt", "");
            Path expectedPath = src.toPath().resolveSibling(caseName + ".expectedRes.json");
            cases.add(new Object[]{
                    caseName,
                    src.toPath(),
                    Files.exists(expectedPath) ? expectedPath : null
            });
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("testCases")
    void detectChoiceMenu(String caseName, Path srcFile, Path expectedFile) throws Exception {
        List<String> lines = Files.readAllLines(srcFile);
        Optional<ChoiceMenuModel> result = detector.detectChoiceMenu(lines);
        if (expectedFile == null) {
            assertTrue(result.isEmpty(),
                    "Expected no menu for case '" + caseName + "' but got: " + result);
        } else {
            ChoiceMenuModel expected = ChoiceMenuModel.fromJson(Files.readString(expectedFile));
            assertTrue(result.isPresent(),
                    "Expected a menu for case '" + caseName + "' but got empty");
            assertEquals(expected.text(), result.get().text(),
                    "text mismatch for case '" + caseName + "'");
            assertEquals(expected.options(), result.get().options(),
                    "options mismatch for case '" + caseName + "'");
        }
    }

    // -------------------------------------------------------------------------
    // detectSessionState
    // -------------------------------------------------------------------------

    @org.junit.jupiter.api.Test
    void detectSessionStateUnknownWhenNullOrEmpty() {
        assertEquals(ScreenContentDetector.DetectedSessionState.UNKNOWN, detector.detectSessionState(null));
        assertEquals(ScreenContentDetector.DetectedSessionState.UNKNOWN, detector.detectSessionState(List.of()));
    }

    // -------------------------------------------------------------------------
    // detectSessionState — fixture-based parameterized tests
    // -------------------------------------------------------------------------

    private static final String SESSION_STATE_RESOURCE_DIR =
            "io/github/nbclaudecodegui/process/screen-content-detector/detect-session-state";

    static Stream<Object[]> sessionStateTestCases() throws Exception {
        URL dirUrl = ScreenContentDetectorTest.class.getClassLoader().getResource(SESSION_STATE_RESOURCE_DIR);
        assertNotNull(dirUrl, "Resource directory not found: " + SESSION_STATE_RESOURCE_DIR);
        File dir = new File(dirUrl.toURI());
        List<Object[]> cases = new ArrayList<>();
        File[] srcFiles = dir.listFiles((d, name) -> name.endsWith(".src.txt"));
        assertNotNull(srcFiles);
        for (File src : srcFiles) {
            String caseName = src.getName().replace(".src.txt", "");
            Path expectedPath = src.toPath().resolveSibling(caseName + ".expectedState.txt");
            cases.add(new Object[]{caseName, src.toPath(), expectedPath});
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sessionStateTestCases")
    void detectSessionStateFromFixtures(String caseName, Path srcFile, Path expectedFile) throws Exception {
        List<String> lines = Files.readAllLines(srcFile);
        ScreenContentDetector.DetectedSessionState expected =
                ScreenContentDetector.DetectedSessionState.valueOf(Files.readString(expectedFile).trim());
        ScreenContentDetector.DetectedSessionState result = detector.detectSessionState(lines);
        assertEquals(expected, result, "State mismatch for case '" + caseName + "'");
    }

    // -------------------------------------------------------------------------
    // detectEditMode
    // -------------------------------------------------------------------------

    @org.junit.jupiter.api.Test
    void detectEditModeEmptyWhenNoIndicatorNoEscPattern() {
        // "esc to interrupt" without leading spaces → no mode indicator → Optional.empty()
        List<String> lines = List.of("Normal output", "esc to interrupt");
        java.util.Optional<EditMode> result = detector.detectEditMode(lines);
        assertTrue(result.isEmpty(), "Expected empty Optional when no mode indicator and no leading-space esc pattern");
    }

    @org.junit.jupiter.api.Test
    void detectEditModePlanWhenPlanModeInFooter() {
        List<String> lines = List.of(
                "Some output",
                "plan mode  |  esc to interrupt"
        );
        java.util.Optional<EditMode> result = detector.detectEditMode(lines);
        assertTrue(result.isPresent());
        assertEquals(EditMode.PLAN, result.get());
    }

    @org.junit.jupiter.api.Test
    void detectEditModePlanWithManyTrailingEmptyLines() {
        // JediTerm screen buffer has many trailing blank lines — must still detect plan mode
        List<String> lines = new ArrayList<>(List.of(
                "Some output",
                "\u23F8 plan mode on  |  esc to interrupt"
        ));
        for (int i = 0; i < 15; i++) lines.add("");
        java.util.Optional<EditMode> result = detector.detectEditMode(lines);
        assertTrue(result.isPresent());
        assertEquals(EditMode.PLAN, result.get());
    }

    @org.junit.jupiter.api.Test
    void detectEditModeAcceptEditsWithTrailingEmptyLines() {
        List<String> lines = new ArrayList<>(List.of(
                "Some output",
                "\u23F5\u23F5 accept edits on  |  esc to interrupt"
        ));
        for (int i = 0; i < 10; i++) lines.add("");
        java.util.Optional<EditMode> result = detector.detectEditMode(lines);
        assertTrue(result.isPresent());
        assertEquals(EditMode.ACCEPT_EDITS, result.get());
    }

    @org.junit.jupiter.api.Test
    void detectEditModeBypassPermissions() {
        // Screen text captured from Claude launched with --dangerously-skip-permissions
        List<String> lines = new ArrayList<>(List.of(
                "Some output",
                "\u23F5\u23F5 bypass permissions on (shift+tab to cycle)"
        ));
        for (int i = 0; i < 10; i++) lines.add("");
        java.util.Optional<EditMode> result = detector.detectEditMode(lines);
        assertTrue(result.isPresent());
        assertEquals(EditMode.BYPASS_PERMISSIONS, result.get());
    }

    @org.junit.jupiter.api.Test
    void detectEditModeBypassPermissionsCaseInsensitive() {
        List<String> lines = List.of("Some output", "Bypass Permissions on | esc to interrupt");
        java.util.Optional<EditMode> result = detector.detectEditMode(lines);
        assertTrue(result.isPresent());
        assertEquals(EditMode.BYPASS_PERMISSIONS, result.get());
    }

    @org.junit.jupiter.api.Test
    void detectEditModeEmptyWhenEscToInterruptNoLeadingSpaces() {
        // "esc to interrupt" without leading spaces (e.g. in transitioning screen) → Optional.empty()
        List<String> lines = new ArrayList<>(List.of(
                "Some output",
                "esc to interrupt"
        ));
        for (int i = 0; i < 15; i++) lines.add("");
        java.util.Optional<EditMode> result = detector.detectEditMode(lines);
        assertTrue(result.isEmpty(), "Expected empty Optional when esc line has no leading spaces");
    }

    @org.junit.jupiter.api.Test
    void detectEditModeDefaultWhenEscToInterruptLeadingSpaces() {
        // "  esc to interrupt" with two leading spaces → Ask/default mode confirmed
        List<String> lines = new ArrayList<>(List.of(
                "Some output",
                "  esc to interrupt"
        ));
        for (int i = 0; i < 15; i++) lines.add("");
        java.util.Optional<EditMode> result = detector.detectEditMode(lines);
        assertTrue(result.isPresent(), "Expected Optional.of(DEFAULT) for leading-space esc pattern");
        assertEquals(EditMode.DEFAULT, result.get());
    }

    @org.junit.jupiter.api.Test
    void detectEditModeEmptyWhenNullOrEmpty() {
        assertTrue(detector.detectEditMode(null).isEmpty());
        assertTrue(detector.detectEditMode(List.of()).isEmpty());
    }

    // -------------------------------------------------------------------------
    // detectPlanName
    // -------------------------------------------------------------------------

    @org.junit.jupiter.api.Test
    void detectPlanNameEmptyWhenNoMdInFooter() {
        List<String> lines = List.of("No plan here", "esc to interrupt");
        assertTrue(detector.detectPlanName(lines).isEmpty());
    }

    @org.junit.jupiter.api.Test
    void detectPlanNameFoundWhenMdFileInFooter() {
        List<String> lines = List.of(
                "Some output",
                "PLAN.md  |  plan mode"
        );
        java.util.Optional<String> result = detector.detectPlanName(lines);
        assertTrue(result.isPresent());
        assertEquals("PLAN.md", result.get());
    }

    @org.junit.jupiter.api.Test
    void detectPlanNameFoundWhenDashSeparatorLine() {
        // Claude shows plan name like: "──────── create-hello-world-script ──"
        List<String> lines = List.of(
                "Some output",
                "\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500 create-hello-world-script \u2500\u2500",
                "\u276F  ",
                "\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500",
                "  ? for shortcuts"
        );
        java.util.Optional<String> result = detector.detectPlanName(lines);
        assertTrue(result.isPresent());
        assertEquals("create-hello-world-script", result.get());
    }

    // -------------------------------------------------------------------------
    // detectInputPromptReady
    // -------------------------------------------------------------------------

    @org.junit.jupiter.api.Test
    void detectInputPromptReady_splashScreen() {
        // Splash screen has no empty ❯ prompt → false
        List<String> lines = List.of(
                " ╔═══════════════════════════════╗",
                " ║  Welcome to Claude Code       ║",
                " ╚═══════════════════════════════╝",
                "",
                " ? How can I help you today?"
        );
        assertFalse(detector.detectInputPromptReady(lines));
    }

    @org.junit.jupiter.api.Test
    void detectInputPromptReady_idlePrompt() {
        // Genuine idle state shows ❯ with nothing after it → true
        List<String> lines = new ArrayList<>(List.of(
                "Some previous output",
                "Another line",
                "\u276F "
        ));
        assertTrue(detector.detectInputPromptReady(lines));
    }

    @org.junit.jupiter.api.Test
    void detectInputPromptReady_idlePromptWithTrailingBlanks() {
        // Trailing blank lines after ❯ (JediTerm buffer) → still true
        List<String> lines = new ArrayList<>(List.of(
                "Some previous output",
                "\u276F"
        ));
        for (int i = 0; i < 4; i++) lines.add("");
        assertTrue(detector.detectInputPromptReady(lines));
    }

    @org.junit.jupiter.api.Test
    void detectInputPromptReady_promptWithText() {
        // ❯ with placeholder/typed text is still the idle input prompt → true
        List<String> lines = List.of(
                "Some output",
                "\u276F Ask anything (/ for commands)"
        );
        assertTrue(detector.detectInputPromptReady(lines));
    }

    @org.junit.jupiter.api.Test
    void detectInputPromptReady_nullOrEmpty() {
        assertFalse(detector.detectInputPromptReady(null));
        assertFalse(detector.detectInputPromptReady(List.of()));
    }

    @org.junit.jupiter.api.Test
    void detectInputPromptReady_notTriggeredByNumberedMenuCursor() {
        // Trust prompt screen: ❯ followed by a number is a menu cursor, not the input prompt
        List<String> lines = List.of(
                "Do you trust the files in this directory?",
                "❯ 1. Yes, I trust this folder",
                "  2. No, exit",
                "Enter to confirm · Esc to cancel"
        );
        assertFalse(detector.detectInputPromptReady(lines),
                "❯ followed by a digit must NOT be detected as input prompt");
    }

    // -------------------------------------------------------------------------
    // extractOption helper tests (kept inline — simple unit tests, no resource files needed)
    // -------------------------------------------------------------------------

    @org.junit.jupiter.api.Test
    void extractOptionWithCursorGlyph() {
        ChoiceMenuModel.Option opt = ScreenContentDetector.extractOption("\u276F 1. Yes", 1);
        assertEquals("Yes", opt.display());
        assertEquals("1", opt.response());
    }

    @org.junit.jupiter.api.Test
    void extractOptionWithoutCursorGlyph() {
        ChoiceMenuModel.Option opt = ScreenContentDetector.extractOption("2. Yes, allow reading", 2);
        assertEquals("Yes, allow reading", opt.display());
        assertEquals("2", opt.response());
    }

    @org.junit.jupiter.api.Test
    void extractOptionPreservesParenthetical() {
        ChoiceMenuModel.Option opt = ScreenContentDetector.extractOption("3. No (esc)", 3);
        assertEquals("No (esc)", opt.display());
        assertEquals("3", opt.response());
    }

    @org.junit.jupiter.api.Test
    void extractOptionPreservesBashWithArgs() {
        ChoiceMenuModel.Option opt = ScreenContentDetector.extractOption(
                "2. Bash(find /home/oleg/my-projects -name ptr_vector*)", 2);
        assertEquals("Bash(find /home/oleg/my-projects -name ptr_vector*)", opt.display());
        assertEquals("2", opt.response());
    }

    @org.junit.jupiter.api.Test
    void extractOptionNoDot() {
        ChoiceMenuModel.Option opt = ScreenContentDetector.extractOption("some text", 5);
        assertEquals("some text", opt.display());
        assertEquals("5", opt.response());
    }

    // -------------------------------------------------------------------------
    // detectYesNoPrompt (Bug 3)
    // -------------------------------------------------------------------------

    @org.junit.jupiter.api.Test
    void detectYesNoPromptTrueWhenYnAtBottom() {
        List<String> lines = new ArrayList<>(List.of(
                "Welcome to Claude Code!",
                "Do you trust the files in this directory?",
                "",
                "  /home/user/myproject",
                "",
                "Claude Code may read files in this directory. [Y/n]"
        ));
        assertTrue(detector.detectYesNoPrompt(lines),
                "Expected detectYesNoPrompt=true when [Y/n] present at bottom");
    }

    @org.junit.jupiter.api.Test
    void detectYesNoPromptTrueForYNVariants() {
        assertTrue(detector.detectYesNoPrompt(List.of("Some text [y/N]")),
                "[y/N] variant should match");
        assertTrue(detector.detectYesNoPrompt(List.of("Some text [yes/no]")),
                "[yes/no] variant should match");
        assertTrue(detector.detectYesNoPrompt(List.of("Some text (y/n)")),
                "(y/n) variant should match");
    }

    @org.junit.jupiter.api.Test
    void detectYesNoPromptFalseWhenNoYnPattern() {
        List<String> lines = List.of(
                "Normal output",
                "\u276F Ask anything..."
        );
        assertFalse(detector.detectYesNoPrompt(lines),
                "Expected detectYesNoPrompt=false for normal screen");
    }

    @org.junit.jupiter.api.Test
    void detectYesNoPromptFalseWhenNullOrEmpty() {
        assertFalse(detector.detectYesNoPrompt(null));
        assertFalse(detector.detectYesNoPrompt(List.of()));
    }
}
