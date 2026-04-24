package io.github.nbclaudecodegui.process;

import io.github.nbclaudecodegui.model.ChoiceMenuModel;
import io.github.nbclaudecodegui.model.EditMode;
import io.github.nbclaudecodegui.settings.ClaudeCodePreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects numbered-menu prompts by scanning the rendered terminal screen
 * (JediTerm's TerminalTextBuffer), not the raw PTY stream.
 *
 * <p>Reading the rendered screen avoids false aborts caused by cursor-movement
 * sequences (e.g. {@code ESC[15A}) that Claude uses to update spinner lines
 * above the menu while the menu is still waiting for input.
 *
 * <p>This class is stateless — call {@link #detectChoiceMenu(List)} at any time.
 */
public final class ScreenContentDetector {

    /** Creates a new detector with default state. */
    public ScreenContentDetector() {}

    private static final Logger LOG = Logger.getLogger(ScreenContentDetector.class.getName());

    private String tag = "";

    /**
     * Sets a session tag used as a log prefix for debugging.
     *
     * @param tag the tag string, or {@code null} to clear
     */
    public void setSessionTag(String tag) {
        this.tag = tag == null ? "" : tag;
    }

    /**
     * Matches a numbered-menu option line in either of two forms:
     * <ul>
     *   <li>{@code ❯ 1. Yes} — selected option (cursor glyph + number + dot)</li>
     *   <li>{@code 2. Yes, allow...} — non-selected option (number + dot)</li>
     * </ul>
     * Leading whitespace is allowed (produced by ESC[1C cursor-forward expansion).
     */
    private static final Pattern OPTION_LINE =
            Pattern.compile("^\\s*(\u276F|\u25B6|>)?\\s*\\d+\\.\\s*.+");

    /** Matches an option line that carries the Ink cursor glyph (selected item). */
    private static final Pattern CURSOR_LINE =
            Pattern.compile("^\\s*[\u276F\u25B6>]\\s*\\d+\\.\\s*.+");

    /**
     * Spinner characters Claude shows in the footer when a tool is running.
     * Braille spinner: ⠋ ⠙ ⠹ ⠸ ⠼ ⠴ ⠦ ⠧ ⠇ ⠏
     */
    private static final String SPINNER_CHARS = "\u280B\u2819\u2839\u2838\u283C\u2834\u2826\u2827\u2807\u280F";

    /** Pattern matching a plan file name in the status bar (e.g. "PLAN.md" or "plan.md"). */
    private static final Pattern PLAN_NAME_PATTERN =
            Pattern.compile("\\b([\\w\\-]+\\.md)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Pattern matching a plan name in Claude's separator line, e.g.
     * "──────── create-hello-world-script ──" (U+2500 box-drawing dashes).
     */
    private static final Pattern PLAN_NAME_DASHES_PATTERN =
            Pattern.compile("\u2500{2,}\\s+([\\w][\\w\\-]*)\\s+\u2500+");

    // -------------------------------------------------------------------------
    // Session state
    // -------------------------------------------------------------------------

    /** Represents Claude's current activity state (legacy — use {@link DetectedSessionState}). */
    public enum SessionState {
        /** Claude is idle and ready for a new prompt. */
        READY,
        /** Claude is currently processing a request. */
        WORKING
    }

    /**
     * Richer session state returned by TTY-layout-based detection.
     * Unlike the legacy {@link SessionState}, this enum includes {@link #UNKNOWN}
     * for blank or transitioning screens where the state cannot be determined.
     */
    public enum DetectedSessionState {
        /** Claude is idle and ready for a new prompt (input prompt area visible, no interrupt footer). */
        READY,
        /** Claude is currently processing a request. */
        WORKING,
        /** Screen is blank or transitioning — state cannot be determined. */
        UNKNOWN
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Scan the visible screen lines for a numbered menu prompt.
     *
     * <p>The algorithm searches from the bottom of the screen upward for a
     * contiguous block of numbered-option lines, then uses the line immediately
     * above that block as the question text.
     *
     * @param screenLines lines from {@code TerminalTextBuffer.getScreenBuffer().getLineTexts()}
     * @return a detected {@link ChoiceMenuModel}, or empty if no numbered menu is visible
     */
    public Optional<ChoiceMenuModel> detectChoiceMenu(List<String> screenLines) {
        if (screenLines == null || screenLines.isEmpty()) return Optional.empty();

        // Find the last option line (bottom-most) on screen
        int lastOptionRow = -1;
        for (int i = screenLines.size() - 1; i >= 0; i--) {
            if (OPTION_LINE.matcher(screenLines.get(i).trim()).matches()) {
                lastOptionRow = i;
                break;
            }
        }
        if (lastOptionRow < 0) return detectUnnumberedChoiceMenu(screenLines);
        LOG.fine(tag + "[ScreenContentDetector] lastOptionRow=" + lastOptionRow
                + " line=" + screenLines.get(lastOptionRow).trim());

        // Walk upward to collect all contiguous option lines.
        // Allow up to 3 consecutive "continuation" lines (non-option, non-blank)
        // to handle wrapped option text that overflows to the next screen line.
        // Stop as soon as we encounter option "1." — it is always the first item of the
        // menu, so anything above it belongs to a previous context.
        //
        // optionRows[i] stores the screen-line index of each option (parallel to options list).
        List<ChoiceMenuModel.Option> options = new ArrayList<>();
        List<Integer> optionRows = new ArrayList<>();
        int firstOptionRow = lastOptionRow;
        int continuationCount = 0;
        for (int i = lastOptionRow; i >= 0; i--) {
            String trimmed = screenLines.get(i).trim();
            if (OPTION_LINE.matcher(trimmed).matches()) {
                ChoiceMenuModel.Option opt = extractOption(trimmed, options.size() + 1);
                options.add(0, opt);
                optionRows.add(0, i);
                firstOptionRow = i;
                continuationCount = 0;
                // Option "1" is always the first — stop collecting here
                if ("1".equals(opt.response())) break;
            } else if (!trimmed.isBlank()) {
                continuationCount++;
                if (continuationCount > 3) {
                    LOG.fine(tag + "[ScreenContentDetector] stopped upward scan at row " + i
                            + " (continuationCount>3): " + trimmed);
                    break;
                }
            }
        }

        // Collect per-option description: the first candidate line immediately below each
        // option line that looks like an intentional description (not a wrapped continuation
        // of the option text, not a hint line, not a separator).
        // A description line must:
        //   - be non-blank and non-separator
        //   - not be another option line
        //   - not be a keyboard-hint line (contains "·" or starts with "Esc"/"Tab"/"Enter")
        //   - have a shorter leading-whitespace indent than a deeply-wrapped continuation
        //     (continuation wraps are typically indented to align with the option text start,
        //      so they have large indent; description lines have moderate indent ≤ 8 spaces)
        for (int oi = 0; oi < options.size(); oi++) {
            int optRow = optionRows.get(oi);
            int nextOptRow = (oi + 1 < optionRows.size()) ? optionRows.get(oi + 1) : lastOptionRow + 4;
            for (int j = optRow + 1; j < Math.min(nextOptRow, screenLines.size()); j++) {
                String raw = screenLines.get(j);
                String line = raw.trim();
                if (line.isBlank()) break;
                if (OPTION_LINE.matcher(line).matches()) break;
                // Skip keyboard-hint lines
                if (isHintLine(line)) break;
                // Skip separator lines
                if (isSeparatorLine(line)) break;
                // Description must be indented (at least 1 leading space) and not too deeply
                // indented (> 8 spaces = wrapped continuation of the option text itself).
                int indent = raw.length() - raw.stripLeading().length();
                if (indent == 0 || indent > 8) break;
                ChoiceMenuModel.Option prev = options.get(oi);
                options.set(oi, new ChoiceMenuModel.Option(
                        prev.display(), prev.response(), line, prev.checked(), prev.hasCheckbox()));
                break;
            }
        }

        if (options.isEmpty()) return Optional.empty();
        LOG.fine(tag + "[ScreenContentDetector] collected options=" + options
                + " firstOptionRow=" + firstOptionRow);

        // Bug 1 guard: a single option with no Esc/cancel/amend hint in the
        // 3 lines below lastOptionRow is likely an echoed previous selection,
        // not a real menu. Real menus always have ≥2 options OR show a hint.
        if (options.size() == 1) {
            boolean hasHint = false;
            for (int i = lastOptionRow + 1; i < Math.min(lastOptionRow + 4, screenLines.size()); i++) {
                String lower = screenLines.get(i).toLowerCase();
                if (lower.contains("esc") || lower.contains("cancel") || lower.contains("amend")) {
                    hasHint = true;
                    break;
                }
            }
            if (!hasHint) {
                LOG.fine(tag + "[ScreenContentDetector] rejected: single option with no hint");
                return Optional.empty();
            }
        }

        // First non-blank line above the first option is the question
        String question = "";
        for (int i = firstOptionRow - 1; i >= 0; i--) {
            String line = screenLines.get(i).trim();
            if (!line.isBlank()) {
                question = line;
                break;
            }
        }

        // Guard: Claude Code Ink menus always show a cursor glyph (❯ / ▶ / >) on the
        // currently-selected option. Numbered lists in Claude's own output never have
        // a cursor, so requiring one eliminates false positives entirely.
        boolean hasCursor = false;
        for (int i = firstOptionRow; i <= lastOptionRow; i++) {
            if (CURSOR_LINE.matcher(screenLines.get(i).trim()).matches()) {
                hasCursor = true;
                break;
            }
        }
        if (!hasCursor) {
            LOG.fine(tag + "[ScreenContentDetector] rejected: no cursor glyph found");
            return Optional.empty();
        }

        // Guard: if a separator line exists below the last option, a real menu always has
        // at least one option line below that separator (e.g. "6. Chat about this" in
        // interview-style menus). An echoed user prompt has no options below the separator
        // that frames the input prompt ❯. Applies only when a separator is actually found.
        int firstSepBelow = -1;
        for (int i = lastOptionRow + 1; i < screenLines.size(); i++) {
            if (isSeparatorLine(screenLines.get(i).trim())) { firstSepBelow = i; break; }
        }
        if (firstSepBelow >= 0) {
            boolean hasOptionBelowSep = false;
            for (int i = firstSepBelow + 1; i < screenLines.size(); i++) {
                if (OPTION_LINE.matcher(screenLines.get(i).trim()).matches()) {
                    hasOptionBelowSep = true; break;
                }
            }
            if (!hasOptionBelowSep) {
                LOG.fine(tag + "[ScreenContentDetector] rejected: separator at row "
                        + firstSepBelow + " with no options below: "
                        + screenLines.get(firstSepBelow).trim());
                return Optional.empty();
            }
        }

        LOG.fine(tag + "[ScreenContentDetector] detected prompt: \"" + question + "\" options=" + options);
        return Optional.of(new ChoiceMenuModel(question, options, 0));
    }

    // -------------------------------------------------------------------------
    // Unnumbered menu detection
    // -------------------------------------------------------------------------

    /**
     * Scan the visible screen lines for an unnumbered menu prompt (e.g. the {@code /resume}
     * session picker).
     *
     * <p>Unnumbered menus have no {@code N.} option prefix. Each option occupies two
     * consecutive non-blank lines (main text + metadata/description). The selected option is
     * prefixed with {@code ❯} (no indent); all others are indented with leading spaces.
     * A search box (box-drawing borders + ⌕) and a title line appear above the options.
     * A footer hint line containing {@code ·} and navigation keywords appears at the bottom.
     *
     * <p>Responses are encoded as {@code "ARROW:N"} (0-based index). The controller
     * navigates to option N using arrow keys then sends {@code \r}.
     *
     * @param screenLines lines from {@code TerminalTextBuffer.getScreenBuffer().getLineTexts()}
     * @return a detected {@link ChoiceMenuModel}, or empty if no unnumbered menu is visible
     */
    Optional<ChoiceMenuModel> detectUnnumberedChoiceMenu(List<String> screenLines) {
        if (screenLines == null || screenLines.isEmpty()) return Optional.empty();

        // Step 1: find footer — last non-blank line containing '·' and navigation/Esc keywords
        int footerRow = -1;
        for (int i = screenLines.size() - 1; i >= 0; i--) {
            String line = screenLines.get(i);
            if (line.isBlank()) continue;
            String trimmed = line.trim();
            if (isUnnumberedFooterLine(trimmed)) {
                footerRow = i;
                break;
            }
            // First non-blank line from bottom is not a footer → no unnumbered menu
            break;
        }
        if (footerRow < 0) return Optional.empty();

        // Step 2: walk upward from footer collecting option blocks (pairs: desc + main, bottom-up).
        // Stop when we hit a non-option line (search box, separator, 0-indent title, numbered option).
        // Also stop if we encounter a footer-like line mid-screen (handles mid-render footer redraws).
        List<String> blockMainLines = new ArrayList<>();
        List<String> blockDescLines = new ArrayList<>();
        int stopRow = -1; // row where we stopped (first non-option line above options)

        int i = footerRow - 1;
        while (i >= 0) {
            // Skip blank lines between blocks
            while (i >= 0 && screenLines.get(i).isBlank()) i--;
            if (i < 0) break;

            String raw = screenLines.get(i);
            String trimmed = raw.trim();

            // Stop conditions — record stop row for header search
            if (isSeparatorLine(trimmed) || OPTION_LINE.matcher(trimmed).matches()) {
                break; // separator or numbered option → not an unnumbered menu above
            }
            if (isBoxDrawingLine(trimmed) || trimmed.contains("\u2315")) { // ⌕
                // Search box / box border → stop options, continue upward for header
                stopRow = i;
                break;
            }
            if (isUnnumberedFooterLine(trimmed)) {
                // Footer appeared mid-screen (cursor-movement redraw) → treat as footer, stop
                break;
            }
            // Line with no leading indent and no ❯ → title / non-option line
            boolean startsWithCursor = !raw.isEmpty() && "\u276F\u25B6>".indexOf(raw.charAt(0)) >= 0;
            boolean hasIndent = !raw.isEmpty() && Character.isWhitespace(raw.charAt(0));
            if (!startsWithCursor && !hasIndent) {
                // This is the title line itself
                stopRow = i;
                break;
            }

            // Collect this block: desc = current line, main = line above (if non-blank)
            String desc = trimmed;
            i--;
            String main = null;
            if (i >= 0 && !screenLines.get(i).isBlank()) {
                String raw2 = screenLines.get(i);
                String trimmed2 = raw2.trim();
                if (!isSeparatorLine(trimmed2) && !OPTION_LINE.matcher(trimmed2).matches()
                        && !isBoxDrawingLine(trimmed2) && !trimmed2.contains("\u2315")
                        && !isUnnumberedFooterLine(trimmed2)) {
                    main = trimmed2;
                    i--;
                }
            }
            if (main == null) {
                // Single-line block — treat as main with no description
                main = desc;
                desc = null;
            }
            blockMainLines.add(0, main);
            blockDescLines.add(0, desc);
        }

        if (blockMainLines.size() < 2) return Optional.empty();

        // Step 3: build options, detect selected (❯)
        List<ChoiceMenuModel.Option> options = new ArrayList<>();
        int defaultIdx = 0;
        boolean foundCursor = false;
        for (int oi = 0; oi < blockMainLines.size(); oi++) {
            String main = blockMainLines.get(oi);
            String desc = blockDescLines.get(oi);
            if (!main.isEmpty() && "\u276F\u25B6>".indexOf(main.charAt(0)) >= 0) {
                main = main.substring(1).stripLeading();
                if (!foundCursor) {
                    defaultIdx = oi;
                    foundCursor = true;
                }
            }
            options.add(new ChoiceMenuModel.Option(main, "ARROW:" + oi, desc));
        }
        if (!foundCursor) {
            LOG.fine(tag + "[ScreenContentDetector] unnumbered: no cursor glyph found");
            return Optional.empty();
        }

        // Step 4: find header — walk upward from stopRow past search box / box-drawing lines
        String header = "";
        if (stopRow >= 0) {
            for (int h = stopRow; h >= 0; h--) {
                String line = screenLines.get(h).trim();
                if (line.isBlank() || isSeparatorLine(line) || isBoxDrawingLine(line)
                        || line.contains("\u2315")) {
                    continue;
                }
                // First non-blank, non-separator, non-box line above the options region
                header = line;
                break;
            }
        }

        LOG.fine(tag + "[ScreenContentDetector] unnumbered menu: header=\"" + header
                + "\" options=" + options + " defaultIdx=" + defaultIdx);
        return Optional.of(new ChoiceMenuModel(header, options, defaultIdx));
    }

    /**
     * Returns {@code true} if the (trimmed) line is an unnumbered-picker footer hint:
     * contains {@code ·} and at least one of: Esc, cancel, ↑, ↓, Ctrl+.
     */
    private static boolean isUnnumberedFooterLine(String trimmed) {
        if (!trimmed.contains("\u00B7")) return false; // ·
        String lower = trimmed.toLowerCase();
        return lower.contains("esc") || lower.contains("cancel")
                || lower.contains("\u2191") || lower.contains("\u2193")
                || lower.contains("ctrl+");
    }

    /**
     * Returns {@code true} if the (trimmed) line consists primarily of box-drawing characters
     * (╭, ╰, │, ─, ┌, └, etc.) used for search-box borders or similar UI chrome.
     */
    private static boolean isBoxDrawingLine(String trimmed) {
        if (trimmed.isEmpty()) return false;
        char first = trimmed.charAt(0);
        // Box-drawing corner/side characters: ╭ ╰ │ ┌ └ ├ ┤ ╔ ╚ ║
        return first == '\u256D' || first == '\u2570' || first == '\u2502'
                || first == '\u250C' || first == '\u2514' || first == '\u251C'
                || first == '\u2524' || first == '\u2554' || first == '\u255A'
                || first == '\u2551';
    }

    // -------------------------------------------------------------------------
    // New detection methods (Stage 12)
    // -------------------------------------------------------------------------

    /**
     * Detect the Claude CLI edit mode from the rendered screen footer.
     *
     * <p>Possible return values:
     * <ul>
     *   <li>{@link EditMode#PLAN} — bottom line contains {@code "plan mode"} or
     *       {@code "plan-mode"}</li>
     *   <li>{@link EditMode#ACCEPT_EDITS} — bottom line contains
     *       {@code "accept edits"}</li>
     *   <li>{@link EditMode#BYPASS_PERMISSIONS} — bottom line contains
     *       {@code "bypass permissions"}; present when Claude was launched with
     *       {@code --dangerously-skip-permissions}</li>
     *   <li>{@link EditMode#DEFAULT} — bottom line starts with
     *       {@code "  esc to interrupt"} (two leading spaces)</li>
     *   <li>{@link Optional#empty()} — screen is blank or transitioning</li>
     * </ul>
     *
     * @param lines rendered screen lines
     * @return detected {@link EditMode}, or empty if unknown
     */
    public Optional<EditMode> detectEditMode(List<String> lines) {
        if (lines == null || lines.isEmpty()) return Optional.empty();
        List<String> bottom = bottomNonBlankLines(lines, 3);
        if (bottom.isEmpty()) return Optional.empty();
        for (String line : bottom) {
            String lower = line.toLowerCase();
            if (lower.contains("plan mode") || lower.contains("plan-mode")) {
                return Optional.of(EditMode.PLAN);
            }
            if (lower.contains("accept edits")) {
                return Optional.of(EditMode.ACCEPT_EDITS);
            }
            if (lower.contains("bypass permissions")) {
                return Optional.of(EditMode.BYPASS_PERMISSIONS);
            }
        }
        // "  esc to interrupt" with two leading spaces is the reliable Ask/default-mode signal.
        // Without leading spaces the screen is in an unknown / transitioning state.
        if (bottom.get(0).startsWith("  esc to interrupt")) {
            return Optional.of(EditMode.DEFAULT);
        }
        return Optional.empty();
    }

    /**
     * Detect whether Claude is currently working (spinner visible) or ready.
     *
     * @param lines rendered screen lines
     * @return {@link SessionState#WORKING} if a spinner char is visible in the bottom 3 lines,
     *         otherwise {@link SessionState#READY}
     * @deprecated Use {@link #detectSessionState(List)} which returns {@link DetectedSessionState}
     *             and includes an {@code UNKNOWN} state for blank/transitioning screens.
     */
    @Deprecated
    public SessionState detectSessionStateLegacy(List<String> lines) {
        if (lines == null || lines.isEmpty()) return SessionState.READY;
        for (String line : bottomNonBlankLines(lines, 3)) {
            for (char c : SPINNER_CHARS.toCharArray()) {
                if (line.indexOf(c) >= 0) return SessionState.WORKING;
            }
        }
        return SessionState.READY;
    }

    /**
     * Detect Claude's session state using TTY layout heuristics.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>If {@code lines} is null/empty → {@link DetectedSessionState#UNKNOWN}</li>
     *   <li>Search bottom 15 lines for an input-prompt area: a {@code ❯} line that has
     *       a separator line directly above or directly below it.</li>
     *   <li>If input-prompt area found:
     *     <ul>
     *       <li>Footer (bottom 3 non-blank lines) contains a line starting with
     *           {@code "  esc to interrupt"} (two leading spaces) → {@link DetectedSessionState#WORKING}</li>
     *       <li>Otherwise → {@link DetectedSessionState#READY}</li>
     *     </ul>
     *   </li>
     *   <li>If no input-prompt area found:
     *     <ul>
     *       <li>Spinner character in bottom 3 lines → {@link DetectedSessionState#WORKING}</li>
     *       <li>Otherwise → {@link DetectedSessionState#UNKNOWN}</li>
     *     </ul>
     *   </li>
     * </ol>
     *
     * @param lines rendered screen lines from {@code TerminalTextBuffer}
     * @return the detected {@link DetectedSessionState}
     */
    public DetectedSessionState detectSessionState(List<String> lines) {
        if (lines == null || lines.isEmpty()) return DetectedSessionState.UNKNOWN;

        // Search all lines (bottom-up) for an input prompt adjacent to a separator line.
        // Claude Code uses the alternate screen buffer and draws content from the top,
        // so the input prompt area may be anywhere, not just in the bottom rows.
        boolean inputPromptAreaFound = false;
        int promptRow = -1;
        for (int i = lines.size() - 1; i >= 0; i--) {
            String line = lines.get(i);
            if (INPUT_PROMPT.matcher(line.trim()).find()) {
                boolean separatorAbove = i > 0 && (
                        isSeparatorLine(lines.get(i - 1).trim()) ||
                        PLAN_NAME_DASHES_PATTERN.matcher(lines.get(i - 1).trim()).find()
                );
                // Autocomplete popup lines may appear between ❯ and the separator below —
                // scan up to 5 lines down to find it.
                boolean separatorBelow = false;
                for (int j = i + 1; j < lines.size() && j <= i + 5; j++) {
                    if (isSeparatorLine(lines.get(j).trim())) {
                        separatorBelow = true;
                        break;
                    }
                }
                if (separatorAbove && separatorBelow) {
                    inputPromptAreaFound = true;
                    promptRow = i;
                    break;
                }
            }
        }

        if (inputPromptAreaFound) {
            // Interactive picker overlay (⌕ = search box in /resume or history picker).
            // The picker is drawn over the screen while the input prompt area remains in
            // the buffer — detect and report WORKING so the plugin disables the send button.
            // Only scan lines BELOW the prompt row: the picker is rendered below the input
            // area, so ⌕ in lines above (Claude response content) must not trigger this.
            for (int i = promptRow + 1; i < lines.size(); i++) {
                if (lines.get(i).indexOf('\u2315') >= 0) return DetectedSessionState.WORKING;
            }
            // "esc to interrupt" anywhere in a footer line means Claude is working.
            // Default/ask mode: "  esc to interrupt" (two leading spaces).
            // Plan mode: "⏸ plan mode on (shift+tab to cycle) · esc to interrupt".
            for (String footerLine : bottomNonBlankLines(lines, 3)) {
                if (footerLine.toLowerCase().contains("esc to interrupt")) {
                    return DetectedSessionState.WORKING;
                }
            }
            return DetectedSessionState.READY;
        }

        // No input prompt area — fall back to spinner detection
        for (String line : bottomNonBlankLines(lines, 3)) {
            for (char c : SPINNER_CHARS.toCharArray()) {
                if (line.indexOf(c) >= 0) return DetectedSessionState.WORKING;
            }
        }
        // Non-empty screen with no prompt and no spinner: Claude is in some interactive
        // state (picker, transition) — report WORKING so READY state is cleared.
        return DetectedSessionState.WORKING;
    }

    /**
     * Matches a line whose first non-whitespace character is the CC input cursor glyph.
     * After {@code trim()}, any line starting with ❯ / > / ▶ qualifies — the idle
     * prompt may carry placeholder text (e.g. {@code ❯ Ask anything...}) so we only
     * require the glyph at the start, not an empty tail.
     */
    private static final Pattern INPUT_PROMPT =
            Pattern.compile("^[\u276F>\u25B6](?!\\s*\\d)");

    /**
     * Returns true when the CC input prompt ({@code ❯}) is visible in the bottom
     * portion of the screen — meaning CC is truly idle and ready for keyboard input
     * (as opposed to still rendering the startup splash screen).
     *
     * <p>The splash/welcome screen shows a welcome box with no {@code ❯} line at the
     * bottom. The genuine idle state always has {@code ❯} (with or without placeholder
     * text) in the bottom few lines.
     *
     * @param lines rendered screen lines
     * @return {@code true} if the input prompt is found in the bottom 5 non-blank lines
     */
    public boolean detectInputPromptReady(List<String> lines) {
        if (lines == null || lines.isEmpty()) return false;
        for (String line : bottomNonBlankLines(lines, 5)) {
            if (INPUT_PROMPT.matcher(line.trim()).find()) return true;
        }
        return false;
    }

    /**
     * Detect the active plan file name from the screen footer.
     *
     * @param lines rendered screen lines
     * @return the plan file name, or empty if none detected
     */
    public Optional<String> detectPlanName(List<String> lines) {
        if (lines == null || lines.isEmpty()) return Optional.empty();
        for (String line : bottomNonBlankLines(lines, 5)) {
            Matcher m = PLAN_NAME_DASHES_PATTERN.matcher(line);
            if (m.find()) {
                return Optional.of(m.group(1));
            }
            m = PLAN_NAME_PATTERN.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                // Skip common non-plan occurrences like "README.md"
                if (!name.equalsIgnoreCase("README.md")) {
                    return Optional.of(name);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Pattern matching a Y/n confirmation prompt (case-insensitive).
     * Matches: [Y/n], [y/N], [yes/no], (y/n)
     */
    private static final Pattern YN_PROMPT =
            Pattern.compile("\\[Y/n\\]|\\[y/N\\]|\\[yes/no\\]|\\(y/n\\)", Pattern.CASE_INSENSITIVE);

    /**
     * Returns {@code true} if the bottom non-blank lines of the screen contain
     * a Y/n confirmation prompt (e.g. Claude's first-run directory-trust dialog).
     *
     * <p>Matches patterns: {@code [Y/n]}, {@code [y/N]}, {@code [yes/no]}, {@code (y/n)}.
     *
     * @param lines rendered screen lines
     * @return {@code true} if a Y/n prompt is detected
     */
    public boolean detectYesNoPrompt(List<String> lines) {
        if (lines == null || lines.isEmpty()) return false;
        for (String line : bottomNonBlankLines(lines, 5)) {
            if (YN_PROMPT.matcher(line).find()) return true;
        }
        return false;
    }

    /**
     * Returns up to {@code n} non-blank lines from the bottom of the screen buffer,
     * skipping trailing empty rows that JediTerm adds as padding.
     * Lines are returned in bottom-up order (last visible line first).
     */
    static List<String> bottomNonBlankLines(List<String> lines, int n) {
        List<String> result = new ArrayList<>(n);
        for (int i = lines.size() - 1; i >= 0 && result.size() < n; i--) {
            String line = lines.get(i);
            if (!line.isBlank()) result.add(line);
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Extract a display Option from a menu line.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code "❯ 1. Yes"} → Option("Yes", "1")</li>
     *   <li>{@code "2. Yes, allow reading from plans/ during this session"} → Option("Yes, allow...", "2")</li>
     *   <li>{@code "3. No"} → Option("No", "3")</li>
     * </ul>
     *
     * @param line   the trimmed screen line
     * @param index  1-based fallback index if number extraction fails
     */
    static ChoiceMenuModel.Option extractOption(String line, int index) {
        // Skip cursor glyph if present
        String working = line;
        if (!working.isEmpty() && "\u276F\u25B6>".indexOf(working.charAt(0)) >= 0) {
            working = working.substring(1).stripLeading();
        }
        int dotPos = working.indexOf('.');
        if (dotPos < 0) {
            return new ChoiceMenuModel.Option(line, String.valueOf(index));
        }
        String numStr = working.substring(0, dotPos).strip();
        int num;
        try {
            num = Integer.parseInt(numStr);
        } catch (NumberFormatException e) {
            num = index;
        }
        String afterDot = working.substring(dotPos + 1).strip();
        // Detect checkbox prefix: [ ], [x], [X], [✓], [✔]
        boolean checked = false;
        boolean hasCheckbox = false;
        if (afterDot.startsWith("[ ]")) {
            hasCheckbox = true;
            afterDot = afterDot.substring(3).stripLeading();
        } else if (afterDot.startsWith("[x]") || afterDot.startsWith("[X]") || afterDot.startsWith("[✓]") || afterDot.startsWith("[✔]")) {
            hasCheckbox = true;
            checked = true;
            afterDot = afterDot.substring(3).stripLeading();
        }
        return new ChoiceMenuModel.Option(afterDot.strip(), String.valueOf(num), null, checked, hasCheckbox);
    }

    /**
     * Returns {@code true} if the (trimmed) line is a keyboard-hint line like
     * {@code "Esc to cancel · Tab to amend · ctrl+e to explain"} or
     * {@code "Enter to select · ↑/↓ to navigate · Esc to cancel"}.
     * These lines follow the last menu option and must not be treated as descriptions.
     */
    private static boolean isHintLine(String trimmed) {
        // Hint lines contain the middle-dot separator "·" used between keyboard shortcuts,
        // or start with typical shortcut keywords.
        if (trimmed.contains("\u00B7")) return true; // ·
        String lower = trimmed.toLowerCase();
        return lower.startsWith("esc ") || lower.startsWith("enter to") || lower.startsWith("tab to");
    }

    /**
     * Returns {@code true} if the (trimmed) line consists entirely of box-drawing horizontal
     * characters (e.g. {@code ────────────────────────────────────────}).
     * Such lines separate sections in the terminal and must not be treated as descriptions.
     */
    private static boolean isSeparatorLine(String trimmed) {
        if (trimmed.length() < 4) return false;
        return trimmed.chars().allMatch(c -> c == '\u2500' || c == '\u2501' || c == '\u2014' || c == '-' || c == '=');
    }
}
