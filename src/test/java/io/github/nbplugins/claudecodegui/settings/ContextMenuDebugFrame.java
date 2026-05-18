package io.github.nbplugins.claudecodegui.settings;

import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;

/**
 * Standalone Swing debug program for diagnosing right-click context menu
 * behaviour on JTextField and styled JButton components.
 *
 * <p>Reproduces the exact component setup used in {@code ClaudeProfilesPanel}
 * and logs all mouse events + popup-trigger state to a text area.
 *
 * <p>Run with:
 * <pre>
 *   mvn compile test-compile -DskipTests
 *   mvn exec:java -Dexec.mainClass="io.github.nbplugins.claudecodegui.settings.ContextMenuDebugFrame" \
 *       -Dexec.classpathScope=test
 * </pre>
 */
public class ContextMenuDebugFrame extends JFrame {

    private final JTextArea log = new JTextArea(12, 60);

    public ContextMenuDebugFrame() {
        super("Context Menu Debug");
        setDefaultCloseOperation(EXIT_ON_CLOSE);

        JPanel rows = new JPanel(new GridBagLayout());
        rows.setBorder(new EmptyBorder(12, 12, 12, 12));
        int row = 0;

        // Row A: editable JTextField
        JTextField editableField = new JTextField("https://api.example.com", 36);
        addRow(rows, row++, "A — JTextField (editable)", editableField,
                buildPopupMenu("editable-field"), true);

        // Row B: non-editable but enabled JTextField  (mirrors baseUrlField when Other API not selected)
        JTextField readOnlyField = new JTextField("https://api.example.com", 36);
        readOnlyField.setEditable(false);
        addRow(rows, row++, "B — JTextField (editable=false, enabled=true)", readOnlyField,
                buildPopupMenu("readonly-field"), true);

        // Row C: styled JButton — exact copy of buildLinkButton (MouseListener)
        JButton linkBtn = buildLinkButton("console.anthropic.com",
                "https://console.anthropic.com");
        addRow(rows, row++, "C — styled JButton (MouseListener)", linkBtn,
                buildPopupMenu("link-button"), false);

        // Row D: JTextField via setComponentPopupMenu only (no MouseListener)
        JTextField compPopupField = new JTextField("https://api.example.com", 36);
        JPopupMenu compPopupFieldMenu = buildPopupMenu("D-setComponentPopupMenu-field");
        compPopupField.setComponentPopupMenu(compPopupFieldMenu);
        compPopupField.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e)  { appendLog("D — JTextField (setComponentPopupMenu)", "mousePressed",  e); }
            @Override public void mouseReleased(MouseEvent e) { appendLog("D — JTextField (setComponentPopupMenu)", "mouseReleased", e); }
        });
        GridBagConstraints dlc = new GridBagConstraints();
        dlc.gridx = 0; dlc.gridy = row; dlc.anchor = GridBagConstraints.WEST; dlc.insets = new Insets(6,0,6,12);
        rows.add(new JLabel("D — JTextField (setComponentPopupMenu):"), dlc);
        GridBagConstraints dcc = new GridBagConstraints();
        dcc.gridx = 1; dcc.gridy = row; dcc.fill = GridBagConstraints.HORIZONTAL; dcc.weightx = 1.0; dcc.insets = new Insets(6,0,6,0);
        rows.add(compPopupField, dcc);
        row++;

        // Row E: styled JButton via setComponentPopupMenu only (no MouseListener)
        JButton compPopupBtn = buildLinkButtonPlain("console.anthropic.com (setComponentPopupMenu)");
        JPopupMenu compPopupBtnMenu = buildPopupMenu("E-setComponentPopupMenu-button");
        compPopupBtn.setComponentPopupMenu(compPopupBtnMenu);
        compPopupBtn.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e)  { appendLog("E — JButton (setComponentPopupMenu)", "mousePressed",  e); }
            @Override public void mouseReleased(MouseEvent e) { appendLog("E — JButton (setComponentPopupMenu)", "mouseReleased", e); }
        });
        GridBagConstraints elc = new GridBagConstraints();
        elc.gridx = 0; elc.gridy = row; elc.anchor = GridBagConstraints.WEST; elc.insets = new Insets(6,0,6,12);
        rows.add(new JLabel("E — styled JButton (setComponentPopupMenu):"), elc);
        GridBagConstraints ecc = new GridBagConstraints();
        ecc.gridx = 1; ecc.gridy = row; ecc.fill = GridBagConstraints.HORIZONTAL; ecc.weightx = 1.0; ecc.insets = new Insets(6,0,6,0);
        rows.add(compPopupBtn, ecc);
        row++;

        // Log area
        log.setEditable(false);
        log.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 11));

        getContentPane().setLayout(new BorderLayout(0, 8));
        getContentPane().add(rows, BorderLayout.NORTH);
        getContentPane().add(new JScrollPane(log), BorderLayout.CENTER);
        pack();
        setLocationRelativeTo(null);
    }

    // -------------------------------------------------------------------------

    private void addRow(JPanel panel, int row, String label,
                        java.awt.Component comp, JPopupMenu menu, boolean isField) {
        GridBagConstraints lc = new GridBagConstraints();
        lc.gridx = 0; lc.gridy = row;
        lc.anchor = GridBagConstraints.WEST;
        lc.insets = new Insets(6, 0, 6, 12);
        panel.add(new JLabel(label + ":"), lc);

        GridBagConstraints cc = new GridBagConstraints();
        cc.gridx = 1; cc.gridy = row;
        cc.fill = GridBagConstraints.HORIZONTAL;
        cc.weightx = 1.0;
        cc.insets = new Insets(6, 0, 6, 0);
        panel.add(comp, cc);

        // Attach mouse listener that logs events and shows popup on trigger
        comp.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                appendLog(label, "mousePressed", e);
                if (e.isPopupTrigger()) menu.show(e.getComponent(), e.getX(), e.getY());
            }
            @Override public void mouseReleased(MouseEvent e) {
                appendLog(label, "mouseReleased", e);
                if (e.isPopupTrigger()) menu.show(e.getComponent(), e.getX(), e.getY());
            }
            @Override public void mouseClicked(MouseEvent e) {
                appendLog(label, "mouseClicked", e);
            }
        });
    }

    private JPopupMenu buildPopupMenu(String tag) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem copyItem = new JMenuItem("Copy URL");
        JMenuItem openItem = new JMenuItem("Open in Browser");
        copyItem.addActionListener(e -> appendLog(tag, "Copy URL clicked", null));
        openItem.addActionListener(e -> appendLog(tag, "Open in Browser clicked", null));
        menu.add(copyItem);
        menu.add(openItem);
        return menu;
    }

    /** Styled button without any mouse listener — for setComponentPopupMenu test. */
    private static JButton buildLinkButtonPlain(String label) {
        JButton btn = new JButton("<html><a href=''>" + label + "</a></html>");
        btn.setBorderPainted(false);
        btn.setContentAreaFilled(false);
        btn.setOpaque(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return btn;
    }

    /** Exact replica of {@code ClaudeProfilesPanel.buildLinkButton}. */
    private static JButton buildLinkButton(String label, String url) {
        JButton btn = new JButton("<html><a href=''>" + label + "</a></html>");
        btn.setBorderPainted(false);
        btn.setContentAreaFilled(false);
        btn.setOpaque(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setToolTipText(url);
        btn.addActionListener(e -> System.out.println("left-click: " + url));
        return btn;
    }

    private void appendLog(String tag, String event, MouseEvent e) {
        String line;
        if (e != null) {
            line = String.format("[%s] %-16s  button=%d  isPopupTrigger=%-5s  x=%d y=%d%n",
                    tag, event, e.getButton(), e.isPopupTrigger(), e.getX(), e.getY());
        } else {
            line = String.format("[%s] %s%n", tag, event);
        }
        System.out.print(line);
        SwingUtilities.invokeLater(() -> {
            log.append(line);
            log.setCaretPosition(log.getDocument().getLength());
        });
    }

    // -------------------------------------------------------------------------

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new ContextMenuDebugFrame().setVisible(true));
    }
}
