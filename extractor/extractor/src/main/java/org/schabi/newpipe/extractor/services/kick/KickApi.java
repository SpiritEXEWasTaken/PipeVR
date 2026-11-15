package org.schabi.newpipe.extractor.services.kick;

import com.grack.nanojson.JsonArray;
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

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** PipeVR: Kick's website API (kick.com/api/...), written from Kick's public web interfaces. */
public final class KickApi {
    public static final String BASE_URL = "https://kick.com";
    private static final String USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";

    /** Session of the signed-in Kick account (from the kick.com sign-in page), or null. */
    @Nullable
    private static volatile String sessionToken;
    @Nullable
    private static volatile String xsrfToken;
    @Nullable
    private static volatile String username;

    private KickApi() {
    }

    public static void setAccount(@Nullable final String session, @Nullable final String xsrf,
                                  @Nullable final String user) {
        sessionToken = session;
        xsrfToken = xsrf;
        username = user;
    }

    public static boolean isSignedIn() {
        final String s = sessionToken;
        return s != null && !s.isEmpty();
    }

    @Nullable
    public static String getUsername() {
        return username;
    }

    private static Map<String, List<String>> headers(final boolean json) {
        final Map<String, List<String>> h = new HashMap<>();
        h.put("User-Agent", Collections.singletonList(USER_AGENT));
        h.put("Accept", Collections.singletonList("application/json"));
        if (json) {
            h.put("Content-Type", Collections.singletonList("application/json"));
        }
        final String s = sessionToken;
        if (s != null && !s.isEmpty()) {
            h.put("Authorization", Collections.singletonList("Bearer " + s));
            h.put("Cookie", Collections.singletonList("session_token=" + s
                    + (xsrfToken != null ? "; XSRF-TOKEN=" + xsrfToken : "")));
            if (xsrfToken != null) {
                h.put("X-XSRF-TOKEN", Collections.singletonList(xsrfToken));
            }
        }
        return h;
    }

    private static Object parse(final Response response) throws ExtractionException {
        if (response.responseCode() == 404) {
            throw new org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException(
                    "Not found on Kick");
        }
        try {
            return JsonParser.any().from(response.responseBody());
        } catch (final Exception e) {
            throw new ParsingException("Kick returned an unexpected response (HTTP "
                    + response.responseCode() + ")", e);
        }
    }

    @Nonnull
    public static JsonObject getObject(@Nonnull final String path)
            throws IOException, ExtractionException {
        final Object o = parse(NewPipe.getDownloader().get(BASE_URL + path, headers(false)));
        return o instanceof JsonObject ? (JsonObject) o : new JsonObject();
    }

    @Nonnull
    public static JsonArray getArray(@Nonnull final String path)
            throws IOException, ExtractionException {
        final Object o = parse(NewPipe.getDownloader().get(BASE_URL + path, headers(false)));
        return o instanceof JsonArray ? (JsonArray) o : new JsonArray();
    }

    /** POST (or DELETE) with the signed-in session. Returns the HTTP status code. */
    public static int send(@Nonnull final String method, @Nonnull final String path,
                           @Nullable final JsonObject body) throws IOException, ExtractionException {
        final byte[] data = body == null ? new byte[0]
                : JsonWriter.string(body).getBytes(StandardCharsets.UTF_8);
        final Response r;
        if ("DELETE".equals(method)) {
            r = NewPipe.getDownloader().execute(org.schabi.newpipe.extractor.downloader.Request
                    .newBuilder().httpMethod("DELETE").url(BASE_URL + path)
                    .headers(headers(true)).dataToSend(data).build());
        } else {
            r = NewPipe.getDownloader().post(BASE_URL + path, headers(true), data,
                    Localization.DEFAULT);
        }
        return r.responseCode();
    }

    public static String enc(final String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Biggest thumbnail of a Kick thumbnail object or url. */
    @Nullable
    public static String thumbnail(@Nullable final Object thumb) {
        if (thumb instanceof JsonObject) {
            final JsonObject t = (JsonObject) thumb;
            return t.getString("src", t.getString("url"));
        }
        return thumb instanceof String ? (String) thumb : null;
    }

    public static String liveUrl(final String slug) {
        return BASE_URL + "/" + slug;
    }

    public static String channelUrl(final String slug) {
        return BASE_URL + "/" + slug + "/videos";
    }

    public static String vodUrl(final String uuid) {
        return BASE_URL + "/video/" + uuid;
    }

    // ---- signed-in account ----

    /** Username of the account a session belongs to (blocking). */
    @Nonnull
    public static String whoAmI(@Nonnull final String session, @Nullable final String xsrf)
            throws IOException, ExtractionException {
        final String oldS = sessionToken;
        final String oldX = xsrfToken;
        sessionToken = session;
        xsrfToken = xsrf;
        try {
            final JsonObject me = getObject("/api/v1/user");
            final String name = me.getString("username");
            if (name == null) {
                throw new ExtractionException("Kick did not accept the sign-in");
            }
            return name;
        } finally {
            sessionToken = oldS;
            xsrfToken = oldX;
        }
    }

    @Nullable
    public static Boolean isFollowing(@Nonnull final String slug)
            throws IOException, ExtractionException {
        if (!isSignedIn()) {
            return null;
        }
        final JsonObject me = getObject("/api/v2/channels/" + enc(slug) + "/me");
        return me.has("is_following") ? me.getBoolean("is_following") : null;
    }

    public static boolean setFollowing(@Nonnull final String slug, final boolean follow)
            throws IOException, ExtractionException {
        final int code = send(follow ? "POST" : "DELETE",
                "/api/v2/channels/" + enc(slug) + "/follow", null);
        return code >= 200 && code < 300;
    }
}
