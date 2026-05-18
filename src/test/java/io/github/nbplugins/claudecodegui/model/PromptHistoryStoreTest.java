package io.github.nbplugins.claudecodegui.model;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PromptHistoryStore}.
 */
class PromptHistoryStoreTest {

    @TempDir Path tempDir;

    private PromptHistoryStore store;

    @BeforeEach
    void setUp() {
        store = new PromptHistoryStoreTestable(tempDir.resolve("history.json"));
    }

    @Test
    void addAndGetAll() {
        store.add("first prompt");
        store.add("second prompt");
        List<HistoryEntry> all = store.getAll();
        assertEquals(2, all.size());
        assertEquals("second prompt", all.get(0).getText(), "newest-first");
        assertEquals("first prompt",  all.get(1).getText());
    }

    @Test
    void deduplication() {
        store.add("hello");
        store.add("world");
        store.add("hello");   // duplicate → moved to front
        List<HistoryEntry> all = store.getAll();
        assertEquals(2, all.size());
        assertEquals("hello", all.get(0).getText());
        assertEquals("world", all.get(1).getText());
    }

    @Test
    void trimToMaxDepth() {
        ((PromptHistoryStoreTestable) store).setMaxDepth(3);
        store.add("a");
        store.add("b");
        store.add("c");
        store.add("d");   // oldest "a" should be evicted
        List<HistoryEntry> all = store.getAll();
        assertEquals(3, all.size());
        assertTrue(all.stream().noneMatch(e -> "a".equals(e.getText())), "oldest evicted");
    }

    @Test
    void delete() {
        store.add("keep");
        store.add("remove");
        HistoryEntry toDelete = store.getAll().stream()
                .filter(e -> "remove".equals(e.getText())).findFirst().orElseThrow();
        store.delete(List.of(toDelete));
        List<HistoryEntry> all = store.getAll();
        assertEquals(1, all.size());
        assertEquals("keep", all.get(0).getText());
    }

    @Test
    void deleteOlderThan() {
        store.add("recent");
        store.injectEntry(new HistoryEntry("old", Instant.now().minusSeconds(10_000)));
        store.deleteOlderThan(Instant.now().minusSeconds(5_000));
        List<HistoryEntry> all = store.getAll();
        assertEquals(1, all.size());
        assertEquals("recent", all.get(0).getText());
    }

    @Test
    void ttlExpiryOnAdd() {
        ((PromptHistoryStoreTestable) store).setTtlDays(1);
        store.injectEntry(new HistoryEntry("stale", Instant.now().minusSeconds(2L * 86_400)));
        store.add("fresh");
        List<HistoryEntry> all = store.getAll();
        assertTrue(all.stream().noneMatch(e -> "stale".equals(e.getText())),
                "stale entry should be removed by TTL");
    }

    @Test
    void getAll_emptyStore_returnsEmpty() {
        assertTrue(store.getAll().isEmpty());
    }
}
