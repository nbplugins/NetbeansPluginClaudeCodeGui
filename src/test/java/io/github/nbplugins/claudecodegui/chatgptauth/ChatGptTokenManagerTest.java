package io.github.nbplugins.claudecodegui.chatgptauth;

import io.github.nbplugins.claudecodegui.chatgptauth.ChatGptOAuthClient.TokenSet;
import io.github.nbplugins.claudecodegui.settings.ClaudeProfile;
import io.github.nbplugins.claudecodegui.settings.ClaudeProfileStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ChatGptTokenManager}.
 *
 * <p>Uses the package-private {@link ChatGptTokenManager.TokenRefresher}
 * injection point so no real network call is made — the fake refresher runs
 * entirely in-process.
 *
 * <p><b>Note:</b> {@link ClaudeProfileStore#clearProfiles()} is called in
 * {@link #setUp()} per the same pattern as {@code ClaudeProfileStoreTest},
 * since successful refreshes persist via {@link ClaudeProfileStore}.
 */
class ChatGptTokenManagerTest {

    @BeforeEach
    void setUp() {
        try {
            ClaudeProfileStore.clearProfiles();
        } catch (Exception ignored) {
            // NbPreferences platform not available in plain unit-test environment
        }
    }

    private static ClaudeProfile signedInProfile(Instant expiresAt) {
        ClaudeProfile p = ClaudeProfile.createNamed("chatgpt-test-" + System.nanoTime());
        p.setOpenaiSubscription(true);
        p.setChatgptAccessToken("access-old");
        p.setChatgptRefreshToken("refresh-old");
        p.setChatgptAccountId("acct-1");
        p.setChatgptTokenExpiresAt(expiresAt.toString());
        return p;
    }

    private static Clock fixedClock(Instant now) {
        return Clock.fixed(now, ZoneOffset.UTC);
    }

    // -------------------------------------------------------------------------
    // getValidAccessToken — no refresh when fresh
    // -------------------------------------------------------------------------

    @Test
    void getValidAccessToken_notNearExpiry_returnsCachedTokenWithoutRefreshing() throws Exception {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        ClaudeProfile profile = signedInProfile(now.plusSeconds(3600)); // 1h out, well outside 5m skew

        AtomicInteger refreshCalls = new AtomicInteger();
        ChatGptTokenManager manager = new ChatGptTokenManager(fixedClock(now), (client, refreshToken) -> {
            refreshCalls.incrementAndGet();
            throw new OAuthException("should not be called");
        });

        String token = manager.getValidAccessToken(profile);

        assertEquals("access-old", token);
        assertEquals(0, refreshCalls.get());
    }

    // -------------------------------------------------------------------------
    // getValidAccessToken — refresh when near expiry
    // -------------------------------------------------------------------------

    @Test
    void getValidAccessToken_withinSkewOfExpiry_refreshesAndPersists() throws Exception {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        ClaudeProfile profile = signedInProfile(now.plus(java.time.Duration.ofMinutes(2))); // within 5m skew

        TokenSet refreshed = new TokenSet("access-new", "refresh-new", "acct-1", "user@example.com",
                now.plusSeconds(3600));
        AtomicInteger refreshCalls = new AtomicInteger();
        ChatGptTokenManager manager = new ChatGptTokenManager(fixedClock(now), (client, refreshToken) -> {
            assertEquals("refresh-old", refreshToken);
            refreshCalls.incrementAndGet();
            return refreshed;
        });

        String token = manager.getValidAccessToken(profile);

        assertEquals("access-new", token);
        assertEquals(1, refreshCalls.get());
        assertEquals("access-new", profile.getChatgptAccessToken());
        assertEquals("refresh-new", profile.getChatgptRefreshToken());
    }

    @Test
    void getValidAccessToken_noRecordedExpiry_refreshes() throws Exception {
        ClaudeProfile profile = ClaudeProfile.createNamed("chatgpt-test-noexp-" + System.nanoTime());
        profile.setOpenaiSubscription(true);
        profile.setChatgptAccessToken("access-old");
        profile.setChatgptRefreshToken("refresh-old");
        // chatgptTokenExpiresAt left blank

        TokenSet refreshed = new TokenSet("access-new", "refresh-new", "acct-1", "",
                Instant.now().plusSeconds(3600));
        AtomicInteger refreshCalls = new AtomicInteger();
        ChatGptTokenManager manager = new ChatGptTokenManager(Clock.systemUTC(), (client, refreshToken) -> {
            refreshCalls.incrementAndGet();
            return refreshed;
        });

        manager.getValidAccessToken(profile);
        assertEquals(1, refreshCalls.get());
    }

    // -------------------------------------------------------------------------
    // getValidAccessToken — not signed in
    // -------------------------------------------------------------------------

    @Test
    void getValidAccessToken_notSignedIn_throwsOAuthException() {
        ClaudeProfile profile = ClaudeProfile.createNamed("chatgpt-test-nosignin-" + System.nanoTime());
        ChatGptTokenManager manager = new ChatGptTokenManager(Clock.systemUTC(),
                (client, refreshToken) -> { throw new OAuthException("unexpected"); });

        assertThrows(OAuthException.class, () -> manager.getValidAccessToken(profile));
    }

    // -------------------------------------------------------------------------
    // Single-flight under concurrency
    // -------------------------------------------------------------------------

    @Test
    void refresh_concurrentCallsForSameProfile_onlyOneHttpCallMade() throws Exception {
        Instant now = Instant.now();
        ClaudeProfile profile = signedInProfile(now.minusSeconds(1)); // already expired

        AtomicInteger refreshCalls = new AtomicInteger();
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        TokenSet refreshed = new TokenSet("access-new", "refresh-new", "acct-1", "", now.plusSeconds(3600));

        ChatGptTokenManager manager = new ChatGptTokenManager(Clock.systemUTC(), (client, refreshToken) -> {
            refreshCalls.incrementAndGet();
            try {
                release.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return refreshed;
        });

        int threadCount = 5;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
        java.util.List<java.util.concurrent.Future<String>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(pool.submit(() -> manager.getValidAccessToken(profile)));
        }
        Thread.sleep(100); // let all threads reach the in-flight refresh
        release.countDown();

        for (java.util.concurrent.Future<String> f : futures) {
            assertEquals("access-new", f.get());
        }
        pool.shutdown();

        assertEquals(1, refreshCalls.get(), "only one HTTP refresh call should be made for concurrent callers");
    }

    // -------------------------------------------------------------------------
    // Refresh HTTP call uses the profile's proxy settings
    // -------------------------------------------------------------------------

    @Test
    void refresh_usesHttpClientConfiguredWithProfileProxySettings() throws Exception {
        Instant now = Instant.now();
        ClaudeProfile profile = signedInProfile(now.minusSeconds(1));
        profile.setProxyMode(ClaudeProfile.ProxyMode.CUSTOM);
        profile.setHttpsProxy("http://proxy.example.com:3128");

        java.util.concurrent.atomic.AtomicReference<java.net.http.HttpClient> capturedClient =
                new java.util.concurrent.atomic.AtomicReference<>();
        TokenSet refreshed = new TokenSet("access-new", "refresh-new", "acct-1", "", now.plusSeconds(3600));
        ChatGptTokenManager manager = new ChatGptTokenManager(Clock.systemUTC(), (client, refreshToken) -> {
            capturedClient.set(client);
            return refreshed;
        });

        manager.getValidAccessToken(profile);

        assertNotNull(capturedClient.get());
        assertTrue(capturedClient.get().proxy().isPresent(),
                "HttpClient used for refresh must apply the profile's CUSTOM proxy settings");
    }

    // -------------------------------------------------------------------------
    // Refresh failure
    // -------------------------------------------------------------------------

    @Test
    void refresh_httpFailure_throwsOAuthExceptionAndLeavesCachedTokenUnchanged() {
        Instant now = Instant.now();
        ClaudeProfile profile = signedInProfile(now.minusSeconds(1));

        ChatGptTokenManager manager = new ChatGptTokenManager(Clock.systemUTC(),
                (client, refreshToken) -> { throw new OAuthException("refresh token revoked"); });

        assertThrows(OAuthException.class, () -> manager.getValidAccessToken(profile));
        assertEquals("access-old", profile.getChatgptAccessToken());
    }
}
