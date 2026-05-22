package io.github.nbplugins.claudecodegui.settings;

import io.github.nbplugins.claudecodegui.settings.ClaudeProfile.ProxyMode;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;

/**
 * Immutable snapshot of HTTP proxy settings taken from a {@link ClaudeProfile}.
 *
 * <p>Used to configure {@link HttpClient} instances in components that make
 * outbound HTTP requests on behalf of a session (e.g. the OpenAI-compatible proxy servlet).
 */
public final class ProxyConfiguration {

    private final ProxyMode mode;
    private final String    httpProxy;
    private final String    httpsProxy;
    private final String    noProxy;

    public ProxyConfiguration(ProxyMode mode, String httpProxy, String httpsProxy, String noProxy) {
        this.mode       = mode       != null ? mode       : ProxyMode.SYSTEM_MANAGED;
        this.httpProxy  = httpProxy  != null ? httpProxy  : "";
        this.httpsProxy = httpsProxy != null ? httpsProxy : "";
        this.noProxy    = noProxy    != null ? noProxy    : "";
    }

    /** Creates a snapshot from a profile's current proxy settings. */
    public static ProxyConfiguration from(ClaudeProfile profile) {
        return new ProxyConfiguration(
                profile.getProxyMode(),
                profile.getHttpProxy(),
                profile.getHttpsProxy(),
                profile.getNoProxy());
    }

    public ProxyMode getMode()       { return mode; }
    public String    getHttpProxy()  { return httpProxy; }
    public String    getHttpsProxy() { return httpsProxy; }
    public String    getNoProxy()    { return noProxy; }

    /**
     * Configures an {@link HttpClient.Builder} with this proxy configuration.
     * <ul>
     *   <li>{@link ProxyMode#SYSTEM_MANAGED} — uses the JVM default proxy selector</li>
     *   <li>{@link ProxyMode#NO_PROXY} — disables proxying</li>
     *   <li>{@link ProxyMode#CUSTOM} — uses the HTTPS proxy URL, or HTTP proxy as fallback</li>
     * </ul>
     *
     * @param builder the builder to configure (modified in place)
     * @return the same builder, for chaining
     */
    public HttpClient.Builder applyTo(HttpClient.Builder builder) {
        switch (mode) {
            case NO_PROXY ->
                builder.proxy(HttpClient.Builder.NO_PROXY);
            case CUSTOM -> {
                String url = !httpsProxy.isBlank() ? httpsProxy
                           : !httpProxy.isBlank()  ? httpProxy
                           : null;
                if (url != null) {
                    try {
                        URI uri  = URI.create(url);
                        int port = uri.getPort() > 0 ? uri.getPort() : 8080;
                        builder.proxy(ProxySelector.of(new InetSocketAddress(uri.getHost(), port)));
                    } catch (Exception e) {
                        builder.proxy(ProxySelector.getDefault());
                    }
                }
            }
            default -> // SYSTEM_MANAGED
                builder.proxy(ProxySelector.getDefault());
        }
        return builder;
    }
}
