package io.github.nbplugins.claudecodegui.actions;

import io.github.nbplugins.claudecodegui.settings.ClaudeCodePreferences;
import io.github.nbplugins.claudecodegui.ui.ClaudeSessionTab;
import java.awt.event.ActionEvent;
import javax.swing.AbstractAction;
import static javax.swing.Action.SMALL_ICON;
import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.awt.ActionRegistration;
import org.openide.util.ImageUtilities;
import org.openide.util.NbBundle.Messages;

/**
 * Toolbar action that opens the Claude Code chat window.
 *
 * <p>Registered in the Build toolbar via {@code layer.xml}.
 */
@ActionID(
    category = "Window",
    id = "io.github.nbplugins.claudecodegui.actions.ClaudeCodeAction"
)
@ActionRegistration(
    displayName = "#CTL_ClaudeCodeAction",
    lazy = false,
    asynchronous = false
)
@ActionReference(path = "Toolbars/Build", position = 200)
@Messages("CTL_ClaudeCodeAction=Claude Code")
public final class ClaudeCodeAction extends AbstractAction {

    private static final String ICON_CLAUDE      = "io/github/nbplugins/claudecodegui/icons/claude-icon.png";
    private static final String ICON_DEVIN       = "io/github/nbplugins/claudecodegui/icons/devin-icon.png";
    private static final String ICON_ANTIGRAVITY = "io/github/nbplugins/claudecodegui/icons/antigravity-icon.png";
    private static final String ICON_CURSOR      = "io/github/nbplugins/claudecodegui/icons/cursor-icon.png";
    private static final String ICON_AIDER       = "io/github/nbplugins/claudecodegui/icons/aider-icon.png";
    private static final String ICON_CODEX       = "io/github/nbplugins/claudecodegui/icons/codex-icon.png";
    private static final String ICON_QWEN        = "io/github/nbplugins/claudecodegui/icons/qwen-icon.png";
    private static final String ICON_KODER       = "io/github/nbplugins/claudecodegui/icons/koder-icon.png";

    /** Constructs the action and sets the toolbar icon based on the configured CLI type. */
    public ClaudeCodeAction() {
        String iconPath;
        String label;
        if (ClaudeCodePreferences.isDevinCli()) {
            iconPath = ICON_DEVIN;
            label    = "Devin";
        } else if (ClaudeCodePreferences.isAntigravityCli()) {
            iconPath = ICON_ANTIGRAVITY;
            label    = "Google Antigravity";
        } else if (ClaudeCodePreferences.isCursorCli()) {
            iconPath = ICON_CURSOR;
            label    = "Cursor";
        } else if (ClaudeCodePreferences.isAiderCli()) {
            iconPath = ICON_AIDER;
            label    = "Aider";
        } else if (ClaudeCodePreferences.isCodexCli()) {
            iconPath = ICON_CODEX;
            label    = "OpenAI Codex";
        } else if (ClaudeCodePreferences.isQwenCli()) {
            iconPath = ICON_QWEN;
            label    = "Qwen Code";
        } else if (ClaudeCodePreferences.isKoderCli()) {
            iconPath = ICON_KODER;
            label    = "Koder";
        } else {
            iconPath = ICON_CLAUDE;
            label    = "Claude Code";
        }
        putValue("iconBase", iconPath);
        putValue(SMALL_ICON, ImageUtilities.loadImageIcon(iconPath, false));
        putValue(SHORT_DESCRIPTION, label);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        ClaudeSessionTab.openNewOrFocus();
    }
}
