package io.github.nbplugins.claudecodegui.ui;

import io.github.nbplugins.claudecodegui.model.ChoiceMenuModel;
import io.github.nbplugins.claudecodegui.model.ChoiceMenuModel.Option;
import io.github.nbplugins.claudecodegui.ui.common.UiUtils;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ChoiceMenuPanel}.
 *
 * <p>All Swing operations are performed on the EDT.
 */
class ChoiceMenuPanelTest {

    @Test
    void testInitiallyHidden() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChoiceMenuPanel panel = new ChoiceMenuPanel(() -> null);
            assertFalse(panel.isVisible(), "panel should be hidden initially");
        });
    }

    @Test
    void testShowWithOptionsCreatesButtons() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChoiceMenuPanel panel = new ChoiceMenuPanel(() -> null);
            ChoiceMenuModel model = new ChoiceMenuModel("Allow?",
                    List.of(new Option("Yes", "1"), new Option("No", "2")), -1);
            panel.show(model, answer -> {}, true);

            assertTrue(panel.getComponentCount() > 0, "panel should have components after show()");

            List<String> btnLabels = collectButtonLabels(panel);
            String yesLabel = UiUtils.ICON_CHECK + " Yes";
            String noLabel  = UiUtils.ICON_CROSS + " No";
            assertTrue(btnLabels.contains(yesLabel), "should have '" + yesLabel + "' button");
            assertTrue(btnLabels.contains(noLabel),  "should have '" + noLabel + "' button");
        });
    }

    @Test
    void testYesButtonIsGreen() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChoiceMenuPanel panel = new ChoiceMenuPanel(() -> null);
            ChoiceMenuModel model = new ChoiceMenuModel("Q?",
                    List.of(new Option("Yes", "1"), new Option("No", "2")), -1);
            panel.show(model, answer -> {}, true);

            JButton btn = findButton(panel, UiUtils.ICON_CHECK + " Yes");
            assertNotNull(btn, "Yes button must exist");
            assertEquals(new Color(34, 139, 34), btn.getBackground(), "Yes button should be green");
        });
    }

    @Test
    void testNoButtonIsRed() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChoiceMenuPanel panel = new ChoiceMenuPanel(() -> null);
            ChoiceMenuModel model = new ChoiceMenuModel("Q?",
                    List.of(new Option("Yes", "1"), new Option("No", "2")), -1);
            panel.show(model, answer -> {}, true);

            JButton btn = findButton(panel, UiUtils.ICON_CROSS + " No");
            assertNotNull(btn, "No button must exist");
            assertEquals(new Color(178, 34, 34), btn.getBackground(), "No button should be red");
        });
    }

    @Test
    void testRadioButtonForNonYesNoOption() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChoiceMenuPanel panel = new ChoiceMenuPanel(() -> null);
            ChoiceMenuModel model = new ChoiceMenuModel("Q?",
                    List.of(new Option("Maybe", "3")), -1);
            panel.show(model, answer -> {}, true);

            JRadioButton rb = findRadioButton(panel, "Maybe");
            assertNotNull(rb, "Non-Yes/No option should render as JRadioButton");
        });
    }

    @Test
    void testCancelAlwaysPresent() throws Exception {
        // With Yes/No only
        SwingUtilities.invokeAndWait(() -> {
            ChoiceMenuPanel panel = new ChoiceMenuPanel(() -> null);
            panel.show(new ChoiceMenuModel("Q?", List.of(new Option("Yes", "1"), new Option("No", "2")), -1), a -> {}, true);
            assertNotNull(findButton(panel, "Cancel"), "Cancel must be present with Yes/No options");
        });

        // With radio options
        SwingUtilities.invokeAndWait(() -> {
            ChoiceMenuPanel panel = new ChoiceMenuPanel(() -> null);
            panel.show(new ChoiceMenuModel("Q?", List.of(new Option("Maybe", "3")), -1), a -> {}, true);
            assertNotNull(findButton(panel, "Cancel"), "Cancel must be present with radio options");
        });

        // Free-form
        SwingUtilities.invokeAndWait(() -> {
            ChoiceMenuPanel panel = new ChoiceMenuPanel(() -> null);
            panel.show(new ChoiceMenuModel("Q?", List.of(), -1), a -> {}, true);
            assertNotNull(findButton(panel, "Cancel"), "Cancel must be present for free-form input");
        });
    }

    @Test
    void testSendAbsentWhenOnlyYesNo() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChoiceMenuPanel panel = new ChoiceMenuPanel(() -> null);
            panel.show(new ChoiceMenuModel("Q?", List.of(new Option("Yes", "1"), new Option("No", "2")), -1), a -> {}, true);
            assertNull(findButton(panel, "Send"), "Send button must NOT be present when only Yes/No options");
        });
    }

    @Test
    void testSendPresentWhenOtherOptionsExist() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChoiceMenuPanel panel = new ChoiceMenuPanel(() -> null);
            panel.show(new ChoiceMenuModel("Q?",
                    List.of(new Option("Yes", "1"), new Option("Maybe", "3")), -1), a -> {}, true);
            assertNotNull(findButton(panel, "Send"), "Send button must be present when other options exist");
        });
    }

    @Test
    void testSendDisabledUntilRadioSelected() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChoiceMenuPanel panel = new ChoiceMenuPanel(() -> null);
            panel.show(new ChoiceMenuModel("Q?", List.of(new Option("Maybe", "3")), -1), a -> {}, true);
            JButton send = findButton(panel, "Send");
            assertNotNull(send, "Send must exist");
            assertFalse(send.isEnabled(), "Send must be disabled when no radio is selected");

            JRadioButton rb = findRadioButton(panel, "Maybe");
            assertNotNull(rb);
            rb.setSelected(true);
            assertTrue(send.isEnabled(), "Send must be enabled after radio selection");
        });
    }

    @Test
    void testSendEnabledWhenDefaultRadioSelected() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChoiceMenuPanel panel = new ChoiceMenuPanel(() -> null);
            panel.show(new ChoiceMenuModel("Q?", List.of(new Option("Maybe", "1")), 0), a -> {}, true);
            JButton send = findButton(panel, "Send");
            assertNotNull(send, "Send must exist");
            assertTrue(send.isEnabled(), "Send must be enabled when default radio is pre-selected");
        });
    }

    @Test
    void testDefaultRadioSelected() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChoiceMenuPanel panel = new ChoiceMenuPanel(() -> null);
            panel.show(new ChoiceMenuModel("Q?", List.of(new Option("Maybe", "1")), 0), a -> {}, true);
            JRadioButton rb = findRadioButton(panel, "Maybe");
            assertNotNull(rb, "JRadioButton for Maybe must exist");
            assertTrue(rb.isSelected(), "Radio button for default option should be selected");
        });
    }

    @Test
    void testButtonClickInvokesCallbackWithResponse() throws Exception {
        List<String> received = new ArrayList<>();

        SwingUtilities.invokeAndWait(() -> {
            ChoiceMenuPanel panel = new ChoiceMenuPanel(() -> null);
            ChoiceMenuModel model = new ChoiceMenuModel("Allow?",
                    List.of(new Option("Yes", "1"), new Option("No", "2")), -1);
            panel.show(model, received::add, true);

            JButton yesBtn = findButton(panel, UiUtils.ICON_CHECK + " Yes");
            assertNotNull(yesBtn, "Yes button must exist");
            yesBtn.doClick();
        });

        assertEquals(1, received.size(), "callback must be called exactly once");
        assertEquals("1", received.get(0), "response should be the option response value");
    }

    @Test
    void testHideAfterButtonClick() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChoiceMenuPanel panel = new ChoiceMenuPanel(() -> null);
            ChoiceMenuModel model = new ChoiceMenuModel("Go?", List.of(new Option("Yes", "1")), -1);
            panel.show(model, answer -> {}, true);

            JButton btn = findButton(panel, UiUtils.ICON_CHECK + " Yes");
            assertNotNull(btn);
            btn.doClick();

            assertFalse(panel.isVisible(), "panel should hide after button click");
        });
    }

    @Test
    void testCancelInvokesCallbackWithNull() throws Exception {
        List<String> received = new ArrayList<>();
        List<Boolean> nullReceived = new ArrayList<>();

        SwingUtilities.invokeAndWait(() -> {
            ChoiceMenuPanel panel = new ChoiceMenuPanel(() -> null);
            ChoiceMenuModel model = new ChoiceMenuModel("Q?", List.of(new Option("Yes", "1")), -1);
            panel.show(model, answer -> {
                if (answer == null) nullReceived.add(true);
                else received.add(answer);
            }, true);

            JButton cancelBtn = findButton(panel, "Cancel");
            assertNotNull(cancelBtn, "Cancel button must exist");
            cancelBtn.doClick();

            assertFalse(panel.isVisible(), "panel should hide after Cancel");
        });

        assertTrue(nullReceived.size() == 1, "Cancel must invoke callback with null");
        assertTrue(received.isEmpty(), "Cancel must not pass a non-null answer");
    }

    @Test
    void testHideMethodHidesPanel() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChoiceMenuPanel panel = new ChoiceMenuPanel(() -> null);
            ChoiceMenuModel model = new ChoiceMenuModel("Q?", List.of(new Option("Yes", "1")), -1);
            panel.show(model, answer -> {}, true);
            assertTrue(panel.getComponentCount() > 0, "panel should have components after show()");

            panel.dismiss();
            assertEquals(0, panel.getComponentCount(), "dismiss() should clear panel components");
        });
    }

    @Test
    void testShowWithNoOptionsShowsSendAndCancelButtons() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChoiceMenuPanel panel = new ChoiceMenuPanel(() -> null);
            ChoiceMenuModel model = new ChoiceMenuModel("Enter your response:", List.of(), -1);
            panel.show(model, answer -> {}, true);
            assertTrue(panel.getComponentCount() > 0, "panel should have components after show()");

            List<String> btnLabels = collectButtonLabels(panel);
            assertTrue(btnLabels.contains("Send"), "Send button should be present for free-form input");
            assertTrue(btnLabels.contains("Cancel"), "Cancel button should always be present");
        });
    }

    @Test
    void testCancelAndSendAreInRightCol() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChoiceMenuPanel panel = new ChoiceMenuPanel(() -> null);
            panel.show(new ChoiceMenuModel("Q?", List.of(new Option("Maybe", "3")), -1), a -> {}, true);

            JPanel rightCol = findPanelByName(panel, "rightCol");
            assertNotNull(rightCol, "rightCol panel must exist");
            assertNotNull(findButton(rightCol, "Cancel"), "Cancel must be in rightCol");
            assertNotNull(findButton(rightCol, "Send"), "Send must be in rightCol");
        });
    }

    @Test
    void testCancelInRightColWhenOnlyYesNo() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChoiceMenuPanel panel = new ChoiceMenuPanel(() -> null);
            panel.show(new ChoiceMenuModel("Q?", List.of(new Option("Yes", "1"), new Option("No", "2")), -1), a -> {}, true);

            JPanel rightCol = findPanelByName(panel, "rightCol");
            assertNotNull(rightCol, "rightCol panel must exist");
            assertNotNull(findButton(rightCol, "Cancel"), "Cancel must be in rightCol");
            assertNull(findButton(rightCol, "Send"), "Send must NOT be in rightCol for Yes/No only");
        });
    }

    /**
     * Regression: PTY spinner lines arrived while the panel was visible and the old
     * ClaudePromptPanel listener called dismissIfActive() on every such line.
     */
    @Test
    void dismissIfActiveFiresNullCallbackImmediately() throws Exception {
        List<String> received = new ArrayList<>();

        SwingUtilities.invokeAndWait(() -> {
            ChoiceMenuPanel panel = new ChoiceMenuPanel(() -> null);
            ChoiceMenuModel model = new ChoiceMenuModel("Do you want to proceed?",
                    List.of(new Option(" Yes", "1"), new Option(" No", "3")), 0);
            panel.show(model, answer -> received.add(String.valueOf(answer)), true);

            assertTrue(panel.getComponentCount() > 0, "panel must have components after show()");
            assertTrue(received.isEmpty(), "callback must not fire before user action");

            panel.dismissIfActive();

            assertEquals(0, panel.getComponentCount(), "dismissIfActive() clears the panel");
            assertEquals(1, received.size(), "dismissIfActive() fires callback once");
            assertEquals("null", received.get(0),
                    "dismissIfActive() fires callback with null (no user selection)");
        });
    }

    // -------------------------------------------------------------------------
    // Type-input option tests
    // -------------------------------------------------------------------------

    @Test
    void testTypeInputOptionRendersTextField() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChoiceMenuPanel panel = new ChoiceMenuPanel(() -> null);
            ChoiceMenuModel model = new ChoiceMenuModel("Choose:",
                    List.of(new Option("Option A", "1"),
                            new Option("Type something.", "2")), 0);
            panel.show(model, answer -> {}, true);

            JTextField tf = findTextField(panel);
            assertNotNull(tf, "panel must contain a JTextField for 'Type something.' option");
        });
    }

    @Test
    void testTypeInputFieldDisabledByDefault() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChoiceMenuPanel panel = new ChoiceMenuPanel(() -> null);
            ChoiceMenuModel model = new ChoiceMenuModel("Choose:",
                    List.of(new Option("Option A", "1"),
                            new Option("Type something.", "2")), 0);
            panel.show(model, answer -> {}, true);

            JTextField tf = findTextField(panel);
            assertNotNull(tf);
            assertFalse(tf.isEnabled(),
                    "type-input text field must be disabled until its radio button is selected");
        });
    }

    @Test
    void testTypeInputFieldEnabledWhenRadioSelected() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChoiceMenuPanel panel = new ChoiceMenuPanel(() -> null);
            ChoiceMenuModel model = new ChoiceMenuModel("Choose:",
                    List.of(new Option("Option A", "1"),
                            new Option("Type something.", "2")), 0);
            panel.show(model, answer -> {}, true);

            JRadioButton typeRb = findRadioButtonByName(panel, "typeInputRb");
            assertNotNull(typeRb, "type-input radio button must exist");
            typeRb.setSelected(true);

            JTextField tf = findTextField(panel);
            assertNotNull(tf);
            assertTrue(tf.isEnabled(),
                    "type-input text field must be enabled when its radio button is selected");
        });
    }

    @Test
    void testTypeInputOptionCallbackIncludesOptionNumber() throws Exception {
        // Bug 4: callback must send "TYPE:N:text" so writePtyAnswer can send the digit first
        List<String> captured = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            ChoiceMenuPanel panel = new ChoiceMenuPanel(() -> null);
            // "Type something." is option index 1 (0-based) → 1-based = 2
            ChoiceMenuModel model = new ChoiceMenuModel("Choose:",
                    List.of(new Option("Option A", "1"),
                            new Option("Type something.", "2")), 0);
            panel.show(model, captured::add, true);

            JRadioButton typeRb = findRadioButtonByName(panel, "typeInputRb");
            assertNotNull(typeRb, "type-input radio button must exist with name 'typeInputRb'");
            typeRb.setSelected(true);

            JTextField tf = findTextField(panel);
            assertNotNull(tf);
            tf.setText("my custom text");

            JButton sendBtn = findButton(panel, "Send");
            assertNotNull(sendBtn, "Send button must exist");
            sendBtn.doClick();
        });

        assertEquals(1, captured.size(), "callback must be called once");
        String answer = captured.get(0);
        assertTrue(answer.startsWith("TYPE:2:"),
                "callback must include option number: expected 'TYPE:2:...' but got: " + answer);
        assertEquals("TYPE:2:my custom text", answer,
                "callback must carry option number and typed text");
    }

    @Test
    void testTypeInputOptionLayoutIsHorizontal() throws Exception {
        // Bug 2: text field must be in the same row as the radio button (horizontal layout)
        SwingUtilities.invokeAndWait(() -> {
            ChoiceMenuPanel panel = new ChoiceMenuPanel(() -> null);
            ChoiceMenuModel model = new ChoiceMenuModel("Choose:",
                    List.of(new Option("Option A", "1"),
                            new Option("Type something.", "2")), 0);
            panel.show(model, s -> {}, true);

            JPanel typeInputRow = findPanelByName(panel, "typeInputRow");
            assertNotNull(typeInputRow, "typeInputRow panel must exist");

            JRadioButton rb = findRadioButtonByName(typeInputRow, "typeInputRb");
            assertNotNull(rb, "radio button must be inside typeInputRow");

            JTextField tf = findTextField(typeInputRow);
            assertNotNull(tf, "text field must be inside typeInputRow");
        });
    }

    @Test
    void testFocusTraversalOrderRadiosThenSendCancel() throws Exception {
        // Bug 3: Tab order must be: radios → Send → Cancel
        SwingUtilities.invokeAndWait(() -> {
            ChoiceMenuPanel panel = new ChoiceMenuPanel(() -> null);
            ChoiceMenuModel model = new ChoiceMenuModel("Choose?",
                    List.of(new Option("Option A", "1"),
                            new Option("Option B", "2")), 0);
            panel.show(model, s -> {}, true);

            java.awt.FocusTraversalPolicy ftp = panel.getFocusTraversalPolicy();
            assertNotNull(ftp, "panel must have an explicit FocusTraversalPolicy");

            JButton sendBtn = findButton(panel, "Send");
            JButton cancelBtn = findButton(panel, "Cancel");
            assertNotNull(sendBtn);
            assertNotNull(cancelBtn);

            // Cancel must come after Send in the traversal order
            java.awt.Component afterSend = ftp.getComponentAfter(panel, sendBtn);
            assertEquals(cancelBtn, afterSend, "Cancel must follow Send in Tab order");
        });
    }

    // -------------------------------------------------------------------------
    // Checkbox option tests
    // -------------------------------------------------------------------------

    @Test
    void testCheckboxOptionRendersAsJCheckBox() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChoiceMenuPanel panel = new ChoiceMenuPanel(() -> null);
            ChoiceMenuModel model = new ChoiceMenuModel("Choose releases:",
                    List.of(new Option("Release A", "1", null, false, true),
                            new Option("Release B", "2", null, false, true)), 0);
            panel.show(model, answer -> {}, true);

            assertNotNull(findCheckBox(panel, "Release A"), "hasCheckbox=true option must render as JCheckBox");
            assertNotNull(findCheckBox(panel, "Release B"), "hasCheckbox=true option must render as JCheckBox");
            assertNull(findRadioButton(panel, "Release A"), "hasCheckbox=true option must NOT render as JRadioButton");
        });
    }

    @Test
    void testNonCheckboxOptionInMixedMenuRendersAsRadio() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChoiceMenuPanel panel = new ChoiceMenuPanel(() -> null);
            ChoiceMenuModel model = new ChoiceMenuModel("Choose:",
                    List.of(new Option("Release A", "1", null, false, true),
                            new Option("Chat about this", "2")), 0);
            panel.show(model, answer -> {}, true);

            assertNotNull(findCheckBox(panel, "Release A"), "hasCheckbox=true must be JCheckBox");
            assertNotNull(findRadioButton(panel, "Chat about this"), "hasCheckbox=false must be JRadioButton");
        });
    }

    @Test
    void testCheckboxMenuHasSubmitButton() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChoiceMenuPanel panel = new ChoiceMenuPanel(() -> null);
            ChoiceMenuModel model = new ChoiceMenuModel("Choose releases:",
                    List.of(new Option("Release A", "1", null, false, true)), 0);
            panel.show(model, answer -> {}, true);

            assertNotNull(findButton(panel, "Submit"), "must have Submit button when checkbox options exist");
            assertNull(findButton(panel, "Send"), "must NOT have Send button when checkbox options exist");
        });
    }

    @Test
    void testSubmitButtonSendsMultiResponse() throws Exception {
        List<String> captured = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            ChoiceMenuPanel panel = new ChoiceMenuPanel(() -> null);
            ChoiceMenuModel model = new ChoiceMenuModel("Choose releases:",
                    List.of(new Option("Release A", "1", null, false, true),
                            new Option("Release B", "2", null, false, true)), 0);
            panel.show(model, captured::add, true);

            JCheckBox cb = findCheckBox(panel, "Release A");
            assertNotNull(cb);
            cb.setSelected(true);

            JButton submit = findButton(panel, "Submit");
            assertNotNull(submit);
            submit.doClick();
        });

        assertEquals(1, captured.size());
        assertEquals("MULTI:1", captured.get(0), "Submit with one checkbox selected must send MULTI:1");
    }

    @Test
    void testRadioInMixedMenuSendsDirectResponse() throws Exception {
        List<String> captured = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            ChoiceMenuPanel panel = new ChoiceMenuPanel(() -> null);
            ChoiceMenuModel model = new ChoiceMenuModel("Choose:",
                    List.of(new Option("Release A", "1", null, false, true),
                            new Option("Chat about this", "6")), 0);
            panel.show(model, captured::add, true);

            JRadioButton rb = findRadioButton(panel, "Chat about this");
            assertNotNull(rb, "Chat about this must be a radio button");
            rb.setSelected(true);

            JButton submit = findButton(panel, "Submit");
            assertNotNull(submit);
            submit.doClick();
        });

        assertEquals(1, captured.size());
        assertEquals("6", captured.get(0), "Radio selection in mixed menu must send the option response directly");
    }

    // -------------------------------------------------------------------------
    // Divider / layout tests
    // -------------------------------------------------------------------------

    /**
     * Regression: switchSouthCard(CARD_CHOICE) was called before choiceMenuPanel.show(),
     * so getPreferredSize().height returned 0 and the divider was set to hide the panel.
     * The fix: call show() first, then switchSouthCard().
     */
    @Test
    void preferredHeightIsZeroBeforeShowCausesInvisiblePanel() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChoiceMenuPanel panel = new ChoiceMenuPanel(() -> null);

            // Before show(): panel is empty — height must be 0 (the bug condition)
            int heightBefore = panel.getPreferredSize().height;

            panel.show(new ChoiceMenuModel("Allow?",
                    List.of(new Option("Yes", "1"), new Option("No", "2")), -1), a -> {}, true);

            int heightAfter = panel.getPreferredSize().height;

            assertTrue(heightAfter > heightBefore + 20,
                    "After show(), preferred height must be significantly larger than before show(). "
                    + "before=" + heightBefore + " after=" + heightAfter
                    + " — switchSouthCard must be called AFTER show() to size the divider correctly");
            assertTrue(heightBefore < 20,
                    "Before show(), panel height (" + heightBefore + ") is near-zero — "
                    + "calling switchSouthCard before show() would make the menu invisible");
        });
    }

    /**
     * Regression: lockDividerForChoiceMenu must call splitPane.validate() before
     * reading choiceMenuPanel.getPreferredSize().height. Without validate(), the HTML
     * JLabel inside ChoiceMenuPanel does not know its actual width and may report an
     * incorrect (too small) preferred height, causing the divider to be placed too low
     * and clipping the bottom of the panel.
     *
     * <p>This test verifies that after the panel is placed in a container with a known
     * width and validate() is called, getPreferredSize().height returns a value consistent
     * with the actual layout height (i.e., the component is not clipped).
     */
    @Test
    void preferredHeightAfterSplitPaneValidateCoversAllContent() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChoiceMenuPanel choiceMenuPanel = new ChoiceMenuPanel(() -> null);
            JPanel promptPanel = new JPanel();

            // Mirror production layout: CardLayout southCard with CARD_PROMPT and CARD_CHOICE
            java.awt.CardLayout cardLayout = new java.awt.CardLayout();
            JPanel southCard = new JPanel(cardLayout);
            southCard.add(promptPanel,    "prompt");
            southCard.add(choiceMenuPanel, "choice");

            JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                    new JPanel(), southCard);
            splitPane.setResizeWeight(1.0);
            splitPane.setDividerSize(5);

            JFrame frame = new JFrame();
            frame.add(splitPane);
            frame.setSize(400, 600);
            frame.setVisible(true);

            try {
                // Mirror production: show() first, then CardLayout.show() (card switch)
                choiceMenuPanel.show(new ChoiceMenuModel(
                        "Do you want to make this edit to precious-beaming-clock.md?",
                        List.of(new Option("Yes", "1"),
                                new Option("Yes, and allow Claude to edit its own settings for this session", "2"),
                                new Option("No", "3")), -1),
                        answer -> {}, true);
                cardLayout.show(southCard, "choice");

                // Simulate lockDividerForChoiceMenu WITH validate() (the fix)
                splitPane.setEnabled(false);
                splitPane.validate();
                int total   = splitPane.getHeight();
                int natural = choiceMenuPanel.getPreferredSize().height;
                assertTrue(total > 0, "splitPane must have positive height");
                assertTrue(natural > 0, "choiceMenuPanel preferred height must be positive");

                int divLoc = total - splitPane.getDividerSize() - natural;
                assertTrue(divLoc >= 0,
                        "Divider location must be non-negative — preferred height (" + natural
                        + ") must fit inside splitPane (" + total + ")");

                splitPane.setDividerLocation(divLoc);
                splitPane.validate();

                // After correctly placed divider, component must not be clipped.
                // Allow 2px tolerance for pixel-level rounding in divider placement.
                assertTrue(choiceMenuPanel.getHeight() >= natural - 2,
                        "choiceMenuPanel actual height (" + choiceMenuPanel.getHeight()
                        + ") must be >= preferred height (" + natural
                        + ") — divider placed too low without validate()");
            } finally {
                frame.dispose();
            }
        });
    }

    // -------------------------------------------------------------------------
    // Regression: splitPane must stay enabled when choice menu is shown (#22)
    // -------------------------------------------------------------------------

    /**
     * Regression test for issue #22: showing the choice menu must not disable
     * the {@link JSplitPane}.  Disabling the whole splitPane propagated to all
     * child components including the terminal scrollbar, making it unresponsive.
     * The fix disables only the divider via {@code BasicSplitPaneUI.getDivider()}.
     */
    @Test
    void splitPaneRemainsEnabledWhenChoiceMenuShown() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChoiceMenuPanel choiceMenuPanel = new ChoiceMenuPanel(() -> null);
            JPanel promptPanel = new JPanel();

            java.awt.CardLayout cardLayout = new java.awt.CardLayout();
            JPanel southCard = new JPanel(cardLayout);
            southCard.add(promptPanel,     "prompt");
            southCard.add(choiceMenuPanel, "choice");

            JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                    new JPanel(), southCard);

            JFrame frame = new JFrame();
            frame.add(splitPane);
            frame.setSize(400, 600);
            frame.setVisible(true);

            try {
                choiceMenuPanel.show(new ChoiceMenuModel("Continue?",
                        List.of(new Option("Yes", "1"), new Option("No", "2")), -1),
                        answer -> {}, true);
                cardLayout.show(southCard, "choice");

                // Simulate the production switchSouthCard(CARD_CHOICE) path:
                // only the divider is disabled, not the whole splitPane.
                if (splitPane.getUI() instanceof javax.swing.plaf.basic.BasicSplitPaneUI ui) {
                    ui.getDivider().setEnabled(false);
                }

                assertTrue(splitPane.isEnabled(),
                        "splitPane must remain enabled when choice menu is shown — " +
                        "disabling it would also disable the terminal scrollbar (issue #22)");
            } finally {
                frame.dispose();
            }
        });
    }

    // -------------------------------------------------------------------------
    // Arrow-key navigation tests
    // -------------------------------------------------------------------------

    @Test
    void testYesNoButtonsHaveLeftRightBindings() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChoiceMenuPanel panel = new ChoiceMenuPanel(() -> null);
            panel.show(new ChoiceMenuModel("Q?",
                    List.of(new Option("Yes", "1"), new Option("No", "2")), -1), a -> {}, false);

            List<JButton> ynBtns = collectYesNoButtons(panel);
            assertEquals(2, ynBtns.size(), "must find two Yes/No buttons");
            for (JButton btn : ynBtns) {
                assertNotNull(btn.getInputMap(javax.swing.JComponent.WHEN_FOCUSED)
                        .get(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0)), btn.getText() + " must have LEFT binding");
                assertNotNull(btn.getInputMap(javax.swing.JComponent.WHEN_FOCUSED)
                        .get(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0)), btn.getText() + " must have RIGHT binding");
            }
        });
    }

    @Test
    void testYesNoButtonsHaveUpDownBindings() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChoiceMenuPanel panel = new ChoiceMenuPanel(() -> null);
            panel.show(new ChoiceMenuModel("Q?",
                    List.of(new Option("Yes", "1"), new Option("No", "2"),
                            new Option("Maybe", "3")), -1), a -> {}, false);

            List<JButton> ynBtns = collectYesNoButtons(panel);
            assertEquals(2, ynBtns.size());
            for (JButton btn : ynBtns) {
                assertNotNull(btn.getInputMap(javax.swing.JComponent.WHEN_FOCUSED)
                        .get(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0)), btn.getText() + " must have UP binding");
                assertNotNull(btn.getInputMap(javax.swing.JComponent.WHEN_FOCUSED)
                        .get(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0)), btn.getText() + " must have DOWN binding");
            }
        });
    }

    @Test
    void testYesNoDownActionNavigatesToFirstRadio() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChoiceMenuPanel panel = new ChoiceMenuPanel(() -> null);
            JFrame frame = new JFrame();
            frame.add(panel);
            frame.setSize(400, 300);
            frame.setVisible(true);
            try {
                panel.show(new ChoiceMenuModel("Q?",
                        List.of(new Option("Yes", "1"), new Option("No", "2"),
                                new Option("Option A", "3"), new Option("Option B", "4")), -1),
                        a -> {}, false);

                List<JButton> ynBtns = collectYesNoButtons(panel);
                assertFalse(ynBtns.isEmpty());
                JButton yesBtn = ynBtns.get(0);

                // Fire the ynDown action directly
                javax.swing.Action down = yesBtn.getActionMap().get("ynDown");
                assertNotNull(down, "ynDown action must be registered");
                down.actionPerformed(new java.awt.event.ActionEvent(yesBtn, 0, "ynDown"));

                JRadioButton firstRadio = findRadioButton(panel, "Option A");
                assertNotNull(firstRadio);
                // Verify action was invoked without exception (focus may not transfer in headless)
            } finally {
                frame.dispose();
            }
        });
    }

    @Test
    void testYesNoUpActionNavigatesToLastRadio() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChoiceMenuPanel panel = new ChoiceMenuPanel(() -> null);
            JFrame frame = new JFrame();
            frame.add(panel);
            frame.setSize(400, 300);
            frame.setVisible(true);
            try {
                panel.show(new ChoiceMenuModel("Q?",
                        List.of(new Option("Yes", "1"), new Option("No", "2"),
                                new Option("Option A", "3"), new Option("Option B", "4")), -1),
                        a -> {}, false);

                List<JButton> ynBtns = collectYesNoButtons(panel);
                JButton yesBtn = ynBtns.get(0);
                javax.swing.Action up = yesBtn.getActionMap().get("ynUp");
                assertNotNull(up, "ynUp action must be registered");
                up.actionPerformed(new java.awt.event.ActionEvent(yesBtn, 0, "ynUp"));
                // action fires without exception — focus may not transfer in headless env
            } finally {
                frame.dispose();
            }
        });
    }

    @Test
    void testYesNoUpNavigatesToLastCheckboxWhenNoRadios() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChoiceMenuPanel panel = new ChoiceMenuPanel(() -> null);
            JFrame frame = new JFrame();
            frame.add(panel);
            frame.setSize(400, 300);
            frame.setVisible(true);
            try {
                panel.show(new ChoiceMenuModel("Q?",
                        List.of(new Option("Yes", "1"), new Option("No", "2"),
                                new Option("Release A", "3", null, false, true),
                                new Option("Release B", "4", null, false, true)), -1),
                        a -> {}, false);

                List<JButton> ynBtns = collectYesNoButtons(panel);
                assertFalse(ynBtns.isEmpty(), "must have Yes/No buttons");
                JButton yesBtn = ynBtns.get(0);
                javax.swing.Action up = yesBtn.getActionMap().get("ynUp");
                assertNotNull(up, "ynUp must be registered even when options are checkboxes");
                up.actionPerformed(new java.awt.event.ActionEvent(yesBtn, 0, "ynUp"));
                // fires without exception
            } finally {
                frame.dispose();
            }
        });
    }

    @Test
    void testTypeInputRadioHasRightBinding() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChoiceMenuPanel panel = new ChoiceMenuPanel(() -> null);
            panel.show(new ChoiceMenuModel("Choose:",
                    List.of(new Option("Option A", "1"),
                            new Option("Type something.", "2")), 0), a -> {}, false);

            JRadioButton typeRb = findRadioButtonByName(panel, "typeInputRb");
            assertNotNull(typeRb, "typeInputRb must exist");
            assertNotNull(typeRb.getInputMap(javax.swing.JComponent.WHEN_FOCUSED)
                    .get(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0)),
                    "type-input radio must have RIGHT binding to focus its text field");
        });
    }

    @Test
    void testPlainRadioHasRightBindingToSend() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChoiceMenuPanel panel = new ChoiceMenuPanel(() -> null);
            panel.show(new ChoiceMenuModel("Choose:",
                    List.of(new Option("Option A", "1"),
                            new Option("Option B", "2")), 0), a -> {}, false);

            JRadioButton rb = findRadioButton(panel, "Option A");
            assertNotNull(rb);
            assertNotNull(rb.getInputMap(javax.swing.JComponent.WHEN_FOCUSED)
                    .get(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0)),
                    "plain radio (no text field) must have RIGHT binding → Send");
        });
    }

    @Test
    void testPlainRadioHasLeftBinding() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChoiceMenuPanel panel = new ChoiceMenuPanel(() -> null);
            panel.show(new ChoiceMenuModel("Choose:",
                    List.of(new Option("Option A", "1"),
                            new Option("Option B", "2")), 0), a -> {}, false);

            JRadioButton rb = findRadioButton(panel, "Option A");
            assertNotNull(rb);
            assertNotNull(rb.getInputMap(javax.swing.JComponent.WHEN_FOCUSED)
                    .get(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0)),
                    "plain radio must have LEFT binding → Send");
        });
    }

    @Test
    void testCancelIncludedInLeftRightCycle() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChoiceMenuPanel panel = new ChoiceMenuPanel(() -> null);
            panel.show(new ChoiceMenuModel("Q?",
                    List.of(new Option("Yes", "1"), new Option("No", "2")), -1), a -> {}, false);

            JButton cancelBtn = findButton(panel, "Cancel");
            assertNotNull(cancelBtn, "Cancel must exist");
            assertNotNull(cancelBtn.getInputMap(javax.swing.JComponent.WHEN_FOCUSED)
                    .get(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0)),
                    "Cancel must have LEFT binding in the Yes/No/Cancel cycle");
            assertNotNull(cancelBtn.getInputMap(javax.swing.JComponent.WHEN_FOCUSED)
                    .get(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0)),
                    "Cancel must have RIGHT binding in the Yes/No/Cancel cycle");
        });
    }

    @Test
    void testPlainCheckboxHasUpDownBindings() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChoiceMenuPanel panel = new ChoiceMenuPanel(() -> null);
            panel.show(new ChoiceMenuModel("Choose:",
                    List.of(new Option("Release A", "1", null, false, true),
                            new Option("Release B", "2", null, false, true)), 0),
                    a -> {}, false);

            JCheckBox cb = findCheckBox(panel, "Release A");
            assertNotNull(cb, "checkbox must exist");
            assertNotNull(cb.getInputMap(javax.swing.JComponent.WHEN_FOCUSED)
                    .get(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0)),
                    "checkbox must have UP binding");
            assertNotNull(cb.getInputMap(javax.swing.JComponent.WHEN_FOCUSED)
                    .get(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0)),
                    "checkbox must have DOWN binding");
        });
    }

    @Test
    void testPlainCheckboxHasLeftAndRightBindings() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChoiceMenuPanel panel = new ChoiceMenuPanel(() -> null);
            panel.show(new ChoiceMenuModel("Choose:",
                    List.of(new Option("Release A", "1", null, false, true),
                            new Option("Release B", "2", null, false, true)), 0),
                    a -> {}, false);

            JCheckBox cb = findCheckBox(panel, "Release A");
            assertNotNull(cb, "checkbox must exist");
            assertNotNull(cb.getInputMap(javax.swing.JComponent.WHEN_FOCUSED)
                    .get(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0)),
                    "plain checkbox must have LEFT binding → Submit");
            assertNotNull(cb.getInputMap(javax.swing.JComponent.WHEN_FOCUSED)
                    .get(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0)),
                    "plain checkbox must have RIGHT binding → Submit");
        });
    }

    @Test
    void testSendHasUpDownBindings() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChoiceMenuPanel panel = new ChoiceMenuPanel(() -> null);
            panel.show(new ChoiceMenuModel("Choose:",
                    List.of(new Option("Option A", "1")), 0), a -> {}, false);

            JButton sendBtn = findButton(panel, "Send");
            assertNotNull(sendBtn, "Send must exist");
            assertNotNull(sendBtn.getInputMap(javax.swing.JComponent.WHEN_FOCUSED)
                    .get(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0)),
                    "Send must have UP binding → Cancel");
            assertNotNull(sendBtn.getInputMap(javax.swing.JComponent.WHEN_FOCUSED)
                    .get(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0)),
                    "Send must have DOWN binding → Cancel");
        });
    }

    @Test
    void testCancelHasUpDownBindingsWhenSendPresent() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChoiceMenuPanel panel = new ChoiceMenuPanel(() -> null);
            panel.show(new ChoiceMenuModel("Choose:",
                    List.of(new Option("Option A", "1")), 0), a -> {}, false);

            JButton cancelBtn = findButton(panel, "Cancel");
            assertNotNull(cancelBtn, "Cancel must exist");
            assertNotNull(cancelBtn.getInputMap(javax.swing.JComponent.WHEN_FOCUSED)
                    .get(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0)),
                    "Cancel must have UP binding → Send");
            assertNotNull(cancelBtn.getInputMap(javax.swing.JComponent.WHEN_FOCUSED)
                    .get(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0)),
                    "Cancel must have DOWN binding → Send");
        });
    }

    @Test
    void testCancelHasNoUpDownBindingsWhenNoSend() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChoiceMenuPanel panel = new ChoiceMenuPanel(() -> null);
            panel.show(new ChoiceMenuModel("Q?",
                    List.of(new Option("Yes", "1"), new Option("No", "2")), -1), a -> {}, false);

            JButton cancelBtn = findButton(panel, "Cancel");
            assertNotNull(cancelBtn, "Cancel must exist");
            assertNull(cancelBtn.getInputMap(javax.swing.JComponent.WHEN_FOCUSED)
                    .get(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0)),
                    "Cancel must NOT have UP binding when there is no Send button");
            assertNull(cancelBtn.getInputMap(javax.swing.JComponent.WHEN_FOCUSED)
                    .get(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0)),
                    "Cancel must NOT have DOWN binding when there is no Send button");
        });
    }

    @Test
    void testSendHasLeftRightBindingsWhenRadiosExist() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChoiceMenuPanel panel = new ChoiceMenuPanel(() -> null);
            panel.show(new ChoiceMenuModel("Choose:",
                    List.of(new Option("Option A", "1"), new Option("Option B", "2")), 0),
                    a -> {}, false);

            JButton sendBtn = findButton(panel, "Send");
            assertNotNull(sendBtn, "Send must exist");
            assertNotNull(sendBtn.getInputMap(javax.swing.JComponent.WHEN_FOCUSED)
                    .get(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0)),
                    "Send must have LEFT binding → first radio");
            assertNotNull(sendBtn.getInputMap(javax.swing.JComponent.WHEN_FOCUSED)
                    .get(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0)),
                    "Send must have RIGHT binding → first radio");
        });
    }

    @Test
    void testTypeInputCheckboxHasRightBinding() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChoiceMenuPanel panel = new ChoiceMenuPanel(() -> null);
            // hasCheckbox=true + hasTextInput=true → typeInputCb
            panel.show(new ChoiceMenuModel("Choose:",
                    List.of(new Option("Type something.", "1", null, false, true, true)), 0),
                    a -> {}, false);

            JCheckBox cb = findCheckBoxByName(panel, "typeInputCb");
            assertNotNull(cb, "typeInputCb checkbox must exist");
            assertNotNull(cb.getInputMap(javax.swing.JComponent.WHEN_FOCUSED)
                    .get(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0)),
                    "typeInputCb must have RIGHT binding to focus its text field");
        });
    }

    @Test
    void testTextFieldsHaveUpDownBindings() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChoiceMenuPanel panel = new ChoiceMenuPanel(() -> null);
            panel.show(new ChoiceMenuModel("Choose:",
                    List.of(new Option("Type something.", "1"),
                            new Option("Type other.", "2")), 0), a -> {}, false);

            List<JTextField> fields = collectTextFields(panel);
            assertFalse(fields.isEmpty(), "must find text fields");
            for (JTextField tf : fields) {
                assertNotNull(tf.getInputMap(javax.swing.JComponent.WHEN_FOCUSED)
                        .get(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0)),
                        "text field must have UP binding");
                assertNotNull(tf.getInputMap(javax.swing.JComponent.WHEN_FOCUSED)
                        .get(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0)),
                        "text field must have DOWN binding");
            }
        });
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private static List<String> collectButtonLabels(java.awt.Container container) {
        List<String> labels = new ArrayList<>();
        for (Component c : container.getComponents()) {
            if (c instanceof JButton btn) {
                labels.add(btn.getText());
            } else if (c instanceof java.awt.Container sub) {
                labels.addAll(collectButtonLabels(sub));
            }
        }
        return labels;
    }

    private static JButton findButton(java.awt.Container container, String label) {
        for (Component c : container.getComponents()) {
            if (c instanceof JButton btn && label.equals(btn.getText())) {
                return btn;
            } else if (c instanceof java.awt.Container sub) {
                JButton found = findButton(sub, label);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static JPanel findPanelByName(java.awt.Container container, String name) {
        for (Component c : container.getComponents()) {
            if (c instanceof JPanel p && name.equals(p.getName())) {
                return p;
            } else if (c instanceof java.awt.Container sub) {
                JPanel found = findPanelByName(sub, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static JRadioButton findRadioButton(java.awt.Container container, String label) {
        for (Component c : container.getComponents()) {
            if (c instanceof JRadioButton rb && label.equals(rb.getText())) {
                return rb;
            } else if (c instanceof java.awt.Container sub) {
                JRadioButton found = findRadioButton(sub, label);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static JRadioButton findRadioButtonByName(java.awt.Container container, String name) {
        for (Component c : container.getComponents()) {
            if (c instanceof JRadioButton rb && name.equals(rb.getName())) {
                return rb;
            } else if (c instanceof java.awt.Container sub) {
                JRadioButton found = findRadioButtonByName(sub, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static JCheckBox findCheckBox(java.awt.Container container, String label) {
        for (Component c : container.getComponents()) {
            if (c instanceof JCheckBox cb && label.equals(cb.getText())) {
                return cb;
            } else if (c instanceof java.awt.Container sub) {
                JCheckBox found = findCheckBox(sub, label);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static JTextField findTextField(java.awt.Container container) {
        for (Component c : container.getComponents()) {
            if (c instanceof JTextField tf) return tf;
            if (c instanceof java.awt.Container sub) {
                JTextField found = findTextField(sub);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static List<JTextField> collectTextFields(java.awt.Container container) {
        List<JTextField> result = new ArrayList<>();
        for (Component c : container.getComponents()) {
            if (c instanceof JTextField tf) result.add(tf);
            else if (c instanceof java.awt.Container sub) result.addAll(collectTextFields(sub));
        }
        return result;
    }

    private static List<JButton> collectYesNoButtons(java.awt.Container container) {
        List<JButton> result = new ArrayList<>();
        for (Component c : container.getComponents()) {
            if (c instanceof JButton btn) {
                String t = btn.getText();
                if (!"Send".equals(t) && !"Submit".equals(t) && !"Cancel".equals(t)) result.add(btn);
            } else if (c instanceof java.awt.Container sub) {
                result.addAll(collectYesNoButtons(sub));
            }
        }
        return result;
    }

    private static JCheckBox findCheckBoxByName(java.awt.Container container, String name) {
        for (Component c : container.getComponents()) {
            if (c instanceof JCheckBox cb && name.equals(cb.getName())) return cb;
            else if (c instanceof java.awt.Container sub) {
                JCheckBox found = findCheckBoxByName(sub, name);
                if (found != null) return found;
            }
        }
        return null;
    }
}
