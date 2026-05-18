package io.github.nbplugins.claudecodegui.ui.common;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import javax.swing.text.JTextComponent;

/**
 * Builds a basic Cut / Copy / Paste / Select All / Clear context menu
 * for any {@link JTextComponent}.
 *
 * <p>Usage — simple:
 * <pre>
 *   BasicTextContextMenu.attach(myTextArea);
 * </pre>
 *
 * <p>Extended with favorites: see {@link TextContextMenu}.
 */
public class BasicTextContextMenu {

    /** Protected constructor — subclasses only. */
    protected BasicTextContextMenu() {}

    /**
     * Creates a read-only JPopupMenu with Select All and Copy.
     * Copy is enabled only when text is selected (checked when menu opens).
     *
     * @param tc the text component whose actions the menu items delegate to
     * @return the configured popup menu
     */
    public static JPopupMenu createReadOnly(JTextComponent tc) {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem selectAll = new JMenuItem("Select All");
        selectAll.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK));
        selectAll.addActionListener(e -> tc.selectAll());

        JMenuItem copy = new JMenuItem("Copy");
        copy.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK));
        copy.addActionListener(e -> tc.copy());

        menu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                copy.setEnabled(tc.getSelectionStart() != tc.getSelectionEnd());
            }
            @Override public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {}
            @Override public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {}
        });

        menu.add(selectAll);
        menu.add(copy);
        return menu;
    }

    /**
     * Creates a JPopupMenu with Select All, Copy, [separator], Cut, Paste, Clear.
     * Does NOT attach it — call {@link #attach(JTextComponent, JPopupMenu)} afterwards
     * if you need to add more items first.
     *
     * @param tc the text component whose actions the menu items delegate to
     * @return the configured popup menu
     */
    public static JPopupMenu create(JTextComponent tc) {
        JPopupMenu menu = createReadOnly(tc);  // SelectAll + Copy (with enable logic)

        JMenuItem cut = new JMenuItem("Cut");
        cut.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_X, InputEvent.CTRL_DOWN_MASK));
        cut.addActionListener(e -> tc.cut());

        JMenuItem paste = new JMenuItem("Paste");
        paste.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK));
        paste.addActionListener(e -> tc.paste());

        JMenuItem clear = new JMenuItem("Clear");
        clear.addActionListener(e -> tc.setText(""));

        menu.addSeparator();
        menu.add(cut);
        menu.add(paste);
        menu.add(clear);
        return menu;
    }

    /**
     * Attaches a pre-built menu to a text component via mousePressed + mouseReleased
     * popup triggers (handles both Windows and macOS conventions).
     *
     * @param tc   the text component to attach the menu to
     * @param menu the popup menu to show on right-click
     */
    public static void attach(JTextComponent tc, JPopupMenu menu) {
        tc.setComponentPopupMenu(menu);
    }

    /**
     * Convenience: create and attach in one call when no extra items are needed.
     *
     * @param tc the text component to attach a default context menu to
     */
    public static void attach(JTextComponent tc) {
        attach(tc, create(tc));
    }
}
