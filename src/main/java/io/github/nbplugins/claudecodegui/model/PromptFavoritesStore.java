package io.github.nbplugins.claudecodegui.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persistent favorites store (project + global).
 *
 * <p>Project favorites are stored in
 * {@code {workingDir}/nbproject/claude-plugin-favorites.json} (NB projects) or
 * {@code ~/.netbeans/claude-plugin/extra-dirs/{sha256}/favorites.json} (arbitrary dirs).
 *
 * <p>Global favorites are stored in
 * {@code ~/.netbeans/claude-plugin/global/favorites.json}.
 *
 * <p>Obtain instances via {@link #getInstance(Path)}.
 */
public final class PromptFavoritesStore {

    private static final Logger LOG = Logger.getLogger(PromptFavoritesStore.class.getName());

    private static final ConcurrentHashMap<Path, PromptFavoritesStore> CACHE =
            new ConcurrentHashMap<>();

    private final Path         projectFile;
    private final Path         globalFile;
    private final ObjectMapper mapper;

    /**
     * Visible for tests — use {@link #getInstance(Path)} in production code.
     *
     * @param projectFile path to the project-scoped favorites JSON file
     * @param globalFile  path to the global favorites JSON file
     */
    public PromptFavoritesStore(Path projectFile, Path globalFile) {
        this.projectFile = projectFile;
        this.globalFile  = globalFile;
        mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Returns (or creates) the store for the given working directory.
     *
     * <p><b>Note for tests:</b> the global file always resolves to the real user path
     * (see {@link #resolveGlobalFile()}), regardless of {@code workingDir}.
     * Tests that call {@link #addGlobal} must use the package-private constructor
     * {@code new PromptFavoritesStore(projectFile, globalFile)} with both paths
     * inside a temp directory.
     *
     * @param workingDir absolute path of the working directory
     * @return store instance
     */
    public static PromptFavoritesStore getInstance(Path workingDir) {
        Path key = workingDir.toAbsolutePath().normalize();
        return CACHE.computeIfAbsent(key, k -> {
            Path projFile = resolveProjectFile(k);
            Path globalF  = resolveGlobalFile();
            return new PromptFavoritesStore(projFile, globalF);
        });
    }

    // -------------------------------------------------------------------------
    // Combined read API
    // -------------------------------------------------------------------------

    /**
     * Returns combined Project + Global favorites, sorted by
     * {@code (project_pos, global_pos)}: project entries first (by their
     * stored position), then global entries (by their list index).
     *
     * @return unmodifiable list with scope set on each entry
     */
    public List<FavoriteEntry> getAll() {
        ProjectData pd = loadProject();
        List<FavoriteEntry> globals = loadGlobal();

        // Build project entries ordered by project_pos
        Map<String, Integer> posMap = new HashMap<>();
        for (OrderingEntry oe : pd.ordering) posMap.put(oe.id, oe.projectPos);

        List<FavoriteEntry> projectEntries = new ArrayList<>(pd.favorites);
        projectEntries.sort(Comparator.comparingInt(
                e -> posMap.getOrDefault(e.getId().toString(), Integer.MAX_VALUE)));

        List<FavoriteEntry> result = new ArrayList<>();
        result.addAll(projectEntries);
        result.addAll(globals);
        return List.copyOf(result);
    }

    /**
     * Returns all favorites that have a non-null shortcut (for ShortcutMatcher).
     *
     * @return unmodifiable list
     */
    public List<FavoriteEntry> getAllWithShortcuts() {
        return getAll().stream()
                .filter(e -> e.getShortcut() != null && !e.getShortcut().isBlank())
                .toList();
    }

    // -------------------------------------------------------------------------
    // Project favorites
    // -------------------------------------------------------------------------

    /**
     * Returns per-project favorites in stored order.
     *
     * @return immutable list of project-scoped favorites
     */
    public List<FavoriteEntry> getProject() {
        ProjectData pd = loadProject();
        Map<String, Integer> posMap = new HashMap<>();
        for (OrderingEntry oe : pd.ordering) posMap.put(oe.id, oe.projectPos);
        List<FavoriteEntry> list = new ArrayList<>(pd.favorites);
        list.sort(Comparator.comparingInt(
                e -> posMap.getOrDefault(e.getId().toString(), Integer.MAX_VALUE)));
        return List.copyOf(list);
    }

    /**
     * Appends a new project favorite.
     *
     * @param entry the entry to add (scope is forced to PROJECT)
     */
    public void addProject(FavoriteEntry entry) {
        if (entry == null) return;
        entry.setScope(FavoriteEntry.Scope.PROJECT);
        ProjectData pd = loadProject();
        pd.favorites.add(entry);
        int pos = pd.ordering.stream().mapToInt(oe -> oe.projectPos).max().orElse(-1) + 1;
        pd.ordering.add(new OrderingEntry(entry.getId().toString(), pos));
        saveProject(pd);
    }

    /**
     * Removes the given project favorites (matched by ID).
     *
     * @param toDelete entries to remove
     */
    public void delete(List<FavoriteEntry> toDelete) {
        if (toDelete == null || toDelete.isEmpty()) return;
        ProjectData pd = loadProject();
        toDelete.forEach(d -> {
            pd.favorites.removeIf(e -> e.getId().equals(d.getId()));
            pd.ordering.removeIf(oe -> oe.id.equals(d.getId().toString()));
        });
        saveProject(pd);
    }

    /**
     * Saves {@code project_pos} for all project entries based on the given order.
     *
     * @param ordered list in desired display order
     */
    public void reorder(List<FavoriteEntry> ordered) {
        ProjectData pd = loadProject();
        // Rebuild ordering from the given list
        Map<String, Integer> newPos = new HashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            newPos.put(ordered.get(i).getId().toString(), i);
        }
        for (OrderingEntry oe : pd.ordering) {
            if (newPos.containsKey(oe.id)) oe.projectPos = newPos.get(oe.id);
        }
        saveProject(pd);
    }

    /**
     * Updates all changed fields of an existing entry (matched by ID).
     * Works for both project and global entries.
     *
     * @param updated the entry with new field values
     */
    public void update(FavoriteEntry updated) {
        if (updated == null) return;
        if (updated.getScope() == FavoriteEntry.Scope.PROJECT) {
            ProjectData pd = loadProject();
            for (int i = 0; i < pd.favorites.size(); i++) {
                if (pd.favorites.get(i).getId().equals(updated.getId())) {
                    pd.favorites.set(i, updated);
                    break;
                }
            }
            saveProject(pd);
        } else {
            List<FavoriteEntry> globals = loadGlobal();
            for (int i = 0; i < globals.size(); i++) {
                if (globals.get(i).getId().equals(updated.getId())) {
                    globals.set(i, updated);
                    break;
                }
            }
            saveGlobal(globals);
        }
    }

    /**
     * Moves a project favorite to global: removes from project, adds as new
     * global with new UUID and scope=GLOBAL.
     *
     * @param entry project entry to promote
     */
    public void toGlobal(FavoriteEntry entry) {
        if (entry == null) return;
        delete(List.of(entry));
        FavoriteEntry global = new FavoriteEntry(
                entry.getText(), UUID.randomUUID(), entry.getShortcut(), FavoriteEntry.Scope.GLOBAL);
        addGlobal(global);
    }

    // -------------------------------------------------------------------------
    // Global favorites
    // -------------------------------------------------------------------------

    /**
     * Returns all global favorites in stored order.
     *
     * @return immutable list of global favorites
     */
    public List<FavoriteEntry> getGlobal() {
        return List.copyOf(loadGlobal());
    }

    /**
     * Appends a new global favorite.
     *
     * @param entry the entry to add (scope is forced to GLOBAL)
     */
    public void addGlobal(FavoriteEntry entry) {
        if (entry == null) return;
        entry.setScope(FavoriteEntry.Scope.GLOBAL);
        List<FavoriteEntry> list = loadGlobal();
        list.add(entry);
        saveGlobal(list);
    }

    /**
     * Removes the given global favorites (matched by ID).
     *
     * @param toDelete entries to remove
     */
    public void deleteGlobal(List<FavoriteEntry> toDelete) {
        if (toDelete == null || toDelete.isEmpty()) return;
        List<FavoriteEntry> list = loadGlobal();
        toDelete.forEach(d -> list.removeIf(e -> e.getId().equals(d.getId())));
        saveGlobal(list);
    }

    /**
     * Replaces the global list with the provided order.
     *
     * @param reordered the full global list in the desired display order
     */
    public void reorderGlobal(List<FavoriteEntry> reordered) {
        saveGlobal(new ArrayList<>(reordered));
    }

    // -------------------------------------------------------------------------
    // Internal — project file
    // -------------------------------------------------------------------------

    ProjectData loadProject() {
        if (!Files.exists(projectFile)) return new ProjectData();
        try {
            JsonNode root = mapper.readTree(projectFile.toFile());
            ProjectData pd = new ProjectData();
            JsonNode favNode = root.get("projectFavorites");
            if (favNode != null && favNode.isArray()) {
                for (JsonNode n : favNode) {
                    FavoriteEntry e = nodeToFavorite(n, FavoriteEntry.Scope.PROJECT);
                    if (e != null) pd.favorites.add(e);
                }
            }
            JsonNode ordNode = root.get("ordering");
            if (ordNode != null && ordNode.isArray()) {
                for (JsonNode n : ordNode) {
                    String id  = n.path("id").asText(null);
                    int    pos = n.path("project_pos").asInt(0);
                    if (id != null) pd.ordering.add(new OrderingEntry(id, pos));
                }
            }
            return pd;
        } catch (IOException ex) {
            LOG.log(Level.WARNING, "Failed to load project favorites from " + projectFile, ex);
            return new ProjectData();
        }
    }

    void saveProject(ProjectData pd) {
        try {
            Files.createDirectories(projectFile.getParent());
            ObjectNode root = mapper.createObjectNode();
            ArrayNode favArr = root.putArray("projectFavorites");
            for (FavoriteEntry e : pd.favorites) favArr.add(favoriteToNode(e));
            ArrayNode ordArr = root.putArray("ordering");
            for (OrderingEntry oe : pd.ordering) {
                ObjectNode on = mapper.createObjectNode();
                on.put("id", oe.id);
                on.put("project_pos", oe.projectPos);
                ordArr.add(on);
            }
            mapper.writeValue(projectFile.toFile(), root);
        } catch (IOException ex) {
            LOG.log(Level.WARNING, "Failed to save project favorites to " + projectFile, ex);
        }
    }

    // -------------------------------------------------------------------------
    // Internal — global file
    // -------------------------------------------------------------------------

    List<FavoriteEntry> loadGlobal() {
        if (!Files.exists(globalFile)) return new ArrayList<>();
        try {
            JsonNode root = mapper.readTree(globalFile.toFile());
            List<FavoriteEntry> result = new ArrayList<>();
            if (root.isArray()) {
                for (JsonNode n : root) {
                    FavoriteEntry e = nodeToFavorite(n, FavoriteEntry.Scope.GLOBAL);
                    if (e != null) result.add(e);
                }
            }
            return result;
        } catch (IOException ex) {
            LOG.log(Level.WARNING, "Failed to load global favorites from " + globalFile, ex);
            return new ArrayList<>();
        }
    }

    void saveGlobal(List<FavoriteEntry> list) {
        try {
            Files.createDirectories(globalFile.getParent());
            ArrayNode arr = mapper.createArrayNode();
            for (FavoriteEntry e : list) arr.add(favoriteToNode(e));
            mapper.writeValue(globalFile.toFile(), arr);
        } catch (IOException ex) {
            LOG.log(Level.WARNING, "Failed to save global favorites to " + globalFile, ex);
        }
    }

    // -------------------------------------------------------------------------
    // JSON helpers
    // -------------------------------------------------------------------------

    private static FavoriteEntry nodeToFavorite(JsonNode n, FavoriteEntry.Scope defaultScope) {
        String text = n.path("text").asText(null);
        if (text == null) return null;
        String idStr   = n.path("id").asText(null);
        UUID   id      = idStr != null ? tryParseUUID(idStr) : UUID.randomUUID();
        String shortcut = n.path("shortcut").asText(null);
        String scopeStr = n.path("scope").asText(null);
        FavoriteEntry.Scope scope = scopeStr != null ? FavoriteEntry.Scope.valueOf(scopeStr) : defaultScope;
        return new FavoriteEntry(text, id, shortcut, scope);
    }

    private ObjectNode favoriteToNode(FavoriteEntry e) {
        ObjectNode n = mapper.createObjectNode();
        n.put("id",   e.getId().toString());
        n.put("text", e.getText());
        n.put("scope", e.getScope().name());
        if (e.getShortcut() != null) n.put("shortcut", e.getShortcut());
        return n;
    }

    private static UUID tryParseUUID(String s) {
        try { return UUID.fromString(s); }
        catch (Exception ex) { return UUID.randomUUID(); }
    }

    // -------------------------------------------------------------------------
    // File resolution
    // -------------------------------------------------------------------------

    private static Path resolveProjectFile(Path workingDir) {
        if (Files.isDirectory(workingDir.resolve("nbproject"))) {
            return workingDir.resolve("nbproject/claude-plugin-favorites.json");
        }
        return PromptHistoryStore.extraDirsBase()
                .resolve(PromptHistoryStore.sha256(workingDir.toString()) + "/favorites.json");
    }

    private static Path resolveGlobalFile() {
        try {
            java.io.File ud = org.openide.modules.Places.getUserDirectory();
            if (ud != null) {
                return ud.getParentFile().toPath().resolve("claude-plugin/global/favorites.json");
            }
        } catch (Exception ignored) {}
        return Path.of(System.getProperty("user.home"), ".netbeans/claude-plugin/global/favorites.json");
    }

    // -------------------------------------------------------------------------
    // Inner types
    // -------------------------------------------------------------------------

    static final class ProjectData {
        List<FavoriteEntry> favorites = new ArrayList<>();
        List<OrderingEntry> ordering  = new ArrayList<>();
    }

    static final class OrderingEntry {
        String id;
        int    projectPos;
        OrderingEntry(String id, int projectPos) {
            this.id = id; this.projectPos = projectPos;
        }
    }
}
