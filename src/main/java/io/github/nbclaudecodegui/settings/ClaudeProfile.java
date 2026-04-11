package io.github.nbclaudecodegui.settings;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable(-ish) POJO representing a Claude Code connection profile.
 *
 * <p>A profile bundles an isolated {@code CLAUDE_CONFIG_DIR}, authentication
 * credentials, proxy settings, and arbitrary extra environment variables.
 * The built-in <em>Default</em> profile has {@code id=""} and is never
 * serialised to or from {@link ClaudeProfileStore}; it is constructed
 * programmatically.
 *
 * <h2>Connection type</h2>
 * {@link ConnectionType} is <em>not</em> stored — it is derived from the
 * credential fields by {@link #computeConnectionType()}:
 * <ul>
 *   <li>non-blank {@code apiKey} + non-blank {@code baseUrl} → {@link ConnectionType#OTHER_API}</li>
 *   <li>non-blank {@code apiKey} → {@link ConnectionType#CLAUDE_API}</li>
 *   <li>non-blank {@code token} → {@link ConnectionType#SUBSCRIPTION}</li>
 *   <li>otherwise → {@link ConnectionType#CLAUDE_MANAGED}</li>
 * </ul>
 *
 * <h2>Profile name validation</h2>
 * Profile names must not be blank, must not equal {@code "."} or {@code ".."},
 * and must not contain any of: space, {@code /}, {@code \}, {@code :},
 * {@code *}, {@code ?}, {@code "}, {@code <}, {@code >}, {@code |}, or NUL.
 * Use {@link #validateName(String)} to check before creating or renaming.
 */
public final class ClaudeProfile {

    // -------------------------------------------------------------------------
    // Enums
    // -------------------------------------------------------------------------

    /**
     * Describes how a profile authenticates with Claude / the AI backend.
     * Derived at runtime from credential fields; never persisted.
     */
    public enum ConnectionType {
        /** Claude manages its own authentication (OAuth flow). No env vars injected. */
        CLAUDE_MANAGED,
        /** Anthropic subscription — injects {@code CLAUDE_CODE_OAUTH_TOKEN}. */
        SUBSCRIPTION,
        /** Direct Anthropic API — injects {@code ANTHROPIC_API_KEY}. */
        CLAUDE_API,
        /** Third-party or custom API endpoint — injects {@code ANTHROPIC_AUTH_TOKEN}
         *  and {@code ANTHROPIC_BASE_URL}. */
        OTHER_API
    }

    /**
     * Controls which HTTP proxy variables are injected into the process
     * environment.
     */
    public enum ProxyMode {
        /**
         * Do not touch proxy env vars — they are inherited from the IDE
         * process environment (default behaviour).
         */
        SYSTEM_MANAGED,
        /**
         * Force no-proxy by setting {@code HTTP_PROXY}, {@code HTTPS_PROXY},
         * and {@code NO_PROXY} to empty strings.
         */
        NO_PROXY,
        /**
         * Inject user-supplied {@code HTTP_PROXY} and {@code HTTPS_PROXY}
         * (and optionally {@code NO_PROXY}) values.
         */
        CUSTOM
    }

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    /** The {@code id} value for the built-in Default profile. */
    public static final String DEFAULT_ID = "";

    private static final String[] ALIAS_NAMES = {"sonnet", "opus", "haiku"};
    private static final String[] ALIAS_ENV_KEYS = {
        "ANTHROPIC_DEFAULT_SONNET_MODEL",
        "ANTHROPIC_DEFAULT_OPUS_MODEL",
        "ANTHROPIC_DEFAULT_HAIKU_MODEL"
    };

    /** The display name of the built-in Default profile. */
    public static final String DEFAULT_NAME = "Default";

    // -------------------------------------------------------------------------
    // Fields (serialised)
    // -------------------------------------------------------------------------

    /** Profile identifier.  {@code ""} for the Default profile; equals the directory name otherwise. */
    private String id;

    /** Human-readable display name shown in combo boxes and settings UI. */
    private String name;

    /** {@code CLAUDE_CODE_OAUTH_TOKEN} value; blank when not used. */
    private String token;

    /** {@code ANTHROPIC_API_KEY} value; blank when not used. */
    private String apiKey;

    /** {@code ANTHROPIC_BASE_URL} value; blank when not used. */
    private String baseUrl;

    /**
     * Proxy injection mode.  Defaults to {@link ProxyMode#SYSTEM_MANAGED}.
     */
    private ProxyMode proxyMode;

    /** {@code HTTP_PROXY} value used when {@code proxyMode == CUSTOM}. */
    private String httpProxy;

    /** {@code HTTPS_PROXY} value used when {@code proxyMode == CUSTOM}. */
    private String httpsProxy;

    /** {@code NO_PROXY} value used when {@code proxyMode == CUSTOM}. */
    private String noProxy;

    /**
     * Arbitrary extra environment variables injected on top of all others.
     * Each entry is a two-element array {@code [key, value]}.
     */
    private List<String[]> extraEnvVars;

    /**
     * Model alias map: alias name ({@code "sonnet"}, {@code "opus"}, {@code "haiku"})
     * → model ID. Emitted as {@code ANTHROPIC_DEFAULT_*_MODEL} env vars.
     */
    private Map<String, String> modelAliases;

    /**
     * Model IDs assigned the {@code "custom"} alias — injected into the model combo
     * as-is. Multiple entries are allowed. No env var is emitted for these.
     */
    private List<String> customModels;

    /**
     * Extra CLI arguments passed to the {@code claude} process on startup.
     * E.g. {@code "--verbose --dangerously-skip-permissions"}.
     */
    private String extraCliArgs;

    /**
     * Explicit storage directory for {@code CLAUDE_CONFIG_DIR}.
     * When blank, the computed path {@code <profilesDir>/<id>} is used.
     * When non-blank, this exact path is used as-is.
     */
    private String storageDir;

    // -------------------------------------------------------------------------
    // No-arg constructor (Jackson)
    // -------------------------------------------------------------------------

    /** No-arg constructor required by Jackson for deserialisation. */
    public ClaudeProfile() {
        this.id           = "";
        this.name         = "";
        this.token        = "";
        this.apiKey       = "";
        this.baseUrl      = "";
        this.proxyMode    = ProxyMode.SYSTEM_MANAGED;
        this.httpProxy    = "";
        this.httpsProxy   = "";
        this.noProxy      = "";
        this.extraEnvVars  = new ArrayList<>();
        this.modelAliases  = new HashMap<>();
        this.customModels  = new ArrayList<>();
        this.extraCliArgs  = "";
        this.storageDir    = "";
    }

    // -------------------------------------------------------------------------
    // Factory helpers
    // -------------------------------------------------------------------------

    /**
     * Creates the built-in Default profile.
     * All credential and proxy fields are blank; {@link ProxyMode#SYSTEM_MANAGED}.
     *
     * @return the Default profile singleton value
     */
    public static ClaudeProfile createDefault() {
        ClaudeProfile p = new ClaudeProfile();
        p.id   = DEFAULT_ID;
        p.name = DEFAULT_NAME;
        return p;
    }

    /**
     * Creates a new empty named profile with the given id/name.
     *
     * @param name profile name (must pass {@link #validateName(String)})
     * @return a new profile with all credential fields blank
     * @throws IllegalArgumentException if {@code name} is invalid
     */
    public static ClaudeProfile createNamed(String name) {
        String err = validateName(name);
        if (err != null) {
            throw new IllegalArgumentException(err);
        }
        ClaudeProfile p = new ClaudeProfile();
        p.id   = name;
        p.name = name;
        return p;
    }

    // -------------------------------------------------------------------------
    // Derived / computed
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if this is the built-in Default profile.
     *
     * @return {@code true} when {@code id} is blank
     */
    @JsonIgnore
    public boolean isDefault() {
        return id == null || id.isBlank();
    }

    /**
     * Computes the {@link ConnectionType} from the stored credential fields.
     * This value is intentionally not persisted.
     *
     * @return the connection type inferred from current field values
     */
    public ConnectionType computeConnectionType() {
        if (!isBlank(apiKey)) {
            return isBlank(baseUrl) ? ConnectionType.CLAUDE_API : ConnectionType.OTHER_API;
        }
        if (!isBlank(token)) {
            return ConnectionType.SUBSCRIPTION;
        }
        return ConnectionType.CLAUDE_MANAGED;
    }

    /**
     * Returns the environment variables that this profile contributes,
     * keyed by variable name.
     *
     * <p>Variables are added in this order (later entries win):
     * <ol>
     *   <li>Auth variables (based on {@link #computeConnectionType()})</li>
     *   <li>Proxy variables (based on {@link #proxyMode})</li>
     *   <li>{@link #extraEnvVars} (in list order)</li>
     * </ol>
     *
     * <p>For the Default profile ({@link #isDefault()} == true) only
     * {@link #extraEnvVars} and proxy/auth overrides are applied; no
     * {@code CLAUDE_CONFIG_DIR} is included here (the caller sets that).
     *
     * @return modifiable map of env var name → value
     */
    public Map<String, String> toEnvVars() {
        Map<String, String> env = new HashMap<>();

        // Auth
        switch (computeConnectionType()) {
            case SUBSCRIPTION -> env.put("CLAUDE_CODE_OAUTH_TOKEN", blankToEmpty(token));
            case CLAUDE_API   -> { /* API key is written to settings.local.json as apiKeyHelper */ }
            case OTHER_API    -> {
                env.put("ANTHROPIC_AUTH_TOKEN", blankToEmpty(apiKey));
                env.put("ANTHROPIC_BASE_URL", blankToEmpty(baseUrl));
            }
            default -> { /* CLAUDE_MANAGED — nothing to inject */ }
        }

        // Proxy
        if (proxyMode == null) proxyMode = ProxyMode.SYSTEM_MANAGED;
        switch (proxyMode) {
            case NO_PROXY -> {
                env.put("HTTP_PROXY",  "");
                env.put("HTTPS_PROXY", "");
                env.put("NO_PROXY",    "");
            }
            case CUSTOM -> {
                if (!isBlank(httpProxy))  env.put("HTTP_PROXY",  httpProxy);
                if (!isBlank(httpsProxy)) env.put("HTTPS_PROXY", httpsProxy);
                if (!isBlank(noProxy))    env.put("NO_PROXY",    noProxy);
            }
            default -> { /* SYSTEM_MANAGED — inherit from parent process */ }
        }

        // Model aliases (ANTHROPIC_DEFAULT_*_MODEL)
        if (modelAliases != null) {
            for (int i = 0; i < ALIAS_NAMES.length; i++) {
                String modelId = modelAliases.get(ALIAS_NAMES[i]);
                if (modelId != null && !modelId.isBlank()) {
                    env.put(ALIAS_ENV_KEYS[i], modelId);
                }
            }
        }

        // Extra vars (overwrite everything above if there is a conflict)
        if (extraEnvVars != null) {
            for (String[] kv : extraEnvVars) {
                if (kv != null && kv.length == 2 && kv[0] != null && !kv[0].isBlank()) {
                    env.put(kv[0], kv[1] != null ? kv[1] : "");
                }
            }
        }

        return env;
    }

    // -------------------------------------------------------------------------
    // Validation
    // -------------------------------------------------------------------------

    /**
     * Validates a prospective profile name.
     *
     * <p>A valid name:
     * <ul>
     *   <li>is non-blank</li>
     *   <li>is not {@code "."} or {@code ".."}</li>
     *   <li>does not contain: space, {@code /}, {@code \}, {@code :},
     *       {@code *}, {@code ?}, {@code "}, {@code <}, {@code >},
     *       {@code |}, or NUL ({@code \0})</li>
     * </ul>
     *
     * @param name candidate profile name
     * @return {@code null} if valid; a human-readable error message if invalid
     */
    public static String validateName(String name) {
        if (name == null || name.isBlank()) {
            return "Profile name must not be blank.";
        }
        if (".".equals(name) || "..".equals(name)) {
            return "Profile name must not be \".\" or \"..\".";
        }
        for (char c : name.toCharArray()) {
            if (c == ' ' || c == '/' || c == '\\' || c == ':' || c == '*'
                    || c == '?' || c == '"' || c == '<' || c == '>' || c == '|'
                    || c == '\0') {
                return "Profile name must not contain spaces or the characters / \\ : * ? \" < > | or NUL.";
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Getters / setters  (used by Jackson and the UI)
    // -------------------------------------------------------------------------

    /**
     * Returns the profile id.
     *
     * @return profile id (blank for Default)
     */
    public String getId()   { return id != null ? id : ""; }

    /**
     * Sets the profile id.
     *
     * @param id profile id
     */
    public void setId(String id) { this.id = id != null ? id : ""; }

    /**
     * Returns the display name.
     *
     * @return display name
     */
    public String getName() { return name != null ? name : ""; }

    /**
     * Sets the display name.
     *
     * @param name display name
     */
    public void setName(String name) { this.name = name != null ? name : ""; }

    /**
     * Returns the OAuth token for Subscription mode.
     *
     * @return OAuth token
     */
    public String getToken()   { return blankToEmpty(token); }

    /**
     * Sets the OAuth token.
     *
     * @param token OAuth token
     */
    public void setToken(String token) { this.token = token; }

    /**
     * Returns the Anthropic API key.
     *
     * @return API key
     */
    public String getApiKey()  { return blankToEmpty(apiKey); }

    /**
     * Sets the Anthropic API key.
     *
     * @param apiKey API key
     */
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    /**
     * Returns the custom base URL for Other API mode.
     *
     * @return custom base URL
     */
    public String getBaseUrl() { return blankToEmpty(baseUrl); }

    /**
     * Sets the custom base URL.
     *
     * @param baseUrl custom base URL
     */
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    /**
     * Returns the current proxy mode.
     *
     * @return proxy mode
     */
    public ProxyMode getProxyMode() { return proxyMode != null ? proxyMode : ProxyMode.SYSTEM_MANAGED; }

    /**
     * Sets the proxy mode.
     *
     * @param proxyMode proxy mode
     */
    public void setProxyMode(ProxyMode proxyMode) { this.proxyMode = proxyMode; }

    /**
     * Returns the HTTP proxy value (CUSTOM mode).
     *
     * @return HTTP proxy
     */
    public String getHttpProxy()  { return blankToEmpty(httpProxy); }

    /**
     * Sets the HTTP proxy value.
     *
     * @param httpProxy HTTP proxy
     */
    public void setHttpProxy(String httpProxy) { this.httpProxy = httpProxy; }

    /**
     * Returns the HTTPS proxy value (CUSTOM mode).
     *
     * @return HTTPS proxy
     */
    public String getHttpsProxy() { return blankToEmpty(httpsProxy); }

    /**
     * Sets the HTTPS proxy value.
     *
     * @param httpsProxy HTTPS proxy
     */
    public void setHttpsProxy(String httpsProxy) { this.httpsProxy = httpsProxy; }

    /**
     * Returns the NO_PROXY value (CUSTOM mode).
     *
     * @return NO_PROXY value
     */
    public String getNoProxy()    { return blankToEmpty(noProxy); }

    /**
     * Sets the NO_PROXY value.
     *
     * @param noProxy NO_PROXY value
     */
    public void setNoProxy(String noProxy) { this.noProxy = noProxy; }

    /**
     * Returns an unmodifiable view of the extra env var list.
     * Each element is a two-element {@code String[]} {@code [key, value]}.
     *
     * @return unmodifiable list
     */
    public List<String[]> getExtraEnvVars() {
        return extraEnvVars != null
                ? Collections.unmodifiableList(extraEnvVars)
                : Collections.emptyList();
    }

    /**
     * Replaces the extra env var list.
     *
     * @param extraEnvVars list of {@code [key, value]} arrays; {@code null} clears the list
     */
    public void setExtraEnvVars(List<String[]> extraEnvVars) {
        this.extraEnvVars = extraEnvVars != null ? new ArrayList<>(extraEnvVars) : new ArrayList<>();
    }

    /**
     * Returns an unmodifiable view of the model alias map.
     * Keys are alias names ({@code "sonnet"}, {@code "opus"}, {@code "haiku"});
     * values are model IDs.
     *
     * @return unmodifiable map
     */
    public Map<String, String> getModelAliases() {
        return modelAliases != null ? Collections.unmodifiableMap(modelAliases) : Collections.emptyMap();
    }

    /**
     * Replaces the model alias map.
     *
     * @param modelAliases alias → model ID map; {@code null} clears the map
     */
    public void setModelAliases(Map<String, String> modelAliases) {
        this.modelAliases = modelAliases != null ? new HashMap<>(modelAliases) : new HashMap<>();
    }

    /**
     * Returns an unmodifiable view of the custom model ID list.
     * These are displayed in the model combo and switched via {@code /model <id>}.
     *
     * @return unmodifiable list; never {@code null}
     */
    public List<String> getCustomModels() {
        return customModels != null ? Collections.unmodifiableList(customModels) : List.of();
    }

    /**
     * Replaces the custom model ID list.
     *
     * @param ids list of model IDs; {@code null} clears the list
     */
    public void setCustomModels(List<String> ids) {
        this.customModels = ids != null ? new ArrayList<>(ids) : new ArrayList<>();
    }

    /**
     * Returns the extra CLI arguments string (e.g. {@code "--verbose"}).
     *
     * @return extra CLI args; never {@code null}
     */
    public String getExtraCliArgs() { return extraCliArgs != null ? extraCliArgs : ""; }

    /**
     * Sets the extra CLI arguments string.
     *
     * @param extraCliArgs CLI args; {@code null} is treated as empty
     */
    public void setExtraCliArgs(String extraCliArgs) {
        this.extraCliArgs = extraCliArgs != null ? extraCliArgs : "";
    }

    /**
     * Returns the explicit storage directory for {@code CLAUDE_CONFIG_DIR}.
     * A blank value means the computed path {@code <profilesDir>/<id>} is used.
     *
     * @return explicit storage dir, or {@code ""} if not set
     */
    public String getStorageDir() { return storageDir != null ? storageDir : ""; }

    /**
     * Sets the explicit storage directory for {@code CLAUDE_CONFIG_DIR}.
     * Pass blank or {@code null} to fall back to the computed path.
     *
     * @param storageDir explicit path; {@code null} is treated as empty
     */
    public void setStorageDir(String storageDir) {
        this.storageDir = storageDir != null ? storageDir : "";
    }

    /**
     * Fluent setter for {@link #setStorageDir(String)}.
     *
     * @param storageDir explicit path; {@code null} clears the override
     * @return this profile instance
     */
    public ClaudeProfile withStorageDir(String storageDir) {
        setStorageDir(storageDir);
        return this;
    }

    // -------------------------------------------------------------------------
    // Object
    // -------------------------------------------------------------------------

    @Override
    public String toString() {
        return "ClaudeProfile{id='" + getId() + "', name='" + getName()
                + "', connectionType=" + computeConnectionType()
                + ", proxyMode=" + getProxyMode() + "}";
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String blankToEmpty(String s) {
        return s != null ? s : "";
    }
}
