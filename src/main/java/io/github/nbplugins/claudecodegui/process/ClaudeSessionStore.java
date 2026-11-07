package io.github.nbplugins.claudecodegui.process;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nbplugins.claudecodegui.model.SavedSession;
import io.github.nbplugins.claudecodegui.settings.ClaudeCodePreferences;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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

    /**
     * Size of the block read at a time when scanning a session file backwards
     * for the prompt a running process just appended. The scan stops at the
     * first record older than the cutoff, so the whole file is read only when
     * the conversation is entirely newer than it.
     */
    private static final int PROMPT_EVIDENCE_BLOCK_BYTES = 512 * 1024;

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

    /**
     * Returns the session directory for diagnostics/logging.
     *
     * @param workingDir      working directory, or {@code null}
     * @param claudeConfigDir Claude config base dir; {@code null} means {@code ~/.claude}
     * @return session directory, or {@code "<unknown>"} when the working dir is missing
     */
    public static String sessionDirForLog(Path workingDir, Path claudeConfigDir) {
        return workingDir != null ? sessionsDir(workingDir, claudeConfigDir).toString() : "<unknown>";
    }

    // -------------------------------------------------------------------------
    // Existence check
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if at least one session JSONL file exists for the
     * given working directory.  Does not parse any file content.
     *
     * @param workingDir      working directory to check
     * @param claudeConfigDir Claude config base dir; {@code null} means {@code ~/.claude}
     * @return {@code true} if any {@code .jsonl} file is found
     */
    public static boolean hasAnySessions(Path workingDir, Path claudeConfigDir) {
        Path dir = sessionsDir(workingDir, claudeConfigDir);
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.anyMatch(p -> p.getFileName().toString().endsWith(".jsonl"));
        } catch (IOException e) {
            LOG.fine("Could not check sessions in " + dir + ": " + e.getMessage());
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Session identity helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the IDs of all session JSONL files for the given working directory.
     *
     * @param workingDir      working directory
     * @param claudeConfigDir Claude config base dir; {@code null} means {@code ~/.claude}
     * @return mutable set of session IDs (file names without {@code .jsonl})
     */
    public static Set<String> listSessionIds(Path workingDir, Path claudeConfigDir) {
        Path dir = sessionsDir(workingDir, claudeConfigDir);
        Set<String> ids = new LinkedHashSet<>();
        if (!Files.isDirectory(dir)) {
            return ids;
        }
        try (Stream<Path> files = Files.list(dir)) {
            files.map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(".jsonl"))
                    .forEach(name -> ids.add(name.substring(0, name.length() - 6)));
        } catch (IOException e) {
            LOG.fine("Could not list session ids in " + dir + ": " + e.getMessage());
        }
        return ids;
    }

    /**
     * Returns the ID of the most recently modified session JSONL file.
     *
     * <p>Uses only file modification times (no parsing) — this mirrors the session
     * that {@code claude --continue} resumes.
     *
     * @param workingDir      working directory
     * @param claudeConfigDir Claude config base dir; {@code null} means {@code ~/.claude}
     * @return session ID, or {@code null} if no session file exists
     */
    public static String findLatestSessionId(Path workingDir, Path claudeConfigDir) {
        Path newest = newestSessionFile(sessionsDir(workingDir, claudeConfigDir), null, 0);
        return newest != null ? sessionIdOf(newest) : null;
    }

    /**
     * Returns the ID of a session file created since a launch: the newest JSONL file
     * whose ID is not in {@code excludeIds} and whose mtime is at least
     * {@code minMtimeMillis}. If no such file matches the mtime, falls back to the
     * newest file not in {@code excludeIds}.
     *
     * @param workingDir      working directory
     * @param claudeConfigDir Claude config base dir; {@code null} means {@code ~/.claude}
     * @param excludeIds      session IDs that already existed before the launch (may be {@code null})
     * @param minMtimeMillis  earliest acceptable file modification time (epoch millis)
     * @return session ID, or {@code null} if no candidate exists
     */
    public static String findSessionIdCreatedAfter(Path workingDir, Path claudeConfigDir,
                                                   Set<String> excludeIds, long minMtimeMillis) {
        Path dir = sessionsDir(workingDir, claudeConfigDir);
        Set<String> exclude = excludeIds != null ? excludeIds : Set.of();
        Path newest = newestSessionFile(dir, exclude, minMtimeMillis);
        if (newest == null) {
            newest = newestSessionFile(dir, exclude, 0);
        }
        return newest != null ? sessionIdOf(newest) : null;
    }

    /**
     * Returns the ID of the session file modified most recently, considering only
     * files whose mtime is at least {@code sinceMillis} and whose ID is not in
     * {@code excludeIds}.
     *
     * <p>Unlike {@link #findSessionIdCreatedAfter} there is no fallback to older
     * files: this is used to detect which conversation a running process is
     * currently writing after it may have switched sessions in-process.
     *
     * @param workingDir      working directory
     * @param claudeConfigDir Claude config base dir; {@code null} means {@code ~/.claude}
     * @param sinceMillis     earliest acceptable file modification time (epoch millis)
     * @param excludeIds      session IDs to ignore (e.g. owned by other tabs); may be {@code null}
     * @return session ID, or {@code null} if no file was modified since {@code sinceMillis}
     */
    public static String findLatestSessionIdWrittenSince(Path workingDir, Path claudeConfigDir,
                                                         long sinceMillis, Set<String> excludeIds) {
        Path newest = newestSessionFile(
                sessionsDir(workingDir, claudeConfigDir), excludeIds, sinceMillis);
        return newest != null ? sessionIdOf(newest) : null;
    }

    /**
     * Returns up to {@code limit} IDs of session files modified at or after
     * {@code sinceMillis}, newest first, skipping {@code excludeIds}.
     *
     * <p>Used to look for this tab's own conversation among the files written
     * since its prompt: another session can be newer than the one this process
     * is writing, so a single newest-file lookup is not enough.
     *
     * @param workingDir      working directory
     * @param claudeConfigDir Claude config base dir; {@code null} means {@code ~/.claude}
     * @param sinceMillis     earliest acceptable file modification time (epoch millis)
     * @param excludeIds      session IDs to ignore (e.g. owned by other tabs); may be {@code null}
     * @param limit           maximum number of IDs to return
     * @return session IDs, newest first; empty if none match
     */
    public static List<String> listSessionIdsWrittenSince(Path workingDir, Path claudeConfigDir,
                                                          long sinceMillis, Set<String> excludeIds,
                                                          int limit) {
        return listSessionIdsWrittenSince(workingDir, claudeConfigDir, sinceMillis,
                excludeIds, null, limit);
    }

    /**
     * Returns up to {@code limit} IDs of session files modified at or after
     * {@code sinceMillis}, newest first, skipping {@code excludeIds}.
     *
     * <p>When {@code includeIds} is non-{@code null}, only those sessions are
     * considered <em>before</em> the limit is applied. A caller that is looking
     * for a specific known conversation (e.g. one another tab is writing) must
     * not let newer unrelated files fill the limited list and hide it.
     *
     * @param workingDir      working directory
     * @param claudeConfigDir Claude config base dir; {@code null} means {@code ~/.claude}
     * @param sinceMillis     earliest acceptable file modification time (epoch millis)
     * @param excludeIds      session IDs to ignore; may be {@code null}
     * @param includeIds      session IDs to consider, or {@code null} for all
     * @param limit           maximum number of IDs to return
     * @return session IDs, newest first; empty if none match
     */
    public static List<String> listSessionIdsWrittenSince(Path workingDir, Path claudeConfigDir,
                                                          long sinceMillis, Set<String> excludeIds,
                                                          Set<String> includeIds, int limit) {
        if (limit <= 0) return List.of();
        Path dir = sessionsDir(workingDir, claudeConfigDir);
        if (!Files.isDirectory(dir)) return List.of();
        List<java.util.Map.Entry<Long, String>> found = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".jsonl")) continue;
                String id = sessionIdOf(file);
                if (includeIds != null && !includeIds.contains(id)) continue;
                if (excludeIds != null && excludeIds.contains(id)) continue;
                long mtime;
                try {
                    mtime = Files.getLastModifiedTime(file).toMillis();
                } catch (IOException e) {
                    continue;
                }
                if (mtime < sinceMillis) continue;
                found.add(java.util.Map.entry(mtime, id));
            }
        } catch (IOException e) {
            LOG.fine("Could not scan sessions in " + dir + ": " + e.getMessage());
            return List.of();
        }
        found.sort(java.util.Map.Entry.<Long, String>comparingByKey().reversed());
        List<String> ids = new ArrayList<>();
        for (java.util.Map.Entry<Long, String> entry : found) {
            ids.add(entry.getValue());
            if (ids.size() >= limit) break;
        }
        return ids;
    }

    /**
     * Returns when the session's conversation file was last written.
     *
     * @param workingDir      the project directory the session belongs to
     * @param claudeConfigDir the Claude configuration directory
     * @param sessionId       the session ID
     * @return the last-modified time in epoch millis, or {@code 0} when the file
     *         does not exist or cannot be read
     */
    public static long sessionFileModified(Path workingDir, Path claudeConfigDir,
                                           String sessionId) {
        Path file = sessionsDir(workingDir, claudeConfigDir)
                .resolve(sessionId + ".jsonl");
        long modified = 0L;
        try {
            modified = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            LOG.fine("Could not read the time of " + file + ": " + e.getMessage());
        }
        return modified;
    }

    /**
     * Returns whether the session file contains a user message with
     * {@code prompt} whose timestamp is at or after {@code sinceMillis}.
     *
     * <p>This is the evidence that a running process — and not a sibling tab
     * writing its own conversation — is the one appending to the file: a
     * conversation resumed in-process contains the prompt this tab just
     * submitted, while another tab's turn contains only that tab's messages.
     *
     * @param workingDir      working directory
     * @param claudeConfigDir Claude config base dir; {@code null} means {@code ~/.claude}
     * @param sessionId       session ID (file name without {@code .jsonl})
     * @param sinceMillis     earliest acceptable message timestamp (epoch millis)
     * @param prompt          prompt text submitted by this tab
     * @return {@code true} if such a message exists; {@code false} otherwise
     */
    public static boolean containsUserPromptSince(Path workingDir, Path claudeConfigDir,
                                                  String sessionId, long sinceMillis,
                                                  String prompt) {
        if (prompt == null || prompt.isBlank()) return false;
        String wanted = prompt.trim();
        if (wanted.isEmpty()) return false;
        return containsUserRecordSince(workingDir, claudeConfigDir, sessionId, sinceMillis,
                line -> isUserMessageWith(line, sinceMillis, wanted));
    }

    /**
     * Returns whether the session file contains a user message written at or
     * after {@code sinceMillis}, whatever its text.
     *
     * <p>Used when the submitted prompt has no known text (it was recalled from
     * the TUI history): a user record written after that submission is evidence
     * that a prompt went into the file, while another tab's turn only appends
     * assistant records, so a newer file time alone is not.
     *
     * @param workingDir      working directory
     * @param claudeConfigDir Claude config base dir; {@code null} means {@code ~/.claude}
     * @param sessionId       session ID (file name without {@code .jsonl})
     * @param sinceMillis     earliest acceptable message timestamp (epoch millis)
     * @return {@code true} if such a message exists; {@code false} otherwise
     */
    public static boolean containsUserMessageSince(Path workingDir, Path claudeConfigDir,
                                                   String sessionId, long sinceMillis) {
        return containsUserRecordSince(workingDir, claudeConfigDir, sessionId, sinceMillis,
                line -> isUserMessageSince(line, sinceMillis));
    }

    private static boolean containsUserRecordSince(Path workingDir, Path claudeConfigDir,
                                                   String sessionId, long sinceMillis,
                                                   java.util.function.Predicate<String> match) {
        if (sessionId == null) return false;
        Path file = sessionsDir(workingDir, claudeConfigDir).resolve(sessionId + ".jsonl");
        if (!Files.isRegularFile(file)) return false;
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file.toFile(), "r")) {
            // Read backwards in blocks: the newest records are the most likely
            // match, and a long turn can push the prompt arbitrarily far from
            // the end of the file. The scan stops as soon as a conversation
            // record older than the cutoff is reached, since those records are
            // appended in time order. Rename titles are not: they are appended
            // after a read of the file and may carry a timestamp sampled
            // before records already in it, so the scan goes past them.
            long pos = raf.length();
            // Bytes read so far that belong to a record cut by the block
            // boundaries, kept as separate chunks in file order: a record
            // spanning many blocks is never recopied while it is being found.
            java.util.ArrayDeque<byte[]> pending = new java.util.ArrayDeque<>();
            byte[] buffer = new byte[PROMPT_EVIDENCE_BLOCK_BYTES];
            while (pos > 0) {
                int size = (int) Math.min(PROMPT_EVIDENCE_BLOCK_BYTES, pos);
                long start = pos - size;
                raf.seek(start);
                raf.readFully(buffer, 0, size);
                pos = start;

                int newline = indexOf(buffer, size, (byte) '\n');
                if (pos > 0 && newline < 0) {
                    // The record fills this whole block and continues earlier:
                    // keep the bytes and look for its start in the next block.
                    pending.addFirst(java.util.Arrays.copyOf(buffer, size));
                    continue;
                }

                // Assemble the complete lines once: the part of this block after
                // its first newline (or all of it when the file starts here),
                // followed by the chunks already collected.
                int firstLine = pos > 0 ? newline + 1 : 0;
                java.io.ByteArrayOutputStream joined = new java.io.ByteArrayOutputStream(
                        size + pending.stream().mapToInt(chunk -> chunk.length).sum());
                joined.write(buffer, firstLine, size - firstLine);
                for (byte[] chunk : pending) joined.write(chunk, 0, chunk.length);
                pending.clear();
                if (pos > 0 && newline > 0) {
                    // The first line is cut in the middle: its beginning is in
                    // the block read next, so keep its bytes for that block.
                    pending.addFirst(java.util.Arrays.copyOf(buffer, newline));
                }

                String text = joined.toString(StandardCharsets.UTF_8);
                String[] lines = text.split("\n", -1);
                for (int i = lines.length - 1; i >= 0; i--) {
                    if (match.test(lines[i])) return true;
                    if (!isTitleRecord(lines[i]) && isOlderThan(lines[i], sinceMillis)) {
                        return false;
                    }
                }
            }
            return false;
        } catch (IOException | RuntimeException e) {
            LOG.fine("Could not read session " + file + ": " + e.getMessage());
            return false;
        }
    }

    /** Returns the index of the first occurrence of {@code b} in the first {@code length} bytes, or {@code -1}. */
    private static int indexOf(byte[] bytes, int length, byte b) {
        for (int i = 0; i < length; i++) {
            if (bytes[i] == b) return i;
        }
        return -1;
    }

    /**
     * Returns whether the JSONL {@code line} is a session rename title record.
     *
     * <p>Such a record is appended after a read of the file, so it can be dated
     * before records already in it; its position does not prove that the
     * records above it are older.
     */
    private static boolean isTitleRecord(String line) {
        if (line.isBlank() || !line.contains("custom-title")) return false;
        try {
            return "custom-title".equals(MAPPER.readTree(line).path("type").asText());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Returns whether the JSONL {@code line} is a conversation record dated
     * before {@code cutoffMillis}. Those records are appended in time order, so
     * everything before such a line is older still and the backward scan can
     * stop.
     */
    private static boolean isOlderThan(String line, long cutoffMillis) {
        if (line.isBlank()) return false;
        try {
            JsonNode node = MAPPER.readTree(line);
            JsonNode tsNode = node.get("timestamp");
            if (tsNode == null || !tsNode.isTextual()) return false;
            return Instant.parse(tsNode.asText()).toEpochMilli() < cutoffMillis;
        } catch (Exception e) {
            return false;
        }
    }

    /** Returns whether the JSONL {@code line} is a user message dated at or after {@code sinceMillis}. */
    private static boolean isUserMessageSince(String line, long sinceMillis) {
        if (line.isBlank()) return false;
        try {
            JsonNode node = MAPPER.readTree(line);
            if (!"user".equals(node.path("type").asText())) return false;
            JsonNode tsNode = node.get("timestamp");
            if (tsNode == null || !tsNode.isTextual()) return false;
            if (Instant.parse(tsNode.asText()).toEpochMilli() < sinceMillis) return false;
            return isHumanUserMessage(node);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Returns whether a {@code type: "user"} record was typed by the human.
     *
     * <p>Claude also stores tool results as {@code type: "user"} records while
     * it works, and writes system-injected messages with {@code isMeta}; neither
     * is a prompt, so neither is evidence that this process submitted one.
     */
    private static boolean isHumanUserMessage(JsonNode node) {
        if (node.path("isMeta").asBoolean(false)) return false;
        JsonNode content = node.path("message").get("content");
        if (content == null) return false;
        if (content.isTextual()) return !content.asText().isBlank();
        if (!content.isArray()) return false;
        for (JsonNode block : content) {
            if ("text".equals(block.path("type").asText())) return true;
        }
        return false;
    }

    /** Returns whether the JSONL {@code line} is a user message carrying {@code prompt}. */
    private static boolean isUserMessageWith(String line, long sinceMillis, String prompt) {
        if (line.isBlank()) return false;
        try {
            JsonNode node = MAPPER.readTree(line);
            if (!"user".equals(node.path("type").asText())) return false;
            JsonNode tsNode = node.get("timestamp");
            if (tsNode == null || !tsNode.isTextual()) return false;
            if (Instant.parse(tsNode.asText()).toEpochMilli() < sinceMillis) return false;
            JsonNode content = node.path("message").get("content");
            if (content == null) return false;
            if (content.isTextual()) return isSamePrompt(content.asText(), prompt);
            if (content.isArray()) {
                StringBuilder joined = new StringBuilder();
                for (JsonNode block : content) {
                    String text = textOfBlock(block);
                    if (text == null) continue;
                    if (isSamePrompt(text, prompt)) return true;
                    if (joined.length() > 0) joined.append('\n');
                    joined.append(text);
                }
                return isSamePrompt(joined.toString(), prompt);
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /** Returns the text of a content block, or {@code null} for non-text blocks. */
    private static String textOfBlock(JsonNode block) {
        if (block.isTextual()) return block.asText();
        if (block.isObject() && "text".equals(block.path("type").asText())) {
            return block.path("text").asText();
        }
        return null;
    }

    /**
     * Compares a stored message with the submitted prompt. The whole message
     * must match: a shorter prompt such as {@code yes} must not be treated as
     * evidence for an unrelated message that merely contains it.
     */
    private static boolean isSamePrompt(String text, String prompt) {
        return !text.isBlank() && text.trim().equals(prompt);
    }

    private static Path newestSessionFile(Path dir, Set<String> excludeIds, long minMtimeMillis) {
        if (!Files.isDirectory(dir)) {
            return null;
        }
        Path newest = null;
        long newestMtime = Long.MIN_VALUE;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".jsonl")) continue;
                String id = sessionIdOf(file);
                if (excludeIds != null && excludeIds.contains(id)) continue;
                long mtime;
                try {
                    mtime = Files.getLastModifiedTime(file).toMillis();
                } catch (IOException e) {
                    continue;
                }
                if (mtime < minMtimeMillis) continue;
                if (newest == null || mtime > newestMtime) {
                    newest = file;
                    newestMtime = mtime;
                }
            }
        } catch (IOException e) {
            LOG.fine("Could not scan sessions in " + dir + ": " + e.getMessage());
        }
        return newest;
    }

    private static String sessionIdOf(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".jsonl") ? name.substring(0, name.length() - 6) : name;
    }

    /**
     * Returns the parsed session with the given ID.
     *
     * @param workingDir      working directory
     * @param claudeConfigDir Claude config base dir; {@code null} means {@code ~/.claude}
     * @param sessionId       session ID (file name without {@code .jsonl})
     * @return the session, or {@code null} if the file is missing or unparseable
     */
    public static SavedSession findSession(Path workingDir, Path claudeConfigDir, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        Path file = sessionsDir(workingDir, claudeConfigDir).resolve(sessionId + ".jsonl");
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return parseSessionFile(file);
        } catch (Exception e) {
            LOG.fine("Could not parse session file " + file + ": " + e.getMessage());
            return null;
        }
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
        // Read first and sample the clock afterwards: the title is appended
        // last, so it must not be dated before the records it follows, or a
        // reader that relies on the append order stops before them.
        String existing = Files.readString(file, StandardCharsets.UTF_8);
        String timestamp = Instant.now().toString();
        String line = MAPPER.writeValueAsString(
                MAPPER.createObjectNode()
                        .put("type", "custom-title")
                        .put("customTitle", newName)
                        .put("sessionId", sessionId)
                        .put("timestamp", timestamp)
        );
        // Append with newline
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
