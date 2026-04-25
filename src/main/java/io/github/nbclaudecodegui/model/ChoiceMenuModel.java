package io.github.nbclaudecodegui.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

/**
 * Describes the structure of a numbered-menu prompt from Claude.
 *
 * <p>Supports JSON serialization/deserialization via Jackson.
 */
public final class ChoiceMenuModel {

    /**
     * A single option in the menu.
     *
     * <p>{@code display} is the user-facing label; {@code response} is the string sent back
     * to Claude (usually the item number). {@code description} is an optional subtitle shown
     * below the label — present when Claude renders a description line under the option.
     * {@code checked} is set when the option has a checked checkbox marker ({@code [x]}).
     * {@code hasCheckbox} is set when the option has any checkbox marker ({@code [ ]} or {@code [x]}).
     * {@code hasTextInput} is set when the option requires free-text entry, detected by a
     * {@code "shift+tab to approve with this feedback"} hint line below the option.
     *
     * @param display      user-facing label shown in the menu
     * @param response     string sent back to Claude when this option is selected
     * @param description  optional subtitle shown below the label; may be {@code null}
     * @param checked      whether the checkbox is pre-checked
     * @param hasCheckbox  whether the option has a checkbox marker at all
     * @param hasTextInput whether the option expects free-text input (submitted with \r)
     */
    public record Option(
            @JsonProperty("display") String display,
            @JsonProperty("response") String response,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            @JsonProperty("description") String description,
            @JsonInclude(JsonInclude.Include.NON_DEFAULT)
            @JsonProperty("checked") boolean checked,
            @JsonInclude(JsonInclude.Include.NON_DEFAULT)
            @JsonProperty("hasCheckbox") boolean hasCheckbox,
            @JsonInclude(JsonInclude.Include.NON_DEFAULT)
            @JsonProperty("hasTextInput") boolean hasTextInput) {

        /** Jackson deserialization constructor. */
        @JsonCreator
        public Option {
        }

        /**
         * Convenience constructor without description, checked, hasCheckbox, or hasTextInput.
         *
         * @param display  user-facing label
         * @param response string sent back to Claude
         */
        public Option(String display, String response) {
            this(display, response, null, false, false, false);
        }

        /**
         * Convenience constructor without checked, hasCheckbox, or hasTextInput.
         *
         * @param display     user-facing label
         * @param response    string sent back to Claude
         * @param description optional subtitle; may be {@code null}
         */
        public Option(String display, String response, String description) {
            this(display, response, description, false, false, false);
        }

        /**
         * Convenience constructor without hasCheckbox or hasTextInput.
         *
         * @param display     user-facing label
         * @param response    string sent back to Claude
         * @param description optional subtitle; may be {@code null}
         * @param checked     whether the checkbox is pre-checked
         */
        public Option(String display, String response, String description, boolean checked) {
            this(display, response, description, checked, false, false);
        }

        /**
         * Convenience constructor without hasTextInput.
         *
         * @param display     user-facing label
         * @param response    string sent back to Claude
         * @param description optional subtitle; may be {@code null}
         * @param checked     whether the checkbox is pre-checked
         * @param hasCheckbox whether the option has a checkbox marker
         */
        public Option(String display, String response, String description, boolean checked,
                      boolean hasCheckbox) {
            this(display, response, description, checked, hasCheckbox, false);
        }
    }

    @JsonProperty("text")
    private final String text;
    @JsonProperty("options")
    private final List<Option> options;
    @JsonProperty("defaultOptionIndex")
    private final int defaultOptionIndex;

    /**
     * Creates a choice-menu model.
     *
     * @param text               prompt text displayed above the options
     * @param options            list of selectable options
     * @param defaultOptionIndex index of the pre-selected option
     */
    @JsonCreator
    public ChoiceMenuModel(
            @JsonProperty("text") String text,
            @JsonProperty("options") List<Option> options,
            @JsonProperty("defaultOptionIndex") int defaultOptionIndex) {
        this.text = text;
        this.options = options;
        this.defaultOptionIndex = defaultOptionIndex;
    }

    /**
     * Returns the prompt text.
     *
     * @return prompt text
     */
    public String text() { return text; }

    /**
     * Returns the list of selectable options.
     *
     * @return option list
     */
    public List<Option> options() { return options; }

    /**
     * Returns the index of the pre-selected option.
     *
     * @return default option index
     */
    public int defaultOptionIndex() { return defaultOptionIndex; }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Serializes this model to a JSON string.
     *
     * @return JSON representation
     * @throws Exception if serialization fails
     */
    public String toJson() throws Exception {
        return MAPPER.writeValueAsString(this);
    }

    /**
     * Deserializes a {@code ChoiceMenuModel} from a JSON string.
     *
     * @param json JSON string
     * @return deserialized model
     * @throws Exception if deserialization fails
     */
    public static ChoiceMenuModel fromJson(String json) throws Exception {
        return MAPPER.readValue(json, ChoiceMenuModel.class);
    }
}
