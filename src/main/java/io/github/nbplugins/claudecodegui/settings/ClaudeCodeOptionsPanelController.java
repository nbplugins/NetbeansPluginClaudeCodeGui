package io.github.nbplugins.claudecodegui.settings;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import org.netbeans.spi.options.OptionsPanelController;
import org.openide.util.HelpCtx;
import org.openide.util.Lookup;

/**
 * Manages the Claude Code top-level options page in Tools → Options.
 *
 * <p>Registered via {@code layer.xml} (OptionsDialog/ClaudeCodeGUI.instance).
 * The page can be opened programmatically via:
 * <pre>{@code
 * OptionsDisplayer.getDefault().open("ClaudeCodeGUI");
 * }</pre>
 */
public final class ClaudeCodeOptionsPanelController extends OptionsPanelController {

    private ClaudeCodeOptionsPanel panel;
    private final PropertyChangeSupport pcs = new PropertyChangeSupport(this);
    private boolean changed;

    /** Creates the controller; called by the NetBeans Options framework via {@code layer.xml}. */
    public ClaudeCodeOptionsPanelController() {}

    @Override
    public void update() {
        getPanel().load();
        changed = false;
    }

    @Override
    public void applyChanges() {
        SwingUtilities.invokeLater(() -> {
            getPanel().store();
            changed = false;
        });
    }

    @Override
    public void cancel() {
        // no pending state to discard
    }

    @Override
    public boolean isValid() {
        return getPanel().valid();
    }

    @Override
    public boolean isChanged() {
        return changed;
    }

    @Override
    public HelpCtx getHelpCtx() {
        return null;
    }

    @Override
    public JComponent getComponent(Lookup masterLookup) {
        return getPanel();
    }

    @Override
    public void addPropertyChangeListener(PropertyChangeListener l) {
        pcs.addPropertyChangeListener(l);
    }

    @Override
    public void removePropertyChangeListener(PropertyChangeListener l) {
        pcs.removePropertyChangeListener(l);
    }

    /**
     * Notifies listeners that a setting value has changed.
     * Called by the panel when a field is modified.
     */
    void changed() {
        if (!changed) {
            changed = true;
            pcs.firePropertyChange(OptionsPanelController.PROP_CHANGED, false, true);
        }
        pcs.firePropertyChange(OptionsPanelController.PROP_VALID, null, null);
    }

    private ClaudeCodeOptionsPanel getPanel() {
        if (panel == null) {
            panel = new ClaudeCodeOptionsPanel();
        }
        return panel;
    }
}
