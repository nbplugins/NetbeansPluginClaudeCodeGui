package io.github.nbclaudecodegui.process;

import io.github.nbclaudecodegui.model.SavedSession;
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
}
