package org.schabi.newpipe.extractor.services.youtube;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonBuilder;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonWriter;

import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.utils.JsonUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static org.schabi.newpipe.extractor.NewPipe.getDownloader;

/**
 * PipeVR: signed-in feeds of the YouTube TV app (TVHTML5 client + OAuth), as SmartTube does it.
 */
public final class YoutubeTvAccountFeeds {
    public static final String TV_CLIENT_NAME = "TVHTML5";
    public static final String TV_CLIENT_VERSION = "7.20260901.15.00";
    public static final String TV_USER_AGENT = "Mozilla/5.0 (Linux armeabi-v7a; Android 7.1.2; "
            + "Fire OS 6.0) Cobalt/22.lts.3.306369-gold (unlike Gecko) v8/8.8.278.8-jit gles "
            + "Starboard/13, Amazon_ATV_mediatek8695_2019/NS6294 (Amazon, AFTMM, Wireless) "
            + "com.amazon.firetv.youtube/22.3.r2.v66.0";

    /** Kiosk ids (also shown as names) and the browse request behind each of them. */
    public static final String HOME = "Home";
    public static final String SUBSCRIPTIONS = "Subscriptions";
    public static final String HISTORY = "History";
    public static final String WATCH_LATER = "Watch later";
    public static final String LIKED = "Liked videos";
    public static final String[] KIOSKS = {HOME, SUBSCRIPTIONS, HISTORY, WATCH_LATER, LIKED};

    /**
     * Resume positions YouTube reported for the signed-in account (from TV cards):
     * videoId -> seconds to resume at, 0 when the video was watched to the end.
     */
    public static final Map<String, Integer> RESUME_SECONDS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Signed-in comments: commentId -> {like action, remove-like action} (perform_comment_action). */
    public static final Map<String, String[]> COMMENT_LIKE_ACTIONS =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** Signed-in comments: commentId -> whether the account liked it. */
    public static final Map<String, Boolean> COMMENT_LIKED =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** Signed-in comments: reply commentId -> the commentId of the comment it replies to. */
    public static final Map<String, String> COMMENT_PARENT =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** Signed-in comments: commentId -> continuation of its detail view (actions + replies). */
    public static final Map<String, String> COMMENT_DETAIL_TOKENS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Remembers the like state/actions and the detail token of a TV commentRenderer. */
    public static void recordComment(@Nonnull final JsonObject commentRenderer) {
        final String id = commentRenderer.getString("commentId");
        if (id == null) {
            return;
        }
        if (commentRenderer.has("isLiked")) {
            COMMENT_LIKED.put(id, commentRenderer.getBoolean("isLiked"));
        }
        final JsonObject like = commentRenderer.getObject("actionButtons")
                .getObject("commentActionButtonsRenderer").getObject("likeButton")
                .getObject("toggleButtonRenderer");
        final String likeAction = like.getObject("defaultServiceEndpoint")
                .getObject("performCommentActionEndpoint").getString("action");
        final String unlikeAction = like.getObject("toggledServiceEndpoint")
                .getObject("performCommentActionEndpoint").getString("action");
        if (likeAction != null && unlikeAction != null) {
            COMMENT_LIKE_ACTIONS.put(id, new String[]{likeAction, unlikeAction});
            if (like.has("isToggled")) {
                COMMENT_LIKED.put(id, like.getBoolean("isToggled"));
            }
        }
        final String detail = findContinuation(commentRenderer.getObject("detailViewEndpoint"));
        if (detail != null) {
            COMMENT_DETAIL_TOKENS.put(id, detail);
        }
    }

    @Nullable
    private static String findContinuation(@Nullable final Object node) {
        if (node instanceof JsonObject) {
            final JsonObject o = (JsonObject) node;
            final String token = o.getObject("reloadContinuationData").getString("continuation");
            if (token != null) {
                return token;
            }
            for (final Object v : o.values()) {
                final String t = findContinuation(v);
                if (t != null) {
                    return t;
                }
            }
        } else if (node instanceof JsonArray) {
            for (final Object v : (JsonArray) node) {
                final String t = findContinuation(v);
                if (t != null) {
                    return t;
                }
            }
        }
        return null;
    }

    /** videoId -> playbackTracking object of its last player response (for watch history). */
    public static final Map<String, JsonObject> PLAYBACK_TRACKING =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<String, JsonObject>() {
                @Override
                protected boolean removeEldestEntry(final Map.Entry<String, JsonObject> eldest) {
                    return size() > 50;
                }
            });

    private YoutubeTvAccountFeeds() {
    }

    /**
     * Fetches the account's watch history (first page) and records the resume positions of
     * its videos into {@link #RESUME_SECONDS}.
     */
    public static void refreshResumePositions(@Nonnull final Localization localization)
            throws IOException, ExtractionException {
        final JsonObject history = post("browse", b -> b.value("browseId", "FEhistory"),
                localization);
        for (final JsonObject wrapped : collect(history, false).renderers) {
            // constructing the item records its resume position
            new org.schabi.newpipe.extractor.services.youtube.extractors
                    .YoutubeTvTileInfoItemExtractor(wrapped, null);
        }
    }

    @Nonnull
    public static String browseIdOf(@Nonnull final String kioskId) {
        switch (kioskId) {
            case SUBSCRIPTIONS: return "FEsubscriptions";
            case HISTORY: return "FEhistory";
            case WATCH_LATER: return "VLWL";
            case LIKED: return "VLLL";
            case HOME:
            default:
                return "default";
        }
    }

    /** POST youtubei/v1/{endpoint} with the TV client context and the account's token. */
    @Nonnull
    public static JsonObject post(@Nonnull final String endpoint,
                                  @Nonnull final Consumer<JsonBuilder<JsonObject>> payload,
                                  @Nonnull final Localization localization)
            throws IOException, ExtractionException {
        final JsonBuilder<JsonObject> builder = JsonObject.builder()
                .object("context")
                    .object("client")
                        .value("clientName", TV_CLIENT_NAME)
                        .value("clientVersion", TV_CLIENT_VERSION)
                        .value("clientScreen", "WATCH")
                        .value("userAgent", TV_USER_AGENT)
                        .value("browserName", "Cobalt")
                        .value("browserVersion", "22.lts.3.306369-gold")
                        .object("tvAppInfo")
                            .value("appQuality", "TV_APP_QUALITY_FULL_ANIMATION")
                            .value("zylonLeftNav", true)
                        .end()
                        .value("hl", localization.getLanguageCode())
                        .value("gl", localization.getCountryCode().isEmpty()
                                ? "US" : localization.getCountryCode())
                        .value("utcOffsetMinutes", 0)
                    .end()
                    .object("user")
                        .value("enableSafetyMode", false)
                        .value("lockedSafetyMode", false)
                    .end()
                .end()
                .value("racyCheckOk", true)
                .value("contentCheckOk", true);
        payload.accept(builder);
        final byte[] body = JsonWriter.string(builder.done()).getBytes(StandardCharsets.UTF_8);

        final Map<String, List<String>> headers = new HashMap<>();
        headers.put("Content-Type", Collections.singletonList("application/json"));
        headers.put("User-Agent", Collections.singletonList(TV_USER_AGENT));
        headers.put("Referer", Collections.singletonList("https://www.youtube.com/tv"));
        headers.put("X-YouTube-Client-Name", Collections.singletonList("7"));
        headers.put("X-YouTube-Client-Version", Collections.singletonList(TV_CLIENT_VERSION));
        YoutubeOAuth.addAuthHeaders(headers);
        if (!headers.containsKey("Authorization")) {
            throw new ExtractionException("Sign in to your YouTube account first");
        }

        final Response response = getDownloader().post(YoutubeParsingHelper.YOUTUBEI_V1_URL
                + endpoint + "?prettyPrint=false", headers, body, localization);
        if (response.responseCode() == 401 || response.responseCode() == 403) {
            throw new ExtractionException("YouTube rejected the sign-in (HTTP "
                    + response.responseCode() + "). Try signing in again.");
        }
        return JsonUtils.toJsonObject(YoutubeParsingHelper.getValidJsonResponseBody(response));
    }

    /**
     * PipeVR: POST youtubei/v1/{endpoint} as the website ("WEB") or mobile website ("MWEB")
     * client with the account's token, for actions the TV app doesn't have (live chat).
     */
    @Nonnull
    public static JsonObject postAs(@Nonnull final String clientName,
                                    @Nonnull final String clientVersion,
                                    @Nonnull final String endpoint,
                                    @Nonnull final Consumer<JsonBuilder<JsonObject>> payload,
                                    @Nonnull final Localization localization)
            throws IOException, ExtractionException {
        final boolean mobile = clientName.startsWith("M");
        final String ua = mobile
                ? "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Chrome/130.0.0.0 Mobile Safari/537.36"
                : "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Chrome/130.0.0.0 Safari/537.36";
        final JsonBuilder<JsonObject> builder = JsonObject.builder()
                .object("context")
                    .object("client")
                        .value("clientName", clientName)
                        .value("clientVersion", clientVersion)
                        .value("userAgent", ua)
                        .value("hl", localization.getLanguageCode())
                        .value("gl", localization.getCountryCode().isEmpty()
                                ? "US" : localization.getCountryCode())
                        .value("utcOffsetMinutes", 0)
                    .end()
                .end();
        payload.accept(builder);
        final byte[] body = JsonWriter.string(builder.done()).getBytes(StandardCharsets.UTF_8);
        final Map<String, List<String>> headers = new HashMap<>();
        headers.put("Content-Type", Collections.singletonList("application/json"));
        headers.put("User-Agent", Collections.singletonList(ua));
        final String origin = mobile ? "https://m.youtube.com" : "https://www.youtube.com";
        headers.put("Origin", Collections.singletonList(origin));
        headers.put("Referer", Collections.singletonList(origin + "/"));
        headers.put("X-YouTube-Client-Name", Collections.singletonList(mobile ? "2" : "1"));
        headers.put("X-YouTube-Client-Version", Collections.singletonList(clientVersion));
        YoutubeOAuth.addAuthHeaders(headers);
        if (!headers.containsKey("Authorization")) {
            throw new ExtractionException("Sign in to your YouTube account first");
        }
        final Response response = getDownloader().post(YoutubeParsingHelper.YOUTUBEI_V1_URL
                + endpoint + "?prettyPrint=false", headers, body, localization);
        try {
            return JsonUtils.toJsonObject(response.responseBody());
        } catch (final Exception e) {
            throw new ExtractionException("HTTP " + response.responseCode() + " from " + endpoint);
        }
    }

    /** Result of walking a TV browse response: the video renderers and the continuation. */
    public static final class Items {
        /** PipeVR: leave out Shorts and news/politics rows (Home, Subscriptions, related). */
        boolean feedFilter;
        public final List<JsonObject> renderers = new ArrayList<>();
        @Nullable
        public String continuation;
    }

    /**
     * Collects every tile/video renderer found in a TV browse or continuation response, in order.
     * The structure varies between feeds (shelves, grids, playlists, secondary nav tabs), so this
     * walks the JSON tree instead of following fixed paths.
     */
    @Nonnull
    public static Items collect(@Nonnull final JsonObject response, final boolean firstShelfOnly) {
        return collect(response, firstShelfOnly, false);
    }

    /**
     * PipeVR: like {@link #collect(JsonObject, boolean)}; with {@code feedFilter}, Shorts (they
     * have their own mode) and rows of news/politics/trending are left out, so the feed is the
     * account's own recommendations.
     */
    @Nonnull
    public static Items collect(@Nonnull final JsonObject response, final boolean firstShelfOnly,
                                final boolean feedFilter) {
        final Items items = new Items();
        items.feedFilter = feedFilter;
        walk(response, items, firstShelfOnly, new boolean[]{false});
        return items;
    }

    private static void walk(@Nullable final Object node, @Nonnull final Items items,
                             final boolean firstShelfOnly, @Nonnull final boolean[] shelfDone) {
        if (node instanceof JsonArray) {
            for (final Object child : (JsonArray) node) {
                walk(child, items, firstShelfOnly, shelfDone);
            }
            return;
        }
        if (!(node instanceof JsonObject)) {
            return;
        }
        final JsonObject object = (JsonObject) node;
        for (final String key : new ArrayList<>(object.keySet())) {
            final Object value = object.get(key);
            switch (key) {
                case "tileRenderer":
                case "videoRenderer":
                case "gridVideoRenderer":
                case "playlistVideoRenderer":
                case "compactVideoRenderer":
                    if (!(firstShelfOnly && shelfDone[0]) && value instanceof JsonObject
                            && !(items.feedFilter && isShort((JsonObject) value))) {
                        items.renderers.add(wrap(key, (JsonObject) value));
                    }
                    break;
                case "promoShelfRenderer":
                case "tvSecondaryNavRenderer":
                    // ads/promos; the subscriptions nav (channel list) is walked separately below
                    if ("tvSecondaryNavRenderer".equals(key)) {
                        walkSubscriptionsNav((JsonObject) value, items, firstShelfOnly, shelfDone);
                    }
                    break;
                case "nextContinuationData":
                case "reloadContinuationData":
                    if (items.continuation == null && value instanceof JsonObject) {
                        items.continuation = ((JsonObject) value).getString("continuation");
                    }
                    break;
                case "continuationCommand":
                    if (items.continuation == null && value instanceof JsonObject) {
                        items.continuation = ((JsonObject) value).getString("token");
                    }
                    break;
                default:
                    if (items.feedFilter && value instanceof JsonObject
                            && key.toLowerCase().endsWith("shelfrenderer")
                            && (key.startsWith("reel") || skipShelf((JsonObject) value))) {
                        break;  // a Shorts row, or news/politics/trending
                    }
                    walk(value, items, firstShelfOnly, shelfDone);
                    if ("shelfRenderer".equals(key) && !items.renderers.isEmpty()) {
                        shelfDone[0] = true;
                    }
                    break;
            }
        }
    }

    /** Only the "All" tab (first tab) of the subscriptions page holds the video feed. */
    private static void walkSubscriptionsNav(@Nonnull final JsonObject nav,
                                             @Nonnull final Items items,
                                             final boolean firstShelfOnly,
                                             @Nonnull final boolean[] shelfDone) {
        final JsonArray sections = nav.getArray("sections");
        if (sections.isEmpty()) {
            return;
        }
        final JsonArray tabs = sections.getObject(0).getObject("tvSecondaryNavSectionRenderer")
                .getArray("tabs");
        if (tabs.isEmpty()) {
            return;
        }
        final JsonObject first = tabs.getObject(0);
        final JsonObject tab = first.has("tabRenderer") ? first.getObject("tabRenderer")
                : first.getObject("expandableTabRenderer");
        walk(tab.getObject("content"), items, firstShelfOnly, shelfDone);
    }

    @Nonnull
    private static final java.util.regex.Pattern SKIP_ROW = java.util.regex.Pattern.compile(
            "\\b(shorts|news|breaking|politic\\w*|trending|election\\w*)\\b");

    /** A video that opens in YouTube's Shorts player. */
    private static boolean isShort(@Nonnull final JsonObject renderer) {
        return com.grack.nanojson.JsonWriter.string(renderer).contains("reelWatchEndpoint");
    }

    /** A row whose title says Shorts, news, politics or trending. */
    private static boolean skipShelf(@Nonnull final JsonObject shelf) {
        final StringBuilder title = new StringBuilder();
        for (final String k : new String[]{"headerRenderer", "header", "title"}) {
            final Object o = shelf.get(k);
            if (o instanceof JsonObject) {
                title.append(com.grack.nanojson.JsonWriter.string(o)).append(' ');
            }
        }
        // only the visible text, not ids or urls
        final java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"(?:simpleText|text)\":\"([^\"]*)\"").matcher(title);
        final StringBuilder visible = new StringBuilder();
        while (m.find()) {
            visible.append(m.group(1)).append(' ');
        }
        return SKIP_ROW.matcher(visible.toString().toLowerCase()).find();
    }

    private static JsonObject wrap(@Nonnull final String type, @Nonnull final JsonObject renderer) {
        final JsonObject wrapper = new JsonObject();
        wrapper.put("type", type);
        wrapper.put("renderer", renderer);
        return wrapper;
    }

    @Nullable
    public static String text(@Nullable final JsonObject textObject) {
        if (textObject == null || textObject.isEmpty()) {
            return null;
        }
        try {
            return YoutubeParsingHelper.getTextFromObject(textObject);
        } catch (final ParsingException e) {
            return null;
        }
    }
}
