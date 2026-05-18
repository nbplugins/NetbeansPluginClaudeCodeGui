package io.github.nbplugins.claudecodegui.ui;

import io.github.nbplugins.claudecodegui.model.FavoriteEntry;
import io.github.nbplugins.claudecodegui.model.PromptFavoritesStore;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link FavoritesPanel#doEdit}.
 */
class FavoritesPanelTest {

    @TempDir
    Path tempDir;

    /** Concrete subclass that captures doEdit calls and lets tests control showMultiLineEditDialog. */
    private static class TestPanel extends FavoritesPanel {

        private String dialogReturn;

        TestPanel(PromptFavoritesStore store, String dialogReturn) {
            super(store);
            this.dialogReturn = dialogReturn;
        }

        @Override
        protected String showMultiLineEditDialog(Component parent, String initialText) {
            return dialogReturn;
        }

        @Override
        protected List<FavoriteEntry> loadEntries() {
            return store.getAll();
        }

        @Override
        protected AbstractTableModel buildModel(List<FavoriteEntry> entries) {
            currentEntries = new ArrayList<>(entries);
            return new AbstractTableModel() {
                @Override public int getRowCount() { return entries.size(); }
                @Override public int getColumnCount() { return 1; }
                @Override public Object getValueAt(int r, int c) { return entries.get(r).getText(); }
            };
        }
    }

    private PromptFavoritesStore makeStore() {
        return PromptFavoritesStore.getInstance(tempDir);
    }

    @Test
    void doEditUpdatesEntryTextOnConfirm() throws Exception {
        PromptFavoritesStore store = makeStore();
        FavoriteEntry entry = FavoriteEntry.ofProject("original text");
        store.addProject(entry);

        SwingUtilities.invokeAndWait(() -> {
            TestPanel panel = new TestPanel(store, "  updated text  ");
            panel.table.setRowSelectionInterval(0, 0);
            panel.doEdit(panel);
        });

        List<FavoriteEntry> all = store.getAll();
        assertFalse(all.isEmpty());
        assertTrue(all.stream().anyMatch(e -> "updated text".equals(e.getText())));
    }

    @Test
    void doEditDoesNothingOnCancel() throws Exception {
        PromptFavoritesStore store = makeStore();
        FavoriteEntry entry = FavoriteEntry.ofProject("original text");
        store.addProject(entry);

        SwingUtilities.invokeAndWait(() -> {
            TestPanel panel = new TestPanel(store, null); // null = cancelled
            panel.table.setRowSelectionInterval(0, 0);
            panel.doEdit(panel);
        });

        assertTrue(store.getAll().stream().anyMatch(e -> "original text".equals(e.getText())));
    }

    @Test
    void doEditDoesNothingOnBlankText() throws Exception {
        PromptFavoritesStore store = makeStore();
        FavoriteEntry entry = FavoriteEntry.ofProject("original text");
        store.addProject(entry);

        SwingUtilities.invokeAndWait(() -> {
            TestPanel panel = new TestPanel(store, "   "); // blank
            panel.table.setRowSelectionInterval(0, 0);
            panel.doEdit(panel);
        });

        assertTrue(store.getAll().stream().anyMatch(e -> "original text".equals(e.getText())));
    }
}
