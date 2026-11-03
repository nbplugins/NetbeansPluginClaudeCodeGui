package io.github.nbplugins.claudecodegui.process;

import io.github.nbplugins.claudecodegui.model.SavedSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClaudeSessionStoreTest {

    @TempDir Path tmpDir;

    // -------------------------------------------------------------------------
    // computeHash
    // -------------------------------------------------------------------------

    @Test
    void computeHash_replacesSeparatorsWithDashes() {
        Path p = Path.of("/home/user/my-project");
        assertEquals("-home-user-my-project", ClaudeSessionStore.computeHash(p));
    }

    @Test
    void computeHash_singleSegment() {
        Path p = Path.of("/myproject");
        assertEquals("-myproject", ClaudeSessionStore.computeHash(p));
    }

    // -------------------------------------------------------------------------
    // listSessions
    // -------------------------------------------------------------------------

    private Path createSessionDir(Path configDir, Path workingDir) throws IOException {
        String hash = ClaudeSessionStore.computeHash(workingDir);
        Path sessionDir = configDir.resolve("projects").resolve(hash);
        Files.createDirectories(sessionDir);
        return sessionDir;
    }

    private void writeJsonl(Path file, String... lines) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) sb.append(line).append("\n");
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    @Test
    void listSessions_parsesSlugAndTimestamps() throws IOException {
        Path workDir = tmpDir.resolve("myproject");
        Path sessionDir = createSessionDir(tmpDir, workDir);
        Path jsonl = sessionDir.resolve("abc123.jsonl");
        writeJsonl(jsonl,
                """
                {"sessionId":"abc123","slug":"enchanted-scribbling","timestamp":"2024-01-01T10:00:00.000Z","type":"summary"}""",
                """
                {"type":"user","content":"hello","timestamp":"2024-01-01T10:05:00.000Z","sessionId":"abc123"}"""
        );

        List<SavedSession> sessions = ClaudeSessionStore.listSessions(workDir, tmpDir);
        assertEquals(1, sessions.size());
        SavedSession s = sessions.get(0);
        assertEquals("abc123", s.sessionId());
        assertEquals("enchanted-scribbling", s.slug());
        assertNull(s.customTitle());
        assertEquals("enchanted-scribbling", s.displayName());
    }

    @Test
    void listSessions_customTitleOverridesSlug() throws IOException {
        Path workDir = tmpDir.resolve("myproject");
        Path sessionDir = createSessionDir(tmpDir, workDir);
        Path jsonl = sessionDir.resolve("abc123.jsonl");
        writeJsonl(jsonl,
                """
                {"sessionId":"abc123","slug":"old-slug","timestamp":"2024-01-01T10:00:00.000Z","type":"summary"}""",
                """
                {"type":"custom-title","customTitle":"My Title","sessionId":"abc123","timestamp":"2024-01-01T11:00:00.000Z"}"""
        );

        List<SavedSession> sessions = ClaudeSessionStore.listSessions(workDir, tmpDir);
        assertEquals(1, sessions.size());
        assertEquals("My Title", sessions.get(0).customTitle());
        assertEquals("My Title", sessions.get(0).displayName());
    }

    @Test
    void listSessions_lastCustomTitleWins() throws IOException {
        Path workDir = tmpDir.resolve("myproject");
        Path sessionDir = createSessionDir(tmpDir, workDir);
        Path jsonl = sessionDir.resolve("abc123.jsonl");
        writeJsonl(jsonl,
                """
                {"sessionId":"abc123","slug":"slug","timestamp":"2024-01-01T10:00:00.000Z","type":"summary"}""",
                """
                {"type":"custom-title","customTitle":"First","sessionId":"abc123","timestamp":"2024-01-01T10:01:00.000Z"}""",
                """
                {"type":"custom-title","customTitle":"Second","sessionId":"abc123","timestamp":"2024-01-01T10:02:00.000Z"}"""
        );

        List<SavedSession> sessions = ClaudeSessionStore.listSessions(workDir, tmpDir);
        assertEquals("Second", sessions.get(0).customTitle());
    }

    @Test
    void listSessions_excludesSessionsWithNoTimestamps() throws IOException {
        Path workDir = tmpDir.resolve("myproject");
        Path sessionDir = createSessionDir(tmpDir, workDir);
        writeJsonl(sessionDir.resolve("nots.jsonl"),
                """
                {"sessionId":"nots","slug":"no-ts","type":"summary"}"""
        );

        List<SavedSession> sessions = ClaudeSessionStore.listSessions(workDir, tmpDir);
        assertTrue(sessions.isEmpty());
    }

    @Test
    void listSessions_sortsByLastAtDesc() throws IOException {
        Path workDir = tmpDir.resolve("myproject");
        Path sessionDir = createSessionDir(tmpDir, workDir);

        writeJsonl(sessionDir.resolve("old.jsonl"),
                """
                {"sessionId":"old","slug":"old-session","timestamp":"2024-01-01T09:00:00.000Z","type":"summary"}"""
        );
        writeJsonl(sessionDir.resolve("new.jsonl"),
                """
                {"sessionId":"new","slug":"new-session","timestamp":"2024-06-01T09:00:00.000Z","type":"summary"}"""
        );

        List<SavedSession> sessions = ClaudeSessionStore.listSessions(workDir, tmpDir);
        assertEquals(2, sessions.size());
        assertEquals("new", sessions.get(0).sessionId());
        assertEquals("old", sessions.get(1).sessionId());
    }

    // -------------------------------------------------------------------------
    // deleteSession
    // -------------------------------------------------------------------------

    @Test
    void deleteSession_removesFile() throws IOException {
        Path workDir = tmpDir.resolve("myproject");
        Path sessionDir = createSessionDir(tmpDir, workDir);
        Path jsonl = sessionDir.resolve("xyz.jsonl");
        writeJsonl(jsonl, """
                {"sessionId":"xyz","slug":"s","timestamp":"2024-01-01T10:00:00.000Z","type":"summary"}""");

        assertTrue(ClaudeSessionStore.deleteSession(workDir, tmpDir, "xyz"));
        assertFalse(Files.exists(jsonl));
    }

    @Test
    void deleteSession_returnsFalseOnSecondCall() throws IOException {
        Path workDir = tmpDir.resolve("myproject");
        Path sessionDir = createSessionDir(tmpDir, workDir);
        Path jsonl = sessionDir.resolve("xyz.jsonl");
        writeJsonl(jsonl, """
                {"sessionId":"xyz","slug":"s","timestamp":"2024-01-01T10:00:00.000Z","type":"summary"}""");

        assertTrue(ClaudeSessionStore.deleteSession(workDir, tmpDir, "xyz"));
        assertFalse(ClaudeSessionStore.deleteSession(workDir, tmpDir, "xyz"));
    }

    // -------------------------------------------------------------------------
    // renameSession
    // -------------------------------------------------------------------------

    @Test
    void renameSession_appendsCustomTitleLine() throws IOException {
        Path workDir = tmpDir.resolve("myproject");
        Path sessionDir = createSessionDir(tmpDir, workDir);
        Path jsonl = sessionDir.resolve("abc.jsonl");
        writeJsonl(jsonl,
                """
                {"sessionId":"abc","slug":"slug","timestamp":"2024-01-01T10:00:00.000Z","type":"summary"}"""
        );

        ClaudeSessionStore.renameSession(workDir, tmpDir, "abc", "My New Name");

        List<SavedSession> sessions = ClaudeSessionStore.listSessions(workDir, tmpDir);
        assertEquals(1, sessions.size());
        assertEquals("My New Name", sessions.get(0).customTitle());
        assertEquals("My New Name", sessions.get(0).displayName());
    }

    @Test
    void renameSession_throwsWhenFileNotFound() {
        Path workDir = tmpDir.resolve("notexist");
        assertThrows(IOException.class, () ->
                ClaudeSessionStore.renameSession(workDir, tmpDir, "no-such-id", "Name"));
    }

    // -------------------------------------------------------------------------
    // Session identity helpers
    // -------------------------------------------------------------------------

    private static final java.time.Instant OLD_TS = java.time.Instant.parse("2024-01-01T10:00:00Z");

    /** Fixed filesystem mtime base; tests derive deterministic mtimes from it. */
    private static final long BASE_MTIME = 1_700_000_000_000L;

    private void writeTimedSession(Path sessionDir, String id, java.time.Instant ts,
                                   long mtimeMillis) throws IOException {
        Path file = sessionDir.resolve(id + ".jsonl");
        writeJsonl(file,
                "{\"sessionId\":\"" + id + "\",\"slug\":\"slug-" + id
                        + "\",\"timestamp\":\"" + ts + "\",\"type\":\"summary\"}");
        Files.setLastModifiedTime(file,
                java.nio.file.attribute.FileTime.fromMillis(mtimeMillis));
    }

    @Test
    void listSessionIds_returnsAllJsonlStems() throws IOException {
        Path workDir = tmpDir.resolve("myproject");
        Path sessionDir = createSessionDir(tmpDir, workDir);
        writeTimedSession(sessionDir, "s1", OLD_TS, BASE_MTIME);
        writeTimedSession(sessionDir, "s2", OLD_TS, BASE_MTIME);
        writeJsonl(sessionDir.resolve("notes.txt"), "ignored");

        assertEquals(java.util.Set.of("s1", "s2"),
                ClaudeSessionStore.listSessionIds(workDir, tmpDir));
    }

    @Test
    void listSessionIds_missingDir_returnsEmptySet() {
        assertTrue(ClaudeSessionStore.listSessionIds(tmpDir.resolve("nope"), tmpDir).isEmpty());
    }

    @Test
    void findLatestSessionId_usesFileMtime() throws IOException {
        Path workDir = tmpDir.resolve("myproject");
        Path sessionDir = createSessionDir(tmpDir, workDir);
        writeTimedSession(sessionDir, "old", OLD_TS, BASE_MTIME);
        writeTimedSession(sessionDir, "new", OLD_TS, BASE_MTIME + 1_000L);

        assertEquals("new", ClaudeSessionStore.findLatestSessionId(workDir, tmpDir));
    }

    @Test
    void findLatestSessionId_ignoresUnparseableNewestFile() throws IOException {
        Path workDir = tmpDir.resolve("myproject");
        Path sessionDir = createSessionDir(tmpDir, workDir);
        writeTimedSession(sessionDir, "older", OLD_TS, BASE_MTIME);
        // No timestamp → parseSessionFile() returns null, but mtime says it is newest.
        Path broken = sessionDir.resolve("broken.jsonl");
        writeJsonl(broken, "{\"sessionId\":\"broken\"}");
        Files.setLastModifiedTime(broken,
                java.nio.file.attribute.FileTime.fromMillis(BASE_MTIME + 1_000L));

        assertEquals("broken", ClaudeSessionStore.findLatestSessionId(workDir, tmpDir),
                "mtime-based lookup must not depend on JSONL content");
    }

    @Test
    void findSessionIdCreatedAfter_skipsFilesPresentBeforeLaunch() throws IOException {
        Path workDir = tmpDir.resolve("myproject");
        Path sessionDir = createSessionDir(tmpDir, workDir);
        writeTimedSession(sessionDir, "preexisting", OLD_TS, BASE_MTIME);
        java.util.Set<String> before = ClaudeSessionStore.listSessionIds(workDir, tmpDir);
        writeTimedSession(sessionDir, "created", OLD_TS, BASE_MTIME + 1_000L);

        assertEquals("created", ClaudeSessionStore.findSessionIdCreatedAfter(
                workDir, tmpDir, before, 0L));
    }

    @Test
    void findSessionIdCreatedAfter_prefersFileNewerThanLaunch() throws IOException {
        Path workDir = tmpDir.resolve("myproject");
        Path sessionDir = createSessionDir(tmpDir, workDir);
        writeTimedSession(sessionDir, "stale-new", OLD_TS, BASE_MTIME);
        writeTimedSession(sessionDir, "fresh", OLD_TS, BASE_MTIME + 2_000L);

        assertEquals("fresh", ClaudeSessionStore.findSessionIdCreatedAfter(
                workDir, tmpDir, java.util.Set.of(), BASE_MTIME + 1_000L));
    }

    @Test
    void findLatestSessionIdWrittenSince_returnsNullWhenNothingNewer() throws IOException {
        Path workDir = tmpDir.resolve("myproject");
        Path sessionDir = createSessionDir(tmpDir, workDir);
        writeTimedSession(sessionDir, "old", OLD_TS, BASE_MTIME);

        assertNull(ClaudeSessionStore.findLatestSessionIdWrittenSince(
                workDir, tmpDir, BASE_MTIME + 1_000L, java.util.Set.of()));
    }

    @Test
    void findLatestSessionIdWrittenSince_picksNewestAndSkipsExcluded() throws IOException {
        Path workDir = tmpDir.resolve("myproject");
        Path sessionDir = createSessionDir(tmpDir, workDir);
        writeTimedSession(sessionDir, "mine", OLD_TS, BASE_MTIME + 1_000L);
        writeTimedSession(sessionDir, "other-tab", OLD_TS, BASE_MTIME + 5_000L);

        // "other-tab" is newer but belongs to another tab.
        assertEquals("mine", ClaudeSessionStore.findLatestSessionIdWrittenSince(
                workDir, tmpDir, BASE_MTIME, java.util.Set.of("other-tab")));
        assertEquals("other-tab", ClaudeSessionStore.findLatestSessionIdWrittenSince(
                workDir, tmpDir, BASE_MTIME, java.util.Set.of()));
    }

    // -------------------------------------------------------------------------
    // containsUserPromptSince
    // -------------------------------------------------------------------------

    private static String userLine(String text, java.time.Instant ts) {
        return "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\""
                + text + "\"},\"timestamp\":\"" + ts + "\"}";
    }

    @Test
    void containsUserPromptSince_findsPromptWrittenAfterSubmission() throws IOException {
        Path workDir = tmpDir.resolve("myproject");
        Path sessionDir = createSessionDir(tmpDir, workDir);
        Path file = sessionDir.resolve("s1.jsonl");
        writeJsonl(file,
                userLine("earlier question", OLD_TS),
                userLine("the prompt from this tab", OLD_TS.plusSeconds(60)));

        assertTrue(ClaudeSessionStore.containsUserPromptSince(workDir, tmpDir, "s1",
                OLD_TS.plusSeconds(30).toEpochMilli(), "the prompt from this tab"));
    }

    @Test
    void containsUserPromptSince_ignoresOlderMessagesWithTheSameText() throws IOException {
        Path workDir = tmpDir.resolve("myproject");
        Path sessionDir = createSessionDir(tmpDir, workDir);
        Path file = sessionDir.resolve("s1.jsonl");
        writeJsonl(file, userLine("continue", OLD_TS));

        assertFalse(ClaudeSessionStore.containsUserPromptSince(workDir, tmpDir, "s1",
                OLD_TS.plusSeconds(30).toEpochMilli(), "continue"),
                "a message written before the prompt is not evidence of a shared resume");
    }

    @Test
    void containsUserPromptSince_acceptsContentBlocks() throws IOException {
        Path workDir = tmpDir.resolve("myproject");
        Path sessionDir = createSessionDir(tmpDir, workDir);
        Path file = sessionDir.resolve("s1.jsonl");
        String ts = OLD_TS.plusSeconds(60).toString();
        writeJsonl(file, "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":["
                + "{\"type\":\"text\",\"text\":\"prompt in a block\"}]},\"timestamp\":\""
                + ts + "\"}");

        assertTrue(ClaudeSessionStore.containsUserPromptSince(workDir, tmpDir, "s1",
                OLD_TS.toEpochMilli(), "prompt in a block"));
    }

    @Test
    void containsUserPromptSince_requiresTheWholePrompt() throws IOException {
        Path workDir = tmpDir.resolve("myproject");
        Path sessionDir = createSessionDir(tmpDir, workDir);
        Path file = sessionDir.resolve("s1.jsonl");
        writeJsonl(file, userLine("Summarize yesterday's changes", OLD_TS.plusSeconds(60)));

        assertFalse(ClaudeSessionStore.containsUserPromptSince(workDir, tmpDir, "s1",
                OLD_TS.toEpochMilli(), "yes"),
                "a message that merely contains the prompt is not evidence");
        assertFalse(ClaudeSessionStore.containsUserPromptSince(workDir, tmpDir, "s1",
                OLD_TS.toEpochMilli(), "yesterday"),
                "a message that merely contains the prompt is not evidence");
        assertTrue(ClaudeSessionStore.containsUserPromptSince(workDir, tmpDir, "s1",
                OLD_TS.toEpochMilli(), "Summarize yesterday's changes"));
    }

    @Test
    void containsUserPromptSince_noEvidence_returnsFalse() throws IOException {
        Path workDir = tmpDir.resolve("myproject");
        Path sessionDir = createSessionDir(tmpDir, workDir);
        writeJsonl(sessionDir.resolve("s1.jsonl"),
                userLine("another tabs prompt", OLD_TS.plusSeconds(60)));

        assertFalse(ClaudeSessionStore.containsUserPromptSince(workDir, tmpDir, "s1",
                OLD_TS.toEpochMilli(), "this tabs prompt"));
        assertFalse(ClaudeSessionStore.containsUserPromptSince(workDir, tmpDir, "missing",
                OLD_TS.toEpochMilli(), "whatever"));
        assertFalse(ClaudeSessionStore.containsUserPromptSince(workDir, tmpDir, "s1",
                OLD_TS.toEpochMilli(), "   "));
    }

    @Test
    void findSession_returnsRequestedSessionOnly() throws IOException {
        Path workDir = tmpDir.resolve("myproject");
        Path sessionDir = createSessionDir(tmpDir, workDir);
        writeTimedSession(sessionDir, "s1", OLD_TS, BASE_MTIME);
        writeTimedSession(sessionDir, "s2", OLD_TS, BASE_MTIME + 1_000L);

        SavedSession s = ClaudeSessionStore.findSession(workDir, tmpDir, "s2");
        assertNotNull(s);
        assertEquals("s2", s.sessionId());
        assertNull(ClaudeSessionStore.findSession(workDir, tmpDir, "missing"));
        assertNull(ClaudeSessionStore.findSession(workDir, tmpDir, null));
    }
}
