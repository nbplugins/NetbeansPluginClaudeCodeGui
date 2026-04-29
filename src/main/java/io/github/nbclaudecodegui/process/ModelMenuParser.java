package io.github.nbclaudecodegui.process;

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
            boolean hasCursor = line.trim().matches("^[❯▶>].*");
            boolean hasCheck  = line.contains("\u2714");
            String trimmed = line.trim().replaceFirst("^[❯▶>]\\s*", "").trim();

            // Numbered format: "N. ..."
            if (trimmed.matches("^\\d+\\..*")) {
                String leftPart = trimmed.split("[·\u00b7]", 2)[0];
                Matcher descMatcher = DESC_PAT.matcher(leftPart);
                if (descMatcher.find()) {
                    String desc = descMatcher.group(1).trim()
                            .replaceFirst("^\u2714\\s*", "").trim();
                    // Format 2: "(currently X)"
                    Matcher currentlyMatcher = CURRENTLY_PAT.matcher(desc);
                    String modelId = currentlyMatcher.find()
                            ? currentlyMatcher.group(1)
                            : desc;
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
