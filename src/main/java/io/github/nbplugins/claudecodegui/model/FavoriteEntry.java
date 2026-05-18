package io.github.nbplugins.claudecodegui.model;

import java.util.UUID;

/**
 * A saved favorite prompt with optional keyboard shortcut and scope.
 */
public final class FavoriteEntry extends PromptEntry {

    /** Scope of a favorite: project-specific or global. */
    public enum Scope {
        /** Shared across all projects. */
        GLOBAL,
        /** Specific to the current project. */
        PROJECT
    }

    private final UUID   id;
    /** Optional keyboard shortcut string, or {@code null} if none. */
    private String shortcut;
    /** Scope of this entry (GLOBAL or PROJECT). */
    private Scope  scope;

    /**
     * Creates a favorite entry with explicit fields.
     *
     * @param text     the prompt text
     * @param id       unique identifier for this entry
     * @param shortcut keyboard shortcut, or {@code null}
     * @param scope    entry scope (GLOBAL or PROJECT)
     */
    public FavoriteEntry(String text, UUID id, String shortcut, Scope scope) {
        super(text);
        this.id       = id;
        this.shortcut = shortcut;
        this.scope    = scope;
    }

    /**
     * Creates a new PROJECT-scoped favorite with a random UUID.
     *
     * @param text the prompt text
     * @return new PROJECT-scoped favorite entry
     */
    public static FavoriteEntry ofProject(String text) {
        return new FavoriteEntry(text, UUID.randomUUID(), null, Scope.PROJECT);
    }

    /**
     * Creates a new GLOBAL-scoped favorite with a random UUID.
     *
     * @param text the prompt text
     * @return new GLOBAL-scoped favorite entry
     */
    public static FavoriteEntry ofGlobal(String text) {
        return new FavoriteEntry(text, UUID.randomUUID(), null, Scope.GLOBAL);
    }

    /**
     * Returns the unique identifier of this entry.
     *
     * @return entry UUID
     */
    public UUID   getId()       { return id; }

    /**
     * Returns the keyboard shortcut, or {@code null} if none.
     *
     * @return shortcut string or {@code null}
     */
    public String getShortcut() { return shortcut; }

    /**
     * Returns the scope (GLOBAL or PROJECT).
     *
     * @return entry scope
     */
    public Scope  getScope()    { return scope; }

    /**
     * Sets the keyboard shortcut.
     *
     * @param shortcut new shortcut string, or {@code null} to clear
     */
    public void setShortcut(String shortcut) { this.shortcut = shortcut; }

    /**
     * Sets the scope.
     *
     * @param scope new scope
     */
    public void setScope(Scope scope)        { this.scope    = scope;    }
}
