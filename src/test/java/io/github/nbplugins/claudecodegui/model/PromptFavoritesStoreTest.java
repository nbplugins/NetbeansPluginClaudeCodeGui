package io.github.nbplugins.claudecodegui.model;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PromptFavoritesStore}.
 */
class PromptFavoritesStoreTest {

    @TempDir Path tempDir;

    private PromptFavoritesStore store;

    @BeforeEach
    void setUp() {
        store = new PromptFavoritesStore(
                tempDir.resolve("favorites.json"),
                tempDir.resolve("global-favorites.json"));
    }

    @Test
    void addGlobalAndGetGlobal() {
        store.addGlobal(FavoriteEntry.ofGlobal("global text"));
        List<FavoriteEntry> globals = store.getGlobal();
        assertEquals(1, globals.size());
        assertEquals("global text", globals.get(0).getText());
        assertEquals(FavoriteEntry.Scope.GLOBAL, globals.get(0).getScope());
    }

    @Test
    void addProjectAndGetProject() {
        store.addProject(FavoriteEntry.ofProject("project text"));
        List<FavoriteEntry> project = store.getProject();
        assertEquals(1, project.size());
        assertEquals("project text", project.get(0).getText());
        assertEquals(FavoriteEntry.Scope.PROJECT, project.get(0).getScope());
    }

    @Test
    void deleteProject() {
        store.addProject(FavoriteEntry.ofProject("keep"));
        FavoriteEntry toRemove = FavoriteEntry.ofProject("remove");
        store.addProject(toRemove);
        store.delete(List.of(toRemove));
        List<FavoriteEntry> project = store.getProject();
        assertEquals(1, project.size());
        assertEquals("keep", project.get(0).getText());
    }

    @Test
    void deleteGlobal() {
        store.addGlobal(FavoriteEntry.ofGlobal("g1"));
        FavoriteEntry g2 = FavoriteEntry.ofGlobal("g2");
        store.addGlobal(g2);
        store.deleteGlobal(List.of(g2));
        assertEquals(1, store.getGlobal().size());
        assertEquals("g1", store.getGlobal().get(0).getText());
    }

    @Test
    void reorderProject() {
        FavoriteEntry a = FavoriteEntry.ofProject("a");
        FavoriteEntry b = FavoriteEntry.ofProject("b");
        FavoriteEntry c = FavoriteEntry.ofProject("c");
        store.addProject(a);
        store.addProject(b);
        store.addProject(c);
        store.reorder(List.of(c, a, b));
        List<FavoriteEntry> result = store.getProject();
        assertEquals("c", result.get(0).getText());
        assertEquals("a", result.get(1).getText());
        assertEquals("b", result.get(2).getText());
    }

    @Test
    void update() {
        FavoriteEntry e = FavoriteEntry.ofProject("some text");
        store.addProject(e);
        e.setShortcut("Ctrl+K Ctrl+F");
        store.update(e);
        FavoriteEntry loaded = store.getProject().get(0);
        assertEquals("Ctrl+K Ctrl+F", loaded.getShortcut());
    }

    @Test
    void toGlobal() {
        FavoriteEntry e = FavoriteEntry.ofProject("move me");
        store.addProject(e);
        store.toGlobal(e);
        assertTrue(store.getProject().isEmpty(), "removed from project");
        assertEquals(1, store.getGlobal().size());
        assertEquals("move me", store.getGlobal().get(0).getText());
        assertEquals(FavoriteEntry.Scope.GLOBAL, store.getGlobal().get(0).getScope());
    }

    @Test
    void getAll_combinesProjectAndGlobal() {
        store.addProject(FavoriteEntry.ofProject("p1"));
        store.addGlobal(FavoriteEntry.ofGlobal("g1"));
        List<FavoriteEntry> all = store.getAll();
        assertEquals(2, all.size());
        assertEquals("p1", all.get(0).getText());  // project first
        assertEquals("g1", all.get(1).getText());
    }

    @Test
    void getAllWithShortcuts() {
        FavoriteEntry withShortcut = FavoriteEntry.ofProject("shortcut prompt");
        withShortcut.setShortcut("Ctrl+K");
        store.addProject(withShortcut);
        store.addProject(FavoriteEntry.ofProject("no shortcut"));
        List<FavoriteEntry> result = store.getAllWithShortcuts();
        assertEquals(1, result.size());
        assertEquals("shortcut prompt", result.get(0).getText());
    }

    @Test
    void getProject_emptyStore_returnsEmpty() {
        assertTrue(store.getProject().isEmpty());
    }
}
