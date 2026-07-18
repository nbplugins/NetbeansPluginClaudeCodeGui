package io.github.nbplugins.claudecodegui.chatgptauth;

import io.github.nbplugins.claudecodegui.chatgptauth.ChatGptOAuthClient.TokenSet;
import io.github.nbplugins.claudecodegui.settings.ClaudeProfile;
import io.github.nbplugins.claudecodegui.settings.ClaudeProfileStore;
import io.github.nbplugins.claudecodegui.settings.ProxyConfiguration;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Keeps a profile's ChatGPT access token fresh.
 *
 * <p>Refreshes the access token shortly before it expires, using the
 * profile's own {@link ProxyConfiguration} for the refresh HTTP call (the
 * same network conditions the rest of that profile's traffic uses).
 * Concurrent callers for the same profile share a single in-flight refresh
 * (single-flight via {@link ConcurrentHashMap}), so a burst of proxy
 * requests near expiry never triggers a refresh stampede.
 *
 * <p>On successful refresh the new token set is persisted back onto the
 * profile via {@link ClaudeProfileStore} (OpenAI may rotate the refresh
 * token, so both access and refresh tokens are overwritten).
 */
public final class ChatGptTokenManager {

    private static final Logger LOG = Logger.getLogger(ChatGptTokenManager.class.getName());

    /** Refresh when the access token is within this long of expiring. */
    static final Duration REFRESH_SKEW = Duration.ofMinutes(5);

    /**
     * Performs the actual refresh-token HTTP exchange. Implemented by
     * {@link ChatGptOAuthClient#refresh} in production; replaced by a fake in
     * tests so no real network call is made.
     */
    @FunctionalInterface
    interface TokenRefresher {
        TokenSet refresh(HttpClient client, String refreshToken) throws OAuthException;
    }

    private final Clock clock;
    private final TokenRefresher refresher;
    private final ConcurrentHashMap<String, CompletableFuture<TokenSet>> inFlight = new ConcurrentHashMap<>();

    /** Creates a manager using the system clock and a new {@link ChatGptOAuthClient}. */
    public ChatGptTokenManager() {
        this(Clock.systemUTC(), new ChatGptOAuthClient()::refresh);
    }

    /**
     * Creates a manager with injectable dependencies, for testing.
     *
     * @param clock     clock used to evaluate token expiry
     * @param refresher performs the refresh HTTP exchange
     */
    ChatGptTokenManager(Clock clock, TokenRefresher refresher) {
        this.clock = clock;
        this.refresher = refresher;
    }

    /**
     * Returns a valid access token for {@code profile}, refreshing first if
     * the current token is within {@link #REFRESH_SKEW} of expiry (or has no
     * recorded expiry).
     *
     * @param profile the profile to read/refresh tokens for
     * @return a valid access token
     * @throws OAuthException if the profile is not signed in, or refresh fails
     */
    public String getValidAccessToken(ClaudeProfile profile) throws OAuthException {
        if (!profile.isSignedIntoChatgpt()) {
            throw new OAuthException("Not signed in to ChatGPT — sign in in Profile settings.");
        }
        if (!needsRefresh(profile)) {
            return profile.getChatgptAccessToken();
        }
        return refresh(profile).accessToken();
    }

    /**
     * Forces a refresh regardless of the current token's expiry.
     *
     * @param profile the profile to refresh
     * @return the refreshed token set
     * @throws OAuthException if the refresh HTTP call fails
     */
    public TokenSet refresh(ClaudeProfile profile) throws OAuthException {
        String profileId = profile.getId();
        CompletableFuture<TokenSet> future = inFlight.computeIfAbsent(profileId,
                id -> CompletableFuture.supplyAsync(() -> doRefresh(profile)));
        try {
            return future.join();
        } catch (java.util.concurrent.CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof OAuthUncheckedException oue) {
                throw oue.checked();
            }
            throw new OAuthException("Refresh failed: " + e.getMessage(), e);
        } finally {
            inFlight.remove(profileId, future);
        }
    }

    private TokenSet doRefresh(ClaudeProfile profile) {
        try {
            HttpClient httpClient = ProxyConfiguration.from(profile).applyTo(
                    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30))).build();
            TokenSet refreshed = refresher.refresh(httpClient, profile.getChatgptRefreshToken());
            persist(profile, refreshed);
            LOG.fine(() -> "ChatGPT token refreshed for profile " + profile.getName());
            return refreshed;
        } catch (OAuthException e) {
            throw new OAuthUncheckedException(e);
        }
    }

    private void persist(ClaudeProfile profile, TokenSet tokenSet) {
        profile.setChatgptAccessToken(tokenSet.accessToken());
        if (!tokenSet.refreshToken().isBlank()) {
            profile.setChatgptRefreshToken(tokenSet.refreshToken());
        }
        if (!tokenSet.accountId().isBlank()) {
            profile.setChatgptAccountId(tokenSet.accountId());
        }
        if (!tokenSet.email().isBlank()) {
            profile.setChatgptEmail(tokenSet.email());
        }
        profile.setChatgptTokenExpiresAt(tokenSet.expiresAt().toString());

        if (profile.isDefault()) {
            java.util.List<ClaudeProfile> all = ClaudeProfileStore.getProfiles();
            ClaudeProfileStore.saveProfiles(all);
        } else {
            ClaudeProfileStore.saveProfile(profile);
        }
    }

    private boolean needsRefresh(ClaudeProfile profile) {
        String expiresAt = profile.getChatgptTokenExpiresAt();
        if (expiresAt.isBlank()) return true;
        try {
            Instant expiry = Instant.parse(expiresAt);
            return Instant.now(clock).plus(REFRESH_SKEW).isAfter(expiry);
        } catch (Exception e) {
            return true;
        }
    }

    /** Unchecked wrapper so {@link OAuthException} can cross a {@link CompletableFuture} boundary. */
    private static final class OAuthUncheckedException extends RuntimeException {
        private final OAuthException checked;

        OAuthUncheckedException(OAuthException checked) {
            super(checked.getMessage(), checked);
            this.checked = checked;
        }

        OAuthException checked() { return checked; }
    }
}
