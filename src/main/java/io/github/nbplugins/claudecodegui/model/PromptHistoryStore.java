package io.github.nbplugins.claudecodegui.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.nbplugins.claudecodegui.settings.ClaudeCodePreferences;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persistent per-project prompt history backed by a JSON file.
 *
 * <p>For NetBeans projects (directory contains {@code nbproject/}), the file is
 * {@code {workingDir}/nbproject/private/claude-plugin-history.json}.
 * For arbitrary directories the file is
 * {@code ~/.netbeans/claude-plugin/extra-dirs/{sha256}/history.json}.
 *
 * <p>Obtain instances via {@link #getInstance(Path)}.
 */
public class PromptHistoryStore {

    private static final Logger LOG = Logger.getLogger(PromptHistoryStore.class.getName());
    private static final TypeReference<List<Map<String, String>>> LIST_TYPE =
            new TypeReference<>() {};

    private static final ConcurrentHashMap<Path, PromptHistoryStore> CACHE =
            new ConcurrentHashMap<>();

    private final Path        file;
    private final ObjectMapper mapper;

    /** Package-private constructor so tests can inject a custom file path. */
    PromptHistoryStore(Path file) {
        this.file = file;
        mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * Returns (or creates) the store for the given working directory.
     *
     * @param workingDir absolute path of the working directory
     * @return store instance
     */
    public static PromptHistoryStore getInstance(Path workingDir) {
        Path key = workingDir.toAbsolutePath().normalize();
        return CACHE.computeIfAbsent(key, k -> new PromptHistoryStore(resolveFile(k)));
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Prepends {@code text} to the history.
     * Deduplicates, trims to max depth, and applies TTL on every write.
     *
     * @param text prompt text to record
     */
    public void add(String text) {
        if (text == null || text.isBlank()) return;
        List<HistoryEntry> entries = load();
        entries.removeIf(e -> text.equals(e.getText()));
        entries.add(0, new HistoryEntry(text, Instant.now()));

        int maxDepth = ClaudeCodePreferences.getHistoryMaxDepth();
        while (entries.size() > maxDepth) entries.remove(entries.size() - 1);

        int ttlDays = ClaudeCodePreferences.getHistoryTtlDays();
        if (ttlDays > 0) {
            Instant cutoff = Instant.now().minusSeconds((long) ttlDays * 86_400);
            entries.removeIf(e -> e.getTime().isBefore(cutoff));
        }
        save(entries);
    }

    /**
     * Returns all history entries, newest-first.
     *
     * @return unmodifiable list
     */
    public List<HistoryEntry> getAll() {
        return List.copyOf(load());
    }

    /**
     * Removes the given entries from the history (matched by text).
     *
     * @param toDelete entries to remove
     */
    public void delete(List<HistoryEntry> toDelete) {
        if (toDelete == null || toDelete.isEmpty()) return;
        List<HistoryEntry> entries = load();
        toDelete.forEach(d -> entries.removeIf(e -> e.getText().equals(d.getText())));
        save(entries);
    }

    /**
     * Removes all entries older than {@code cutoff}.
     *
     * @param cutoff entries with {@code time.isBefore(cutoff)} are deleted
     */
    public void deleteOlderThan(Instant cutoff) {
        if (cutoff == null) return;
        List<HistoryEntry> entries = load();
        if (entries.removeIf(e -> e.getTime().isBefore(cutoff))) save(entries);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    List<HistoryEntry> load() {
        if (!Files.exists(file)) return new ArrayList<>();
        try {
            List<Map<String, String>> raw = mapper.readValue(file.toFile(), LIST_TYPE);
            List<HistoryEntry> result = new ArrayList<>(raw.size());
            for (Map<String, String> m : raw) {
                String t = m.get("text"), time = m.get("time");
                if (t != null && time != null) {
                    try { result.add(new HistoryEntry(t, Instant.parse(time))); }
                    catch (Exception ignored) {}
                }
            }
            return result;
        } catch (IOException ex) {
            LOG.log(Level.WARNING, "Failed to parse history from " + file, ex);
            return new ArrayList<>();
        }
    }

    void save(List<HistoryEntry> entries) {
        try {
            Files.createDirectories(file.getParent());
            List<Map<String, String>> raw = new ArrayList<>(entries.size());
            for (HistoryEntry e : entries) {
                raw.add(Map.of("text", e.getText(), "time", e.getTime().toString()));
            }
            mapper.writeValue(file.toFile(), raw);
        } catch (IOException ex) {
            LOG.log(Level.WARNING, "Failed to save history to " + file, ex);
        }
    }

    /** Directly inject an entry (bypasses dedup/TTL) — for tests only. */
    void injectEntry(HistoryEntry entry) {
        List<HistoryEntry> entries = load();
        entries.add(0, entry);
        save(entries);
    }

    private static Path resolveFile(Path workingDir) {
        if (Files.isDirectory(workingDir.resolve("nbproject"))) {
            return workingDir.resolve("nbproject/private/claude-plugin-history.json");
        }
        return extraDirsBase().resolve(sha256(workingDir.toString()) + "/history.json");
    }

    static Path extraDirsBase() {
        try {
            org.openide.modules.Places.getUserDirectory();
            java.io.File ud = org.openide.modules.Places.getUserDirectory();
            if (ud != null) {
                return ud.getParentFile().toPath().resolve("claude-plugin/extra-dirs");
            }
        } catch (Exception ignored) {}
        return Path.of(System.getProperty("user.home"), ".netbeans/claude-plugin/extra-dirs");
    }

    static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception ex) {
            return Integer.toHexString(input.hashCode());
        }
    }
}
