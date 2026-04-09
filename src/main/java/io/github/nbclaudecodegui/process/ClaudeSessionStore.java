package io.github.nbclaudecodegui.process;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nbclaudecodegui.model.SavedSession;
import io.github.nbclaudecodegui.settings.ClaudeCodePreferences;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Utility for reading and managing Claude Code session JSONL files.
 *
 * <p>Claude Code stores sessions as JSONL files at:
 * {@code <claudeConfigDir>/projects/<hashed-path>/<session-id>.jsonl}
 *
 * <p>The hash is computed by replacing every {@code /} in the absolute working
 * directory path with {@code -}.
 */
public final class ClaudeSessionStore {

    private static final Logger LOG = Logger.getLogger(ClaudeSessionStore.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ClaudeSessionStore() {}

    // -------------------------------------------------------------------------
    // Hash
    // -------------------------------------------------------------------------

    /**
     * Computes the directory-name hash that Claude Code uses to map a working
     * directory to a sessions subdirectory.
     *
     * <p>The algorithm is: replace every {@code /} in the absolute path string
     * with {@code -}.
     *
     * @param workingDir absolute path to the working directory
     * @return directory hash string
     */
    static String computeHash(Path workingDir) {
        return workingDir.toAbsolutePath().toString().replace('/', '-');
    }

    // -------------------------------------------------------------------------
    // Directory resolution
    // -------------------------------------------------------------------------

    /**
     * Returns the directory that contains session JSONL files for the given
     * working directory and Claude config dir.
     *
     * @param workingDir      working directory; must not be {@code null}
     * @param claudeConfigDir base config dir; {@code null} means {@code ~/.claude}
     * @return path to the sessions directory (may not exist)
     */
    static Path sessionsDir(Path workingDir, Path claudeConfigDir) {
        Path configDir = claudeConfigDir != null
                ? claudeConfigDir
                : Path.of(System.getProperty("user.home"), ".claude");
        return configDir.resolve("projects").resolve(computeHash(workingDir));
    }

    // -------------------------------------------------------------------------
    // List
    // -------------------------------------------------------------------------

    /**
     * Lists all saved sessions for the given working directory.
     *
     * <p>Each JSONL file in the session directory is parsed to extract:
     * <ul>
     *   <li>{@code sessionId} — filename without {@code .jsonl} extension</li>
     *   <li>{@code slug} — from the first line with a {@code sessionId} field</li>
     *   <li>{@code customTitle} — from the last line whose {@code type} is
     *       {@code "custom-title"}</li>
     *   <li>{@code createdAt} / {@code lastAt} — min/max {@code timestamp} values</li>
     * </ul>
     *
     * <p>Sessions with no parseable timestamps are excluded. Results are sorted
     * by {@code lastAt} descending (most recent first).
     *
     * @param workingDir      working directory to look up sessions for
     * @param claudeConfigDir Claude config base dir; {@code null} means {@code ~/.claude}
     * @return mutable list of sessions, sorted by {@code lastAt} desc
     */
    public static List<SavedSession> listSessions(Path workingDir, Path claudeConfigDir) {
        return listSessions(workingDir, claudeConfigDir, ClaudeCodePreferences.getSessionListLimit());
    }

    /**
     * Lists saved sessions for the given working directory, limited to the most
     * recent {@code limit} sessions using a two-phase read for performance.
     *
     * <p>Phase 1: reads only file modification times (no content), sorts
     * descending, takes top {@code limit} candidates.
     * Phase 2: parses only those candidate files fully.
     *
     * @param workingDir      working directory to look up sessions for
     * @param claudeConfigDir Claude config base dir; {@code null} means {@code ~/.claude}
     * @param limit           maximum number of sessions to return
     * @return mutable list of sessions, sorted by {@code lastAt} desc
     */
    public static List<SavedSession> listSessions(Path workingDir, Path claudeConfigDir, int limit) {
        Path dir = sessionsDir(workingDir, claudeConfigDir);
        if (!Files.isDirectory(dir)) {
            return new ArrayList<>();
        }

        // Phase 1: read only file modification times, sort desc, take top candidates
        List<long[]> filesSorted = new ArrayList<>(); // [0]=mtime millis, [1]=index into allFiles
        List<Path> allFiles = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .forEach(f -> {
                        long mtime = 0;
                        try { mtime = Files.getLastModifiedTime(f).toMillis(); } catch (IOException ignored) {}
                        int idx = allFiles.size();
                        allFiles.add(f);
                        filesSorted.add(new long[]{mtime, idx});
                    });
        } catch (IOException e) {
            LOG.warning("Could not list sessions in " + dir + ": " + e.getMessage());
        }

        filesSorted.sort((a, b) -> Long.compare(b[0], a[0]));
        int take = Math.min(limit, filesSorted.size());
        List<Path> candidates = new ArrayList<>(take);
        for (int i = 0; i < take; i++) {
            candidates.add(allFiles.get((int) filesSorted.get(i)[1]));
        }

        // Phase 2: parse candidates fully
        List<SavedSession> sessions = new ArrayList<>();
        for (Path file : candidates) {
            try {
                SavedSession s = parseSessionFile(file);
                if (s != null) sessions.add(s);
            } catch (Exception e) {
                LOG.fine("Could not parse session file " + file + ": " + e.getMessage());
            }
        }

        sessions.sort(Comparator.comparing(SavedSession::lastAt).reversed());
        return sessions;
    }

    private static final int FIRST_PROMPT_MAX_LEN = 120;

    private static SavedSession parseSessionFile(Path file) throws IOException {
        String name = file.getFileName().toString();
        String sessionId = name.endsWith(".jsonl") ? name.substring(0, name.length() - 6) : name;

        Instant minTs = null;
        Instant maxTs = null;
        String slug = null;
        String customTitle = null;
        String firstPrompt = null;

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (String line : lines) {
            if (line.isBlank()) continue;
            try {
                JsonNode node = MAPPER.readTree(line);

                // Extract timestamp
                JsonNode tsNode = node.get("timestamp");
                if (tsNode != null && tsNode.isTextual()) {
                    try {
                        Instant ts = Instant.parse(tsNode.asText());
                        if (minTs == null || ts.isBefore(minTs)) minTs = ts;
                        if (maxTs == null || ts.isAfter(maxTs)) maxTs = ts;
                    } catch (Exception ignored) {}
                }

                // Extract slug from first line that has sessionId field
                if (slug == null && node.has("sessionId")) {
                    JsonNode slugNode = node.get("slug");
                    if (slugNode != null && slugNode.isTextual()) {
                        slug = slugNode.asText();
                    }
                }

                // Track last custom-title entry
                JsonNode typeNode = node.get("type");
                String type = typeNode != null ? typeNode.asText() : "";
                if ("custom-title".equals(type)) {
                    JsonNode ctNode = node.get("customTitle");
                    if (ctNode != null && ctNode.isTextual()) {
                        customTitle = ctNode.asText();
                    }
                }

                // Extract first user prompt (type=="user", message.content is a string)
                if (firstPrompt == null && "user".equals(type)) {
                    JsonNode msgNode = node.get("message");
                    if (msgNode != null) {
                        JsonNode contentNode = msgNode.get("content");
                        if (contentNode != null && contentNode.isTextual()) {
                            String text = contentNode.asText();
                            firstPrompt = text.length() > FIRST_PROMPT_MAX_LEN
                                    ? text.substring(0, FIRST_PROMPT_MAX_LEN)
                                    : text;
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        if (minTs == null) return null; // no valid timestamps → skip
        return new SavedSession(sessionId, minTs, maxTs, slug, customTitle, firstPrompt);
    }

    // -------------------------------------------------------------------------
    // Delete
    // -------------------------------------------------------------------------

    /**
     * Deletes the session JSONL file for the given session ID.
     *
     * @param workingDir      working directory
     * @param claudeConfigDir Claude config base dir; {@code null} means {@code ~/.claude}
     * @param sessionId       session ID (filename without {@code .jsonl})
     * @return {@code true} if the file was deleted; {@code false} if it did not exist
     */
    public static boolean deleteSession(Path workingDir, Path claudeConfigDir, String sessionId) {
        Path file = sessionsDir(workingDir, claudeConfigDir).resolve(sessionId + ".jsonl");
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.warning("Could not delete session file " + file + ": " + e.getMessage());
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Rename
    // -------------------------------------------------------------------------

    /**
     * Appends a {@code custom-title} entry to the given session's JSONL file.
     *
     * <p>The appended line has the format:
     * <pre>{"type":"custom-title","customTitle":"...","sessionId":"...","timestamp":"..."}</pre>
     *
     * @param workingDir      working directory
     * @param claudeConfigDir Claude config base dir; {@code null} means {@code ~/.claude}
     * @param sessionId       session ID to rename
     * @param newName         new custom title
     * @throws IOException if the file cannot be written
     */
    public static void renameSession(Path workingDir, Path claudeConfigDir,
                                     String sessionId, String newName) throws IOException {
        Path file = sessionsDir(workingDir, claudeConfigDir).resolve(sessionId + ".jsonl");
        if (!Files.exists(file)) {
            throw new IOException("Session file not found: " + file);
        }
        String timestamp = Instant.now().toString();
        String line = MAPPER.writeValueAsString(
                MAPPER.createObjectNode()
                        .put("type", "custom-title")
                        .put("customTitle", newName)
                        .put("sessionId", sessionId)
                        .put("timestamp", timestamp)
        );
        // Append with newline
        String existing = Files.readString(file, StandardCharsets.UTF_8);
        String newContent = existing.endsWith("\n") ? existing + line + "\n" : existing + "\n" + line + "\n";
        Files.writeString(file, newContent, StandardCharsets.UTF_8);
        LOG.fine("Renamed session " + sessionId + " to '" + newName + "'");
    }

    // -------------------------------------------------------------------------
    // Find most recent
    // -------------------------------------------------------------------------

    /**
     * Returns the most recent session for the given working directory, or
     * {@code null} if no sessions exist.
     *
     * @param workingDir      working directory
     * @param claudeConfigDir Claude config base dir; {@code null} means {@code ~/.claude}
     * @return most recent session, or {@code null}
     */
    public static SavedSession findMostRecent(Path workingDir, Path claudeConfigDir) {
        List<SavedSession> sessions = listSessions(workingDir, claudeConfigDir, 1);
        return sessions.isEmpty() ? null : sessions.get(0);
    }
}
