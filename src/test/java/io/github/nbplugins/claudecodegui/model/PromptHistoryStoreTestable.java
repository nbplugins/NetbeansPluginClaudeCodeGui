package io.github.nbplugins.claudecodegui.model;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Test-only subclass of {@link PromptHistoryStore} that allows overriding
 * TTL/depth settings (bypasses NetBeans preferences).
 */
final class PromptHistoryStoreTestable extends PromptHistoryStore {

    private int maxDepth = 200;
    private int ttlDays  = 0;

    PromptHistoryStoreTestable(Path file) {
        super(file);
    }

    void setMaxDepth(int d) { this.maxDepth = d; }
    void setTtlDays(int t)  { this.ttlDays  = t; }

    @Override
    public void add(String text) {
        if (text == null || text.isBlank()) return;
        List<HistoryEntry> entries = load();
        entries.removeIf(e -> text.equals(e.getText()));
        entries.add(0, new HistoryEntry(text, Instant.now()));
        while (entries.size() > maxDepth) entries.remove(entries.size() - 1);
        if (ttlDays > 0) {
            Instant cutoff = Instant.now().minusSeconds((long) ttlDays * 86_400);
            entries.removeIf(e -> e.getTime().isBefore(cutoff));
        }
        save(entries);
    }
}
