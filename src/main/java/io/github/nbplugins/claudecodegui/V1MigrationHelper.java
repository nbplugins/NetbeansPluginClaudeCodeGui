package io.github.nbplugins.claudecodegui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import org.openide.util.NbPreferences;

/** One-time migration helpers from pre-1.0 package names. Can be removed in 2.0. */
class V1MigrationHelper {

    private static final Logger LOG = Logger.getLogger(V1MigrationHelper.class.getName());

    static final String OLD_PKG = "io.github.nbclaudecodegui.";
    static final String NEW_PKG = "io.github.nbplugins.claudecodegui.";
    static final String OLD_PREFS_PREFIX = "io/github/nbclaudecodegui/";

    private V1MigrationHelper() {}

    /** Rewrites *.settings files under componentsDir, replacing old class FQNs with new ones. */
    static void migrateWindowsSettings(Path componentsDir) {
        if (!Files.exists(componentsDir)) return;
        try (var stream = Files.list(componentsDir)) {
            stream.filter(p -> p.toString().endsWith(".settings"))
                  .forEach(p -> {
                      try {
                          String content = Files.readString(p);
                          if (content.contains(OLD_PKG)) {
                              Files.writeString(p, content.replace(OLD_PKG, NEW_PKG));
                              LOG.log(Level.FINE, "Migrated window settings: {0}", p);
                          }
                      } catch (IOException e) {
                          LOG.log(Level.WARNING, "Could not migrate window settings: " + p, e);
                      }
                  });
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Window settings migration failed", e);
        }
    }

    /** Copies keys from oldPath NbPreferences node to newNode, then removes the old node. */
    static void migratePrefsNode(String oldPath, Preferences newNode) {
        migratePrefsNode(NbPreferences.root(), oldPath, newNode);
    }

    static void migratePrefsNode(Preferences root, String oldPath, Preferences newNode) {
        try {
            Preferences oldNode = root.node(oldPath);
            String[] keys = oldNode.keys();
            if (keys.length == 0) return;
            for (String key : keys) {
                if (newNode.get(key, null) == null) {
                    newNode.put(key, oldNode.get(key, ""));
                }
            }
            oldNode.removeNode();
            LOG.log(Level.FINE, "Migrated prefs from {0}", oldPath);
        } catch (BackingStoreException ignored) {}
    }
}
