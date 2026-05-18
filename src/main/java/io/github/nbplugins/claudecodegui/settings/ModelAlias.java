package io.github.nbplugins.claudecodegui.settings;

/**
 * Represents a single model alias entry in a connection profile.
 *
 * <p>{@code available} is transient — it reflects the result of the last
 * "Fetch" call and is not persisted.
 *
 * @param id        model identifier as returned by {@code /v1/models}
 * @param available {@code null} = unknown, {@code true} = reachable, {@code false} = not reachable
 * @param alias     CC standard alias to map to this model: {@code ""}, {@code "sonnet"},
 *                  {@code "opus"}, or {@code "haiku"}
 */
public record ModelAlias(String id, Boolean available, String alias) {

    /** Validates and normalizes the record components. */
    public ModelAlias {
        if (id == null) throw new IllegalArgumentException("id must not be null");
        if (alias == null) alias = "";
    }

    /**
     * Returns a copy with the given availability.
     *
     * @param available {@code null} = unknown, {@code true} = reachable, {@code false} = not reachable
     * @return new {@code ModelAlias} with updated availability
     */
    public ModelAlias withAvailable(Boolean available) {
        return new ModelAlias(id, available, alias);
    }

    /**
     * Returns a copy with the given alias.
     *
     * @param alias the alias to assign (e.g. {@code "sonnet"}, {@code "opus"}, or {@code ""})
     * @return new {@code ModelAlias} with updated alias
     */
    public ModelAlias withAlias(String alias) {
        return new ModelAlias(id, available, alias);
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
