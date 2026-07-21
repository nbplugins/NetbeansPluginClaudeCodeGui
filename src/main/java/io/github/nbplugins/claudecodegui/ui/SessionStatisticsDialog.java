package io.github.nbplugins.claudecodegui.ui;

import io.github.nbplugins.claudecodegui.openaiproxy.OpenAIProxyConfig;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;

/**
 * Read-only dialog showing cumulative prompt-cache/usage statistics for the
 * current session, broken down per model (a session can switch models
 * mid-conversation via {@code /model}), sourced from
 * {@link OpenAIProxyConfig#getUsageByModel()}.
 *
 * <p>Only meaningful for sessions using the OpenAI-compatible or ChatGPT
 * Subscription connection types — those are the only ones the plugin's proxy
 * sees traffic for.
 */
public final class SessionStatisticsDialog extends JDialog {

    private static final String[] COLUMNS = {"Model", "Requests", "Input tokens", "Output tokens",
            "Cached tokens", "Cache write tokens", "Last request"};

    private final DefaultTableModel tableModel;
    private final Supplier<OpenAIProxyConfig> configSupplier;

    /**
     * Creates the dialog. Call {@link #setVisible(boolean)} to show it.
     *
     * @param owner          parent window (for modality/centering)
     * @param configSupplier supplies the live {@link OpenAIProxyConfig} on each refresh
     *                       ({@code null} if the current connection type isn't proxied —
     *                       shown as an empty table with an explanatory note)
     */
    public SessionStatisticsDialog(Window owner, Supplier<OpenAIProxyConfig> configSupplier) {
        super(owner, "Session Statistics", ModalityType.MODELESS);
        this.configSupplier = configSupplier;

        setLayout(new BorderLayout(8, 8));
        getRootPane().setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        tableModel = new DefaultTableModel(COLUMNS, 0) {
            @Override public boolean isCellEditable(int row, int col) { return false; }
        };
        JTable table = new JTable(tableModel);
        table.setFillsViewportHeight(true);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(560, 160));
        add(scroll, BorderLayout.CENTER);

        add(buildNotePanel(), BorderLayout.SOUTH);

        JButton refreshBtn = new JButton("Refresh");
        JButton closeBtn = new JButton("Close");
        refreshBtn.addActionListener(e -> refresh());
        closeBtn.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(refreshBtn);
        buttons.add(closeBtn);
        add(buttons, BorderLayout.PAGE_END);

        refresh();
        pack();
        setMinimumSize(getSize());
        setLocationRelativeTo(owner);
    }

    private JPanel buildNotePanel() {
        JLabel note = new JLabel("<html><i>Totals are cumulative for this running session (reset on"
                + " restart/resume); \"Last request\" reflects only the most recent request. One row"
                + " per model used during this session. Only available for the OpenAI-compatible and"
                + " ChatGPT Subscription connection types.</i></html>");
        note.setFont(note.getFont().deriveFont(note.getFont().getSize2D() - 1f));
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(note, BorderLayout.CENTER);
        panel.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        return panel;
    }

    private void refresh() {
        tableModel.setRowCount(0);
        OpenAIProxyConfig config = configSupplier.get();
        if (config == null) {
            return;
        }
        Map<String, OpenAIProxyConfig.UsageSnapshot> byModel = config.getUsageByModel();
        List<Map.Entry<String, OpenAIProxyConfig.UsageSnapshot>> rows = new ArrayList<>(byModel.entrySet());
        for (Map.Entry<String, OpenAIProxyConfig.UsageSnapshot> e : rows) {
            OpenAIProxyConfig.UsageSnapshot u = e.getValue();
            tableModel.addRow(new Object[]{
                    e.getKey(), u.requests(), u.inputTokens(), u.outputTokens(),
                    u.cachedTokens(), u.cacheWriteTokens(),
                    describeLastRequest(u.lastRequestMessageCount(), u.lastRequestSizeBytes())
            });
        }
    }

    private static String describeLastRequest(int messageCount, long sizeBytes) {
        if (messageCount <= 0 && sizeBytes <= 0) {
            return "n/a";
        }
        return messageCount + " messages, " + formatBytes(sizeBytes);
    }

    private static String formatBytes(long bytes) {
        if (bytes >= 1024 * 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
        }
        if (bytes >= 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return bytes + " bytes";
    }
}
