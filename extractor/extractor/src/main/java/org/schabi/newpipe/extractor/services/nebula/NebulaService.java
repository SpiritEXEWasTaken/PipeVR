package org.schabi.newpipe.extractor.services.nebula;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonWriter;

import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.InfoItemExtractor;
import org.schabi.newpipe.extractor.InfoItemsCollector;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.MultiInfoItemsCollector;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.channel.ChannelExtractor;
import org.schabi.newpipe.extractor.channel.ChannelInfoItemExtractor;
import org.schabi.newpipe.extractor.channel.ChannelTabExtractor;
import org.schabi.newpipe.extractor.comments.CommentsExtractor;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.PaidContentException;
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
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static org.schabi.newpipe.extractor.StreamingService.ServiceInfo.MediaCapability.VIDEO;

/**
 * PipeVR: Nebula (nebula.tv). Browsing, search, channels and video metadata use the public
 * content API the website calls (content.api.nebula.app) and work signed out. Playback needs a
 * Nebula JWT: videos marked "free_sample_eligible" play with the anonymous JWT the website
 * gets from users.api.nebula.app/api/v1/authorization/; everything else needs a subscriber's
 * JWT ({@link #setUserToken}). Endpoints follow yt-dlp's (Unlicense) nebula extractor and the
 * website's own calls.
 */
public class NebulaService extends StreamingService {
    static final String BASE = "https://nebula.tv";
    static final String CONTENT = "https://content.api.nebula.app";
    static final String USERS = "https://users.api.nebula.app";
    private static final String UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
    static final int PAGE_SIZE = 24;

    @Nullable
    private static volatile String userToken;
    @Nullable
    private static volatile String anonToken;

    /** The signed-in user's Nebula JWT (from {@link #signIn} or {@link #authorize}). */
    public static void setUserToken(@Nullable final String jwt) {
        userToken = jwt == null || jwt.trim().isEmpty() ? null : jwt.trim();
    }

    public static boolean isSignedIn() {
        return userToken != null;
    }

    public NebulaService(final int id) {
        super(id, "Nebula", Collections.singletonList(VIDEO));
    }

    // ---- sign-in helpers (never called by the extractor itself) ----

    /**
     * Logs in with email and password and returns the account's API key ("key"), which is
     * long-lived; store it and turn it into a JWT with {@link #authorize}.
     */
    public static String loginKey(final String email, final String password)
            throws IOException, ExtractionException {
        final byte[] body = JsonWriter.string().object()
                .value("email", email).value("password", password).end().done()
                .getBytes(StandardCharsets.UTF_8);
        final Map<String, List<String>> h = headers(null);
        h.put("Content-Type", Collections.singletonList("application/json"));
        Response r = NewPipe.getDownloader().post(USERS + "/api/v1/auth/login/", h, body);
        if (r.responseCode() == 404 || r.responseCode() == 405) {
            // the website's own proxy, which yt-dlp uses
            r = NewPipe.getDownloader().post(BASE + "/auth/login/", h, body);
        }
        if (r.responseCode() == 400 || r.responseCode() == 401) {
            throw new ContentNotAvailableException("Nebula: wrong email or password");
        }
        final String key = parseObject(r).getString("key");
        if (key == null || key.isEmpty()) {
            throw new ParsingException("Nebula login returned no key (HTTP "
                    + r.responseCode() + ")");
        }
        return key;
    }

    /** Turns an API key into a JWT (pass null for an anonymous JWT). */
    public static String authorize(@Nullable final String apiKey)
            throws IOException, ExtractionException {
        final Map<String, List<String>> h = headers(null);
        if (apiKey != null) {
            h.put("Authorization", Collections.singletonList("Token " + apiKey));
        }
        final Response r = NewPipe.getDownloader().post(USERS + "/api/v1/authorization/", h,
                new byte[0]);
        final String token = parseObject(r).getString("token");
        if (token == null || token.isEmpty()) {
            throw new ParsingException("Nebula authorization failed (HTTP "
                    + r.responseCode() + ")");
        }
        return token;
    }

    /** Email + password to a user JWT (login, then authorization). */
    public static String signIn(final String email, final String password)
            throws IOException, ExtractionException {
        return authorize(loginKey(email, password));
    }

    // ---- HTTP ----

    static Map<String, List<String>> headers(@Nullable final String bearer) {
        final Map<String, List<String>> h = new HashMap<>();
        h.put("User-Agent", Collections.singletonList(UA));
        h.put("Accept", Collections.singletonList("application/json"));
        h.put("Origin", Collections.singletonList(BASE));
        h.put("Referer", Collections.singletonList(BASE + "/"));
        if (bearer != null) {
            h.put("Authorization", Collections.singletonList("Bearer " + bearer));
        }
        return h;
    }

    static JsonObject parseObject(final Response r) throws ParsingException {
        try {
            final Object o = JsonParser.any().from(r.responseBody());
            return o instanceof JsonObject ? (JsonObject) o : new JsonObject();
        } catch (final Exception e) {
            throw new ParsingException("Unexpected Nebula response (HTTP "
                    + r.responseCode() + ")", e);
        }
    }

    /** GET on the public content API (absolute URL or path). */
    static JsonObject api(final String pathOrUrl) throws IOException, ExtractionException {
        final String url = pathOrUrl.startsWith("http") ? pathOrUrl : CONTENT + pathOrUrl;
        final Response r = NewPipe.getDownloader().get(url, headers(null));
        if (r.responseCode() == 404) {
            throw new ContentNotAvailableException("Not found on Nebula");
        }
        return parseObject(r);
    }

    static String enc(final String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    static String videoUrl(final String slug) {
        return BASE + "/videos/" + slug;
    }

    static String channelUrl(final String slug) {
        return BASE + "/" + slug;
    }

    @Nullable
    static String img(final JsonObject images, final String key) {
        final Object o = images.get(key);
        return o instanceof JsonObject ? ((JsonObject) o).getString("src") : null;
    }

    @Nullable
    static DateWrapper date(@Nullable final String iso) {
        if (iso == null) {
            return null;
        }
        try {
            return new DateWrapper(OffsetDateTime.parse(iso));
        } catch (final Exception e) {
            return null;
        }
    }

    static boolean isFreeSample(final JsonObject ep) {
        return ep.getArray("attributes").contains("free_sample_eligible");
    }

    // ---- items ----

    /** A video_episode in a list. */
    static final class EpisodeItem implements StreamInfoItemExtractor {
        private final JsonObject ep;

        EpisodeItem(final JsonObject ep) {
            this.ep = ep;
        }

        @Override
        public String getName() {
            return ep.getString("title", "Nebula video");
        }

        @Override
        public String getUrl() {
            return videoUrl(ep.getString("slug"));
        }

        @Override
        public String getThumbnailUrl() {
            return img(ep.getObject("images"), "thumbnail");
        }

        @Override
        public StreamType getStreamType() {
            return StreamType.VIDEO_STREAM;
        }

        @Override
        public long getDuration() {
            return ep.getLong("duration", -1);
        }

        @Override
        public long getViewCount() {
            return -1;
        }

        @Override
        public String getUploaderName() {
            return ep.getString("channel_title", "");
        }

        @Override
        public String getUploaderUrl() {
            final String s = ep.getString("channel_slug");
            return s == null ? "" : channelUrl(s);
        }

        @Override
        public String getUploaderAvatarUrl() {
            return img(ep.getObject("images"), "channel_avatar");
        }

        @Override
        public String getTextualUploadDate() {
            return ep.getString("published_at");
        }

        @Override
        public DateWrapper getUploadDate() {
            return date(ep.getString("published_at"));
        }
    }

    static final class ChannelItem implements ChannelInfoItemExtractor {
        private final JsonObject c;

        ChannelItem(final JsonObject c) {
            this.c = c;
        }

        @Override
        public String getName() {
            return c.getString("title", "");
        }

        @Override
        public String getUrl() {
            return channelUrl(c.getString("slug", ""));
        }

        @Override
        public String getThumbnailUrl() {
            return img(c.getObject("images"), "avatar");
        }

        @Override
        public String getDescription() {
            return c.getString("description", "");
        }

        @Override
        public long getSubscriberCount() {
            return -1;
        }

        @Override
        public long getStreamCount() {
            return -1;
        }

        @Override
        public boolean isVerified() {
            return false;
        }
    }

    static StreamInfoItemsCollector collect(final int serviceId, final JsonArray results) {
        final StreamInfoItemsCollector collector = new StreamInfoItemsCollector(serviceId);
        for (int i = 0; i < results.size(); i++) {
            final JsonObject r = results.getObject(i);
            if ("video_episode".equals(r.getString("type", "video_episode"))
                    && r.getString("slug") != null) {
                collector.commit(new EpisodeItem(r));
            }
        }
        return collector;
    }

    /** A paginated content API list: the page id is the API's absolute "next" URL. */
    @Nullable
    static Page next(final JsonObject list) {
        final String n = list.getString("next");
        return n == null || n.isEmpty() ? null : new Page(n);
    }

    // ---- link handlers ----

    static final String HOST = "^https?://(?:www\\.|beta\\.)?(?:nebula\\.tv|nebula\\.app"
            + "|watchnebula\\.com)";

    static final class StreamLH extends LinkHandlerFactory {
        private static final Pattern VIDEO = Pattern.compile(HOST
                + "/(?:videos|embed)/([A-Za-z0-9_-]+)");

        @Override
        public String getId(final String url) throws ParsingException {
            final Matcher m = VIDEO.matcher(url);
            if (m.find()) {
                return m.group(1);
            }
            throw new ParsingException("Not a Nebula video: " + url);
        }

        @Override
        public String getUrl(final String id) {
            return videoUrl(id);
        }

        @Override
        public boolean onAcceptUrl(final String url) {
            return VIDEO.matcher(url).find();
        }
    }

    static final class ChannelLH extends ListLinkHandlerFactory {
        private static final Pattern CHANNEL = Pattern.compile(HOST
                + "/(?!(?:videos|embed|myshows|library|search|login|join|account|settings"
                + "|explore|browse|featured|categories|category|podcasts|classes|terms"
                + "|privacy|faq|help|gift|redeem|auth|jobs|press|about|beta|app|sign-up"
                + "|signup|subscribe)(?:[/?#]|$))([A-Za-z0-9_-]+)/?(?:videos/?)?(?:[?#]|$)");

        @Override
        public String getId(final String url) throws ParsingException {
            final Matcher m = CHANNEL.matcher(url);
            if (m.find()) {
                return m.group(1);
            }
            throw new ParsingException("Not a Nebula channel: " + url);
        }

        @Override
        public String getUrl(final String id, final List<FilterItem> c, final List<FilterItem> s) {
            return channelUrl(id);
        }

        @Override
        public boolean onAcceptUrl(final String url) {
            return CHANNEL.matcher(url).find();
        }
    }

    static final class SearchLH extends SearchQueryHandlerFactory {
        private static final String PREFIX = BASE + "/search?q=";

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
        static final String LATEST = "Latest";
        static final String LATEST_URL = BASE + "/videos";

        @Override
        public String getUrl(final String id, final List<FilterItem> c, final List<FilterItem> s) {
            return LATEST_URL;
        }

        @Override
        public String getId(final String url) {
            return LATEST;
        }

        @Override
        public boolean onAcceptUrl(final String url) {
            return url.replaceAll("/+$", "").equals(LATEST_URL);
        }
    }

    // ---- extractors ----

    static final class EpisodeExtractor extends StreamExtractor {
        private JsonObject ep;
        private String hlsUrl;
        private String resolution = "1080p";

        EpisodeExtractor(final StreamingService s, final LinkHandler h) {
            super(s, h);
        }

        @Override
        public void onFetchPage(@Nonnull final Downloader d)
                throws IOException, ExtractionException {
            ep = api("/content/videos/" + enc(getId()) + "/");
            final String contentId = ep.getString("id");
            if (contentId == null) {
                throw new ContentNotAvailableException("Nebula video not found");
            }
            final boolean free = isFreeSample(ep);
            final String token;
            if (isSignedIn()) {
                token = userToken;
            } else if (free) {
                token = anonymous(false);
            } else {
                throw new PaidContentException("Sign in to Nebula to watch");
            }
            final String manifest = CONTENT + "/video_episodes/" + contentId + "/manifest.m3u8";
            Response r = manifestRequest(d, manifest, token);
            if (!isSignedIn() && (r.responseCode() == 401 || r.responseCode() == 403)) {
                // the cached anonymous JWT may have expired
                r = manifestRequest(d, manifest, anonymous(true));
            }
            if (r.responseCode() == 401 || r.responseCode() == 403) {
                if (isSignedIn()) {
                    throw new PaidContentException(r.responseCode() == 401
                            ? "Your Nebula plan doesn't include this video"
                            : "Nebula sign-in expired: sign in again");
                }
                throw new PaidContentException("Sign in to Nebula to watch");
            }
            final String location = r.getHeader("Location");
            if (r.responseCode() / 100 == 3 && location != null) {
                hlsUrl = location;
                // fetch the master playlist (no auth: the CDN token is in the path)
                r = d.get(hlsUrl, headers(null));
            } else {
                hlsUrl = r.latestUrl() != null ? r.latestUrl() : manifest;
            }
            final String body = r.responseBody();
            if (!body.startsWith("#EXTM3U")) {
                throw new ContentNotAvailableException("Nebula did not provide this video (HTTP "
                        + r.responseCode() + ")");
            }
            int best = 0;
            final Matcher m = Pattern.compile("RESOLUTION=\\d+x(\\d+)").matcher(body);
            while (m.find()) {
                best = Math.max(best, Integer.parseInt(m.group(1)));
            }
            if (best > 0) {
                resolution = best + "p";
            }
        }

        private Response manifestRequest(final Downloader d, final String url, final String token)
                throws IOException, ExtractionException {
            return d.execute(Request.newBuilder().get(url).headers(headers(token))
                    .followRedirects(false).build());
        }

        private static synchronized String anonymous(final boolean refresh)
                throws IOException, ExtractionException {
            if (anonToken == null || refresh) {
                anonToken = authorize(null);
            }
            return anonToken;
        }

        @Nonnull
        @Override
        public String getName() {
            return ep.getString("title", "Nebula video");
        }

        @Override
        public String getThumbnailUrl() {
            return img(ep.getObject("images"), "thumbnail");
        }

        @Override
        public String getUploaderUrl() {
            final String s = ep.getString("channel_slug");
            return s == null ? "" : channelUrl(s);
        }

        @Override
        public String getUploaderName() {
            return ep.getString("channel_title", "");
        }

        @Override
        public String getUploaderAvatarUrl() {
            return img(ep.getObject("images"), "channel_avatar");
        }

        @Nonnull
        @Override
        public Description getDescription() {
            return new Description(ep.getString("description", ""), Description.PLAIN_TEXT);
        }

        @Override
        public long getLength() {
            return ep.getLong("duration", 0);
        }

        @Override
        public String getTextualUploadDate() {
            return ep.getString("published_at");
        }

        @Override
        public DateWrapper getUploadDate() {
            return date(ep.getString("published_at"));
        }

        @Nonnull
        @Override
        public String getHlsUrl() {
            return hlsUrl;
        }

        @Nonnull
        @Override
        public Map<String, String> getManifestRequestHeaders() {
            return mediaHeaders();
        }

        @Override
        public List<AudioStream> getAudioStreams() {
            return Collections.emptyList();
        }

        @Override
        public List<VideoStream> getVideoStreams() {
            return Collections.singletonList(new VideoStream.Builder()
                    .setId("nebula-hls")
                    .setContent(hlsUrl, true).setIsVideoOnly(false)
                    .setMediaFormat(MediaFormat.MPEG_4)
                    .setDeliveryMethod(DeliveryMethod.HLS)
                    .setResolution(resolution)
                    .setRequestHeaders(mediaHeaders())
                    .build());
        }

        @Override
        public List<VideoStream> getVideoOnlyStreams() {
            return Collections.emptyList();
        }

        @Override
        public StreamType getStreamType() {
            return StreamType.VIDEO_STREAM;
        }

        /** The channel's latest videos. */
        @Nullable
        @Override
        public InfoItemsCollector<? extends InfoItem, ? extends InfoItemExtractor>
        getRelatedItems() throws IOException, ExtractionException {
            final String channelId = ep.getString("channel_id");
            if (channelId == null) {
                return null;
            }
            final JsonArray list = api("/video_channels/" + channelId
                    + "/video_episodes/?ordering=-published_at&page_size=12")
                    .getArray("results");
            final StreamInfoItemsCollector c = new StreamInfoItemsCollector(getServiceId());
            for (int i = 0; i < list.size(); i++) {
                final JsonObject o = list.getObject(i);
                if (!getId().equals(o.getString("slug"))) {
                    c.commit(new EpisodeItem(o));
                }
            }
            return c;
        }
    }

    /** Headers for the starlight.nebula.tv CDN (the access token is in the URL path). */
    static Map<String, String> mediaHeaders() {
        final Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", UA);
        h.put("Origin", BASE);
        h.put("Referer", BASE + "/");
        return h;
    }

    /** A channel's videos, newest first. */
    static final class NebulaChannelExtractor extends ChannelExtractor {
        private JsonObject channel;
        private JsonObject first;

        NebulaChannelExtractor(final StreamingService s, final ListLinkHandler h) {
            super(s, h);
        }

        @Override
        public void onFetchPage(@Nonnull final Downloader d)
                throws IOException, ExtractionException {
            channel = api("/content/" + enc(getId()) + "/");
            final String id = channel.getString("id", getId());
            first = api("/video_channels/" + id + "/video_episodes/?ordering=-published_at"
                    + "&page_size=" + PAGE_SIZE);
        }

        @Nonnull
        @Override
        public String getName() {
            return channel.getString("title", "Nebula");
        }

        @Override
        public String getAvatarUrl() {
            return img(channel.getObject("images"), "avatar");
        }

        @Override
        public String getBannerUrl() {
            return img(channel.getObject("images"), "banner");
        }

        @Override
        public long getSubscriberCount() {
            return -1;
        }

        @Override
        public String getDescription() {
            return channel.getString("description", "");
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

        @Nonnull
        @Override
        public InfoItemsPage<StreamInfoItem> getInitialPage() {
            return new InfoItemsPage<>(collect(getServiceId(), first.getArray("results")),
                    next(first));
        }

        @Override
        public InfoItemsPage<StreamInfoItem> getPage(final Page p)
                throws IOException, ExtractionException {
            final JsonObject list = api(p.getUrl());
            return new InfoItemsPage<>(collect(getServiceId(), list.getArray("results")),
                    next(list));
        }
    }

    /** Front page: the newest videos across Nebula. */
    static final class Latest extends KioskExtractor<StreamInfoItem> {
        private JsonObject first;

        Latest(final StreamingService s, final ListLinkHandler h, final String id) {
            super(s, h, id);
        }

        @Override
        public void onFetchPage(@Nonnull final Downloader d)
                throws IOException, ExtractionException {
            first = api("/video_episodes/?ordering=-published_at&page_size=" + PAGE_SIZE);
        }

        @Nonnull
        @Override
        public String getName() {
            return getId();
        }

        @Nonnull
        @Override
        public InfoItemsPage<StreamInfoItem> getInitialPage() {
            return new InfoItemsPage<>(collect(getServiceId(), first.getArray("results")),
                    next(first));
        }

        @Override
        public InfoItemsPage<StreamInfoItem> getPage(final Page p)
                throws IOException, ExtractionException {
            final JsonObject list = api(p.getUrl());
            return new InfoItemsPage<>(collect(getServiceId(), list.getArray("results")),
                    next(list));
        }
    }

    /** Video search (paginated); matching channels are added on the first page. */
    static final class Search extends SearchExtractor {
        private JsonObject videos;
        private JsonArray channels;

        Search(final StreamingService s, final SearchQueryHandler h) {
            super(s, h);
        }

        @Override
        public void onFetchPage(@Nonnull final Downloader d)
                throws IOException, ExtractionException {
            final String q = enc(getSearchString());
            videos = api("/video_episodes/search/?q=" + q);
            try {
                channels = api("/video_channels/search/?q=" + q).getArray("results");
            } catch (final Exception e) {
                channels = new JsonArray();
            }
        }

        private InfoItemsPage<InfoItem> page(final JsonObject list,
                                             @Nullable final JsonArray chans) {
            final MultiInfoItemsCollector c = new MultiInfoItemsCollector(getServiceId());
            if (chans != null) {
                for (int i = 0; i < chans.size() && i < 5; i++) {
                    c.commit(new ChannelItem(chans.getObject(i)));
                }
            }
            final JsonArray r = list.getArray("results");
            for (int i = 0; i < r.size(); i++) {
                final JsonObject o = r.getObject(i);
                if ("video_episode".equals(o.getString("type", "video_episode"))
                        && o.getString("slug") != null) {
                    c.commit(new EpisodeItem(o));
                }
            }
            return new InfoItemsPage<>(c, next(list));
        }

        @Override
        protected InfoItemsPage<InfoItem> getInitialPageInternal() {
            return page(videos, channels);
        }

        @Override
        protected InfoItemsPage<InfoItem> getPageInternal(final Page p)
                throws IOException, ExtractionException {
            return page(api(p.getUrl()), null);
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
        return null;
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
            list.addKioskEntry((s, url, kid) -> new Latest(NebulaService.this,
                    lh.fromUrl(url), kid), lh, KioskLH.LATEST);
            list.setDefaultKiosk(KioskLH.LATEST);
        } catch (final Exception e) {
            throw new ExtractionException(e);
        }
        return list;
    }

    @Override
    public ChannelExtractor getChannelExtractor(final ListLinkHandler h) {
        return new NebulaChannelExtractor(this, h);
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
        return new EpisodeExtractor(this, h);
    }

    @Override
    public CommentsExtractor getCommentsExtractor(final ListLinkHandler h) {
        return null;
    }
}
