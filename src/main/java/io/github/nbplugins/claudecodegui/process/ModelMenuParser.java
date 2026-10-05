package io.github.nbplugins.claudecodegui.process;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the terminal output of Claude Code's {@code /model} selection menu.
 *
 * <p>Supports two numbered formats and a legacy {@code claude-xxx} format:
 *
 * <p><b>Format 1</b> — version string on the right of the separator:
 * <pre>
 * ❯ 1. Default (recommended) ✔  Sonnet 4.6 · Best for everyday tasks
 *   2. Opus                     Opus 4.6 · Most capable for complex work
 * </pre>
 *
 * <p><b>Format 2</b> — {@code (currently X)} token in the description:
 * <pre>
 * ❯ 1. Default (recommended) ✔  Use the default model (currently anthropic/claude-sonnet-4.6) · $3/$15 per Mtok
 *   2. Sonnet (1M context)      Sonnet 4.6 for long sessions · $3/$15 per Mtok
 * </pre>
 *
 * <p><b>Legacy format</b> — bare {@code claude-xxx} identifiers:
 * <pre>
 *   claude-sonnet-4-6
 * ❯ claude-opus-4-5
 * </pre>
 */
public class ModelMenuParser {

    /**
     * Parsed result of {@link #parse}: all detected model names plus the
     * zero-based index of the currently active model.
     *
     * @param models       list of model display names; never {@code null}
     * @param currentIndex zero-based index of the active model, or {@code -1}
     */
    public record ModelDiscovery(List<String> models, int currentIndex) {}

    /** Extracts the description part after ✔ or 2+ spaces. */
    private static final Pattern DESC_PAT =
            Pattern.compile("(?:\u2714\\s+|\\s{2,})(.+)$");

    /** Extracts the model id from "(currently X)" in description. */
    private static final Pattern CURRENTLY_PAT =
            Pattern.compile("\\(currently\\s+([^)]+?)\\s*\\)");

    /** Fallback: "Title Case Word N.M" at the tail of the left part. */
    private static final Pattern VERSION_TAIL_PAT =
            Pattern.compile("([A-Z][a-z]+\\s+\\d+\\.\\d+)\\s*$");

    /** Matches a bare version-like name, e.g. "Opus 5.5" or "Sonnet 5" (no decimal required). */
    private static final Pattern NAME_LOOKS_LIKE_VERSION_PAT =
            Pattern.compile("^[A-Z][a-zA-Z]+\\s+\\d+(?:\\.\\d+)?$");

    /**
     * Parses terminal screen lines and returns the discovered models.
     *
     * @param lines terminal screen lines (may contain ANSI-stripped text)
     * @return {@link ModelDiscovery} with models and current index
     */
    public ModelDiscovery parse(List<String> lines) {
        List<String> models = new ArrayList<>();
        int currentIndex = -1;

        for (String line : lines) {
            boolean hasCursor = line.trim().matches("^[❯▶>↑↓].*");
            boolean hasCheck  = line.contains("\u2714");
            String trimmed = line.trim().replaceFirst("^[❯▶>↑↓]\\s*", "").trim();

            // Numbered format: "N. ..."
            if (trimmed.matches("^\\d+\\..*")) {
                // Strip the ordinal (and any following whitespace — some CLI versions emit a
                // single space, others two) first, so a double-space-after-ordinal quirk can
                // never be mistaken for the name/description column boundary below.
                String afterOrdinal = trimmed.replaceFirst("^\\d+\\.\\s*", "");
                boolean hasSeparator = afterOrdinal.indexOf('·') >= 0;
                String leftPart = hasSeparator ? afterOrdinal.split("[·\\u00b7]", 2)[0] : afterOrdinal;
                Matcher descMatcher = DESC_PAT.matcher(leftPart);
                if (descMatcher.find()) {
                    String namePart = leftPart.substring(0, descMatcher.start()).trim();
                    if (namePart.toLowerCase().contains("(disabled)")) continue;
                    String desc = descMatcher.group(1).trim()
                            .replaceFirst("^\u2714\\s*", "").trim();
                    // Format 2: "(currently X)"
                    Matcher currentlyMatcher = CURRENTLY_PAT.matcher(desc);
                    String modelId;
                    if (currentlyMatcher.find()) {
                        modelId = currentlyMatcher.group(1);
                    } else if (!hasSeparator && NAME_LOOKS_LIKE_VERSION_PAT.matcher(namePart).matches()) {
                        // Some newer CLI versions omit the ·-separated version column
                        // entirely and put the version directly in the name slot (e.g.
                        // "Opus 5.5             For complex work..."), leaving no version
                        // string in the description at all. Take the name here only because
                        // it itself looks like a bare version ("Word N[.M]") — a missing
                        // separator alone is not enough, since a truncated terminal line can
                        // also lack one while still being the old "name  version · desc"
                        // shape (see format2-truncated), whose name column never looks like a
                        // version.
                        modelId = namePart;
                    } else {
                        modelId = desc;
                    }
                    if (hasCheck) currentIndex = models.size();
                    models.add(modelId);
                    continue;
                }
                // Fallback: version at tail of left part
                Matcher tailMatcher = VERSION_TAIL_PAT.matcher(leftPart);
                if (tailMatcher.find()) {
                    if (hasCheck) currentIndex = models.size();
                    models.add(tailMatcher.group(1).trim());
                    continue;
                }
            }

            // Legacy format: "claude-xxx" or "claude/xxx"
            if (trimmed.startsWith("claude-") && !trimmed.contains(" ")) {
                if (hasCursor) currentIndex = models.size();
                models.add(trimmed);
            } else if (trimmed.matches("(?i)claude[\\-/][\\w\\-\\.]+")) {
                if (hasCursor) currentIndex = models.size();
                models.add(trimmed);
            }
        }

        return new ModelDiscovery(models, currentIndex);
    }
}
