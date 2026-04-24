package io.github.nbclaudecodegui.model;

import io.github.nbclaudecodegui.model.HistoryEntry;
import io.github.nbclaudecodegui.model.PromptHistoryStore;
import io.github.nbclaudecodegui.settings.ClaudeCodePreferences;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.swing.SwingUtilities;

/**
 * State container for a single Claude Code session.
 *
 * <p><b>Thread safety:</b> all volatile fields may be read from any thread.
 * All mutations should call the appropriate setter, which:
 * <ol>
 *   <li>Updates the volatile field immediately (visible to all threads).</li>
 *   <li>Dispatches listener notifications on the EDT via
 *       {@link SwingUtilities#invokeLater} (or directly if already on the EDT).</li>
 * </ol>
 *
 * <p>{@link #EDIT_MODE_REGISTRY} is intentionally static and public so that
 * {@code NetBeansMCPHandler} — running on Jetty servlet threads — can read
 * the current edit mode without holding a reference to any particular session.
 */
public final class ClaudeSessionModel {

    // -------------------------------------------------------------------------
    // Static registry
    // -------------------------------------------------------------------------

    /**
     * Cross-session, thread-safe registry of {@link EditMode} keyed by absolute
     * working-directory path.
     *
     * <p>Updated whenever {@link #setEditMode} is called; cleared by
     * {@link #clearEditModeRegistry} when a session stops.
     * Read by {@code NetBeansMCPHandler.getEditModeForCwd()} on servlet threads
     * to decide whether to auto-allow file edits in {@link EditMode#ACCEPT_EDITS}
     * or {@link EditMode#BYPASS_PERMISSIONS} mode.
     */
    public static final ConcurrentHashMap<String, EditMode> EDIT_MODE_REGISTRY =
            new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Listener interface
    // -------------------------------------------------------------------------

    /**
     * Receives notifications when session state changes.
     * All methods are called on the EDT.
     *
     * <p>All methods have default no-op implementations so that implementors
     * only need to override the events they care about.
     */
    public interface ClaudeSessionModelListener {

        /**
         * Called when the session lifecycle transitions to a new state.
         *
         * @param state the new lifecycle state
         */
        default void onLifecycleChanged(SessionLifecycle state) {}

        /**
         * Called when the edit mode changes.
         *
         * @param mode the new mode, or {@code null} if cleared
         */
        default void onEditModeChanged(EditMode mode) {}

        /**
         * Called when the list of available models or the selected index changes.
         *
         * @param models      available model names; never {@code null}
         * @param selectedIdx index of the active model, or {@code -1} if unknown
         */
        default void onModelListChanged(List<String> models, int selectedIdx) {}

        /**
         * Called when a choice-menu prompt appears or is dismissed.
         *
         * @param menu the active choice menu, or {@code null} when dismissed
         */
        default void onChoiceMenuChanged(ChoiceMenuModel menu) {}

        /**
         * Called when the working directory is confirmed or changed.
         *
         * @param dir the working directory
         */
        default void onWorkingDirectoryChanged(File dir) {}

        /**
         * Called when a new plan-file name is detected on screen, or cleared.
         *
         * @param planName the detected plan name, or empty string if none
         */
        default void onPlanNameChanged(String planName) {}
    }

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    /** Current lifecycle phase of the session. */
    private volatile SessionLifecycle lifecycle = SessionLifecycle.STARTING;

    /** The directory Claude is running in; {@code null} until user confirms. */
    private volatile File workingDirectory;

    /**
     * Current edit mode. Also written to {@link #EDIT_MODE_REGISTRY} when set.
     */
    private volatile EditMode editMode;

    /** Name of the selected profile; may be {@code null} for Default. */
    private volatile String selectedProfileName;

    /** Model name detected from the Claude CLI status line; may be {@code null}. */
    private volatile String detectedModelName;

    /** In-session prompt history; EDT-only mutation via {@link #addPromptToHistory}. */
    private final List<String> promptHistory = new ArrayList<>();

    /** Available model names, populated after first READY state. */
    private List<String> availableModels = new ArrayList<>();

    /** Index of the selected model in {@link #availableModels}, or {@code -1}. */
    private int selectedModelIndex = -1;

    /**
     * The choice-menu currently shown to the user, or {@code null} when idle.
     * Set by screen-content detection; cleared when the user answers or Claude
     * removes the menu.
     */
    private ChoiceMenuModel activeChoiceMenu;

    private final List<ClaudeSessionModelListener> listeners = new CopyOnWriteArrayList<>();

    /** Creates a new, empty session model in the {@link SessionLifecycle#STARTING} state. */
    public ClaudeSessionModel() {}

    // -------------------------------------------------------------------------
    // Listener management
    // -------------------------------------------------------------------------

    /**
     * Registers a listener to receive state-change notifications.
     *
     * @param l the listener to add
     */
    public void addListener(ClaudeSessionModelListener l) {
        listeners.add(l);
    }

    /**
     * Removes a previously registered listener. Notifications will no longer
     * be dispatched to it.
     *
     * @param l the listener to remove
     */
    public void removeListener(ClaudeSessionModelListener l) {
        listeners.remove(l);
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    /**
     * Returns the current lifecycle state.
     *
     * @return current lifecycle state
     */
    public SessionLifecycle getLifecycle() { return lifecycle; }

    /**
     * Returns the working directory.
     *
     * @return working directory, or {@code null} if not yet set
     */
    public File getWorkingDirectory() { return workingDirectory; }

    /**
     * Returns the current edit mode.
     *
     * @return edit mode, or {@code null} if not yet set
     */
    public EditMode getEditMode() { return editMode; }

    /**
     * Returns the selected profile name.
     *
     * @return selected profile name, or {@code null} for Default
     */
    public String getSelectedProfileName() { return selectedProfileName; }

    /**
     * Returns the detected model name.
     *
     * @return detected model name, or {@code null}
     */
    public String getDetectedModelName() { return detectedModelName; }

    /**
     * Returns a snapshot of available model names.
     *
     * @return available model names (never {@code null})
     */
    public List<String> getAvailableModels() { return List.copyOf(availableModels); }

    /**
     * Returns the selected model index.
     *
     * @return selected model index, or {@code -1}
     */
    public int getSelectedModelIndex() { return selectedModelIndex; }

    /**
     * Returns the active choice menu.
     *
     * @return active choice menu, or {@code null} when idle
     */
    public ChoiceMenuModel getActiveChoiceMenu() { return activeChoiceMenu; }

    /**
     * Returns a snapshot of in-session prompt history.
     *
     * @return prompt history snapshot
     */
    public List<String> getPromptHistory() { return List.copyOf(promptHistory); }

    // -------------------------------------------------------------------------
    // Setters (each fires the corresponding listener method on EDT)
    // -------------------------------------------------------------------------

    /**
     * Updates the lifecycle state and fires {@link ClaudeSessionModelListener#onLifecycleChanged}.
     *
     * <p>Safe to call from any thread.
     *
     * @param s the new lifecycle state
     */
    public void setLifecycle(SessionLifecycle s) {
        lifecycle = s;
        fireOnEdt(() -> listeners.forEach(l -> l.onLifecycleChanged(s)));
    }

    /**
     * Updates the edit mode, writes to {@link #EDIT_MODE_REGISTRY}, and fires
     * {@link ClaudeSessionModelListener#onEditModeChanged}.
     *
     * <p>If {@link #workingDirectory} is {@code null} the registry update is
     * silently skipped (null-safe).
     *
     * <p>Safe to call from any thread.
     *
     * @param mode the new edit mode, or {@code null} to clear
     */
    public void setEditMode(EditMode mode) {
        editMode = mode;
        if (workingDirectory != null) {
            if (mode != null) {
                EDIT_MODE_REGISTRY.put(workingDirectory.getAbsolutePath(), mode);
            } else {
                EDIT_MODE_REGISTRY.remove(workingDirectory.getAbsolutePath());
            }
        }
        fireOnEdt(() -> listeners.forEach(l -> l.onEditModeChanged(mode)));
    }

    /**
     * Sets the working directory and fires
     * {@link ClaudeSessionModelListener#onWorkingDirectoryChanged}.
     *
     * <p>Safe to call from any thread.
     *
     * @param dir the working directory
     */
    public void setWorkingDirectory(File dir) {
        workingDirectory = dir;
        fireOnEdt(() -> listeners.forEach(l -> l.onWorkingDirectoryChanged(dir)));
    }

    /**
     * Updates the selected profile name.
     *
     * @param name profile name, or {@code null} for Default
     */
    public void setSelectedProfileName(String name) {
        selectedProfileName = name;
    }

    /**
     * Replaces the available model list and selected index, and fires
     * {@link ClaudeSessionModelListener#onModelListChanged}.
     *
     * <p>Pass an empty list and {@code -1} to clear the models (e.g. on session stop).
     *
     * <p>Safe to call from any thread.
     *
     * @param models      list of model name strings
     * @param selectedIdx index of the active model, or {@code -1} if unknown
     */
    public void setModelList(List<String> models, int selectedIdx) {
        availableModels = new ArrayList<>(models);
        selectedModelIndex = selectedIdx;
        List<String> snapshot = List.copyOf(models);
        fireOnEdt(() -> listeners.forEach(l -> l.onModelListChanged(snapshot, selectedIdx)));
    }

    /**
     * Sets the active choice menu and fires
     * {@link ClaudeSessionModelListener#onChoiceMenuChanged}.
     *
     * <p>Pass {@code null} (or call {@link #clearChoiceMenu}) to dismiss the menu.
     *
     * <p>Safe to call from any thread.
     *
     * @param menu the menu to show, or {@code null} to dismiss
     */
    public void setActiveChoiceMenu(ChoiceMenuModel menu) {
        activeChoiceMenu = menu;
        fireOnEdt(() -> listeners.forEach(l -> l.onChoiceMenuChanged(menu)));
    }

    /**
     * Dismisses the active choice menu (sets it to {@code null}) and fires
     * {@link ClaudeSessionModelListener#onChoiceMenuChanged} with {@code null}.
     *
     * <p>Safe to call from any thread.
     */
    public void clearChoiceMenu() {
        setActiveChoiceMenu(null);
    }

    /**
     * Sets the detected plan-file name and fires
     * {@link ClaudeSessionModelListener#onPlanNameChanged}.
     *
     * @param planName detected plan name, or empty string if none
     */
    public void setPlanName(String planName) {
        fireOnEdt(() -> listeners.forEach(l -> l.onPlanNameChanged(planName != null ? planName : "")));
    }

    // -------------------------------------------------------------------------
    // Prompt history
    // -------------------------------------------------------------------------

    /**
     * Prepends {@code text} to the in-session prompt history and persists it.
     * Duplicate entries are moved to the front.
     * The in-memory list is capped at {@link ClaudeCodePreferences#getHistoryMaxDepth()}.
     *
     * <p>Should only be called on the EDT.
     *
     * @param text prompt text to record
     */
    public void addPromptToHistory(String text) {
        promptHistory.remove(text);
        promptHistory.add(0, text);
        int maxDepth = ClaudeCodePreferences.getHistoryMaxDepth();
        while (promptHistory.size() > maxDepth) {
            promptHistory.remove(promptHistory.size() - 1);
        }
        if (workingDirectory != null) {
            PromptHistoryStore.getInstance(workingDirectory.toPath()).add(text);
        }
    }

    /**
     * Loads persisted history for {@code workingDir} into the in-memory list.
     * Should be called after {@link #setWorkingDirectory} when a session starts.
     *
     * <p>Should only be called on the EDT.
     */
    public void loadPersistedHistory() {
        if (workingDirectory == null) return;
        List<HistoryEntry> persisted =
                PromptHistoryStore.getInstance(workingDirectory.toPath()).getAll();
        promptHistory.clear();
        int maxDepth = ClaudeCodePreferences.getHistoryMaxDepth();
        for (HistoryEntry e : persisted) {
            if (promptHistory.size() >= maxDepth) break;
            promptHistory.add(e.getText());
        }
    }

    // -------------------------------------------------------------------------
    // Registry helpers
    // -------------------------------------------------------------------------

    /**
     * Removes the entry for {@link #workingDirectory} from {@link #EDIT_MODE_REGISTRY}.
     * No-op if {@link #workingDirectory} is {@code null}.
     *
     * <p>Call this when a session stops so stale entries do not linger.
     */
    public void clearEditModeRegistry() {
        if (workingDirectory != null) {
            EDIT_MODE_REGISTRY.remove(workingDirectory.getAbsolutePath());
        }
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    /**
     * Runs {@code r} on the EDT.
     *
     * <ul>
     *   <li>If already on the EDT, runs synchronously.</li>
     *   <li>Otherwise, blocks the calling thread until the EDT has executed
     *       {@code r} via {@link SwingUtilities#invokeAndWait}. This guarantees
     *       that all listeners have been notified before any setter returns,
     *       which simplifies reasoning in both production code and tests.</li>
     * </ul>
     */
    private static void fireOnEdt(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(r);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (InvocationTargetException ex) {
                throw new RuntimeException("Listener threw an exception", ex.getCause());
            }
        }
    }
}
