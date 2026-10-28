package io.github.nbplugins.claudecodegui.ui.common;

import java.beans.PropertyChangeListener;
import java.io.File;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Logger;
import javax.swing.SwingUtilities;
import javax.swing.event.CaretListener;
import javax.swing.text.Document;
import javax.swing.text.JTextComponent;
import org.netbeans.api.editor.EditorRegistry;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.loaders.DataObject;

/**
 * Tracks the line selection in the last focused IDE editor and notifies
 * listeners whenever it changes.
 *
 * <p>Uses {@link EditorRegistry#lastFocusedComponent()}, so the selection is kept
 * while focus moves to the Claude prompt panel. The registry and caret listeners
 * are installed only while at least one listener is registered.
 *
 * <p>All methods must be called on the EDT; listeners are notified on the EDT.
 */
public final class EditorSelectionTracker {

    private static final Logger LOG = Logger.getLogger(EditorSelectionTracker.class.getName());

    private static final EditorSelectionTracker DEFAULT = new EditorSelectionTracker();

    private final List<Consumer<EditorSelection>> listeners = new CopyOnWriteArrayList<>();
    private final PropertyChangeListener registryListener =
            e -> onEdt(() -> track(EditorRegistry.lastFocusedComponent()));
    private final CaretListener caretListener = e -> onEdt(this::update);

    /** Editor component whose caret is currently observed; {@code null} if none. */
    private JTextComponent tracked;
    /** Last selection published to listeners; {@code null} if nothing is selected. */
    private EditorSelection current;

    private EditorSelectionTracker() {}

    /**
     * Returns the shared tracker.
     *
     * @return the IDE-wide tracker instance
     */
    public static EditorSelectionTracker getDefault() {
        return DEFAULT;
    }

    /**
     * Registers a listener and immediately calls it with the current selection.
     *
     * @param listener receives the new selection, or {@code null} when nothing is selected
     */
    public void addListener(Consumer<EditorSelection> listener) {
        if (listeners.isEmpty()) {
            EditorRegistry.addPropertyChangeListener(registryListener);
            track(EditorRegistry.lastFocusedComponent());
        }
        listeners.add(listener);
        listener.accept(current);
    }

    /**
     * Unregisters a listener; stops tracking once no listeners remain.
     *
     * @param listener the listener passed to {@link #addListener}
     */
    public void removeListener(Consumer<EditorSelection> listener) {
        listeners.remove(listener);
        if (listeners.isEmpty()) {
            EditorRegistry.removePropertyChangeListener(registryListener);
            track(null);
        }
    }

    private void track(JTextComponent component) {
        if (component != tracked) {
            if (tracked != null) tracked.removeCaretListener(caretListener);
            tracked = component;
            if (tracked != null) tracked.addCaretListener(caretListener);
        }
        update();
    }

    private void update() {
        EditorSelection selection = tracked == null ? null
                : EditorSelection.of(filePathOf(tracked.getDocument()), tracked.getDocument(),
                        tracked.getSelectionStart(), tracked.getSelectionEnd());
        if (Objects.equals(selection, current)) return;
        current = selection;
        LOG.fine(() -> "Editor selection changed: " + current);
        for (Consumer<EditorSelection> l : listeners) l.accept(current);
    }

    /** Returns the absolute on-disk path of the file behind {@code doc}, or {@code null}. */
    private static String filePathOf(Document doc) {
        Object desc = doc.getProperty(Document.StreamDescriptionProperty);
        FileObject fo = desc instanceof DataObject dob ? dob.getPrimaryFile()
                : desc instanceof FileObject f ? f : null;
        File file = fo != null ? FileUtil.toFile(fo) : null;
        return file != null ? file.getAbsolutePath() : null;
    }

    private static void onEdt(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) r.run();
        else SwingUtilities.invokeLater(r);
    }
}
