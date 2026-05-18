package io.github.nbplugins.claudecodegui.actions;

import io.github.nbplugins.claudecodegui.model.FavoriteEntry;
import java.awt.event.ActionEvent;
import java.util.function.Consumer;
import javax.swing.AbstractAction;

/**
 * Action that inserts the text of a {@link FavoriteEntry} into the input area.
 *
 * <p>Each favorite can be associated with one of these actions, which can then
 * be registered with the global NetBeans action system.
 */
public final class PromptFavoriteAction extends AbstractAction {

    /** The favorite entry this action represents. */
    private final FavoriteEntry     entry;
    /** Callback that receives the prompt text when the action is performed. */
    private final Consumer<String>  onSend;

    /**
     * Creates the action.
     *
     * @param entry  the favorite to insert
     * @param onSend callback that receives the prompt text
     */
    public PromptFavoriteAction(FavoriteEntry entry, Consumer<String> onSend) {
        super(entry.getText());
        this.entry  = entry;
        this.onSend = onSend;
    }

    /**
     * Returns the associated favorite entry.
     *
     * @return the favorite entry this action represents
     */
    public FavoriteEntry getEntry() {
        return entry;
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        onSend.accept(entry.getText());
    }
}
