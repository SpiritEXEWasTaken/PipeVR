package org.schabi.newpipe.extractor.services.twitch;

import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonWriter;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.localization.Localization;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * PipeVR: Twitch's public web API (the GraphQL endpoint and video servers the Twitch website
 * uses). Written from Twitch's public interfaces.
 */
public final class TwitchApi {
    public static final String BASE_URL = "https://www.twitch.tv";
    /** Twitch website's public client id. */
    public static final String CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko";
    private static final String GQL_URL = "https://gql.twitch.tv/gql";
    private static final String USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";

    /** OAuth token of the signed-in Twitch account (phase C), or null. */
    @Nullable
    private static volatile String authToken;

    private TwitchApi() {
    }

    @Nullable
    private static volatile String login;
    @Nullable
    private static volatile String displayName;

    public static void setAuthToken(@Nullable final String token) {
        authToken = token;
    }

    /** The signed-in account (null when signed out). */
    public static void setAccount(@Nullable final String token, @Nullable final String userLogin,
                                  @Nullable final String userDisplayName) {
        authToken = token;
        login = userLogin;
        displayName = userDisplayName;
    }

    @Nullable
    public static String getLogin() {
        return login;
    }

    @Nullable
    public static String getDisplayName() {
        return displayName;
    }

    @Nullable
    public static String getAuthToken() {
        return authToken;
    }

    public static boolean isSignedIn() {
        final String token = authToken;
        return token != null && !token.isEmpty();
    }

    /** PipeVR: where diagnostic lines go (the app's log); null = nowhere. */
    @Nullable
    public static volatile java.util.function.Consumer<String> logger;

    public static void log(@Nonnull final String line) {
        final java.util.function.Consumer<String> l = logger;
        if (l != null) {
            l.accept(line);
        }
    }

    /**
     * PipeVR: Twitch no longer gives apps your follow list ("service error"). The app can read
     * which followed channels are live from twitch.tv itself (signed in, in a web view).
     */
    public interface FollowedLiveProvider {
        /** Logins of followed channels that are live now (empty if none / unknown). */
        @Nonnull
        java.util.List<String> liveLogins();
    }

    @Nullable
    public static volatile FollowedLiveProvider followedLiveProvider;

    /** Errors of the last GraphQL answer that still had data (for messages and logs). */
    @Nullable
    public static volatile String lastErrors;

    /** Runs a GraphQL query and returns its "data" object. */
    @Nonnull
    public static JsonObject gql(@Nonnull final String query,
                                 @Nullable final JsonObject variables)
            throws IOException, ExtractionException {
        final JsonObject body = new JsonObject();
        body.put("query", query);
        if (variables != null) {
            body.put("variables", variables);
        }
        final Map<String, List<String>> headers = new HashMap<>();
        headers.put("Client-Id", Collections.singletonList(CLIENT_ID));
        headers.put("Content-Type", Collections.singletonList("application/json"));
        headers.put("User-Agent", Collections.singletonList(USER_AGENT));
        final String token = authToken;
        if (token != null && !token.isEmpty()) {
            headers.put("Authorization", Collections.singletonList("OAuth " + token));
        }
        final Response response = NewPipe.getDownloader().post(GQL_URL, headers,
                JsonWriter.string(body).getBytes(StandardCharsets.UTF_8), Localization.DEFAULT);
        final JsonObject json;
        try {
            json = JsonParser.object().from(response.responseBody());
        } catch (final Exception e) {
            throw new ParsingException("Could not parse Twitch response", e);
        }
        if (json.has("errors") && !json.has("data")) {
            throw new ExtractionException("Twitch: " + json.getArray("errors"));
        }
        // PipeVR: keep the last errors (partial answers hide them otherwise)
        lastErrors = json.has("errors") ? String.valueOf(json.getArray("errors")) : null;
        final String q = query.length() > 60 ? query.substring(0, 60) : query;
        log("gql HTTP " + response.responseCode() + " signedIn=" + (token != null)
                + " [" + q.replace('\n', ' ') + "]"
                + (lastErrors != null ? " errors=" + lastErrors : ""));
        return json.getObject("data");
    }

    @Nonnull
    public static JsonObject vars(@Nonnull final Object... keyValues) {
        final JsonObject o = new JsonObject();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            o.put((String) keyValues[i], keyValues[i + 1]);
        }
        return o;
    }

    private static String enc(final String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Master HLS playlist of a live channel. */
    @Nonnull
    public static String liveHlsUrl(@Nonnull final String login)
            throws IOException, ExtractionException {
        final JsonObject token = gql("query($login: String!) { streamPlaybackAccessToken("
                + "channelName: $login, params: {platform: \"web\", playerBackend: "
                + "\"mediaplayer\", playerType: \"site\"}) { value signature } }",
                vars("login", login)).getObject("streamPlaybackAccessToken");
        if (token.getString("signature") == null) {
            throw new ExtractionException("This Twitch channel is not live");
        }
        return "https://usher.ttvnw.net/api/channel/hls/" + enc(login) + ".m3u8?sig="
                + enc(token.getString("signature")) + "&token=" + enc(token.getString("value"))
                + "&allow_source=true&allow_audio_only=true&fast_bread=true"
                + "&platform=web&player=twitchweb&supported_codecs=h264&p="
                + ThreadLocalRandom.current().nextInt(1000000);
    }

    /** Master HLS playlist of a past broadcast (VOD). */
    @Nonnull
    public static String vodHlsUrl(@Nonnull final String vodId)
            throws IOException, ExtractionException {
        final JsonObject token = gql("query($id: ID!) { videoPlaybackAccessToken(id: $id, "
                + "params: {platform: \"web\", playerBackend: \"mediaplayer\", "
                + "playerType: \"site\"}) { value signature } }",
                vars("id", vodId)).getObject("videoPlaybackAccessToken");
        if (token.getString("signature") == null) {
            throw new ExtractionException("This Twitch video is not available");
        }
        return "https://usher.ttvnw.net/vod/" + enc(vodId) + ".m3u8?sig="
                + enc(token.getString("signature")) + "&token=" + enc(token.getString("value"))
                + "&allow_source=true&allow_audio_only=true&platform=web&player=twitchweb"
                + "&supported_codecs=h264&p=" + ThreadLocalRandom.current().nextInt(1000000);
    }

    public static String channelUrl(final String login) {
        return BASE_URL + "/" + login + "/videos";
    }

    public static String liveUrl(final String login) {
        return BASE_URL + "/" + login;
    }

    public static String vodUrl(final String id) {
        return BASE_URL + "/videos/" + id;
    }

    // ---- signed-in account (PipeVR phase C) ----

    /** {login, displayName, profileImageURL} of the account a token belongs to. */
    @Nonnull
    public static String[] whoAmI(@Nonnull final String token)
            throws IOException, ExtractionException {
        final String previous = authToken;
        authToken = token;
        try {
            final JsonObject user = gql("query { currentUser { id login displayName "
                    + "profileImageURL(width: 150) } }", null).getObject("currentUser");
            if (user.getString("login") == null) {
                throw new ExtractionException("Twitch did not accept the sign-in");
            }
            return new String[]{user.getString("login"), user.getString("displayName"),
                    user.getString("profileImageURL")};
        } finally {
            authToken = previous;
        }
    }

    private static String userId(final String channelLogin)
            throws IOException, ExtractionException {
        final String id = gql("query($l: String!) { user(login: $l) { id } }",
                vars("l", channelLogin)).getObject("user").getString("id");
        if (id == null) {
            throw new ExtractionException("Unknown Twitch channel " + channelLogin);
        }
        return id;
    }

    /** Whether the signed-in account follows a channel (null if unknown). */
    @Nullable
    public static Boolean isFollowing(@Nonnull final String channelLogin)
            throws IOException, ExtractionException {
        if (!isSignedIn()) {
            return null;
        }
        final JsonObject self = gql("query($l: String!) { user(login: $l) { self { follower "
                + "{ followedAt } } } }", vars("l", channelLogin)).getObject("user")
                .getObject("self");
        if (!self.has("follower")) {
            return null;
        }
        return self.get("follower") != null && !self.getObject("follower").isEmpty();
    }

    /** Follows/unfollows a channel with the signed-in account. */
    public static boolean setFollowing(@Nonnull final String channelLogin, final boolean follow)
            throws IOException, ExtractionException {
        final JsonObject input = vars("targetID", userId(channelLogin));
        if (follow) {
            input.put("disableNotifications", false);
        }
        final JsonObject data = gql(follow
                ? "mutation($input: FollowUserInput!) { followUser(input: $input) { "
                + "follow { followedAt } error { code } } }"
                : "mutation($input: UnfollowUserInput!) { unfollowUser(input: $input) { "
                + "follow { followedAt } } }", vars("input", input));
        final JsonObject result = data.getObject(follow ? "followUser" : "unfollowUser");
        final boolean ok = !result.isEmpty() && result.getObject("error").getString("code") == null;
        if (!ok) {
            // PipeVR: say why (Twitch may demand a browser-only check for these changes)
            throw new ExtractionException("Twitch refused to " + (follow ? "follow" : "unfollow")
                    + (lastErrors != null ? ": " + lastErrors
                    : result.getObject("error").getString("code") != null
                    ? ": " + result.getObject("error").getString("code") : ""));
        }
        return true;
    }

    /** Live channels the signed-in account follows. */
    @Nonnull
    public static com.grack.nanojson.JsonArray followedLive()
            throws IOException, ExtractionException {
        final JsonObject data = gql("query { currentUser { login followedLiveUsers(first: 100) "
                + "{ edges { node { login displayName profileImageURL(width: 70) stream { id "
                + "title viewersCount createdAt previewImageURL(width: 640, height: 360) "
                + "game { name } } } } } } }", null);
        final Object user = data.get("currentUser");
        if (!(user instanceof JsonObject)) {
            // the sign-in wasn't accepted for this request: say so instead of "no videos"
            throw new ExtractionException("Twitch didn't accept your sign-in for the Following "
                    + "list" + (lastErrors != null ? ": " + lastErrors : " (try signing in "
                    + "again)"));
        }
        final com.grack.nanojson.JsonArray live =
                ((JsonObject) user).getObject("followedLiveUsers").getArray("edges");
        log("followedLiveUsers: " + live.size());
        if (!live.isEmpty()) {
            return live;
        }
        // PipeVR: followedLiveUsers can come back empty; read the follow list itself and keep
        // the channels that are live right now
        final com.grack.nanojson.JsonArray out = new com.grack.nanojson.JsonArray();
        final JsonObject follows = gql("query { currentUser { follows(first: 100) { edges { "
                + "node { login displayName profileImageURL(width: 70) stream { id title "
                + "viewersCount createdAt previewImageURL(width: 640, height: 360) "
                + "game { name } } } } } } }", null).getObject("currentUser").getObject("follows");
        final com.grack.nanojson.JsonArray edges = follows.getArray("edges");
        for (int i = 0; i < edges.size(); i++) {
            final JsonObject node = edges.getObject(i).getObject("node");
            final Object stream = node.get("stream");
            if (stream instanceof JsonObject && !((JsonObject) stream).isEmpty()) {
                out.add(edges.getObject(i));
            }
        }
        log("follows: " + edges.size() + ", live now: " + out.size());
        if (!out.isEmpty() || !edges.isEmpty()) {
            return out;
        }
        // Twitch refused both lists: read the live follows from twitch.tv
        final FollowedLiveProvider provider = followedLiveProvider;
        if (provider != null) {
            final java.util.List<String> logins = provider.liveLogins();
            log("followed live from twitch.tv: " + logins);
            return liveUsers(logins);
        }
        if (lastErrors != null) {
            throw new ExtractionException("Twitch didn't give your follows: " + lastErrors);
        }
        return out;
    }

    /** Edges ({node: user}) for those of these channels that are live, in one request. */
    @Nonnull
    private static com.grack.nanojson.JsonArray liveUsers(@Nonnull final java.util.List<String> logins)
            throws IOException, ExtractionException {
        final com.grack.nanojson.JsonArray out = new com.grack.nanojson.JsonArray();
        if (logins.isEmpty()) {
            return out;
        }
        final StringBuilder q = new StringBuilder("query {");
        int n = 0;
        for (final String login : logins) {
            if (!login.matches("[A-Za-z0-9_]{1,30}") || n >= 40) {
                continue;
            }
            q.append(" u").append(n++).append(": user(login: \"").append(login).append("\") { "
                    + "login displayName profileImageURL(width: 70) stream { id title "
                    + "viewersCount createdAt previewImageURL(width: 640, height: 360) "
                    + "game { name } } }");
        }
        q.append(" }");
        final JsonObject data = gql(q.toString(), null);
        for (int i = 0; i < n; i++) {
            final Object user = data.get("u" + i);
            if (user instanceof JsonObject && ((JsonObject) user).get("stream") instanceof JsonObject) {
                final JsonObject edge = new JsonObject();
                edge.put("node", user);
                out.add(edge);
            }
        }
        return out;
    }
}
