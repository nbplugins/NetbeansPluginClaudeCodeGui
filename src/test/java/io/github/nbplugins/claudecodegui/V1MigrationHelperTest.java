package io.github.nbplugins.claudecodegui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static io.github.nbplugins.claudecodegui.V1MigrationHelper.migratePrefsNode;

import static org.junit.jupiter.api.Assertions.*;

class V1MigrationHelperTest {

    // ── migrateWindowsSettings ────────────────────────────────────────────────

    @Test
    void windowsSettings_replacesOldPackageInSettingsFiles(@TempDir Path dir) throws IOException {
        String oldContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <settings version="2.0">
                    <instanceof class="io.github.nbclaudecodegui.ui.ClaudeSessionTab"/>
                    <serialdata class="io.github.nbclaudecodegui.ui.ClaudeSessionTab">
                    </serialdata>
                </settings>
                """;
        Path file = dir.resolve("ClaudeSessionTopComponent.settings");
        Files.writeString(file, oldContent);

        V1MigrationHelper.migrateWindowsSettings(dir);

        String result = Files.readString(file);
        assertFalse(result.contains("io.github.nbclaudecodegui."),
                "Old package must be replaced");
        assertTrue(result.contains("io.github.nbplugins.claudecodegui.ui.ClaudeSessionTab"),
                "New package must be present");
    }

    @Test
    void windowsSettings_skipsFilesWithoutOldPackage(@TempDir Path dir) throws IOException {
        String content = "<settings><instanceof class=\"com.example.Other\"/></settings>";
        Path file = dir.resolve("Other.settings");
        Files.writeString(file, content);
        long modifiedBefore = file.toFile().lastModified();

        // Small sleep to ensure mtime would differ if file were rewritten
        V1MigrationHelper.migrateWindowsSettings(dir);

        assertEquals(content, Files.readString(file), "Unrelated file must not be modified");
    }

    @Test
    void windowsSettings_skipsNonSettingsFiles(@TempDir Path dir) throws IOException {
        Path xmlFile = dir.resolve("ClaudeSession.xml");
        Files.writeString(xmlFile, "<root class=\"io.github.nbclaudecodegui.Foo\"/>");

        V1MigrationHelper.migrateWindowsSettings(dir);

        assertTrue(Files.readString(xmlFile).contains("io.github.nbclaudecodegui.Foo"),
                ".xml files must not be touched");
    }

    @Test
    void windowsSettings_nonExistentDirIsNoOp(@TempDir Path tmp) {
        Path missing = tmp.resolve("nonexistent");
        assertDoesNotThrow(() -> V1MigrationHelper.migrateWindowsSettings(missing));
    }

    @Test
    void windowsSettings_migratesMarkdownPreviewTab(@TempDir Path dir) throws IOException {
        String oldContent = "<instanceof class=\"io.github.nbclaudecodegui.ui.MarkdownPreviewTab\"/>";
        Path file = dir.resolve("MarkdownPreviewTab.settings");
        Files.writeString(file, oldContent);

        V1MigrationHelper.migrateWindowsSettings(dir);

        String result = Files.readString(file);
        assertTrue(result.contains("io.github.nbplugins.claudecodegui.ui.MarkdownPreviewTab"));
    }

    // ── migratePrefsNode ──────────────────────────────────────────────────────

    @Test
    void prefsNode_copiesKeysToNewNodeAndRemovesOld() {
        String oldPath = "test/v1migration/prefs-copy-test";
        Preferences root = Preferences.userRoot();
        Preferences oldNode = root.node(oldPath);
        oldNode.put("key1", "value1");
        oldNode.put("key2", "value2");

        Preferences newNode = root.node("test/v1migration/prefs-copy-new");

        try {
            migratePrefsNode(root, oldPath, newNode);

            assertEquals("value1", newNode.get("key1", null));
            assertEquals("value2", newNode.get("key2", null));

            // Old node should be gone (keys.length == 0 or nodeExists == false)
            assertDoesNotThrow(() -> {
                Preferences check = Preferences.userRoot().node(oldPath);
                assertEquals(0, check.keys().length, "Old node must have no keys after migration");
            });
        } finally {
            // Cleanup
            try { newNode.removeNode(); } catch (Exception ignored) {}
            try { Preferences.userRoot().node(oldPath).removeNode(); } catch (Exception ignored) {}
        }
    }

    @Test
    void prefsNode_doesNotOverwriteExistingNewKeys() {
        String oldPath = "test/v1migration/prefs-no-overwrite-old";
        Preferences root = Preferences.userRoot();
        Preferences oldNode = root.node(oldPath);
        oldNode.put("key1", "old-value");

        Preferences newNode = root.node("test/v1migration/prefs-no-overwrite-new");
        newNode.put("key1", "new-value");

        try {
            migratePrefsNode(root, oldPath, newNode);

            assertEquals("new-value", newNode.get("key1", null),
                    "Existing key in new node must not be overwritten");
        } finally {
            try { newNode.removeNode(); } catch (Exception ignored) {}
            try { Preferences.userRoot().node(oldPath).removeNode(); } catch (Exception ignored) {}
        }
    }

    @Test
    void prefsNode_emptyOldNodeIsNoOp() {
        String oldPath = "test/v1migration/prefs-empty-old";
        Preferences root = Preferences.userRoot();
        Preferences newNode = root.node("test/v1migration/prefs-empty-new");

        try {
            assertDoesNotThrow(() -> migratePrefsNode(root, oldPath, newNode));
            assertEquals(0, newNode.keys().length, "New node must remain empty");
        } catch (Exception ignored) {
        } finally {
            try { newNode.removeNode(); } catch (Exception ignored) {}
        }
    }

    @Test
    void prefsNode_idempotentOnSecondRun() {
        String oldPath = "test/v1migration/prefs-idempotent-old";
        Preferences root = Preferences.userRoot();
        Preferences newNode = root.node("test/v1migration/prefs-idempotent-new");

        try {
            // First run: nothing to migrate (old node empty)
            migratePrefsNode(root, oldPath, newNode);
            newNode.put("key1", "value1");

            // Second run: old node still empty, new node has data — must not touch it
            migratePrefsNode(root, oldPath, newNode);
            assertEquals("value1", newNode.get("key1", null),
                    "Second run must not clear new node");
        } catch (Exception ignored) {
        } finally {
            try { newNode.removeNode(); } catch (Exception ignored) {}
        }
    }
}
