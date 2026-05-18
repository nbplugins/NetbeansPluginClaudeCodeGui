package io.github.nbplugins.claudecodegui.process;


/**
 * Minimal parser for the claude CLI {@code --output-format stream-json} NDJSON stream.
 *
 * <p>Avoids external JSON dependencies by using targeted field extraction for the
 * specific keys needed from the well-known event types emitted by claude:
 * <ul>
 *   <li>{@code system} — contains {@code sessionId} (camelCase)</li>
 *   <li>{@code result} — contains {@code result} (full response text),
 *       {@code session_id} (snake_case), and {@code total_cost_usd}</li>
 * </ul>
 */
public final class StreamJsonParser {

    private StreamJsonParser() {}

    // -------------------------------------------------------------------------
    // public API
    // -------------------------------------------------------------------------

    /**
     * Extracts the value of a string field from a JSON object.
     *
     * <p>Only searches for the field at any depth — this is intentional: all
     * fields of interest ({@code type}, {@code result}, {@code sessionId}, …)
     * are either top-level or uniquely named within the documents produced by
     * claude CLI.
     *
     * @param json  a single NDJSON line (JSON object)
     * @param field the field name
     * @return the unescaped string value, or {@code null} if not found
     */
    public static String extractString(String json, String field) {
        String needle = "\"" + field + "\"";
        int searchFrom = 0;

        while (true) {
            int keyPos = json.indexOf(needle, searchFrom);
            if (keyPos < 0) return null;

            // Verify this occurrence is a KEY: the character(s) immediately
            // after the closing quote of the field name must be ':' (JSON key syntax).
            int afterNeedle = keyPos + needle.length();
            int colonPos = afterNeedle;
            while (colonPos < json.length() && json.charAt(colonPos) == ' ') colonPos++;
            if (colonPos >= json.length() || json.charAt(colonPos) != ':') {
                // This match is a value occurrence, not a key — skip past it
                searchFrom = keyPos + 1;
                continue;
            }

            // skip whitespace after ':', then expect opening quote
            int i = colonPos + 1;
            while (i < json.length() && json.charAt(i) == ' ') i++;
            if (i >= json.length() || json.charAt(i) != '"') return null;
            i++; // skip opening quote

            // read until unescaped closing quote
            StringBuilder sb = new StringBuilder();
            while (i < json.length()) {
                char c = json.charAt(i);
                if (c == '\\' && i + 1 < json.length()) {
                    char esc = json.charAt(++i);
                    switch (esc) {
                        case 'n'  -> sb.append('\n');
                        case 't'  -> sb.append('\t');
                        case 'r'  -> sb.append('\r');
                        case '"'  -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/'  -> sb.append('/');
                        case 'u'  -> {
                            if (i + 4 < json.length()) {
                                try {
                                    sb.append((char) Integer.parseInt(
                                            json.substring(i + 1, i + 5), 16));
                                } catch (NumberFormatException ignored) {}
                                i += 4;
                            }
                        }
                        default -> { sb.append('\\'); sb.append(esc); }
                    }
                } else if (c == '"') {
                    break;
                } else {
                    sb.append(c);
                }
                i++;
            }
            return sb.toString();
        } // end while(true)
    }

    /**
     * Extracts the session ID from a stream-json event line.
     *
     * <p>Handles both {@code sessionId} (camelCase, present in the
     * {@code system} init event) and {@code session_id} (snake_case, present
     * in the {@code result} event).
     *
     * @param json a single NDJSON line
     * @return the session ID, or {@code null} if not present
     */
    public static String extractSessionId(String json) {
        String id = extractString(json, "sessionId");
        if (id == null) {
            id = extractString(json, "session_id");
        }
        return id;
    }

    /**
     * Extracts the text content from an {@code assistant} stream-json event.
     *
     * <p>Locates the first {@code {"type":"text","text":"..."}} content item in the
     * event's {@code content} array and returns its unescaped value.  Returns
     * {@code null} for events that contain only {@code thinking} or {@code tool_use}
     * content.
     *
     * @param json a single NDJSON line for an {@code assistant} event
     * @return the unescaped text value, or {@code null} if no text content present
     */
    public static String extractAssistantText(String json) {
        int typeTextPos = json.indexOf("\"type\":\"text\"");
        if (typeTextPos < 0) return null;
        // After "type":"text" the next "text": key is the content text field.
        return extractString(json.substring(typeTextPos + 13), "text");
    }

}
