package io.github.nbplugins.claudecodegui.actions;

import io.github.nbplugins.claudecodegui.settings.ClaudeProjectProperties;
import io.github.nbplugins.claudecodegui.ui.ClaudeSessionTab;
import java.awt.event.ActionEvent;
import java.io.File;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JMenuItem;
import org.netbeans.api.project.Project;
import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.awt.ActionReferences;
import org.openide.awt.ActionRegistration;
import org.openide.filesystems.FileUtil;
import org.openide.util.ContextAwareAction;
import org.openide.util.ImageUtilities;
import org.openide.util.Lookup;
import org.openide.util.NbBundle.Messages;
import org.openide.util.actions.Presenter;

/**
 * Context-menu action that opens a Claude Code session for the selected project.
 *
 * <p>If a session for the project directory is already open, it is focused.
 * Otherwise a new {@link ClaudeSessionTab} is created.
 */
@ActionID(
    category = "Project",
    id = "io.github.nbplugins.claudecodegui.actions.OpenWithClaudeAction"
)
@ActionRegistration(
    displayName = "#CTL_OpenWithClaudeAction",
    iconBase = "io/github/nbplugins/claudecodegui/icons/claude-icon.png",
    lazy = false
)
@ActionReferences({
    @ActionReference(path = "Projects/Actions", position = 100)
})
@Messages("CTL_OpenWithClaudeAction=Open with Claude Code")
public final class OpenWithClaudeAction extends AbstractAction
        implements ContextAwareAction {

    private static final String ICON =
            "io/github/nbplugins/claudecodegui/icons/claude-icon.png";

    /** No-arg constructor required by the annotation processor. */
    public OpenWithClaudeAction() {
        super(org.openide.util.NbBundle.getMessage(
                OpenWithClaudeAction.class, "CTL_OpenWithClaudeAction"));
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        ClaudeSessionTab.openNewOrFocus();
    }

    @Override
    public Action createContextAwareInstance(Lookup context) {
        if (context != null) {
            Project project = context.lookup(Project.class);
            if (project != null) {
                return new ContextAction(FileUtil.toFile(project.getProjectDirectory()));
            }
        }
        return new ContextAction(null);
    }

    // -------------------------------------------------------------------------

    /**
     * Context-specific action instance — implements {@link Presenter.Popup}
     * so that the icon appears in the context menu.
     */
    private static final class ContextAction extends AbstractAction
            implements Presenter.Popup {

        private final File directory;

        ContextAction(File directory) {
            super(org.openide.util.NbBundle.getMessage(
                    OpenWithClaudeAction.class, "CTL_OpenWithClaudeAction"));
            this.directory = directory;
            setEnabled(directory != null && directory.isDirectory());
            putValue(SMALL_ICON, ImageUtilities.loadImageIcon(ICON, false));
            putValue("iconBase", ICON);
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            if (directory != null) {
                String profileName = ClaudeProjectProperties.getProfileName(directory);
                ClaudeSessionTab.openForDirectory(directory,
                        profileName.isBlank() ? null : profileName);
            }
        }

        @Override
        public JMenuItem getPopupPresenter() {
            JMenuItem item = new JMenuItem(this);
            item.setVisible(isEnabled());
            return item;
        }
    }
}
