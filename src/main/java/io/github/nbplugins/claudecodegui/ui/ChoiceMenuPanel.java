package io.github.nbplugins.claudecodegui.ui;

import io.github.nbplugins.claudecodegui.model.ChoiceMenuModel;
import io.github.nbplugins.claudecodegui.ui.common.DecoratedTextField;
import io.github.nbplugins.claudecodegui.ui.common.UiUtils;
import java.awt.Color;
import java.awt.Component;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import javax.swing.KeyStroke;

/**
 * A panel that appears when claude asks an interactive question.
 *
 * <p>Displays a question label, Yes/No colour buttons, radio buttons for other
 * options, a free-form text field (only when there are no parsed options),
 * and a Cancel button pinned to the right.
 */
public final class ChoiceMenuPanel extends JPanel {

    private static final Logger LOG = Logger.getLogger(ChoiceMenuPanel.class.getName());

    /** Callback invoked with the selected option string when the user makes a choice. */
    private Consumer<String> callback;

    /** Supplies the current working directory path for context menus. */
    private final Supplier<String> workingDirSupplier;

    /**
     * Creates an initially-hidden choice menu panel.
     *
     * @param workingDirSupplier supplies the current working directory path (may return {@code null})
     */
    public ChoiceMenuPanel(Supplier<String> workingDirSupplier) {
        this.workingDirSupplier = workingDirSupplier;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Color.LIGHT_GRAY),
                BorderFactory.createEmptyBorder(4, 4, 4, 4)));
        setFocusCycleRoot(true); // keep Tab navigation contained within this panel
        setVisible(false);
    }

    /**
     * Shows the panel for the given choice menu model.
     *
     * @param model    the menu model
     * @param callback invoked with the chosen response string, or {@code null} on Cancel
     */
    public void show(ChoiceMenuModel model, Consumer<String> callback, boolean grabFocus) {
        this.callback = callback;
        removeAll();

        final ChoiceMenuModel finalModel = model;

        // Split options into Yes/No vs others
        // Yes/No only applies to options without a checkbox marker
        List<ChoiceMenuModel.Option> yesNoOptions = model.options().stream()
                .filter(o -> !o.hasCheckbox() && !isTypeInputOption(o)
                          && (o.display().trim().equalsIgnoreCase("Yes")
                           || o.display().trim().equalsIgnoreCase("No")))
                .toList();
        List<ChoiceMenuModel.Option> otherOptions = model.options().stream()
                .filter(o -> o.hasCheckbox() || isTypeInputOption(o)
                          || (!o.display().trim().equalsIgnoreCase("Yes")
                           && !o.display().trim().equalsIgnoreCase("No")))
                .toList();

        boolean hasCheckboxOptions = model.options().stream().anyMatch(ChoiceMenuModel.Option::hasCheckbox);

        // Track focus targets
        java.util.List<JButton> yesNoBtns = new java.util.ArrayList<>();
        JButton defaultYesNoBtn = null;
        JRadioButton[] radioButtons = new JRadioButton[otherOptions.size()];
        JRadioButton defaultRadioBtn = null;
        JTextField defaultTypeField = null;
        JTextField freeField = null;
        JButton sendBtn = null;

        // --- Question label — full width ---
        String questionText = model.text();
        LOG.info("[ChoiceMenuPanel] question: " + questionText);
        if (questionText != null && !questionText.isBlank()) {
            JLabel questionLabel = new JLabel("<html>" + escapeHtml(questionText) + "</html>");
            questionLabel.setFont(questionLabel.getFont().deriveFont(questionLabel.getFont().getSize() * 1.5f));
            questionLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            add(questionLabel);
            add(Box.createVerticalStrut(6));
        }

        // --- Left column (Y_AXIS): yes/no row, radios, free-form ---
        JPanel leftCol = new JPanel();
        leftCol.setLayout(new BoxLayout(leftCol, BoxLayout.Y_AXIS));
        leftCol.setAlignmentY(Component.TOP_ALIGNMENT);

        // Yes / No buttons
        if (!yesNoOptions.isEmpty()) {
            JPanel yesNoRow = new JPanel();
            yesNoRow.setLayout(new BoxLayout(yesNoRow, BoxLayout.X_AXIS));
            yesNoRow.setAlignmentX(Component.LEFT_ALIGNMENT);

            for (ChoiceMenuModel.Option opt : yesNoOptions) {
                String display = opt.display().trim();
                String response = opt.response();
                String label = display.equalsIgnoreCase("Yes")
                        ? UiUtils.ICON_CHECK + " " + display
                        : UiUtils.ICON_CROSS + " " + display;
                JButton btn = new JButton(label);
                UiUtils.applyActionStyle(btn, display.equalsIgnoreCase("Yes"));
                btn.addActionListener(e -> {
                    LOG.info("[ChoiceMenuPanel] yes/no clicked: \"" + display + "\" → \"" + response + "\"");
                    submitAnswer(response);
                });
                yesNoBtns.add(btn);
                yesNoRow.add(btn);
                yesNoRow.add(Box.createHorizontalStrut(4));

                int idx = model.options().indexOf(opt);
                if (idx == model.defaultOptionIndex()) {
                    defaultYesNoBtn = btn;
                }
            }
            leftCol.add(yesNoRow);
            leftCol.add(Box.createVerticalStrut(4));
        }

        // Other options: checkbox, radio, or type-input depending on option type
        ButtonGroup group = null;
        JTextField[] typeFields = new JTextField[otherOptions.size()];
        JCheckBox[] checkBoxes = new JCheckBox[otherOptions.size()];
        if (!otherOptions.isEmpty()) {
            group = new ButtonGroup();
            for (int i = 0; i < otherOptions.size(); i++) {
                ChoiceMenuModel.Option opt = otherOptions.get(i);

                if (opt.hasCheckbox() && isTypeInputOption(opt)) {
                    // Checkbox + text field: field enabled only when checkbox is checked
                    JCheckBox cb = new JCheckBox(" ", opt.checked());
                    cb.setName("typeInputCb");
                    cb.setAlignmentX(Component.LEFT_ALIGNMENT);
                    checkBoxes[i] = cb;

                    DecoratedTextField tf = new DecoratedTextField(workingDirSupplier);
                    tf.setMaximumSize(new java.awt.Dimension(
                            Integer.MAX_VALUE, tf.getPreferredSize().height));
                    tf.setEnabled(opt.checked());
                    setPlaceholder(tf, opt.display().trim());
                    cb.addChangeListener(e -> tf.setEnabled(cb.isSelected()));
                    tf.addFocusListener(new java.awt.event.FocusAdapter() {
                        @Override public void focusGained(java.awt.event.FocusEvent e) {
                            cb.setSelected(true);
                        }
                    });
                    typeFields[i] = tf;

                    JPanel typeRow = new JPanel();
                    typeRow.setName("typeInputRow");
                    typeRow.setLayout(new BoxLayout(typeRow, BoxLayout.X_AXIS));
                    typeRow.setAlignmentX(Component.LEFT_ALIGNMENT);
                    typeRow.add(cb);
                    typeRow.add(tf);
                    leftCol.add(typeRow);

                } else if (isTypeInputOption(opt)) {
                    // Type-input: radio button + adjacent text field.
                    // When description is set, display is a label ("Yes") and description is
                    // the input hint ("and tell Claude what to do next"). Otherwise display
                    // itself is the hint ("Type something.") and the radio gets a blank label.
                    boolean hasLabel = opt.description() != null && !opt.description().isBlank();
                    JRadioButton rb = new JRadioButton(hasLabel ? opt.display().trim() : " ");
                    rb.setAlignmentX(Component.LEFT_ALIGNMENT);
                    rb.setName("typeInputRb");
                    group.add(rb);
                    radioButtons[i] = rb;

                    DecoratedTextField tf = new DecoratedTextField(workingDirSupplier);
                    tf.setMaximumSize(new java.awt.Dimension(
                            Integer.MAX_VALUE, tf.getPreferredSize().height));
                    tf.setEnabled(false);
                    String hintText = hasLabel ? opt.description().trim() : opt.display().trim();
                    setPlaceholder(tf, hintText);
                    rb.addChangeListener(e -> tf.setEnabled(rb.isSelected()));
                    tf.addFocusListener(new java.awt.event.FocusAdapter() {
                        @Override public void focusGained(java.awt.event.FocusEvent e) {
                            rb.setSelected(true);
                        }
                    });
                    typeFields[i] = tf;

                    JPanel typeRow = new JPanel();
                    typeRow.setName("typeInputRow");
                    typeRow.setLayout(new BoxLayout(typeRow, BoxLayout.X_AXIS));
                    typeRow.setAlignmentX(Component.LEFT_ALIGNMENT);
                    typeRow.add(rb);
                    typeRow.add(tf);
                    leftCol.add(typeRow);

                } else if (opt.hasCheckbox()) {
                    // Checkbox item (has [ ] or [x] marker)
                    JCheckBox cb = new JCheckBox(opt.display().trim(), opt.checked());
                    cb.setAlignmentX(Component.LEFT_ALIGNMENT);
                    checkBoxes[i] = cb;
                    leftCol.add(cb);

                } else {
                    // Plain radio button (no checkbox marker)
                    JRadioButton rb = new JRadioButton(opt.display().trim());
                    rb.setAlignmentX(Component.LEFT_ALIGNMENT);
                    group.add(rb);
                    radioButtons[i] = rb;
                    leftCol.add(rb);
                }

                // Description subtitle — skip for type-input options (description is the field hint)
                if (opt.description() != null && !opt.description().isBlank() && !isTypeInputOption(opt)) {
                    JLabel desc = new JLabel("<html><small>" + escapeHtml(opt.description()) + "</small></html>");
                    desc.setForeground(Color.GRAY);
                    desc.setAlignmentX(Component.LEFT_ALIGNMENT);
                    leftCol.add(desc);
                }

                int idx = model.options().indexOf(opt);
                if (idx == model.defaultOptionIndex()) {
                    if (radioButtons[i] != null) {
                        radioButtons[i].setSelected(true);
                        defaultRadioBtn = radioButtons[i];
                        defaultTypeField = typeFields[i];
                    }
                }
            }
            leftCol.add(Box.createVerticalStrut(4));
        }

        // Free-form input (no options at all)
        if (model.options().isEmpty()) {
            freeField = new DecoratedTextField(workingDirSupplier);
            freeField.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, freeField.getPreferredSize().height));
            freeField.setAlignmentX(Component.LEFT_ALIGNMENT);
            final JTextField ff = freeField;
            freeField.addKeyListener(new java.awt.event.KeyAdapter() {
                @Override
                public void keyPressed(java.awt.event.KeyEvent e) {
                    if (e.getKeyCode() == java.awt.event.KeyEvent.VK_ENTER) {
                        submitAnswer(ff.getText().trim());
                    }
                }
            });
            leftCol.add(freeField);
            leftCol.add(Box.createVerticalStrut(4));
        }

        // --- Right column (Y_AXIS): Cancel, then Send ---
        JPanel rightCol = new JPanel();
        rightCol.setName("rightCol");
        rightCol.setLayout(new BoxLayout(rightCol, BoxLayout.Y_AXIS));
        rightCol.setAlignmentY(Component.TOP_ALIGNMENT);

        JButton cancelBtn = new JButton("Cancel");
        cancelBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
        cancelBtn.addActionListener(e -> {
            LOG.info("[ChoiceMenuPanel] Cancel clicked");
            cancel();
        });
        rightCol.add(cancelBtn);

        boolean hasSend = !otherOptions.isEmpty() || model.options().isEmpty();
        if (hasSend) {
            final JRadioButton[] finalRadios = radioButtons;
            final JCheckBox[] finalCheckBoxes = checkBoxes;
            final JTextField[] finalTypeFields = typeFields;
            final JTextField finalField = freeField;
            // Use "Submit" label when there are checkbox options (multi-select), "Send" otherwise
            sendBtn = new JButton(hasCheckboxOptions ? "Submit" : "Send");
            sendBtn.setAlignmentX(Component.CENTER_ALIGNMENT);

            // Compute initial enabled state
            java.util.function.BooleanSupplier isActionEnabled = () -> {
                for (JRadioButton rb : finalRadios) {
                    if (rb != null && rb.isSelected()) return true;
                }
                for (JCheckBox cb : finalCheckBoxes) {
                    if (cb != null && cb.isSelected()) return true;
                }
                return false;
            };
            if (!otherOptions.isEmpty()) {
                sendBtn.setEnabled(isActionEnabled.getAsBoolean());
            }

            final JButton finalSend = sendBtn;
            java.awt.event.ItemListener enableUpdater = ev -> finalSend.setEnabled(isActionEnabled.getAsBoolean());
            for (JRadioButton rb : radioButtons) {
                if (rb != null) rb.addItemListener(enableUpdater);
            }
            for (JCheckBox cb : checkBoxes) {
                if (cb != null) cb.addItemListener(enableUpdater);
            }

            sendBtn.addActionListener(e -> {
                // Radio selected → single response
                for (int i = 0; i < finalRadios.length; i++) {
                    JRadioButton rb = finalRadios[i];
                    if (rb != null && rb.isSelected()) {
                        ChoiceMenuModel.Option opt = otherOptions.get(i);
                        if (isTypeInputOption(opt) && finalTypeFields[i] != null) {
                            boolean hasLabel = opt.description() != null && !opt.description().isBlank();
                            String hint = hasLabel ? opt.description().trim() : opt.display().trim();
                            String typed = finalTypeFields[i].getText().trim();
                            if (!typed.isEmpty() && !typed.equals(hint)) {
                                // Encode as "TYPE:N:text" for writePtyAnswer to handle.
                                int optNum = finalModel.options().indexOf(opt) + 1;
                                submitAnswer("TYPE:" + optNum + ":" + typed);
                            } else if (hasLabel) {
                                // "Yes" label with no text typed — select the option with no text.
                                submitAnswer(opt.response());
                            }
                        } else {
                            submitAnswer(opt.response());
                        }
                        return;
                    }
                }
                // Checkbox type-input with text → MULTI_TYPE response
                for (int i = 0; i < finalCheckBoxes.length; i++) {
                    JCheckBox cb = finalCheckBoxes[i];
                    if (cb != null && cb.isSelected() && "typeInputCb".equals(cb.getName())) {
                        String hint = otherOptions.get(i).display().trim();
                        String typed = finalTypeFields[i] != null ? finalTypeFields[i].getText().trim() : "";
                        if (!typed.isEmpty() && !typed.equals(hint)) {
                            java.util.List<String> otherChecked = new java.util.ArrayList<>();
                            for (int j = 0; j < finalCheckBoxes.length; j++) {
                                if (j != i && finalCheckBoxes[j] != null && finalCheckBoxes[j].isSelected()) {
                                    otherChecked.add(otherOptions.get(j).response());
                                }
                            }
                            int optNum = finalModel.options().indexOf(otherOptions.get(i)) + 1;
                            submitAnswer("MULTI_TYPE:" + String.join(",", otherChecked) + ":" + optNum + ":" + typed);
                            return;
                        }
                    }
                }
                // Checkboxes → MULTI response
                java.util.List<String> selected = new java.util.ArrayList<>();
                for (int i = 0; i < finalCheckBoxes.length; i++) {
                    if (finalCheckBoxes[i] != null && finalCheckBoxes[i].isSelected()) {
                        selected.add(otherOptions.get(i).response());
                    }
                }
                if (!selected.isEmpty()) {
                    submitAnswer("MULTI:" + String.join(",", selected));
                    return;
                }
                if (finalField != null) {
                    submitAnswer(finalField.getText().trim());
                }
            });
            rightCol.add(Box.createVerticalStrut(4));
            rightCol.add(sendBtn);

            // Enter when Send button has focus clicks it (some L&Fs only fire Space on buttons)
            final JButton finalSendForEnter = sendBtn;
            sendBtn.getInputMap(JComponent.WHEN_FOCUSED)
                    .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "clickSend");
            sendBtn.getActionMap().put("clickSend", new AbstractAction() {
                @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                    if (finalSendForEnter.isEnabled()) finalSendForEnter.doClick();
                }
            });

            // Enter in type-input text fields also triggers Send
            for (JTextField tf : typeFields) {
                if (tf != null) {
                    tf.addActionListener(e -> {
                        if (finalSendForEnter.isEnabled()) finalSendForEnter.doClick();
                    });
                }
            }
        }

        // Pin rightCol width to its preferred size so it doesn't stretch
        rightCol.setMaximumSize(new java.awt.Dimension(
                rightCol.getPreferredSize().width, Integer.MAX_VALUE));

        // --- Main row: leftCol + strut + rightCol ---
        JPanel mainRow = new JPanel();
        mainRow.setLayout(new BoxLayout(mainRow, BoxLayout.X_AXIS));
        mainRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainRow.add(leftCol);
        mainRow.add(Box.createHorizontalGlue());
        mainRow.add(rightCol);

        add(mainRow);

        // ESC → cancel from anywhere in the panel
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel");
        getActionMap().put("cancel", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { cancel(); }
        });

        // Enter on radio buttons → click Send
        final JButton finalSendRef = sendBtn;
        for (JRadioButton rb : radioButtons) {
            if (rb != null) {
                rb.getInputMap(JComponent.WHEN_FOCUSED)
                        .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "sendOnEnter");
                rb.getActionMap().put("sendOnEnter", new AbstractAction() {
                    @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                        if (finalSendRef != null && finalSendRef.isEnabled()) {
                            finalSendRef.doClick();
                        }
                    }
                });
            }
        }

        // Arrow key navigation for radio buttons.
        // UP/DOWN: cyclic among enabled, visible, non-null radios.
        // LEFT: focus Send if enabled, else Cancel if enabled.
        // RIGHT: focus adjacent text field if present and enabled; otherwise Send/Cancel.
        final JButton finalSendForNav = sendBtn;
        final JButton finalCancelForNav = cancelBtn;
        final JTextField[] finalTypeFieldsNav = typeFields;
        final java.util.List<JButton> finalYesNoBtnsForRb = yesNoBtns;
        for (int i = 0; i < radioButtons.length; i++) {
            if (radioButtons[i] == null) continue;
            final int idx = i;
            AbstractAction prevAction = new AbstractAction() {
                @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                    int n = radioButtons.length;
                    int target = (idx - 1 + n) % n;
                    while (target != idx && (radioButtons[target] == null
                            || !radioButtons[target].isVisible() || !radioButtons[target].isEnabled())) {
                        target = (target - 1 + n) % n;
                    }
                    if (target != idx && radioButtons[target] != null && radioButtons[target].isEnabled()) {
                        radioButtons[target].setSelected(true);
                        if (finalTypeFieldsNav[target] != null && finalTypeFieldsNav[target].isEnabled()) {
                            finalTypeFieldsNav[target].requestFocusInWindow();
                        } else {
                            radioButtons[target].requestFocusInWindow();
                        }
                    } else {
                        // No other radio to go to — fall back to first enabled Yes/No button (Yes)
                        for (int j = 0; j < finalYesNoBtnsForRb.size(); j++) {
                            if (finalYesNoBtnsForRb.get(j).isEnabled()) { finalYesNoBtnsForRb.get(j).requestFocusInWindow(); return; }
                        }
                    }
                }
            };
            AbstractAction nextAction = new AbstractAction() {
                @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                    int n = radioButtons.length;
                    int target = (idx + 1) % n;
                    while (target != idx && (radioButtons[target] == null
                            || !radioButtons[target].isVisible() || !radioButtons[target].isEnabled())) {
                        target = (target + 1) % n;
                    }
                    if (target != idx && radioButtons[target] != null && radioButtons[target].isEnabled()) {
                        radioButtons[target].setSelected(true);
                        if (finalTypeFieldsNav[target] != null && finalTypeFieldsNav[target].isEnabled()) {
                            finalTypeFieldsNav[target].requestFocusInWindow();
                        } else {
                            radioButtons[target].requestFocusInWindow();
                        }
                    } else {
                        // No other radio to go to — fall back to first enabled Send/Cancel
                        focusFirstEnabled(finalSendForNav, finalCancelForNav);
                    }
                }
            };
            radioButtons[i].getInputMap(JComponent.WHEN_FOCUSED)
                    .put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "prev");
            radioButtons[i].getInputMap(JComponent.WHEN_FOCUSED)
                    .put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "next");
            radioButtons[i].getActionMap().put("prev", prevAction);
            radioButtons[i].getActionMap().put("next", nextAction);
            if (finalSendForNav != null || finalCancelForNav != null) {
                radioButtons[i].getInputMap(JComponent.WHEN_FOCUSED)
                        .put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), "rbLeft");
                radioButtons[i].getActionMap().put("rbLeft", new AbstractAction() {
                    @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                        focusFirstEnabled(finalSendForNav, finalCancelForNav);
                    }
                });
            }
            if (typeFields[i] != null) {
                final JTextField tfForRight = typeFields[i];
                radioButtons[i].getInputMap(JComponent.WHEN_FOCUSED)
                        .put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "rbRight");
                radioButtons[i].getActionMap().put("rbRight", new AbstractAction() {
                    @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                        if (tfForRight.isEnabled()) {
                            tfForRight.requestFocusInWindow();
                        } else {
                            focusFirstEnabled(finalSendForNav, finalCancelForNav);
                        }
                    }
                });
            } else if (finalSendForNav != null || finalCancelForNav != null) {
                radioButtons[i].getInputMap(JComponent.WHEN_FOCUSED)
                        .put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "rbRight");
                radioButtons[i].getActionMap().put("rbRight", new AbstractAction() {
                    @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                        focusFirstEnabled(finalSendForNav, finalCancelForNav);
                    }
                });
            }
        }

        // Left/Right cycle: Yes / No / Cancel (all in one ring) — skips disabled buttons.
        // Up/Down from Yes/No buttons only (not Cancel) → last/first enabled radio or checkbox.
        final java.util.List<JButton> allLRBtns = new java.util.ArrayList<>(yesNoBtns);
        allLRBtns.add(cancelBtn);
        if (allLRBtns.size() > 1) {
            final int lrCount = allLRBtns.size();
            final JRadioButton[] finalRadiosYN = radioButtons;
            final JCheckBox[] finalCheckBoxesYN = checkBoxes;
            for (int i = 0; i < lrCount; i++) {
                JButton btn = allLRBtns.get(i);
                final int lrIdx = i;
                btn.getInputMap(JComponent.WHEN_FOCUSED)
                        .put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), "lrPrev");
                btn.getInputMap(JComponent.WHEN_FOCUSED)
                        .put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "lrNext");
                btn.getActionMap().put("lrPrev", new AbstractAction() {
                    @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                        int target = (lrIdx - 1 + lrCount) % lrCount;
                        while (target != lrIdx && !allLRBtns.get(target).isEnabled()) {
                            target = (target - 1 + lrCount) % lrCount;
                        }
                        if (allLRBtns.get(target).isEnabled()) allLRBtns.get(target).requestFocusInWindow();
                    }
                });
                btn.getActionMap().put("lrNext", new AbstractAction() {
                    @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                        int target = (lrIdx + 1) % lrCount;
                        while (target != lrIdx && !allLRBtns.get(target).isEnabled()) {
                            target = (target + 1) % lrCount;
                        }
                        if (allLRBtns.get(target).isEnabled()) allLRBtns.get(target).requestFocusInWindow();
                    }
                });
            }
            for (JButton btn : yesNoBtns) {
                btn.getInputMap(JComponent.WHEN_FOCUSED)
                        .put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "ynUp");
                btn.getInputMap(JComponent.WHEN_FOCUSED)
                        .put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "ynDown");
                btn.getActionMap().put("ynUp", new AbstractAction() {
                    @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                        for (int j = finalRadiosYN.length - 1; j >= 0; j--) {
                            if (finalRadiosYN[j] != null && finalRadiosYN[j].isEnabled()) { finalRadiosYN[j].requestFocusInWindow(); return; }
                            if (finalCheckBoxesYN[j] != null && finalCheckBoxesYN[j].isEnabled()) { finalCheckBoxesYN[j].requestFocusInWindow(); return; }
                        }
                    }
                });
                btn.getActionMap().put("ynDown", new AbstractAction() {
                    @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                        for (int j = 0; j < finalRadiosYN.length; j++) {
                            if (finalRadiosYN[j] != null && finalRadiosYN[j].isEnabled()) { finalRadiosYN[j].requestFocusInWindow(); return; }
                            if (finalCheckBoxesYN[j] != null && finalCheckBoxesYN[j].isEnabled()) { finalCheckBoxesYN[j].requestFocusInWindow(); return; }
                        }
                    }
                });
            }
        }

        // Checkbox UP/DOWN/LEFT/RIGHT navigation.
        // UP: previous enabled radio/checkbox; if none, last enabled Yes/No button.
        // DOWN: next enabled radio/checkbox; if none, first enabled Send/Cancel.
        // LEFT: focus Send/Cancel (first enabled).
        // RIGHT: adjacent text field if enabled; else Send/Cancel.
        final JButton finalSendForCb = sendBtn;
        final JButton finalCancelForCb = cancelBtn;
        final java.util.List<JButton> finalYesNoBtnsForCb = yesNoBtns;
        final JRadioButton[] finalRadiosForCb = radioButtons;
        final JCheckBox[] finalCbsForCb = checkBoxes;
        for (int i = 0; i < checkBoxes.length; i++) {
            if (checkBoxes[i] == null) continue;
            final int cbIdx = i;
            // UP
            checkBoxes[i].getInputMap(JComponent.WHEN_FOCUSED)
                    .put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "cbUp");
            checkBoxes[i].getActionMap().put("cbUp", new AbstractAction() {
                @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                    for (int j = cbIdx - 1; j >= 0; j--) {
                        if (finalRadiosForCb[j] != null && finalRadiosForCb[j].isEnabled()) { finalRadiosForCb[j].requestFocusInWindow(); return; }
                        if (finalCbsForCb[j] != null && finalCbsForCb[j].isEnabled()) { finalCbsForCb[j].requestFocusInWindow(); return; }
                    }
                    for (int j = 0; j < finalYesNoBtnsForCb.size(); j++) {
                        if (finalYesNoBtnsForCb.get(j).isEnabled()) { finalYesNoBtnsForCb.get(j).requestFocusInWindow(); return; }
                    }
                }
            });
            // DOWN
            checkBoxes[i].getInputMap(JComponent.WHEN_FOCUSED)
                    .put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "cbDown");
            checkBoxes[i].getActionMap().put("cbDown", new AbstractAction() {
                @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                    for (int j = cbIdx + 1; j < finalCbsForCb.length; j++) {
                        if (finalRadiosForCb[j] != null && finalRadiosForCb[j].isEnabled()) { finalRadiosForCb[j].requestFocusInWindow(); return; }
                        if (finalCbsForCb[j] != null && finalCbsForCb[j].isEnabled()) { finalCbsForCb[j].requestFocusInWindow(); return; }
                    }
                    focusFirstEnabled(finalSendForCb, finalCancelForCb);
                }
            });
            // LEFT
            if (finalSendForCb != null || finalCancelForCb != null) {
                checkBoxes[i].getInputMap(JComponent.WHEN_FOCUSED)
                        .put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), "cbLeft");
                checkBoxes[i].getActionMap().put("cbLeft", new AbstractAction() {
                    @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                        focusFirstEnabled(finalSendForCb, finalCancelForCb);
                    }
                });
            }
            // RIGHT
            if (typeFields[i] != null) {
                final JTextField tfForRight = typeFields[i];
                checkBoxes[i].getInputMap(JComponent.WHEN_FOCUSED)
                        .put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "cbRight");
                checkBoxes[i].getActionMap().put("cbRight", new AbstractAction() {
                    @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                        if (tfForRight.isEnabled()) {
                            tfForRight.requestFocusInWindow();
                        } else {
                            focusFirstEnabled(finalSendForCb, finalCancelForCb);
                        }
                    }
                });
            } else if (finalSendForCb != null || finalCancelForCb != null) {
                checkBoxes[i].getInputMap(JComponent.WHEN_FOCUSED)
                        .put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "cbRight");
                checkBoxes[i].getActionMap().put("cbRight", new AbstractAction() {
                    @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                        focusFirstEnabled(finalSendForCb, finalCancelForCb);
                    }
                });
            }
        }

        // Send ↔ Cancel Up/Down (skip if target disabled); Send Left/Right → first enabled radio/checkbox.
        if (sendBtn != null) {
            final JButton finalCancelForSend = cancelBtn;
            sendBtn.getInputMap(JComponent.WHEN_FOCUSED)
                    .put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "sendUp");
            sendBtn.getInputMap(JComponent.WHEN_FOCUSED)
                    .put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "sendDown");
            sendBtn.getActionMap().put("sendUp", new AbstractAction() {
                @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                    if (finalCancelForSend.isEnabled()) finalCancelForSend.requestFocusInWindow();
                }
            });
            sendBtn.getActionMap().put("sendDown", new AbstractAction() {
                @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                    if (finalCancelForSend.isEnabled()) finalCancelForSend.requestFocusInWindow();
                }
            });

            final JButton finalSendForCancel = sendBtn;
            cancelBtn.getInputMap(JComponent.WHEN_FOCUSED)
                    .put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "cancelUp");
            cancelBtn.getInputMap(JComponent.WHEN_FOCUSED)
                    .put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "cancelDown");
            cancelBtn.getActionMap().put("cancelUp", new AbstractAction() {
                @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                    if (finalSendForCancel.isEnabled()) finalSendForCancel.requestFocusInWindow();
                }
            });
            cancelBtn.getActionMap().put("cancelDown", new AbstractAction() {
                @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                    if (finalSendForCancel.isEnabled()) finalSendForCancel.requestFocusInWindow();
                }
            });

            boolean hasToggle = false;
            for (int j = 0; j < radioButtons.length; j++) {
                if (radioButtons[j] != null || checkBoxes[j] != null) { hasToggle = true; break; }
            }
            if (hasToggle) {
                final JRadioButton[] finalRadiosSend = radioButtons;
                final JCheckBox[] finalCbSend = checkBoxes;
                AbstractAction sendToFirst = new AbstractAction() {
                    @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                        for (int j = 0; j < finalRadiosSend.length; j++) {
                            if (finalRadiosSend[j] != null && finalRadiosSend[j].isEnabled()) { finalRadiosSend[j].requestFocusInWindow(); return; }
                            if (finalCbSend[j] != null && finalCbSend[j].isEnabled())         { finalCbSend[j].requestFocusInWindow();         return; }
                        }
                    }
                };
                sendBtn.getInputMap(JComponent.WHEN_FOCUSED)
                        .put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), "sendLR");
                sendBtn.getInputMap(JComponent.WHEN_FOCUSED)
                        .put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "sendLR");
                sendBtn.getActionMap().put("sendLR", sendToFirst);
            }
        }

        // Text field UP/DOWN → navigate to the adjacent enabled option's toggle (radio/checkbox)
        final java.util.List<JButton> finalYesNoBtns = yesNoBtns;
        final JRadioButton[] finalRadiosTF = radioButtons;
        final JCheckBox[] finalCheckBoxesTF = checkBoxes;
        for (int i = 0; i < typeFields.length; i++) {
            if (typeFields[i] == null) continue;
            final int tfIdx = i;
            typeFields[i].getInputMap(JComponent.WHEN_FOCUSED)
                    .put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "tfUp");
            typeFields[i].getInputMap(JComponent.WHEN_FOCUSED)
                    .put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "tfDown");
            typeFields[i].getActionMap().put("tfUp", new AbstractAction() {
                @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                    for (int j = tfIdx - 1; j >= 0; j--) {
                        if (finalRadiosTF[j] != null && finalRadiosTF[j].isEnabled()) { finalRadiosTF[j].requestFocusInWindow(); return; }
                        if (finalCheckBoxesTF[j] != null && finalCheckBoxesTF[j].isEnabled()) { finalCheckBoxesTF[j].requestFocusInWindow(); return; }
                    }
                    for (int j = 0; j < finalYesNoBtns.size(); j++) {
                        if (finalYesNoBtns.get(j).isEnabled()) { finalYesNoBtns.get(j).requestFocusInWindow(); return; }
                    }
                }
            });
            typeFields[i].getActionMap().put("tfDown", new AbstractAction() {
                @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                    for (int j = tfIdx + 1; j < finalRadiosTF.length; j++) {
                        if (finalRadiosTF[j] != null && finalRadiosTF[j].isEnabled()) { finalRadiosTF[j].requestFocusInWindow(); return; }
                        if (finalCheckBoxesTF[j] != null && finalCheckBoxesTF[j].isEnabled()) { finalCheckBoxesTF[j].requestFocusInWindow(); return; }
                    }
                    for (JButton yb : finalYesNoBtns) {
                        if (yb.isEnabled()) { yb.requestFocusInWindow(); return; }
                    }
                }
            });
        }

        // Tab order: Yes/No → options in otherOptions index order (toggle + text field) → Send → Cancel
        java.util.List<java.awt.Component> tabOrder = new java.util.ArrayList<>();
        tabOrder.addAll(yesNoBtns);
        for (int i = 0; i < otherOptions.size(); i++) {
            if (radioButtons[i] != null) {
                tabOrder.add(radioButtons[i]);
                if (typeFields[i] != null) tabOrder.add(typeFields[i]);
            } else if (checkBoxes[i] != null) {
                tabOrder.add(checkBoxes[i]);
                if (typeFields[i] != null) tabOrder.add(typeFields[i]);
            }
        }
        if (freeField != null) tabOrder.add(freeField);
        if (sendBtn != null) tabOrder.add(sendBtn);
        tabOrder.add(cancelBtn);
        setFocusTraversalPolicy(new ListFTP(tabOrder));
        setFocusTraversalPolicyProvider(true);

        // Do NOT call setVisible(true) here — CardLayout manages visibility.
        // Calling setVisible(true) before CardLayout.show() corrupts CardLayout state:
        // CardLayout's first pass hides the first visible component (which becomes this
        // panel after setVisible(true)), then shows it again, but internal state breaks
        // and subsequent card switches stop working.
        revalidate();
        repaint();
        LOG.fine("[ChoiceMenuPanel] show() done: visible=" + isVisible()
                + " componentCount=" + getComponentCount()
                + " preferredSize=" + getPreferredSize()
                + " parent=" + (getParent() != null ? getParent().getClass().getSimpleName() : "null")
                + " parentVisible=" + (getParent() != null && getParent().isVisible()));

        // --- Default focus: the element Claude pre-selected (❯) ---
        final java.awt.Component focusTarget;
        if (freeField != null) {
            focusTarget = freeField;
        } else if (defaultYesNoBtn != null) {
            focusTarget = defaultYesNoBtn;
        } else if (defaultTypeField != null) {
            focusTarget = defaultTypeField;  // type-input option is default → focus its text field
        } else if (defaultRadioBtn != null) {
            focusTarget = defaultRadioBtn;
        } else {
            focusTarget = sendBtn;
        }
        if (grabFocus && focusTarget != null) {
            javax.swing.SwingUtilities.invokeLater(focusTarget::requestFocusInWindow);
        }
    }


/** Hides the panel if a prompt is still pending (called when Claude accepted input via terminal). */
    public void dismissIfActive() {
        if (callback != null) {
            LOG.info("[ChoiceMenuPanel] dismissing — Claude accepted terminal input");
            Consumer<String> cb = callback;
            setVisible(false);
            callback = null;
            removeAll();
            revalidate();
            repaint();
            cb.accept(null);
        }
    }

    /** Hides the panel and clears its state. */
    public void dismiss() {
        setVisible(false);
        callback = null;
        removeAll();
        revalidate();
        repaint();
    }

    // -------------------------------------------------------------------------

    private void submitAnswer(String answer) {
        Consumer<String> cb = callback;
        LOG.info("[ChoiceMenuPanel] submitAnswer: \"" + answer + "\", cb=" + cb);
        setVisible(false);
        callback = null;
        removeAll();
        revalidate();
        repaint();
        if (cb != null && answer != null && !answer.isBlank()) {
            cb.accept(answer);
        }
    }

    private void cancel() {
        Consumer<String> cb = callback;
        setVisible(false);
        callback = null;
        removeAll();
        revalidate();
        repaint();
        if (cb != null) {
            cb.accept(null);
        }
    }

    /**
     * Returns {@code true} if the option represents a free-text input slot
     * (display text starts with "type", case-insensitive).
     */
    private static boolean isTypeInputOption(ChoiceMenuModel.Option opt) {
        return opt.display().trim().toLowerCase().startsWith("type") || opt.hasTextInput();
    }

    /** Shows {@code hint} as greyed-out placeholder text; clears on focus, restores on blur if empty. */
    private static void setPlaceholder(JTextField tf, String hint) {
        tf.setForeground(Color.GRAY);
        tf.setText(hint);
        tf.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusGained(java.awt.event.FocusEvent e) {
                if (hint.equals(tf.getText())) {
                    tf.setText("");
                    tf.setForeground(javax.swing.UIManager.getColor("TextField.foreground"));
                }
            }
            @Override public void focusLost(java.awt.event.FocusEvent e) {
                if (tf.getText().isBlank()) {
                    tf.setForeground(Color.GRAY);
                    tf.setText(hint);
                }
            }
        });
    }

    /** Focuses the first of the given buttons that is non-null and enabled; does nothing if none qualifies. */
    private static void focusFirstEnabled(JButton... buttons) {
        for (JButton b : buttons) {
            if (b != null && b.isEnabled()) { b.requestFocusInWindow(); return; }
        }
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Focus traversal policy backed by an explicit ordered list. */
    private static final class ListFTP extends java.awt.FocusTraversalPolicy {
        private final java.util.List<java.awt.Component> order;
        ListFTP(java.util.List<java.awt.Component> order) { this.order = order; }

        private java.awt.Component next(java.awt.Component c, int dir) {
            int i = order.indexOf(c);
            if (i < 0) return order.isEmpty() ? null : order.get(0);
            int n = order.size();
            return order.get(((i + dir) % n + n) % n);
        }

        @Override public java.awt.Component getComponentAfter(java.awt.Container fc, java.awt.Component c) { return next(c, 1); }
        @Override public java.awt.Component getComponentBefore(java.awt.Container fc, java.awt.Component c) { return next(c, -1); }
        @Override public java.awt.Component getFirstComponent(java.awt.Container fc) { return order.isEmpty() ? null : order.get(0); }
        @Override public java.awt.Component getLastComponent(java.awt.Container fc) { return order.isEmpty() ? null : order.get(order.size() - 1); }
        @Override public java.awt.Component getDefaultComponent(java.awt.Container fc) { return getFirstComponent(fc); }
    }
}
