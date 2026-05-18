package io.github.nbplugins.claudecodegui.ui.common;

import io.github.nbplugins.claudecodegui.model.FavoriteEntry;
import io.github.nbplugins.claudecodegui.model.PromptFavoritesStore;
import io.github.nbplugins.claudecodegui.ui.FavoritesDialog;
import java.awt.Window;
import java.nio.file.Path;
import java.util.function.Supplier;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.text.JTextComponent;

/**
 * Builds a standard Cut / Copy / Paste / Select All / Clear context menu
 * for any {@link JTextComponent}, optionally including Add to Favorites and
 * Favorites browser items.
 *
 * <p>Usage — simple (no extra items):
 * <pre>
 *   TextContextMenu.attach(myTextField);
 * </pre>
 *
 * <p>Usage — with favorites (wdSupplier may return null; items will be disabled):
 * <pre>
 *   JPopupMenu menu = TextContextMenu.create(myTextArea, wdSupplier, windowSupplier);
 *   TextContextMenu.attach(myTextArea, menu);
 * </pre>
 */
public final class TextContextMenu extends BasicTextContextMenu {

    /** Not instantiable — all methods are static. */
    private TextContextMenu() {}

    /**
     * Creates a JPopupMenu with standard editing actions plus Add to Favorites and
     * Favorites browser items. Both favorites items are enabled only when
     * {@code wdSupplier.get()} returns a non-null value (evaluated lazily on popup open).
     *
     * @param tc             the text component whose actions the menu items delegate to
     * @param wdSupplier     returns the current working directory path, or {@code null}
     * @param windowSupplier returns the ancestor Window for dialogs
     * @return the configured popup menu
     */
    public static JPopupMenu create(JTextComponent tc,
                                    Supplier<String> wdSupplier,
                                    Supplier<Window> windowSupplier) {
        JPopupMenu menu = BasicTextContextMenu.create(tc);

        JMenuItem addToFav = new JMenuItem("Add to Favorites");
        addToFav.addActionListener(e -> {
            String wd = wdSupplier.get();
            if (wd == null) return;
            String text = tc.getText().trim();
            if (!text.isEmpty()) {
                PromptFavoritesStore.getInstance(Path.of(wd))
                        .addProject(FavoriteEntry.ofProject(text));
            }
        });

        JMenuItem openFav = new JMenuItem("Favorites...");
        openFav.addActionListener(e -> {
            String wd = wdSupplier.get();
            if (wd == null) return;
            Window win = windowSupplier.get();
            FavoritesDialog dlg = new FavoritesDialog(win, Path.of(wd), text -> tc.setText(text));
            dlg.setVisible(true);
        });

        menu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                boolean hasWd = wdSupplier.get() != null;
                addToFav.setEnabled(hasWd && !tc.getText().trim().isEmpty());
                openFav.setEnabled(hasWd);
            }
            @Override public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {}
            @Override public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {}
        });

        menu.addSeparator();
        menu.add(addToFav);
        menu.add(openFav);
        return menu;
    }
}
