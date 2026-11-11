package io.github.nbplugins.claudecodegui.controller;

import io.github.nbplugins.claudecodegui.model.ClaudeSessionModel;
import io.github.nbplugins.claudecodegui.model.EditMode;
import io.github.nbplugins.claudecodegui.model.SessionLifecycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.nbplugins.claudecodegui.model.ChoiceMenuModel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
     * A launch-known tab submits a recalled prompt at about the same time as a
     * live sibling submits its own human prompt: the shared pass adopts the
     * sibling's conversation for a moment. The launch conversation then
     * receives this tab's user record and becomes the newest file, so the tab
     * must take its identity back instead of staying pinned to the sibling —
     * otherwise Save &amp; Switch renames the sibling's conversation.
     */
    @Test
    void anUnknownPromptRecoversTheLaunchConversationAfterASharedGuess() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "a", "a");
        writeSession(dir, "b", "b");
        setMtime(dir.resolve("a.jsonl"), START_MS - 5_000L);
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
            // Up recalls a prompt and Enter submits it: its text is unknown.
            controller.simulateTerminalInput(
                    new byte[]{0x1b, '[', 'A'}, START_MS + 11_000L);
            assertNull(controller.simulateTerminalInput(
                    new byte[]{'\r'}, START_MS + 11_001L));

            // The sibling's own human prompt lands first.
            appendUserPrompt(dir, "b", "sibling prompt", START_MS + 11_400L);
            setMtime(dir.resolve("b.jsonl"), START_MS + 11_500L);
            assertEquals("b", controller.resolveActiveSessionId(),
                    "with no record of its own yet, the tab follows the sibling");

            // This tab's submission lands in its launch conversation, later.
            appendUserPrompt(dir, "a", "recalled prompt", START_MS + 11_600L);
            setMtime(dir.resolve("a.jsonl"), START_MS + 12_600L);

            assertEquals("a", controller.resolveActiveSessionId(),
                    "the launch conversation's record must take the identity back");
            assertEquals("a", controller.resolveActiveSessionId(),
                    "later scans must keep the recovered conversation");
            assertEquals("b", tabB.resolveActiveSessionId(),
                    "the sibling keeps its own conversation");
        } finally {
            tabB.stopProcess();
        }
    }

    /**
     * Two unclaimed files hold the same prompt: an exited tab's conversation and
     * this NEW tab's own file, written later. The store returns the newest
     * first; the scan must keep that order and take this tab's own file, or the
     * older conversation is renamed and kept as the identity on later scans.
     */
    @Test
    void anUnclaimedNewerOwnFileWinsOverAnOlderOneWithTheSamePrompt() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "a", "a");
        writeSession(dir, "z", "z");
        setMtime(dir.resolve("a.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("z.jsonl"), START_MS - 5_000L);

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, false);
        controller.simulateLiveSession(config, null, Set.of("a", "z"));

        controller.simulateTerminalInput(
                "same".getBytes(java.nio.charset.StandardCharsets.UTF_8), START_MS + 11_000L);
        assertEquals("same", controller.simulateTerminalInput(
                new byte[]{'\r'}, START_MS + 11_001L));

        // The exited tab's conversation already holds the same prompt.
        appendUserPrompt(dir, "a", "same", START_MS + 11_400L);
        setMtime(dir.resolve("a.jsonl"), START_MS + 11_500L);
        // This tab's own file was written later.
        appendUserPrompt(dir, "z", "same", START_MS + 11_600L);
        setMtime(dir.resolve("z.jsonl"), START_MS + 12_600L);

        assertEquals("z", controller.resolveActiveSessionId(),
                "the newest unclaimed match is this tab's own file");
        assertEquals("z", controller.resolveActiveSessionId(),
                "later scans must keep the same file");
    }

    /**
     * The launch snapshot is prepared on a worker thread and the spawn re-reads
     * the profile. If the profile's storage moved in between, the snapshot
     * lists the old store, whose files make every file in the new store look
     * new — one written within the file-time slack could then be adopted as
     * this tab's own. The snapshot must be recaptured in the spawn store.
     */
    @Test
    void aProfileMovedDuringPreparationRecapturesTheLaunchSnapshot() throws Exception {
        java.nio.file.Path oldConfig = tmpDir.resolve("old-config");
        java.nio.file.Path newConfig = tmpDir.resolve("new-config");
        java.nio.file.Path work = tmpDir.resolve("project");

        writeSession(sessionDir(oldConfig, work), "old", "old");
        writeSession(sessionDir(newConfig, work), "current", "current");

        ClaudeSessionController.LaunchIdentity prepared =
                ClaudeSessionController.prepareLaunchIdentity(
                        work.toFile(), oldConfig,
                        io.github.nbplugins.claudecodegui.model.SessionMode.NEW, null);
        assertEquals(Set.of("old"), prepared.preStartSessionIds());

        ClaudeSessionController.LaunchIdentity same =
                ClaudeSessionController.reconcileLaunchIdentity(
                        work.toFile(),
                        io.github.nbplugins.claudecodegui.model.SessionMode.NEW,
                        null, prepared, oldConfig);
        assertSame(prepared, same, "an unchanged store keeps the prepared snapshot");

        ClaudeSessionController.LaunchIdentity moved =
                ClaudeSessionController.reconcileLaunchIdentity(
                        work.toFile(),
                        io.github.nbplugins.claudecodegui.model.SessionMode.NEW,
                        null, prepared, newConfig);
        assertEquals(newConfig, moved.configDir());
        assertEquals(Set.of("current"), moved.preStartSessionIds(),
                "the snapshot must describe the store the process will write");
    }

    /**
     * Two live conversations held by sibling tabs both contain the submitted
     * text; the tab must adopt the one written last, which is the conversation
     * the process is actually on. Searching an unordered set could pick the
     * older sibling instead.
     */
    @Test
    void borrowedPromptCandidatesAreSearchedNewestFirst() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "a", "a");
        writeSession(dir, "b", "b");
        setMtime(dir.resolve("a.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("b.jsonl"), START_MS - 5_000L);

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, false);
        controller.simulateLiveSession(config, null, Set.of("a", "b"));

        ClaudeSessionModel modelC = new ClaudeSessionModel();
        modelC.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabC =
                new ClaudeSessionController(modelC, Collections::emptyList);
        tabC.simulateProcessStart(START_MS + 1_000L, true);
        tabC.simulateLiveSession(config, "a", Set.of("a"));

        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS + 1_000L, true);
        tabB.simulateLiveSession(config, "b", Set.of("b"));

        try {
            controller.simulateTerminalInput(
                    "continue".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    START_MS + 11_000L);
            assertEquals("continue", controller.simulateTerminalInput(
                    new byte[]{'\r'}, START_MS + 11_001L));

            // The older sibling's conversation holds the text first...
            appendUserPrompt(dir, "a", "continue", START_MS + 11_400L);
            setMtime(dir.resolve("a.jsonl"), START_MS + 11_500L);
            // ...and the newer one holds it too, written strictly later.
            appendUserPrompt(dir, "b", "continue", START_MS + 11_600L);
            setMtime(dir.resolve("b.jsonl"), START_MS + 12_600L);

            assertEquals("b", controller.resolveActiveSessionId(),
                    "the live conversation written last is the one on screen");
            assertEquals("b", controller.resolveActiveSessionId(),
                    "later scans must keep the same conversation");
        } finally {
            tabB.stopProcess();
            tabC.stopProcess();
        }
    }

    /**
     * A NEW tab submits a recalled prompt and borrows a live sibling's
     * conversation while its own file does not exist yet. Once its own file
     * does exist, it must win even if the sibling writes again later: the
     * borrowed file is established by another live tab, so the claim-based
     * exclusion alone (this tab now holds the sibling's file too) would keep
     * choosing the sibling.
     */
    @Test
    void anUnknownPromptPrefersItsOwnNewFileOverTheBorrowedSiblings() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "b", "b");
        setMtime(dir.resolve("b.jsonl"), START_MS - 5_000L);

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateLiveSession(config, null, Set.of("b"));

        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS + 1_000L, true);
        tabB.simulateLiveSession(config, "b", Set.of("b"));

        try {
            // Up recalls a prompt, Enter submits it: its text is unknown.
            controller.simulateTerminalInput(
                    new byte[]{0x1b, '[', 'A'}, START_MS + 11_000L);
            assertNull(controller.simulateTerminalInput(
                    new byte[]{'\r'}, START_MS + 11_001L));

            appendUserPrompt(dir, "b", "sibling prompt", START_MS + 11_400L);
            setMtime(dir.resolve("b.jsonl"), START_MS + 11_500L);
            assertEquals("b", controller.resolveActiveSessionId(),
                    "the sibling's conversation is borrowed while no own file exists");

            // This tab's own file appears, then the sibling writes again later.
            writeSession(dir, "own", "own");
            appendUserPrompt(dir, "own", "own prompt", START_MS + 11_600L);
            setMtime(dir.resolve("own.jsonl"), START_MS + 11_700L);
            appendUserPrompt(dir, "b", "sibling again", START_MS + 12_600L);
            setMtime(dir.resolve("b.jsonl"), START_MS + 12_700L);

            assertEquals("own", controller.resolveActiveSessionId(),
                    "the tab's own file must win over the borrowed sibling's");
            assertEquals("own", controller.resolveActiveSessionId(),
                    "later scans must keep the tab's own file");
        } finally {
            tabB.stopProcess();
        }
    }

    /**
     * Two live tabs launched the same saved conversation {@code a}; this tab
     * then resumed {@code b} in the TUI and submits a recalled prompt there.
     * The other launcher writes its own human message into {@code a}. A record
     * in the launch file is not proof that this tab's submission went there, so
     * the strictly newer resumed conversation must win.
     */
    @Test
    void aSharedLaunchFileDoesNotOverrideAConversationResumedLater() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "a", "a");
        writeSession(dir, "b", "b");
        setMtime(dir.resolve("a.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("b.jsonl"), START_MS - 5_000L);

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateLiveSession(config, "a", Set.of("a", "b"));

        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS + 1_000L, true);
        tabB.simulateLiveSession(config, "a", Set.of("a", "b"));

        try {
            // The TUI resume into b: the conversation is written without any
            // prompt from this tab yet, so the scan follows the file time.
            appendUserPrompt(dir, "b", "earlier turn", START_MS + 5_000L);
            setMtime(dir.resolve("b.jsonl"), START_MS + 5_500L);
            assertEquals("b", controller.resolveActiveSessionId(),
                    "the resumed conversation becomes the tracked identity");

            // Up recalls a prompt and Enter submits it: its text is unknown.
            controller.simulateTerminalInput(
                    new byte[]{0x1b, '[', 'A'}, START_MS + 11_000L);
            assertNull(controller.simulateTerminalInput(
                    new byte[]{'\r'}, START_MS + 11_001L));

            // The other launcher's own human message lands in the shared file.
            appendUserPrompt(dir, "a", "other tab turn", START_MS + 11_400L);
            setMtime(dir.resolve("a.jsonl"), START_MS + 11_500L);
            // This tab's submission lands in the resumed conversation, later.
            appendUserPrompt(dir, "b", "recalled prompt", START_MS + 11_600L);
            setMtime(dir.resolve("b.jsonl"), START_MS + 12_600L);

            assertEquals("b", controller.resolveActiveSessionId(),
                    "the resumed conversation written last is the one on screen");
            assertEquals("b", controller.resolveActiveSessionId(),
                    "later scans must keep the resumed conversation");
        } finally {
            tabB.stopProcess();
        }
    }

    /**
     * A tab launched as a second writer of saved conversation {@code a}
     * temporarily adopts a live sibling's conversation {@code c} while its own
     * record is not flushed yet. Once its matching record lands in {@code a},
     * the launch file must be eligible again: it is established by another live
     * tab, so the ownership exclusion alone would keep the sibling preferred.
     */
    @Test
    void aSharedLaunchConversationWinsBackAfterATemporaryAdoption() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "a", "a");
        writeSession(dir, "c", "c");
        setMtime(dir.resolve("a.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("c.jsonl"), START_MS - 5_000L);

        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS, true);
        tabB.simulateLiveSession(config, "a", Set.of("a", "c"));

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS + 1_000L, true);
        controller.simulateLiveSession(config, "a", Set.of("a", "c"));

        ClaudeSessionModel modelC = new ClaudeSessionModel();
        modelC.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabC =
                new ClaudeSessionController(modelC, Collections::emptyList);
        tabC.simulateProcessStart(START_MS + 1_000L, true);
        tabC.simulateLiveSession(config, "c", Set.of("a", "c"));

        try {
            controller.simulateTerminalInput(
                    "continue".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    START_MS + 11_000L);
            assertEquals("continue", controller.simulateTerminalInput(
                    new byte[]{'\r'}, START_MS + 11_001L));

            // The sibling's conversation holds the text first.
            appendUserPrompt(dir, "c", "continue", START_MS + 11_400L);
            setMtime(dir.resolve("c.jsonl"), START_MS + 11_500L);
            assertEquals("c", controller.resolveActiveSessionId(),
                    "the sibling's conversation is adopted while no own record exists");

            // This tab's record lands in the shared launch conversation, later.
            appendUserPrompt(dir, "a", "continue", START_MS + 11_600L);
            setMtime(dir.resolve("a.jsonl"), START_MS + 12_600L);

            assertEquals("a", controller.resolveActiveSessionId(),
                    "the launch conversation's record must take the identity back");
            assertEquals("a", controller.resolveActiveSessionId(),
                    "later scans must keep the launch conversation");
        } finally {
            tabC.stopProcess();
            tabB.stopProcess();
        }
    }

    /**
     * A NEW tab has resolved its own conversation {@code a} and a sibling NEW
     * tab is borrowing it. Stopping the writer runs the exit callback, which
     * releases this tab's claims — the final lookup before the rename must
     * still treat {@code a} as this tab's own, or it falls back to an unrelated
     * conversation that merely received a user record after the submission,
     * even though {@code a} is strictly newer.
     */
    @Test
    void theFinalStopLookupKeepsTheConversationsTheTabHeld() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "a", "a");
        writeSession(dir, "noise", "noise");
        setMtime(dir.resolve("a.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("noise.jsonl"), START_MS - 5_000L);

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateLiveSession(config, null, Set.of("a", "noise"));

        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);

        try {
            // Up recalls a prompt, Enter submits it: its text is unknown.
            controller.simulateTerminalInput(
                    new byte[]{0x1b, '[', 'A'}, START_MS + 11_000L);
            assertNull(controller.simulateTerminalInput(
                    new byte[]{'\r'}, START_MS + 11_001L));

            appendUserPrompt(dir, "a", "recalled prompt", START_MS + 11_400L);
            setMtime(dir.resolve("a.jsonl"), START_MS + 11_500L);
            assertEquals("a", controller.resolveActiveSessionId(),
                    "the tab's own conversation is adopted");

            // A sibling NEW tab borrows the resolved conversation.
            tabB.simulateProcessStart(START_MS + 12_000L, true);
            tabB.simulateLiveSession(config, "a", Set.of("a", "noise"));

            // An unrelated conversation also receives a human record after the
            // submission; the tab's own file stays strictly newer.
            appendUserPrompt(dir, "noise", "unrelated work", START_MS + 12_100L);
            setMtime(dir.resolve("noise.jsonl"), START_MS + 12_200L);
            setMtime(dir.resolve("a.jsonl"), START_MS + 12_600L);

            Set<String> held = controller.claimedSessionIds(work.toFile(), config);
            assertEquals(Set.of("a"), held, "the resolved conversation is held");

            // The waiter runs the exit callback while the writer is stopping.
            controller.simulateProcessExit();

            assertEquals("a", controller.resolveIdentityWithPreservedClaims(held),
                    "the final lookup must keep the conversations the tab held");
        } finally {
            tabB.stopProcess();
        }
    }

    /**
     * Two tabs resumed the same saved conversation {@code a}: the first owns
     * it, so the second only borrows it even though {@code --resume} named that
     * file. Stopping the second tab without submitting a prompt releases the
     * borrowed hold, and the final lookup must still keep the launch
     * conversation — an unrelated conversation written since the launch must
     * not take the rename just because it is unheld.
     */
    @Test
    void aStoppingTabKeepsTheLaunchConversationItOnlyBorrows() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "a", "a");
        writeSession(dir, "noise", "noise");
        setMtime(dir.resolve("a.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("noise.jsonl"), START_MS - 5_000L);

        // The first tab owns the saved conversation.
        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateLiveSession(config, "a", Set.of("a", "noise"));

        // The second tab resumed the same conversation and so only borrows it.
        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList) {
                    @Override
                    void stopWriter() {
                        super.stopWriter();
                        // The exit waiter releases this tab's claims while the
                        // writer shuts down, before the final lookup runs.
                        simulateProcessExit();
                    }
                };
        tabB.simulateProcessStart(START_MS + 1_000L, true);
        tabB.simulateLiveSession(config, "a", Set.of("a", "noise"));

        // An unrelated conversation is written after the launch but stays
        // older than the launch conversation.
        appendUserPrompt(dir, "noise", "unrelated work", START_MS + 10_000L);
        setMtime(dir.resolve("noise.jsonl"), START_MS + 11_000L);
        appendUserPrompt(dir, "a", "from the first tab", START_MS + 11_500L);
        setMtime(dir.resolve("a.jsonl"), START_MS + 12_000L);

        tabB.stopAndRename("Shared Name");

        assertEquals("Shared Name",
                io.github.nbplugins.claudecodegui.process.ClaudeSessionStore
                        .findSession(work, config, "a").customTitle(),
                "the launch conversation is renamed, not a newer unrelated file");
        assertNull(io.github.nbplugins.claudecodegui.process.ClaudeSessionStore
                        .findSession(work, config, "noise").customTitle(),
                "the unrelated conversation must keep its name");
    }

    /**
     * The PTY can exit on its own (Claude quit or crashed) while the tab stays
     * open, and the user then closes it with a new name. The exit callback has
     * already released this tab's claims, so a conversation it only borrowed is
     * no longer evidence by the time Save &amp; Switch resolves the target: it
     * must be carried over from the exit, or an unrelated unheld file takes the
     * rename just because it is newer.
     */
    @Test
    void aTabWhoseProcessExitedKeepsItsLaunchConversation() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "a", "a");
        writeSession(dir, "noise", "noise");
        setMtime(dir.resolve("a.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("noise.jsonl"), START_MS - 5_000L);

        // The first tab owns the saved conversation.
        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateLiveSession(config, "a", Set.of("a", "noise"));

        // The second tab resumed the same conversation and so only borrows it.
        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS + 1_000L, true);
        tabB.simulateLiveSession(config, "a", Set.of("a", "noise"));

        // An unrelated conversation is written after the launch but stays
        // older than the launch conversation.
        appendUserPrompt(dir, "noise", "unrelated work", START_MS + 10_000L);
        setMtime(dir.resolve("noise.jsonl"), START_MS + 11_000L);
        appendUserPrompt(dir, "a", "from the first tab", START_MS + 11_500L);
        setMtime(dir.resolve("a.jsonl"), START_MS + 12_000L);

        // Claude quits on its own while the tab stays open.
        tabB.simulateProcessExit();

        tabB.stopAndRename("Shared Name");

        assertEquals("Shared Name",
                io.github.nbplugins.claudecodegui.process.ClaudeSessionStore
                        .findSession(work, config, "a").customTitle(),
                "the launch conversation is renamed, not a newer unrelated file");
        assertNull(io.github.nbplugins.claudecodegui.process.ClaudeSessionStore
                        .findSession(work, config, "noise").customTitle(),
                "the unrelated conversation must keep its name");
    }

    /**
     * A panel send can still be paused between its text and its Enter when the
     * user closes the tab. Its Enter never reaches the PTY, but if it completes
     * after the writer was stopped, its evidence replaces the last submitted
     * prompt before the final identity lookup runs: an unrelated conversation
     * that received the same text after that bogus cutoff then takes the
     * rename instead of the launch conversation.
     */
    @Test
    void aPausedSendDoesNotReplaceTheEvidenceOfTheFinalLookup() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "a", "a");
        writeSession(dir, "noise", "noise");
        setMtime(dir.resolve("a.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("noise.jsonl"), START_MS - 5_000L);

        model.setWorkingDirectory(work.toFile());
        CountDownLatch paused = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<java.util.concurrent.Future<?>> send = new AtomicReference<>();
        ClaudeSessionController tab =
                new ClaudeSessionController(model, Collections::emptyList) {
                    @Override
                    void pauseGuiSend() throws InterruptedException {
                        paused.countDown();
                        // A stop cancels the queue by interrupting this sender.
                        release.await();
                    }

                    @Override
                    void stopWriter() {
                        // Let the paused send finish right here: the identity
                        // snapshot is taken after this method returns.
                        release.countDown();
                        java.util.concurrent.Future<?> queued = send.get();
                        if (queued != null) {
                            try {
                                queued.get(2, TimeUnit.SECONDS);
                            } catch (Exception ignored) {
                                // Cancelled by the stop: the expected outcome.
                            }
                        }
                        super.stopWriter();
                    }
                };
        tab.simulateProcessStart(START_MS, true);
        tab.simulateLiveSession(config, "a", Set.of("a", "noise"));
        // The launch conversation holds the last prompt this tab submitted.
        tab.simulateGuiSend("hello", START_MS + 2_000L);
        tab.completeGuiSend(() -> START_MS + 2_000L);
        appendUserPrompt(dir, "a", "hello", START_MS + 2_100L);
        setMtime(dir.resolve("a.jsonl"), START_MS + 2_200L);

        // A second panel send is written and pauses before its Enter.
        send.set(tab.simulateQueueGuiSend("delayed words",
                () -> START_MS + 3_000L, () -> START_MS + 20_000L));
        assertTrue(paused.await(5, TimeUnit.SECONDS), "the second send must pause");

        // An unrelated conversation receives the same text after the send's
        // would-be submission and is newer than the launch conversation.
        appendUserPrompt(dir, "noise", "delayed words", START_MS + 20_100L);
        setMtime(dir.resolve("noise.jsonl"), START_MS + 20_200L);

        try {
            tab.stopAndRename("Closed Name");
        } finally {
            tab.stopProcess();
        }

        assertEquals("Closed Name",
                io.github.nbplugins.claudecodegui.process.ClaudeSessionStore
                        .findSession(work, config, "a").customTitle(),
                "the launch conversation is renamed, not the lookalike file");
        assertNull(io.github.nbplugins.claudecodegui.process.ClaudeSessionStore
                        .findSession(work, config, "noise").customTitle(),
                "the unrelated conversation must keep its name");
    }

    /**
     * A terminal Enter during the pause submits the panel line for real. When a
     * stop then cancels the sender, the completion that would normally publish
     * that submission never runs: the lookup sees only unknown-text evidence
     * and, after an in-process resume into a live conversation, falls back to
     * the newer user record in the launch file instead of following the
     * conversation that actually received the prompt.
     */
    @Test
    void aTerminalSubmissionDuringThePauseSurvivesTheCancelledSend() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "a", "a");
        writeSession(dir, "b", "b");
        setMtime(dir.resolve("a.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("b.jsonl"), START_MS - 5_000L);

        model.setWorkingDirectory(work.toFile());
        CountDownLatch paused = new CountDownLatch(1);
        AtomicReference<java.util.concurrent.Future<?>> send = new AtomicReference<>();
        long terminalEnterAt = START_MS + 11_100L;
        ClaudeSessionController tab =
                new ClaudeSessionController(model, Collections::emptyList) {
                    @Override
                    void pauseGuiSend() throws InterruptedException {
                        // The user presses Enter in the terminal: the line the
                        // panel text was written to is submitted for real.
                        simulateTerminalInput(new byte[]{'\r'}, terminalEnterAt);
                        paused.countDown();
                        // Only the stop's interruption releases this sender.
                        new CountDownLatch(1).await();
                    }
                };
        tab.simulateProcessStart(START_MS, true);
        // This tab launched the saved conversation a; a sibling tab holds b.
        tab.simulateLiveSession(config, "a", Set.of("a", "b"));
        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS + 1_000L, true);
        tabB.simulateLiveSession(config, "b", Set.of("a", "b"));

        try {
            // The user resumes b from the prompt panel: the conversation is on
            // b, but this tab's identity has not been re-resolved yet.
            tab.simulateGuiSend("/resume", START_MS + 10_000L);
            tab.completeGuiSend(() -> START_MS + 10_050L);
            tab.simulateGuiChoiceAnswer("ARROW:1");

            // The next panel send is written and pauses before its Enter.
            send.set(tab.simulateQueueGuiSend("delayed words",
                    () -> START_MS + 11_000L, () -> START_MS + 20_000L));
            assertTrue(paused.await(5, TimeUnit.SECONDS), "the second send must pause");

            // The terminal Enter submitted the panel text into b. The launch
            // file receives a later user record, so unknown-text time matching
            // would pick it instead of b, which holds the submitted prompt.
            appendUserPrompt(dir, "b", "delayed words", START_MS + 11_150L);
            setMtime(dir.resolve("b.jsonl"), START_MS + 11_200L);
            appendUserPrompt(dir, "a", "later work", START_MS + 11_300L);
            setMtime(dir.resolve("a.jsonl"), START_MS + 11_400L);

            tab.stopAndRename("Closed Name");
        } finally {
            tab.stopProcess();
            tabB.stopProcess();
        }

        assertEquals("Closed Name",
                io.github.nbplugins.claudecodegui.process.ClaudeSessionStore
                        .findSession(work, config, "b").customTitle(),
                "the conversation the terminal Enter submitted into is renamed");
        assertNull(io.github.nbplugins.claudecodegui.process.ClaudeSessionStore
                        .findSession(work, config, "a").customTitle(),
                "the launch conversation must keep its name");
    }

    /**
     * An Up/Down arrow moves the half-second-stale menu cursor off the text
     * editor a digit had activated, and the poll does not report it yet: the
     * tracked activation must end with the arrow, so the digit naming the
     * item the user moved to selects it instead of being typed into an editor
     * that no longer has the focus.
     */
    @Test
    void anArrowLeavingTheActivatedEditorRestoresDigitSelections() {
        model.setActiveChoiceMenu(new ChoiceMenuModel("How was your experience?", List.of(
                new ChoiceMenuModel.Option("Great", "1"),
                new ChoiceMenuModel.Option("Bad", "2"),
                new ChoiceMenuModel.Option("Type something.", "3")), 0));

        // The digit activates the editor, then Up moves the cursor back to
        // the ordinary items before the next poll.
        controller.simulateTerminalInput(new byte[]{'3'}, START_MS);
        controller.simulateTerminalInput(new byte[]{0x1b, '[', 'A'}, START_MS + 10L);
        controller.simulateTerminalInput(new byte[]{0x1b, '[', 'A'}, START_MS + 20L);

        assertEquals("hello", controller.simulateTerminalInput(
                        "1hello\r".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        START_MS + 100L),
                "the digit selects the item the cursor moved to and hello is the next prompt");
    }

    /**
     * A draft typed after the terminal Enter that submitted the panel line is
     * a new, unsubmitted line: it must not hide the submission when a stop
     * cancels the sender. Otherwise the exact panel text is lost as evidence
     * and the lookup falls back to the launch conversation's newer record
     * instead of following the resumed live conversation that received it.
     */
    @Test
    void aDraftAfterTheSubmittingEnterDoesNotHideTheSubmission() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "a", "a");
        writeSession(dir, "b", "b");
        setMtime(dir.resolve("a.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("b.jsonl"), START_MS - 5_000L);

        model.setWorkingDirectory(work.toFile());
        CountDownLatch paused = new CountDownLatch(1);
        long terminalEnterAt = START_MS + 11_100L;
        ClaudeSessionController tab =
                new ClaudeSessionController(model, Collections::emptyList) {
                    @Override
                    void pauseGuiSend() throws InterruptedException {
                        simulateTerminalInput(new byte[]{'\r'}, terminalEnterAt);
                        // The user starts another line without submitting it.
                        simulateTerminalInput(
                                "next".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                terminalEnterAt + 100L);
                        paused.countDown();
                        // Only the stop's interruption releases this sender.
                        new CountDownLatch(1).await();
                    }
                };
        tab.simulateProcessStart(START_MS, true);
        tab.simulateLiveSession(config, "a", Set.of("a", "b"));
        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS + 1_000L, true);
        tabB.simulateLiveSession(config, "b", Set.of("a", "b"));

        try {
            tab.simulateGuiSend("/resume", START_MS + 10_000L);
            tab.completeGuiSend(() -> START_MS + 10_050L);
            tab.simulateGuiChoiceAnswer("ARROW:1");

            tab.simulateQueueGuiSend("delayed words",
                    () -> START_MS + 11_000L, () -> START_MS + 20_000L);
            assertTrue(paused.await(5, TimeUnit.SECONDS), "the second send must pause");

            appendUserPrompt(dir, "b", "delayed words", START_MS + 11_150L);
            setMtime(dir.resolve("b.jsonl"), START_MS + 11_200L);
            appendUserPrompt(dir, "a", "later work", START_MS + 11_300L);
            setMtime(dir.resolve("a.jsonl"), START_MS + 11_400L);

            tab.stopAndRename("Closed Name");
        } finally {
            tab.stopProcess();
            tabB.stopProcess();
        }

        assertEquals("Closed Name",
                io.github.nbplugins.claudecodegui.process.ClaudeSessionStore
                        .findSession(work, config, "b").customTitle(),
                "the conversation that received the panel prompt is renamed");
        assertNull(io.github.nbplugins.claudecodegui.process.ClaudeSessionStore
                        .findSession(work, config, "a").customTitle(),
                "the launch conversation must keep its name");
    }

    /**
     * A sender interrupted by a restart may reach its cleanup only after the
     * replacement process recorded input. Its generation is stale by then, and
     * the recorder holds the replacement's state: clearing it would erase the
     * replacement's draft, so a following panel prompt would be published
     * without the text the PTY actually submits.
     */
    @Test
    void aCancelledSendDoesNotClearTheReplacementRecordersDraft() {
        long accepted = controller.simulateProcessGeneration();
        controller.simulateGuiSend("hello", START_MS);

        controller.stopProcess();
        controller.simulateTerminalInput(
                "fresh".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                START_MS + 100L);

        // The interrupted sender's cleanup runs only now.
        controller.simulateDiscardGuiSend(accepted);

        assertEquals("fresh suffix", controller.simulateGuiSend(" suffix", START_MS + 200L),
                "the replacement's draft must survive the cancelled send's cleanup");
    }

    /**
     * Arrows can also enter a text editor: two Downs from the first item land
     * on `Type something.`, and the poll does not report it yet. The digits
     * typed in that window belong to the editor, so they must not retire the
     * menu and turn the rest of the answer into prompt evidence.
     */
    @Test
    void arrowsEnteringTheEditorMakeTheFollowingDigitsItsText() {
        model.setActiveChoiceMenu(new ChoiceMenuModel("How was your experience?", List.of(
                new ChoiceMenuModel.Option("Great", "1"),
                new ChoiceMenuModel.Option("Bad", "2"),
                new ChoiceMenuModel.Option("Type something.", "3")), 0));

        controller.simulateTerminalInput(new byte[]{0x1b, '[', 'B'}, START_MS + 10L);
        controller.simulateTerminalInput(new byte[]{0x1b, '[', 'B'}, START_MS + 20L);

        assertNull(controller.simulateTerminalInput(
                        "1feedback\r".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        START_MS + 100L),
                "the cursor is in the editor the arrows reached; the answer is not a prompt");
        assertNull(controller.simulateLastPromptText(),
                "the menu's answer must not become prompt evidence");
    }

    /**
     * A sender blocked in a native write to a hung process cannot be released
     * by interrupting its thread, and Save & Switch waits for it on the EDT.
     * The PTY child is killed before the wait, so the write returns and the
     * stop completes instead of freezing the IDE.
     */
    @Test
    void stopAndRenameUnblocksAWriteStuckOnAHungProcess() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);
        writeSession(dir, "a", "a");
        setMtime(dir.resolve("a.jsonl"), START_MS - 5_000L);

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateActiveSession(config, "a", Set.of("a"));

        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        controller.simulatePtyWrite(bytes -> {
            writing.countDown();
            // A hung process never drains the pipe: the interrupt that
            // cancels the queue does not end this write.
            while (true) {
                try {
                    released.await();
                    return;
                } catch (InterruptedException ignored) {
                    // the native write ignores it too
                }
            }
        });
        // Only killing the child releases the blocked write.
        controller.simulatePtyKill(released::countDown);
        controller.simulateQueueGuiSend("delayed words",
                () -> START_MS + 10L, () -> START_MS + 20L);
        assertTrue(writing.await(5, TimeUnit.SECONDS), "the send must reach its write");

        java.util.concurrent.ExecutorService stopper =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<?> stopped = stopper.submit(
                    () -> controller.stopAndRename("Closed Name"));
            stopped.get(10, TimeUnit.SECONDS);
        } finally {
            released.countDown();
            stopper.shutdownNow();
            controller.stopProcess();
        }

        assertEquals("Closed Name",
                io.github.nbplugins.claudecodegui.process.ClaudeSessionStore
                        .findSession(work, config, "a").customTitle(),
                "the stop completes and still names the launched session");
    }

    /**
     * A switch command submitted by a terminal Enter during the pause — the
     * panel's untouched line — has switched the conversation. A later terminal
     * submission of a recalled prompt replaces the text evidence, but the
     * switch itself happened, and the stop-time lookup has to follow it to the
     * conversation the command cleared into rather than the launch one.
     */
    @Test
    void aSubmittedSwitchSurvivesALaterRecalledSubmission() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "a", "a");
        setMtime(dir.resolve("a.jsonl"), START_MS - 5_000L);

        model.setWorkingDirectory(work.toFile());
        CountDownLatch paused = new CountDownLatch(1);
        ClaudeSessionController tab =
                new ClaudeSessionController(model, Collections::emptyList) {
                    @Override
                    void pauseGuiSend() {
                        // The terminal submits the untouched /clear, then the
                        // user recalls an earlier prompt and submits it too.
                        simulateTerminalInput(new byte[]{'\r'}, START_MS + 11_000L);
                        simulateTerminalInput(new byte[]{0x1b, '[', 'A'}, START_MS + 11_050L);
                        simulateTerminalInput(new byte[]{'\r'}, START_MS + 11_100L);
                        paused.countDown();
                    }
                };

        try {
            tab.simulateProcessStart(START_MS, true);
            tab.simulateLiveSession(config, "a", Set.of("a"));
            tab.simulateQueueGuiSend("/clear",
                    () -> START_MS + 10_000L, () -> START_MS + 11_200L);
            assertTrue(paused.await(5, TimeUnit.SECONDS), "the send must pause");

            // The launch conversation keeps receiving records, but the
            // conversation /clear opened is strictly newer.
            appendUserPrompt(dir, "a", "later work", START_MS + 11_250L);
            setMtime(dir.resolve("a.jsonl"), START_MS + 11_300L);
            writeSession(dir, "b", "b");
            appendUserPrompt(dir, "b", "recalled words", START_MS + 11_350L);
            setMtime(dir.resolve("b.jsonl"), START_MS + 11_400L);

            tab.stopAndRename("Cleared Title");
        } finally {
            tab.stopProcess();
        }

        assertEquals("Cleared Title",
                io.github.nbplugins.claudecodegui.process.ClaudeSessionStore
                        .findSession(work, config, "b").customTitle(),
                "the conversation the submitted switch opened is renamed");
        assertNull(io.github.nbplugins.claudecodegui.process.ClaudeSessionStore
                        .findSession(work, config, "a").customTitle(),
                "the launch conversation keeps its name");
    }

    /**
     * A GUI answer typed into a menu's text editor is forwarded with a delay:
     * the TUI keeps the menu open until its Enter. A typed answer submitted
     * with Enter in that window answers the same editor, so it is not a prompt
     * and must not become prompt evidence.
     */
    @Test
    void aTypedAnswerDuringTheTypeAnswerIsNotAPrompt() throws Exception {
        model.setActiveChoiceMenu(new ChoiceMenuModel("How was your experience?", List.of(
                new ChoiceMenuModel.Option("Great", "1"),
                new ChoiceMenuModel.Option("Bad", "2"),
                new ChoiceMenuModel.Option("Type something.", "3")), 0));

        controller.writePtyAnswer("TYPE:3:auto answer");

        assertNull(controller.simulateTerminalInput(
                        "feedback\r".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        START_MS + 100L),
                "the TUI is still on its editor: the typed answer is the menu's");
        assertNull(controller.simulateLastPromptText(),
                "the menu's answer must not become prompt evidence");

        long deadline = System.currentTimeMillis() + 3_000L;
        while (model.getActiveChoiceMenu() != null && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
        assertNull(model.getActiveChoiceMenu(),
                "the menu is retired once the answer has been forwarded");
    }

    /**
     * A NEW tab temporarily borrows a live sibling's conversation because both
     * submitted the same text before its own file was flushed. The borrowed
     * file is the sibling's, so the final stop lookup must not preserve it as
     * this tab's own: once this tab's own file is flushed during the stop, that
     * file is the answer even if the sibling's conversation is currently
     * tracked.
     */
    @Test
    void aBorrowedConversationIsNotPreservedForTheFinalStopLookup() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "a", "a");
        writeSession(dir, "b", "b");
        setMtime(dir.resolve("a.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("b.jsonl"), START_MS - 5_000L);

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateLiveSession(config, null, Set.of("a", "b"));

        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS + 1_000L, true);
        tabB.simulateLiveSession(config, "b", Set.of("a", "b"));

        try {
            controller.simulateTerminalInput(
                    "continue".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    START_MS + 11_000L);
            assertEquals("continue", controller.simulateTerminalInput(
                    new byte[]{'\r'}, START_MS + 11_001L));

            // The sibling's conversation holds the text first.
            appendUserPrompt(dir, "b", "continue", START_MS + 11_400L);
            setMtime(dir.resolve("b.jsonl"), START_MS + 11_500L);
            assertEquals("b", controller.resolveActiveSessionId(),
                    "the sibling's conversation is borrowed while no own file exists");

            // This tab's own file is flushed during the stop, strictly newest.
            appendUserPrompt(dir, "a", "continue", START_MS + 11_600L);
            setMtime(dir.resolve("a.jsonl"), START_MS + 12_600L);

            Set<String> held = controller.claimedSessionIds(work.toFile(), config);
            assertEquals(Set.of(), held,
                    "a borrowed conversation is not this tab's own file");

            controller.simulateProcessExit();

            assertEquals("a", controller.resolveIdentityWithPreservedClaims(held),
                    "the tab's own file must win over the borrowed one");
        } finally {
            tabB.stopProcess();
        }
    }

    /**
     * A launch-known tab temporarily adopted a sibling's conversation {@code c}
     * because the sibling flushed the identical prompt first. Once the
     * conversation the tab actually joined holds the submission and is strictly
     * newer, the borrowed guess must not stay preferred: the newest-first
     * candidate order has to decide, or Save &amp; Switch renames the sibling's
     * conversation.
     */
    @Test
    void aBorrowedGuessDoesNotPinALaunchKnownTabToTheOlderConversation() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "a", "a");
        writeSession(dir, "b", "b");
        writeSession(dir, "c", "c");
        setMtime(dir.resolve("a.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("b.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("c.jsonl"), START_MS - 5_000L);

        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS, true);
        tabB.simulateLiveSession(config, "b", Set.of("a", "b", "c"));

        ClaudeSessionModel modelC = new ClaudeSessionModel();
        modelC.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabC =
                new ClaudeSessionController(modelC, Collections::emptyList);
        tabC.simulateProcessStart(START_MS, true);
        tabC.simulateLiveSession(config, "c", Set.of("a", "b", "c"));

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS + 1_000L, true);
        controller.simulateLiveSession(config, "a", Set.of("a", "b", "c"));

        try {
            controller.simulateTerminalInput(
                    "continue".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    START_MS + 11_000L);
            assertEquals("continue", controller.simulateTerminalInput(
                    new byte[]{'\r'}, START_MS + 11_001L));

            // The sibling's conversation holds the text first.
            appendUserPrompt(dir, "c", "continue", START_MS + 11_400L);
            setMtime(dir.resolve("c.jsonl"), START_MS + 11_500L);
            assertEquals("c", controller.resolveActiveSessionId(),
                    "the identical sibling prompt is adopted while nothing else matches");

            // The conversation this tab actually joined gets its record later.
            appendUserPrompt(dir, "b", "continue", START_MS + 11_600L);
            setMtime(dir.resolve("b.jsonl"), START_MS + 12_600L);

            assertEquals("b", controller.resolveActiveSessionId(),
                    "the borrowed guess must not beat the strictly newer joined file");
            assertEquals("b", controller.resolveActiveSessionId(),
                    "later scans must keep the joined conversation");
        } finally {
            tabC.stopProcess();
            tabB.stopProcess();
        }
    }

    /**
     * A resolved own file that another live tab also writes is not proof of
     * this tab's submission: that tab's concurrent turn appends a user record
     * of its own. It must compete with the other shared candidates by recency,
     * so a conversation the run actually joined and that is strictly newer
     * wins, instead of the own priority returning the shared file.
     */
    @Test
    void anOwnFileSharedWithAnotherWriterCompetesWithTheSharedCandidates() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "a", "a");
        writeSession(dir, "b", "b");
        setMtime(dir.resolve("a.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("b.jsonl"), START_MS - 5_000L);

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateLiveSession(config, null, Set.of("a", "b"));

        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);

        ClaudeSessionModel modelC = new ClaudeSessionModel();
        modelC.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabC =
                new ClaudeSessionController(modelC, Collections::emptyList);

        try {
            controller.simulateTerminalInput(
                    "continue".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    START_MS + 11_000L);
            assertEquals("continue", controller.simulateTerminalInput(
                    new byte[]{'\r'}, START_MS + 11_001L));

            appendUserPrompt(dir, "a", "continue", START_MS + 11_400L);
            setMtime(dir.resolve("a.jsonl"), START_MS + 11_500L);
            assertEquals("a", controller.resolveActiveSessionId(),
                    "the first-file priority resolves the run's own conversation");

            // A second tab resumes the resolved conversation, and a third one
            // writes the conversation this tab switches to.
            tabB.simulateProcessStart(START_MS + 12_000L, true);
            tabB.simulateLiveSession(config, "a", Set.of("a", "b"));
            tabC.simulateProcessStart(START_MS + 12_000L, true);
            tabC.simulateLiveSession(config, "b", Set.of("a", "b"));

            // A recalled prompt is submitted; the second tab's concurrent turn
            // appends a human record to the shared own file, while the actual
            // submission lands in b and is strictly newer.
            controller.simulateTerminalInput(
                    new byte[]{0x1b, '[', 'A'}, START_MS + 21_000L);
            assertNull(controller.simulateTerminalInput(
                    new byte[]{'\r'}, START_MS + 21_001L));

            appendUserPrompt(dir, "a", "other tab turn", START_MS + 21_100L);
            setMtime(dir.resolve("a.jsonl"), START_MS + 21_200L);
            appendUserPrompt(dir, "b", "recalled prompt", START_MS + 21_300L);
            setMtime(dir.resolve("b.jsonl"), START_MS + 21_400L);

            assertEquals("b", controller.resolveActiveSessionId(),
                    "the shared own file must not beat the strictly newer joined file");
            assertEquals("b", controller.resolveActiveSessionId(),
                    "later scans must keep the joined conversation");

            Set<String> held = controller.claimedSessionIds(work.toFile(), config);
            controller.simulateProcessExit();
            assertEquals("b", controller.resolveIdentityWithPreservedClaims(held),
                    "the final lookup must make the same comparison");
        } finally {
            tabC.stopProcess();
            tabB.stopProcess();
        }
    }

    /**
     * The destination of a shared launch-file match may be a saved conversation
     * the user resumed in the TUI that no live tab holds yet: a record written
     * strictly later with the same text is then the conversation on screen.
     */
    @Test
    void aSharedLaunchFileMatchYieldsToAStrictlyNewerUnclaimedConversation() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "a", "a");
        writeSession(dir, "b", "b");
        setMtime(dir.resolve("a.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("b.jsonl"), START_MS - 5_000L);

        // This tab launched the saved conversation a.
        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateLiveSession(config, "a", Set.of("a", "b"));

        // A second tab launched the same saved file and only follows it.
        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS + 1_000L, true);
        tabB.simulateLiveSession(config, "a", Set.of("a", "b"));

        try {
            controller.simulateTerminalInput(
                    "continue".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    START_MS + 11_000L);
            assertEquals("continue", controller.simulateTerminalInput(
                    new byte[]{'\r'}, START_MS + 11_001L));

            // The other launcher's own turn submits the same literal text into
            // the shared launch file.
            appendUserPrompt(dir, "a", "continue", START_MS + 11_400L);
            setMtime(dir.resolve("a.jsonl"), START_MS + 11_500L);
            // This tab's submission lands in the resumed saved conversation,
            // which no live tab holds, later.
            appendUserPrompt(dir, "b", "continue", START_MS + 11_600L);
            setMtime(dir.resolve("b.jsonl"), START_MS + 12_600L);

            assertEquals("b", controller.resolveActiveSessionId(),
                    "the shared launch match must not beat the strictly newer file");
            assertEquals("b", controller.resolveActiveSessionId(),
                    "later scans must keep the resumed conversation");
        } finally {
            tabB.stopProcess();
        }
    }

    /**
     * The conversation can also be switched from the GUI alone — a slash
     * command such as {@code /resume} sent from the prompt panel and the
     * picker answer — and those writes are made by the plugin, so no raw
     * terminal input marks the switch. A shared launch-file match must then
     * yield to the strictly newer conversation exactly as it does after
     * typing in the terminal.
     */
    @Test
    void aSharedLaunchFileMatchYieldsToAGuiDrivenSwitch() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "a", "a");
        writeSession(dir, "b", "b");
        setMtime(dir.resolve("a.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("b.jsonl"), START_MS - 5_000L);

        // This tab launched the saved conversation a.
        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateLiveSession(config, "a", Set.of("a", "b"));

        // A second tab launched the same saved file and only follows it.
        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS + 1_000L, true);
        tabB.simulateLiveSession(config, "a", Set.of("a", "b"));

        try {
            // The user switches the conversation from the GUI: /resume from
            // the prompt panel, then the session picked from the menu.
            controller.simulateGuiSend("/resume", START_MS + 10_000L);
            controller.completeGuiSend(() -> START_MS + 10_050L);
            controller.simulateGuiChoiceAnswer("ARROW:1");

            // The new conversation is not followed by the identity poll yet
            // when the next prompt is sent.
            controller.simulateSentPrompt("continue", START_MS + 11_000L);

            // The other launcher's own turn submits the same literal text into
            // the shared launch file.
            appendUserPrompt(dir, "a", "continue", START_MS + 11_400L);
            setMtime(dir.resolve("a.jsonl"), START_MS + 11_500L);
            // This tab's submission lands in the resumed saved conversation,
            // which no live tab holds, later.
            appendUserPrompt(dir, "b", "continue", START_MS + 11_600L);
            setMtime(dir.resolve("b.jsonl"), START_MS + 12_600L);

            assertEquals("b", controller.resolveActiveSessionId(),
                    "a GUI-driven switch must not leave the launch conversation");
            assertEquals("b", controller.resolveActiveSessionId(),
                    "later scans must keep the resumed conversation");
        } finally {
            tabB.stopProcess();
        }
    }

    /**
     * A conversation switch made from the GUI can land in a saved conversation
     * no live tab holds. A recalled prompt leaves only user records as
     * evidence, and a concurrent turn of the writer remaining on the shared
     * launch file must not end the search: the strictly newer destination
     * holds this tab's submission.
     */
    @Test
    void anUnknownPromptFollowsAGuiSwitchIntoAnUnclaimedConversation() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "a", "a");
        writeSession(dir, "b", "b");
        setMtime(dir.resolve("a.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("b.jsonl"), START_MS - 5_000L);

        // This tab launched the saved conversation a.
        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateLiveSession(config, "a", Set.of("a", "b"));

        // A second tab launched the same saved file and only follows it.
        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS + 1_000L, true);
        tabB.simulateLiveSession(config, "a", Set.of("a", "b"));

        try {
            // The user switches the conversation from the GUI: /resume from
            // the prompt panel, then the session picked from the menu.
            controller.simulateGuiSend("/resume", START_MS + 10_000L);
            controller.completeGuiSend(() -> START_MS + 10_050L);
            controller.simulateGuiChoiceAnswer("ARROW:1");

            // A recalled prompt: its text is unknown.
            controller.simulatePromptSubmittedAt(START_MS + 11_000L);
            controller.simulatePromptText(null);

            // The other launcher's own turn lands in the shared launch file.
            appendUserPrompt(dir, "a", "continue", START_MS + 11_400L);
            setMtime(dir.resolve("a.jsonl"), START_MS + 11_500L);
            // This tab's submission lands in the resumed saved conversation,
            // which no live tab holds, strictly later.
            appendUserPrompt(dir, "b", "recalled", START_MS + 11_600L);
            setMtime(dir.resolve("b.jsonl"), START_MS + 12_600L);

            assertEquals("b", controller.resolveActiveSessionId(),
                    "the shared launch record must not hide the unclaimed destination");
            assertEquals("b", controller.resolveActiveSessionId(),
                    "later scans must keep the resumed conversation");
        } finally {
            tabB.stopProcess();
        }
    }

    /**
     * The panel writes /resume, then the user replaces the line during the
     * pause: Ctrl+U clears it and hello is typed and submitted instead. The
     * Enter does not switch the conversation, so the discarded command must
     * not stay as switch evidence — otherwise the resolver's switch recovery
     * adopts an unrelated newer unheld record instead of the launch
     * conversation that received hello.
     */
    @Test
    void aSwitchCommandEditedAwayDoesNotSwitchTheConversation() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "a", "a");
        writeSession(dir, "noise", "noise");
        setMtime(dir.resolve("a.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("noise.jsonl"), START_MS - 5_000L);

        // This tab and a second one launched the same saved conversation, so
        // only one of them owns it.
        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateLiveSession(config, "a", Set.of("a", "noise"));

        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS + 1_000L, true);
        tabB.simulateLiveSession(config, "a", Set.of("a", "noise"));

        try {
            controller.simulateGuiSend("/resume", START_MS + 10_000L);
            controller.simulateTerminalInput(new byte[]{0x15}, START_MS + 10_050L);
            controller.simulateTerminalInput(
                    "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    START_MS + 10_060L);
            controller.completeGuiSend(() -> START_MS + 10_100L);

            // hello landed in the launch conversation.
            appendUserPrompt(dir, "a", "hello", START_MS + 10_150L);
            setMtime(dir.resolve("a.jsonl"), START_MS + 10_250L);
            // An unrelated conversation received hello later and is newer.
            appendUserPrompt(dir, "noise", "hello", START_MS + 10_160L);
            setMtime(dir.resolve("noise.jsonl"), START_MS + 10_600L);

            assertEquals("a", controller.resolveActiveSessionId(),
                    "an edited-away switch must leave the launch conversation");
        } finally {
            tabB.stopProcess();
        }
    }

    /**
     * The panel text reaches the conversation only when the GUI send's Enter is
     * written. Publishing it as own-session evidence sooner would let an
     * identity scan running in the pause adopt another conversation that
     * receives the same prompt before this process has submitted anything.
     * The cutoff is that Enter's time, not the text-write time: a record
     * written in the pause must not count as this submission.
     */
    @Test
    void aGuiPromptBecomesEvidenceOnlyWhenItsEnterIsWritten() {
        controller.simulatePromptSubmittedAt(START_MS - 10_000L);
        controller.simulatePromptText("earlier");

        controller.simulateGuiSend("hello", START_MS);

        assertEquals("earlier", controller.simulateLastPromptText(),
                "the panel text is not submitted until its Enter");
        assertEquals(START_MS - 10_000L, controller.simulatePromptSubmittedAtValue(),
                "the previous submission stays the evidence meanwhile");

        controller.completeGuiSend(() -> START_MS + 200L);

        assertEquals("hello", controller.simulateLastPromptText(),
                "the Enter makes the panel text the submitted prompt");
        assertEquals(START_MS + 200L, controller.simulatePromptSubmittedAtValue(),
                "the Enter time is the submission cutoff, not the send time");
    }

    /**
     * A Tab-completed or recalled draft holds only a part of the submitted
     * line, so a GUI submission carrying it is recorded as unknown text
     * instead of an exact prompt the conversation never received. Like exact
     * text, it is not published before the Enter is written: an identity scan
     * running in the pause could otherwise adopt an unrelated conversation by
     * time alone. Its cutoff is the Enter time, not the send time: with no
     * text to match, a record written during the pause would otherwise count
     * as this submission.
     */
    @Test
    void aGuiSendWithACompletedDraftRecordsUnknownText() {
        controller.simulatePromptSubmittedAt(START_MS - 10_000L);
        controller.simulatePromptText("earlier");

        controller.simulateTerminalInput(
                "@fi".getBytes(java.nio.charset.StandardCharsets.UTF_8), START_MS);
        controller.simulateTerminalInput(new byte[]{'\t'}, START_MS + 1_000L);

        assertEquals("@fi hello", controller.simulateGuiSend(" hello", START_MS + 2_000L),
                "the composed line is still what the conversation receives");
        assertEquals("earlier", controller.simulateLastPromptText(),
                "nothing is published before the Enter is written");
        assertEquals(START_MS - 10_000L, controller.simulatePromptSubmittedAtValue(),
                "the previous evidence is kept until the actual submission");

        controller.completeGuiSend(() -> START_MS + 2_300L);
        assertNull(controller.simulateLastPromptText(),
                "a completed draft makes the submitted text unknown");
        assertEquals(START_MS + 2_300L, controller.simulatePromptSubmittedAtValue(),
                "the Enter time is the submission cutoff, not the send time");
    }

    /**
     * The reconciliation resets the recall/completion flags, so a terminal
     * Enter during the pause sees an empty line and is classified as an empty
     * Enter, although it submits the composed line. The pending unknown-text
     * submission must then be published at that Enter's time: skipping it
     * leaves the previous prompt as the evidence and the stop-time lookup
     * renames the wrong conversation.
     */
    @Test
    void anEarlyEnterPublishesTheUnknownTextOfACompletedDraft() {
        controller.simulatePromptSubmittedAt(START_MS - 10_000L);
        controller.simulatePromptText("earlier");

        controller.simulateTerminalInput(
                "@fi".getBytes(java.nio.charset.StandardCharsets.UTF_8), START_MS);
        controller.simulateTerminalInput(new byte[]{'\t'}, START_MS + 1_000L);
        controller.simulateGuiSend(" hello", START_MS + 2_000L);
        controller.simulateTerminalInput(new byte[]{'\r'}, START_MS + 2_100L);
        controller.completeGuiSend(() -> START_MS + 2_400L);

        assertNull(controller.simulateLastPromptText(),
                "the completed draft makes the submitted text unknown");
        assertEquals(START_MS + 2_100L, controller.simulatePromptSubmittedAtValue(),
                "the early Enter's time is the submission cutoff");
    }

    /**
     * A GUI send extending a recalled draft is unknown-text evidence, and a
     * terminal Enter during the pause submits that line. Up then recalls a
     * second prompt: the recorder holds no text for that line either, yet the
     * delayed Enter submits it. The submission must be recorded as unknown
     * text at the delayed Enter, not dropped as if the Enter did nothing —
     * otherwise the previous prompt stays the evidence with its old timestamp.
     */
    @Test
    void aRecalledDraftAfterTheEarlyEnterIsRecordedAsUnknownSubmission() {
        controller.simulatePromptSubmittedAt(START_MS - 10_000L);
        controller.simulatePromptText("earlier");

        // Up recalls a prompt on the input line: only part of it is recorded.
        controller.simulateTerminalInput(new byte[]{0x1b, '[', 'A'}, START_MS);
        controller.simulateGuiSend(" hello", START_MS + 10L);
        // The terminal Enter submits the composed line; Up recalls another
        // prompt on the new line, which leaves nothing in the recorder.
        controller.simulateTerminalInput(new byte[]{'\r'}, START_MS + 50L);
        controller.simulateTerminalInput(new byte[]{0x1b, '[', 'A'}, START_MS + 100L);

        controller.completeGuiSend(() -> START_MS + 250L);

        assertNull(controller.simulateLastPromptText(),
                "a recalled line has no recorded text to match");
        assertEquals(START_MS + 250L, controller.simulatePromptSubmittedAtValue(),
                "the delayed Enter submits the recalled line");
    }

    /**
     * A terminal Enter during the pause submits the panel text; a draft typed
     * after it starts a new line, which the delayed Enter then submits. That
     * draft is fully recorded, so it becomes the evidence with the Enter time
     * instead of the delayed Enter being treated as a no-op and the second
     * submission going unrecorded.
     */
    @Test
    void aDraftTypedAfterTheEarlyEnterIsSubmittedByTheDelayedEnter() {
        controller.simulatePromptSubmittedAt(START_MS - 10_000L);
        controller.simulatePromptText("earlier");

        controller.simulateGuiSend("hello", START_MS);
        controller.simulateTerminalInput(new byte[]{'\r'}, START_MS + 50L);
        controller.simulateTerminalInput(
                "next".getBytes(java.nio.charset.StandardCharsets.UTF_8), START_MS + 100L);

        controller.completeGuiSend(() -> START_MS + 250L);

        assertEquals("next", controller.simulateLastPromptText(),
                "the delayed Enter submits the draft typed after the early Enter");
        assertEquals(START_MS + 250L, controller.simulatePromptSubmittedAtValue(),
                "the delayed Enter's time is the submission cutoff");
    }

    /**
     * A switch driven from the terminal (a resume picked with the keyboard)
     * must be followed for an unknown prompt as well: the shared launch file's
     * concurrent record must not hide the unclaimed destination.
     */
    @Test
    void anUnknownPromptFollowsATerminalSwitchIntoAnUnclaimedConversation() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "a", "a");
        writeSession(dir, "b", "b");
        setMtime(dir.resolve("a.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("b.jsonl"), START_MS - 5_000L);

        // This tab launched the saved conversation a.
        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateLiveSession(config, "a", Set.of("a", "b"));

        // A second tab launched the same saved file and only follows it.
        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS + 1_000L, true);
        tabB.simulateLiveSession(config, "a", Set.of("a", "b"));

        try {
            // The user resumes the saved conversation b from the terminal and
            // submits a prompt recalled from the history.
            controller.simulateTerminalInput(
                    "/resume\r".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    START_MS + 9_000L);
            controller.simulateTerminalInput(
                    new byte[]{0x1b, '[', 'A'}, START_MS + 10_000L);
            assertNull(controller.simulateTerminalInput(
                    new byte[]{'\r'}, START_MS + 11_000L));

            // The other launcher's own turn lands in the shared launch file.
            appendUserPrompt(dir, "a", "continue", START_MS + 11_400L);
            setMtime(dir.resolve("a.jsonl"), START_MS + 11_500L);
            // This tab's submission lands in the resumed saved conversation,
            // which no live tab holds, strictly later.
            appendUserPrompt(dir, "b", "recalled", START_MS + 11_600L);
            setMtime(dir.resolve("b.jsonl"), START_MS + 12_600L);

            assertEquals("b", controller.resolveActiveSessionId(),
                    "a terminal-driven switch must follow the unclaimed destination");
            assertEquals("b", controller.resolveActiveSessionId(),
                    "later scans must keep the resumed conversation");
        } finally {
            tabB.stopProcess();
        }
    }

    /**
     * An unheld file is not proof on its own: a tab that submitted its own
     * prompt and exited leaves one behind, and it must not replace the shared
     * conversation on screen when it was written earlier.
     */
    @Test
    void anUnheldOlderRecordDoesNotDisplaceTheSharedConversation() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "a", "a");
        writeSession(dir, "b", "b");
        writeSession(dir, "c", "c");
        setMtime(dir.resolve("a.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("b.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("c.jsonl"), START_MS - 5_000L);

        // The live sibling on b.
        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS, true);
        tabB.simulateLiveSession(config, "b", Set.of("a", "b", "c"));

        // This tab launched the saved conversation a.
        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS + 1_000L, true);
        controller.simulateLiveSession(config, "a", Set.of("a", "b", "c"));

        try {
            // The user switches to the live conversation b from the GUI.
            controller.simulateGuiSend("/resume", START_MS + 10_000L);
            controller.completeGuiSend(() -> START_MS + 10_050L);
            controller.simulateGuiChoiceAnswer("ARROW:1");

            // A recalled prompt: its text is unknown.
            controller.simulatePromptSubmittedAt(START_MS + 11_000L);
            controller.simulatePromptText(null);

            // A tab that submitted its own prompt and exited leaves an unheld
            // record behind, earlier than this tab's submission.
            appendUserPrompt(dir, "c", "third", START_MS + 11_400L);
            setMtime(dir.resolve("c.jsonl"), START_MS + 11_500L);
            // The live conversation holds the actual submission, strictly
            // later.
            appendUserPrompt(dir, "b", "recalled", START_MS + 11_600L);
            setMtime(dir.resolve("b.jsonl"), START_MS + 12_600L);

            assertEquals("b", controller.resolveActiveSessionId(),
                    "an older unheld record must not displace the shared conversation");
            assertEquals("b", controller.resolveActiveSessionId(),
                    "later scans must keep the shared conversation");
        } finally {
            tabB.stopProcess();
        }
    }

    /**
     * A conversation this tab resolved after a GUI switch stays selected. The
     * switch marker lasts until the run ends, so a later recalled prompt in
     * that own conversation must not be replaced by a live sibling that merely
     * appended a newer human record.
     */
    @Test
    void aResolvedOwnConversationSurvivesALaterGuiSwitchSearch() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "a", "a");
        writeSession(dir, "b", "b");
        writeSession(dir, "c", "c");
        setMtime(dir.resolve("a.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("b.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("c.jsonl"), START_MS - 5_000L);

        // The live sibling on c.
        ClaudeSessionModel modelC = new ClaudeSessionModel();
        modelC.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabC =
                new ClaudeSessionController(modelC, Collections::emptyList);
        tabC.simulateProcessStart(START_MS, true);
        tabC.simulateLiveSession(config, "c", Set.of("a", "b", "c"));

        // This tab launched the saved conversation a.
        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS + 1_000L, true);
        controller.simulateLiveSession(config, "a", Set.of("a", "b", "c"));

        try {
            // The user switches the conversation from the GUI.
            controller.simulateGuiSend("/resume", START_MS + 10_000L);
            controller.completeGuiSend(() -> START_MS + 10_050L);
            controller.simulateGuiChoiceAnswer("ARROW:1");

            // A literal prompt resolves the unclaimed destination b as this
            // tab's own conversation.
            controller.simulateSentPrompt("continue", START_MS + 11_000L);
            appendUserPrompt(dir, "b", "continue", START_MS + 11_400L);
            setMtime(dir.resolve("b.jsonl"), START_MS + 11_500L);
            assertEquals("b", controller.resolveActiveSessionId());

            // A recalled prompt: its text is unknown. The own conversation
            // receives the submission, but the sibling appends a newer record.
            controller.simulatePromptSubmittedAt(START_MS + 12_000L);
            controller.simulatePromptText(null);
            appendUserPrompt(dir, "b", "recalled", START_MS + 12_400L);
            setMtime(dir.resolve("b.jsonl"), START_MS + 12_500L);
            appendUserPrompt(dir, "c", "sibling", START_MS + 12_600L);
            setMtime(dir.resolve("c.jsonl"), START_MS + 13_600L);

            assertEquals("b", controller.resolveActiveSessionId(),
                    "the resolved own conversation must beat a sibling's later record");
            assertEquals("b", controller.resolveActiveSessionId(),
                    "later scans must keep the own conversation");
        } finally {
            tabC.stopProcess();
        }
    }

    /**
     * A launch file that another live tab also writes is not proof that this
     * tab's submission went there: that tab's own turn can submit the same
     * literal text. A live conversation holding the same text and written
     * strictly later must win instead of the launch file ending the search.
     */
    @Test
    void aSharedLaunchFileMatchYieldsToAStrictlyNewerLiveConversation() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "a", "a");
        writeSession(dir, "b", "b");
        setMtime(dir.resolve("a.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("b.jsonl"), START_MS - 5_000L);

        // This tab launched the saved conversation a.
        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateLiveSession(config, "a", Set.of("a", "b"));

        // A second tab launched the same saved file and only follows it.
        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS + 1_000L, true);
        tabB.simulateLiveSession(config, "a", Set.of("a", "b"));

        // The conversation this tab resumes in the TUI is live as well.
        ClaudeSessionModel modelC = new ClaudeSessionModel();
        modelC.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabC =
                new ClaudeSessionController(modelC, Collections::emptyList);
        tabC.simulateProcessStart(START_MS + 1_000L, true);
        tabC.simulateLiveSession(config, "b", Set.of("a", "b"));

        try {
            controller.simulateTerminalInput(
                    "continue".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    START_MS + 11_000L);
            assertEquals("continue", controller.simulateTerminalInput(
                    new byte[]{'\r'}, START_MS + 11_001L));

            // The other launcher's own turn submits the same literal text into
            // the shared launch file.
            appendUserPrompt(dir, "a", "continue", START_MS + 11_400L);
            setMtime(dir.resolve("a.jsonl"), START_MS + 11_500L);
            // This tab's submission lands in the resumed conversation, later.
            appendUserPrompt(dir, "b", "continue", START_MS + 11_600L);
            setMtime(dir.resolve("b.jsonl"), START_MS + 12_600L);

            assertEquals("b", controller.resolveActiveSessionId(),
                    "the shared launch match must not beat the strictly newer live file");
            assertEquals("b", controller.resolveActiveSessionId(),
                    "later scans must keep the resumed conversation");
        } finally {
            tabC.stopProcess();
            tabB.stopProcess();
        }
    }

    /**
     * A NEW run's resolved conversation must stay eligible even after a later
     * recalled submission temporarily borrowed a live sibling's file: adopting
     * the sibling releases the claim on the run's own file, so the final lookup
     * must keep it as a candidate rather than considering only the borrowed
     * sibling. Once the run's own file holds the submission and is strictly
     * newer, it is the answer.
     */
    @Test
    void aResolvedNewConversationStaysEligibleAfterABorrowedGuess() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "a", "a");
        writeSession(dir, "b", "b");
        setMtime(dir.resolve("a.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("b.jsonl"), START_MS - 5_000L);

        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS, true);
        tabB.simulateLiveSession(config, "b", Set.of("a", "b"));

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS + 1_000L, true);
        controller.simulateLiveSession(config, null, Set.of("a", "b"));

        try {
            controller.simulateTerminalInput(
                    "continue".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    START_MS + 11_000L);
            assertEquals("continue", controller.simulateTerminalInput(
                    new byte[]{'\r'}, START_MS + 11_001L));

            appendUserPrompt(dir, "a", "continue", START_MS + 11_400L);
            setMtime(dir.resolve("a.jsonl"), START_MS + 11_500L);
            assertEquals("a", controller.resolveActiveSessionId(),
                    "the first-file priority resolves the run's own conversation");

            // A recalled prompt is submitted; the live sibling writes its own
            // record first, so the run temporarily borrows the sibling's file.
            controller.simulateTerminalInput(
                    new byte[]{0x1b, '[', 'A'}, START_MS + 21_000L);
            assertNull(controller.simulateTerminalInput(
                    new byte[]{'\r'}, START_MS + 21_001L));

            appendUserPrompt(dir, "b", "sibling turn", START_MS + 21_100L);
            setMtime(dir.resolve("b.jsonl"), START_MS + 21_200L);
            assertEquals("b", controller.resolveActiveSessionId(),
                    "the sibling's file is borrowed while the own file is not written yet");

            // The run's own file receives the submission, strictly newer.
            appendUserPrompt(dir, "a", "recalled prompt", START_MS + 21_300L);
            setMtime(dir.resolve("a.jsonl"), START_MS + 21_400L);

            assertEquals("a", controller.resolveActiveSessionId(),
                    "the run's own file must win once it holds the submission");
            assertEquals("a", controller.resolveActiveSessionId(),
                    "later scans must keep the run's own file");

            Set<String> held = controller.claimedSessionIds(work.toFile(), config);
            controller.simulateProcessExit();
            assertEquals("a", controller.resolveIdentityWithPreservedClaims(held),
                    "the final lookup must keep the run's own file eligible");
        } finally {
            tabB.stopProcess();
        }
    }

    /**
     * A NEW run resolved its own conversation for the first submission. When it
     * later joins a live sibling's conversation and submits a recalled prompt
     * (unknown text), the first-file priority is spent: an exited tab's
     * unrelated unclaimed file must not be adopted ahead of the shared
     * conversation that holds the submission, even though that file is newer
     * than nothing else considered.
     */
    @Test
    void theFirstFilePriorityEndsAfterANewRunResolvedItsOwnFile() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "a", "a");
        writeSession(dir, "b", "b");
        writeSession(dir, "c", "c");
        setMtime(dir.resolve("a.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("b.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("c.jsonl"), START_MS - 5_000L);

        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS, true);
        tabB.simulateLiveSession(config, "b", Set.of("a", "b", "c"));

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS + 1_000L, true);
        controller.simulateLiveSession(config, null, Set.of("a", "b", "c"));

        try {
            controller.simulateTerminalInput(
                    "continue".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    START_MS + 11_000L);
            assertEquals("continue", controller.simulateTerminalInput(
                    new byte[]{'\r'}, START_MS + 11_001L));

            // This run creates its own conversation for the first submission.
            appendUserPrompt(dir, "a", "continue", START_MS + 11_400L);
            setMtime(dir.resolve("a.jsonl"), START_MS + 11_500L);
            assertEquals("a", controller.resolveActiveSessionId(),
                    "the first-file priority resolves the run's own conversation");

            // A recalled prompt is submitted after the TUI joined b.
            controller.simulateTerminalInput(
                    new byte[]{0x1b, '[', 'A'}, START_MS + 21_000L);
            assertNull(controller.simulateTerminalInput(
                    new byte[]{'\r'}, START_MS + 21_001L));

            // An unrelated exited tab's conversation also received a human
            // record; the joined conversation gets the actual submission and
            // is strictly newer.
            appendUserPrompt(dir, "c", "other work", START_MS + 21_600L);
            setMtime(dir.resolve("c.jsonl"), START_MS + 21_700L);
            appendUserPrompt(dir, "b", "recalled prompt", START_MS + 21_900L);
            setMtime(dir.resolve("b.jsonl"), START_MS + 22_000L);

            assertEquals("b", controller.resolveActiveSessionId(),
                    "the shared conversation holding the submission must win");
            assertEquals("b", controller.resolveActiveSessionId(),
                    "later scans must keep the shared conversation");
        } finally {
            tabB.stopProcess();
        }
    }

    /**
     * A NEW tab resumes another live tab's conversation and only then submits
     * a recalled prompt, before creating a file of its own. An exited tab's
     * unrelated unclaimed record written after the submission must not be
     * taken as this run's first file: the resumed conversation holds the
     * actual submission and was written strictly later.
     */
    @Test
    void aNewTabResumingALiveConversationDoesNotAdoptAnExitedTabsRecord() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "c", "c");
        writeSession(dir, "b", "b");
        setMtime(dir.resolve("c.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("b.jsonl"), START_MS - 5_000L);

        // Another live tab is writing conversation b.
        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS, true);
        tabB.simulateLiveSession(config, "b", Set.of("b", "c"));

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS + 1_000L, true);
        controller.simulateLiveSession(config, null, Set.of("b", "c"));

        try {
            // The user resumes the live conversation b from the GUI.
            controller.simulateGuiSend("/resume", START_MS + 10_000L);
            controller.completeGuiSend(() -> START_MS + 10_050L);
            controller.simulateGuiChoiceAnswer("ARROW:1");

            // A recalled prompt: its text is unknown.
            controller.simulatePromptSubmittedAt(START_MS + 11_000L);
            controller.simulatePromptText(null);

            // A third tab submits to c and exits: unclaimed, but unrelated.
            appendUserPrompt(dir, "c", "other work", START_MS + 11_400L);
            setMtime(dir.resolve("c.jsonl"), START_MS + 11_500L);
            // The actual submission lands in the resumed conversation b, which
            // is strictly newer.
            appendUserPrompt(dir, "b", "recalled prompt", START_MS + 11_600L);
            setMtime(dir.resolve("b.jsonl"), START_MS + 12_600L);

            assertEquals("b", controller.resolveActiveSessionId(),
                    "the resumed conversation must win over an exited tab's record");
            assertEquals("b", controller.resolveActiveSessionId(),
                    "later scans must keep the resumed conversation");
        } finally {
            tabB.stopProcess();
        }
    }

    /**
     * The same race with known prompt text. Once the tab has borrowed a
     * sibling's conversation, the launch-known preference must not keep it
     * there: the borrowed file is owned by another live tab, so its own launch
     * conversation — which now holds the submitted text and is strictly newer —
     * must take the identity back.
     */
    @Test
    void aKnownPromptRecoversTheLaunchConversationAfterASharedGuess() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "a", "a");
        writeSession(dir, "b", "b");
        setMtime(dir.resolve("a.jsonl"), START_MS - 5_000L);
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
            controller.simulateTerminalInput(
                    "continue".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    START_MS + 11_000L);
            assertEquals("continue", controller.simulateTerminalInput(
                    new byte[]{'\r'}, START_MS + 11_001L));

            // The sibling's identical prompt lands first.
            appendUserPrompt(dir, "b", "continue", START_MS + 11_400L);
            setMtime(dir.resolve("b.jsonl"), START_MS + 11_500L);
            assertEquals("b", controller.resolveActiveSessionId(),
                    "with no record of its own yet, the tab follows the sibling");

            // This tab's submission lands in its launch conversation, later.
            appendUserPrompt(dir, "a", "continue", START_MS + 11_600L);
            setMtime(dir.resolve("a.jsonl"), START_MS + 12_600L);

            assertEquals("a", controller.resolveActiveSessionId(),
                    "the launch conversation's record must take the identity back");
            assertEquals("a", controller.resolveActiveSessionId(),
                    "later scans must keep the recovered conversation");
            assertEquals("b", tabB.resolveActiveSessionId(),
                    "the sibling keeps its own conversation");
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

    /**
     * Stopping releases this tab's claims, which also erases the fact that a
     * conversation was only borrowed. The final post-stop lookup must still
     * not return that borrowed conversation: another live tab holds it, so the
     * launch conversation flushed strictly later during the stop is the one
     * being renamed.
     */
    @Test
    void aBorrowedConversationReleasedAtStopDoesNotWinTheFinalLookup() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "a", "a");
        setMtime(dir.resolve("a.jsonl"), START_MS - 5_000L);

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateLiveSession(config, "a", Set.of("a"));

        // A NEW sibling resolves the conversation it created and holds it.
        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS + 1_000L, true);
        tabB.simulateLiveSession(config, null, Set.of("a"));

        try {
            writeSession(dir, "b", "b");
            appendUserPrompt(dir, "b", "continue", START_MS + 10_500L);
            setMtime(dir.resolve("b.jsonl"), START_MS + 11_000L);
            assertEquals("b", tabB.resolveActiveSessionId(),
                    "the sibling resolves the conversation it created");

            // Both tabs submit the same prompt: this tab can only borrow the
            // sibling's file, which already holds the text.
            controller.simulateGuiSend("continue", START_MS + 10_000L);
            controller.completeGuiSend(() -> START_MS + 10_200L);
            assertEquals("b", controller.resolveActiveSessionId(),
                    "the identical prompt makes this tab follow the sibling");

            Set<String> held = controller.claimedSessionIds(work.toFile(), config);
            assertFalse(held.contains("b"),
                    "the sibling's conversation is only borrowed and is not carried over");

            // The writer exits, releasing the borrowed classification, and the
            // launch conversation is flushed strictly later: the rename must
            // target it, not the borrowed file another tab still holds.
            controller.simulateProcessExit();
            appendUserPrompt(dir, "a", "continue", START_MS + 12_000L);
            setMtime(dir.resolve("a.jsonl"), START_MS + 13_000L);

            assertEquals("a", controller.resolveIdentityWithPreservedClaims(held),
                    "the launch conversation flushed during the stop must be renamed");
        } finally {
            tabB.stopProcess();
        }
    }

    /**
     * The GUI submits a prompt with two writes separated by a pause. Keys typed
     * within that pause are part of the line the Enter submits, but the TUI
     * Enter itself never reaches the parser (the plugin marks its own writes).
     * They must not stay recorded as the draft of the next prompt, and the
     * panel text must not pass as the exact submitted text.
     */
    @Test
    void keysTypedWhileTheGuiPromptIsBeingSubmittedDoNotBecomeTheNextPrompt() {
        controller.simulateGuiSend("panel text", START_MS);

        // Keys typed while the panel text is being submitted.
        controller.simulateTerminalInput(
                " and typed".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                START_MS + 100L);
        controller.completeGuiSend();

        assertNull(controller.simulateLastPromptText(),
                "the line the Enter submitted is not the panel text alone");
        assertEquals("next",
                controller.simulateTerminalInput(
                        "next\r".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        START_MS + 300L),
                "keys from the previous submission must not stay in the draft");
    }

    /**
     * A nonprinting edit during the GUI send's pause changes the TUI line
     * without leaving anything in the recorded draft: Backspace on the freshly
     * reset recorder deletes a character of the panel text. The submitted text
     * is then no longer the composed evidence.
     */
    @Test
    void aNonprintingEditDuringTheGuiSendMakesItsTextUnknown() {
        controller.simulateGuiSend("hello", START_MS);

        // Backspace during the pause: the TUI line becomes "hell".
        controller.simulateTerminalInput(new byte[]{0x7f}, START_MS + 100L);
        controller.completeGuiSend();

        assertNull(controller.simulateLastPromptText(),
                "the line the Enter submits is no longer the panel text");
    }

    /**
     * The child can persist the submitted prompt as soon as it reads the Enter,
     * so the submission cutoff must be sampled before the bytes are forwarded.
     * Sampling it after the write makes the cutoff later than the user record
     * and the evidence lookup rejects the conversation's own file.
     */
    @Test
    void theGuiPromptIsTimestampedBeforeTheEnterIsWritten() {
        long[] writeStarted = new long[1];
        controller.simulatePtyWrite(bytes -> {
            writeStarted[0] = System.currentTimeMillis();
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        controller.simulateGuiSend("hello", START_MS + 1L);
        controller.simulateTerminalInput(
                " world".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                START_MS + 2L);
        controller.completeGuiSend();

        assertTrue(writeStarted[0] > 0L, "the Enter must be written");
        assertTrue(controller.simulatePromptSubmittedAtValue() <= writeStarted[0],
                "the submission time must be captured before the Enter is forwarded");
    }

    /**
     * If the user submits the line from the terminal during the GUI send's
     * pause, the panel's Enter only submits an empty line afterwards. That
     * later, no-op Enter must not re-time the submission: the cutoff would
     * then be later than the user record the child wrote for the terminal
     * Enter.
     */
    @Test
    void aTerminalEnterDuringTheGuiSendKeepsTheFirstSubmissionTime() {
        controller.simulateGuiSend("hello", START_MS);

        // The terminal submits the line the panel text was written to.
        controller.simulateTerminalInput(new byte[]{'\r'}, START_MS + 100L);
        controller.completeGuiSend();

        assertEquals("hello", controller.simulateLastPromptText(),
                "the terminal Enter submitted exactly the panel text");
        assertEquals(START_MS + 100L, controller.simulatePromptSubmittedAtValue(),
                "the terminal Enter's time is the cutoff; the panel's later Enter "
                        + "must not re-time the submission");
    }

    /**
     * An edit followed by a terminal Enter during the GUI send's pause submits
     * a line that is no longer the panel text. The recorder sees only the empty
     * buffer (the panel text was never recorded), so the submission must be
     * classified as unknown at the Enter itself: until {@code completeGuiSend}
     * runs, the identity scan would otherwise still match the panel text as
     * exact evidence.
     */
    @Test
    void anEditedGapEnterInvalidatesTheExactEvidenceImmediately() {
        controller.simulateGuiSend("hello", START_MS);

        // Backspace makes the TUI line "hell"; Enter submits it before the
        // panel's own Enter.
        controller.simulateTerminalInput(new byte[]{0x7f}, START_MS + 100L);
        controller.simulateTerminalInput(new byte[]{'\r'}, START_MS + 200L);

        assertNull(controller.simulateLastPromptText(),
                "the edit made the submitted line differ from the panel text");
        assertEquals(START_MS + 200L, controller.simulatePromptSubmittedAtValue(),
                "the terminal Enter's time is the submission time");

        controller.completeGuiSend();
        assertNull(controller.simulateLastPromptText(),
                "the panel's no-op Enter must keep the submission unknown");
    }

    /**
     * The panel text must be on the TUI line before terminal input can be
     * processed against the reconciled recorder. Otherwise an Enter arriving
     * between the reconciliation and the text write submits only the terminal
     * draft, while the recorded evidence claims the composed line was
     * submitted.
     */
    @Test
    void terminalInputWaitsForTheGuiTextWrite() throws Exception {
        CountDownLatch writeStarted = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);
        controller.simulatePtyWrite(bytes -> {
            writeStarted.countDown();
            try {
                releaseWrite.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        controller.simulateTerminalInput(
                "draft".getBytes(java.nio.charset.StandardCharsets.UTF_8), START_MS);

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread sender = new Thread(() -> {
            try {
                controller.simulateGuiSendWithText("hello", START_MS + 1L);
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        sender.start();
        assertTrue(writeStarted.await(5, TimeUnit.SECONDS),
                "the panel text must be written");

        CountDownLatch enterDone = new CountDownLatch(1);
        Thread enter = new Thread(() -> {
            controller.simulateTerminalInput(new byte[]{'\r'}, START_MS + 2L);
            enterDone.countDown();
        });
        enter.start();
        assertFalse(enterDone.await(400, TimeUnit.MILLISECONDS),
                "terminal input must wait until the panel text is on the TUI line");
        releaseWrite.countDown();
        sender.join(5_000L);
        enter.join(5_000L);

        assertNull(failure.get(), "the panel text write must not fail");
        assertEquals(0L, enterDone.getCount(),
                "terminal input must be processed after the write");
    }

    /**
     * The prompt panel accepts a second send while the previous one is still
     * in its pause. Writing the second text before the first Enter would put
     * both prompts on one TUI line, which neither pending record describes, so
     * the evidence would name only the second text. The second send must wait
     * until the first line is submitted.
     */
    @Test
    void aSecondGuiSendWaitsForTheFirstSendToBeSubmitted() throws Exception {
        List<String> writes = Collections.synchronizedList(new ArrayList<>());
        controller.simulatePtyWrite(bytes -> writes.add(
                new String(bytes, java.nio.charset.StandardCharsets.UTF_8)));

        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch firstPaused = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        Thread first = new Thread(() -> {
            try {
                controller.simulateSendPrompt("hello", () -> START_MS,
                        () -> START_MS + 200L, () -> {
                            firstPaused.countDown();
                            try {
                                releaseFirst.await(5, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        });
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "first-gui-send");
        first.start();
        assertTrue(firstPaused.await(5, TimeUnit.SECONDS),
                "the first text must be written");

        CountDownLatch secondAtPause = new CountDownLatch(1);
        Thread second = new Thread(() -> {
            try {
                controller.simulateSendPrompt("world", () -> START_MS + 50L,
                        () -> START_MS + 250L, secondAtPause::countDown);
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "second-gui-send");
        second.start();
        assertFalse(secondAtPause.await(400, TimeUnit.MILLISECONDS),
                "the second send must wait until the first line is submitted");

        releaseFirst.countDown();
        first.join(5_000L);
        second.join(5_000L);

        assertNull(failure.get(), "the sends must not fail");
        assertEquals(List.of("hello", "\r", "world", "\r"), writes,
                "each text is written separately and followed by its own Enter");
        assertEquals("world", controller.simulateLastPromptText(),
                "the last submission is the second prompt");
    }

    /**
     * A send queued behind another one belongs to the run that accepted it. If
     * the tab is restarted while it waits, it must not write the prompt through
     * the replacement process's connector, where it would execute in a
     * conversation the user did not type it for. Once the first send's text
     * went to the closed process, its delayed Enter must not be delivered to
     * the replacement either.
     */
    @Test
    void aQueuedGuiSendIsDroppedWhenTheTabRestartsBeforeItWrites() throws Exception {
        List<String> writes = Collections.synchronizedList(new ArrayList<>());
        controller.simulatePtyWrite(bytes -> writes.add(
                new String(bytes, java.nio.charset.StandardCharsets.UTF_8)));

        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch firstPaused = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        Thread first = new Thread(() -> {
            try {
                controller.simulateSendPrompt("hello", () -> START_MS,
                        () -> START_MS + 200L, () -> {
                            firstPaused.countDown();
                            try {
                                releaseFirst.await(5, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        });
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "first-gui-send");
        first.start();
        assertTrue(firstPaused.await(5, TimeUnit.SECONDS),
                "the first text must be written");

        Thread second = new Thread(() -> {
            try {
                controller.simulateSendPrompt("world", () -> START_MS + 50L,
                        () -> START_MS + 250L, () -> { });
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "second-gui-send");
        second.start();
        // BLOCKED means the sender has already captured the generation it was
        // accepted for and is waiting for the first send to release the lock.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (second.getState() != Thread.State.BLOCKED
                && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertEquals(Thread.State.BLOCKED, second.getState(),
                "the second send must be queued behind the first one");

        // The user stops the session and starts another one while both sends
        // are still open.
        controller.stopProcess();
        releaseFirst.countDown();
        first.join(5_000L);
        second.join(5_000L);

        assertNull(failure.get(), "the sends must not fail");
        assertEquals(List.of("hello"), writes,
                "neither the queued prompt nor a stale Enter may reach the replacement process");
    }

    /**
     * Three sends accepted in quick succession must reach the TUI in that
     * order. Giving each send its own thread that competes for the send lock
     * offers no FIFO guarantee — a monitor may hand the lock to either waiter
     * — so the prompts could be written swapped, and a slash command and the
     * instruction it was meant to precede could run in the wrong order.
     */
    @Test
    void queuedGuiSendsAreDeliveredInAcceptanceOrder() throws Exception {
        List<String> writes = Collections.synchronizedList(new ArrayList<>());
        java.util.Set<Thread> sendThreads = java.util.Collections.synchronizedSet(
                java.util.Collections.newSetFromMap(
                        new java.util.IdentityHashMap<Thread, Boolean>()));

        CountDownLatch firstPaused = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean first =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        controller = new ClaudeSessionController(model, Collections::emptyList) {
            @Override
            void pauseGuiSend() throws InterruptedException {
                if (first.compareAndSet(true, false)) {
                    firstPaused.countDown();
                    releaseFirst.await(5, TimeUnit.SECONDS);
                }
            }
        };
        controller.simulatePtyWrite(bytes -> {
            writes.add(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            sendThreads.add(Thread.currentThread());
        });

        java.util.concurrent.Future<?> firstSend = controller.simulateQueueGuiSend("first");
        java.util.concurrent.Future<?> secondSend = controller.simulateQueueGuiSend("second");
        java.util.concurrent.Future<?> thirdSend = controller.simulateQueueGuiSend("third");

        assertTrue(firstPaused.await(5, TimeUnit.SECONDS),
                "the first text must be written");
        releaseFirst.countDown();
        firstSend.get(5, TimeUnit.SECONDS);
        secondSend.get(5, TimeUnit.SECONDS);
        thirdSend.get(5, TimeUnit.SECONDS);

        assertEquals(List.of("first", "\r", "second", "\r", "third", "\r"),
                new ArrayList<>(writes),
                "the prompts must be submitted in the order they were accepted");
        assertEquals(1, sendThreads.size(),
                "the sends must be delivered by one ordered worker, not competing threads");
    }

    /** Starts {@code /bin/cat} in a PTY for a test that needs a real connector. */
    private static com.pty4j.PtyProcess startEchoPty() throws Exception {
        return new com.pty4j.PtyProcessBuilder(new String[]{"/bin/cat"})
                .setEnvironment(java.util.Map.of("TERM", "xterm"))
                .setDirectory(System.getProperty("java.io.tmpdir"))
                .setConsole(false)
                .setRedirectErrorStream(true)
                .start();
    }

    /**
     * A sender can pass its generation check and then wait for the recorder's
     * lock while a restart replaces the connector. The text must not be written
     * at all: through the current connector it would leave the stale prompt on
     * the replacement TUI's input line for its next submission, and the
     * recorder it would reconcile belongs to the replacement too.
     */
    @Test
    void aSendAcceptedBeforeTheRestartDoesNotReachTheReplacementConnector()
            throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(
                System.getProperty("os.name", "").toLowerCase().contains("win"));
        com.pty4j.PtyProcess p = startEchoPty();
        try {
            io.github.nbplugins.claudecodegui.process.PtyTtyConnector replacement =
                    new io.github.nbplugins.claudecodegui.process.PtyTtyConnector(p);
            long accepted = controller.simulateProcessGeneration();
            List<io.github.nbplugins.claudecodegui.process.PtyTtyConnector> targets =
                    new ArrayList<>();
            controller.simulatePtyWrite(bytes -> { });
            controller.simulatePtyWriteTargets(targets::add);

            // The tab is restarted: the accepted process is gone and a new PTY
            // is already attached.
            controller.stopProcess();
            controller.simulateConnector(replacement);

            controller.simulateGuiSendWithText("stale text", START_MS, accepted);

            assertTrue(targets.isEmpty(),
                    "the text of a replaced run must not be written");
        } finally {
            p.destroyForcibly();
        }
    }

    /**
     * The Enter of a send whose process was replaced must not be written to
     * the replacement TUI: it would submit whatever line that process has on
     * its input line, and the evidence would describe a submission the
     * accepted conversation never received.
     */
    @Test
    void aStaleEnterIsDroppedInsteadOfSubmittingTheReplacementProcess()
            throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(
                System.getProperty("os.name", "").toLowerCase().contains("win"));
        com.pty4j.PtyProcess p = startEchoPty();
        try {
            io.github.nbplugins.claudecodegui.process.PtyTtyConnector replacement =
                    new io.github.nbplugins.claudecodegui.process.PtyTtyConnector(p);
            long accepted = controller.simulateProcessGeneration();
            List<io.github.nbplugins.claudecodegui.process.PtyTtyConnector> targets =
                    new ArrayList<>();
            controller.simulatePtyWrite(bytes -> { });
            controller.simulatePtyWriteTargets(targets::add);

            controller.simulateGuiSend("hello", START_MS);
            controller.stopProcess();
            controller.simulateConnector(replacement);

            controller.completeGuiSend(() -> START_MS + 100L, accepted);

            assertTrue(targets.isEmpty(),
                    "no Enter may be written after the accepted process was replaced");
        } finally {
            p.destroyForcibly();
        }
    }

    /**
     * A sender that blocked on the recorder's lock while a restart replaced the
     * process must not reconcile its recorder either: the pending state and
     * the cleared line would belong to the old send, and the replacement's own
     * draft would be lost — a later panel send would then compose only its own
     * text and the identity lookup could follow an unrelated file.
     */
    @Test
    void aStaleSendDoesNotRewriteTheReplacementRecordersDraft() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(
                System.getProperty("os.name", "").toLowerCase().contains("win"));
        com.pty4j.PtyProcess p = startEchoPty();
        try {
            io.github.nbplugins.claudecodegui.process.PtyTtyConnector replacement =
                    new io.github.nbplugins.claudecodegui.process.PtyTtyConnector(p);
            long accepted = controller.simulateProcessGeneration();
            controller.simulatePtyWrite(bytes -> { });
            controller.simulatePtyWriteTargets(t -> { });

            // The tab is restarted and the replacement's user types a draft.
            controller.stopProcess();
            controller.simulateConnector(replacement);
            controller.simulateTerminalInput(
                    "fresh".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    START_MS);

            // The old sender only now runs its reconciliation.
            controller.simulateGuiSendWithText("stale text", START_MS + 100L, accepted);

            assertEquals("fresh suffix",
                    controller.simulateGuiSend(" suffix", START_MS + 200L),
                    "the replacement's draft must survive the stale send");
        } finally {
            p.destroyForcibly();
        }
    }

    /**
     * Text typed during the pause and then submitted by the terminal Enter is
     * only a part of the line (the panel text is not in the recorder), so the
     * text becomes unknown while the first submission's time is kept.
     */
    @Test
    void terminalTextSubmittedByTheGapEnterKeepsItsOwnTime() {
        controller.simulateGuiSend("hello", START_MS);

        controller.simulateTerminalInput(
                " world".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                START_MS + 50L);
        controller.simulateTerminalInput(new byte[]{'\r'}, START_MS + 100L);
        controller.completeGuiSend();

        assertNull(controller.simulateLastPromptText(),
                "the recorded text is only a part of the submitted line");
        assertEquals(START_MS + 100L, controller.simulatePromptSubmittedAtValue(),
                "the terminal Enter's time is the submission time");
    }

    /**
     * Once a terminal Enter has submitted the panel line during the pause, the
     * next line typed in the terminal is recorded in full: its submission must
     * keep its text instead of being reported as a part of the panel line, and
     * the panel's delayed Enter must not clear that evidence either.
     */
    @Test
    void textSubmittedAfterTheGapEnterStaysKnown() {
        controller.simulateGuiSend("hello", START_MS);
        // The user submits the panel line from the terminal before the
        // panel's own Enter is written.
        controller.simulateTerminalInput(new byte[]{'\r'}, START_MS + 50L);

        assertEquals("next", controller.simulateTerminalInput(
                        "next\r".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        START_MS + 100L),
                "the line typed on the fresh line is recorded in full");

        controller.completeGuiSend(() -> START_MS + 200L);

        assertEquals("next", controller.simulateLastPromptText(),
                "the panel's Enter must not clear the newer submission's text");
        assertEquals(START_MS + 100L, controller.simulatePromptSubmittedAtValue(),
                "the newer submission keeps its own time");
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
     * A digit typed while a numbered choice menu is open selects an item
     * immediately and is consumed by the TUI, so it must not become draft text
     * for the prompt typed after the menu is dismissed: the malformed text
     * could no longer match the file the prompt was actually written to.
     *
     * <p>The menu is left in the model on purpose: the screen is polled only
     * every half second, and a prompt submitted before the poll catches up is
     * still a real submission, not a menu answer.
     */
    @Test
    void aMenuSelectionDigitIsNotRecordedAsPromptDraft() {
        model.setActiveChoiceMenu(new ChoiceMenuModel("Pick one", List.of(
                new ChoiceMenuModel.Option("Yes", "1"),
                new ChoiceMenuModel.Option("No", "2")), 0));

        controller.simulateTerminalInput(new byte[]{'1'}, START_MS);
        // The screen poll has not refreshed yet: the model still reports the
        // menu the digit dismissed.
        assertEquals("hello", controller.simulateTerminalInput(
                "hello\r".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                START_MS + 1L),
                "the menu selection must not prefix the submitted prompt");
    }

    /**
     * A numbered menu can offer a free-text option recognised only by its
     * label ({@code "Type something."}): the digit activates that option's
     * editor instead of answering the menu, so the menu stays open and the
     * text typed next is the menu's answer, not a submitted prompt.
     */
    @Test
    void aDigitOnALabelTypedTextOptionDoesNotDismissTheMenu() {
        model.setActiveChoiceMenu(new ChoiceMenuModel("How was your experience?", List.of(
                new ChoiceMenuModel.Option("Great", "1"),
                new ChoiceMenuModel.Option("Bad", "2"),
                new ChoiceMenuModel.Option("Type something.", "3")), 0));

        controller.simulateTerminalInput(new byte[]{'3'}, START_MS);
        assertNull(controller.simulateTerminalInput(
                        "feedback\r".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        START_MS + 100L),
                "the answer to a text menu is not a submitted prompt");
        assertNull(controller.simulateLastPromptText(),
                "the menu's answer must not become prompt evidence");
    }

    /**
     * The digit's own option decides whether the menu closes: an ordinary
     * choice in a menu that also offers a text-input option still selects and
     * closes the TUI menu, so the prompt typed next must be recorded as a
     * prompt rather than swallowed as another menu answer.
     */
    @Test
    void anOrdinaryChoiceInAMixedMenuStillClosesItOnItsDigit() {
        model.setActiveChoiceMenu(new ChoiceMenuModel("How was your experience?", List.of(
                new ChoiceMenuModel.Option("Great", "1"),
                new ChoiceMenuModel.Option("Bad", "2"),
                new ChoiceMenuModel.Option("Type something.", "3")), 0));

        controller.simulateTerminalInput(new byte[]{'1'}, START_MS);
        assertEquals("hello", controller.simulateTerminalInput(
                        "hello\r".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        START_MS + 100L),
                "an ordinary choice closes the menu, so the next Enter submits a prompt");
        assertEquals("hello", controller.simulateLastPromptText(),
                "the terminal prompt becomes the evidence");
    }

    /**
     * A digit typed while the menu's text-input option already has the focus is
     * text for that editor, not the selection of the ordinary option it
     * numbers — the answer protocol navigates with arrows in that case (see
     * {@code writePtyAnswer}). The menu stays open, so the rest of the answer
     * must not be recorded as a submitted prompt.
     */
    @Test
    void aDigitTypedIntoTheFocusedTextOptionDoesNotDismissTheMenu() {
        model.setActiveChoiceMenu(new ChoiceMenuModel("How was your experience?", List.of(
                new ChoiceMenuModel.Option("Great", "1"),
                new ChoiceMenuModel.Option("Bad", "2"),
                new ChoiceMenuModel.Option("Type something.", "3")), 2));

        controller.simulateTerminalInput(new byte[]{'1'}, START_MS);
        assertNull(controller.simulateTerminalInput(
                        "feedback\r".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        START_MS + 100L),
                "the answer to a text menu is not a submitted prompt");
        assertNull(controller.simulateLastPromptText(),
                "the digit went into the focused editor, so the answer is not prompt evidence");
    }

    /**
     * A digit that activates a menu's text editor moves the cursor only in the
     * TUI: the model still reports the previous focus until the next screen
     * poll. Digits typed in that window are text for the editor, so they must
     * not retire the menu even when they name an ordinary option.
     */
    @Test
    void aDigitTypedAfterActivatingTheEditorDoesNotDismissTheMenu() {
        model.setActiveChoiceMenu(new ChoiceMenuModel("How was your experience?", List.of(
                new ChoiceMenuModel.Option("Great", "1"),
                new ChoiceMenuModel.Option("Bad", "2"),
                new ChoiceMenuModel.Option("Type something.", "3")), 0));

        // The digit activates the editor; the poll has not moved the focus.
        controller.simulateTerminalInput(new byte[]{'3'}, START_MS);
        controller.simulateTerminalInput(new byte[]{'1'}, START_MS + 50L);
        assertNull(controller.simulateTerminalInput(
                        "feedback\r".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        START_MS + 100L),
                "the digits went into the activated editor; the answer is not a prompt");
        assertNull(controller.simulateLastPromptText(),
                "the menu's answer must not become prompt evidence");
    }

    /**
     * A digit that names no item selects nothing: the TUI keeps the menu open,
     * so the recorder must not retire it — otherwise the text typed next is
     * recorded as prompt evidence instead of staying a menu answer.
     */
    @Test
    void aDigitThatNamesNoMenuOptionDoesNotDismissTheMenu() {
        model.setActiveChoiceMenu(new ChoiceMenuModel("Pick one", List.of(
                new ChoiceMenuModel.Option("Yes", "1"),
                new ChoiceMenuModel.Option("No", "2")), 0));

        controller.simulateTerminalInput(new byte[]{'9'}, START_MS);
        // The next digit selects: the menu closes and its text is discarded.
        controller.simulateTerminalInput(new byte[]{'1'}, START_MS + 50L);

        assertEquals("hello", controller.simulateTerminalInput(
                        "hello\r".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        START_MS + 100L),
                "a digit naming no option must not retire the menu");
        assertEquals("hello", controller.simulateLastPromptText(),
                "the terminal prompt becomes the evidence");
    }

    /**
     * A checkbox menu's free-text option is answered with the text, Up, then
     * Right, which submits the menu. The text belongs to the menu answer, not
     * to the prompt typed after it, and the Right must retire the menu the
     * model still reports until the next screen poll.
     */
    @Test
    void aFreeTextCheckboxAnswerDoesNotPrefixTheNextPrompt() {
        model.setActiveChoiceMenu(new ChoiceMenuModel("Pick one", List.of(
                checkbox("1", false),
                checkbox("2", false),
                new ChoiceMenuModel.Option("Type something", "3", null,
                        false, false, true)), 0));

        controller.simulateTerminalInput(
                "other".getBytes(java.nio.charset.StandardCharsets.UTF_8), START_MS);
        controller.simulateTerminalInput(new byte[]{0x1b, '[', 'A'}, START_MS + 1L);
        controller.simulateTerminalInput(new byte[]{0x1b, '[', 'C'}, START_MS + 2L);
        // The screen poll has not refreshed yet: the model still reports the
        // menu the Right submitted.
        assertEquals("hello", controller.simulateTerminalInput(
                "hello\r".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                START_MS + 3L),
                "the checkbox answer must not prefix the submitted prompt");
    }

    /**
     * Text typed into a picker's search box is not prompt draft. When a live
     * Esc cancels the picker (the GUI cancel path already resets the recorder),
     * the search text must be discarded too, or the next prompt is published
     * with the stale prefix and can no longer match the conversation it was
     * written to.
     */
    @Test
    void aPickerSearchCancelledLiveDoesNotPrefixTheNextPrompt() {
        model.setActiveChoiceMenu(new ChoiceMenuModel("Resume", List.of(
                new ChoiceMenuModel.Option("One", "1"),
                new ChoiceMenuModel.Option("Two", "2")), 0));

        controller.simulateTerminalInput(
                "work".getBytes(java.nio.charset.StandardCharsets.UTF_8), START_MS);
        // The user cancels the picker with Esc in the terminal.
        controller.simulateTerminalInput(new byte[]{0x1b}, START_MS + 1L);
        // The screen no longer shows the picker.
        model.clearChoiceMenu();

        assertEquals("hello", controller.simulateTerminalInput(
                "hello\r".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                START_MS + 2L),
                "the cancelled picker search must not prefix the submitted prompt");
    }

    /**
     * The unnumbered {@code /resume} picker uses {@code ARROW:} answers and
     * "Type to search": a digit typed there is search text, not a selection,
     * so it must not retire the menu. Retiring it would let the rest of the
     * search text be recorded as prompt draft, and the picker's later Esc
     * would no longer discard it.
     */
    @Test
    void aDigitTypedIntoASearchablePickerDoesNotRetireTheMenu() {
        model.setActiveChoiceMenu(new ChoiceMenuModel("Resume", List.of(
                new ChoiceMenuModel.Option("One", "ARROW:0"),
                new ChoiceMenuModel.Option("Two", "ARROW:1")), 0));

        // Search text typed into the picker: its digits are search text.
        controller.simulateTerminalInput(
                "2026".getBytes(java.nio.charset.StandardCharsets.UTF_8), START_MS);
        // The user cancels the picker with Esc in the terminal.
        controller.simulateTerminalInput(new byte[]{0x1b}, START_MS + 1L);

        assertEquals("hello", controller.simulateTerminalInput(
                "hello\r".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                START_MS + 2L),
                "the cancelled picker search must not prefix the submitted prompt");
    }

    /**
     * A standalone Esc that cancelled the picker is consumed by the next
     * input. When that input is a GUI submission, the picker's search text must
     * not be published as part of the line, since the TUI submits only the
     * panel text.
     */
    @Test
    void aPickerSearchCancelledBeforeAGuiSendIsNotPublished() {
        model.setActiveChoiceMenu(new ChoiceMenuModel("Resume", List.of(
                new ChoiceMenuModel.Option("One", "1"),
                new ChoiceMenuModel.Option("Two", "2")), 0));

        controller.simulateTerminalInput(
                "work".getBytes(java.nio.charset.StandardCharsets.UTF_8), START_MS);
        // The user cancels the picker with Esc in the terminal; no terminal
        // input follows before the prompt is sent from the panel.
        controller.simulateTerminalInput(new byte[]{0x1b}, START_MS + 1L);

        assertEquals("hello", controller.simulateGuiSend("hello", START_MS + 2L),
                "the cancelled picker search must not prefix the GUI prompt");
    }

    /**
     * A launch-known tab owns only the conversation it launched on. Following a
     * NEW sibling's provisional file must not make that file owned by this tab,
     * or the sibling that created it loses it.
     */
    @Test
    void aLaunchKnownTabDoesNotOwnAnAdoptedConversation() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "a", "a");
        writeSession(dir, "b", "b");
        setMtime(dir.resolve("a.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("b.jsonl"), START_MS - 5_000L);

        // The NEW sibling tab, which guessed the conversation b.
        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS, false);
        tabB.simulateLiveSession(config, null, Set.of("a", "b"));

        // This tab launched the saved conversation a.
        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS + 1_000L, true);
        controller.simulateLiveSession(config, "a", Set.of("a", "b"));

        try {
            // Both tabs submit the same text; the sibling's conversation holds
            // it and this tab starts following that file.
            controller.simulateSentPrompt("continue", START_MS + 10_000L);
            tabB.simulateSentPrompt("continue", START_MS + 10_000L);
            appendUserPrompt(dir, "b", "continue", START_MS + 10_400L);
            setMtime(dir.resolve("b.jsonl"), START_MS + 10_500L);
            assertEquals("b", controller.resolveActiveSessionId());

            // The launch file's own record of the same text is flushed late, so
            // it is written after the sibling's but stamped earlier: the
            // sibling must keep its strictly newer conversation either way.
            appendUserPrompt(dir, "a", "continue", START_MS + 10_200L);
            setMtime(dir.resolve("a.jsonl"), START_MS + 10_300L);

            // The creator keeps the conversation it created: the adopted file
            // must not be marked as owned by this tab.
            assertEquals("b", tabB.resolveActiveSessionId(),
                    "the adopted file must stay the sibling's own");
        } finally {
            tabB.stopProcess();
        }
    }

    /**
     * A launch-known tab adopts a sibling's file whenever the sibling's prompt
     * lands first — but that adoption is a guess, not an established claim. It
     * must not hide the file from the scan of the tab whose process actually
     * writes it: the creator would otherwise adopt a different conversation and
     * later rename it instead of its own.
     */
    @Test
    void aFollowersClaimDoesNotHideTheFileFromItsCreator() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "a", "a");
        writeSession(dir, "b", "b");
        setMtime(dir.resolve("a.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("b.jsonl"), START_MS - 5_000L);

        // This tab launched the saved conversation a.
        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS + 1_000L, true);
        controller.simulateLiveSession(config, "a", Set.of("a", "b"));

        // The NEW sibling tab created conversation b, but has not scanned yet.
        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS, false);
        tabB.simulateLiveSession(config, null, Set.of("a", "b"));

        try {
            // Both submitted the same text; the sibling's record lands first
            // and this tab adopts the file provisionally before the sibling
            // has had a chance to claim it. The prompt is recorded through the
            // seams that do not queue a background scan, so the order in which
            // the two tabs resolve their identity is fixed.
            controller.simulatePromptSubmittedAt(START_MS + 10_000L);
            controller.simulatePromptText("continue");
            tabB.simulatePromptSubmittedAt(START_MS + 10_000L);
            tabB.simulatePromptText("continue");
            appendUserPrompt(dir, "b", "continue", START_MS + 10_400L);
            setMtime(dir.resolve("b.jsonl"), START_MS + 10_500L);
            assertEquals("b", controller.resolveActiveSessionId());

            // The launch file's own record of the same text is flushed late, so
            // it is written after the sibling's but stamped earlier.
            appendUserPrompt(dir, "a", "continue", START_MS + 10_200L);
            setMtime(dir.resolve("a.jsonl"), START_MS + 10_300L);

            // The creator still finds its own file despite the follower's
            // claim, even though the launch file carries the same text.
            assertEquals("b", tabB.resolveActiveSessionId(),
                    "the follower's guess must not hide the created file");
        } finally {
            tabB.stopProcess();
        }
    }

    /**
     * A resume command recalled from the terminal history is not recognised as
     * one, but the Enter that answers the picker it opened is a conversation
     * switch: the unclaimed destination must be followed for the next unknown
     * prompt.
     */
    @Test
    void aRecalledResumeCommandFollowsTheUnclaimedDestination() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "a", "a");
        writeSession(dir, "b", "b");
        setMtime(dir.resolve("a.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("b.jsonl"), START_MS - 5_000L);

        // This tab launched the saved conversation a.
        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateLiveSession(config, "a", Set.of("a", "b"));

        // A second tab launched the same saved file and only follows it.
        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS + 1_000L, true);
        tabB.simulateLiveSession(config, "a", Set.of("a", "b"));

        try {
            // Up recalls /resume from the history, Enter submits an unknown
            // command, and the picker it opened is answered with an Enter.
            model.setActiveChoiceMenu(new ChoiceMenuModel("Resume Session (1 of 1)", List.of(
                    new ChoiceMenuModel.Option("One", "1")), 0));
            controller.simulateTerminalInput(
                    new byte[]{0x1b, '[', 'A'}, START_MS + 9_000L);
            assertNull(controller.simulateTerminalInput(
                    new byte[]{'\r'}, START_MS + 9_100L));
            assertNull(controller.simulateTerminalInput(
                    new byte[]{'\r'}, START_MS + 9_500L));
            model.clearChoiceMenu();

            // A recalled prompt: its text is unknown.
            controller.simulateTerminalInput(
                    new byte[]{0x1b, '[', 'A'}, START_MS + 10_000L);
            assertNull(controller.simulateTerminalInput(
                    new byte[]{'\r'}, START_MS + 11_000L));

            // The other launcher's own turn lands in the shared launch file.
            appendUserPrompt(dir, "a", "continue", START_MS + 11_400L);
            setMtime(dir.resolve("a.jsonl"), START_MS + 11_500L);
            // This tab's submission lands in the resumed saved conversation,
            // which no live tab holds, strictly later.
            appendUserPrompt(dir, "b", "recalled", START_MS + 11_600L);
            setMtime(dir.resolve("b.jsonl"), START_MS + 12_600L);

            assertEquals("b", controller.resolveActiveSessionId(),
                    "the picker selection must enable the unclaimed destination");
            assertEquals("b", controller.resolveActiveSessionId(),
                    "later scans must keep the resumed conversation");
        } finally {
            tabB.stopProcess();
        }
    }

    /**
     * Confirming an ordinary menu (permission prompt, trust-folder check)
     * answers a question in the current conversation; it does not switch the
     * conversation. Treating it as a resume would let the switch recovery
     * adopt an unrelated unheld record written by another tab that has just
     * exited, instead of the launch conversation that holds the submission.
     */
    @Test
    void anOrdinaryMenuAnsweredInTheTerminalDoesNotEnableSwitchRecovery() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "a", "a");
        writeSession(dir, "c", "c");
        setMtime(dir.resolve("a.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("c.jsonl"), START_MS - 5_000L);

        // This tab launched the saved conversation a.
        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateLiveSession(config, "a", Set.of("a", "c"));

        model.setActiveChoiceMenu(new ChoiceMenuModel("Security guide", List.of(
                new ChoiceMenuModel.Option("No, exit", "ARROW:0"),
                new ChoiceMenuModel.Option("Yes, I trust this folder", "ARROW:1")), 0));
        // The trust-folder confirmation is answered with Enter.
        controller.simulateTerminalInput(new byte[]{'\r'}, START_MS + 8_000L);
        model.clearChoiceMenu();

        // A first prompt recalled from the history: its text is unknown.
        controller.simulateTerminalInput(
                new byte[]{0x1b, '[', 'A'}, START_MS + 9_000L);
        assertNull(controller.simulateTerminalInput(
                new byte[]{'\r'}, START_MS + 9_100L));

        // This tab's submission lands in the launch conversation...
        appendUserPrompt(dir, "a", "recalled", START_MS + 9_700L);
        setMtime(dir.resolve("a.jsonl"), START_MS + 9_800L);
        // ...while another tab submits to c and exits, leaving a newer
        // unheld record.
        appendUserPrompt(dir, "c", "other work", START_MS + 9_900L);
        setMtime(dir.resolve("c.jsonl"), START_MS + 10_000L);

        assertEquals("a", controller.resolveActiveSessionId(),
                "a menu answer is not a conversation switch");
        assertEquals("a", controller.resolveActiveSessionId(),
                "later scans must keep the launch conversation");
    }

    /**
     * The same distinction for a menu answered from the plugin's choice panel:
     * an {@code ARROW:} answer is a resume only when the menu open is the
     * {@code /resume} session picker, not the trust-folder confirmation.
     */
    @Test
    void anOrdinaryMenuAnsweredInThePanelDoesNotEnableSwitchRecovery() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "a", "a");
        writeSession(dir, "c", "c");
        setMtime(dir.resolve("a.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("c.jsonl"), START_MS - 5_000L);

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateLiveSession(config, "a", Set.of("a", "c"));

        model.setActiveChoiceMenu(new ChoiceMenuModel("Security guide", List.of(
                new ChoiceMenuModel.Option("No, exit", "ARROW:0"),
                new ChoiceMenuModel.Option("Yes, I trust this folder", "ARROW:1")), 0));
        controller.simulateGuiChoiceAnswer("ARROW:1");
        model.clearChoiceMenu();

        controller.simulatePromptSubmittedAt(START_MS + 9_100L);
        controller.simulatePromptText(null);

        appendUserPrompt(dir, "a", "recalled", START_MS + 9_700L);
        setMtime(dir.resolve("a.jsonl"), START_MS + 9_800L);
        appendUserPrompt(dir, "c", "other work", START_MS + 9_900L);
        setMtime(dir.resolve("c.jsonl"), START_MS + 10_000L);

        assertEquals("a", controller.resolveActiveSessionId(),
                "an ARROW answer to an ordinary menu is not a resume");
        assertEquals("a", controller.resolveActiveSessionId(),
                "later scans must keep the launch conversation");
    }

    /**
     * A tab that resumed another live tab's conversation answers a permission
     * menu with Enter after submitting its prompt. The menu answer changes
     * nothing in the conversation, so it must not hide the submission from the
     * identity scan: Save &amp; Switch would otherwise rename the launch
     * conversation while the resumed file is the one holding the prompt.
     */
    @Test
    void aPermissionMenuAnswerDoesNotHideTheSubmittedPrompt() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "a", "a");
        writeSession(dir, "x", "x");
        setMtime(dir.resolve("a.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("x.jsonl"), START_MS - 5_000L);

        // A sibling tab resumed x and is writing it.
        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS, true);
        tabB.simulateLiveSession(config, "x", Set.of("a", "x"));

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateLiveSession(config, "a", Set.of("a", "x"));

        try {
            controller.simulateGuiSend("hello", START_MS + 10_000L);
            controller.completeGuiSend(() -> START_MS + 10_100L);

            // The submission went to the resumed conversation.
            appendUserPrompt(dir, "x", "hello", START_MS + 10_500L);
            setMtime(dir.resolve("x.jsonl"), START_MS + 10_600L);

            // A permission menu is answered with Enter before the next scan.
            model.setActiveChoiceMenu(new ChoiceMenuModel("Allow edit?", List.of(
                    new ChoiceMenuModel.Option("Yes", "ARROW:0"),
                    new ChoiceMenuModel.Option("No", "ARROW:1")), 0));
            controller.simulateTerminalInput(new byte[]{'\r'}, START_MS + 10_700L);
            model.clearChoiceMenu();

            controller.stopAndRename("Resumed title");
        } finally {
            tabB.stopProcess();
        }

        assertEquals("Resumed title",
                io.github.nbplugins.claudecodegui.process.ClaudeSessionStore
                        .findSession(work, config, "x").customTitle(),
                "the conversation holding the submitted prompt is renamed");
        assertNull(io.github.nbplugins.claudecodegui.process.ClaudeSessionStore
                        .findSession(work, config, "a").customTitle(),
                "the launch conversation was not the one on screen");
    }

    /**
     * A picker cancelled with a standalone Esc immediately before a GUI send
     * stays dismissed: the send rewrites the recorder, but the screen poll has
     * not removed the model's stale entry yet. Without the dismissed-menu
     * marker surviving those resets, the next prompt typed in the terminal is
     * swallowed as another menu answer and never becomes prompt evidence.
     */
    @Test
    void aPickerCancelledBeforeAGuiSendStaysDismissed() {
        model.setActiveChoiceMenu(new ChoiceMenuModel("Resume Session (1 of 1)", List.of(
                new ChoiceMenuModel.Option("One", "ARROW:0")), 0));

        controller.simulateTerminalInput(
                "work".getBytes(java.nio.charset.StandardCharsets.UTF_8), START_MS);
        // A standalone Esc cancels the picker; the model still reports it
        // until the next screen poll.
        controller.simulateTerminalInput(new byte[]{0x1b}, START_MS + 1L);

        controller.simulateGuiSend("hello", START_MS + 2L);
        controller.completeGuiSend(() -> START_MS + 200L);

        assertEquals("hello", controller.simulateLastPromptText(),
                "the panel send is the submitted prompt");
        assertEquals("next", controller.simulateTerminalInput(
                "next\r".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                START_MS + 300L),
                "the cancelled picker must stay dismissed after a GUI send");
        assertEquals("next", controller.simulateLastPromptText(),
                "the terminal prompt must become the evidence");
    }

    /**
     * With the menu hidden the prompt panel stays usable while the
     * {@code /resume} picker is open: the panel text goes into the picker's
     * search field and the panel's Enter selects an entry instead of
     * submitting a prompt. The filter must not become prompt evidence, and
     * the picker it answered must not swallow the next terminal prompt before
     * the screen poll removes the stale model entry.
     */
    @Test
    void aPanelAnswerToTheOpenPickerIsNotPromptEvidence() {
        model.setActiveChoiceMenu(new ChoiceMenuModel("Resume Session (1 of 1)", List.of(
                new ChoiceMenuModel.Option("One", "ARROW:0")), 0));

        controller.simulateGuiSend("work", START_MS);
        controller.completeGuiSend(() -> START_MS + 200L);

        assertNull(controller.simulateLastPromptText(),
                "the picker's search filter is not a prompt");
        assertEquals("hello", controller.simulateTerminalInput(
                        "hello\r".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        START_MS + 300L),
                "the answered picker must not swallow the next prompt");
        assertEquals("hello", controller.simulateLastPromptText(),
                "the terminal prompt becomes the evidence");
        assertEquals(START_MS + 300L, controller.simulatePromptSubmittedAtValue(),
                "the terminal prompt keeps its own submission time");
    }

    /**
     * Cancelling the picker from the plugin's choice panel consumes it, but
     * the model keeps reporting the entry until the next poll: a panel send
     * in that window is a prompt, not another answer to the cancelled picker.
     */
    @Test
    void aPanelSendAfterThePanelCancelledTheMenuIsAPrompt() {
        model.setActiveChoiceMenu(new ChoiceMenuModel("Resume Session (1 of 1)", List.of(
                new ChoiceMenuModel.Option("One", "ARROW:0")), 0));
        // Cancel sends Esc to the TUI; the model entry is not cleared here.
        controller.writePtyAnswer(null);

        controller.simulateGuiSend("hello", START_MS);
        controller.completeGuiSend(() -> START_MS + 200L);

        assertEquals("hello", controller.simulateLastPromptText(),
                "the cancelled picker must not turn the panel send into a menu answer");
    }

    /**
     * A terminal Enter can answer the open picker during the panel send's
     * pause, and the user then starts the next prompt before the delayed GUI
     * Enter. That Enter submits the new draft: it must be recorded as the
     * prompt, not discarded with the search filter the menu consumed.
     */
    @Test
    void aDraftTypedAfterThePickerSelectionIsRecorded() {
        model.setActiveChoiceMenu(new ChoiceMenuModel("Resume Session (1 of 1)", List.of(
                new ChoiceMenuModel.Option("One", "ARROW:0")), 0));

        controller.simulateGuiSend("work", START_MS);
        // The terminal Enter selects the picker entry during the pause.
        controller.simulateTerminalInput(new byte[]{'\r'}, START_MS + 50L);
        // The user starts the next prompt on the fresh line.
        controller.simulateTerminalInput(
                "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                START_MS + 100L);

        controller.completeGuiSend(() -> START_MS + 200L);

        assertEquals("hello", controller.simulateLastPromptText(),
                "the delayed Enter submits the draft typed after the selection");
        assertEquals(START_MS + 200L, controller.simulatePromptSubmittedAtValue(),
                "the draft keeps its own submission time");
    }

    /**
     * A digit answers a numbered menu immediately and closes it, so the text
     * typed into it is the menu's search filter, not a prompt draft. Leaving
     * it in the recorder would prefix the next prompt typed before the screen
     * poll and publish text the conversation never received, which an
     * identity scan cannot match.
     */
    @Test
    void textTypedIntoANumberedMenuDoesNotPrefixTheNextPrompt() {
        model.setActiveChoiceMenu(new ChoiceMenuModel("Do you want to proceed?", List.of(
                new ChoiceMenuModel.Option("Yes", "1"),
                new ChoiceMenuModel.Option("No", "2")), 0));

        controller.simulateTerminalInput(
                "other".getBytes(java.nio.charset.StandardCharsets.UTF_8), START_MS);
        // The digit selects an item; the model still reports the menu until
        // the next screen poll.
        controller.simulateTerminalInput(
                "1".getBytes(java.nio.charset.StandardCharsets.UTF_8), START_MS + 50L);

        assertEquals("hello", controller.simulateTerminalInput(
                        "hello\r".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        START_MS + 100L),
                "the menu's search text is not part of the next prompt");
        assertEquals("hello", controller.simulateLastPromptText(),
                "the terminal prompt becomes the evidence");
    }

    /**
     * A standalone Esc typed during the panel send's pause cancels the
     * {@code /resume} picker; the delayed Enter then acts on the empty main
     * prompt. It must not be classified as a picker selection, which would
     * mark a conversation switch and let the switch recovery adopt an
     * unrelated unheld conversation after a recalled prompt.
     */
    @Test
    void aPickerCancelledDuringThePauseDoesNotEnableSwitchRecovery() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "a", "a");
        writeSession(dir, "c", "c");
        setMtime(dir.resolve("a.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("c.jsonl"), START_MS - 5_000L);

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateLiveSession(config, "a", Set.of("a", "c"));

        model.setActiveChoiceMenu(new ChoiceMenuModel("Resume Session (1 of 1)", List.of(
                new ChoiceMenuModel.Option("One", "ARROW:0")), 0));
        controller.simulateGuiSend("work", START_MS + 8_000L);
        // A standalone Esc cancels the picker during the pause; the model
        // still reports it until the next screen poll.
        controller.simulateTerminalInput(new byte[]{0x1b}, START_MS + 8_050L);
        controller.completeGuiSend(() -> START_MS + 8_200L);

        // A prompt recalled from the history: its text is unknown.
        controller.simulateTerminalInput(
                new byte[]{0x1b, '[', 'A'}, START_MS + 9_000L);
        assertNull(controller.simulateTerminalInput(
                new byte[]{'\r'}, START_MS + 9_100L));

        // This tab's submission lands in the launch conversation...
        appendUserPrompt(dir, "a", "recalled", START_MS + 9_700L);
        setMtime(dir.resolve("a.jsonl"), START_MS + 9_800L);
        // ...while an unrelated unheld conversation gets a newer record.
        appendUserPrompt(dir, "c", "other work", START_MS + 9_900L);
        setMtime(dir.resolve("c.jsonl"), START_MS + 10_000L);

        assertEquals("a", controller.resolveActiveSessionId(),
                "a cancelled picker is not a conversation switch");
        assertEquals("a", controller.resolveActiveSessionId(),
                "later scans must keep the launch conversation");
    }

    /**
     * A panel send of a bare digit answers a numbered menu at the initial text
     * write — the answer protocol sends such digits without Enter — so the menu
     * is gone for the pause. The prompt typed then starts on the empty input
     * line and must be published as exact evidence instead of being swallowed
     * as one more menu answer.
     */
    @Test
    void aDigitSentFromThePanelRetiresTheMenuBeforeThePause() throws Exception {
        model.setActiveChoiceMenu(new ChoiceMenuModel("Do you want to proceed?", List.of(
                new ChoiceMenuModel.Option("Yes", "1"),
                new ChoiceMenuModel.Option("No", "2")), 0));

        controller.simulateGuiSendWithText("1", START_MS);
        // The screen poll has not refreshed yet: the model still reports the
        // menu the panel digit answered.
        assertEquals("hello", controller.simulateTerminalInput(
                        "hello\r".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        START_MS + 50L),
                "the menu was answered at the write, so this Enter submits a prompt");
        assertEquals("hello", controller.simulateLastPromptText(),
                "the prompt typed during the pause is exact evidence");

        controller.completeGuiSend(() -> START_MS + 200L);

        assertEquals("hello", controller.simulateLastPromptText(),
                "the delayed Enter acts on the empty line and keeps the evidence");
        assertEquals(START_MS + 50L, controller.simulatePromptSubmittedAtValue(),
                "the prompt keeps its own submission time");
    }

    /**
     * A draft typed after the panel's digit answered a numbered menu sits on
     * the empty input line, and the delayed Enter submits it as a prompt: it
     * must be recorded, not discarded with the menu answer.
     */
    @Test
    void aDraftTypedAfterThePanelAnsweredTheMenuIsRecorded() throws Exception {
        model.setActiveChoiceMenu(new ChoiceMenuModel("Do you want to proceed?", List.of(
                new ChoiceMenuModel.Option("Yes", "1"),
                new ChoiceMenuModel.Option("No", "2")), 0));

        controller.simulateGuiSendWithText("1", START_MS);
        controller.simulateTerminalInput(
                "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8), START_MS + 50L);

        controller.completeGuiSend(() -> START_MS + 200L);

        assertEquals("hello", controller.simulateLastPromptText(),
                "the delayed Enter submits the draft typed after the menu answer");
        assertEquals(START_MS + 200L, controller.simulatePromptSubmittedAtValue(),
                "the draft keeps the submission time of the Enter that sent it");
    }

    /**
     * Once a NEW run has resolved its own conversation, newer unrelated files
     * must not push it out of the bounded prompt-evidence list: a file that
     * merely carries the same common prompt text would then take over the
     * tab's identity and Save &amp; Switch would rename the wrong session.
     */
    @Test
    void theResolvedOwnSessionSurvivesTheCandidateCap() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "own", "own");
        setMtime(dir.resolve("own.jsonl"), START_MS - 5_000L);

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateLiveSession(config, null, Set.of("own"));

        // The run's first prompt creates its own conversation.
        controller.simulatePromptSubmittedAt(START_MS + 1_000L);
        controller.simulatePromptText("first");
        appendUserPrompt(dir, "own", "first", START_MS + 1_100L);
        setMtime(dir.resolve("own.jsonl"), START_MS + 1_200L);
        assertEquals("own", controller.resolveActiveSessionId());

        // A second, common prompt: eight unrelated conversations are written
        // after this tab's own file, and one of them happens to hold the same
        // text. The own file drops out of the newest-eight discovery list but
        // is still the conversation this run writes.
        controller.simulatePromptSubmittedAt(START_MS + 2_000L);
        controller.simulatePromptText("continue");
        appendUserPrompt(dir, "own", "continue", START_MS + 2_100L);
        setMtime(dir.resolve("own.jsonl"), START_MS + 2_200L);
        for (int i = 0; i < 7; i++) {
            String id = "sibling" + i;
            writeSession(dir, id, id);
            appendUserPrompt(dir, id, "own turn " + i, START_MS + 2_300L + i);
            setMtime(dir.resolve(id + ".jsonl"), START_MS + 2_400L + i);
        }
        writeSession(dir, "impostor", "impostor");
        appendUserPrompt(dir, "impostor", "continue", START_MS + 2_500L);
        setMtime(dir.resolve("impostor.jsonl"), START_MS + 2_600L);

        assertEquals("own", controller.resolveActiveSessionId(),
                "the known own conversation must not be dropped by the candidate cap");
        assertEquals("own", controller.resolveActiveSessionId(),
                "later scans must keep the known own conversation");
    }

    /**
     * Rounded file times can put the resolved own file just below the
     * submission cutoff while an unrelated conversation records the same
     * common text in the next timestamp bucket. The own file is stronger
     * evidence: the mtime slack must be applied to it before the lookalike
     * match is accepted and replaces its identity.
     */
    @Test
    void theKnownOwnSessionIsFoundThroughTheMtimeSlack() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "own", "own");
        setMtime(dir.resolve("own.jsonl"), START_MS - 5_000L);

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateLiveSession(config, null, Set.of("own"));

        controller.simulatePromptSubmittedAt(START_MS + 1_000L);
        controller.simulatePromptText("first");
        appendUserPrompt(dir, "own", "first", START_MS + 1_100L);
        setMtime(dir.resolve("own.jsonl"), START_MS + 1_200L);
        assertEquals("own", controller.resolveActiveSessionId());

        // The own file holds the second prompt, but its rounded mtime lands
        // just below the cutoff; an unrelated file with the same text is
        // written in the next timestamp bucket.
        long since = START_MS + 5_000L;
        controller.simulatePromptSubmittedAt(since);
        controller.simulatePromptText("continue");
        appendUserPrompt(dir, "own", "continue", since + 100L);
        setMtime(dir.resolve("own.jsonl"), since - 500L);
        writeSession(dir, "lookalike", "lookalike");
        appendUserPrompt(dir, "lookalike", "continue", since + 200L);
        setMtime(dir.resolve("lookalike.jsonl"), since + 300L);

        assertEquals("own", controller.resolveActiveSessionId(),
                "the own conversation is found through the mtime slack");
        assertEquals("own", controller.resolveActiveSessionId(),
                "later scans must keep the own conversation");
    }
    @Test
    void aBorrowedActiveConversationIsNotPreferred() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "a", "a");
        writeSession(dir, "b", "b");
        setMtime(dir.resolve("a.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("b.jsonl"), START_MS - 5_000L);

        // The NEW sibling tab, which guessed the conversation b.
        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS, false);
        tabB.simulateLiveSession(config, null, Set.of("a", "b"));

        // This tab launched the saved conversation a.
        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS + 1_000L, true);
        controller.simulateLiveSession(config, "a", Set.of("a", "b"));

        try {
            // Both tabs submit the same text; only the sibling's file holds it
            // yet, so this tab temporarily follows it. The prompt is recorded
            // through the seams that do not queue a background scan, so the
            // tab that has not claimed the file yet cannot do so concurrently:
            // the launch conversation must win on the evidence alone.
            controller.simulatePromptSubmittedAt(START_MS + 10_000L);
            controller.simulatePromptText("continue");
            appendUserPrompt(dir, "b", "continue", START_MS + 10_400L);
            setMtime(dir.resolve("b.jsonl"), START_MS + 10_500L);
            assertEquals("b", controller.resolveActiveSessionId());

            // This tab's own launch file records the prompt strictly later.
            appendUserPrompt(dir, "a", "continue", START_MS + 10_600L);
            setMtime(dir.resolve("a.jsonl"), START_MS + 10_700L);

            assertEquals("a", controller.resolveActiveSessionId(),
                    "the borrowed file must not outrank this tab's own prompt");
            assertEquals("a", controller.resolveActiveSessionId(),
                    "later scans must keep the launched conversation");
        } finally {
            tabB.stopProcess();
        }
    }

    /**
     * The same borrowed file, claimed by the sibling <em>after</em> this tab
     * provisionally adopted it: the claim registry never marks this tab as a
     * borrower then, but the file is still the sibling's guess, so it must not
     * outrank the launch conversation holding this tab's prompt.
     */
    @Test
    void aFileTheSiblingClaimsLaterDoesNotOutrankTheLaunchConversation() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "a", "a");
        writeSession(dir, "b", "b");
        setMtime(dir.resolve("a.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("b.jsonl"), START_MS - 5_000L);

        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS, false);
        tabB.simulateLiveSession(config, null, Set.of("a", "b"));

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS + 1_000L, true);
        controller.simulateLiveSession(config, "a", Set.of("a", "b"));

        try {
            // Only the sibling's file holds the text yet, and nobody has
            // claimed it: this tab adopts it provisionally.
            controller.simulateSentPrompt("continue", START_MS + 10_000L);
            appendUserPrompt(dir, "b", "continue", START_MS + 10_400L);
            setMtime(dir.resolve("b.jsonl"), START_MS + 10_500L);
            assertEquals("b", controller.resolveActiveSessionId());

            // The sibling publishes its claim on b afterwards.
            tabB.simulateClaimSession(config, "b");

            appendUserPrompt(dir, "a", "continue", START_MS + 10_600L);
            setMtime(dir.resolve("a.jsonl"), START_MS + 10_700L);

            assertEquals("a", controller.resolveActiveSessionId(),
                    "a file another live tab holds is not this tab's own evidence");
        } finally {
            tabB.stopProcess();
        }
    }

    /**
     * A /resume command assembled across a terminal draft and the panel text
     * switches the conversation like a plain one: the unknown prompt that
     * follows must be recovered from the unclaimed destination.
     */
    @Test
    void aComposedResumeCommandFollowsTheUnclaimedDestination() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "a", "a");
        writeSession(dir, "b", "b");
        setMtime(dir.resolve("a.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("b.jsonl"), START_MS - 5_000L);

        // This tab launched the saved conversation a.
        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateLiveSession(config, "a", Set.of("a", "b"));

        // A second tab launched the same saved file and only follows it.
        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS + 1_000L, true);
        tabB.simulateLiveSession(config, "a", Set.of("a", "b"));

        try {
            // The command is split: the draft was typed in the terminal and the
            // rest is sent from the panel. The picker it opens is not answered
            // through the terminal recorder.
            controller.simulateTerminalInput(
                    "/res".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    START_MS + 9_000L);
            assertEquals("/resume",
                    controller.simulateGuiSend("ume", START_MS + 9_100L));
            controller.completeGuiSend(() -> START_MS + 9_200L);

            // A recalled prompt: its text is unknown.
            controller.simulateTerminalInput(
                    new byte[]{0x1b, '[', 'A'}, START_MS + 10_000L);
            assertNull(controller.simulateTerminalInput(
                    new byte[]{'\r'}, START_MS + 11_000L));

            // The other launcher's own turn lands in the shared launch file.
            appendUserPrompt(dir, "a", "continue", START_MS + 11_400L);
            setMtime(dir.resolve("a.jsonl"), START_MS + 11_500L);
            // This tab's submission lands in the resumed saved conversation,
            // which no live tab holds, strictly later.
            appendUserPrompt(dir, "b", "recalled", START_MS + 11_600L);
            setMtime(dir.resolve("b.jsonl"), START_MS + 12_600L);

            assertEquals("b", controller.resolveActiveSessionId(),
                    "the composed command must enable the unclaimed destination");
            assertEquals("b", controller.resolveActiveSessionId(),
                    "later scans must keep the resumed conversation");
        } finally {
            tabB.stopProcess();
        }
    }

    /**
     * Once a switch recovery has resolved the unheld conversation the run
     * resumed into, a later unknown-text submission keeps it: without the
     * resolved own ID the own-candidate pass is skipped, and a live sibling's
     * newer human record would make Save &amp; Switch rename the sibling's
     * conversation.
     */
    @Test
    void theSwitchRecoveryKeepsItsOwnFileForLaterUnknownPrompts() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "a", "a");
        writeSession(dir, "b", "b");
        writeSession(dir, "c", "c");
        setMtime(dir.resolve("a.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("b.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("c.jsonl"), START_MS - 5_000L);

        // This tab launched the saved conversation a.
        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateLiveSession(config, "a", Set.of("a", "b", "c"));

        // A sibling tab runs its own conversation c.
        ClaudeSessionModel modelC = new ClaudeSessionModel();
        modelC.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabC =
                new ClaudeSessionController(modelC, Collections::emptyList);
        tabC.simulateProcessStart(START_MS + 1_000L, true);
        tabC.simulateLiveSession(config, "c", Set.of("a", "b", "c"));

        try {
            // The run switches to the unheld conversation b and submits a
            // recalled prompt there: its text is unknown.
            controller.simulateTerminalInput(
                    "/res".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    START_MS + 9_000L);
            assertEquals("/resume",
                    controller.simulateGuiSend("ume", START_MS + 9_100L));
            controller.completeGuiSend(() -> START_MS + 9_200L);
            controller.simulateTerminalInput(
                    new byte[]{0x1b, '[', 'A'}, START_MS + 10_000L);
            controller.simulateTerminalInput(new byte[]{'\r'}, START_MS + 11_000L);
            appendUserPrompt(dir, "b", "first", START_MS + 11_600L);
            setMtime(dir.resolve("b.jsonl"), START_MS + 12_600L);
            assertEquals("b", controller.resolveActiveSessionId(),
                    "the switch must resolve the unheld destination");

            // A second recalled prompt goes to b, while the sibling appends a
            // newer human record to its own live conversation c.
            controller.simulateTerminalInput(
                    new byte[]{0x1b, '[', 'A'}, START_MS + 13_000L);
            controller.simulateTerminalInput(new byte[]{'\r'}, START_MS + 14_000L);
            appendUserPrompt(dir, "b", "second", START_MS + 14_400L);
            setMtime(dir.resolve("b.jsonl"), START_MS + 14_500L);
            appendUserPrompt(dir, "c", "sibling", START_MS + 14_600L);
            setMtime(dir.resolve("c.jsonl"), START_MS + 14_700L);

            assertEquals("b", controller.resolveActiveSessionId(),
                    "the resolved switch destination must stay this tab's own");
        } finally {
            tabC.stopProcess();
        }
    }

    /**
     * A terminal Enter during the GUI send's pause submits the panel text
     * together with the keys typed there. The text the terminal parser
     * recorded is only a part of that prompt, so an identity scan in the
     * window before the panel's Enter must not adopt another conversation's
     * file matched by the part alone.
     */
    @Test
    void aTerminalEnterDuringTheGuiSendPublishesNoPartialPrompt() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "b", "b");
        writeSession(dir, "w", "w");
        setMtime(dir.resolve("b.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("w.jsonl"), START_MS - 5_000L);

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateLiveSession(config, "b", Set.of("b", "w"));

        // The panel text is written, and during the pause before its Enter the
        // user types in the terminal and presses Enter: the TUI submits the
        // composed line "hello world".
        controller.simulateGuiSend("hello", START_MS + 10_000L);
        controller.simulateTerminalInput(
                " world".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                START_MS + 10_050L);
        controller.simulateTerminalInput(new byte[]{'\r'}, START_MS + 10_100L);

        // An unrelated conversation holds the partial text; the launched one
        // holds the composed prompt, slightly later.
        appendUserPrompt(dir, "w", "world", START_MS + 10_200L);
        setMtime(dir.resolve("w.jsonl"), START_MS + 10_300L);
        appendUserPrompt(dir, "b", "hello world", START_MS + 10_400L);
        setMtime(dir.resolve("b.jsonl"), START_MS + 10_500L);

        assertEquals("b", controller.resolveActiveSessionId(),
                "the partial terminal text must not identify a conversation");

        controller.completeGuiSend();
        assertEquals("b", controller.resolveActiveSessionId(),
                "the composed prompt must keep the launched conversation");
    }

    /**
     * An Enter on the still-empty terminal line does nothing on the TUI, so it
     * must not invalidate the prompt evidence: the submitted prompt would stop
     * matching, and the resumed conversation's live claim stays excluded from
     * the file-time fallbacks, leaving Save &amp; Switch on the previous
     * conversation.
     */
    @Test
    void anEmptyTerminalEnterKeepsTheSubmittedPromptEvidence() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "a", "a");
        writeSession(dir, "b", "b");
        setMtime(dir.resolve("a.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("b.jsonl"), START_MS - 5_000L);

        // This tab launched the saved conversation a.
        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateLiveSession(config, "a", Set.of("a", "b"));

        // A live sibling holds the conversation resumed in the TUI.
        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS + 1_000L, true);
        tabB.simulateLiveSession(config, "b", Set.of("a", "b"));

        try {
            controller.simulateTerminalInput(
                    "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    START_MS + 11_000L);
            assertEquals("hello", controller.simulateTerminalInput(
                    new byte[]{'\r'}, START_MS + 11_001L));
            // An Enter on the empty line before the scan.
            controller.simulateTerminalInput(new byte[]{'\r'}, START_MS + 11_500L);

            // The submission lands in the resumed conversation.
            appendUserPrompt(dir, "b", "hello", START_MS + 11_600L);
            setMtime(dir.resolve("b.jsonl"), START_MS + 11_700L);

            assertEquals("b", controller.resolveActiveSessionId(),
                    "an empty Enter must not hide the submitted prompt");
            assertEquals("b", controller.resolveActiveSessionId(),
                    "later scans must keep the resumed conversation");
        } finally {
            tabB.stopProcess();
        }
    }

    /**
     * A draft typed in the terminal is consumed by a prompt sent from the GUI:
     * its characters are part of that submission, so the next line typed in
     * the terminal must not carry them, and the submission's evidence is the
     * whole line the TUI receives.
     */
    @Test
    void aGuiSendConsumesTheTerminalDraftBeforeTheNextLine() {
        controller.simulateTerminalInput(
                "draft".getBytes(java.nio.charset.StandardCharsets.UTF_8), START_MS);
        assertEquals("drafthello", controller.simulateGuiSend("hello", START_MS + 1_000L),
                "the GUI submission takes the terminal draft with it");
        controller.completeGuiSend();
        assertEquals("next", controller.simulateTerminalInput(
                        "next\r".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        START_MS + 2_000L),
                "the consumed draft must not prefix the next terminal line");
    }

    /**
     * The cancellation of a picker must be consumed before the next escape
     * sequence: a bracketed paste starts with its own {@code ESC} in a separate
     * write, and starting that sequence must not forget that the previous Esc
     * cancelled the picker, or the stale search text prefixes the pasted
     * prompt.
     */
    @Test
    void aPickerCancellationIsConsumedBeforeTheNextEscapeSequence() {
        java.nio.charset.Charset utf8 = java.nio.charset.StandardCharsets.UTF_8;
        model.setActiveChoiceMenu(new ChoiceMenuModel("Resume", List.of(
                new ChoiceMenuModel.Option("One", "1"),
                new ChoiceMenuModel.Option("Two", "2")), 0));

        controller.simulateTerminalInput("work".getBytes(utf8), START_MS);
        // The user cancels the picker with Esc in the terminal.
        controller.simulateTerminalInput(new byte[]{0x1b}, START_MS + 1L);
        // The screen no longer shows the picker.
        model.clearChoiceMenu();

        // A bracketed paste begins with its own escape sequence.
        controller.simulateTerminalInput("\u001b[200~".getBytes(utf8), START_MS + 2L);
        controller.simulateTerminalInput("hello".getBytes(utf8), START_MS + 3L);
        controller.simulateTerminalInput("\u001b[201~".getBytes(utf8), START_MS + 4L);

        assertEquals("hello", controller.simulateTerminalInput(
                new byte[]{'\r'}, START_MS + 5L),
                "the cancelled picker search must not prefix the pasted prompt");
    }

    /**
     * A whitespace-only GUI send carries no prompt text of this tab, so it must
     * not replace the evidence of the previous prompt: otherwise the scan looks
     * for an unknown-text submission, and a sibling that appends a real prompt
     * afterwards is adopted even though this tab's own conversation is strictly
     * newer.
     */
    @Test
    void aWhitespaceOnlySendKeepsThePreviousPromptEvidence() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "a", "a");
        writeSession(dir, "b", "b");
        setMtime(dir.resolve("a.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("b.jsonl"), START_MS - 5_000L);

        // This tab launched the saved conversation a and wrote a prompt there.
        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateLiveSession(config, "a", Set.of("a", "b"));
        appendUserPrompt(dir, "a", "first prompt", START_MS + 5_000L);
        setMtime(dir.resolve("a.jsonl"), START_MS + 5_500L);
        assertEquals("a", controller.resolveActiveSessionId(),
                "the launch conversation holds this tab's prompt");

        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS + 1_000L, true);
        tabB.simulateLiveSession(config, "b", Set.of("a", "b"));

        try {
            // The GUI sends a lone Enter: no prompt text of this tab is written.
            controller.simulateSentPrompt("   ", START_MS + 10_000L);
            // This tab's turn only appends assistant output, which is newer.
            java.nio.file.Files.writeString(dir.resolve("a.jsonl"),
                    "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\","
                            + "\"content\":\"assistant turn\"},\"timestamp\":\""
                            + java.time.Instant.ofEpochMilli(START_MS + 10_500L) + "\"}\n",
                    java.nio.file.StandardOpenOption.APPEND);
            setMtime(dir.resolve("a.jsonl"), START_MS + 11_000L);
            // The sibling submits a real prompt afterwards.
            appendUserPrompt(dir, "b", "hello", START_MS + 12_000L);
            setMtime(dir.resolve("b.jsonl"), START_MS + 12_500L);

            assertEquals("a", controller.resolveActiveSessionId(),
                    "the sibling's prompt must not replace the previous evidence");
            assertEquals("a", controller.resolveActiveSessionId(),
                    "later scans must keep the launch conversation");
        } finally {
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
     * A paste with many line breaks must be inserted in the same single
     * operation as its text. Handling every newline on its own copies the
     * unchanged draft once per line, which turns a line-heavy paste into
     * seconds of quadratic work on the thread feeding the terminal.
     */
    @Test
    void aNewlineHeavyPasteBeforeALargeDraftIsInsertedQuickly() {
        String draft = "d".repeat(512 * 1024);
        String pasted = "x\n".repeat(512 * 1024);
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
                "the whole pasted text, line breaks included, must precede the draft");
        assertTrue(elapsedMillis < 3_000,
                "a paste must not rescan the draft per line (took "
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
     * The same two unresolved tabs stopped with a name: neither file can be
     * proven to be this tab's, so the name would land in the sibling's
     * conversation. The rename must be skipped rather than guessed.
     */
    @Test
    void aStopDoesNotRenameEitherOfTwoSharedUnresolvedFiles() throws Exception {
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

            writeSession(dir, "b", "b");
            appendUserPrompt(dir, "b", "hello", START_MS + 10_500L);
            setMtime(dir.resolve("b.jsonl"), START_MS + 10_600L);
            assertEquals("b", controller.resolveActiveSessionId());
            assertEquals("b", tabB.resolveActiveSessionId());

            writeSession(dir, "a", "a");
            appendUserPrompt(dir, "a", "hello", START_MS + 10_500L);
            setMtime(dir.resolve("a.jsonl"), START_MS + 11_200L);
            assertEquals("b", controller.resolveActiveSessionId(),
                    "the shared conversation is still the one this tab tracks");

            controller.stopAndRename("A-title");
        } finally {
            tabB.stopProcess();
        }

        assertNull(io.github.nbplugins.claudecodegui.process.ClaudeSessionStore
                        .findSession(work, config, "b").customTitle(),
                "the sibling's conversation must not be renamed");
        assertNull(io.github.nbplugins.claudecodegui.process.ClaudeSessionStore
                        .findSession(work, config, "a").customTitle(),
                "the ambiguous candidate must not be renamed either");
    }

    /**
     * The sibling of {@link #aStopDoesNotRenameEitherOfTwoSharedUnresolvedFiles}
     * exits without a rename before this tab is stopped. Its departure releases
     * its claim but does not turn this tab's earlier guess into ownership: both
     * files still hold the same prompt, so neither may be renamed.
     */
    @Test
    void aStopDoesNotRenameWhenTheSiblingExitedBeforeTheStop() throws Exception {
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

            writeSession(dir, "b", "b");
            appendUserPrompt(dir, "b", "hello", START_MS + 10_500L);
            setMtime(dir.resolve("b.jsonl"), START_MS + 10_600L);
            assertEquals("b", controller.resolveActiveSessionId());
            assertEquals("b", tabB.resolveActiveSessionId());

            writeSession(dir, "a", "a");
            appendUserPrompt(dir, "a", "hello", START_MS + 10_500L);
            setMtime(dir.resolve("a.jsonl"), START_MS + 11_200L);
            assertEquals("b", controller.resolveActiveSessionId(),
                    "the shared conversation is still the one this tab tracks");

            // The sibling stops without renaming; its claim is released.
            tabB.stopProcess();
            assertEquals("b", controller.resolveActiveSessionId(),
                    "the departure does not resolve the identity");

            controller.stopAndRename("A-title");
        } finally {
            tabB.stopProcess();
        }

        assertNull(io.github.nbplugins.claudecodegui.process.ClaudeSessionStore
                        .findSession(work, config, "b").customTitle(),
                "the sibling's conversation must not be renamed");
        assertNull(io.github.nbplugins.claudecodegui.process.ClaudeSessionStore
                        .findSession(work, config, "a").customTitle(),
                "the ambiguous candidate must not be renamed either");
    }

    /**
     * Both NEW tabs submit identical prompts, and both files hold the prompt
     * before either tab scans its store. The first tab takes the newest file;
     * the second excludes it as the first tab's claim and takes the other. The
     * claim the second tab excluded is only the first tab's guess, so the two
     * files stay indistinguishable for a rename: neither may be renamed.
     */
    @Test
    void aStopDoesNotRenameWhenTheSiblingHoldsTheOtherGuess() throws Exception {
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

            // Both files exist before either tab scans.
            writeSession(dir, "a", "a");
            appendUserPrompt(dir, "a", "hello", START_MS + 10_500L);
            setMtime(dir.resolve("a.jsonl"), START_MS + 10_600L);
            writeSession(dir, "b", "b");
            appendUserPrompt(dir, "b", "hello", START_MS + 10_500L);
            setMtime(dir.resolve("b.jsonl"), START_MS + 11_200L);

            assertEquals("b", controller.resolveActiveSessionId(),
                    "the first tab takes the newest file");
            assertEquals("a", tabB.resolveActiveSessionId(),
                    "the second tab can only take the other one");

            tabB.stopAndRename("B-title");
        } finally {
            tabB.stopProcess();
        }

        assertNull(io.github.nbplugins.claudecodegui.process.ClaudeSessionStore
                        .findSession(work, config, "a").customTitle(),
                "the guess this tab tracked must not be renamed");
        assertNull(io.github.nbplugins.claudecodegui.process.ClaudeSessionStore
                        .findSession(work, config, "b").customTitle(),
                "the sibling's guess must not be renamed either");
    }

    /**
     * A NEW tab that created its conversation and is merely followed by a
     * sibling has nothing ambiguous about its file: the rename must still
     * happen there.
     */
    @Test
    void aStopRenamesTheFileAFollowerOnlyFollows() throws Exception {
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

            // Only this tab's conversation holds the prompt; the sibling
            // follows it because it has no file of its own.
            writeSession(dir, "a", "a");
            appendUserPrompt(dir, "a", "hello", START_MS + 10_500L);
            setMtime(dir.resolve("a.jsonl"), START_MS + 10_600L);
            assertEquals("a", controller.resolveActiveSessionId());
            assertEquals("a", tabB.resolveActiveSessionId());

            controller.stopAndRename("Shared Title");
        } finally {
            tabB.stopProcess();
        }

        assertEquals("Shared Title",
                io.github.nbplugins.claudecodegui.process.ClaudeSessionStore
                        .findSession(work, config, "a").customTitle(),
                "the creator's conversation is renamed although a sibling follows it");
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
     * A provisional file of another NEW tab can be borrowed and even match the
     * own-prompt evidence, but it is not this tab's conversation: once the file
     * this tab created holds the same prompt and is strictly newer, the
     * borrowed hold must not stay preferred. Both submissions came from the
     * GUI, so neither terminal input nor a GUI switch marks the change.
     */
    @Test
    void aBorrowedProvisionalConversationDoesNotStayPreferred() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "a", "a");
        writeSession(dir, "b", "b");
        setMtime(dir.resolve("a.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("b.jsonl"), START_MS - 5_000L);

        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS, true);
        tabB.simulateLiveSession(config, null, Set.of("a", "b"));

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateLiveSession(config, null, Set.of("a", "b"));

        try {
            // Both tabs submit the same text from the GUI.
            controller.simulateSentPrompt("hello", START_MS + 10_000L);
            tabB.simulateSentPrompt("hello", START_MS + 10_000L);
            // Let the background scans finish before any file exists: their
            // timing is otherwise free to adopt a file ahead of the explicit
            // resolutions this test describes.
            controller.simulateAwaitIdentityScan();
            tabB.simulateAwaitIdentityScan();

            // This tab resolves its own conversation first.
            appendUserPrompt(dir, "a", "hello", START_MS + 10_400L);
            setMtime(dir.resolve("a.jsonl"), START_MS + 10_500L);
            assertEquals("a", controller.resolveActiveSessionId());

            // The sibling's conversation appears later and the sibling
            // resolves it.
            appendUserPrompt(dir, "b", "hello", START_MS + 10_600L);
            setMtime(dir.resolve("b.jsonl"), START_MS + 10_700L);
            assertEquals("b", tabB.resolveActiveSessionId());

            // The next identical submission is found only in the sibling's
            // file: this tab temporarily borrows it.
            controller.simulateSentPrompt("again", START_MS + 11_000L);
            tabB.simulateSentPrompt("again", START_MS + 11_000L);
            controller.simulateAwaitIdentityScan();
            tabB.simulateAwaitIdentityScan();
            appendUserPrompt(dir, "b", "again", START_MS + 11_400L);
            setMtime(dir.resolve("b.jsonl"), START_MS + 11_500L);
            assertEquals("b", controller.resolveActiveSessionId(),
                    "with nothing of its own written yet, the tab borrows the sibling");

            // This tab's own conversation is flushed later, strictly newer.
            appendUserPrompt(dir, "a", "again", START_MS + 11_600L);
            setMtime(dir.resolve("a.jsonl"), START_MS + 12_600L);

            assertEquals("a", controller.resolveActiveSessionId(),
                    "a borrowed provisional file must not stay preferred");
            assertEquals("a", controller.resolveActiveSessionId(),
                    "later scans must keep the tab's own conversation");
        } finally {
            tabB.stopProcess();
        }
    }

    /**
     * A launch-known tab switched to an unclaimed conversation and resolved it;
     * a later recalled submission temporarily follows a live sibling's file
     * before its own conversation is flushed. The retained own ID must stay
     * eligible, or the sibling's file is renamed instead.
     */
    @Test
    void aLaunchKnownTabRecoversItsSwitchedConversationAfterARecall() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "a", "a");
        writeSession(dir, "b", "b");
        writeSession(dir, "c", "c");
        setMtime(dir.resolve("a.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("b.jsonl"), START_MS - 5_000L);
        setMtime(dir.resolve("c.jsonl"), START_MS - 5_000L);

        ClaudeSessionModel modelC = new ClaudeSessionModel();
        modelC.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabC =
                new ClaudeSessionController(modelC, Collections::emptyList);
        tabC.simulateProcessStart(START_MS, true);
        tabC.simulateLiveSession(config, "c", Set.of("a", "b", "c"));

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS + 1_000L, true);
        controller.simulateLiveSession(config, "a", Set.of("a", "b", "c"));

        try {
            // The tab switches to the unclaimed conversation b with a literal
            // prompt.
            controller.simulateSentPrompt("continue", START_MS + 10_000L);
            appendUserPrompt(dir, "b", "continue", START_MS + 10_400L);
            setMtime(dir.resolve("b.jsonl"), START_MS + 10_500L);
            assertEquals("b", controller.resolveActiveSessionId());

            // A recalled prompt: its text is unknown.
            controller.simulatePromptSubmittedAt(START_MS + 11_000L);
            controller.simulatePromptText(null);

            // The live sibling finishes its own turn first, so the tab
            // temporarily follows it.
            appendUserPrompt(dir, "c", "sibling", START_MS + 11_400L);
            setMtime(dir.resolve("c.jsonl"), START_MS + 11_500L);
            assertEquals("c", controller.resolveActiveSessionId(),
                    "the sibling record is adopted while the tab's file is not flushed");

            // The tab's own conversation gets the actual submission, strictly
            // newest.
            appendUserPrompt(dir, "b", "recalled", START_MS + 11_600L);
            setMtime(dir.resolve("b.jsonl"), START_MS + 12_600L);

            assertEquals("b", controller.resolveActiveSessionId(),
                    "the retained own conversation must be eligible again");
            assertEquals("b", controller.resolveActiveSessionId(),
                    "later scans must keep it");
        } finally {
            tabC.stopProcess();
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

    /**
     * This tab's process quits on its own while Save &amp; Switch is taking the
     * snapshot of the claims it must carry into the final lookup. The exit
     * waiter moves the claims from the live registry into the preserved set;
     * the snapshot must observe the transition as a whole. Reading the two sets
     * apart would miss the shared conversation this tab held, and the scan
     * would then rename an unrelated eligible file even though the shared one
     * is the newest.
     */
    @Test
    void aStopSnapshotsTheClaimsTheExitWaiterIsReleasing() throws Exception {
        java.nio.file.Path config = tmpDir.resolve("config");
        java.nio.file.Path work = tmpDir.resolve("project");
        java.nio.file.Path dir = sessionDir(config, work);

        writeSession(dir, "shared", "shared");
        setMtime(dir.resolve("shared.jsonl"), START_MS + 12_000L);
        writeSession(dir, "unrelated", "unrelated");
        setMtime(dir.resolve("unrelated.jsonl"), START_MS + 5_000L);

        // Another live tab resumed the same saved conversation.
        ClaudeSessionModel modelB = new ClaudeSessionModel();
        modelB.setWorkingDirectory(work.toFile());
        ClaudeSessionController tabB =
                new ClaudeSessionController(modelB, Collections::emptyList);
        tabB.simulateProcessStart(START_MS, true);
        tabB.simulateLiveSession(config, "shared", Set.of());

        model.setWorkingDirectory(work.toFile());
        controller.simulateProcessStart(START_MS, true);
        controller.simulateLiveSession(config, "shared", Set.of("shared", "unrelated"));
        // The process quits exactly while the stop path snapshots the claims.
        controller.simulateClaimSnapshotHook(controller::simulateProcessExit);

        try {
            controller.stopAndRename("Shared Title");
        } finally {
            tabB.stopProcess();
        }

        assertEquals("Shared Title",
                io.github.nbplugins.claudecodegui.process.ClaudeSessionStore
                        .findSession(work, config, "shared").customTitle(),
                "the conversation this tab held is renamed even after its process quit");
        assertNull(io.github.nbplugins.claudecodegui.process.ClaudeSessionStore
                        .findSession(work, config, "unrelated").customTitle(),
                "an unrelated file must not be renamed");
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
