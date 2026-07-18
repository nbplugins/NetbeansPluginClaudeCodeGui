package io.github.nbplugins.claudecodegui.chatgptauth;

/**
 * Thrown when the ChatGPT OAuth login or token-refresh flow fails.
 *
 * <p>{@link #getMessage()} is written to be shown directly to the user (e.g.
 * in an Anthropic-format proxy error or a dialog), so callers should not
 * wrap it further before display.
 */
public final class OAuthException extends Exception {

    /**
     * Creates a new exception with a user-facing message.
     *
     * @param message human-readable description of the failure
     */
    public OAuthException(String message) {
        super(message);
    }

    /**
     * Creates a new exception with a user-facing message and cause.
     *
     * @param message human-readable description of the failure
     * @param cause   underlying cause
     */
    public OAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
