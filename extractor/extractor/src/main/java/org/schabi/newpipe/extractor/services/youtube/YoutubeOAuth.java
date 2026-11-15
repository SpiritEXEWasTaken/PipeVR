package org.schabi.newpipe.extractor.services.youtube;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * PipeVR: holds the access token of the signed-in Google account (obtained by the app through
 * the YouTube TV device-code sign-in) and decides which player requests use it.
 *
 * <p>Playback is anonymous by default. Only videos the app explicitly marks with
 * {@link #requestAuthFor(String)} (age-restricted after the user confirmed, members-only) are
 * fetched with the TV client and the user's token.</p>
 */
public final class YoutubeOAuth {
    @Nullable
    private static volatile String accessToken;
    @Nullable
    private static volatile String pageId;
    private static final Set<String> AUTH_VIDEO_IDS = ConcurrentHashMap.newKeySet();
    @Nullable
    private static volatile TokenProvider tokenProvider;

    /** Implemented by the app: returns a valid (refreshed if needed) access token. */
    public interface TokenProvider {
        /**
         * Blocking; called from extractor threads.
         *
         * @return a fresh access token, or null when signed out
         */
        @Nullable
        String getFreshAccessToken();
    }

    public static void setTokenProvider(@Nullable final TokenProvider provider) {
        tokenProvider = provider;
    }

    private YoutubeOAuth() {
    }

    /**
     * @param token  OAuth access token of the active account, or null when signed out
     * @param pageIdToken page id of the selected brand channel, or null for the main channel
     */
    public static void setCredentials(@Nullable final String token,
                                      @Nullable final String pageIdToken) {
        accessToken = token;
        pageId = pageIdToken;
    }

    private static volatile boolean hasAccount;

    /**
     * Called by the app whenever the active account changes.
     *
     * @param signedIn    whether an account is selected (its token is fetched lazily)
     * @param pageIdToken page id of the selected brand channel, or null
     */
    public static void setAccount(final boolean signedIn, @Nullable final String pageIdToken) {
        hasAccount = signedIn;
        pageId = pageIdToken;
        accessToken = null;
    }

    public static boolean isSignedIn() {
        final String token = accessToken;
        return hasAccount || (token != null && !token.isEmpty());
    }

    /** Fetch this video's player response with the signed-in account from now on. */
    public static void requestAuthFor(@Nonnull final String videoId) {
        AUTH_VIDEO_IDS.add(videoId);
    }

    public static boolean shouldUseAuthFor(@Nullable final String videoId) {
        return videoId != null && isSignedIn() && AUTH_VIDEO_IDS.contains(videoId);
    }

    public static void addAuthHeaders(@Nonnull final Map<String, List<String>> headers) {
        final TokenProvider provider = tokenProvider;
        if (provider != null) {
            final String fresh = provider.getFreshAccessToken();
            if (fresh != null) {
                accessToken = fresh;
            }
        }
        final String token = accessToken;
        if (token == null || token.isEmpty()) {
            return;
        }
        headers.put("Authorization", Collections.singletonList("Bearer " + token));
        final String page = pageId;
        if (page != null && !page.isEmpty()) {
            headers.put("X-Goog-Pageid", Collections.singletonList(page));
        }
    }
}
