package io.github.nbplugins.claudecodegui.ui.common;

import java.util.Arrays;
import java.util.List;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.text.JTextComponent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BasicTextContextMenuTest {

    private static List<String> itemLabels(JPopupMenu menu) {
        return Arrays.stream(menu.getComponents())
                .filter(c -> c instanceof JMenuItem)
                .map(c -> ((JMenuItem) c).getText())
                .toList();
    }

    @Test
    void createReadOnly_menuHasSelectAllAndCopy() {
        JTextComponent tc = new JTextField();
        JPopupMenu menu = BasicTextContextMenu.createReadOnly(tc);
        List<String> labels = itemLabels(menu);
        assertEquals(2, labels.size());
        assertTrue(labels.contains("Select All"));
        assertTrue(labels.contains("Copy"));
    }

    @Test
    void createReadOnly_copyDisabledWhenNoSelection() {
        JTextComponent tc = new JTextField("hello");
        tc.setSelectionStart(0);
        tc.setSelectionEnd(0);
        JPopupMenu menu = BasicTextContextMenu.createReadOnly(tc);

        // Simulate menu becoming visible
        javax.swing.event.PopupMenuEvent evt = new javax.swing.event.PopupMenuEvent(menu);
        for (javax.swing.event.PopupMenuListener l : menu.getPopupMenuListeners()) {
            l.popupMenuWillBecomeVisible(evt);
        }

        JMenuItem copy = Arrays.stream(menu.getComponents())
                .filter(c -> c instanceof JMenuItem && "Copy".equals(((JMenuItem) c).getText()))
                .map(c -> (JMenuItem) c)
                .findFirst().orElseThrow();
        assertFalse(copy.isEnabled());
    }

    @Test
    void createReadOnly_copyEnabledWhenSelectionExists() {
        JTextComponent tc = new JTextField("hello");
        tc.setSelectionStart(0);
        tc.setSelectionEnd(3);
        JPopupMenu menu = BasicTextContextMenu.createReadOnly(tc);

        javax.swing.event.PopupMenuEvent evt = new javax.swing.event.PopupMenuEvent(menu);
        for (javax.swing.event.PopupMenuListener l : menu.getPopupMenuListeners()) {
            l.popupMenuWillBecomeVisible(evt);
        }

        JMenuItem copy = Arrays.stream(menu.getComponents())
                .filter(c -> c instanceof JMenuItem && "Copy".equals(((JMenuItem) c).getText()))
                .map(c -> (JMenuItem) c)
                .findFirst().orElseThrow();
        assertTrue(copy.isEnabled());
    }

    @Test
    void create_menuHasExpectedItems() {
        JTextComponent tc = new JTextField();
        JPopupMenu menu = BasicTextContextMenu.create(tc);
        List<String> labels = itemLabels(menu);
        assertTrue(labels.contains("Select All"), "missing Select All");
        assertTrue(labels.contains("Copy"), "missing Copy");
        assertTrue(labels.contains("Cut"), "missing Cut");
        assertTrue(labels.contains("Paste"), "missing Paste");
        assertTrue(labels.contains("Clear"), "missing Clear");
    }
}
