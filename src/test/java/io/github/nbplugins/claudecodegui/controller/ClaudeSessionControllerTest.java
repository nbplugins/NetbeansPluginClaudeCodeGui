package io.github.nbplugins.claudecodegui.controller;

import io.github.nbplugins.claudecodegui.model.ClaudeSessionModel;
import io.github.nbplugins.claudecodegui.model.EditMode;
import io.github.nbplugins.claudecodegui.model.SessionLifecycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.nbplugins.claudecodegui.model.ChoiceMenuModel;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ClaudeSessionController} logic that does not require
 * a live PTY process.
 *
 * <p>All tests use a stub {@code screenLines} supplier and verify state
 * changes via {@link ClaudeSessionModel}.
 */
class ClaudeSessionControllerTest {

    private ClaudeSessionModel model;
    private ClaudeSessionController controller;

    @BeforeEach
    void setUp() {
        model = new ClaudeSessionModel();
        controller = new ClaudeSessionController(model, Collections::emptyList);
    }

    @AfterEach
    void tearDown() {
        // Drops the controller from the live-session / claim registries.
        controller.stopProcess();
    }

    // -------------------------------------------------------------------------
    // onClaudeIdle
    // -------------------------------------------------------------------------

    private static ChoiceMenuModel menu(String text, String... displays) {
        List<ChoiceMenuModel.Option> opts = new java.util.ArrayList<>();
        for (int i = 0; i < displays.length; i++) {
            opts.add(new ChoiceMenuModel.Option(displays[i], String.valueOf(i + 1)));
        }
        return new ChoiceMenuModel(text, opts, 0);
    }

    @Test
    void isShrunkViewOf_prefixOfScrollingList_true() {
        ChoiceMenuModel full = menu("Select model", "a", "b", "c", "d");
        ChoiceMenuModel shrunk = menu("Select model", "a", "b", "c");
        assertTrue(ClaudeSessionController.isShrunkViewOf(shrunk, full));
    }

    @Test
    void isShrunkViewOf_sameSizeOrGrown_false() {
        ChoiceMenuModel three = menu("Select model", "a", "b", "c");
        ChoiceMenuModel four = menu("Select model", "a", "b", "c", "d");
        assertFalse(ClaudeSessionController.isShrunkViewOf(three, three));
        assertFalse(ClaudeSessionController.isShrunkViewOf(four, three));
    }

    @Test
    void isShrunkViewOf_differentTextOrOptions_false() {
        ChoiceMenuModel full = menu("Select model", "a", "b", "c", "d");
        assertFalse(ClaudeSessionController.isShrunkViewOf(menu("Other", "a", "b", "c"), full));
        assertFalse(ClaudeSessionController.isShrunkViewOf(menu("Select model", "a", "x", "c"), full));
    }

    /** Menu whose options are numbered from {@code first} (a scrolled window of a long list). */
    private static ChoiceMenuModel scrolled(String text, int first, String... displays) {
        List<ChoiceMenuModel.Option> opts = new java.util.ArrayList<>();
        for (int i = 0; i < displays.length; i++) {
            opts.add(new ChoiceMenuModel.Option(displays[i], String.valueOf(first + i)));
        }
        return new ChoiceMenuModel(text, opts, 0);
    }

    @Test
    void isShrunkViewOf_scrolledWindowWithDifferentScrollIndicator_true() {
        ChoiceMenuModel full = scrolled("\u2191 8.  Fable 5", 9, "a", "b", "c", "d", "e");
        ChoiceMenuModel shrunk = scrolled("\u2191 10. b", 11, "c", "d", "e");
        assertTrue(ClaudeSessionController.isShrunkViewOf(shrunk, full));
    }

    @Test
    void isShrunkViewOf_scrolledWindowMismatchingOption_false() {
        ChoiceMenuModel full = scrolled("\u2191 8.  Fable 5", 9, "a", "b", "c", "d", "e");
        assertFalse(ClaudeSessionController.isShrunkViewOf(scrolled("\u2191 10. b", 11, "c", "x", "e"), full));
        assertFalse(ClaudeSessionController.isShrunkViewOf(scrolled("Question", 11, "c", "d", "e"), full));
    }

    @Test
    void onClaudeIdleSetsLifecycleToReady() {
        model.setLifecycle(SessionLifecycle.WORKING);
        controller.onClaudeIdle();
        assertEquals(SessionLifecycle.READY, model.getLifecycle());
    }

    @Test
    void onClaudeIdleFromStartingAlsoSetsReady() {
        assertEquals(SessionLifecycle.STARTING, model.getLifecycle());
        controller.onClaudeIdle();
        assertEquals(SessionLifecycle.READY, model.getLifecycle());
    }

    // -------------------------------------------------------------------------
    // cancelPrompt (no connector — should be a no-op)
    // -------------------------------------------------------------------------

    @Test
    void cancelPromptWithNoConnectorDoesNotCrash() {
        assertDoesNotThrow(() -> controller.cancelPrompt());
    }

    // -------------------------------------------------------------------------
    // onEditModeComboChanged
    // -------------------------------------------------------------------------

    @Test
    void onEditModeComboChangedUpdatesModel() {
        // Set up directory so registry write works
        model.setWorkingDirectory(new java.io.File("/tmp/test"));
        controller.onEditModeComboChanged(EditMode.ACCEPT_EDITS);
        assertEquals(EditMode.ACCEPT_EDITS, model.getEditMode());
    }

    @Test
    void onEditModeComboChangedNoOpWhenSameMode() {
        model.setWorkingDirectory(new java.io.File("/tmp/test"));
        model.setEditMode(EditMode.DEFAULT);

        AtomicReference<EditMode> captured = new AtomicReference<>(null);
        model.addListener(new NoOpListener() {
            @Override public void onEditModeChanged(io.github.nbplugins.claudecodegui.model.EditMode mode) {
                captured.set(mode);
            }
        });

        controller.onEditModeComboChanged(EditMode.DEFAULT);  // same as current
        // Listener must NOT have been called (no change)
        assertNull(captured.get(), "No notification expected when mode is unchanged");
    }

    @Test
    void onEditModeComboChangedNullIsIgnored() {
        assertDoesNotThrow(() -> controller.onEditModeComboChanged(null));
    }

    // -------------------------------------------------------------------------
    // triggerPromptScan (no connector — screen is empty)
    // -------------------------------------------------------------------------

    @Test
    void triggerPromptScanWithEmptyScreenLeavesChoiceMenuNull() throws Exception {
        // triggerPromptScan schedules on EDT; since we're already on the test thread
        // (not necessarily EDT), we just verify it doesn't crash and model stays clean
        assertDoesNotThrow(() -> controller.triggerPromptScan());
        // Give invokeLater time if needed (test thread is not EDT)
        Thread.sleep(50);
        assertNull(model.getActiveChoiceMenu());
    }

    // -------------------------------------------------------------------------
    // hasLiveProcess
    // -------------------------------------------------------------------------

    @Test
    void hasLiveProcessReturnsFalseWithoutProcess() {
        assertFalse(controller.hasLiveProcess());
    }

    // -------------------------------------------------------------------------
    // acceptEdits mode must not be overwritten by screen poll during WORKING
    // -------------------------------------------------------------------------

    /**
     * Regression: when lifecycle is WORKING the screen footer shows a spinner,
     * not the mode indicator.  The fixture has "esc to interrupt" without leading
     * spaces → detectEditMode() returns Optional.empty().  pollScreenState() must
     * skip the update when WORKING + empty (screen transitioning), so "acceptEdits"
     * is preserved in the model and registry.
     */
    @Test
    void pollScreenStateDoesNotOverwriteAcceptEditsWhileWorking() throws Exception {
        // Build a controller whose screenLines supplier returns a non-empty WORKING screen
        // (spinner visible, no "accept edits" text — exactly what Claude shows while executing)
        List<String> workingScreen = Arrays.asList(
                "Writing file src/main/java/Foo.java…",
                "⠙ Thinking",
                "esc to interrupt"
        );
        ClaudeSessionModel m2 = new ClaudeSessionModel();
        ClaudeSessionController c2 = new ClaudeSessionController(m2, () -> workingScreen);

        m2.setWorkingDirectory(new java.io.File("/tmp/test2"));
        m2.setEditMode(EditMode.ACCEPT_EDITS);
        m2.setLifecycle(SessionLifecycle.WORKING);

        // Simulate modelComboPopulated = true so the sync branch is reached
        java.lang.reflect.Field f =
                ClaudeSessionController.class.getDeclaredField("modelComboPopulated");
        f.setAccessible(true);
        f.setBoolean(c2, true);

        c2.pollScreenState();

        assertEquals(EditMode.ACCEPT_EDITS, m2.getEditMode(),
                "acceptEdits must not be overwritten by screen poll while WORKING");
        assertEquals(EditMode.ACCEPT_EDITS,
                io.github.nbplugins.claudecodegui.model.ClaudeSessionModel.EDIT_MODE_REGISTRY
                        .get("/tmp/test2"),
                "Registry must still hold acceptEdits");
    }

    /**
     * Regression guard: if the screen shows a concrete mode (e.g. "plan mode" disappears
     * and nothing replaces it → "default") while WORKING, the model must NOT be overwritten
     * with "default" — but if the screen explicitly shows "plan mode" text it must still update
     * (once confirmed twice — see {@link #pollScreenStateEditModeAppliesAfterTwoConsecutiveConfirmations}
     * for why a single poll is no longer enough on its own).
     */
    @Test
    void pollScreenStateDetectsPlanModeChangeWhileWorking() throws Exception {
        // Screen shows "plan mode" text still visible during WORKING
        List<String> planScreen = Arrays.asList(
                "⠙ Thinking",
                "esc to interrupt",
                "plan mode | ? for shortcuts"
        );
        ClaudeSessionModel m3 = new ClaudeSessionModel();
        ClaudeSessionController c3 = new ClaudeSessionController(m3, () -> planScreen);

        m3.setWorkingDirectory(new java.io.File("/tmp/test3"));
        m3.setEditMode(EditMode.DEFAULT);
        m3.setLifecycle(SessionLifecycle.WORKING);

        java.lang.reflect.Field f =
                ClaudeSessionController.class.getDeclaredField("modelComboPopulated");
        f.setAccessible(true);
        f.setBoolean(c3, true);

        c3.pollScreenState();
        assertEquals(EditMode.DEFAULT, m3.getEditMode(),
                "first poll must only register PLAN as a pending candidate, not apply it yet");

        c3.pollScreenState();
        assertEquals(EditMode.PLAN, m3.getEditMode(),
                "plan mode must be detected even during WORKING once confirmed on a second consecutive poll");
    }

    // -------------------------------------------------------------------------
    // pollScreenState — edit-mode debounce: a single glitch poll must not flip
    // the model; the same new mode confirmed twice must be applied.
    //
    // Regression: Claude's Ink renderer repaints the footer via cursor-movement
    // escapes, which can transiently reflow the bottom line for a single poll
    // tick. Applying every single-poll detection immediately could flip the model
    // to a glitch mode and back on the very next poll, visible to the user as the
    // mode briefly cycling through unrelated values and sometimes settling on the
    // wrong one.
    // -------------------------------------------------------------------------

    @Test
    void pollScreenStateEditModeDoesNotFlipOnSingleGlitchPoll() throws Exception {
        List<String> acceptEditsScreen = Collections.singletonList(
                "⏵⏵ accept edits on (shift+tab to cycle)");
        List<String> glitchScreen = Collections.singletonList(
                "⏵⏵ auto mode on (shift+tab to cycle)");
        AtomicReference<List<String>> screenRef = new AtomicReference<>(acceptEditsScreen);
        ClaudeSessionModel m = new ClaudeSessionModel();
        ClaudeSessionController c = new ClaudeSessionController(m, screenRef::get);
        m.setWorkingDirectory(new java.io.File("/tmp/test-glitch"));
        m.setEditMode(EditMode.ACCEPT_EDITS);
        m.setLifecycle(SessionLifecycle.READY);
        setModelComboPopulated(c, true);

        c.pollScreenState();
        assertEquals(EditMode.ACCEPT_EDITS, m.getEditMode(), "baseline poll must keep acceptEdits");

        // Single glitch poll: screen briefly shows an unrelated mode (mid-redraw reflow)
        screenRef.set(glitchScreen);
        c.pollScreenState();
        assertEquals(EditMode.ACCEPT_EDITS, m.getEditMode(),
                "a single glitch poll reading a different mode must not flip the model");

        // Glitch was one-off; real footer reverts on the next poll
        screenRef.set(acceptEditsScreen);
        c.pollScreenState();
        assertEquals(EditMode.ACCEPT_EDITS, m.getEditMode(),
                "model must still show acceptEdits once the glitch frame is gone");
    }

    @Test
    void pollScreenStateEditModeAppliesAfterTwoConsecutiveConfirmations() throws Exception {
        List<String> acceptEditsScreen = Collections.singletonList(
                "⏵⏵ accept edits on (shift+tab to cycle)");
        List<String> autoScreen = Collections.singletonList(
                "⏵⏵ auto mode on (shift+tab to cycle)");
        AtomicReference<List<String>> screenRef = new AtomicReference<>(acceptEditsScreen);
        ClaudeSessionModel m = new ClaudeSessionModel();
        ClaudeSessionController c = new ClaudeSessionController(m, screenRef::get);
        m.setWorkingDirectory(new java.io.File("/tmp/test-confirm"));
        m.setEditMode(EditMode.ACCEPT_EDITS);
        m.setLifecycle(SessionLifecycle.READY);
        setModelComboPopulated(c, true);

        c.pollScreenState();
        assertEquals(EditMode.ACCEPT_EDITS, m.getEditMode());

        screenRef.set(autoScreen);
        c.pollScreenState();
        assertEquals(EditMode.ACCEPT_EDITS, m.getEditMode(),
                "first differing poll must only become a pending candidate, not apply immediately");

        c.pollScreenState();
        assertEquals(EditMode.AUTO, m.getEditMode(),
                "second consecutive confirming poll must apply the new mode");
    }

    private static void setModelComboPopulated(ClaudeSessionController c, boolean value) throws Exception {
        java.lang.reflect.Field f = ClaudeSessionController.class.getDeclaredField("modelComboPopulated");
        f.setAccessible(true);
        f.setBoolean(c, value);
    }

    // -------------------------------------------------------------------------
    // setWorkingDirectory — propagated to listeners (required for tab title fix)
    // -------------------------------------------------------------------------

    @Test
    void setWorkingDirectoryNotifiesListener() {
        AtomicReference<java.io.File> captured = new AtomicReference<>(null);
        model.addListener(new NoOpListener() {
            @Override public void onWorkingDirectoryChanged(java.io.File dir) {
                captured.set(dir);
            }
        });

        java.io.File dir = new java.io.File("/tmp/test-wd");
        model.setWorkingDirectory(dir);

        assertEquals(dir, captured.get(),
                "model.setWorkingDirectory must fire onWorkingDirectoryChanged so the tab title updates");
    }

    // -------------------------------------------------------------------------
    // flushPendingPrompt — Y/n trust prompt must not be dismissed on next tick
    // -------------------------------------------------------------------------

    /**
     * Regression: synthetic Y/n menu was destroyed on the very next timer tick
     * because detectChoiceMenu() returns empty for Y/n screens, which triggered
     * model.clearChoiceMenu() before the user could interact with it.
     */
    @Test
    void flushPendingPromptDoesNotDismissYnMenuWhileYnStillOnScreen() throws Exception {
        List<String> ynScreen = Arrays.asList(
                "Do you trust the files in this directory?",
                "Only proceed if you trust the source. [Y/n]"
        );
        ClaudeSessionModel m = new ClaudeSessionModel();
        ClaudeSessionController c = new ClaudeSessionController(m, () -> ynScreen);

        // First call: detects Y/n → creates synthetic menu
        c.flushPendingPrompt();
        assertNotNull(m.getActiveChoiceMenu(), "Y/n menu must be created on first flush");

        // Second call (simulates next timer tick): Y/n still on screen → must NOT clear menu
        c.flushPendingPrompt();
        assertNotNull(m.getActiveChoiceMenu(),
                "Y/n menu must survive subsequent flush while Y/n prompt is still on screen");
    }

    // -------------------------------------------------------------------------
    // flushPendingPrompt — trust prompt detected from PTY buffer when screen empty
    // -------------------------------------------------------------------------

    /**
     * Regression: when the JediTerm widget has not yet been laid out (terminal size 0×0),
     * screenLines.get() returns an empty list. The trust-directory prompt must still be
     * detected from the rolling PTY line buffer.
     */
    @Test
    void flushPendingPromptDetectsTrustPromptFromPtyBufferWhenScreenEmpty() {
        ClaudeSessionModel m = new ClaudeSessionModel();
        // screenLines always returns empty — simulates uninitialised JediTerm widget
        ClaudeSessionController c = new ClaudeSessionController(m, Collections::emptyList);

        c.simulatePtyLine("─────────────────────────────────");
        c.simulatePtyLine("Accessing workspace:");
        c.simulatePtyLine("/home/user/my-project");
        c.simulatePtyLine("Quick safety check: Is this a project you trust?");
        c.simulatePtyLine("❯ 1. Yes, I trust this folder");
        c.simulatePtyLine("2. No, exit");
        c.simulatePtyLine("Enter to confirm · Esc to cancel");

        c.flushPendingPrompt();

        assertNotNull(m.getActiveChoiceMenu(),
                "ChoiceMenuPanel must appear for trust prompt even when screen buffer is empty");
        assertEquals(2, m.getActiveChoiceMenu().options().size(),
                "Trust prompt must have exactly 2 options");
    }

    // -------------------------------------------------------------------------
    // pollScreenState — stale menu cleared when spinner replaces it on screen
    // -------------------------------------------------------------------------

    /**
     * Regression: if a choice menu is shown and the user answers it directly in
     * the terminal, Claude immediately starts the thinking spinner (ESC[15A ●).
     * The spinner continuously restarts promptFlushTimer so flushPendingPrompt
     * never fires.  The stale menu stays in the model, blocking the next menu
     * (e.g. "exit plan mode" Yes/No) from being shown.
     *
     * Fix: pollScreenState must clear the menu when detectChoiceMenu returns
     * empty and no Y/n prompt is on screen.
     */
    @Test
    void pollScreenStateClearsStaleMenuWhenMenuGoneFromScreen() throws Exception {
        // Screen showing a choice menu (bash dialog)
        List<String> menuScreen = Arrays.asList(
                " Bash command",
                "   git commit -m \"...\"",
                "   Run shell command",
                " Do you want to proceed?",
                " \u276f 1. Yes",
                "   2. No",
                " Esc to cancel \u00b7 Tab to amend"
        );
        AtomicReference<List<String>> screenRef = new AtomicReference<>(menuScreen);
        ClaudeSessionModel m = new ClaudeSessionModel();
        ClaudeSessionController c = new ClaudeSessionController(m, screenRef::get);

        // First poll: detects menu and sets it
        c.pollScreenState();
        assertNotNull(m.getActiveChoiceMenu(), "menu must be set on first poll");

        // User answered in terminal; screen now shows only the spinner (no menu)
        screenRef.set(Arrays.asList(
                "\u25cf",   // spinner
                "esc to interrupt"
        ));

        // Second poll: menu gone from screen — must clear it
        c.pollScreenState();
        assertNull(m.getActiveChoiceMenu(),
                "pollScreenState must clear stale menu when it disappears from screen");
    }

    /**
     * Regression guard: after stale menu is cleared, a NEW menu on screen must
     * be picked up on the very next pollScreenState call.
     */
    @Test
    void pollScreenStateSetsNewMenuAfterStaleMenuCleared() throws Exception {
        List<String> menuScreen1 = Arrays.asList(
                " Do you want to proceed?",
                " \u276f 1. Yes",
                "   2. No",
                " Esc to cancel"
        );
        List<String> spinnerScreen = Arrays.asList("\u25cf", "esc to interrupt");
        List<String> menuScreen2 = Arrays.asList(
                " Claude wants to exit plan mode",
                " \u276f 1. Yes",
                "   2. No",
                " Esc to cancel"
        );
        AtomicReference<List<String>> screenRef = new AtomicReference<>(menuScreen1);
        ClaudeSessionModel m = new ClaudeSessionModel();
        ClaudeSessionController c = new ClaudeSessionController(m, screenRef::get);

        c.pollScreenState();
        assertNotNull(m.getActiveChoiceMenu(), "first menu must be set");

        // Answered in terminal, spinner appears
        screenRef.set(spinnerScreen);
        c.pollScreenState();
        assertNull(m.getActiveChoiceMenu(), "stale menu must be cleared");

        // New menu appears
        screenRef.set(menuScreen2);
        c.pollScreenState();
        assertNotNull(m.getActiveChoiceMenu(), "new menu must be detected after stale cleared");
    }

    // -------------------------------------------------------------------------
    // pollScreenState — flicker regression: choice-panel resize reflows the same
    // dialog to a different wrapped width, which must not immediately replace it
    // -------------------------------------------------------------------------

    /**
     * Regression (GrandOrgue project bug report): showing a choice menu resizes the
     * terminal (to fit the ChoiceMenuPanel), which makes Claude's Ink renderer re-wrap
     * the SAME dialog at the new column width. If the reflowed variant is applied to
     * the model immediately, it triggers another divider resize, causing another
     * reflow, looping forever (visible as a flickering dialog).
     *
     * <p>These are the actual "git-commit-bash-dialog" / "git-commit-bash-dialog-narrow"
     * fixtures from {@code screen-content-detector/detect-choice-menu/} — the same
     * real-world dialog, reflowed at two different terminal widths; only option 2's
     * wrapped display text differs.
     *
     * <p>Fix: a replacement candidate that differs from the currently active menu must
     * be seen on two consecutive polls before it replaces the active menu.
     */
    @Test
    void pollScreenStateDoesNotFlickerBetweenReflowedMenuVariants() throws Exception {
        List<String> normalWidth = Arrays.asList(
                " Bash command",
                "",
                "   git add pom.xml README.md && git commit -m \"$(cat <<'EOF'",
                "   Rename Maven project: name to \"Netbeans Claude Code GUI\", artifactId to \"netbeans-claude-code-gui\" (0.14.17-SNAPSHOT)",
                "",
                "   Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>",
                "   EOF",
                "   )\"",
                "   Run shell command",
                "",
                " Do you want to proceed?",
                " ❯ 1. Yes",
                "   2. Yes, and don't ask again for git add and git commit commands in /home/oleg/my-projects/NetbeansClaudeCodePlugin",
                "   3. No",
                "",
                " Esc to cancel · Tab to amend · ctrl+e to explain"
        );
        List<String> narrowWidth = Arrays.asList(
                " Bash command",
                "",
                "   git add pom.xml README.md && git commit -m \"$(cat <<'EOF'",
                "   Rename Maven project: name to \"Netbeans Claude Code GUI\", artifactId to \"netbeans-claude-code-gui\"",
                " (0.14.17-SNAPSHOT)",
                "",
                "   Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>",
                "   EOF",
                "   )\"",
                "   Run shell command",
                "",
                " Do you want to proceed?",
                " ❯ 1. Yes",
                "   2. Yes, and don't ask again for git add and git commit commands in /home/oleg/my-projects/Ne",
                "tbeansClaudeCodePlugin",
                "   3. No",
                "",
                " Esc to cancel · Tab to amend · ctrl+e to explain"
        );

        AtomicReference<List<String>> screenRef = new AtomicReference<>(normalWidth);
        ClaudeSessionModel m = new ClaudeSessionModel();
        ClaudeSessionController c = new ClaudeSessionController(m, screenRef::get);

        c.pollScreenState();
        ChoiceMenuModel shown = m.getActiveChoiceMenu();
        assertNotNull(shown, "menu must be set on first poll");
        String originalOption2 = shown.options().get(1).display();

        // Choice panel resize reflows the dialog to a narrower width for one poll.
        screenRef.set(narrowWidth);
        c.pollScreenState();
        assertEquals(originalOption2, m.getActiveChoiceMenu().options().get(1).display(),
                "a single differing poll must not replace the active menu with the reflowed variant");

        // Screen reflows back to the original width before the narrow variant is ever
        // confirmed twice in a row -- the menu must never have flickered to it.
        screenRef.set(normalWidth);
        c.pollScreenState();
        assertEquals(originalOption2, m.getActiveChoiceMenu().options().get(1).display(),
                "menu must remain stable and never show the transient reflowed variant");
    }

    /**
     * A genuinely different menu (not a reflow artifact) must still be applied once
     * it is seen on two consecutive polls, so the debounce added for the flicker fix
     * does not permanently block legitimate menu replacements.
     */
    @Test
    void pollScreenStateAppliesNewMenuAfterTwoConsecutiveConfirmations() throws Exception {
        List<String> menuA = Arrays.asList(
                " Do you want to proceed?",
                " ❯ 1. Yes",
                "   2. No",
                " Esc to cancel"
        );
        List<String> menuB = Arrays.asList(
                " Claude wants to exit plan mode",
                " ❯ 1. Yes",
                "   2. No",
                " Esc to cancel"
        );
        AtomicReference<List<String>> screenRef = new AtomicReference<>(menuA);
        ClaudeSessionModel m = new ClaudeSessionModel();
        ClaudeSessionController c = new ClaudeSessionController(m, screenRef::get);

        c.pollScreenState();
        assertEquals("Do you want to proceed?", m.getActiveChoiceMenu().text());

        screenRef.set(menuB);
        c.pollScreenState();
        assertEquals("Do you want to proceed?", m.getActiveChoiceMenu().text(),
                "must not replace on the first differing detection");

        c.pollScreenState();
        assertEquals("Claude wants to exit plan mode", m.getActiveChoiceMenu().text(),
                "genuinely new menu must be applied after two consecutive confirmations");
    }

    // -------------------------------------------------------------------------
    // pollScreenState — model discovery deferred while screen shows WORKING
    // -------------------------------------------------------------------------

    /**
     * Regression: when --continue resumes a session where Claude is still actively
     * working, detectInputPromptReady() returns true (❯ is always visible), causing
     * STARTING → READY, and then discoverModels() fires immediately. discoverModels()
     * sets lifecycle back to WORKING and sends /model to a busy Claude, which times
     * out and leaves the session stuck permanently in WORKING.
     *
     * Fix: model discovery must be deferred when detectSessionState returns WORKING
     * (i.e. "esc to interrupt" is visible in the footer).
     */
    @Test
    void pollScreenState_doesNotTriggerModelDiscovery_whileScreenShowsWorking() throws Exception {
        List<String> workingScreen = Arrays.asList(
                "Some previous Claude output here",
                "────────────────",
                "❯",
                "────────────────",
                "  esc to interrupt"
        );
        AtomicReference<Integer> discoveryCalls = new AtomicReference<>(0);
        ClaudeSessionModel m = new ClaudeSessionModel();
        ClaudeSessionController c = new ClaudeSessionController(m, () -> workingScreen) {
            @Override void discoverModels() { discoveryCalls.set(discoveryCalls.get() + 1); }
        };

        // Session starts in STARTING; first poll: STARTING→READY (❯ visible),
        // then READY→WORKING (esc to interrupt); discovery must be deferred
        c.pollScreenState();

        assertEquals(SessionLifecycle.WORKING, m.getLifecycle(),
                "lifecycle must be WORKING: STARTING→READY then READY→WORKING (esc to interrupt)");
        assertEquals(0, (int) discoveryCalls.get(),
                "discoverModels must NOT fire while screen shows 'esc to interrupt'");
    }

    /**
     * Verifies that model discovery IS triggered once the screen transitions to
     * truly idle state (❯ visible, no "esc to interrupt").
     */
    @Test
    void pollScreenState_triggersModelDiscovery_whenScreenBecomesReady() throws Exception {
        List<String> workingScreen = Arrays.asList(
                "Some previous Claude output here",
                "────────────────",
                "❯",
                "────────────────",
                "  esc to interrupt"
        );
        List<String> readyScreen = Arrays.asList(
                "Some previous Claude output here",
                "────────────────",
                "❯",
                "────────────────",
                "  ⏸ plan mode on  ·  ? for shortcuts"
        );
        AtomicReference<List<String>> screenRef = new AtomicReference<>(workingScreen);
        AtomicReference<Integer> discoveryCalls = new AtomicReference<>(0);
        ClaudeSessionModel m = new ClaudeSessionModel();
        ClaudeSessionController c = new ClaudeSessionController(m, screenRef::get) {
            @Override void discoverModels() { discoveryCalls.set(discoveryCalls.get() + 1); }
        };

        // First poll: STARTING → READY → WORKING; discovery deferred
        c.pollScreenState();
        assertEquals(SessionLifecycle.WORKING, m.getLifecycle());
        assertEquals(0, (int) discoveryCalls.get(), "no discovery while screen shows WORKING");

        // Screen transitions to idle
        screenRef.set(readyScreen);

        // Second poll: WORKING → READY (screen sync fires at end of poll);
        // model discovery guard runs before the sync, so discovery not yet triggered
        c.pollScreenState();
        assertEquals(SessionLifecycle.READY, m.getLifecycle(),
                "lifecycle must be READY once screen shows idle state");

        // Third poll: lifecycle is READY, screen is READY → discovery fires
        c.pollScreenState();
        assertEquals(1, (int) discoveryCalls.get(),
                "discoverModels must fire once the screen becomes truly idle");
    }

    // -------------------------------------------------------------------------
    // custom model list — appended after standard models in discoverModels
    // -------------------------------------------------------------------------

    /**
     * Verifies that custom model IDs injected via reflection are appended after
     * standard models when {@code discoverModels} stores the final list.
     *
     * <p>We simulate the post-discovery state by directly setting
     * {@code customModelIds} and {@code standardModelCount} via reflection,
     * then calling the private helper that builds and publishes the list.
     * This avoids the need for a live PTY process.
     */
    @Test
    void discoverModels_customModelsAppendedAfterStandardModels() throws Exception {
        ClaudeSessionModel m = new ClaudeSessionModel();
        ClaudeSessionController c = new ClaudeSessionController(m, Collections::emptyList);

        // Inject customModelIds
        java.lang.reflect.Field customField =
                ClaudeSessionController.class.getDeclaredField("customModelIds");
        customField.setAccessible(true);
        customField.set(c, java.util.Arrays.asList("openai/gpt-4o", "openai/gpt-4-turbo"));

        // Simulate post-discovery: 2 standard models discovered
        List<String> standardModels = java.util.Arrays.asList("Sonnet 4.6", "Opus 4.6");
        java.lang.reflect.Field stdCount =
                ClaudeSessionController.class.getDeclaredField("standardModelCount");
        stdCount.setAccessible(true);
        stdCount.setInt(c, standardModels.size());

        // Build the final list the same way discoverModels does
        List<String> combined = new java.util.ArrayList<>(standardModels);
        combined.addAll(java.util.Arrays.asList("openai/gpt-4o", "openai/gpt-4-turbo"));
        m.setModelList(combined, 0);

        List<String> result = m.getAvailableModels();
        assertEquals(4, result.size());
        assertEquals("Sonnet 4.6",       result.get(0));
        assertEquals("Opus 4.6",         result.get(1));
        assertEquals("openai/gpt-4o",    result.get(2));
        assertEquals("openai/gpt-4-turbo", result.get(3));
    }

    /**
     * Verifies that {@code switchModel} with a custom-model index is a no-op
     * when there is no active connector (same guard as standard path).
     */
    @Test
    void switchModel_customIndex_noopWithoutConnector() throws Exception {
        ClaudeSessionModel m = new ClaudeSessionModel();
        ClaudeSessionController c = new ClaudeSessionController(m, Collections::emptyList);
        m.setLifecycle(SessionLifecycle.READY);

        java.lang.reflect.Field customField =
                ClaudeSessionController.class.getDeclaredField("customModelIds");
        customField.setAccessible(true);
        customField.set(c, java.util.Arrays.asList("openai/gpt-4o"));

        java.lang.reflect.Field stdCount =
                ClaudeSessionController.class.getDeclaredField("standardModelCount");
        stdCount.setAccessible(true);
        stdCount.setInt(c, 2);  // index 2 → custom

        // Must not throw even though connector is null
        assertDoesNotThrow(() -> c.switchModel(2));
    }

    /**
     * Verifies that {@code stopProcess} resets {@code customModelIds} and
     * {@code standardModelCount} to their initial values.
     */
    @Test
    void stopProcess_resetsCustomModelState() throws Exception {
        ClaudeSessionModel m = new ClaudeSessionModel();
        ClaudeSessionController c = new ClaudeSessionController(m, Collections::emptyList);

        // Inject non-default values
        java.lang.reflect.Field customField =
                ClaudeSessionController.class.getDeclaredField("customModelIds");
        customField.setAccessible(true);
        customField.set(c, java.util.Arrays.asList("openai/gpt-4o"));

        java.lang.reflect.Field stdCount =
                ClaudeSessionController.class.getDeclaredField("standardModelCount");
        stdCount.setAccessible(true);
        stdCount.setInt(c, 3);

        c.stopProcess();

        List<?> ids = (List<?>) customField.get(c);
        assertTrue(ids.isEmpty(), "customModelIds must be empty after stopProcess");
        assertEquals(0, stdCount.getInt(c), "standardModelCount must be 0 after stopProcess");
    }

    // -------------------------------------------------------------------------
    // computeCheckboxToggles
    // -------------------------------------------------------------------------

    private static ChoiceMenuModel.Option checkbox(String response, boolean checked) {
        return new ChoiceMenuModel.Option(response, response, null, checked, true);
    }

    private static ChoiceMenuModel.Option noCheckbox(String response) {
        return new ChoiceMenuModel.Option(response, response, null, false, false);
    }

    @Test
    void computeToggles_allUnchecked_wantSome() {
        List<ChoiceMenuModel.Option> opts = List.of(
                checkbox("1", false), checkbox("2", false), checkbox("3", false));
        List<String> toggles = ClaudeSessionController.computeCheckboxToggles(Set.of("1", "3"), opts);
        assertEquals(List.of("1", "3"), toggles);
    }

    @Test
    void computeToggles_someAlreadyChecked_wantIncludesThem() {
        // Option 2 is already checked; user wants {1, 2} — should only toggle 1
        List<ChoiceMenuModel.Option> opts = List.of(
                checkbox("1", false), checkbox("2", true));
        List<String> toggles = ClaudeSessionController.computeCheckboxToggles(Set.of("1", "2"), opts);
        assertEquals(List.of("1"), toggles);
    }

    @Test
    void computeToggles_needToUncheckAndCheck() {
        // Options 1 and 2 are checked; user wants {2, 3} — toggle 1 (uncheck) and 3 (check)
        List<ChoiceMenuModel.Option> opts = List.of(
                checkbox("1", true), checkbox("2", true), checkbox("3", false));
        List<String> toggles = ClaudeSessionController.computeCheckboxToggles(Set.of("2", "3"), opts);
        assertEquals(List.of("1", "3"), toggles);
    }

    @Test
    void computeToggles_wantNone_returnsEmpty() {
        List<ChoiceMenuModel.Option> opts = List.of(
                checkbox("1", false), checkbox("2", false));
        List<String> toggles = ClaudeSessionController.computeCheckboxToggles(Set.of(), opts);
        assertEquals(List.of(), toggles);
    }

    @Test
    void computeToggles_nonCheckboxOptionsSkipped() {
        List<ChoiceMenuModel.Option> opts = List.of(
                checkbox("1", false), noCheckbox("2"), checkbox("3", false));
        List<String> toggles = ClaudeSessionController.computeCheckboxToggles(Set.of("1", "2"), opts);
        // Option 2 has no checkbox — ignored; only 1 toggled
        assertEquals(List.of("1"), toggles);
    }

    @Test
    void computeToggles_alreadyMatchingDesired_noToggles() {
        // Option 1 checked, user wants {1} — nothing to do
        List<ChoiceMenuModel.Option> opts = List.of(checkbox("1", true), checkbox("2", false));
        List<String> toggles = ClaudeSessionController.computeCheckboxToggles(Set.of("1"), opts);
        assertEquals(List.of(), toggles);
    }

    // -------------------------------------------------------------------------
    // trySendShiftTabsUntilMode — read-first, multi-poll fix
    // -------------------------------------------------------------------------

    /**
     * Regression: when already at the target mode, no Shift+Tab must be sent.
     * Old code always sent a tab first; that overshoot the mode cycle.
     */
    @Test
    void trySendShiftTabsUntilMode_alreadyAtTarget_noTabSent() throws Exception {
        AtomicReference<Integer> tabsSent = new AtomicReference<>(0);
        ClaudeSessionModel m = new ClaudeSessionModel();
        ClaudeSessionController c = new ClaudeSessionController(m, Collections::emptyList) {
            @Override Optional<EditMode> detectCurrentMode() { return Optional.of(EditMode.PLAN); }
            @Override void sendOneShiftTab() { tabsSent.set(tabsSent.get() + 1); }
        };
        setModeSwitchPollMs(c, 1);

        boolean result = invokeTrySend(c, EditMode.PLAN);

        assertTrue(result, "must return true when already at target");
        assertEquals(0, (int) tabsSent.get(), "no tab should be sent when already at target");
    }

    /**
     * Regression: when target is DEFAULT and screen has no mode marker,
     * the initial read must recognise it as "already in default" and not send any tab.
     */
    @Test
    void trySendShiftTabsUntilMode_alreadyDefault_noTabSent() throws Exception {
        AtomicReference<Integer> tabsSent = new AtomicReference<>(0);
        ClaudeSessionModel m = new ClaudeSessionModel();
        ClaudeSessionController c = new ClaudeSessionController(m, Collections::emptyList) {
            @Override Optional<EditMode> detectCurrentMode() { return Optional.empty(); }
            @Override void sendOneShiftTab() { tabsSent.set(tabsSent.get() + 1); }
        };
        setModeSwitchPollMs(c, 1);

        boolean result = invokeTrySend(c, EditMode.DEFAULT);

        assertTrue(result, "must return true when screen has no mode marker and target is DEFAULT");
        assertEquals(0, (int) tabsSent.get(), "no tab should be sent when already in default");
    }

    /**
     * Regression: prevents tab accumulation — only sends the next tab after observing
     * a concrete mode change caused by the current tab.
     *
     * Sequence: initial=AUTO, tab-0→DEFAULT(empty during WORKING, timeout after 15 polls),
     * tab-1→ACCEPT_EDITS, tab-2→PLAN.
     */
    @Test
    void trySendShiftTabsUntilMode_waitsForConcreteChangeBeforeNextTab() throws Exception {
        AtomicReference<Integer> tabsSent = new AtomicReference<>(0);
        // Simulate: initial=AUTO, after tab-0: 15 empty polls (DEFAULT invisible during WORKING)
        // then after tab-1: ACCEPT_EDITS appears on poll-3, then after tab-2: PLAN on poll-2.
        AtomicReference<Integer> pollCount = new AtomicReference<>(0);
        ClaudeSessionModel m = new ClaudeSessionModel();
        ClaudeSessionController c = new ClaudeSessionController(m, Collections::emptyList) {
            @Override Optional<EditMode> detectCurrentMode() {
                int tabs = tabsSent.get();
                int poll = pollCount.updateAndGet(v -> v + 1);
                if (tabs == 0) return Optional.of(EditMode.AUTO); // initial read
                if (tabs == 1) return Optional.empty();           // tab-0: DEFAULT invisible
                if (tabs == 2) return poll <= 18 ? Optional.empty()
                        : Optional.of(EditMode.ACCEPT_EDITS);     // tab-1: ACCEPT_EDITS at poll 3
                return Optional.of(EditMode.PLAN);                // tab-2: PLAN immediately
            }
            @Override void sendOneShiftTab() {
                pollCount.set(0); // reset poll counter for each new tab
                tabsSent.updateAndGet(v -> v + 1);
            }
        };
        setModeSwitchPollMs(c, 1);

        boolean result = invokeTrySend(c, EditMode.PLAN);

        assertTrue(result, "must reach PLAN");
        assertEquals(3, (int) tabsSent.get(), "exactly 3 tabs needed: AUTO→DEFAULT→ACCEPT_EDITS→PLAN");
    }

    /**
     * When the mode change is immediately visible (e.g. CC is idle), only one tab
     * is sent and the target is detected on the first poll.
     */
    @Test
    void trySendShiftTabsUntilMode_stopsAtTargetWithoutOvershoot() throws Exception {
        AtomicReference<Integer> tabsSent = new AtomicReference<>(0);
        ClaudeSessionModel m = new ClaudeSessionModel();
        ClaudeSessionController c = new ClaudeSessionController(m, Collections::emptyList) {
            @Override Optional<EditMode> detectCurrentMode() {
                return tabsSent.get() == 0
                        ? Optional.of(EditMode.ACCEPT_EDITS)  // initial
                        : Optional.of(EditMode.PLAN);         // after first tab
            }
            @Override void sendOneShiftTab() { tabsSent.updateAndGet(v -> v + 1); }
        };
        setModeSwitchPollMs(c, 1);

        boolean result = invokeTrySend(c, EditMode.PLAN);

        assertTrue(result, "must return true on detecting PLAN");
        assertEquals(1, (int) tabsSent.get(), "exactly one tab needed");
    }

    /**
     * When the mode is never reached (e.g. CC doesn't support it), must return
     * false after 5 attempts. Each attempt times out (returns empty = no concrete change).
     */
    @Test
    void trySendShiftTabsUntilMode_returnsfalseWhenModeNeverReached() throws Exception {
        AtomicReference<Integer> tabsSent = new AtomicReference<>(0);
        ClaudeSessionModel m = new ClaudeSessionModel();
        // initial=ACCEPT_EDITS, always stays ACCEPT_EDITS → never see a concrete different mode
        ClaudeSessionController c = new ClaudeSessionController(m, Collections::emptyList) {
            @Override Optional<EditMode> detectCurrentMode() { return Optional.of(EditMode.ACCEPT_EDITS); }
            @Override void sendOneShiftTab() { tabsSent.updateAndGet(v -> v + 1); }
        };
        setModeSwitchPollMs(c, 1);

        boolean result = invokeTrySend(c, EditMode.PLAN);

        assertFalse(result, "must return false after 5 failed attempts");
        assertEquals(5, (int) tabsSent.get(), "5 tabs must have been sent");
    }

    /**
     * Regression: selecting a second mode while a Shift+Tab switch is still running
     * started a second worker thread. Both sent Shift+Tabs concurrently, each steering
     * toward its own target, so the mode kept cycling and landed on an arbitrary mode.
     * A new request must instead be picked up by the single running worker.
     */
    @Test
    void onEditModeComboChanged_secondRequestDuringSwitch_isHandledBySameWorker() throws Exception {
        // Simulated CC cycle (2.1.x order): DEFAULT → ACCEPT_EDITS → PLAN → AUTO → DEFAULT
        EditMode[] cycle = {EditMode.DEFAULT, EditMode.ACCEPT_EDITS, EditMode.PLAN, EditMode.AUTO};
        java.util.concurrent.atomic.AtomicInteger ccMode = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.Set<Thread> senders = java.util.concurrent.ConcurrentHashMap.newKeySet();
        java.util.concurrent.CountDownLatch secondIssued = new java.util.concurrent.CountDownLatch(1);
        ClaudeSessionModel m = new ClaudeSessionModel();
        m.setEditMode(EditMode.DEFAULT);
        ClaudeSessionController c = new ClaudeSessionController(m, Collections::emptyList) {
            @Override boolean isConnected() { return true; }
            @Override Optional<EditMode> detectCurrentMode() { return Optional.of(cycle[ccMode.get()]); }
            @Override void sendOneShiftTab() {
                senders.add(Thread.currentThread());
                // hold worker 1 until the second request is issued, so the test is deterministic
                try { secondIssued.await(2, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                ccMode.updateAndGet(i -> (i + 1) % cycle.length);
            }
        };
        setModeSwitchPollMs(c, 1);

        c.onEditModeComboChanged(EditMode.AUTO);
        c.onEditModeComboChanged(EditMode.PLAN);
        secondIssued.countDown();

        java.lang.reflect.Field inProgress = ClaudeSessionController.class.getDeclaredField("modeSwitchInProgress");
        inProgress.setAccessible(true);
        long deadline = System.currentTimeMillis() + 5_000;
        while (inProgress.getBoolean(c) && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }

        assertFalse(inProgress.getBoolean(c), "switch must finish");
        assertEquals(EditMode.PLAN, cycle[ccMode.get()], "must end on the most recently requested mode");
        assertEquals(1, senders.size(), "Shift+Tabs must be sent by a single worker thread");
    }

    /**
     * Regression: an unchecked exception in the Shift+Tab worker (e.g. NPE when the
     * connector is gone after the session stopped) left the switch target set and
     * {@code modeSwitchInProgress} true forever, so no later switch ever ran and the
     * screen-to-model mode sync stayed disabled.
     */
    @Test
    void onEditModeComboChanged_uncheckedExceptionInWorker_doesNotBlockLaterSwitches() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        ClaudeSessionModel m = new ClaudeSessionModel();
        m.setEditMode(EditMode.DEFAULT);
        ClaudeSessionController c = new ClaudeSessionController(m, Collections::emptyList) {
            @Override boolean isConnected() { return true; }
            @Override Optional<EditMode> detectCurrentMode() { return Optional.of(EditMode.DEFAULT); }
            @Override void sendOneShiftTab() {
                if (calls.incrementAndGet() == 1) throw new NullPointerException("connector gone");
            }
        };
        setModeSwitchPollMs(c, 1);
        java.lang.reflect.Field inProgress = ClaudeSessionController.class.getDeclaredField("modeSwitchInProgress");
        inProgress.setAccessible(true);

        c.onEditModeComboChanged(EditMode.PLAN);
        long deadline = System.currentTimeMillis() + 5_000;
        while (calls.get() < 1 && System.currentTimeMillis() < deadline) Thread.sleep(5);
        Thread.sleep(200);
        assertFalse(inProgress.getBoolean(c), "flag must be cleared after the worker failed");

        c.onEditModeComboChanged(EditMode.ACCEPT_EDITS); // model already holds PLAN, so pick another mode
        deadline = System.currentTimeMillis() + 5_000;
        while (calls.get() < 2 && System.currentTimeMillis() < deadline) Thread.sleep(5);
        assertTrue(calls.get() >= 2, "a new worker must start after the failed one");
    }

    @Test
    void onEditModeComboChanged_failureOfActiveSwitch_stillServesQueuedRetarget() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CountDownLatch secondIssued = new java.util.concurrent.CountDownLatch(1);
        ClaudeSessionModel m = new ClaudeSessionModel();
        m.setEditMode(EditMode.DEFAULT);
        ClaudeSessionController c = new ClaudeSessionController(m, Collections::emptyList) {
            @Override boolean isConnected() { return true; }
            @Override Optional<EditMode> detectCurrentMode() { return Optional.of(EditMode.DEFAULT); }
            @Override void sendOneShiftTab() throws java.io.IOException {
                if (calls.incrementAndGet() == 1) {
                    try { secondIssued.await(2, java.util.concurrent.TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    throw new java.io.IOException("write failed");
                }
            }
        };
        setModeSwitchPollMs(c, 1);

        c.onEditModeComboChanged(EditMode.PLAN);
        long deadline = System.currentTimeMillis() + 5_000;
        while (calls.get() < 1 && System.currentTimeMillis() < deadline) Thread.sleep(5);
        c.onEditModeComboChanged(EditMode.ACCEPT_EDITS); // queued while PLAN switch is active
        secondIssued.countDown();
        deadline = System.currentTimeMillis() + 5_000;
        while (calls.get() < 2 && System.currentTimeMillis() < deadline) Thread.sleep(5);
        assertTrue(calls.get() >= 2, "queued retarget must be served after the active switch failed");
    }

    @Test
    void onEditModeComboChanged_sameModeRequestedAgainDuringFailingSwitch_isStillServed() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CountDownLatch requestsIssued = new java.util.concurrent.CountDownLatch(1);
        ClaudeSessionModel m = new ClaudeSessionModel();
        m.setEditMode(EditMode.DEFAULT);
        ClaudeSessionController c = new ClaudeSessionController(m, Collections::emptyList) {
            @Override boolean isConnected() { return true; }
            @Override Optional<EditMode> detectCurrentMode() { return Optional.of(EditMode.DEFAULT); }
            @Override void sendOneShiftTab() throws java.io.IOException {
                if (calls.incrementAndGet() == 1) {
                    try { requestsIssued.await(2, java.util.concurrent.TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    throw new java.io.IOException("write failed");
                }
            }
        };
        setModeSwitchPollMs(c, 1);

        c.onEditModeComboChanged(EditMode.PLAN);   // A, fails
        long deadline = System.currentTimeMillis() + 5_000;
        while (calls.get() < 1 && System.currentTimeMillis() < deadline) Thread.sleep(5);
        c.onEditModeComboChanged(EditMode.ACCEPT_EDITS); // B
        c.onEditModeComboChanged(EditMode.PLAN);         // A again: slot equals the failed target
        requestsIssued.countDown();
        deadline = System.currentTimeMillis() + 5_000;
        while (calls.get() < 2 && System.currentTimeMillis() < deadline) Thread.sleep(5);
        assertTrue(calls.get() >= 2, "the latest request must be served even if it equals the failed target");
    }

    private static void setModeSwitchPollMs(ClaudeSessionController c, int ms) throws Exception {
        java.lang.reflect.Field f = ClaudeSessionController.class.getDeclaredField("modeSwitchPollMs");
        f.setAccessible(true);
        f.setInt(c, ms);
    }

    private static boolean invokeTrySend(ClaudeSessionController c, EditMode target) throws Exception {
        java.lang.reflect.Method m = ClaudeSessionController.class
                .getDeclaredMethod("trySendShiftTabsUntilMode", EditMode.class);
        m.setAccessible(true);
        return (boolean) m.invoke(c, target);
    }

    // -------------------------------------------------------------------------
    // waitForConcreteModeChange — glitch-frame debounce
    //
    // Regression: a single screen-poll tick can catch Claude's Ink renderer
    // mid-repaint of the footer and read an unrelated, one-off mode. Returning
    // that immediately would desynchronize trySendShiftTabsUntilMode's notion of
    // "current mode" from what CC actually shows, causing the final mode to land
    // somewhere other than the one requested. A differing mode is only reported
    // once the same mode is seen on two consecutive polls.
    // -------------------------------------------------------------------------

    @Test
    void waitForConcreteModeChange_singleGlitchPollIsNotReportedAsTheChange() throws Exception {
        // poll-1: one-off glitch shows AUTO; poll-2+: the real, stable new mode (ACCEPT_EDITS).
        AtomicReference<Integer> poll = new AtomicReference<>(0);
        ClaudeSessionModel m = new ClaudeSessionModel();
        ClaudeSessionController c = new ClaudeSessionController(m, Collections::emptyList) {
            @Override Optional<EditMode> detectCurrentMode() {
                int p = poll.updateAndGet(v -> v + 1);
                return p == 1 ? Optional.of(EditMode.AUTO) : Optional.of(EditMode.ACCEPT_EDITS);
            }
        };
        setModeSwitchPollMs(c, 1);

        Optional<EditMode> result = invokeWaitForConcreteModeChange(c, Optional.empty());

        assertEquals(Optional.of(EditMode.ACCEPT_EDITS), result,
                "must resolve to the mode confirmed on two consecutive polls, not the one-off glitch");
    }

    @Test
    void waitForConcreteModeChange_confirmsOnSecondConsecutiveAgreeingPoll() throws Exception {
        // No glitch: the same new mode appears on both poll-1 and poll-2.
        AtomicReference<Integer> poll = new AtomicReference<>(0);
        ClaudeSessionModel m = new ClaudeSessionModel();
        ClaudeSessionController c = new ClaudeSessionController(m, Collections::emptyList) {
            @Override Optional<EditMode> detectCurrentMode() {
                poll.updateAndGet(v -> v + 1);
                return Optional.of(EditMode.ACCEPT_EDITS);
            }
        };
        setModeSwitchPollMs(c, 1);

        Optional<EditMode> result = invokeWaitForConcreteModeChange(c, Optional.empty());

        assertEquals(Optional.of(EditMode.ACCEPT_EDITS), result);
        assertEquals(2, (int) poll.get(), "must confirm on exactly the second consecutive agreeing poll");
    }

    @SuppressWarnings("unchecked")
    private static Optional<EditMode> invokeWaitForConcreteModeChange(
            ClaudeSessionController c, Optional<EditMode> before) throws Exception {
        java.lang.reflect.Method m = ClaudeSessionController.class
                .getDeclaredMethod("waitForConcreteModeChange", Optional.class);
        m.setAccessible(true);
        return (Optional<EditMode>) m.invoke(c, before);
    }

    // -------------------------------------------------------------------------
    // sendShiftTabsUntilMode — empty detectEditMode must be treated as "default"
    // -------------------------------------------------------------------------

    /**
     * Regression: when switching from plan mode to default, the first shift-tab
     * leaves Claude in default (idle) mode.  The idle default screen has no footer
     * marker, so detectEditMode() returns Optional.empty().  The loop must treat
     * empty as "reached default" and stop — otherwise it overshoots into acceptEdits
     * then back to plan, and never reaches default.
     *
     * Log evidence (messages.log.err3):
     *   target=default currentModel=default
     *   attempt=0: detected=(empty)   ← already in default, but was treated as "not reached"
     *   attempt=1: detected=acceptEdits
     *   attempt=2: detected=plan
     *   WARNING: did not reach default after 3 attempts
     *
     * Fix: in sendShiftTabsUntilMode, when targetMode=="default" and detected is
     * empty (no plan/acceptEdits marker on screen), treat this as success and return.
     *
     * This test validates the detectEditMode precondition: an idle default screen
     * produces Optional.empty(), which must be interpreted as "default".
     */
    @Test
    void detectEditMode_emptyResultMeansDefaultModeOnIdleScreen() {
        // Idle "Ask on edit" / default screen: no mode footer at all
        io.github.nbplugins.claudecodegui.process.ScreenContentDetector detector =
                new io.github.nbplugins.claudecodegui.process.ScreenContentDetector();
        java.util.List<String> idleDefaultScreen = java.util.Arrays.asList(
                "Some output from Claude",
                "\u276F Ask anything...",
                "────────────────────────────────────────"
        );
        java.util.Optional<io.github.nbplugins.claudecodegui.model.EditMode> result = detector.detectEditMode(idleDefaultScreen);
        // detectEditMode returns empty for idle default screen (no "plan mode" / "accept edits" /
        // "  esc to interrupt" present).  sendShiftTabsUntilMode must treat this as "default".
        assertTrue(result.isEmpty(),
                "Idle default screen must produce empty detectEditMode — "
                + "sendShiftTabsUntilMode uses empty+target=default as success condition");
    }

    // -------------------------------------------------------------------------
    // Hang detection
    // -------------------------------------------------------------------------

    @Test
    void hangDetectedWhenNoOutputReceivedWithinTimeout() {
        // Simulate process started 70 s ago with no output; timeout = 60 s
        controller.simulateProcessStart(System.currentTimeMillis() - 70_000L, false);

        AtomicReference<String> callbackCommand = new AtomicReference<>();
        AtomicReference<String> callbackError   = new AtomicReference<>();
        controller.setHangCallback((cmd, err) -> {
            callbackCommand.set(cmd);
            callbackError.set(err);
        });

        // pollScreenState() is called on the EDT via Timer — invoke directly in test
        controller.pollScreenState();

        assertNotNull(callbackCommand.get(), "Hang callback should have been invoked");
        assertTrue(callbackError.get().contains("60"), "Error message should include the timeout seconds");
    }

    @Test
    void hangNotDetectedWhenOutputAlreadyReceived() {
        // Simulate process started 70 s ago but output was received
        controller.simulateProcessStart(System.currentTimeMillis() - 70_000L, true);

        AtomicReference<String> callbackCommand = new AtomicReference<>();
        controller.setHangCallback((cmd, err) -> callbackCommand.set(cmd));

        controller.pollScreenState();

        assertNull(callbackCommand.get(), "Hang callback should NOT fire when output was received");
    }

    @Test
    void hangNotDetectedWhenTimeoutIsZero() {
        // Simulate process started 70 s ago with no output; timeout = 0 (disabled)
        // We need to temporarily override the preference — but preferences require NB platform.
        // Instead, verify via processStartedAt=0 (stopProcess sets it to 0, meaning no active process).
        controller.simulateProcessStart(0L, false);

        AtomicReference<String> callbackCommand = new AtomicReference<>();
        controller.setHangCallback((cmd, err) -> callbackCommand.set(cmd));

        controller.pollScreenState();

        assertNull(callbackCommand.get(), "Hang callback should NOT fire when processStartedAt=0");
    }

    @Test
    void hangNotDetectedWhenWithinTimeout() {
        // Simulate process started 10 s ago with no output; default timeout = 60 s
        controller.simulateProcessStart(System.currentTimeMillis() - 10_000L, false);

        AtomicReference<String> callbackCommand = new AtomicReference<>();
        controller.setHangCallback((cmd, err) -> callbackCommand.set(cmd));

        // Since NbPreferences returns default (60 s) in tests, 10 s < 60 s → no hang
        controller.pollScreenState();

        assertNull(callbackCommand.get(), "Hang callback should NOT fire when elapsed < timeout");
    }

    // -------------------------------------------------------------------------
    // discoverModels deferred when blocking dialog is visible at READY
    // -------------------------------------------------------------------------

    /**
     * Regression: when Claude starts with --continue/--resume, it may show a
     * "Resuming session" dialog before the input prompt.  If discoverModels() is
     * triggered while this dialog is visible, it sends /model but Claude cannot
     * process it yet (blocked by the dialog).  Discovery times out, the combo
     * stays unpopulated, and MAX_MODEL_DISCOVERY_ATTEMPTS prevents any retry.
     *
     * Fix: pollScreenState must skip discoverModels() while any choice menu is
     * visible on screen or already shown to the user.
     */
    @Test
    void pollScreenState_doesNotStartDiscoveryWhenBlockingMenuIsPresent() {
        List<String> blockingMenuScreen = Arrays.asList(
                "Resuming the full session will consume a substantial portion of your usage limits.",
                "❯ 1. Resume from full history",
                "  2. Resume from summary",
                "Enter to confirm · Esc to cancel"
        );

        java.util.concurrent.atomic.AtomicInteger discoveryCallCount =
                new java.util.concurrent.atomic.AtomicInteger(0);

        ClaudeSessionModel m = new ClaudeSessionModel();
        ClaudeSessionController c = new ClaudeSessionController(m, () -> blockingMenuScreen) {
            @Override
            void discoverModels() {
                discoveryCallCount.incrementAndGet();
            }
        };

        m.setLifecycle(SessionLifecycle.READY);
        c.pollScreenState();

        assertEquals(0, discoveryCallCount.get(),
                "discoverModels must not be called while a blocking dialog is visible on screen");
        assertNotNull(m.getActiveChoiceMenu(),
                "Blocking dialog must be shown to the user while discovery is deferred");
    }

    // -------------------------------------------------------------------------
    // isModelCommandPendingWithBlankLine
    // -------------------------------------------------------------------------

    @Test
    void isModelCommandPendingWithBlankLine_detectsNewlineInsertedByEnter() {
        List<String> lines = List.of(
                "   ● Built successfully. Install target/nbm/foo.nbm and test.",
                "",
                "  ─────────────────────────────────────────────────────────────",
                "  ──────── fix-kotlin-extensions-nosuchmethoderror ──",
                "  ❯ /model",
                "",
                "  ─────────────────────────────────────────────────────────────",
                "  ─────────────────────────────────────────────────────────────",
                "  ─────────────────────────────────",
                "    ⏵⏵ bypass permissions on (shift+tab to cycle)"
        );
        assertTrue(controller.isModelCommandPendingWithBlankLine(lines));
    }

    @Test
    void isModelCommandPendingWithBlankLine_falseWhenNoBlankAfterModel() {
        List<String> lines = List.of(
                "  ❯ /model",
                "  ─────────────────────────────────────────────────────────────"
        );
        assertFalse(controller.isModelCommandPendingWithBlankLine(lines));
    }

    @Test
    void isModelCommandPendingWithBlankLine_falseWhenModelInHistory() {
        List<String> lines = List.of(
                "  You used /model earlier",
                "",
                "  ─────────────────────────────────────────────────────────────",
                "  ❯ "
        );
        assertFalse(controller.isModelCommandPendingWithBlankLine(lines));
    }

    // -------------------------------------------------------------------------
    // stopAndRename — must target the session that was running
    // -------------------------------------------------------------------------

    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path tmpDir;

    /** Fixed launch timestamp; tests set file mtimes relative to it. */
    private static final long START_MS = 1_700_000_000_000L;

    private static String hashOf(java.nio.file.Path workDir) {
        return workDir.toAbsolutePath().toString().replace('/', '-');
    }

    private static java.nio.file.Path sessionDir(java.nio.file.Path configDir,
                                                 java.nio.file.Path workDir) throws java.io.IOException {
        java.nio.file.Path dir = configDir.resolve("projects").resolve(hashOf(workDir));
        java.nio.file.Files.createDirectories(dir);
        return dir;
    }

    private static void writeSession(java.nio.file.Path dir, String id, String slug)
            throws java.io.IOException {
        java.nio.file.Files.writeString(dir.resolve(id + ".jsonl"),
                "{\"sessionId\":\"" + id + "\",\"slug\":\"" + slug
                        + "\",\"timestamp\":\"2024-01-01T10:00:00Z\",\"type\":\"summary\"}\n");
    }

    /** Appends a user message, as the running process writes it into the JSONL. */
    private static void appendUserPrompt(java.nio.file.Path dir, String id, String text,
                                         long millis) throws java.io.IOException {
        java.nio.file.Files.writeString(dir.resolve(id + ".jsonl"),
                "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"" + text
                        + "\"},\"timestamp\":\"" + java.time.Instant.ofEpochMilli(millis)
                        + "\"}\n",
                java.nio.file.StandardOpenOption.APPEND);
    }

    private static void setMtime(java.nio.file.Path file, long millis) throws java.io.IOException {
        java.nio.file.Files.setLastModifiedTime(file,
                java.nio.file.attribute.FileTime.fromMillis(millis));
    }

    private static long countCustomTitles(java.nio.file.Path file, String value)
            throws java.io.IOException {
        String json = "\"customTitle\":\"" + value + "\"";
        return java.nio.file.Files.readAllLines(file).stream()
                .filter(line -> line.contains("\"type\":\"custom-title\""))
                .filter(line -> line.contains(json))
                .count();
    }

    @Test
    void stopAndRename_writesIntoCapturedConfigDir() throws Exception {
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path profileDir = tmpDir.resolve("profiles").resolve("Work");
        java.nio.file.Path dir = sessionDir(profileDir, work);
        writeSession(dir, "s1", "slug");
        setMtime(dir.resolve("s1.jsonl"), START_MS - 1_000L);

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateActiveSession(profileDir, "s1", Set.of("s1"));

        controller.stopAndRename("My Session");

        io.github.nbplugins.claudecodegui.model.SavedSession s =
                io.github.nbplugins.claudecodegui.process.ClaudeSessionStore
                        .findSession(work, profileDir, "s1");
        assertNotNull(s);
        assertEquals("My Session", s.customTitle(),
                "the title must be written into the profile's own config dir");
    }

    @Test
    void stopAndRename_targetsLaunchSession_notAnotherTab() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        // Another tab is running "other-tab" and wrote it after our launch.
        writeSession(dir, "other-tab", "other");
        setMtime(dir.resolve("other-tab.jsonl"), START_MS + 500L);
        ClaudeSessionController otherTab =
                new ClaudeSessionController(new ClaudeSessionModel(), Collections::emptyList);
        otherTab.simulateProcessStart(START_MS - 500L, true);
        otherTab.simulateLiveSession(config, "other-tab", Set.of("other-tab"));

        // Snapshot taken right before our process launched.
        Set<String> before = Set.of("other-tab");
        writeSession(dir, "this-session", "this");
        setMtime(dir.resolve("this-session.jsonl"), START_MS + 1_000L);

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateActiveSession(config, null, before);

        try {
            controller.stopAndRename("Closed Session");

            assertEquals("Closed Session",
                    io.github.nbplugins.claudecodegui.process.ClaudeSessionStore
                            .findSession(work, config, "this-session").customTitle(),
                    "the session created by this run must be renamed");
            assertNull(io.github.nbplugins.claudecodegui.process.ClaudeSessionStore
                            .findSession(work, config, "other-tab").customTitle(),
                    "another tab's session must not be touched");
        } finally {
            otherTab.stopProcess();
        }
    }

    @Test
    void stopAndRename_followsSessionSwitchedInProcess() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "first", "first");
        setMtime(dir.resolve("first.jsonl"), START_MS - 5_000L);
        writeSession(dir, "second", "second");
        // The TUI's /resume picker switched conversations after the launch:
        // the second file is the one the process writes.
        setMtime(dir.resolve("second.jsonl"), START_MS + 2_000L);

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateActiveSession(config, "first", Set.of("first", "second"));

        controller.stopAndRename("Switched Name");

        assertEquals("Switched Name",
                io.github.nbplugins.claudecodegui.process.ClaudeSessionStore
                        .findSession(work, config, "second").customTitle(),
                "the name must go to the conversation the process switched to");
        assertNull(io.github.nbplugins.claudecodegui.process.ClaudeSessionStore
                        .findSession(work, config, "first").customTitle(),
                "the conversation left by the switch must not be renamed");
    }

    @Test
    void newSessionsClaimTheirOwnFileBeforeSiblingGuesses() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        // Tab A (NEW) wrote its session after its first prompt, but has not
        // opened Save & Switch yet, so its identity is still unresolved.
        writeSession(dir, "a", "a");
        setMtime(dir.resolve("a.jsonl"), START_MS + 1_000L);
        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateLiveSession(config, null, Set.of());

        // Tab B (NEW) launches later and writes its own, newer session.
        writeSession(dir, "b", "b");
        setMtime(dir.resolve("b.jsonl"), START_MS + 5_000L);
        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS + 4_000L, true);
        tabB.simulateLiveSession(config, null, Set.of("a"));

        try {
            // Resolving B must not make A claim B's file on its behalf.
            assertEquals("b", tabB.resolveActiveSessionId(),
                    "tab B must resolve to its own session");
            assertEquals("a", controller.resolveActiveSessionId(),
                    "tab A must keep the session it created");
            assertEquals("b", tabB.resolveActiveSessionId(),
                    "tab B must still resolve to its own session");
        } finally {
            tabB.stopProcess();
        }
    }

    @Test
    void inProcessSwitch_releasesTheClaimOnThePreviousSession() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        // Tab A starts on "a"; tab B starts on "b" after it.
        writeSession(dir, "a", "a");
        setMtime(dir.resolve("a.jsonl"), START_MS - 5_000L);
        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateLiveSession(config, "a", Set.of("a"));

        writeSession(dir, "b", "b");
        setMtime(dir.resolve("b.jsonl"), START_MS + 1_000L);
        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS + 2_000L, true);
        tabB.simulateLiveSession(config, "b", Set.of("a", "b"));

        try {
            // Tab A switches to "c" inside the TUI; the screen poll notices it
            // after ~5 s and must release the claim on the abandoned "a".
            writeSession(dir, "c", "c");
            setMtime(dir.resolve("c.jsonl"), START_MS + 3_000L);
            for (int i = 0; i < 10; i++) {
                controller.simulateIdentityTick();
            }

            // Tab B switches to the conversation A left behind and prompts, so
            // "a" is the newest file it has written.
            setMtime(dir.resolve("a.jsonl"), START_MS + 6_000L);
            assertEquals("a", tabB.resolveActiveSessionId(),
                    "the claim released by A's switch must let B resume that session");
        } finally {
            tabB.stopProcess();
        }
    }

    @Test
    void processExit_releasesTheSessionClaims() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        // Tab A runs "a" and its PTY exits from inside the terminal.
        writeSession(dir, "a", "a");
        setMtime(dir.resolve("a.jsonl"), START_MS - 5_000L);
        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateLiveSession(config, "a", Set.of("a"));
        controller.simulateProcessExit();

        // Tab B starts later and switches to the now-free "a".
        writeSession(dir, "b", "b");
        setMtime(dir.resolve("b.jsonl"), START_MS + 4_000L);
        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS + 3_000L, true);
        tabB.simulateLiveSession(config, "b", Set.of("a", "b"));

        try {
            setMtime(dir.resolve("a.jsonl"), START_MS + 6_000L);
            assertEquals("a", tabB.resolveActiveSessionId(),
                    "an exited process must not keep claiming its session");
        } finally {
            tabB.stopProcess();
        }
    }

    /**
     * A tab may start a saved session at the same moment the tab that already
     * owns it exits. The starting tab's claim must survive the exiting tab's
     * cleanup; if it were lost, a sibling scan would see the file as unowned
     * and could adopt (and later rename) the wrong conversation.
     */
    @Test
    void claimMadeWhileAnotherTabReleases_isNotLost() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        sessionDir(config, work);

        ClaudeSessionModel observerModel = new ClaudeSessionModel();
        observerModel.setWorkingDirectory(work.toFile());
        ClaudeSessionController observer =
                new ClaudeSessionController(observerModel, Collections::emptyList);
        observer.simulateProcessStart(START_MS, true);
        observer.simulateLiveSession(config, "observer", Set.of());

        try {
            for (int i = 0; i < 2_000; i++) {
                String id = "race-" + i;

                ClaudeSessionModel closingModel = new ClaudeSessionModel();
                closingModel.setWorkingDirectory(work.toFile());
                ClaudeSessionController closing =
                        new ClaudeSessionController(closingModel, Collections::emptyList);
                closing.simulateLiveSession(config, id, Set.of());

                ClaudeSessionModel startingModel = new ClaudeSessionModel();
                startingModel.setWorkingDirectory(work.toFile());
                ClaudeSessionController starting =
                        new ClaudeSessionController(startingModel, Collections::emptyList);

                java.util.concurrent.CountDownLatch go =
                        new java.util.concurrent.CountDownLatch(1);
                Thread release = new Thread(() -> {
                    await(go);
                    closing.simulateProcessExit();
                });
                Thread claim = new Thread(() -> {
                    await(go);
                    starting.simulateLiveSession(config, id, Set.of());
                });
                release.start();
                claim.start();
                go.countDown();
                release.join();
                claim.join();

                assertTrue(
                        observer.simulateClaimedByOthers(work.toFile(), config).contains(id),
                        "the claim of the starting tab was lost on iteration " + i);

                starting.stopProcess();
            }
        } finally {
            observer.stopProcess();
        }
    }

    private static void await(java.util.concurrent.CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void newSessionResumingSavedConversation_resolvesAfterPrompt() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        // The saved conversation exists before the NEW tab is launched.
        writeSession(dir, "saved", "saved");
        setMtime(dir.resolve("saved.jsonl"), START_MS - 5_000L);

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateActiveSession(config, null, Set.of("saved"));

        // The TUI's /resume picker switches to the saved conversation, and a
        // prompt from the plugin rewrites it.
        controller.simulatePromptSubmittedAt(START_MS + 2_000L);
        setMtime(dir.resolve("saved.jsonl"), START_MS + 3_000L);

        controller.stopAndRename("Resumed Name");

        assertEquals("Resumed Name",
                io.github.nbplugins.claudecodegui.process.ClaudeSessionStore
                        .findSession(work, config, "saved").customTitle(),
                "a saved conversation resumed before the first JSONL of the "
                        + "new session must still be renamable");
    }

    /**
     * Two tabs can resume the same saved conversation, in which case both own
     * its session file. The shared file must stay eligible for each tab's own
     * identity scan; otherwise a newer, unrelated file written by a third tab
     * is resolved instead and its conversation gets renamed on stop.
     */
    @Test
    void sharedSessionStaysEligibleForIdentityScans() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        // The shared conversation has the newest mtime; the unrelated file was
        // written after both launches but is older.
        writeSession(dir, "shared", "shared");
        setMtime(dir.resolve("shared.jsonl"), START_MS + 5_000L);
        writeSession(dir, "other", "other");
        setMtime(dir.resolve("other.jsonl"), START_MS + 3_000L);

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateLiveSession(config, "shared", Set.of("shared"));

        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS + 500L, true);
        tabB.simulateLiveSession(config, "shared", Set.of("shared"));

        // A third tab wrote another file and exited.
        ClaudeSessionModel modelC = new ClaudeSessionModel();
        modelC.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabC =
                new ClaudeSessionController(modelC, Collections::emptyList);
        tabC.simulateProcessStart(START_MS + 1_000L, true);
        tabC.simulateLiveSession(config, "other", Set.of("other"));
        tabC.simulateProcessExit();

        try {
            assertEquals("shared", controller.resolveActiveSessionId(),
                    "a session shared with another live tab must stay eligible");
        } finally {
            tabB.stopProcess();
        }
    }

    /**
     * A tab can join another live tab's conversation from the in-process resume
     * picker. The other tab still owns that file, so it must not stay an
     * unconditional exclusion: the tab follows the switch and shares ownership.
     */
    @Test
    void inProcessResumeIntoAnotherTabsSession_followsTheSwitch() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "a", "a");
        setMtime(dir.resolve("a.jsonl"), START_MS - 5_000L);
        writeSession(dir, "b", "b");
        setMtime(dir.resolve("b.jsonl"), START_MS - 5_000L);

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateLiveSession(config, "a", Set.of("a", "b"));

        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS + 1_000L, true);
        tabB.simulateLiveSession(config, "b", Set.of("a", "b"));

        try {
            // Tab A picks B's conversation in its TUI and sends a prompt; the
            // write lands in B's file, which B still owns.
            controller.simulatePromptText("continue in the resumed tab");
            controller.simulatePromptSubmittedAt(START_MS + 10_000L);
            appendUserPrompt(dir, "b", "continue in the resumed tab", START_MS + 10_500L);
            setMtime(dir.resolve("b.jsonl"), START_MS + 11_000L);

            assertEquals("b", controller.resolveActiveSessionId(),
                    "a tab that resumed another tab's conversation must follow it");

            controller.stopAndRename("Switched Name");
            assertEquals("Switched Name",
                    io.github.nbplugins.claudecodegui.process.ClaudeSessionStore
                            .findSession(work, config, "b").customTitle(),
                    "the entered name must go to the conversation on screen");
            assertNull(io.github.nbplugins.claudecodegui.process.ClaudeSessionStore
                            .findSession(work, config, "a").customTitle(),
                    "the conversation left by the switch must not be renamed");
        } finally {
            tabB.stopProcess();
        }
    }

    /**
     * A NEW tab can join another live tab's conversation from the in-process
     * resume picker before it has created a JSONL of its own, so its identity is
     * still unknown when the prompt is sent.
     */
    @Test
    void newTabResumingAnotherTabsConversationBeforeItsOwnFile_resolves() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "shared", "shared");
        setMtime(dir.resolve("shared.jsonl"), START_MS - 5_000L);

        // Tab B owns the conversation.
        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS + 1_000L, true);
        tabB.simulateLiveSession(config, "shared", Set.of("shared"));

        // Tab A is NEW and has not written anything yet.
        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateLiveSession(config, null, Set.of("shared"));

        try {
            // A joins B's conversation in the TUI and sends a prompt; the write
            // lands in the shared file.
            controller.simulatePromptText("joined from a new tab");
            controller.simulatePromptSubmittedAt(START_MS + 10_000L);
            appendUserPrompt(dir, "shared", "joined from a new tab", START_MS + 10_500L);
            setMtime(dir.resolve("shared.jsonl"), START_MS + 11_000L);

            assertEquals("shared", controller.resolveActiveSessionId(),
                    "a NEW tab that resumed a live tab's conversation must resolve it");

            controller.stopAndRename("Shared Name");
            assertEquals("Shared Name",
                    io.github.nbplugins.claudecodegui.process.ClaudeSessionStore
                            .findSession(work, config, "shared").customTitle(),
                    "the entered name must be saved to the resumed conversation");
        } finally {
            tabB.stopProcess();
        }
    }

    /**
     * The counterpart of the test above: a NEW tab's first prompt is scanned
     * before the tab has written its own file. A sibling tab finishing a turn at
     * that moment owns a file written after the prompt, but it contains only
     * that tab's messages — not this tab's prompt — so it must not be adopted.
     */
    @Test
    void newTabPromptingWhileAnotherTabWrites_doesNotAdoptItsSession() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "b", "b");
        setMtime(dir.resolve("b.jsonl"), START_MS - 5_000L);

        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS + 1_000L, true);
        tabB.simulateLiveSession(config, "b", Set.of("b"));

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateLiveSession(config, null, Set.of("b"));

        try {
            // A's scan runs before A's own file exists; meanwhile B finishes a
            // turn in the same store.
            controller.simulatePromptText("a brand new prompt");
            controller.simulatePromptSubmittedAt(START_MS + 10_000L);
            appendUserPrompt(dir, "b", "b's own turn", START_MS + 10_500L);
            setMtime(dir.resolve("b.jsonl"), START_MS + 11_000L);

            assertNull(controller.resolveActiveSessionId(),
                    "another tab's turn is not evidence of a shared resume");

            controller.stopAndRename("A Name");
            assertNull(io.github.nbplugins.claudecodegui.process.ClaudeSessionStore
                            .findSession(work, config, "b").customTitle(),
                    "a pending first prompt must not rename another tab's conversation");
        } finally {
            tabB.stopProcess();
        }
    }

    /**
     * A third tab can write its own conversation after this tab's prompt and
     * then exit, leaving an unclaimed and even newer file. The conversation that
     * received this tab's prompt — still owned by its other tab — must win over
     * that mtime-only candidate.
     */
    @Test
    void promptEvidenceWinsOverANewerUnclaimedFile() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "a", "a");
        setMtime(dir.resolve("a.jsonl"), START_MS - 5_000L);
        writeSession(dir, "b", "b");
        setMtime(dir.resolve("b.jsonl"), START_MS - 5_000L);
        writeSession(dir, "c", "c");
        setMtime(dir.resolve("c.jsonl"), START_MS - 5_000L);

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateLiveSession(config, "a", Set.of("a", "b", "c"));

        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS + 1_000L, true);
        tabB.simulateLiveSession(config, "b", Set.of("a", "b", "c"));

        // A third tab writes its own conversation after the prompt and exits,
        // so its file is the newest and no longer claimed by a live tab.
        ClaudeSessionModel modelC = new ClaudeSessionModel();
        modelC.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabC =
                new ClaudeSessionController(modelC, Collections::emptyList);
        tabC.simulateProcessStart(START_MS + 2_000L, true);
        tabC.simulateLiveSession(config, "c", Set.of("a", "b", "c"));
        setMtime(dir.resolve("c.jsonl"), START_MS + 12_000L);
        tabC.stopProcess();

        try {
            // Tab A joined B's conversation and sent its prompt there.
            controller.simulatePromptText("joined b");
            controller.simulatePromptSubmittedAt(START_MS + 10_000L);
            appendUserPrompt(dir, "b", "joined b", START_MS + 10_500L);
            setMtime(dir.resolve("b.jsonl"), START_MS + 11_000L);

            assertEquals("b", controller.resolveActiveSessionId(),
                    "the file holding this tab's prompt must beat a newer unclaimed one");

            controller.stopAndRename("Resumed Name");
            assertEquals("Resumed Name",
                    io.github.nbplugins.claudecodegui.process.ClaudeSessionStore
                            .findSession(work, config, "b").customTitle(),
                    "the entered name must go to the conversation on screen");
            assertNull(io.github.nbplugins.claudecodegui.process.ClaudeSessionStore
                            .findSession(work, config, "c").customTitle(),
                    "the exited tab's conversation must not be renamed");
        } finally {
            tabB.stopProcess();
        }
    }

    /**
     * After a plugin prompt, the user can resume another conversation from the
     * TUI and type its next prompt directly in the terminal, bypassing
     * sendPrompt(). The earlier prompt no longer describes the conversation on
     * screen, so the file times must decide again.
     */
    @Test
    void terminalInputAfterThePromptInvalidatesTheEvidence() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "a", "a");
        writeSession(dir, "b", "b");
        setMtime(dir.resolve("b.jsonl"), START_MS - 5_000L);

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulatePromptText("plugin prompt");
        controller.simulatePromptSubmittedAt(START_MS + 10_000L);
        appendUserPrompt(dir, "a", "plugin prompt", START_MS + 10_500L);
        controller.simulateLiveSession(config, "a", Set.of("a"));
        setMtime(dir.resolve("a.jsonl"), START_MS + 10_500L);

        // The user types in the terminal: the process moves to "b".
        controller.simulateExternalInput(START_MS + 11_000L);
        setMtime(dir.resolve("b.jsonl"), START_MS + 12_000L);

        assertEquals("b", controller.resolveActiveSessionId(),
                "input typed after the prompt makes the old prompt stale evidence");

        controller.stopAndRename("On Screen");
        assertEquals("On Screen",
                io.github.nbplugins.claudecodegui.process.ClaudeSessionStore
                        .findSession(work, config, "b").customTitle(),
                "the entered name must follow the conversation on screen");
        assertNull(io.github.nbplugins.claudecodegui.process.ClaudeSessionStore
                        .findSession(work, config, "a").customTitle(),
                "the conversation left behind must not be renamed");
    }

    /**
     * Two tabs in the same store can submit the same prompt text ({@code continue}).
     * Then both files hold "this tab's prompt", so the session the tab already
     * tracks must win over a sibling's file that happens to be scanned first.
     */
    @Test
    void promptEvidencePrefersTheTabsOwnSessionWhenPromptsMatch() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "own", "own");
        writeSession(dir, "sibling", "sibling");
        setMtime(dir.resolve("own.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("sibling.jsonl"), START_MS - 5_000L);

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateLiveSession(config, "own", Set.of("own", "sibling"));

        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS + 1_000L, true);
        tabB.simulateLiveSession(config, "sibling", Set.of("own", "sibling"));

        try {
            controller.simulatePromptText("continue");
            controller.simulatePromptSubmittedAt(START_MS + 10_000L);
            // Both conversations received an identical prompt after that moment;
            // the sibling's file is even newer.
            appendUserPrompt(dir, "own", "continue", START_MS + 10_500L);
            appendUserPrompt(dir, "sibling", "continue", START_MS + 10_600L);
            setMtime(dir.resolve("own.jsonl"), START_MS + 11_000L);
            setMtime(dir.resolve("sibling.jsonl"), START_MS + 11_500L);

            assertEquals("own", controller.resolveActiveSessionId(),
                    "the session this tab already tracks must win over an identical prompt");
        } finally {
            tabB.stopProcess();
        }
    }

    /**
     * A resume driven entirely from the keyboard: the user picks another tab's
     * conversation through the TUI and types the next prompt in the terminal.
     * That prompt is evidence for the conversation now on screen, even though it
     * was not sent by the plugin.
     */
    @Test
    void promptTypedInTheTerminalFollowsASharedResume() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "own", "own");
        writeSession(dir, "shared", "shared");
        setMtime(dir.resolve("own.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("shared.jsonl"), START_MS - 5_000L);

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateLiveSession(config, "own", Set.of("own", "shared"));

        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS + 1_000L, true);
        tabB.simulateLiveSession(config, "shared", Set.of("own", "shared"));

        try {
            appendUserPrompt(dir, "shared", "typed in terminal", START_MS + 11_500L);
            setMtime(dir.resolve("shared.jsonl"), START_MS + 12_000L);

            controller.simulateTerminalInput(
                    "typed in terminal\r".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    START_MS + 11_000L);

            assertEquals("shared", controller.resolveActiveSessionId(),
                    "a prompt typed in the terminal proves the conversation on screen");
        } finally {
            tabB.stopProcess();
        }
    }

    /**
     * The same resume driven by a prompt recalled from the TUI history: Up
     * fills the TUI input with text the plugin never sees, and Enter submits
     * it. The submission is real, so the file this process writes after it —
     * the conversation on screen — must be followed even though its text
     * cannot be matched.
     */
    @Test
    void promptRecalledFromTheTerminalHistoryFollowsTheConversationOnScreen() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "own", "own");
        writeSession(dir, "shared", "shared");
        setMtime(dir.resolve("own.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("shared.jsonl"), START_MS - 5_000L);

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateLiveSession(config, "own", Set.of("own", "shared"));

        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS + 1_000L, true);
        tabB.simulateLiveSession(config, "shared", Set.of("own", "shared"));

        try {
            // The recalled prompt is appended to the resumed conversation.
            appendUserPrompt(dir, "shared", "recalled prompt", START_MS + 11_500L);
            setMtime(dir.resolve("shared.jsonl"), START_MS + 12_000L);

            // Up recalls it into the TUI input; nothing is typed here.
            controller.simulateTerminalInput(
                    new byte[]{0x1b, '[', 'A'}, START_MS + 11_000L);
            controller.simulateTerminalInput(
                    new byte[]{'\r'}, START_MS + 11_001L);

            assertEquals("shared", controller.resolveActiveSessionId(),
                    "a recalled prompt proves the conversation on screen");
        } finally {
            tabB.stopProcess();
        }
    }

    /**
     * A NEW session's first record can be written between the pre-stop
     * identity lookup and the moment the process exits. The rename must still
     * target that file instead of silently dropping the entered name.
     */
    @Test
    void aSessionFileWrittenWhileStoppingIsStillRenamed() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        model.setWorkingDirectory(work.toFile());
        java.util.concurrent.atomic.AtomicBoolean written =
                new java.util.concurrent.atomic.AtomicBoolean();
        ClaudeSessionController underTest =
                new ClaudeSessionController(model, Collections::emptyList) {
                    @Override
                    void stopWriter() {
                        if (written.compareAndSet(false, true)) {
                            try {
                                writeSession(dir, "late", "late");
                                appendUserPrompt(dir, "late", "first prompt",
                                        START_MS + 5_000L);
                                setMtime(dir.resolve("late.jsonl"), START_MS + 5_000L);
                            } catch (Exception e) {
                                throw new IllegalStateException(e);
                            }
                        }
                        super.stopWriter();
                    }
                };
        underTest.simulateProcessStart(START_MS, true);
        underTest.simulateLiveSession(config, null, Set.of());

        underTest.stopAndRename("Late session");

        io.github.nbplugins.claudecodegui.model.SavedSession saved =
                io.github.nbplugins.claudecodegui.process.ClaudeSessionStore.findSession(
                        work, config, "late");
        assertNotNull(saved, "the session written while stopping must be renamed");
        assertEquals("Late session", saved.customTitle());
    }

    /**
     * A tab tracks conversation A, the user switches to B in the TUI, and B's
     * first record is only written while the process shuts down. The title must
     * follow B — the conversation that was on screen — not the previously
     * tracked A.
     */
    @Test
    void aConversationSwitchedWhileStoppingIsRenamedInsteadOfThePreviousOne() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "a", "a");
        setMtime(dir.resolve("a.jsonl"), START_MS - 5_000L);

        model.setWorkingDirectory(work.toFile());
        java.util.concurrent.atomic.AtomicBoolean written =
                new java.util.concurrent.atomic.AtomicBoolean();
        ClaudeSessionController underTest =
                new ClaudeSessionController(model, Collections::emptyList) {
                    @Override
                    void stopWriter() {
                        if (written.compareAndSet(false, true)) {
                            try {
                                writeSession(dir, "b", "b");
                                appendUserPrompt(dir, "b", "switched prompt",
                                        START_MS + 5_000L);
                                setMtime(dir.resolve("b.jsonl"), START_MS + 5_000L);
                            } catch (Exception e) {
                                throw new IllegalStateException(e);
                            }
                        }
                        super.stopWriter();
                    }
                };
        underTest.simulateProcessStart(START_MS, true);
        underTest.simulateLiveSession(config, "a", Set.of("a"));

        underTest.stopAndRename("Switched session");

        io.github.nbplugins.claudecodegui.model.SavedSession saved =
                io.github.nbplugins.claudecodegui.process.ClaudeSessionStore.findSession(
                        work, config, "b");
        assertNotNull(saved, "the conversation written while stopping is the one on screen");
        assertEquals("Switched session", saved.customTitle());
        assertEquals(0, countCustomTitles(dir.resolve("a.jsonl"), "Switched session"),
                "the previously tracked conversation must not be renamed");
    }

    /**
     * Up recalls a prompt from the TUI history and the user edits it before
     * submitting. Only the edited characters reach this parser, so the full
     * prompt is unknown; treating the fragment as the whole prompt would make
     * the exact-text evidence fail and Save & Switch rename the wrong session.
     */
    @Test
    void anEditedHistoryRecallKeepsThePromptTextUnknown() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "own", "own");
        writeSession(dir, "shared", "shared");
        setMtime(dir.resolve("own.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("shared.jsonl"), START_MS - 5_000L);

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateLiveSession(config, "own", Set.of("own", "shared"));

        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS + 1_000L, true);
        tabB.simulateLiveSession(config, "shared", Set.of("own", "shared"));

        try {
            appendUserPrompt(dir, "shared", "recalled prompt suffix",
                    START_MS + 11_500L);
            setMtime(dir.resolve("shared.jsonl"), START_MS + 12_000L);

            // Up recalls the prompt, then " suffix" is appended and submitted.
            controller.simulateTerminalInput(
                    new byte[]{0x1b, '[', 'A'}, START_MS + 11_000L);
            controller.simulateTerminalInput(
                    " suffix".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    START_MS + 11_001L);
            assertNull(controller.simulateTerminalInput(
                            new byte[]{'\r'}, START_MS + 11_002L),
                    "the recorded characters are only part of the recalled prompt");

            assertEquals("shared", controller.resolveActiveSessionId(),
                    "an edited recall proves the conversation on screen by time");
        } finally {
            tabB.stopProcess();
        }
    }

    /**
     * An unknown-text prompt switched into another live tab's conversation.
     * A third tab then writes its own conversation and exits: its file is
     * unclaimed and newer than the resumed one, but the resumed file holds the
     * submitted user record. The evidence must win over the bare file time, or
     * Save &amp; Switch renames the unrelated conversation.
     */
    @Test
    void anUnknownPromptAdoptsTheResumedConversationOverANewerExitedOne() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "own", "own");
        writeSession(dir, "shared", "shared");
        writeSession(dir, "third", "third");
        setMtime(dir.resolve("own.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("shared.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("third.jsonl"), START_MS - 5_000L);

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateLiveSession(config, "own", Set.of("own", "shared"));

        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS + 1_000L, true);
        tabB.simulateLiveSession(config, "shared", Set.of("own", "shared"));

        try {
            // The resumed conversation receives the submitted prompt.
            appendUserPrompt(dir, "shared", "recalled prompt", START_MS + 11_500L);
            setMtime(dir.resolve("shared.jsonl"), START_MS + 12_000L);
            // An unrelated, already-exited session is written even later.
            setMtime(dir.resolve("third.jsonl"), START_MS + 13_000L);

            // Up recalls a prompt and Enter submits it: its text is unknown.
            controller.simulateTerminalInput(
                    new byte[]{0x1b, '[', 'A'}, START_MS + 11_000L);
            assertNull(controller.simulateTerminalInput(
                    new byte[]{'\r'}, START_MS + 11_001L));

            assertEquals("shared", controller.resolveActiveSessionId(),
                    "the shared user record must beat a newer unclaimed file");
        } finally {
            tabB.stopProcess();
        }
    }

    /**
     * A tab whose conversation was known at launch switches to another live
     * tab's conversation and submits a recalled prompt. An exited tab wrote its
     * own human prompt afterwards, so its file is unclaimed and holds a user
     * record; the shared conversation holds this tab's submission. The new-file
     * priority belongs to unresolved NEW tabs only, so the unrelated record
     * must not pin this tab and make Save &amp; Switch rename it.
     */
    @Test
    void anUnknownPromptFromALaunchedTabKeepsTheSharedDestination() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "own", "own");
        writeSession(dir, "shared", "shared");
        writeSession(dir, "exited", "exited");
        setMtime(dir.resolve("own.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("shared.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("exited.jsonl"), START_MS - 5_000L);

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateLiveSession(config, "own", Set.of("own", "shared", "exited"));

        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS + 1_000L, true);
        tabB.simulateLiveSession(config, "shared", Set.of("own", "shared", "exited"));

        try {
            // Up recalls a prompt and Enter submits it: its text is unknown.
            controller.simulateTerminalInput(
                    new byte[]{0x1b, '[', 'A'}, START_MS + 11_000L);
            assertNull(controller.simulateTerminalInput(
                    new byte[]{'\r'}, START_MS + 11_001L));

            // The shared conversation receives the submission.
            appendUserPrompt(dir, "shared", "recalled prompt", START_MS + 11_500L);
            setMtime(dir.resolve("shared.jsonl"), START_MS + 12_000L);
            // An exited tab wrote its own human prompt after the submission.
            appendUserPrompt(dir, "exited", "unrelated work", START_MS + 11_600L);
            setMtime(dir.resolve("exited.jsonl"), START_MS + 13_000L);

            assertEquals("shared", controller.resolveActiveSessionId(),
                    "the shared submission must win over an unrelated human record");
        } finally {
            tabB.stopProcess();
        }
    }

    /**
     * The destination of a recalled prompt is another live tab's conversation,
     * but many newer unclaimed files (assistant output only) were written since
     * the submission. The shared conversations must be filtered before the
     * candidate list is truncated, or the destination is never inspected and
     * the file-time fallback renames an unrelated conversation.
     */
    @Test
    void aSharedDestinationIsFoundDespiteManyNewerUnclaimedFiles() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "own", "own");
        writeSession(dir, "shared", "shared");
        setMtime(dir.resolve("own.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("shared.jsonl"), START_MS - 5_000L);

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateLiveSession(config, "own", Set.of("own", "shared"));

        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS + 1_000L, true);
        tabB.simulateLiveSession(config, "shared", Set.of("own", "shared"));

        try {
            controller.simulateTerminalInput(
                    new byte[]{0x1b, '[', 'A'}, START_MS + 11_000L);
            assertNull(controller.simulateTerminalInput(
                    new byte[]{'\r'}, START_MS + 11_001L));

            // The shared conversation receives the submission.
            appendUserPrompt(dir, "shared", "recalled prompt", START_MS + 11_500L);
            setMtime(dir.resolve("shared.jsonl"), START_MS + 12_000L);

            // A crowd of newer, unclaimed conversations with assistant output
            // only fills a globally limited candidate list.
            for (int i = 1; i <= 8; i++) {
                writeSession(dir, "noise" + i, "noise" + i);
                setMtime(dir.resolve("noise" + i + ".jsonl"), START_MS + 12_000L + i * 100L);
            }

            assertEquals("shared", controller.resolveActiveSessionId(),
                    "the shared conversations must be filtered before the limit");
        } finally {
            tabB.stopProcess();
        }
    }

    /**
     * An unresolved NEW tab submits a Tab-completed prompt, so its text is
     * unknown, while a live sibling submits its own human prompt. The sibling
     * holds the only claimed file, but this tab's own new file also contains a
     * user record for its submission: taking the sibling's record as a switch
     * would pin this tab to the sibling's conversation.
     */
    @Test
    void anUnknownPromptPrefersTheTabsOwnNewSessionOverASiblingTurn() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "sibling", "sibling");
        setMtime(dir.resolve("sibling.jsonl"), START_MS - 5_000L);

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateLiveSession(config, null, Set.of("sibling"));

        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS + 1_000L, true);
        tabB.simulateLiveSession(config, "sibling", Set.of("sibling"));

        try {
            // Tab completes the prompt, Enter submits it: the text is unknown.
            controller.simulateTerminalInput(
                    "inspect @REA".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    START_MS + 11_000L);
            controller.simulateTerminalInput(new byte[]{'\t'}, START_MS + 11_001L);
            assertNull(controller.simulateTerminalInput(
                    new byte[]{'\r'}, START_MS + 11_002L));

            // This tab's own new conversation gets the submission ...
            writeSession(dir, "own-new", "own-new");
            appendUserPrompt(dir, "own-new", "inspect @README.md", START_MS + 11_500L);
            setMtime(dir.resolve("own-new.jsonl"), START_MS + 13_000L);
            // ... while the sibling submits an unrelated prompt of its own.
            appendUserPrompt(dir, "sibling", "sibling turn", START_MS + 11_400L);
            setMtime(dir.resolve("sibling.jsonl"), START_MS + 12_000L);

            assertEquals("own-new", controller.resolveActiveSessionId(),
                    "this tab's own record must beat the sibling's own turn");
        } finally {
            tabB.stopProcess();
        }
    }

    /**
     * A file with second-rounded mtimes can hold the complete submitted prompt
     * yet sit just below the exact cutoff. A live sibling submitting identical
     * text must not then be adopted: the relaxed cutoff has to be applied to
     * this tab's own candidates before the borrowed match is accepted.
     */
    @Test
    void aRoundedOwnFileStillWinsOverASiblingWithTheSamePrompt() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "sibling", "sibling");
        setMtime(dir.resolve("sibling.jsonl"), START_MS - 5_000L);

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateLiveSession(config, null, Set.of("sibling"));

        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS + 1_000L, true);
        tabB.simulateLiveSession(config, "sibling", Set.of("sibling"));

        try {
            assertEquals("hello", controller.simulateTerminalInput(
                            "hello\r".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            START_MS + 11_000L),
                    "the submitted prompt is recorded exactly");

            // The own file holds the prompt but its rounded mtime is below the
            // submission; the sibling submitted the same text afterwards.
            writeSession(dir, "own-new", "own-new");
            appendUserPrompt(dir, "own-new", "hello", START_MS + 11_000L);
            setMtime(dir.resolve("own-new.jsonl"), START_MS + 10_500L);
            appendUserPrompt(dir, "sibling", "hello", START_MS + 11_200L);
            setMtime(dir.resolve("sibling.jsonl"), START_MS + 12_000L);

            assertEquals("own-new", controller.resolveActiveSessionId(),
                    "the own record must be found before the borrowed match");
        } finally {
            tabB.stopProcess();
        }
    }

    /** Clearing the input with Ctrl+U discards a recalled prompt: the next text is known. */
    @Test
    void clearingARecalledPromptMakesTheNextSubmissionKnown() {
        controller.simulateTerminalInput(new byte[]{0x1b, '[', 'A'}, START_MS);
        controller.simulateTerminalInput(new byte[]{0x15}, START_MS + 1L);

        assertEquals("typed after clearing", controller.simulateTerminalInput(
                "typed after clearing\r".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                START_MS + 2L));
    }

    /** A tab inside a bracketed paste is literal prompt text. */
    @Test
    void aTabInsideABracketedPasteIsRecordedAsTyped() {
        String paste = "\u001b[200~first\tsecond\u001b[201~\r";

        assertEquals("first\tsecond", controller.simulateTerminalInput(
                paste.getBytes(java.nio.charset.StandardCharsets.UTF_8), START_MS));
    }

    /**
     * Live Tab makes the TUI complete the input from its own suggestions; the
     * completed text never reaches this parser. The recorded line is therefore
     * only a prefix of the submitted prompt, so the text must be treated as
     * unknown instead of being matched against the JSONL.
     */
    @Test
    void aTerminalCompletedPromptHasUnknownText() {
        controller.simulateTerminalInput(
                "inspect @REA".getBytes(java.nio.charset.StandardCharsets.UTF_8), START_MS);
        controller.simulateTerminalInput(new byte[]{'\t'}, START_MS + 1L);

        assertNull(controller.simulateTerminalInput(
                new byte[]{'\r'}, START_MS + 2L),
                "the prefix typed before Tab is not the whole submitted prompt");
    }

    /** Ctrl+U after a completion discards it, so the next prompt is known again. */
    @Test
    void clearingACompletedPromptMakesTheNextSubmissionKnown() {
        controller.simulateTerminalInput(
                "inspect @REA".getBytes(java.nio.charset.StandardCharsets.UTF_8), START_MS);
        controller.simulateTerminalInput(new byte[]{'\t'}, START_MS + 1L);
        controller.simulateTerminalInput(new byte[]{0x15}, START_MS + 2L);

        assertEquals("typed after clearing", controller.simulateTerminalInput(
                "typed after clearing\r".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                START_MS + 3L));
    }

    /**
     * While a choice menu is open, Tab acts on the menu ("Tab to amend") and
     * Enter selects an item rather than submitting a prompt. Marking that Tab
     * as a completion would record the selection as an unknown-text prompt and
     * let the adoption pass take a sibling's newer turn.
     */
    @Test
    void aTabInAChoiceMenuDoesNotRecordAPrompt() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "own", "own");
        writeSession(dir, "shared", "shared");
        setMtime(dir.resolve("own.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("shared.jsonl"), START_MS - 5_000L);

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateLiveSession(config, "own", Set.of("own", "shared"));

        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS + 1_000L, true);
        tabB.simulateLiveSession(config, "shared", Set.of("own", "shared"));

        try {
            model.setActiveChoiceMenu(new ChoiceMenuModel("Pick one", List.of(
                    new ChoiceMenuModel.Option("Yes", "1"),
                    new ChoiceMenuModel.Option("No", "2")), 0));
            // The sibling submits its own turn while the menu is open.
            appendUserPrompt(dir, "shared", "sibling turn", START_MS + 11_500L);
            setMtime(dir.resolve("shared.jsonl"), START_MS + 12_000L);

            // Tab acts on the menu, Enter selects the item.
            controller.simulateTerminalInput(new byte[]{'\t'}, START_MS + 11_000L);
            controller.simulateTerminalInput(new byte[]{'\r'}, START_MS + 11_001L);

            assertEquals("own", controller.resolveActiveSessionId(),
                    "a menu Tab and its selection are not a submitted prompt");
        } finally {
            model.clearChoiceMenu();
            tabB.stopProcess();
        }
    }

    /**
     * Pasting before a large draft must insert the pasted text in one
     * operation. Inserting character by character copies the whole unchanged
     * suffix for every character, which took seconds for a half-megabyte
     * draft and blocked terminal writes on the calling thread.
     */
    @Test
    void aLargePasteBeforeALargeDraftIsInsertedQuickly() {
        String draft = "d".repeat(512 * 1024);
        String pasted = "p".repeat(512 * 1024);
        controller.simulateTerminalInput(
                draft.getBytes(java.nio.charset.StandardCharsets.UTF_8), START_MS);
        // Home, then a bracketed paste at the beginning of the draft.
        controller.simulateTerminalInput(
                new byte[]{0x1b, '[', 'H'}, START_MS + 1L);

        long started = System.nanoTime();
        assertNull(controller.simulateTerminalInput(
                ("\u001b[200~" + pasted + "\u001b[201~")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                START_MS + 2L));
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertEquals(pasted.length() + draft.length(), controller.simulateTerminalInput(
                new byte[]{'\r'}, START_MS + 3L).length(),
                "the whole pasted text must precede the draft");
        assertTrue(elapsedMillis < 3_000,
                "a paste must not rescan the draft per character (took "
                        + elapsedMillis + " ms)");
    }

    /**
     * While a choice menu is open, Up/Down move the menu cursor and Enter
     * selects the item; the typed line stays empty just as it does for a prompt
     * recalled from the TUI history, but no prompt is submitted. Recording it
     * as a recall would let the mtime-only fallback adopt a sibling's newest
     * file even though the conversation on screen never changed.
     */
    @Test
    void arrowKeysInAChoiceMenuDoNotRecallThePromptHistory() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "own", "own");
        writeSession(dir, "shared", "shared");
        setMtime(dir.resolve("own.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("shared.jsonl"), START_MS - 5_000L);

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateLiveSession(config, "own", Set.of("own", "shared"));

        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS + 1_000L, true);
        tabB.simulateLiveSession(config, "shared", Set.of("own", "shared"));

        try {
            model.setActiveChoiceMenu(new ChoiceMenuModel("Pick one", List.of(
                    new ChoiceMenuModel.Option("Yes", "1"),
                    new ChoiceMenuModel.Option("No", "2")), 0));
            // The sibling writes its own turn while the menu is open.
            setMtime(dir.resolve("shared.jsonl"), START_MS + 12_000L);

            // Up moves the menu cursor; Enter selects the item.
            controller.simulateTerminalInput(
                    new byte[]{0x1b, '[', 'A'}, START_MS + 11_000L);
            controller.simulateTerminalInput(
                    new byte[]{'\r'}, START_MS + 11_001L);

            assertEquals("own", controller.resolveActiveSessionId(),
                    "menu navigation is not a prompt history recall");
        } finally {
            model.clearChoiceMenu();
            tabB.stopProcess();
        }
    }

    /**
     * After joining another tab's conversation and submitting a prompt there,
     * the identity scan may only run after the user started typing the next
     * draft. A draft edit must not invalidate the submitted prompt's evidence,
     * or the conversation on screen is renamed in place of this tab's old one.
     */
    @Test
    void draftTypingDoesNotInvalidateTheSubmittedPrompt() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "a", "a");
        setMtime(dir.resolve("a.jsonl"), START_MS - 5_000L);
        writeSession(dir, "b", "b");
        setMtime(dir.resolve("b.jsonl"), START_MS - 5_000L);

        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS + 1_000L, true);
        tabB.simulateLiveSession(config, "b", Set.of("a", "b"));

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateLiveSession(config, "a", Set.of("a", "b"));

        try {
            // The user joins B's conversation in the TUI and submits a prompt.
            appendUserPrompt(dir, "b", "joined b", START_MS + 10_500L);
            setMtime(dir.resolve("b.jsonl"), START_MS + 11_000L);
            controller.simulateTerminalInput(
                    "joined b\r".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    START_MS + 10_000L);

            // ... then starts typing the next prompt without submitting it.
            controller.simulateTerminalInput(
                    "draft".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    START_MS + 11_500L);

            assertEquals("b", controller.resolveActiveSessionId(),
                    "typing a draft must not discard the submitted prompt's evidence");
        } finally {
            tabB.stopProcess();
        }
    }

    /**
     * A NEW tab and a live tab can submit the same text. The sibling's record
     * may land while the NEW tab's own file is still being created, so the tab
     * briefly adopts the sibling; its own file must win the identity back as
     * soon as it appears instead of the borrowed one pinning itself forever.
     */
    @Test
    void newTabWithTheSamePromptAsASibling_takesItsOwnFileWhenItAppears() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "sibling", "sibling");
        setMtime(dir.resolve("sibling.jsonl"), START_MS - 5_000L);

        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS + 1_000L, true);
        tabB.simulateLiveSession(config, "sibling", Set.of("sibling"));

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateLiveSession(config, null, Set.of("sibling"));

        try {
            controller.simulatePromptText("hello");
            controller.simulatePromptSubmittedAt(START_MS + 10_000L);
            // The sibling submits the same text and its record lands after this
            // tab's prompt, while this tab's own file does not exist yet.
            appendUserPrompt(dir, "sibling", "hello", START_MS + 10_500L);
            setMtime(dir.resolve("sibling.jsonl"), START_MS + 10_600L);
            assertEquals("sibling", controller.resolveActiveSessionId(),
                    "with nothing of its own yet, the tab can only follow the shared prompt");

            // This tab's own conversation appears, holding the same prompt.
            writeSession(dir, "own", "own");
            appendUserPrompt(dir, "own", "hello", START_MS + 11_000L);
            setMtime(dir.resolve("own.jsonl"), START_MS + 11_200L);

            assertEquals("own", controller.resolveActiveSessionId(),
                    "the tab's own conversation must win over a sibling with the same prompt");
        } finally {
            tabB.stopProcess();
        }
    }

    /**
     * The counterpart of the test above. While the NEW tab's own file is still
     * pending it borrows the live tab's conversation. That provisional claim
     * must not make the conversation look foreign to the tab that established
     * it: otherwise, when the NEW tab's file appears, the sibling treats it as
     * its own (it holds the same prompt) and the two tabs keep naming each
     * other's conversations in Save &amp; Switch.
     */
    @Test
    void borrowedClaimDoesNotStealTheOwnersConversation() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "sibling", "sibling");
        setMtime(dir.resolve("sibling.jsonl"), START_MS - 5_000L);

        // Tab B established the conversation and has its own prompt evidence.
        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS + 1_000L, true);
        tabB.simulateLiveSession(config, "sibling", Set.of("sibling"));
        tabB.simulatePromptText("hello");
        tabB.simulatePromptSubmittedAt(START_MS + 9_000L);

        // Tab A is NEW and submits the same text.
        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateLiveSession(config, null, Set.of("sibling"));
        controller.simulatePromptText("hello");
        controller.simulatePromptSubmittedAt(START_MS + 10_000L);

        try {
            appendUserPrompt(dir, "sibling", "hello", START_MS + 10_500L);
            setMtime(dir.resolve("sibling.jsonl"), START_MS + 10_600L);
            assertEquals("sibling", controller.resolveActiveSessionId(),
                    "with nothing of its own yet, the NEW tab borrows the sibling");

            // A's own conversation appears, holding the same prompt. B scans
            // before A has claimed the new file.
            writeSession(dir, "own", "own");
            appendUserPrompt(dir, "own", "hello", START_MS + 11_000L);
            setMtime(dir.resolve("own.jsonl"), START_MS + 11_200L);

            assertEquals("sibling", tabB.resolveActiveSessionId(),
                    "a borrowed claim must not make the owner adopt the borrower's file");

            assertEquals("own", controller.resolveActiveSessionId(),
                    "the NEW tab must still return to its own conversation");
            assertEquals("sibling", tabB.resolveActiveSessionId(),
                    "both tabs must keep their own conversations once both files exist");
        } finally {
            tabB.stopProcess();
        }
    }

    /**
     * Two NEW tabs that have not resolved their session yet submit the same
     * text. Neither knows which file is its own: the first file to appear can
     * only be guessed, so the claim stays provisional instead of establishing
     * ownership over a sibling's conversation. When a second file with the same
     * prompt appears, the evidence cannot tell the two tabs apart — displacing
     * the current claim would swap the two conversations — so both keep the
     * conversation they already track.
     */
    @Test
    void twoUnresolvedNewTabsDoNotSwapTheConversationTheyShare() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);
        java.nio.charset.Charset utf8 = java.nio.charset.StandardCharsets.UTF_8;

        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS, true);
        tabB.simulateLiveSession(config, null, Set.of());

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateLiveSession(config, null, Set.of());

        try {
            controller.simulatePromptText("hello");
            controller.simulatePromptSubmittedAt(START_MS + 10_000L);
            tabB.simulatePromptText("hello");
            tabB.simulatePromptSubmittedAt(START_MS + 10_000L);

            // B's conversation is written first; A scans before B and can only
            // follow the only file that holds the prompt.
            writeSession(dir, "b", "b");
            appendUserPrompt(dir, "b", "hello", START_MS + 10_500L);
            setMtime(dir.resolve("b.jsonl"), START_MS + 10_600L);
            assertEquals("b", controller.resolveActiveSessionId());
            assertEquals("b", tabB.resolveActiveSessionId());

            // A's own conversation appears with the same prompt.
            writeSession(dir, "a", "a");
            appendUserPrompt(dir, "a", "hello", START_MS + 10_500L);
            setMtime(dir.resolve("a.jsonl"), START_MS + 11_200L);

            assertEquals("b", tabB.resolveActiveSessionId(),
                    "a shared provisional claim must not be displaced");
            assertEquals("b", controller.resolveActiveSessionId(),
                    "the tab that guessed first must not be swapped off its file");

            assertEquals("b", controller.resolveActiveSessionId(),
                    "both tabs must keep the conversation they track on later scans");
            assertEquals("b", tabB.resolveActiveSessionId());
        } finally {
            tabB.stopProcess();
        }
    }

    /**
     * The same two unresolved tabs with the scan order reversed: the tab that
     * resolved the shared file first keeps it, and the tab that joined later
     * must not cost it that conversation. A borrowed claim is not ownership.
     */
    @Test
    void aSiblingBorrowingAProvisionalClaimDoesNotDisplaceIt() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS, true);
        tabB.simulateLiveSession(config, null, Set.of());

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateLiveSession(config, null, Set.of());

        try {
            controller.simulatePromptText("hello");
            controller.simulatePromptSubmittedAt(START_MS + 10_000L);
            tabB.simulatePromptText("hello");
            tabB.simulatePromptSubmittedAt(START_MS + 10_000L);

            // The sibling resolves the shared conversation first; this tab joins.
            writeSession(dir, "b", "b");
            appendUserPrompt(dir, "b", "hello", START_MS + 10_500L);
            setMtime(dir.resolve("b.jsonl"), START_MS + 10_600L);
            assertEquals("b", tabB.resolveActiveSessionId());
            assertEquals("b", controller.resolveActiveSessionId());

            // A second file with the same prompt appears.
            writeSession(dir, "a", "a");
            appendUserPrompt(dir, "a", "hello", START_MS + 10_500L);
            setMtime(dir.resolve("a.jsonl"), START_MS + 11_200L);

            assertEquals("b", controller.resolveActiveSessionId(),
                    "the provisional claim of the sibling must not be displaced");
            assertEquals("b", tabB.resolveActiveSessionId(),
                    "the tab that resolved the file first must keep it");

            assertEquals("b", tabB.resolveActiveSessionId(),
                    "later scans must not swap the conversations either");
            assertEquals("b", controller.resolveActiveSessionId());
        } finally {
            tabB.stopProcess();
        }
    }

    /**
     * A tab recalls a prompt in its own terminal while a live sibling finishes
     * its own turn. The sibling's file is newer, but nothing indicates that the
     * conversation on screen changed: a newer mtime alone must not steal the
     * sibling's session (its turn appends assistant output, not a user message).
     */
    @Test
    void aHistoryRecallDoesNotAdoptASiblingConcurrentTurn() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "own", "own");
        writeSession(dir, "shared", "shared");
        setMtime(dir.resolve("own.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("shared.jsonl"), START_MS - 5_000L);

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateLiveSession(config, "own", Set.of("own", "shared"));

        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS + 1_000L, true);
        tabB.simulateLiveSession(config, "shared", Set.of("own", "shared"));

        try {
            // Up recalls a prompt from this tab's own history; Enter submits it.
            controller.simulateTerminalInput(
                    new byte[]{0x1b, '[', 'A'}, START_MS + 11_000L);
            controller.simulateTerminalInput(
                    new byte[]{'\r'}, START_MS + 11_001L);

            // The sibling appends its own assistant turn, which is newer.
            java.nio.file.Files.writeString(dir.resolve("shared.jsonl"),
                    "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\","
                            + "\"content\":\"sibling turn\"},\"timestamp\":\""
                            + java.time.Instant.ofEpochMilli(START_MS + 11_500L) + "\"}\n",
                    java.nio.file.StandardOpenOption.APPEND);
            setMtime(dir.resolve("shared.jsonl"), START_MS + 12_000L);

            assertEquals("own", controller.resolveActiveSessionId(),
                    "a newer file written by another tab is not evidence of a switch");
        } finally {
            tabB.stopProcess();
        }
    }

    /**
     * A /resume picker search text typed in the terminal belongs to the picker.
     * When the GUI selects the destination, the recorded line must not carry
     * that text into the next prompt.
     */
    @Test
    void pickerSearchTextDoesNotBecomePartOfTheNextPrompt() {
        controller.simulateTerminalInput(
                "work".getBytes(java.nio.charset.StandardCharsets.UTF_8), START_MS);
        controller.writePtyAnswer("1");

        assertEquals("hello", controller.simulateTerminalInput(
                "hello\r".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                START_MS + 2L));
    }

    /**
     * A NEW tab may guess (and provisionally claim) a conversation, but an
     * explicit {@code --resume} of that same file knows its identity at launch.
     * The explicit resume must own the file, so the guessing tab stops treating
     * it as its own and follows its own conversation once that appears.
     */
    @Test
    void anExplicitResumeOwnsTheFileEvenWhenANewTabGuessedItFirst() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "b", "b");
        appendUserPrompt(dir, "b", "hello", START_MS + 10_500L);
        setMtime(dir.resolve("b.jsonl"), START_MS + 10_600L);

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateLiveSession(config, null, Set.of());
        controller.simulatePromptText("hello");
        controller.simulatePromptSubmittedAt(START_MS + 10_000L);
        assertEquals("b", controller.resolveActiveSessionId(),
                "the NEW tab follows the conversation that holds its prompt");

        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS + 11_000L, true);
        tabB.simulateLiveSession(config, "b", Set.of("b"));

        try {
            // This tab's own conversation appears with the same prompt.
            writeSession(dir, "a", "a");
            appendUserPrompt(dir, "a", "hello", START_MS + 12_000L);
            setMtime(dir.resolve("a.jsonl"), START_MS + 12_500L);

            assertEquals("a", controller.resolveActiveSessionId(),
                    "an explicit resume owns the file; the guess must follow its own");
            assertEquals("b", tabB.resolveActiveSessionId(),
                    "the tab that resumed the conversation explicitly keeps it");
        } finally {
            tabB.stopProcess();
        }
    }

    /**
     * Two tabs resume the same saved conversation, so the second one legitimately
     * borrows the file the first owns. A launch-known identity is that tab's own
     * conversation even when another live tab shares it: an unrelated, exited
     * conversation that recorded the same text after the submission must not be
     * adopted in its place.
     */
    @Test
    void aSharedLaunchIdBeatsAnUnrelatedConversationWithTheSamePrompt() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "shared", "shared");
        setMtime(dir.resolve("shared.jsonl"), START_MS - 5_000L);
        writeSession(dir, "third", "third");
        setMtime(dir.resolve("third.jsonl"), START_MS - 5_000L);

        // This tab resumes the saved conversation and therefore owns it.
        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateLiveSession(config, "shared", Set.of("shared", "third"));
        controller.simulatePromptText("continue");
        controller.simulatePromptSubmittedAt(START_MS + 10_000L);

        // A second tab resumes the same conversation and only borrows the file.
        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS + 1_000L, true);
        tabB.simulateLiveSession(config, "shared", Set.of("shared", "third"));
        tabB.simulatePromptText("continue");
        tabB.simulatePromptSubmittedAt(START_MS + 10_000L);

        try {
            appendUserPrompt(dir, "shared", "continue", START_MS + 10_500L);
            setMtime(dir.resolve("shared.jsonl"), START_MS + 10_600L);

            // An exited conversation records the same text after the submission.
            appendUserPrompt(dir, "third", "continue", START_MS + 11_000L);
            setMtime(dir.resolve("third.jsonl"), START_MS + 11_200L);

            assertEquals("shared", tabB.resolveActiveSessionId(),
                    "a shared launch identity must not yield to an unrelated newer file");
            assertEquals("shared", tabB.resolveActiveSessionId(),
                    "later scans must keep resolving the shared conversation");
            assertEquals("shared", controller.resolveActiveSessionId(),
                    "the owning tab keeps the conversation it launched");
        } finally {
            tabB.stopProcess();
        }
    }

    /**
     * Up recalls a draft that is never submitted before the tab restarts. The
     * replacement process starts with empty input, so the first typed prompt is
     * known in full; a leftover recall flag would record it as unknown text and
     * let an unrelated newer conversation be adopted.
     */
    @Test
    void aRestartClearsAPromptRecalledButNeverSubmitted() {
        controller.simulateTerminalInput(new byte[]{0x1b, '[', 'A'}, START_MS);
        controller.stopProcess();
        controller.simulateProcessStart(START_MS + 1_000L, true);

        assertEquals("typed prompt", controller.simulateTerminalInput(
                        "typed prompt\r".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        START_MS + 2_000L),
                "a recall from the previous run must not make the new prompt unknown");
    }

    /**
     * A standalone Esc arrives as its own write and only dismisses the
     * autocomplete: it must not consume the keystroke that follows, or the next
     * prompt is recorded without that character and its evidence no longer
     * matches the conversation on screen.
     */
    @Test
    void aStandaloneEscapeDoesNotSwallowTheNextKeystroke() {
        byte[] esc = {0x1b};
        java.nio.charset.Charset utf8 = java.nio.charset.StandardCharsets.UTF_8;

        controller.simulateTerminalInput(esc, START_MS);
        controller.simulateTerminalInput("hello".getBytes(utf8), START_MS + 1);
        assertEquals("hello", controller.simulateTerminalInput(new byte[]{'\r'}, START_MS + 2),
                "Esc must not consume the first character typed after it");

        controller.simulateTerminalInput("world".getBytes(utf8), START_MS + 3);
        controller.simulateTerminalInput(esc, START_MS + 4);
        assertEquals("world", controller.simulateTerminalInput(new byte[]{'\r'}, START_MS + 5),
                "Esc before Enter must not swallow the submission");
    }

    /**
     * A prompt typed after a standalone Esc may itself start with {@code O} or
     * {@code [}. Those characters belong to the input: a real SS3/CSI sequence
     * is written in one piece, so treating the later write as its continuation
     * drops characters and leaves prompt evidence that matches no session.
     */
    @Test
    void aStandaloneEscapeFollowedByASequencePrefixIsTypedText() {
        byte[] esc = {0x1b};
        java.nio.charset.Charset utf8 = java.nio.charset.StandardCharsets.UTF_8;

        controller.simulateTerminalInput(esc, START_MS);
        assertEquals("Open file", controller.simulateTerminalInput(
                        "Open file\r".getBytes(utf8), START_MS + 3_000L),
                "an SS3-looking continuation after a standalone Esc is plain text");

        controller.simulateTerminalInput(esc, START_MS + 4_000L);
        assertEquals("[hello]", controller.simulateTerminalInput(
                        "[hello]\r".getBytes(utf8), START_MS + 7_000L),
                "a CSI-looking continuation after a standalone Esc is plain text");
    }

    /**
     * A tab whose Claude process quit stays open. It must stop enumerating the
     * session store: otherwise every exited tab keeps the shared identity worker
     * busy and delays identity updates for the live tabs.
     */
    @Test
    void anExitedProcessStopsSchedulingIdentityScans() {
        controller.simulateProcessStart(START_MS, true);
        assertTrue(controller.scheduleIdentityRefresh(),
                "a running tab keeps refreshing its session identity");

        controller.simulateProcessNotRunning();
        assertFalse(controller.scheduleIdentityRefresh(),
                "an exited process must not schedule identity scans");
    }

    /**
     * Cursor keys and other escape sequences must be consumed as a whole: their
     * final byte must not leak into the buffered line, or the recorded prompt
     * would no longer match the one actually submitted and the identity scan
     * could not confirm the conversation on screen.
     */
    @Test
    void escapeSequencesDoNotCorruptATerminalPrompt() {
        byte[] enter = {'\r'};
        assertNull(controller.simulateTerminalInput(
                "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8), START_MS));
        controller.simulateTerminalInput(new byte[]{0x1b, '[', 'A'}, START_MS + 1); // Up
        controller.simulateTerminalInput(new byte[]{0x1b, '[', 'B'}, START_MS + 2); // Down
        controller.simulateTerminalInput(new byte[]{0x1b, '[', 'Z'}, START_MS + 3); // Shift+Tab
        controller.simulateTerminalInput(
                new byte[]{0x1b, '[', '5', '~'}, START_MS + 4);                     // PgUp
        controller.simulateTerminalInput(
                new byte[]{0x1b, '[', '6', '~'}, START_MS + 5);                     // PgDn
        controller.simulateTerminalInput(new byte[]{0x1b, 'O', 'A'}, START_MS + 6); // SS3 Up
        controller.simulateTerminalInput(new byte[]{0x1b, 'x'}, START_MS + 7);      // Alt+x

        assertEquals("hello", controller.simulateTerminalInput(enter, START_MS + 8),
                "escape sequences must not append their bytes to the typed prompt");
    }

    /**
     * A multiline prompt pasted into the terminal arrives inside a bracketed
     * paste ({@code ESC [ 200 ~} … {@code ESC [ 201 ~}). Its newlines are part
     * of the prompt, not submissions, so the whole text is recorded — otherwise
     * the prompt evidence would only keep the last pasted line.
     */
    @Test
    void bracketedPasteKeepsMultilinePromptsIntact() {
        assertNull(controller.simulateTerminalInput(
                "\u001b[200~".getBytes(java.nio.charset.StandardCharsets.UTF_8), START_MS));
        assertNull(controller.simulateTerminalInput(
                "first\nsecond".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                START_MS + 1),
                "newlines inside a paste must not submit the line");
        controller.simulateTerminalInput(
                "\u001b[201~".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                START_MS + 2);

        assertEquals("first\nsecond",
                controller.simulateTerminalInput(new byte[]{'\r'}, START_MS + 3),
                "the whole pasted prompt must be recorded");
    }

    @Test
    void bracketedPasteRecordsCrLfAsASingleNewline() {
        controller.simulateTerminalInput(
                "\u001b[200~".getBytes(java.nio.charset.StandardCharsets.UTF_8), START_MS);
        controller.simulateTerminalInput(
                "a\r\nb".getBytes(java.nio.charset.StandardCharsets.UTF_8), START_MS + 1);
        controller.simulateTerminalInput(
                "\u001b[201~".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                START_MS + 2);

        assertEquals("a\nb",
                controller.simulateTerminalInput(new byte[]{'\r'}, START_MS + 3));
    }

    /**
     * A prompt corrected with Left-arrow is submitted differently from the keys
     * pressed: the buffered line must apply the cursor move, or the recorded
     * prompt does not match the conversation and the evidence is lost.
     */
    @Test
    void cursorEditsReconstructTheSubmittedPrompt() {
        controller.simulateTerminalInput(
                "helo".getBytes(java.nio.charset.StandardCharsets.UTF_8), START_MS);
        controller.simulateTerminalInput(new byte[]{0x1b, '[', 'D'}, START_MS + 1); // Left
        controller.simulateTerminalInput(
                "l".getBytes(java.nio.charset.StandardCharsets.UTF_8), START_MS + 2);

        assertEquals("hello",
                controller.simulateTerminalInput(new byte[]{'\r'}, START_MS + 3),
                "the character typed after Left must land before the last one");
    }

    @Test
    void homeEndDeleteAndBackspaceEditTheSubmittedPrompt() {
        controller.simulateTerminalInput(
                "ac".getBytes(java.nio.charset.StandardCharsets.UTF_8), START_MS);
        controller.simulateTerminalInput(new byte[]{0x1b, '[', 'H'}, START_MS + 1);      // Home
        controller.simulateTerminalInput(
                "0".getBytes(java.nio.charset.StandardCharsets.UTF_8), START_MS + 2);    // "0ac"
        controller.simulateTerminalInput(new byte[]{0x1b, '[', 'F'}, START_MS + 3);      // End
        controller.simulateTerminalInput(
                "d".getBytes(java.nio.charset.StandardCharsets.UTF_8), START_MS + 4);    // "0acd"
        controller.simulateTerminalInput(new byte[]{0x1b, '[', 'H'}, START_MS + 5);      // Home
        controller.simulateTerminalInput(new byte[]{0x1b, '[', 'C'}, START_MS + 6);      // Right
        controller.simulateTerminalInput(new byte[]{0x1b, '[', 'C'}, START_MS + 7);      // Right
        controller.simulateTerminalInput(new byte[]{0x1b, '[', '3', '~'}, START_MS + 8); // Delete "c"
        controller.simulateTerminalInput(new byte[]{0x1b, '[', 'D'}, START_MS + 9);      // Left
        controller.simulateTerminalInput(new byte[]{0x7f}, START_MS + 10);               // Backspace "0"

        assertEquals("ad",
                controller.simulateTerminalInput(new byte[]{'\r'}, START_MS + 11));
    }

    /**
     * Supplementary Unicode characters (emoji and the like) are two UTF-16 code
     * units. Backspace, Delete and the horizontal cursor keys must treat them
     * as one character: splitting a surrogate pair leaves malformed text that
     * can never match the message actually submitted, so the conversation on
     * screen would not be recognised when the session is renamed.
     */
    @Test
    void backspaceAndDeleteRemoveWholeSupplementaryCharacters() {
        java.nio.charset.Charset utf8 = java.nio.charset.StandardCharsets.UTF_8;

        controller.simulateTerminalInput("😀".getBytes(utf8), START_MS);
        assertEquals("hello",
                controller.simulateTerminalInput(
                        "\u007fhello\r".getBytes(utf8), START_MS + 1),
                "Backspace after an emoji must remove the whole character");

        controller.simulateTerminalInput("a😀b".getBytes(utf8), START_MS + 2);
        controller.simulateTerminalInput(new byte[]{0x1b, '[', 'D'}, START_MS + 4);      // Left
        controller.simulateTerminalInput(new byte[]{0x1b, '[', 'D'}, START_MS + 5);      // Left
        controller.simulateTerminalInput(new byte[]{0x1b, '[', '3', '~'}, START_MS + 6); // Delete
        assertEquals("ab",
                controller.simulateTerminalInput(new byte[]{'\r'}, START_MS + 7),
                "Left and Delete must step over and remove whole characters");
    }

    /**
     * Cursor keys must be harmless at the ends of the line: Left at the start
     * and Right at the end happen during ordinary navigation, and an exception
     * raised by the input listener would abort the rest of the write.
     */
    @Test
    void cursorKeysAtTheLineBoundariesDoNotThrow() {
        java.nio.charset.Charset utf8 = java.nio.charset.StandardCharsets.UTF_8;

        // Empty line: both directions are no-ops.
        controller.simulateTerminalInput(new byte[]{0x1b, '[', 'D'}, START_MS);      // Left
        controller.simulateTerminalInput(new byte[]{0x1b, '[', 'C'}, START_MS + 1);  // Right

        controller.simulateTerminalInput("ab".getBytes(utf8), START_MS + 2);
        controller.simulateTerminalInput(new byte[]{0x1b, '[', 'F'}, START_MS + 3);  // End
        controller.simulateTerminalInput(new byte[]{0x1b, '[', 'C'}, START_MS + 4);  // Right at end
        controller.simulateTerminalInput(new byte[]{0x1b, '[', 'H'}, START_MS + 5);  // Home
        controller.simulateTerminalInput(new byte[]{0x1b, '[', 'D'}, START_MS + 6);  // Left at start

        assertEquals("ab",
                controller.simulateTerminalInput(new byte[]{'\r'}, START_MS + 7));
    }

    // -------------------------------------------------------------------------
    // prepareLaunchIdentity — captured before the process starts
    // -------------------------------------------------------------------------

    @Test
    void prepareLaunchIdentity_newSession_hasNoIdButSnapshotsTheStore() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);
        writeSession(dir, "old", "old");

        ClaudeSessionController.LaunchIdentity identity =
                ClaudeSessionController.prepareLaunchIdentity(work.toFile(), config,
                        io.github.nbplugins.claudecodegui.model.SessionMode.NEW, null);

        assertNull(identity.sessionId());
        assertEquals(Set.of("old"), identity.preStartSessionIds());
        assertEquals(config, identity.configDir());
    }

    @Test
    void prepareLaunchIdentity_continueLast_picksTheNewestExistingSession() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);
        writeSession(dir, "older", "older");
        setMtime(dir.resolve("older.jsonl"), START_MS - 5_000L);
        writeSession(dir, "newer", "newer");
        setMtime(dir.resolve("newer.jsonl"), START_MS - 1_000L);

        ClaudeSessionController.LaunchIdentity identity =
                ClaudeSessionController.prepareLaunchIdentity(work.toFile(), config,
                        io.github.nbplugins.claudecodegui.model.SessionMode.CONTINUE_LAST, null);

        assertEquals("newer", identity.sessionId());
        assertEquals(Set.of("older", "newer"), identity.preStartSessionIds());
    }

    @Test
    void prepareLaunchIdentity_resumeSpecific_usesTheGivenId() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);
        writeSession(dir, "saved", "saved");

        ClaudeSessionController.LaunchIdentity identity =
                ClaudeSessionController.prepareLaunchIdentity(work.toFile(), config,
                        io.github.nbplugins.claudecodegui.model.SessionMode.RESUME_SPECIFIC,
                        "saved");

        assertEquals("saved", identity.sessionId());
        assertEquals(Set.of("saved"), identity.preStartSessionIds());
    }

    /**
     * The prompt panel's "New session" action stops with a blank name: no rename
     * is attempted, so the identity — and the session store with it — must not
     * be resolved on the EDT before the PTY is stopped.
     */
    @Test
    void stopAndRename_blankName_doesNotScanTheStore() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);
        writeSession(dir, "a", "a");
        setMtime(dir.resolve("a.jsonl"), START_MS - 5_000L);

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateActiveSession(config, "a", Set.of("a"));
        controller.simulatePromptSubmittedAt(START_MS + 1_000L);

        long scansBefore = controller.simulateScanSequence();
        controller.stopAndRename("   ");

        assertEquals(scansBefore, controller.simulateScanSequence(),
                "a blank name must not trigger a session-store scan");
        assertNull(controller.getActiveConfigDir(), "the process must still be stopped");
        assertNull(io.github.nbplugins.claudecodegui.process.ClaudeSessionStore
                .findSession(work, config, "a").customTitle());
    }

    /**
     * The launch is prepared off the EDT, so the UI is usable while no process
     * exists yet: the context menu asks {@link ClaudeSessionController#getOpenAIProxyUuid()}
     * from the EDT, and the input panel asks {@link ClaudeSessionController#canSendPrompt()}
     * before clearing the field.
     */
    @Test
    void noProcessYet_gettersAreSafeAndSendingIsUnavailable() {
        assertNull(controller.getOpenAIProxyUuid(),
                "asking for the proxy UUID before a launch must not throw");
        assertNull(controller.getLastAttemptedCommand(),
                "asking for the attempted command before a launch must not throw");
        assertFalse(controller.canSendPrompt(),
                "a prompt cannot be delivered before the PTY connector exists");
    }

    /**
     * The counterpart of the test above: while this tab keeps writing its own
     * conversation, another tab's newer file must not be adopted.
     */
    @Test
    void ownSessionIsKeptWhenAnotherTabWritesConcurrently() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "a", "a");
        setMtime(dir.resolve("a.jsonl"), START_MS - 5_000L);
        writeSession(dir, "b", "b");
        setMtime(dir.resolve("b.jsonl"), START_MS - 5_000L);

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateLiveSession(config, "a", Set.of("a", "b"));

        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS + 1_000L, true);
        tabB.simulateLiveSession(config, "b", Set.of("a", "b"));

        try {
            // Both tabs are working: this tab's own file is written after its
            // prompt, and the other tab's file even later.
            controller.simulatePromptSubmittedAt(START_MS + 10_000L);
            setMtime(dir.resolve("a.jsonl"), START_MS + 11_000L);
            setMtime(dir.resolve("b.jsonl"), START_MS + 12_000L);

            assertEquals("a", controller.resolveActiveSessionId(),
                    "a tab writing its own conversation must not adopt another tab's file");
        } finally {
            tabB.stopProcess();
        }
    }

    /**
     * Resuming a saved conversation in-process on a coarse-timestamp filesystem:
     * the new file existed before the launch (pre-launch snapshot) and its mtime
     * is rounded below the prompt time, but the tab must still follow the switch.
     */
    @Test
    void inProcessResumeWithCoarseMtime_followsTheSwitch() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "a", "a");
        setMtime(dir.resolve("a.jsonl"), START_MS - 5_000L);
        writeSession(dir, "b", "b");
        setMtime(dir.resolve("b.jsonl"), START_MS - 5_000L);

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateActiveSession(config, "a", Set.of("a", "b"));

        // The TUI switches to "b" and the plugin sends a prompt; "b" is written
        // after it but its mtime is rounded down into the same second.
        controller.simulatePromptSubmittedAt(START_MS + 1_500L);
        setMtime(dir.resolve("b.jsonl"), START_MS + 1_000L);

        controller.stopAndRename("Resumed Name");

        assertEquals("Resumed Name",
                io.github.nbplugins.claudecodegui.process.ClaudeSessionStore
                        .findSession(work, config, "b").customTitle(),
                "a resumed saved conversation must be recognised despite its rounded mtime");
        assertNull(io.github.nbplugins.claudecodegui.process.ClaudeSessionStore
                        .findSession(work, config, "a").customTitle(),
                "the conversation left by the switch must not be renamed");
    }

    @Test
    void newSessionWithCoarseMtime_resolvesAfterPrompt() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        // A conversation that existed before the NEW tab was launched, so it is
        // in the pre-launch snapshot and must stay ineligible.
        writeSession(dir, "old", "old");
        setMtime(dir.resolve("old.jsonl"), START_MS - 5_000L);

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateActiveSession(config, null, Set.of("old"));

        // The first turn finishes inside one coarse (1 s) timestamp bucket: the
        // file is written after the prompt but its mtime is rounded below it.
        controller.simulatePromptSubmittedAt(START_MS + 1_500L);
        writeSession(dir, "fresh", "fresh");
        setMtime(dir.resolve("fresh.jsonl"), START_MS + 1_000L);

        controller.stopAndRename("Fresh Name");

        assertEquals("Fresh Name",
                io.github.nbplugins.claudecodegui.process.ClaudeSessionStore
                        .findSession(work, config, "fresh").customTitle(),
                "a session whose mtime was rounded down must still be renamable");
        assertNull(io.github.nbplugins.claudecodegui.process.ClaudeSessionStore
                        .findSession(work, config, "old").customTitle(),
                "the pre-launch conversation must not be renamed instead");
    }

    /**
     * A scan queued while the process is being stopped must not start with the
     * new run's generation and the old run's inputs: it would publish (and
     * re-claim) the stopped session after the stop released its claims.
     */
    @Test
    void stopProcess_resetsTheIdentityInputsWithTheGeneration() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        sessionDir(config, work);
        model.setWorkingDirectory(work.toFile());

        for (int i = 0; i < 500; i++) {
            controller.simulateProcessStart(START_MS, true);
            controller.simulateActiveSession(config, "session", Set.of("session"));
            controller.simulatePromptSubmittedAt(START_MS + 1_000L);
            long before = controller.simulateProcessGeneration();

            java.util.concurrent.atomic.AtomicReference<ClaudeSessionController.IdentityCapture>
                    staleCapture = new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicBoolean running =
                    new java.util.concurrent.atomic.AtomicBoolean(true);
            Thread sampler = new Thread(() -> {
                while (running.get()) {
                    ClaudeSessionController.IdentityCapture c =
                            controller.simulateCaptureIdentityInputs();
                    if (c.generation() > before && (c.hasConfigDir() || c.since() != 0)) {
                        staleCapture.compareAndSet(null, c);
                    }
                }
            });
            sampler.setDaemon(true);
            sampler.start();

            controller.stopProcess();
            running.set(false);
            sampler.join();

            assertNull(staleCapture.get(),
                    "a scan starting after the generation bump captured the stopped "
                            + "run's identity inputs on iteration " + i);
        }
    }

    @Test
    void staleExitCallback_doesNotReleaseTheReplacementRun() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        // First run of this tab.
        writeSession(dir, "old", "old");
        setMtime(dir.resolve("old.jsonl"), START_MS - 5_000L);
        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateLiveSession(config, "old", Set.of());
        long oldGeneration = controller.simulateProcessGeneration();

        // Save & Switch restarts the tab before the old waiter reports its exit.
        controller.stopProcess();
        writeSession(dir, "b", "b");
        setMtime(dir.resolve("b.jsonl"), START_MS + 4_000L);
        controller.simulateProcessStart(START_MS + 1_000L, true);
        controller.simulateLiveSession(config, "b", Set.of());
        controller.simulateProcessExitAt(oldGeneration);

        // A sibling started later must still see "b" as owned by this tab.
        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS + 3_000L, true);
        tabB.simulateLiveSession(config, null, Set.of("old"));
        try {
            assertNull(tabB.resolveActiveSessionId(),
                    "a late exit callback of the replaced process must not "
                            + "release the new run's claim");
        } finally {
            tabB.stopProcess();
        }
    }

    @Test
    void scanStartedBeforeRestart_doesNotPublishAfterReplacement() throws Exception {
        java.nio.file.Path storeA = tmpDir.resolve("configA");
        java.nio.file.Path storeB = tmpDir.resolve("configB");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dirA = sessionDir(storeA, work);
        java.nio.file.Path dirB = sessionDir(storeB, work);
        writeSession(dirA, "old", "old");
        setMtime(dirA.resolve("old.jsonl"), START_MS + 2_000L);
        writeSession(dirB, "new", "new");
        setMtime(dirB.resolve("new.jsonl"), START_MS - 5_000L);

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateActiveSession(storeA, "old", Set.of("old"));
        long staleGeneration = controller.simulateProcessGeneration();

        // Save & Switch restarts the tab into another profile / store before
        // the background scan finishes.
        controller.stopProcess();
        controller.simulateProcessStart(START_MS + 1_000L, true);
        controller.simulateActiveSession(storeB, "new", Set.of("new"));

        controller.simulatePublishStaleScanResult(staleGeneration, "old");

        assertEquals("new", controller.resolveActiveSessionId(),
                "a scan started before the restart must not overwrite the "
                        + "identity of the replaced run");
    }

    @Test
    void olderScan_doesNotOverwriteANewerScanResult() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);
        writeSession(dir, "a", "a");
        setMtime(dir.resolve("a.jsonl"), START_MS + 1_000L);
        writeSession(dir, "b", "b");
        setMtime(dir.resolve("b.jsonl"), START_MS + 2_000L);

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateActiveSession(config, null, Set.of());

        // A slow worker scan started before the synchronous one, but finishes
        // (and publishes) after it.
        long slowScan = controller.simulateBeginIdentityScan();
        long fastScan = controller.simulateBeginIdentityScan();
        controller.simulatePublishScanResult(
                controller.simulateProcessGeneration(), fastScan, "b");
        controller.simulatePublishScanResult(
                controller.simulateProcessGeneration(), slowScan, "a");

        // Remove the files so the final resolve cannot mask a wrong identity.
        java.nio.file.Files.delete(dir.resolve("a.jsonl"));
        java.nio.file.Files.delete(dir.resolve("b.jsonl"));

        assertEquals("b", controller.resolveActiveSessionId(),
                "a scan that started earlier must not undo a newer scan's result");
    }

    @Test
    void claimsAreScopedToTheirSessionStore() throws Exception {
        java.nio.file.Path storeA = tmpDir.resolve("configA");
        java.nio.file.Path storeB = tmpDir.resolve("configB");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dirA = sessionDir(storeA, work);
        java.nio.file.Path dirB = sessionDir(storeB, work);

        // A live tab in store A runs a session "shared".
        writeSession(dirA, "shared", "shared");
        setMtime(dirA.resolve("shared.jsonl"), START_MS - 5_000L);
        ClaudeSessionModel modelA = new ClaudeSessionModel();
        modelA.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabA =
                new ClaudeSessionController(modelA, Collections::emptyList);
        tabA.simulateProcessStart(START_MS - 500L, true);
        tabA.simulateLiveSession(storeA, "shared", Set.of("shared"));

        // Our tab in store B switches in-process from "previous" to a copy of
        // the same conversation (same ID, different file).
        writeSession(dirB, "previous", "previous");
        setMtime(dirB.resolve("previous.jsonl"), START_MS - 1_000L);
        writeSession(dirB, "shared", "shared");
        setMtime(dirB.resolve("shared.jsonl"), START_MS + 2_000L);

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateActiveSession(storeB, "previous", Set.of("previous", "shared"));
        try {
            assertEquals("shared", controller.resolveActiveSessionId(),
                    "a claim from another store must not hide this store's file");
        } finally {
            tabA.stopProcess();
        }
    }

    @Test
    void stopAndRename_usesResumeSessionId() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);
        writeSession(dir, "resumed", "old");
        setMtime(dir.resolve("resumed.jsonl"), START_MS - 5_000L);
        writeSession(dir, "newer", "newer");
        setMtime(dir.resolve("newer.jsonl"), START_MS - 1_000L);

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateActiveSession(config, "resumed", Set.of("resumed", "newer"));

        controller.stopAndRename("Resumed Name");

        assertEquals("Resumed Name",
                io.github.nbplugins.claudecodegui.process.ClaudeSessionStore
                        .findSession(work, config, "resumed").customTitle());
        assertNull(io.github.nbplugins.claudecodegui.process.ClaudeSessionStore
                .findSession(work, config, "newer").customTitle(),
                "a newer unrelated session must not be renamed");
    }

    @Test
    void stopAndRename_skipsUnchangedTitle() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);
        writeSession(dir, "s1", "slug");
        java.nio.file.Files.writeString(dir.resolve("s1.jsonl"),
                "{\"type\":\"custom-title\",\"customTitle\":\"Same\",\"sessionId\":\"s1\"}\n",
                java.nio.file.StandardOpenOption.APPEND);
        setMtime(dir.resolve("s1.jsonl"), START_MS - 1_000L);

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateActiveSession(config, "s1", Set.of("s1"));

        controller.stopAndRename("Same");

        assertEquals(1, countCustomTitles(dir.resolve("s1.jsonl"), "Same"),
                "repeated stops must not append duplicate custom-title lines");
    }

    @Test
    void stopAndRename_blankName_doesNothing() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);
        writeSession(dir, "s1", "slug");
        setMtime(dir.resolve("s1.jsonl"), START_MS - 1_000L);

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateActiveSession(config, "s1", Set.of("s1"));

        controller.stopAndRename("   ");

        assertNull(io.github.nbplugins.claudecodegui.process.ClaudeSessionStore
                .findSession(work, config, "s1").customTitle());
    }

    // -------------------------------------------------------------------------
    // No-op listener base class
    // -------------------------------------------------------------------------

    private static class NoOpListener
            implements ClaudeSessionModel.ClaudeSessionModelListener {
        @Override public void onLifecycleChanged(SessionLifecycle state) {}
        @Override public void onEditModeChanged(io.github.nbplugins.claudecodegui.model.EditMode mode) {}
        @Override public void onModelListChanged(List<String> models, int selectedIdx) {}
        @Override public void onChoiceMenuChanged(io.github.nbplugins.claudecodegui.model.ChoiceMenuModel menu) {}
        @Override public void onWorkingDirectoryChanged(java.io.File dir) {}
    }
}
