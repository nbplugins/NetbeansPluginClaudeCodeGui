package io.github.nbplugins.claudecodegui.settings;

/**
 * Represents a single model alias entry in a connection profile.
 *
 * <p>{@code available} is transient — it reflects the result of the last
 * "Fetch" call and is not persisted.
 *
 * @param id                    model identifier as returned by {@code /v1/models}
 * @param available             {@code null} = unknown, {@code true} = reachable, {@code false} = not reachable
 * @param alias                 CC standard alias to map to this model: {@code ""}, {@code "sonnet"},
 *                              {@code "opus"}, or {@code "haiku"}
 * @param explicitPromptCaching experimental: send explicit {@code prompt_cache_options}/
 *                              {@code prompt_cache_breakpoint} (GPT-&ge;5.6 models) or
 *                              {@code prompt_cache_retention: "24h"} (older GPT models) for this
 *                              model, via the OpenAI-compatible or ChatGPT Subscription connection
 *                              types. See {@link #defaultExplicitPromptCaching(String)}.
 */
public record ModelAlias(String id, Boolean available, String alias, boolean explicitPromptCaching) {

    /** Validates and normalizes the record components. */
    public ModelAlias {
        if (id == null) throw new IllegalArgumentException("id must not be null");
        if (alias == null) alias = "";
    }

    /**
     * Convenience constructor defaulting {@code explicitPromptCaching} from the
     * model id (see {@link #defaultExplicitPromptCaching(String)}).
     */
    public ModelAlias(String id, Boolean available, String alias) {
        this(id, available, alias, defaultExplicitPromptCaching(id));
    }

    /**
     * Returns a copy with the given availability.
     *
     * @param available {@code null} = unknown, {@code true} = reachable, {@code false} = not reachable
     * @return new {@code ModelAlias} with updated availability
     */
    public ModelAlias withAvailable(Boolean available) {
        return new ModelAlias(id, available, alias, explicitPromptCaching);
    }

    /**
     * Returns a copy with the given alias.
     *
     * @param alias the alias to assign (e.g. {@code "sonnet"}, {@code "opus"}, or {@code ""})
     * @return new {@code ModelAlias} with updated alias
     */
    public ModelAlias withAlias(String alias) {
        return new ModelAlias(id, available, alias, explicitPromptCaching);
    }

    /**
     * Returns a copy with the given explicit-prompt-caching flag.
     *
     * @param explicitPromptCaching whether to opt this model into explicit prompt-cache control
     * @return new {@code ModelAlias} with the flag updated
     */
    public ModelAlias withExplicitPromptCaching(boolean explicitPromptCaching) {
        return new ModelAlias(id, available, alias, explicitPromptCaching);
    }

    /**
     * Parses a GPT-family model id's {@code major.minor} version
     * (e.g. {@code "gpt-5.6-terra"} &rarr; {@code 5.6}), or {@code null} if the
     * id doesn't match a recognizable {@code gpt-<major>.<minor>} pattern
     * (custom/unknown ids, non-GPT models like {@code grok-4.5}, etc.).
     */
    public static Double parseGptVersion(String id) {
        if (id == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)(?:^|[^a-z0-9])gpt-(\\d+(?:\\.\\d+)?)").matcher(id);
        if (!m.find()) return null;
        try {
            return Double.parseDouble(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Default value for {@link #explicitPromptCaching}: on for parsed GPT
     * versions &ge; 5.6 (the family whose implicit caching regressed — see
     * the ChatGPT-subscription rate-limit investigation), off for anything
     * else (older GPT models, non-GPT models, or unparseable ids) — those
     * either don't need it or aren't confirmed to accept the 5.6-only fields.
     */
    public static boolean defaultExplicitPromptCaching(String id) {
        Double version = parseGptVersion(id);
        return version != null && version >= 5.6;
    }

    /**
     * Validates alias uniqueness across a list of model aliases.
     *
     * <p>The {@code "custom"} alias is exempt: multiple models may share it,
     * since they are stored in a separate list and injected into the model combo
     * without an env var.
     *
     * @param models the list of model aliases to validate
     * @return {@code null} if valid, or an error message naming the duplicate alias
     */
    public static String validateAliasUniqueness(java.util.List<ModelAlias> models) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (ModelAlias m : models) {
            String a = m.alias();
            if (a != null && !a.isBlank() && !"custom".equals(a)) {
                if (!seen.add(a)) {
                    return "Alias '" + a + "' is assigned to more than one model";
                }
            }
        }
        return null;
    }
}
