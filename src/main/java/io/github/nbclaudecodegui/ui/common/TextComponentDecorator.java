package io.github.nbclaudecodegui.ui.common;

import io.github.nbclaudecodegui.model.PromptFavoritesStore;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.util.function.Supplier;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;

/**
 * Sets up shared input features on a {@link JTextComponent}:
 * <ol>
 *   <li>Context menu with Cut/Copy/Paste/SelectAll/Clear + Add to Favorites + Favorites...</li>
 *   <li>File/image drag-and-drop and Ctrl+V paste via {@link FileDropHandler}</li>
 *   <li>{@code @}-path completion popup via {@link AtCompletionPopup}</li>
 *   <li>Favorites shortcut matching via {@link ShortcutMatcher}</li>
 * </ol>
 *
 * <p>Construct, call {@link #setup()}, then call {@link #cleanup()} when the component
 * is disposed to release any temp files created during the session.
 */
public final class TextComponentDecorator {

    private final JTextComponent   tc;
    private final Supplier<String> wdSupplier;

    private FileDropHandler dropHandler;
    private JPopupMenu      contextMenu;
    private ShortcutMatcher shortcutMatcher;

    /**
     * Creates a decorator for the given text component.
     *
     * @param tc         the text component to decorate
     * @param wdSupplier supplies the current working directory path (may return {@code null})
     */
    public TextComponentDecorator(JTextComponent tc, Supplier<String> wdSupplier) {
        this.tc         = tc;
        this.wdSupplier = wdSupplier;
    }

    /**
     * Installs all four features.  Must be called once after the component is created.
     */
    public void setup() {
        // 0. Context menu with favorites
        contextMenu = TextContextMenu.create(tc, wdSupplier,
                () -> SwingUtilities.getWindowAncestor(tc));
        TextContextMenu.attach(tc, contextMenu);

        // 1. DND + Ctrl+V paste
        dropHandler = new FileDropHandler(wdSupplier, tc.getTransferHandler());
        tc.setTransferHandler(dropHandler);

        // Replace Swing's SwingDropTarget with our own that never calls setDropLocation,
        // so the caret stays frozen at its original position during the entire drag.
        tc.setDropTarget(new java.awt.dnd.DropTarget(tc,
                java.awt.dnd.DnDConstants.ACTION_COPY_OR_MOVE,
                new java.awt.dnd.DropTargetAdapter() {
                    @Override
                    public void dragEnter(java.awt.dnd.DropTargetDragEvent e) {
                        if (dropHandler.canImportTransferable(e.getTransferable()))
                            e.acceptDrag(java.awt.dnd.DnDConstants.ACTION_COPY_OR_MOVE);
                        else
                            e.rejectDrag();
                    }
                    @Override
                    public void dragOver(java.awt.dnd.DropTargetDragEvent e) {
                        if (dropHandler.canImportTransferable(e.getTransferable()))
                            e.acceptDrag(java.awt.dnd.DnDConstants.ACTION_COPY_OR_MOVE);
                        else
                            e.rejectDrag();
                    }
                    @Override
                    public void drop(java.awt.dnd.DropTargetDropEvent e) {
                        dropHandler.handleDrop(e, tc);
                    }
                }, true));

        // 2. @-completion
        AtCompletionPopup.install(tc, wdSupplier);

        // 3. Ctrl+V interception + shortcut matching
        tc.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                // Ctrl+V — intercept for file/image paste; fall through to default paste on plain text
                if (e.getKeyCode() == KeyEvent.VK_V && e.isControlDown()
                        && !e.isShiftDown() && !e.isAltDown()) {
                    e.consume();
                    if (!dropHandler.importFromClipboard(tc)) {
                        tc.paste();
                    }
                    return;
                }
                // ShortcutMatcher — try to match favorites shortcuts
                ShortcutMatcher sm = getOrCreateShortcutMatcher();
                if (sm != null && sm.keyPressed(e)) {
                    e.consume();
                }
            }

            @Override
            public void keyTyped(KeyEvent e) {
                if (shortcutMatcher != null && shortcutMatcher.shouldSuppressKeyTyped()) {
                    e.consume();
                }
            }
        });
    }

    /**
     * Returns the context menu created during {@link #setup()}, so callers may append
     * additional items (e.g. history navigation in {@code ClaudePromptPanel}).
     *
     * @return the context menu, or {@code null} if {@link #setup()} has not been called
     */
    public JPopupMenu getContextMenu() {
        return contextMenu;
    }

    /**
     * Releases resources: deletes temp PNG files created from clipboard images.
     */
    public void cleanup() {
        if (dropHandler != null) dropHandler.cleanup();
    }

    private ShortcutMatcher getOrCreateShortcutMatcher() {
        String wd = wdSupplier.get();
        if (wd == null) return null;
        if (shortcutMatcher == null) {
            shortcutMatcher = new ShortcutMatcher(tc,
                    PromptFavoritesStore.getInstance(Path.of(wd)));
        }
        return shortcutMatcher;
    }
}
