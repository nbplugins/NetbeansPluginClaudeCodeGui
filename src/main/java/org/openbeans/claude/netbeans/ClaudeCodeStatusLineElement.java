package org.openbeans.claude.netbeans;

import io.github.nbclaudecodegui.model.SavedSession;
import io.github.nbclaudecodegui.model.SessionMode;
import io.github.nbclaudecodegui.process.ClaudeSessionStore;
import io.github.nbclaudecodegui.settings.ClaudeCodePreferences;
import io.github.nbclaudecodegui.settings.ClaudeProfile;
import io.github.nbclaudecodegui.settings.ClaudeProfileStore;
import io.github.nbclaudecodegui.ui.ClaudeSessionTab;
import io.github.nbclaudecodegui.ui.SaveAndSwitchDialog;
import java.awt.Component;
import java.awt.Frame;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.nio.file.Path;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import org.openide.awt.StatusLineElementProvider;
import org.openide.util.Lookup;
import org.openide.util.lookup.ServiceProvider;
import org.openide.windows.TopComponent;
import org.openide.windows.WindowManager;

/**
 * Status bar element for Claude Code integration status.
 * Shows a visual indicator in the NetBeans status bar with tooltip information.
 */
@ServiceProvider(service = StatusLineElementProvider.class, position = 100)
public class ClaudeCodeStatusLineElement implements StatusLineElementProvider {
    
    private JLabel statusLabel;
    private ClaudeCodeStatusService statusService;
    
    /** Creates the status line element; called by the NetBeans service provider framework. */
    public ClaudeCodeStatusLineElement() {
        statusService = Lookup.getDefault().lookup(ClaudeCodeStatusService.class);
        initializeStatusLabel();
    }
    
    private void initializeStatusLabel() {
        statusLabel = new JLabel();
        statusLabel.setText("Claude");
        statusLabel.setToolTipText("Claude Code: Initializing...");
        
        statusLabel.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        statusLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                onStatusLabelClicked();
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                updateTooltip();
            }
        });
        
        updateStatusDisplay();
    }
    
    private void updateStatusDisplay() {
        if (statusService != null) {
            boolean isRunning = statusService.isServerRunning();
            boolean lockValid = statusService.isLockFileValid();
            
            if (isRunning && lockValid) {
                statusLabel.setText("Claude ✓");
            } else if (isRunning) {
                statusLabel.setText("Claude ~");
            } else {
                statusLabel.setText("Claude ✗");
            }
            
            updateTooltip();
        }
    }
    
    private void updateTooltip() {
        if (statusService != null) {
            String status = statusService.getStatus();
            statusLabel.setToolTipText("<html>" + status.replace("\n", "<br>") + "</html>");
        } else {
            statusLabel.setToolTipText("Claude Code: Service not available");
        }
    }
    
    @Override
    public Component getStatusLineElement() {
        return statusLabel;
    }
    
    /**
     * Updates the status display. Can be called by other components
     * when status changes.
     */
    public void refresh() {
        updateStatusDisplay();
    }

    private void onStatusLabelClicked() {
        // Find the active ClaudeSessionTab
        ClaudeSessionTab activeTab = null;
        for (TopComponent tc : WindowManager.getDefault().getRegistry().getOpened()) {
            if (tc instanceof ClaudeSessionTab tab && tab.isSessionActive()) {
                activeTab = tab;
                break;
            }
        }
        if (activeTab == null) return;

        File workingDir = activeTab.getWorkingDirectory();
        if (workingDir == null) return;

        // Resolve claude config dir from the active profile
        String profileName = activeTab.getSelectedProfileName();
        Path claudeConfigDir = null;
        if (profileName != null && !ClaudeProfile.DEFAULT_NAME.equals(profileName)) {
            ClaudeProfile profile = ClaudeProfileStore.findByName(profileName);
            if (profile != null) {
                claudeConfigDir = ClaudeProfileStore.resolveStorageDir(
                        profile, ClaudeCodePreferences.getProfilesDir());
            }
        }

        // Get current session display name
        SavedSession recent = ClaudeSessionStore.findMostRecent(
                workingDir.toPath(), claudeConfigDir);
        String currentName = recent != null ? recent.displayName() : "";
        String currentId   = recent != null ? recent.sessionId() : null;

        final ClaudeSessionTab tab = activeTab;
        final Path configDir = claudeConfigDir;

        Frame mainFrame = WindowManager.getDefault().getMainWindow();
        SaveAndSwitchDialog dialog = new SaveAndSwitchDialog(
                mainFrame,
                currentName,
                currentId,
                workingDir.toPath(),
                configDir,
                io.github.nbclaudecodegui.model.SessionMode.CLOSE_ONLY,
                (name, mode, resumeId) ->
                        SwingUtilities.invokeLater(() -> tab.onSaveAndSwitch(name, mode, resumeId))
        );
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setVisible(true);
    }
}