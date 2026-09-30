package io.github.nbplugins.claudecodegui.settings;

import io.github.nbplugins.claudecodegui.settings.ClaudeProfile.ProxyMode;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ProxyConfiguration}.
 */
class ProxyConfigurationTest {

    // -------------------------------------------------------------------------
    // applyTo — CUSTOM proxy must not eagerly resolve the proxy hostname
    // -------------------------------------------------------------------------

    /**
     * Regression: {@code new InetSocketAddress(host, port)} resolves the hostname via
     * a blocking DNS lookup at construction time. If the proxy host cannot be resolved
     * (unreachable DNS, typo, VPN down), this hangs the calling thread indefinitely —
     * observed as {@link io.github.nbplugins.claudecodegui.chatgptauth.ChatGptTokenManager#refresh}
     * hanging forever in {@code ChatGptTokenManagerTest}. The proxy address must be built
     * with {@link InetSocketAddress#createUnresolved} so resolution is deferred to actual
     * connection time (subject to the HTTP client's own connect timeout).
     */
    @Test
    void applyTo_customProxy_doesNotEagerlyResolveHostname() {
        ProxyConfiguration cfg = new ProxyConfiguration(
                ProxyMode.CUSTOM, "", "http://proxy.example.invalid:3128", "");

        HttpClient client = cfg.applyTo(HttpClient.newBuilder()).build();

        assertTrue(client.proxy().isPresent(), "proxy must be configured");
        InetSocketAddress addr = (InetSocketAddress) client.proxy().get()
                .select(URI.create("https://api.openai.com")).get(0).address();

        assertTrue(addr.isUnresolved(),
                "proxy address must be unresolved — eager resolution can hang the calling thread");
        assertEquals("proxy.example.invalid", addr.getHostString());
        assertEquals(3128, addr.getPort());
    }

    /**
     * Regression reproduction of the actual hang: an existing, real hostname whose DNS
     * query hangs (rather than failing fast with NXDOMAIN like a made-up TLD does) must
     * not block {@code applyTo}. This is the exact hostname used in the original hanging
     * test ({@code ChatGptTokenManagerTest.refresh_usesHttpClientConfiguredWithProfileProxySettings}).
     * On the old {@code new InetSocketAddress(host, port)} code this call hangs
     * indefinitely in sandboxes where DNS queries for real-but-unreachable hosts are
     * dropped rather than rejected; {@code assertTimeoutPreemptively} turns that hang
     * into a fast test failure instead of stalling the whole suite.
     */
    @Test
    void applyTo_customProxy_doesNotHangOnSlowOrDroppedDnsQuery() {
        ProxyConfiguration cfg = new ProxyConfiguration(
                ProxyMode.CUSTOM, "", "http://proxy.example.com:3128", "");

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            HttpClient client = cfg.applyTo(HttpClient.newBuilder()).build();
            InetSocketAddress addr = (InetSocketAddress) client.proxy().orElseThrow()
                    .select(URI.create("https://api.openai.com")).get(0).address();
            assertTrue(addr.isUnresolved(), "proxy address must be unresolved");
        });
    }

    @Test
    void applyTo_customProxy_defaultPortWhenNoneSpecified() {
        ProxyConfiguration cfg = new ProxyConfiguration(
                ProxyMode.CUSTOM, "", "http://proxy.example.invalid", "");

        HttpClient client = cfg.applyTo(HttpClient.newBuilder()).build();

        InetSocketAddress addr = (InetSocketAddress) client.proxy().orElseThrow()
                .select(URI.create("https://api.openai.com")).get(0).address();
        assertEquals(8080, addr.getPort());
    }

    @Test
    void applyTo_customProxy_fallsBackToHttpProxyWhenHttpsProxyBlank() {
        ProxyConfiguration cfg = new ProxyConfiguration(
                ProxyMode.CUSTOM, "http://proxy.example.invalid:3129", "", "");

        HttpClient client = cfg.applyTo(HttpClient.newBuilder()).build();

        InetSocketAddress addr = (InetSocketAddress) client.proxy().orElseThrow()
                .select(URI.create("https://api.openai.com")).get(0).address();
        assertEquals("proxy.example.invalid", addr.getHostString());
        assertEquals(3129, addr.getPort());
    }

    @Test
    void applyTo_noProxy_disablesProxying() {
        ProxyConfiguration cfg = new ProxyConfiguration(ProxyMode.NO_PROXY, "", "", "");

        HttpClient client = cfg.applyTo(HttpClient.newBuilder()).build();

        boolean noProxySelected = client.proxy().isEmpty() || client.proxy().get()
                .select(URI.create("https://api.openai.com")).stream()
                .allMatch(p -> p.type() == java.net.Proxy.Type.DIRECT);
        assertTrue(noProxySelected, "NO_PROXY must not select any proxy address");
    }
}
