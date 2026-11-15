package org.schabi.newpipe.extractor.services.rumble;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
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
import org.schabi.newpipe.extractor.stream.SubtitlesStream;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.extractor.subscription.SubscriptionExtractor;
import org.schabi.newpipe.extractor.suggestion.SuggestionExtractor;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
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

import static org.schabi.newpipe.extractor.StreamingService.ServiceInfo.MediaCapability.LIVE;
import static org.schabi.newpipe.extractor.StreamingService.ServiceInfo.MediaCapability.VIDEO;

/**
 * PipeVR: Rumble. Anonymous. Lists come from rumble.com's HTML pages (parsed with Jsoup; the
 * channel videos tab embeds its list as JSON), playback from the embed player's JSON
 * (rumble.com/embedJS/u3/?request=video&ver=2&v={embedId}), which gives HLS and progressive
 * MP4 for videos and HLS for livestreams. Endpoint reference: yt-dlp's Rumble extractor
 * (Unlicense).
 */
public class RumbleService extends StreamingService {
    static final String BASE = "https://rumble.com";
    private static final String UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";

    static final String KIOSK_TRENDING = "Trending";
    static final String KIOSK_WEEK = "Top this week";
    static final String KIOSK_PICKS = "Editor picks";

    public RumbleService(final int id) {
        super(id, "Rumble", Arrays.asList(VIDEO, LIVE));
    }

    // ---- http ----

    /** Headers for the player (media and HLS playlists). */
    static Map<String, String> mediaHeaders() {
        final Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", UA);
        h.put("Referer", BASE + "/");
        return h;
    }

    /** GET a rumble.com page. Cloudflare lets requests through when they look like a browser. */
    static String get(final String url, final boolean json)
            throws IOException, ExtractionException {
        final Map<String, List<String>> h = new HashMap<>();
        h.put("User-Agent", Collections.singletonList(UA));
        h.put("Accept", Collections.singletonList(json ? "application/json, */*"
                : "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"));
        h.put("Accept-Language", Collections.singletonList("en-US,en;q=0.9"));
        Response r = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            r = NewPipe.getDownloader().get(url, h);
            if (!isChallenge(r)) {
                break;
            }
        }
        if (isChallenge(r)) {
            // PipeVR: retry through the app's browser engine, which passes Cloudflare's check
            final ChallengeFetcher fetcher = challengeFetcher;
            if (fetcher != null) {
                final String body = fetcher.fetch(url, json);
                if (body != null && !body.contains("Just a moment")) {
                    return body;
                }
            }
            throw new ContentNotAvailableException("Rumble's bot check blocked the request; "
                    + "try again later");
        }
        if (r.responseCode() == 404 || r.responseCode() == 410) {
            throw new ContentNotAvailableException("Not found on Rumble");
        }
        if (r.responseCode() >= 400) {
            throw new ParsingException("Rumble answered HTTP " + r.responseCode());
        }
        return r.responseBody();
    }

    /**
     * PipeVR: fetches a URL with a real browser engine (an Android WebView) when Rumble's
     * Cloudflare check refuses the normal HTTP client. Returns the page HTML, or for JSON the
     * response text; null on failure.
     */
    public interface ChallengeFetcher {
        @Nullable
        String fetch(String url, boolean json) throws IOException;
    }

    @Nullable
    private static volatile ChallengeFetcher challengeFetcher;

    public static void setChallengeFetcher(@Nullable final ChallengeFetcher fetcher) {
        challengeFetcher = fetcher;
    }

    private static boolean isChallenge(final Response r) {
        return (r.responseCode() == 403 || r.responseCode() == 503)
                && r.responseBody().contains("Just a moment");
    }

    static Document page(final String url) throws IOException, ExtractionException {
        return Jsoup.parse(get(url, false), url);
    }

    static String enc(final String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    static String abs(@Nullable final String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        if (url.startsWith("//")) {
            return "https:" + url;
        }
        return url.startsWith("/") ? BASE + url : url;
    }

    /** Drops the tracking query (?e9s=...) from rumble.com links. */
    static String clean(@Nullable final String url) {
        final String a = abs(url);
        final int q = a.indexOf('?');
        return q < 0 ? a : a.substring(0, q);
    }

    /** "40.8K", "2.43K watching", "3.67M Followers", "476,523". */
    static long count(@Nullable final String text) {
        if (text == null) {
            return -1;
        }
        final Matcher m = Pattern.compile("([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([KMB])?",
                Pattern.CASE_INSENSITIVE).matcher(text.replace(' ', ' '));
        if (!m.find()) {
            return -1;
        }
        try {
            double v = Double.parseDouble(m.group(1).replace(",", ""));
            if (m.group(2) != null) {
                switch (m.group(2).toUpperCase(Locale.ROOT)) {
                    case "K": v *= 1e3; break;
                    case "M": v *= 1e6; break;
                    default: v *= 1e9; break;
                }
            }
            return (long) v;
        } catch (final NumberFormatException e) {
            return -1;
        }
    }

    /** "1:09:29" / "4:12" to seconds. */
    static long duration(@Nullable final String text) {
        if (text == null || !text.trim().matches("[0-9:]+")) {
            return -1;
        }
        long s = 0;
        for (final String part : text.trim().split(":")) {
            if (!part.isEmpty()) {
                s = s * 60 + Long.parseLong(part);
            }
        }
        return s;
    }

    @Nullable
    static DateWrapper date(@Nullable final String iso) {
        if (iso == null || iso.isEmpty()) {
            return null;
        }
        try {
            return new DateWrapper(OffsetDateTime.parse(iso.trim()));
        } catch (final Exception e) {
            return null;
        }
    }

    /** Avatars are CSS backgrounds: "i.user-image--img--id-HASH { background-image: url(..) }"
     *  and ".video-item--by-a--ID::before { background-image: url("..") }". */
    static Map<String, String> cssImages(final String html) {
        final Map<String, String> map = new HashMap<>();
        final Matcher m = Pattern.compile("\\.((?:user-image--img--id-|video-item--by-a--)"
                + "[\\w-]+)(?:::before)?\\s*\\{\\s*background-image:\\s*url\\(\"?([^\")]+)\"?\\)")
                .matcher(html);
        while (m.find()) {
            map.put(m.group(1), m.group(2));
        }
        return map;
    }

    @Nullable
    static String cssImage(final Map<String, String> css, @Nullable final Element el,
                           final String prefix) {
        if (el == null) {
            return null;
        }
        for (final String c : el.classNames()) {
            if (c.startsWith(prefix) && css.containsKey(c)) {
                return css.get(c);
            }
        }
        return null;
    }

    // ---- list items ----

    /** An item of a channel's videos tab (JSON in rum-videos-grid). */
    static final class JsonItem implements StreamInfoItemExtractor {
        private final JsonObject o;

        JsonItem(final JsonObject o) {
            this.o = o;
        }

        @Override
        public String getName() {
            return Parser.unescapeEntities(o.getString("title", ""), false);
        }

        @Override
        public String getUrl() {
            return clean(o.getString("url", o.getString("relative_url")));
        }

        @Override
        public String getThumbnailUrl() {
            return o.getString("thumb");
        }

        @Override
        public StreamType getStreamType() {
            return o.getBoolean("live", false)
                    && !"ended".equals(o.getString("livestream_status"))
                    ? StreamType.LIVE_STREAM : StreamType.VIDEO_STREAM;
        }

        @Override
        public long getDuration() {
            return o.getLong("duration", -1);
        }

        @Override
        public long getViewCount() {
            return o.getLong("views", o.getObject("video_stats").getLong("rumble_plays", -1));
        }

        @Nonnull
        @Override
        public String getUploaderName() {
            return o.getObject("by").getString("name", "");
        }

        @Override
        public String getUploaderUrl() {
            return clean(o.getObject("by").getString("url"));
        }

        @Override
        public String getUploaderAvatarUrl() {
            return o.getObject("by").getString("thumb");
        }

        @Override
        public boolean isUploaderVerified() {
            return o.getObject("by").getBoolean("verified_badge", false);
        }

        @Override
        public String getTextualUploadDate() {
            return o.getString("upload_date");
        }

        @Override
        public DateWrapper getUploadDate() {
            return date(o.getString("upload_date"));
        }

        @Override
        public boolean isShortFormContent() {
            return o.getBoolean("is_short", false);
        }
    }

    /** "article.video-item" (browse and search pages). */
    static final class VideoItem implements StreamInfoItemExtractor {
        private final Element e;
        private final Map<String, String> css;

        VideoItem(final Element e, final Map<String, String> css) {
            this.e = e;
            this.css = css;
        }

        @Override
        public String getName() {
            final Element t = e.selectFirst(".video-item--title");
            return t != null ? t.text() : "";
        }

        @Override
        public String getUrl() {
            final Element a = e.selectFirst("a.video-item--a");
            return a == null ? "" : clean(a.attr("href"));
        }

        @Override
        public String getThumbnailUrl() {
            final Element img = e.selectFirst("img.video-item--img");
            return img == null ? null : img.absUrl("src");
        }

        @Override
        public StreamType getStreamType() {
            return e.selectFirst(".video-item--live") != null ? StreamType.LIVE_STREAM
                    : StreamType.VIDEO_STREAM;
        }

        @Override
        public long getDuration() {
            final Element d = e.selectFirst(".video-item--duration");
            return d == null ? -1 : duration(d.attr("data-value"));
        }

        @Override
        public long getViewCount() {
            final Element v = e.selectFirst(".video-item--views");
            if (v != null) {
                return count(v.text());
            }
            final Element w = e.selectFirst(".video-item--watching");
            return w == null ? -1 : count(w.text());
        }

        private Element by() {
            return e.selectFirst("a.video-item--by-a");
        }

        @Nonnull
        @Override
        public String getUploaderName() {
            final Element by = by();
            if (by == null) {
                return "";
            }
            final Element n = by.selectFirst(".ellipsis-1");
            return n != null ? n.ownText() : by.ownText();
        }

        @Override
        public String getUploaderUrl() {
            final Element by = by();
            return by == null ? "" : clean(by.attr("href"));
        }

        @Override
        public String getUploaderAvatarUrl() {
            return cssImage(css, by(), "video-item--by-a--");
        }

        @Override
        public boolean isUploaderVerified() {
            return e.selectFirst(".video-item--by-verified") != null;
        }

        @Override
        public String getTextualUploadDate() {
            final Element t = e.selectFirst("time.video-item--time");
            return t == null ? null : t.attr("datetime");
        }

        @Override
        public DateWrapper getUploadDate() {
            return date(getTextualUploadDate());
        }
    }

    /** "div.videostream" (editor picks, channel home). */
    static final class StreamCard implements StreamInfoItemExtractor {
        private final Element e;

        StreamCard(final Element e) {
            this.e = e;
        }

        @Override
        public String getName() {
            final Element t = e.selectFirst(".thumbnail__title");
            return t == null ? "" : (t.hasAttr("title") ? t.attr("title") : t.text());
        }

        @Override
        public String getUrl() {
            Element a = e.selectFirst("a.videostream__link");
            if (a == null) {
                a = e.selectFirst("a.title__link");
            }
            return a == null ? "" : clean(a.attr("href"));
        }

        @Override
        public String getThumbnailUrl() {
            final Element img = e.selectFirst("img.thumbnail__image");
            return img == null ? null : img.absUrl("src");
        }

        @Override
        public StreamType getStreamType() {
            return e.selectFirst(".videostream__status--live") != null
                    ? StreamType.LIVE_STREAM : StreamType.VIDEO_STREAM;
        }

        @Override
        public long getDuration() {
            final Element d = e.selectFirst(".videostream__status--duration");
            return d == null ? -1 : duration(d.text());
        }

        @Override
        public long getViewCount() {
            final Element v = e.selectFirst(".videostream__views");
            if (v == null) {
                return -1;
            }
            final long n = count(v.attr("data-views"));
            return n >= 0 ? n : count(v.text());
        }

        @Nonnull
        @Override
        public String getUploaderName() {
            final Element n = e.selectFirst(".channel__name");
            return n == null ? "" : (n.hasAttr("title") ? n.attr("title") : n.text());
        }

        @Override
        public String getUploaderUrl() {
            final Element a = e.selectFirst("a.channel__link");
            return a == null ? "" : clean(a.attr("href"));
        }

        @Override
        public String getUploaderAvatarUrl() {
            final Element img = e.selectFirst("img.channel__image");
            return img == null ? null : img.absUrl("src");
        }

        @Override
        public String getTextualUploadDate() {
            final Element t = e.selectFirst("time");
            return t == null ? null : t.attr("datetime");
        }

        @Override
        public DateWrapper getUploadDate() {
            return date(getTextualUploadDate());
        }
    }

    /** One page of any Rumble listing: its stream items and the next page link. */
    static final class Listing {
        final List<StreamInfoItemExtractor> items = new ArrayList<>();
        @Nullable
        String next;
        final Document doc;
        final String html;

        Listing(final String url, final boolean skipFeatured)
                throws IOException, ExtractionException {
            html = get(url, false);
            doc = Jsoup.parse(html, url);
            for (final Element grid : doc.select("rum-videos-grid > script[type=application/json]")) {
                try {
                    final JsonArray arr = JsonParser.object().from(grid.data()).getArray("items");
                    for (int i = 0; i < arr.size(); i++) {
                        final JsonObject o = arr.getObject(i);
                        if ("video".equals(o.getString("object_type", "video"))) {
                            items.add(new JsonItem(o));
                        }
                    }
                } catch (final Exception ignored) {
                    // not a list
                }
            }
            final Map<String, String> css = cssImages(html);
            for (final Element e : doc.select("article.video-item")) {
                items.add(new VideoItem(e, css));
            }
            for (final Element e : doc.select("div.videostream")) {
                if (skipFeatured && e.hasClass("videostream--featured")) {
                    continue;
                }
                items.add(new StreamCard(e));
            }
            final Element n = doc.selectFirst("link[rel=next]");
            next = n == null ? null : n.absUrl("href");
            if (next != null && next.isEmpty()) {
                next = null;
            }
        }

        Page nextPage() {
            return next == null || items.isEmpty() ? null : new Page(next);
        }

        StreamInfoItemsCollector collect(final int serviceId) {
            final StreamInfoItemsCollector c = new StreamInfoItemsCollector(serviceId);
            for (final StreamInfoItemExtractor i : items) {
                c.commit(i);
            }
            return c;
        }
    }

    // ---- link handlers ----

    static final class StreamLH extends LinkHandlerFactory {
        private static final Pattern PAGE = Pattern.compile(
                "^https?://(?:www\\.|m\\.)?rumble\\.com/(v[a-z0-9]{4,})(?:-[^/?#]*)?(?:\\.html)?"
                        + "/?(?:[?#].*)?$", Pattern.CASE_INSENSITIVE);
        private static final Pattern SHARE = Pattern.compile(
                "^https?://(?:www\\.|m\\.)?rumble\\.com/share/(v[a-z0-9]{4,})",
                Pattern.CASE_INSENSITIVE);
        private static final Pattern EMBED = Pattern.compile(
                "^https?://(?:www\\.|m\\.)?rumble\\.com/embed/(?:[a-z0-9]+\\.)?(v[a-z0-9]{3,})",
                Pattern.CASE_INSENSITIVE);

        @Override
        public String getId(final String url) throws ParsingException {
            Matcher m = EMBED.matcher(url);
            if (m.find()) {
                return "embed:" + m.group(1);
            }
            m = SHARE.matcher(url);
            if (m.find()) {
                return m.group(1);
            }
            m = PAGE.matcher(url);
            if (m.find() && !m.group(1).equalsIgnoreCase("videos")) {
                return m.group(1);
            }
            throw new ParsingException("Not a Rumble video: " + url);
        }

        @Override
        public String getUrl(final String id) {
            return id.startsWith("embed:") ? BASE + "/embed/" + id.substring(6) + "/"
                    : BASE + "/" + id;
        }

        @Override
        public boolean onAcceptUrl(final String url) {
            try {
                getId(url);
                return true;
            } catch (final ParsingException e) {
                return false;
            }
        }
    }

    static final class ChannelLH extends ListLinkHandlerFactory {
        private static final Pattern CHANNEL = Pattern.compile(
                "^https?://(?:www\\.|m\\.)?rumble\\.com/(c|user)/([^/?#]+)",
                Pattern.CASE_INSENSITIVE);

        @Override
        public String getId(final String url) throws ParsingException {
            final Matcher m = CHANNEL.matcher(url);
            if (m.find()) {
                return m.group(1).toLowerCase(Locale.ROOT) + "/" + m.group(2);
            }
            throw new ParsingException("Not a Rumble channel: " + url);
        }

        @Override
        public String getUrl(final String id, final List<FilterItem> c, final List<FilterItem> s) {
            return BASE + "/" + id;
        }

        @Override
        public boolean onAcceptUrl(final String url) {
            return CHANNEL.matcher(url).find();
        }
    }

    static final class SearchLH extends SearchQueryHandlerFactory {
        private static final String PREFIX = BASE + "/search/all?q=";

        @Override
        public String getUrl(final String q, final List<FilterItem> c, final List<FilterItem> s) {
            return PREFIX + enc(q);
        }

        @Override
        public String getSearchString(final String url) {
            final Matcher m = Pattern.compile("[?&]q=([^&#]*)").matcher(url);
            return m.find() ? URLDecoder.decode(m.group(1), StandardCharsets.UTF_8) : "";
        }
    }

    static final class KioskLH extends ListLinkHandlerFactory {
        static final Map<String, String> URLS = new LinkedHashMap<>();

        static {
            URLS.put(KIOSK_TRENDING, BASE + "/videos?sort=views&date=today");
            URLS.put(KIOSK_WEEK, BASE + "/videos?sort=views&date=this-week");
            URLS.put(KIOSK_PICKS, BASE + "/editor-picks");
        }

        @Override
        public String getUrl(final String id, final List<FilterItem> c, final List<FilterItem> s) {
            final String u = URLS.get(id);
            return u != null ? u : URLS.get(KIOSK_TRENDING);
        }

        @Override
        public String getId(final String url) {
            for (final Map.Entry<String, String> e : URLS.entrySet()) {
                if (e.getValue().equals(url)) {
                    return e.getKey();
                }
            }
            return KIOSK_TRENDING;
        }

        @Override
        public boolean onAcceptUrl(final String url) {
            return URLS.containsValue(url);
        }
    }

    // ---- extractors ----

    /** A video or livestream: the page for metadata, the embed JSON for streams. */
    static final class VideoExtractor extends StreamExtractor {
        private Document doc;
        private String html = "";
        private JsonObject embed;
        private JsonObject ld = new JsonObject();
        private int live;
        private final List<VideoStream> videos = new ArrayList<>();
        private final List<SubtitlesStream> subtitles = new ArrayList<>();
        private String hlsUrl = "";

        VideoExtractor(final StreamingService s, final LinkHandler h) {
            super(s, h);
        }

        private static JsonObject embedJson(final String embedId)
                throws IOException, ExtractionException {
            final String body = get(BASE + "/embedJS/u3/?request=video&ver=2&v="
                    + enc(embedId), true);
            try {
                final Object o = JsonParser.any().from(body);
                if (o instanceof JsonObject) {
                    return (JsonObject) o;
                }
            } catch (final Exception e) {
                throw new ParsingException("Unexpected Rumble player response", e);
            }
            throw new ContentNotAvailableException("Rumble did not return this video");
        }

        @Override
        public void onFetchPage(@Nonnull final Downloader d)
                throws IOException, ExtractionException {
            String embedId = null;
            String pageUrl = getUrl();
            if (getId().startsWith("embed:")) {
                embedId = getId().substring(6);
                embed = embedJson(embedId);
                pageUrl = embed.getString("l") == null ? null : abs(embed.getString("l"));
            }
            if (pageUrl != null) {
                try {
                    html = get(pageUrl, false);
                    doc = Jsoup.parse(html, pageUrl);
                } catch (final IOException | ExtractionException e) {
                    if (embed == null) {
                        throw e;
                    }
                }
            }
            if (doc != null) {
                for (final Element s : doc.select("script[type=application/ld+json]")) {
                    try {
                        final JsonArray arr = JsonParser.array().from(s.data());
                        for (int i = 0; i < arr.size(); i++) {
                            if ("VideoObject".equals(arr.getObject(i).getString("@type"))) {
                                ld = arr.getObject(i);
                            }
                        }
                    } catch (final Exception ignored) {
                        try {
                            final JsonObject o = JsonParser.object().from(s.data());
                            if ("VideoObject".equals(o.getString("@type"))) {
                                ld = o;
                            }
                        } catch (final Exception ignored2) {
                            // other ld+json
                        }
                    }
                }
                if (embedId == null) {
                    final Matcher m = Pattern.compile("/embed/(v[a-z0-9]+)")
                            .matcher(ld.getString("embedUrl", ""));
                    if (m.find()) {
                        embedId = m.group(1);
                    } else {
                        final Matcher m2 = Pattern.compile("\"embedUrl\"\\s*:\\s*\"https?:\\\\?/"
                                + "\\\\?/rumble\\.com\\\\?/embed\\\\?/(v[a-z0-9]+)").matcher(html);
                        if (m2.find()) {
                            embedId = m2.group(1);
                        }
                    }
                }
            }
            if (embed == null) {
                if (embedId == null) {
                    throw new ContentNotAvailableException("No Rumble player on this page");
                }
                embed = embedJson(embedId);
            }
            live = embed.getInt("live", 0);
            buildStreams();
            if (videos.isEmpty() && hlsUrl.isEmpty()) {
                if (live == 1 || embed.getBoolean("live_placeholder", false)) {
                    throw new ContentNotAvailableException("This Rumble livestream hasn't "
                            + "started yet");
                }
                throw new ContentNotAvailableException("Rumble did not provide streams for "
                        + "this video");
            }
        }

        private static String label(final String key, final JsonObject meta) {
            if (key.matches("\\d+")) {
                return key + "p";
            }
            final int h = meta.getInt("h", 0);
            return h > 0 ? h + "p" : "auto";
        }

        private void buildStreams() {
            final JsonObject ua = embed.getObject("ua");
            final Map<String, String> headers = mediaHeaders();
            int n = 0;
            // progressive files
            for (final String kind : new String[]{"mp4", "webm"}) {
                final Object group = ua.get(kind);
                if (!(group instanceof JsonObject)) {
                    continue;
                }
                for (final Map.Entry<String, Object> e : ((JsonObject) group).entrySet()) {
                    if (!(e.getValue() instanceof JsonObject)) {
                        continue;
                    }
                    final JsonObject f = (JsonObject) e.getValue();
                    final String url = f.getString("url");
                    if (url == null || url.isEmpty()) {
                        continue;
                    }
                    videos.add(new VideoStream.Builder()
                            .setId("rumble-" + kind + "-" + (n++))
                            .setContent(url, true).setIsVideoOnly(false)
                            .setMediaFormat("webm".equals(kind) ? MediaFormat.WEBM
                                    : MediaFormat.MPEG_4)
                            .setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
                            .setResolution(label(e.getKey(), f.getObject("meta")))
                            .setRequestHeaders(headers)
                            .build());
                }
            }
            // HLS renditions
            final Object hls = ua.get("hls");
            if (hls instanceof JsonObject) {
                for (final Map.Entry<String, Object> e : ((JsonObject) hls).entrySet()) {
                    if (!(e.getValue() instanceof JsonObject)) {
                        continue;
                    }
                    final JsonObject f = (JsonObject) e.getValue();
                    final String url = f.getString("url");
                    if (url == null || url.isEmpty()) {
                        continue;
                    }
                    if ("auto".equals(e.getKey())) {
                        hlsUrl = url;
                        continue;
                    }
                    videos.add(new VideoStream.Builder()
                            .setId("rumble-hls-" + (n++))
                            .setContent(url, true).setIsVideoOnly(false)
                            .setMediaFormat(MediaFormat.MPEG_4)
                            .setDeliveryMethod(DeliveryMethod.HLS)
                            .setResolution(label(e.getKey(), f.getObject("meta")))
                            .setRequestHeaders(headers)
                            .build());
                }
            }
            if (hlsUrl.isEmpty()) {
                final Object u = embed.get("u");
                if (u instanceof JsonObject) {
                    hlsUrl = ((JsonObject) u).getObject("hls").getString("url", "");
                }
            }
            if (videos.isEmpty() && !hlsUrl.isEmpty()) {
                videos.add(new VideoStream.Builder()
                        .setId("rumble-hls-auto")
                        .setContent(hlsUrl, true).setIsVideoOnly(false)
                        .setMediaFormat(MediaFormat.MPEG_4)
                        .setDeliveryMethod(DeliveryMethod.HLS)
                        .setResolution(embed.getInt("h", 0) > 0 ? embed.getInt("h", 0) + "p"
                                : "720p")
                        .setRequestHeaders(headers)
                        .build());
            }
            // subtitles
            final Object cc = embed.get("cc");
            if (cc instanceof JsonObject) {
                for (final Map.Entry<String, Object> e : ((JsonObject) cc).entrySet()) {
                    if (!(e.getValue() instanceof JsonObject)) {
                        continue;
                    }
                    final JsonObject t = (JsonObject) e.getValue();
                    final String path = t.getString("path");
                    if (path == null || path.isEmpty()) {
                        continue;
                    }
                    final String lang = e.getKey();
                    subtitles.add(new SubtitlesStream.Builder()
                            .setContent(abs(path), true)
                            .setMediaFormat(MediaFormat.VTT)
                            .setLanguageCode(lang.replace("-auto", ""))
                            .setAutoGenerated(lang.contains("auto"))
                            .setRequestHeaders(headers)
                            .build());
                }
            }
        }

        @Nonnull
        @Override
        public String getName() {
            final String t = embed.getString("title");
            if (t != null && !t.isEmpty()) {
                return Parser.unescapeEntities(t, false);
            }
            return ld.getString("name", "Rumble video");
        }

        @Override
        public String getThumbnailUrl() {
            final String i = embed.getString("i");
            return i != null ? i : ld.getString("thumbnailUrl");
        }

        @Override
        public String getUploaderUrl() {
            String u = embed.getObject("author").getString("url");
            if ((u == null || u.isEmpty()) && doc != null) {
                final Element a = doc.selectFirst("a.media-by--a");
                u = a == null ? "" : a.attr("href");
            }
            return clean(u);
        }

        @Override
        public String getUploaderName() {
            return embed.getObject("author").getString("name", "");
        }

        @Override
        public String getUploaderAvatarUrl() {
            if (doc == null) {
                return null;
            }
            final Element a = doc.selectFirst("a.media-by--a");
            return a == null ? null
                    : cssImage(cssImages(html), a.selectFirst("i.user-image"),
                    "user-image--img--id-");
        }

        @Override
        public boolean isUploaderVerified() {
            if (doc == null) {
                return false;
            }
            final Element a = doc.selectFirst("a.media-by--a");
            return a != null && a.selectFirst(".verification-badge-icon") != null;
        }

        @Override
        public long getUploaderSubscriberCount() {
            if (doc == null) {
                return -1;
            }
            final Element f = doc.selectFirst(".media-heading-num-followers");
            return f == null ? -1 : count(f.text());
        }

        @Nonnull
        @Override
        public Description getDescription() {
            if (doc != null) {
                final Element dsc = doc.selectFirst("[data-js=media_long_description_container]");
                if (dsc != null) {
                    return new Description(dsc.html(), Description.HTML);
                }
                final Element p = doc.selectFirst("p.media-description");
                if (p != null) {
                    return new Description(p.html(), Description.HTML);
                }
            }
            return new Description(ld.getString("description", ""), Description.PLAIN_TEXT);
        }

        @Override
        public long getLength() {
            return live == 2 ? 0 : embed.getLong("duration", 0);
        }

        @Override
        public long getViewCount() {
            final long n = ld.getObject("interactionStatistic")
                    .getLong("userInteractionCount", -1);
            if (n >= 0) {
                return n;
            }
            if (doc != null) {
                final Element v = doc.selectFirst(".media-description-info-views");
                if (v != null) {
                    return count(v.text());
                }
            }
            return -1;
        }

        @Override
        public long getLikeCount() {
            return voteCount(".rumbles-vote-up");
        }

        @Override
        public long getDislikeCount() {
            return voteCount(".rumbles-vote-down");
        }

        private long voteCount(final String sel) {
            if (doc == null) {
                return -1;
            }
            final Element v = doc.selectFirst(".media-by-actions-container " + sel);
            if (v == null) {
                return -1;
            }
            final String t = v.text().trim();
            return t.isEmpty() ? -1 : count(t);
        }

        @Override
        public String getTextualUploadDate() {
            final String p = embed.getString("pubDate");
            return p != null ? p : ld.getString("uploadDate");
        }

        @Override
        public DateWrapper getUploadDate() {
            return date(getTextualUploadDate());
        }

        @Nonnull
        @Override
        public List<String> getTags() {
            final List<String> tags = new ArrayList<>();
            if (doc != null) {
                for (final Element a : doc.select("a.video-category-tag")) {
                    tags.add(a.text());
                }
            }
            return tags;
        }

        @Nonnull
        @Override
        public String getHlsUrl() {
            return hlsUrl;
        }

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
            return videos;
        }

        @Override
        public List<VideoStream> getVideoOnlyStreams() {
            return Collections.emptyList();
        }

        @Nonnull
        @Override
        public List<SubtitlesStream> getSubtitlesDefault() {
            return subtitles;
        }

        @Nonnull
        @Override
        public List<SubtitlesStream> getSubtitles(final MediaFormat format) {
            return format == MediaFormat.VTT ? subtitles : Collections.emptyList();
        }

        @Override
        public StreamType getStreamType() {
            return live == 2 ? StreamType.LIVE_STREAM : StreamType.VIDEO_STREAM;
        }
    }

    /** A channel (/c/name or /user/name): header from the page, list from its videos tab. */
    static final class RumbleChannelExtractor extends ChannelExtractor {
        private Listing first;

        RumbleChannelExtractor(final StreamingService s, final ListLinkHandler h) {
            super(s, h);
        }

        @Override
        public void onFetchPage(@Nonnull final Downloader d)
                throws IOException, ExtractionException {
            first = new Listing(BASE + "/" + getId() + "/videos", true);
            if (first.doc.selectFirst(".channel-header--title") == null && first.items.isEmpty()) {
                throw new ContentNotAvailableException("Rumble channel not found");
            }
        }

        @Nonnull
        @Override
        public String getName() throws ParsingException {
            final Element h = first.doc.selectFirst(".channel-header--title h1");
            if (h != null) {
                return h.text();
            }
            final String id = getId();
            return id.substring(id.indexOf('/') + 1);
        }

        @Override
        public String getAvatarUrl() {
            final Element img = first.doc.selectFirst("img.channel-header--img");
            return img == null ? null : img.absUrl("src");
        }

        @Override
        public String getBannerUrl() {
            final Element img = first.doc.selectFirst("img.channel-header--backsplash-img");
            return img == null ? null : img.absUrl("src");
        }

        @Override
        public long getSubscriberCount() {
            final Element t = first.doc.selectFirst(".channel-header--title");
            if (t == null) {
                return -1;
            }
            for (final Element span : t.select("span")) {
                if (span.ownText().toLowerCase(Locale.ROOT).contains("follower")) {
                    return count(span.ownText());
                }
            }
            return -1;
        }

        @Override
        public String getDescription() {
            return "";
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
            final Element t = first.doc.selectFirst(".channel-header--title");
            return t != null && t.selectFirst(".verification-badge-icon, svg title") != null;
        }

        @Nonnull
        @Override
        public InfoItemsPage<StreamInfoItem> getInitialPage() {
            return new InfoItemsPage<>(first.collect(getServiceId()), first.nextPage());
        }

        @Override
        public InfoItemsPage<StreamInfoItem> getPage(final Page p)
                throws IOException, ExtractionException {
            final Listing l = new Listing(p.getUrl(), true);
            return new InfoItemsPage<>(l.collect(getServiceId()), l.nextPage());
        }
    }

    static final class Kiosk extends KioskExtractor<StreamInfoItem> {
        private Listing first;

        Kiosk(final StreamingService s, final ListLinkHandler h, final String id) {
            super(s, h, id);
        }

        @Override
        public void onFetchPage(@Nonnull final Downloader d)
                throws IOException, ExtractionException {
            first = new Listing(getUrl(), false);
        }

        @Nonnull
        @Override
        public String getName() {
            return getId();
        }

        @Nonnull
        @Override
        public InfoItemsPage<StreamInfoItem> getInitialPage() {
            return new InfoItemsPage<>(first.collect(getServiceId()), first.nextPage());
        }

        @Override
        public InfoItemsPage<StreamInfoItem> getPage(final Page p)
                throws IOException, ExtractionException {
            final Listing l = new Listing(p.getUrl(), false);
            return new InfoItemsPage<>(l.collect(getServiceId()), l.nextPage());
        }
    }

    /** Search: page 1 is /search/all (channels + videos), later pages /search/video. */
    static final class Search extends SearchExtractor {
        private Listing first;

        Search(final StreamingService s, final SearchQueryHandler h) {
            super(s, h);
        }

        @Override
        public void onFetchPage(@Nonnull final Downloader d)
                throws IOException, ExtractionException {
            first = new Listing(BASE + "/search/all?q=" + enc(getSearchString()), false);
        }

        private InfoItemsPage<InfoItem> toPage(final Listing l, final boolean withChannels) {
            final MultiInfoItemsCollector c = new MultiInfoItemsCollector(getServiceId());
            if (withChannels) {
                final Map<String, String> css = cssImages(l.html);
                for (final Element card : l.doc.select(
                        "[data-js-swipe-slider=swipe-slider__scroll-container] > div")) {
                    final Element a = card.selectFirst("a[href^=/c/], a[href^=/user/]");
                    if (a == null) {
                        continue;
                    }
                    c.commit(new ChannelCard(card, a, css));
                }
            }
            for (final StreamInfoItemExtractor i : l.items) {
                c.commit(i);
            }
            return new InfoItemsPage<>(c, l.nextPage());
        }

        @Override
        protected InfoItemsPage<InfoItem> getInitialPageInternal() {
            return toPage(first, true);
        }

        @Override
        protected InfoItemsPage<InfoItem> getPageInternal(final Page page)
                throws IOException, ExtractionException {
            return toPage(new Listing(page.getUrl(), false), false);
        }
    }

    /** A channel in search results. */
    static final class ChannelCard implements ChannelInfoItemExtractor {
        private final Element card;
        private final Element link;
        private final Map<String, String> css;

        ChannelCard(final Element card, final Element link, final Map<String, String> css) {
            this.card = card;
            this.link = link;
            this.css = css;
        }

        @Override
        public String getName() {
            final Element n = card.selectFirst("h3 span");
            return n != null ? n.text() : link.text();
        }

        @Override
        public String getUrl() {
            return clean(link.attr("href"));
        }

        @Override
        public String getThumbnailUrl() {
            return cssImage(css, card.selectFirst("i.user-image"), "user-image--img--id-");
        }

        @Override
        public String getDescription() {
            return "";
        }

        @Override
        public long getSubscriberCount() {
            for (final Element s : card.select("span")) {
                if (s.ownText().toLowerCase(Locale.ROOT).contains("follower")) {
                    return count(s.ownText());
                }
            }
            return -1;
        }

        @Override
        public long getStreamCount() {
            return -1;
        }

        @Override
        public boolean isVerified() {
            return card.selectFirst(".verification-badge-icon") != null;
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
            for (final String id : KioskLH.URLS.keySet()) {
                list.addKioskEntry((s, url, kid) -> new Kiosk(RumbleService.this,
                        lh.fromUrl(url), kid), lh, id);
            }
            list.setDefaultKiosk(KIOSK_TRENDING);
        } catch (final Exception e) {
            throw new ExtractionException(e);
        }
        return list;
    }

    @Override
    public ChannelExtractor getChannelExtractor(final ListLinkHandler h) {
        return new RumbleChannelExtractor(this, h);
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
        return null;
    }
}
