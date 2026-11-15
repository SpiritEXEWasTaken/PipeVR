package org.schabi.newpipe.extractor.services.odysee;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonWriter;

import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.MultiInfoItemsCollector;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.channel.ChannelExtractor;
import org.schabi.newpipe.extractor.channel.ChannelInfoItemExtractor;
import org.schabi.newpipe.extractor.channel.ChannelTabExtractor;
import org.schabi.newpipe.extractor.comments.CommentsExtractor;
import org.schabi.newpipe.extractor.comments.CommentsInfoItem;
import org.schabi.newpipe.extractor.comments.CommentsInfoItemExtractor;
import org.schabi.newpipe.extractor.comments.CommentsInfoItemsCollector;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.kiosk.KioskExtractor;
import org.schabi.newpipe.extractor.kiosk.KioskList;
import org.schabi.newpipe.extractor.linkhandler.LinkHandler;
import org.schabi.newpipe.extractor.linkhandler.LinkHandlerFactory;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandlerFactory;
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler;
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandlerFactory;
import org.schabi.newpipe.extractor.localization.DateWrapper;
import org.schabi.newpipe.extractor.playlist.PlaylistExtractor;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.schabi.newpipe.extractor.search.filter.FilterItem;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.Description;
import org.schabi.newpipe.extractor.stream.StreamExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItemExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.extractor.subscription.SubscriptionExtractor;
import org.schabi.newpipe.extractor.suggestion.SuggestionExtractor;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static org.schabi.newpipe.extractor.StreamingService.ServiceInfo.MediaCapability.COMMENTS;
import static org.schabi.newpipe.extractor.StreamingService.ServiceInfo.MediaCapability.LIVE;
import static org.schabi.newpipe.extractor.StreamingService.ServiceInfo.MediaCapability.VIDEO;

/**
 * PipeVR: Odysee (LBRY). Everything works signed out. Uses the endpoints odysee.com itself
 * calls: the LBRY SDK JSON-RPC proxy (resolve / claim_search / get), Lighthouse search, the
 * odysee.com homepage category config, api.odysee.live for livestreams and comments.odysee.tv.
 * Endpoint usage cross-checked against yt-dlp's public-domain lbry extractor.
 *
 * Ids are Odysee web paths with ':' separators, percent-decoded, e.g.
 * "@veritasium:f/why-do-deserts-get-cold-at-night:6" (video) or "@veritasium:f" (channel).
 */
public class OdyseeService extends StreamingService {
    static final String BASE = "https://odysee.com";
    static final String PROXY = "https://api.na-backend.odysee.com/api/v1/proxy";
    static final String LIGHTHOUSE = "https://lighthouse.odysee.tv/search";
    static final String LIVE_API = "https://api.odysee.live/livestream";
    static final String COMMENTS_API = "https://comments.odysee.tv/api/v2";
    static final String CONTENT_API = BASE + "/$/api/content/v2/get";
    private static final String UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
    static final int PAGE_SIZE = 20;
    static final List<String> MATURE = Arrays.asList("mature", "nsfw", "porn", "xxx");

    /** Kiosk id (label) to the odysee.com homepage category key. "Live" is special. */
    static final Map<String, String> CATEGORIES = new LinkedHashMap<>();

    static {
        CATEGORIES.put("Featured", "PRIMARY_CONTENT");
        CATEGORIES.put("Live", "");
        CATEGORIES.put("Tech", "TECHNOLOGY");
        CATEGORIES.put("Pop Culture", "POP_CULTURE");
        CATEGORIES.put("Gaming", "GAMING");
        CATEGORIES.put("Comedy", "COMEDY");
        CATEGORIES.put("Artists", "ART");
        CATEGORIES.put("Education", "EDUCATION");
        CATEGORIES.put("Lifestyle", "LIFESTYLE");
        CATEGORIES.put("Music", "MUSIC");
        CATEGORIES.put("Sports", "SPORTS");
        CATEGORIES.put("Universe", "UNIVERSE");
        CATEGORIES.put("Finance", "FINANCE");
        CATEGORIES.put("News & Politics", "NEWS_AND_POLITICS");
    }

    public OdyseeService(final int id) {
        super(id, "Odysee", Arrays.asList(VIDEO, LIVE, COMMENTS));
    }

    // ---- view and follower counts (api.odysee.com, anonymous visitor user) ----

    private static final String COUNTS_API = "https://api.odysee.com";

    /** Keeps the anonymous Odysee user's token between app starts (set by the app). */
    public interface TokenStore {
        @Nullable
        String load();

        void save(@Nullable String token);
    }

    @Nullable
    private static volatile TokenStore tokenStore;
    @Nullable
    private static volatile String authToken;
    private static volatile long tokenFailedAt;

    public static void setTokenStore(@Nullable final TokenStore store) {
        tokenStore = store;
    }

    /**
     * The token of an anonymous Odysee user, created once like the website does for every new
     * visitor. Null when it can't be made right now (counts are then simply not shown).
     */
    @Nullable
    private static synchronized String countsToken() {
        if (authToken != null) {
            return authToken;
        }
        final TokenStore store = tokenStore;
        if (store != null) {
            authToken = store.load();
            if (authToken != null && !authToken.isEmpty()) {
                return authToken;
            }
        }
        if (System.currentTimeMillis() - tokenFailedAt < 60_000) {
            return null;
        }
        try {
            final JsonObject o = countsPost("/user/new", "auth_token=&language=en");
            final String t = o.getObject("data").getString("auth_token");
            if (t != null && !t.isEmpty()) {
                authToken = t;
                if (store != null) {
                    store.save(t);
                }
                return t;
            }
        } catch (final Exception ignored) {
            // no counts this time
        }
        tokenFailedAt = System.currentTimeMillis();
        return null;
    }

    private static JsonObject countsPost(final String path, final String form)
            throws IOException, ExtractionException {
        final Map<String, List<String>> h = requestHeaders(false);
        h.put("Content-Type", Collections.singletonList("application/x-www-form-urlencoded"));
        final Response r = NewPipe.getDownloader().post(COUNTS_API + path, h,
                form.getBytes(StandardCharsets.UTF_8));
        try {
            return JsonParser.object().from(r.responseBody());
        } catch (final Exception e) {
            throw new ParsingException("Unexpected Odysee counts response (HTTP "
                    + r.responseCode() + ")", e);
        }
    }

    /**
     * Counts for several claims in one request (file/view_count or subscription/sub_count);
     * claim id -> count. Empty on any problem: counts are a nice-to-have.
     */
    static Map<String, Long> counts(final String path, final List<String> claimIds) {
        final Map<String, Long> out = new HashMap<>();
        if (claimIds.isEmpty()) {
            return out;
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            final String token = countsToken();
            if (token == null) {
                return out;
            }
            try {
                final JsonObject o = countsPost(path, "auth_token=" + enc(token)
                        + "&claim_id=" + enc(String.join(",", claimIds)));
                if (!o.getBoolean("success", false)) {
                    // token no longer valid: make a new anonymous user once
                    synchronized (OdyseeService.class) {
                        authToken = null;
                        final TokenStore store = tokenStore;
                        if (store != null) {
                            store.save(null);
                        }
                    }
                    continue;
                }
                final JsonArray data = o.getArray("data");
                for (int i = 0; i < data.size() && i < claimIds.size(); i++) {
                    final Object v = data.get(i);
                    if (v instanceof Number) {
                        out.put(claimIds.get(i), ((Number) v).longValue());
                    }
                }
                return out;
            } catch (final Exception e) {
                return out;
            }
        }
        return out;
    }

    static long count(final String path, @Nullable final String claimId) {
        if (claimId == null || claimId.isEmpty()) {
            return -1;
        }
        final Long v = counts(path, Collections.singletonList(claimId)).get(claimId);
        return v == null ? -1 : v;
    }

    static final String VIEWS = "/file/view_count";
    static final String FOLLOWERS = "/subscription/sub_count";

    static List<String> claimIds(final List<JsonObject> claims) {
        final List<String> ids = new ArrayList<>();
        for (final JsonObject c : claims) {
            final String id = c.getString("claim_id");
            if (id != null && !id.isEmpty()) {
                ids.add(id);
            }
        }
        return ids;
    }

    // ---- http helpers ----

    static Map<String, String> mediaHeaders() {
        final Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", UA);
        h.put("Referer", BASE + "/");
        h.put("Origin", BASE);
        return h;
    }

    private static Map<String, List<String>> requestHeaders(final boolean json) {
        final Map<String, List<String>> h = new HashMap<>();
        for (final Map.Entry<String, String> e : mediaHeaders().entrySet()) {
            h.put(e.getKey(), Collections.singletonList(e.getValue()));
        }
        h.put("Accept", Collections.singletonList("application/json"));
        if (json) {
            h.put("Content-Type", Collections.singletonList("application/json"));
        }
        return h;
    }

    static Object getJson(final String url) throws IOException, ExtractionException {
        final Response r = NewPipe.getDownloader().get(url, requestHeaders(false));
        try {
            return JsonParser.any().from(r.responseBody());
        } catch (final Exception e) {
            throw new ParsingException("Unexpected Odysee response (HTTP "
                    + r.responseCode() + ") from " + url, e);
        }
    }

    /** JSON-RPC call; returns "result" or throws with the error message. */
    static Object rpc(final String endpoint, final String method, final JsonObject params)
            throws IOException, ExtractionException {
        final JsonObject body = new JsonObject();
        body.put("jsonrpc", "2.0");
        body.put("method", method);
        body.put("params", params);
        body.put("id", 1);
        final Response r = NewPipe.getDownloader().post(endpoint + "?m=" + method,
                requestHeaders(true), JsonWriter.string(body).getBytes(StandardCharsets.UTF_8));
        final JsonObject o;
        try {
            o = JsonParser.object().from(r.responseBody());
        } catch (final Exception e) {
            throw new ParsingException("Unexpected Odysee response (HTTP "
                    + r.responseCode() + ")", e);
        }
        if (o.has("error") && o.get("error") != null) {
            final Object err = o.get("error");
            final String msg = err instanceof JsonObject
                    ? ((JsonObject) err).getString("message", "error") : String.valueOf(err);
            throw new ContentNotAvailableException("Odysee: " + msg);
        }
        final Object result = o.get("result");
        return result == null ? new JsonObject() : result;
    }

    static JsonObject sdk(final String method, final JsonObject params)
            throws IOException, ExtractionException {
        final Object o = rpc(PROXY, method, params);
        return o instanceof JsonObject ? (JsonObject) o : new JsonObject();
    }

    /** Resolve one lbry:// URI to its claim. */
    static JsonObject resolve(final String lbryUri) throws IOException, ExtractionException {
        final JsonObject p = new JsonObject();
        final JsonArray urls = new JsonArray();
        urls.add(lbryUri);
        p.put("urls", urls);
        final JsonObject res = sdk("resolve", p);
        final Object claim = res.get(lbryUri);
        if (!(claim instanceof JsonObject) && res.size() == 1) {
            final Object only = res.values().iterator().next();
            if (only instanceof JsonObject) {
                return checkClaim((JsonObject) only);
            }
        }
        if (!(claim instanceof JsonObject)) {
            throw new ContentNotAvailableException("Odysee: not found");
        }
        return checkClaim((JsonObject) claim);
    }

    private static JsonObject checkClaim(final JsonObject claim)
            throws ContentNotAvailableException {
        if (claim.has("error")) {
            final Object err = claim.get("error");
            final String msg = err instanceof JsonObject
                    ? ((JsonObject) err).getString("text", ((JsonObject) err).getString("name",
                    "not found")) : String.valueOf(err);
            throw new ContentNotAvailableException("Odysee: " + msg);
        }
        return claim;
    }

    static JsonObject baseSearch() {
        final JsonObject p = new JsonObject();
        p.put("page_size", PAGE_SIZE);
        p.put("claim_type", arr("stream"));
        p.put("stream_types", arr("video"));
        p.put("has_source", true);
        p.put("no_totals", true);
        p.put("not_tags", arr(MATURE.toArray(new String[0])));
        return p;
    }

    static JsonArray claimSearch(final JsonObject params) throws IOException, ExtractionException {
        return sdk("claim_search", params).getArray("items");
    }

    static JsonArray arr(final String... values) {
        final JsonArray a = new JsonArray();
        a.addAll(Arrays.asList(values));
        return a;
    }

    /** Claims by id, in the given order (claim_search does not keep it). */
    static List<JsonObject> claimsById(final List<String> ids, final boolean channels)
            throws IOException, ExtractionException {
        if (ids.isEmpty()) {
            return Collections.emptyList();
        }
        final JsonObject p = new JsonObject();
        p.put("claim_ids", arr(ids.toArray(new String[0])));
        p.put("page_size", ids.size());
        p.put("no_totals", true);
        p.put("claim_type", arr(channels ? "channel" : "stream"));
        if (!channels) {
            p.put("has_source", true);
        }
        final Map<String, JsonObject> byId = new HashMap<>();
        final JsonArray items = claimSearch(p);
        for (int i = 0; i < items.size(); i++) {
            byId.put(items.getObject(i).getString("claim_id"), items.getObject(i));
        }
        final List<JsonObject> out = new ArrayList<>();
        for (final String id : ids) {
            if (byId.containsKey(id)) {
                out.add(byId.get(id));
            }
        }
        return out;
    }

    static String enc(final String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    // ---- ids & urls ----

    /** "lbry://@a#1/b#2" or "@a:1/b:2" to the id form "@a:1/b:2". */
    static String idFromLbry(final String lbry) {
        String s = lbry.startsWith("lbry://") ? lbry.substring(7) : lbry;
        final int q = s.indexOf('?');
        if (q >= 0) {
            s = s.substring(0, q);
        }
        return s.replace('#', ':');
    }

    static String lbryFromId(final String id) {
        return "lbry://" + id.replace(':', '#');
    }

    static String webUrl(final String id) {
        final StringBuilder sb = new StringBuilder(BASE).append('/');
        final String[] segs = id.split("/");
        for (int i = 0; i < segs.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(enc(segs[i]).replace("+", "%20").replace("%3A", ":")
                    .replace("%40", "@"));
        }
        return sb.toString();
    }

    static String claimUrl(final JsonObject claim) {
        final String c = claim.getString("canonical_url",
                claim.getString("permanent_url", ""));
        return webUrl(idFromLbry(c));
    }

    private static final Pattern WEB = Pattern.compile(
            "^(?:https?://)?(?:www\\.)?(?:odysee\\.com|lbry\\.tv|open\\.lbry\\.com)/([^?]+)",
            Pattern.CASE_INSENSITIVE);

    /** Any accepted URL to its id path, or null. */
    @Nullable
    static String pathOf(final String url) {
        String path;
        if (url.startsWith("lbry://")) {
            path = idFromLbry(url);
        } else {
            final Matcher m = WEB.matcher(url);
            if (!m.find()) {
                return null;
            }
            path = m.group(1);
            final int hash = path.indexOf('#');
            if (hash >= 0) {
                path = path.substring(0, hash);
            }
            try {
                path = URLDecoder.decode(path.replace("+", "%2B"), StandardCharsets.UTF_8);
            } catch (final IllegalArgumentException e) {
                return null;
            }
        }
        path = path.replaceAll("/+$", "");
        if (path.startsWith("$/embed/")) {
            path = path.substring(8);
            final String[] segs = path.split("/");
            // legacy embed form: $/embed/{name}/{claimId}
            if (segs.length == 2 && !segs[0].startsWith("@")
                    && segs[1].matches("[0-9a-f]{40}")) {
                path = segs[0] + ":" + segs[1];
            }
        } else if (path.startsWith("$/")) {
            return null;
        }
        if (path.isEmpty()) {
            return null;
        }
        return path;
    }

    static boolean isVideoPath(@Nullable final String p) {
        if (p == null) {
            return false;
        }
        final String[] segs = p.split("/");
        return (segs.length == 2 && segs[0].startsWith("@") && !segs[1].isEmpty())
                || (segs.length == 1 && !segs[0].startsWith("@"));
    }

    static boolean isChannelPath(@Nullable final String p) {
        return p != null && p.startsWith("@") && !p.contains("/") && p.length() > 1;
    }

    // ---- claim helpers ----

    static JsonObject value(final JsonObject claim) {
        return claim.getObject("value");
    }

    @Nullable
    static String thumb(final JsonObject claim) {
        final String t = value(claim).getObject("thumbnail").getString("url");
        return t == null || t.isEmpty() ? null : t;
    }

    static boolean isMature(final JsonObject claim) {
        final JsonArray tags = value(claim).getArray("tags");
        for (int i = 0; i < tags.size(); i++) {
            if (MATURE.contains(String.valueOf(tags.get(i)).toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    static boolean hasSource(final JsonObject claim) {
        return value(claim).has("source");
    }

    static long releaseTime(final JsonObject claim) {
        final String rt = value(claim).getString("release_time");
        if (rt != null) {
            try {
                return Long.parseLong(rt);
            } catch (final NumberFormatException ignored) {
                // fall through
            }
        }
        final long ts = claim.getLong("timestamp", 0);
        return ts > 0 ? ts : claim.getObject("meta").getLong("creation_timestamp", 0);
    }

    @Nullable
    static DateWrapper date(final long epoch) {
        return epoch <= 0 ? null : new DateWrapper(
                OffsetDateTime.ofInstant(Instant.ofEpochSecond(epoch), ZoneOffset.UTC));
    }

    @Nullable
    static String isoDate(final long epoch) {
        return epoch <= 0 ? null : Instant.ofEpochSecond(epoch).toString();
    }

    static String title(final JsonObject claim) {
        final String t = value(claim).getString("title");
        return t == null || t.isEmpty() ? claim.getString("name", "Odysee") : t;
    }

    static JsonObject channelOf(final JsonObject claim) {
        return claim.get("signing_channel") instanceof JsonObject
                ? claim.getObject("signing_channel") : new JsonObject();
    }

    /** A video claim in a list. */
    static final class ClaimItem implements StreamInfoItemExtractor {
        private final JsonObject claim;
        private final boolean live;
        private final long viewers;

        ClaimItem(final JsonObject claim, final boolean live, final long viewers) {
            this.claim = claim;
            this.live = live;
            this.viewers = viewers;
        }

        @Override
        public String getName() {
            return title(claim);
        }

        @Override
        public String getUrl() {
            return claimUrl(claim);
        }

        @Override
        public String getThumbnailUrl() {
            return thumb(claim);
        }

        @Override
        public StreamType getStreamType() {
            return live ? StreamType.LIVE_STREAM : StreamType.VIDEO_STREAM;
        }

        @Override
        public long getDuration() {
            return live ? -1 : value(claim).getObject("video").getLong("duration", -1);
        }

        @Override
        public long getViewCount() {
            return viewers;
        }

        @Override
        public String getUploaderName() {
            final JsonObject ch = channelOf(claim);
            final String t = value(ch).getString("title");
            return t == null || t.isEmpty() ? ch.getString("name", "") : t;
        }

        @Override
        public String getUploaderUrl() {
            final JsonObject ch = channelOf(claim);
            return ch.has("canonical_url") ? claimUrl(ch) : null;
        }

        @Override
        public String getUploaderAvatarUrl() {
            return thumb(channelOf(claim));
        }

        @Override
        public String getTextualUploadDate() {
            return isoDate(releaseTime(claim));
        }

        @Override
        public DateWrapper getUploadDate() {
            return date(releaseTime(claim));
        }
    }

    static final class ChannelItem implements ChannelInfoItemExtractor {
        private final JsonObject claim;
        private final long followers;

        ChannelItem(final JsonObject claim) {
            this(claim, -1);
        }

        ChannelItem(final JsonObject claim, final long followers) {
            this.claim = claim;
            this.followers = followers;
        }

        @Override
        public String getName() {
            return title(claim);
        }

        @Override
        public String getUrl() {
            return claimUrl(claim);
        }

        @Override
        public String getThumbnailUrl() {
            return thumb(claim);
        }

        @Override
        public String getDescription() {
            return value(claim).getString("description", "");
        }

        @Override
        public long getSubscriberCount() {
            return followers;
        }

        @Override
        public long getStreamCount() {
            return claim.getObject("meta").getLong("claims_in_channel", -1);
        }

        @Override
        public boolean isVerified() {
            return false;
        }
    }

    static StreamInfoItemsCollector collect(final int serviceId, final JsonArray claims) {
        final StreamInfoItemsCollector c = new StreamInfoItemsCollector(serviceId);
        final List<JsonObject> shown = new ArrayList<>();
        for (int i = 0; i < claims.size(); i++) {
            final JsonObject claim = claims.getObject(i);
            if (hasSource(claim)) {
                shown.add(claim);
            }
        }
        final Map<String, Long> views = counts(VIEWS, claimIds(shown));
        for (final JsonObject claim : shown) {
            final Long v = views.get(claim.getString("claim_id", ""));
            c.commit(new ClaimItem(claim, false, v == null ? -1 : v));
        }
        return c;
    }

    /** Lighthouse search: claim ids in rank order. */
    static List<String> lighthouse(final String query) throws IOException, ExtractionException {
        final Object o = getJson(LIGHTHOUSE + "?" + query);
        final List<String> ids = new ArrayList<>();
        if (o instanceof JsonArray) {
            final JsonArray a = (JsonArray) o;
            for (int i = 0; i < a.size(); i++) {
                final String id = a.getObject(i).getString("claimId");
                if (id != null && !ids.contains(id)) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    // ---- link handlers ----

    static final class StreamLH extends LinkHandlerFactory {
        @Override
        public String getId(final String url) throws ParsingException {
            final String p = pathOf(url);
            if (isVideoPath(p)) {
                return p;
            }
            throw new ParsingException("Not an Odysee video: " + url);
        }

        @Override
        public String getUrl(final String id) {
            return webUrl(id);
        }

        @Override
        public boolean onAcceptUrl(final String url) {
            return isVideoPath(pathOf(url));
        }
    }

    static final class ChannelLH extends ListLinkHandlerFactory {
        @Override
        public String getId(final String url) throws ParsingException {
            final String p = pathOf(url);
            if (isChannelPath(p)) {
                return p;
            }
            throw new ParsingException("Not an Odysee channel: " + url);
        }

        @Override
        public String getUrl(final String id, final List<FilterItem> c, final List<FilterItem> s) {
            return webUrl(id);
        }

        @Override
        public boolean onAcceptUrl(final String url) {
            return isChannelPath(pathOf(url));
        }
    }

    /** Comments are keyed by the video URL. */
    static final class CommentsLH extends ListLinkHandlerFactory {
        private final StreamLH stream = new StreamLH();

        @Override
        public String getId(final String url) throws ParsingException {
            return stream.getId(url);
        }

        @Override
        public String getUrl(final String id, final List<FilterItem> c, final List<FilterItem> s) {
            return webUrl(id);
        }

        @Override
        public boolean onAcceptUrl(final String url) {
            return stream.onAcceptUrl(url);
        }
    }

    static final class SearchLH extends SearchQueryHandlerFactory {
        private static final String PREFIX = BASE + "/$/search?q=";

        @Override
        public String getUrl(final String q, final List<FilterItem> c, final List<FilterItem> s) {
            return PREFIX + enc(q);
        }

        @Override
        public String getSearchString(final String url) {
            return url.startsWith(PREFIX)
                    ? URLDecoder.decode(url.substring(PREFIX.length()), StandardCharsets.UTF_8) : "";
        }
    }

    static final class KioskLH extends ListLinkHandlerFactory {
        private static final String PREFIX = BASE + "/$/pipevr-kiosk/";

        @Override
        public String getUrl(final String id, final List<FilterItem> c, final List<FilterItem> s) {
            return PREFIX + enc(id).replace("+", "%20");
        }

        @Override
        public String getId(final String url) throws ParsingException {
            if (!url.startsWith(PREFIX)) {
                throw new ParsingException("Not an Odysee kiosk: " + url);
            }
            return URLDecoder.decode(url.substring(PREFIX.length()), StandardCharsets.UTF_8);
        }

        @Override
        public boolean onAcceptUrl(final String url) {
            return url.startsWith(PREFIX);
        }
    }

    // ---- extractors ----

    static final class VideoExtractor extends StreamExtractor {
        private JsonObject claim;
        private boolean live;
        private final List<VideoStream> videos = new ArrayList<>();
        private String hlsUrl = "";
        private long viewers = -1;

        VideoExtractor(final StreamingService s, final LinkHandler h) {
            super(s, h);
        }

        @Override
        public void onFetchPage(@Nonnull final Downloader d)
                throws IOException, ExtractionException {
            claim = resolve(lbryFromId(getId()));
            // views (a livestream replaces this with its current viewers below)
            viewers = count(VIEWS, claim.getString("claim_id"));
            if (!"stream".equals(claim.getString("value_type"))) {
                if ("repost".equals(claim.getString("value_type"))
                        && claim.get("reposted_claim") instanceof JsonObject) {
                    claim = claim.getObject("reposted_claim");
                } else {
                    throw new ContentNotAvailableException("This Odysee link is not a video");
                }
            }
            final JsonObject v = value(claim);
            if (!hasSource(claim)) {
                loadLive();
                return;
            }
            final JsonObject fee = v.getObject("fee");
            if (!fee.isEmpty() && parseDouble(fee.getString("amount", "0")) > 0) {
                throw new ContentNotAvailableException("This Odysee video must be purchased");
            }
            final String mediaType = v.getObject("source").getString("media_type", "");
            if (!mediaType.startsWith("video/") && !mediaType.isEmpty()) {
                throw new ContentNotAvailableException("Odysee: unsupported media type "
                        + mediaType);
            }
            final JsonObject p = new JsonObject();
            p.put("uri", claim.getString("permanent_url", lbryFromId(getId())));
            p.put("save_file", false);
            final String mp4 = sdk("get", p).getString("streaming_url");
            if (mp4 == null || mp4.isEmpty()) {
                throw new ContentNotAvailableException("Odysee did not provide this video");
            }
            final int height = v.getObject("video").getInt("height", 0);
            final String claimId = claim.getString("claim_id");
            // transcoded HLS (master playlist next to the original upload)
            if (mp4.endsWith(".mp4")) {
                addHls(mp4.substring(0, mp4.length() - 4) + "/master.m3u8", claimId);
            }
            videos.add(new VideoStream.Builder()
                    .setId("odysee-" + claimId + "-original")
                    .setContent(mp4, true).setIsVideoOnly(false)
                    .setMediaFormat(MediaFormat.MPEG_4)
                    .setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
                    .setResolution(height > 0 ? height + "p" : "Original")
                    .setRequestHeaders(mediaHeaders())
                    .build());
        }

        private static double parseDouble(final String s) {
            try {
                return Double.parseDouble(s);
            } catch (final NumberFormatException e) {
                return 0;
            }
        }

        /** Parses an HLS master and adds one stream per variant; no-op if it isn't there. */
        private boolean addHls(final String master, final String idPrefix) {
            final Response r;
            try {
                r = NewPipe.getDownloader().get(master, requestHeaders(false));
            } catch (final Exception e) {
                return false;
            }
            final String body = r.responseBody();
            if (r.responseCode() != 200 || body == null || !body.startsWith("#EXTM3U")) {
                return false;
            }
            hlsUrl = master;
            final String base = master.substring(0, master.lastIndexOf('/') + 1);
            final String[] lines = body.split("\n");
            final Pattern res = Pattern.compile("RESOLUTION=\\d+x(\\d+)");
            int added = 0;
            for (int i = 0; i < lines.length; i++) {
                final String line = lines[i].trim();
                if (!line.startsWith("#EXT-X-STREAM-INF")) {
                    continue;
                }
                String uri = null;
                for (int j = i + 1; j < lines.length && uri == null; j++) {
                    final String n = lines[j].trim();
                    if (!n.isEmpty() && !n.startsWith("#")) {
                        uri = n;
                    }
                }
                if (uri == null) {
                    continue;
                }
                final Matcher m = res.matcher(line);
                final String label = m.find() ? m.group(1) + "p" : "HLS";
                videos.add(new VideoStream.Builder()
                        .setId("odysee-" + idPrefix + "-hls-" + added)
                        .setContent(uri.startsWith("http") ? uri : base + uri, true)
                        .setIsVideoOnly(false)
                        .setMediaFormat(MediaFormat.MPEG_4)
                        .setDeliveryMethod(DeliveryMethod.HLS)
                        .setResolution(label)
                        .setRequestHeaders(mediaHeaders())
                        .build());
                added++;
            }
            if (added == 0) {
                videos.add(new VideoStream.Builder()
                        .setId("odysee-" + idPrefix + "-hls")
                        .setContent(master, true).setIsVideoOnly(false)
                        .setMediaFormat(MediaFormat.MPEG_4)
                        .setDeliveryMethod(DeliveryMethod.HLS)
                        .setResolution("Auto")
                        .setRequestHeaders(mediaHeaders())
                        .build());
            }
            return true;
        }

        private void loadLive() throws IOException, ExtractionException {
            final String channelId = channelOf(claim).getString("claim_id");
            if (channelId == null) {
                throw new ContentNotAvailableException("Odysee: this livestream has no channel");
            }
            final Object o = getJson(LIVE_API + "/is_live?channel_claim_id=" + channelId);
            final JsonObject data = o instanceof JsonObject
                    ? ((JsonObject) o).getObject("data") : new JsonObject();
            final String url = data.getString("VideoURL");
            if (!data.getBoolean("Live", false) || url == null || url.isEmpty()) {
                throw new ContentNotAvailableException("This Odysee livestream is not live now");
            }
            live = true;
            viewers = data.getLong("ViewerCount", -1);
            hlsUrl = url;
            videos.add(new VideoStream.Builder()
                    .setId("odysee-live-" + channelId)
                    .setContent(url, true).setIsVideoOnly(false)
                    .setMediaFormat(MediaFormat.MPEG_4)
                    .setDeliveryMethod(DeliveryMethod.HLS)
                    .setResolution("Live")
                    .setRequestHeaders(mediaHeaders())
                    .build());
        }

        @Nonnull
        @Override
        public String getName() {
            return title(claim);
        }

        @Override
        public String getThumbnailUrl() {
            return thumb(claim);
        }

        @Override
        public String getUploaderUrl() {
            final JsonObject ch = channelOf(claim);
            return ch.has("canonical_url") ? claimUrl(ch) : "";
        }

        @Override
        public String getUploaderName() {
            final JsonObject ch = channelOf(claim);
            final String t = value(ch).getString("title");
            return t == null || t.isEmpty() ? ch.getString("name", "") : t;
        }

        @Override
        public String getUploaderAvatarUrl() {
            return thumb(channelOf(claim));
        }

        @Nonnull
        @Override
        public Description getDescription() {
            return new Description(value(claim).getString("description", ""),
                    Description.MARKDOWN);
        }

        @Override
        public long getLength() {
            return live ? 0 : value(claim).getObject("video").getLong("duration", 0);
        }

        @Override
        public long getViewCount() {
            return viewers;
        }

        @Override
        public String getTextualUploadDate() {
            return isoDate(releaseTime(claim));
        }

        @Override
        public DateWrapper getUploadDate() {
            return date(releaseTime(claim));
        }

        @Override
        public int getAgeLimit() {
            return isMature(claim) ? 18 : 0;
        }

        @Nonnull
        @Override
        public String getLicence() {
            return value(claim).getString("license", "");
        }

        @Nonnull
        @Override
        public List<String> getTags() {
            final List<String> out = new ArrayList<>();
            final JsonArray tags = value(claim).getArray("tags");
            for (int i = 0; i < tags.size(); i++) {
                final String t = String.valueOf(tags.get(i));
                if (!t.startsWith("c:")) {
                    out.add(t);
                }
            }
            return out;
        }

        @Nonnull
        @Override
        public String getHlsUrl() {
            return hlsUrl;
        }

        @Override
        public List<AudioStream> getAudioStreams() {
            return Collections.emptyList();
        }

        @Override
        public List<VideoStream> getVideoStreams() {
            return videos;
        }

        @Override
        public List<VideoStream> getVideoOnlyStreams() {
            return Collections.emptyList();
        }

        @Override
        public StreamType getStreamType() {
            return live ? StreamType.LIVE_STREAM : StreamType.VIDEO_STREAM;
        }

        @Override
        public StreamInfoItemsCollector getRelatedItems() throws IOException,
                ExtractionException {
            final String claimId = claim.getString("claim_id");
            final List<String> ids = lighthouse("s=" + enc(title(claim)) + "&related_to="
                    + claimId + "&size=16&from=0&free_only=true&nsfw=false");
            ids.remove(claimId);
            final StreamInfoItemsCollector c = new StreamInfoItemsCollector(getServiceId());
            for (final JsonObject related : claimsById(ids, false)) {
                if (hasSource(related) && !isMature(related)) {
                    c.commit(new ClaimItem(related, false, -1));
                }
            }
            return c;
        }
    }

    /** A channel's videos, newest first, paged by claim_search page number. */
    static final class ChannelExtractorImpl extends ChannelExtractor {
        private JsonObject channel;
        private long followers = -1;
        private JsonArray first;

        ChannelExtractorImpl(final StreamingService s, final ListLinkHandler h) {
            super(s, h);
        }

        private JsonArray fetch(final int page) throws IOException, ExtractionException {
            if (channel == null) {
                // a fresh extractor (the app's "load more"): look the channel up first
                channel = resolve(lbryFromId(getId()));
            }
            final JsonObject p = baseSearch();
            p.remove("not_tags");
            p.put("channel_ids", arr(channel.getString("claim_id")));
            p.put("order_by", arr("release_time"));
            p.put("page", page);
            return claimSearch(p);
        }

        @Override
        public void onFetchPage(@Nonnull final Downloader d)
                throws IOException, ExtractionException {
            channel = resolve(lbryFromId(getId()));
            if (!"channel".equals(channel.getString("value_type"))) {
                throw new ContentNotAvailableException("Not an Odysee channel");
            }
            followers = count(FOLLOWERS, channel.getString("claim_id"));
            first = fetch(1);
        }

        @Nonnull
        @Override
        public String getName() {
            return title(channel);
        }

        @Override
        public String getAvatarUrl() {
            return thumb(channel);
        }

        @Override
        public String getBannerUrl() {
            return value(channel).getObject("cover").getString("url");
        }

        @Override
        public long getSubscriberCount() {
            return followers;
        }

        @Override
        public String getDescription() {
            return value(channel).getString("description", "");
        }

        @Override
        public String getParentChannelName() {
            return "";
        }

        @Override
        public String getParentChannelUrl() {
            return "";
        }

        @Override
        public String getParentChannelAvatarUrl() {
            return "";
        }

        @Override
        public boolean isVerified() {
            return false;
        }

        private InfoItemsPage<StreamInfoItem> page(final JsonArray list, final int page)
                throws ParsingException {
            return new InfoItemsPage<>(collect(getServiceId(), list),
                    list.size() < PAGE_SIZE ? null : new Page(getUrl(), String.valueOf(page + 1)));
        }

        @Nonnull
        @Override
        public InfoItemsPage<StreamInfoItem> getInitialPage() throws ParsingException {
            return page(first, 1);
        }

        @Override
        public InfoItemsPage<StreamInfoItem> getPage(final Page p)
                throws IOException, ExtractionException {
            final int page = Integer.parseInt(p.getId());
            return page(fetch(page), page);
        }
    }

    /** odysee.com homepage category config (channel ids per category), cached. */
    @Nullable
    private static volatile JsonObject categoryConfig;

    static JsonObject category(final String key) throws IOException, ExtractionException {
        JsonObject cfg = categoryConfig;
        if (cfg == null) {
            final Object o = getJson(CONTENT_API);
            cfg = o instanceof JsonObject ? ((JsonObject) o).getObject("data").getObject("en")
                    .getObject("categories") : new JsonObject();
            if (!cfg.isEmpty()) {
                categoryConfig = cfg;
            }
        }
        return cfg.getObject(key);
    }

    /** Homepage categories (trending in curated channels) and the live-now list. */
    static final class Kiosk extends KioskExtractor<StreamInfoItem> {
        private JsonObject params;
        private StreamInfoItemsCollector firstLive;
        private JsonArray first;

        Kiosk(final StreamingService s, final ListLinkHandler h, final String id) {
            super(s, h, id);
        }

        @Override
        public void onFetchPage(@Nonnull final Downloader d)
                throws IOException, ExtractionException {
            if ("Live".equals(getId())) {
                firstLive = liveNow();
                return;
            }
            final String key = CATEGORIES.get(getId());
            if (key == null) {
                throw new ContentNotAvailableException("Unknown Odysee kiosk " + getId());
            }
            buildParams(key);
            first = claimSearch(params);
        }

        /** Search parameters of a category kiosk (page 1). */
        private void buildParams(final String key) throws IOException, ExtractionException {
            final JsonObject cat = category(key);
            final JsonArray channels = cat.getArray("channelIds");
            params = baseSearch();
            if (!channels.isEmpty()) {
                params.put("channel_ids", channels);
            }
            final long days = cat.getLong("daysOfContent", 30);
            params.put("release_time", ">" + (System.currentTimeMillis() / 1000
                    - days * 86400));
            if (cat.getBoolean("exclude_shorts", false)) {
                params.put("duration", ">60");
            }
            params.put("order_by", "new".equals(cat.getString("order"))
                    ? arr("release_time") : arr("trending_group", "trending_mixed"));
            params.put("page", 1);
        }

        private StreamInfoItemsCollector liveNow() throws IOException, ExtractionException {
            final Object o = getJson(LIVE_API + "/all");
            final JsonArray data = o instanceof JsonObject
                    ? ((JsonObject) o).getArray("data") : new JsonArray();
            final JsonArray urls = new JsonArray();
            final Map<String, Long> viewers = new HashMap<>();
            for (int i = 0; i < data.size() && urls.size() < 50; i++) {
                final JsonObject e = data.getObject(i);
                final String url = e.getObject("ActiveClaim").getString("CanonicalURL");
                if (e.getBoolean("Live", false) && url != null) {
                    urls.add(url);
                    viewers.put(url, e.getLong("ViewerCount", -1));
                }
            }
            final StreamInfoItemsCollector c = new StreamInfoItemsCollector(getServiceId());
            if (urls.isEmpty()) {
                return c;
            }
            final JsonObject p = new JsonObject();
            p.put("urls", urls);
            final JsonObject res = sdk("resolve", p);
            for (int i = 0; i < urls.size(); i++) {
                final String url = urls.getString(i);
                final Object claim = res.get(url);
                if (claim instanceof JsonObject && !((JsonObject) claim).has("error")
                        && !isMature((JsonObject) claim)) {
                    c.commit(new ClaimItem((JsonObject) claim, true, viewers.get(url)));
                }
            }
            return c;
        }

        @Nonnull
        @Override
        public String getName() {
            return getId();
        }

        private InfoItemsPage<StreamInfoItem> page(final JsonArray list, final int page)
                throws ParsingException {
            return new InfoItemsPage<>(collect(getServiceId(), list), list.size() < PAGE_SIZE
                    || page >= 10 ? null : new Page(getUrl(), String.valueOf(page + 1)));
        }

        @Nonnull
        @Override
        public InfoItemsPage<StreamInfoItem> getInitialPage() throws ParsingException {
            if (firstLive != null) {
                return new InfoItemsPage<>(firstLive, null);
            }
            return page(first, 1);
        }

        @Override
        public InfoItemsPage<StreamInfoItem> getPage(final Page p)
                throws IOException, ExtractionException {
            if (params == null) {
                // a fresh extractor (the app's "load more"): rebuild the parameters
                final String key = CATEGORIES.get(getId());
                if (key == null) {
                    return InfoItemsPage.emptyPage();
                }
                buildParams(key);
            }
            final int page = Integer.parseInt(p.getId());
            params.put("page", page);
            return page(claimSearch(params), page);
        }
    }

    /** Lighthouse search: channels (first page) and videos, paged by offset. */
    static final class Search extends SearchExtractor {
        private InfoItemsPage<InfoItem> first;

        Search(final StreamingService s, final SearchQueryHandler h) {
            super(s, h);
        }

        private InfoItemsPage<InfoItem> fetch(final int from, final boolean withChannels)
                throws IOException, ExtractionException {
            final String q = "s=" + enc(getSearchString()) + "&nsfw=false";
            final MultiInfoItemsCollector c = new MultiInfoItemsCollector(getServiceId());
            if (withChannels) {
                final List<JsonObject> chans = claimsById(lighthouse(q
                        + "&claimType=channel&size=3&from=0"), true);
                final Map<String, Long> followers = counts(FOLLOWERS, claimIds(chans));
                for (final JsonObject ch : chans) {
                    final Long f = followers.get(ch.getString("claim_id", ""));
                    c.commit(new ChannelItem(ch, f == null ? -1 : f));
                }
            }
            final List<String> ids = lighthouse(q + "&claimType=file&mediaType=video"
                    + "&free_only=true&size=" + PAGE_SIZE + "&from=" + from);
            final List<JsonObject> vids = new ArrayList<>();
            for (final JsonObject v : claimsById(ids, false)) {
                if (hasSource(v) && !isMature(v)) {
                    vids.add(v);
                }
            }
            final Map<String, Long> views = counts(VIEWS, claimIds(vids));
            for (final JsonObject v : vids) {
                final Long n = views.get(v.getString("claim_id", ""));
                c.commit(new ClaimItem(v, false, n == null ? -1 : n));
            }
            return new InfoItemsPage<>(c, ids.size() < PAGE_SIZE ? null
                    : new Page(getUrl(), String.valueOf(from + PAGE_SIZE)));
        }

        @Override
        public void onFetchPage(@Nonnull final Downloader d)
                throws IOException, ExtractionException {
            first = fetch(0, true);
        }

        @Override
        protected InfoItemsPage<InfoItem> getInitialPageInternal() {
            return first;
        }

        @Override
        protected InfoItemsPage<InfoItem> getPageInternal(final Page page)
                throws IOException, ExtractionException {
            return fetch(Integer.parseInt(page.getId()), false);
        }
    }

    /** Public comments from comments.odysee.tv, popular first. */
    static final class Comments extends CommentsExtractor {
        private String claimId;
        private InfoItemsPage<CommentsInfoItem> first;

        Comments(final StreamingService s, final ListLinkHandler h) {
            super(s, h);
        }

        private InfoItemsPage<CommentsInfoItem> fetch(final int page)
                throws IOException, ExtractionException {
            final JsonObject p = new JsonObject();
            p.put("page", page);
            p.put("claim_id", claimId);
            p.put("page_size", PAGE_SIZE);
            p.put("top_level", true);
            p.put("sort_by", 3);
            final Object o = rpc(COMMENTS_API, "comment.List", p);
            final JsonObject res = o instanceof JsonObject ? (JsonObject) o : new JsonObject();
            final JsonArray items = res.getArray("items");
            final CommentsInfoItemsCollector c = new CommentsInfoItemsCollector(getServiceId());
            for (int i = 0; i < items.size(); i++) {
                final JsonObject cm = items.getObject(i);
                c.commit(new CommentsInfoItemExtractor() {
                    @Override
                    public String getName() {
                        return cm.getString("channel_name", "");
                    }

                    @Override
                    public String getUrl() {
                        return getBaseUrlOfVideo();
                    }

                    @Override
                    public String getThumbnailUrl() {
                        return null;
                    }

                    @Override
                    public String getCommentId() {
                        return cm.getString("comment_id", "");
                    }

                    @Override
                    public Description getCommentText() {
                        return new Description(cm.getString("comment", ""),
                                Description.MARKDOWN);
                    }

                    @Override
                    public String getUploaderName() {
                        return cm.getString("channel_name", "");
                    }

                    @Override
                    public String getUploaderUrl() {
                        final String u = cm.getString("channel_url");
                        return u == null ? "" : webUrl(idFromLbry(u));
                    }

                    @Override
                    public String getTextualUploadDate() {
                        return isoDate(cm.getLong("timestamp", 0));
                    }

                    @Override
                    public DateWrapper getUploadDate() {
                        return date(cm.getLong("timestamp", 0));
                    }

                    @Override
                    public boolean isPinned() {
                        return cm.getBoolean("is_pinned", false);
                    }

                    @Override
                    public int getReplyCount() {
                        return cm.getInt("replies", 0);
                    }
                });
            }
            final int total = res.getInt("total_pages", 0);
            return new InfoItemsPage<>(c, page < total
                    ? new Page(getUrl(), String.valueOf(page + 1)) : null);
        }

        private String videoUrl = "";

        private String getBaseUrlOfVideo() {
            return videoUrl;
        }

        @Override
        public void onFetchPage(@Nonnull final Downloader d)
                throws IOException, ExtractionException {
            final JsonObject claim = resolve(lbryFromId(getId()));
            claimId = claim.getString("claim_id");
            videoUrl = getUrl();
            first = fetch(1);
        }

        @Nonnull
        @Override
        public InfoItemsPage<CommentsInfoItem> getInitialPage() {
            return first;
        }

        @Override
        public InfoItemsPage<CommentsInfoItem> getPage(final Page page)
                throws IOException, ExtractionException {
            return fetch(Integer.parseInt(page.getId()));
        }
    }

    // ---- service ----

    @Override
    public String getBaseUrl() {
        return BASE;
    }

    @Override
    public LinkHandlerFactory getStreamLHFactory() {
        return new StreamLH();
    }

    @Override
    public ListLinkHandlerFactory getChannelLHFactory() {
        return new ChannelLH();
    }

    @Override
    public ListLinkHandlerFactory getChannelTabLHFactory() {
        return null;
    }

    @Override
    public ListLinkHandlerFactory getPlaylistLHFactory() {
        return null;
    }

    @Override
    public SearchQueryHandlerFactory getSearchQHFactory() {
        return new SearchLH();
    }

    @Override
    public ListLinkHandlerFactory getCommentsLHFactory() {
        return new CommentsLH();
    }

    @Override
    public SearchExtractor getSearchExtractor(final SearchQueryHandler q) {
        return new Search(this, q);
    }

    @Override
    public SuggestionExtractor getSuggestionExtractor() {
        return null;
    }

    @Override
    public SubscriptionExtractor getSubscriptionExtractor() {
        return null;
    }

    @Override
    public KioskList getKioskList() throws ExtractionException {
        final KioskList list = new KioskList(this);
        final KioskLH lh = new KioskLH();
        try {
            for (final String id : CATEGORIES.keySet()) {
                list.addKioskEntry((s, url, kid) -> new Kiosk(OdyseeService.this,
                        lh.fromUrl(url), kid), lh, id);
            }
            list.setDefaultKiosk("Featured");
        } catch (final Exception e) {
            throw new ExtractionException(e);
        }
        return list;
    }

    @Override
    public ChannelExtractor getChannelExtractor(final ListLinkHandler h) {
        return new ChannelExtractorImpl(this, h);
    }

    @Override
    public ChannelTabExtractor getChannelTabExtractor(final ListLinkHandler h) {
        return null;
    }

    @Override
    public PlaylistExtractor getPlaylistExtractor(final ListLinkHandler h) {
        return null;
    }

    @Override
    public StreamExtractor getStreamExtractor(final LinkHandler h) {
        return new VideoExtractor(this, h);
    }

    @Override
    public CommentsExtractor getCommentsExtractor(final ListLinkHandler h) {
        return new Comments(this, h);
    }
}
