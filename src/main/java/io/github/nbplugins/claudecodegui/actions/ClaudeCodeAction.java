package io.github.nbplugins.claudecodegui.actions;

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

    private static final String ICON_PATH = "io/github/nbplugins/claudecodegui/icons/claude-icon-32.png";

    /** Constructs the action and sets the toolbar icon. */
    public ClaudeCodeAction() {
        putValue("iconBase", ICON_PATH);
        putValue(SMALL_ICON, ImageUtilities.loadImageIcon(ICON_PATH, false));
        putValue(SHORT_DESCRIPTION, "Claude Code");
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        ClaudeSessionTab.openNewOrFocus();
    }
}
