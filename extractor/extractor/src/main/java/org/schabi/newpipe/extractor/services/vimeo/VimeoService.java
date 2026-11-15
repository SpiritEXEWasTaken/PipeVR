package org.schabi.newpipe.extractor.services.vimeo;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;

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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static org.schabi.newpipe.extractor.StreamingService.ServiceInfo.MediaCapability.COMMENTS;
import static org.schabi.newpipe.extractor.StreamingService.ServiceInfo.MediaCapability.LIVE;
import static org.schabi.newpipe.extractor.StreamingService.ServiceInfo.MediaCapability.VIDEO;

/**
 * PipeVR: Vimeo, anonymous. Lists, search, channels and comments use api.vimeo.com with the
 * website's own anonymous JWT (vimeo.com/_next/jwt). Playback reads the embed player's config
 * (player.vimeo.com/video/{id}). DRM-protected videos are reported as unavailable.
 * Endpoint reference: yt-dlp's Vimeo extractor (Unlicense) and the website itself.
 */
public class VimeoService extends StreamingService {
    static final String BASE = "https://vimeo.com";
    static final String API = "https://api.vimeo.com";
    static final String PLAYER = "https://player.vimeo.com";
    private static final String UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
    static final String KIOSK_STAFF_PICKS = "Staff Picks";
    static final int PER_PAGE = 20;

    static final String VIDEO_FIELDS = "uri,name,description,link,duration,release_time,"
            + "created_time,type,live.status,stats.plays,pictures.sizes,user.name,user.link,"
            + "user.pictures.sizes,player_embed_url,metadata.connections.likes.total";
    static final String USER_FIELDS = "uri,name,link,bio,short_bio,description,pictures.sizes,"
            + "header.sizes,metadata.connections.followers.total,"
            + "metadata.connections.users.total,metadata.connections.videos.total";

    @Nullable
    private static volatile String jwt;
    private static volatile long jwtExpiresMs;

    public VimeoService(final int id) {
        super(id, "Vimeo", Arrays.asList(VIDEO, LIVE, COMMENTS));
    }

    // ---- HTTP helpers ----

    /** Headers the player needs for Vimeo media (none are strictly required; UA for safety). */
    static Map<String, String> mediaHeaders() {
        final Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", UA);
        h.put("Referer", PLAYER + "/");
        h.put("Origin", PLAYER);
        return h;
    }

    private static Map<String, List<String>> headers(final String... kv) {
        final Map<String, List<String>> h = new HashMap<>();
        h.put("User-Agent", Collections.singletonList(UA));
        for (int i = 0; i + 1 < kv.length; i += 2) {
            h.put(kv[i], Collections.singletonList(kv[i + 1]));
        }
        return h;
    }

    private static synchronized String token(final boolean refresh)
            throws IOException, ExtractionException {
        if (!refresh && jwt != null && System.currentTimeMillis() < jwtExpiresMs) {
            return jwt;
        }
        final Response r = NewPipe.getDownloader().get(BASE + "/_next/jwt",
                headers("X-Requested-With", "XMLHttpRequest", "Accept", "application/json"));
        final String t;
        try {
            t = JsonParser.object().from(r.responseBody()).getString("token");
        } catch (final Exception e) {
            throw new ParsingException("Could not get Vimeo token (HTTP "
                    + r.responseCode() + ")", e);
        }
        if (t == null || t.isEmpty()) {
            throw new ParsingException("Vimeo returned no token");
        }
        long exp = System.currentTimeMillis() + 5 * 60_000L;
        try {
            final String[] parts = t.split("\\.");
            final JsonObject payload = JsonParser.object().from(new String(
                    Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
            final long e = payload.getLong("exp", 0);
            if (e > 0) {
                exp = e * 1000L - 60_000L;
            }
        } catch (final Exception ignored) {
            // keep the 5 minute default
        }
        jwt = t;
        jwtExpiresMs = exp;
        return t;
    }

    /** GET an api.vimeo.com path ("/videos/..") or absolute URL as JSON. */
    static JsonObject api(final String pathOrUrl) throws IOException, ExtractionException {
        final String url = pathOrUrl.startsWith("http") ? pathOrUrl : API + pathOrUrl;
        Response r = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            r = NewPipe.getDownloader().get(url, headers(
                    "Authorization", "jwt " + token(attempt > 0),
                    "Accept", "application/vnd.vimeo.*+json;version=3.4"));
            if (r.responseCode() != 401) {
                break;
            }
        }
        if (r.responseCode() == 404) {
            throw new ContentNotAvailableException("Not found on Vimeo");
        }
        if (r.responseCode() == 403) {
            throw new ContentNotAvailableException("This Vimeo content is private");
        }
        try {
            final JsonObject o = JsonParser.object().from(r.responseBody());
            if (r.responseCode() >= 400) {
                throw new ContentNotAvailableException("Vimeo: "
                        + o.getString("error", "HTTP " + r.responseCode()));
            }
            return o;
        } catch (final ContentNotAvailableException e) {
            throw e;
        } catch (final Exception e) {
            throw new ParsingException("Unexpected Vimeo response (HTTP "
                    + r.responseCode() + ")", e);
        }
    }

    static String enc(final String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** "123" or "123:hash" (unlisted) to the canonical page URL. */
    static String videoUrl(final String id) {
        final int c = id.indexOf(':');
        return c < 0 ? BASE + "/" + id : BASE + "/" + id.substring(0, c) + "/"
                + id.substring(c + 1);
    }

    /** "/videos/123" to "123". */
    static String idFromUri(@Nullable final String uri) {
        if (uri == null) {
            return "";
        }
        final int s = uri.lastIndexOf('/');
        return s < 0 ? uri : uri.substring(s + 1);
    }

    /** Picks the smallest picture at least {@code minWidth} wide, else the largest one. */
    @Nullable
    static String picture(@Nullable final Object pictures, final int minWidth) {
        if (!(pictures instanceof JsonObject)) {
            return null;
        }
        final JsonArray sizes = ((JsonObject) pictures).getArray("sizes");
        String best = null;
        int bestW = -1;
        String largest = null;
        int largestW = -1;
        for (int i = 0; i < sizes.size(); i++) {
            final JsonObject s = sizes.getObject(i);
            final int w = s.getInt("width", 0);
            final String link = s.getString("link");
            if (link == null) {
                continue;
            }
            if (w > largestW) {
                largestW = w;
                largest = link;
            }
            if (w >= minWidth && (bestW < 0 || w < bestW)) {
                bestW = w;
                best = link;
            }
        }
        return best != null ? best : largest;
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

    static long connectionTotal(final JsonObject o, final String name) {
        final Object c = o.getObject("metadata").getObject("connections").get(name);
        return c instanceof JsonObject ? ((JsonObject) c).getLong("total", -1) : -1;
    }

    static boolean isLive(final JsonObject video) {
        return "live".equals(video.getString("type"))
                && "streaming".equals(video.getObject("live").getString("status"));
    }

    @Nullable
    static Page nextPage(final JsonObject list) {
        final String next = list.getObject("paging").getString("next");
        return next == null || next.isEmpty() ? null : new Page(API + next);
    }

    static String withFields(final String path, final String fields) {
        return path + (path.contains("?") ? "&" : "?") + "per_page=" + PER_PAGE
                + "&fields=" + enc(fields);
    }

    // ---- info items ----

    /** A video in a list (API video object). */
    static final class VideoItem implements StreamInfoItemExtractor {
        private final JsonObject v;

        VideoItem(final JsonObject v) {
            this.v = v;
        }

        @Override
        public String getName() {
            return v.getString("name", "Vimeo video");
        }

        @Override
        public String getUrl() {
            return videoUrl(idFromUri(v.getString("uri")));
        }

        @Override
        public String getThumbnailUrl() {
            return picture(v.get("pictures"), 640);
        }

        @Override
        public StreamType getStreamType() {
            return isLive(v) ? StreamType.LIVE_STREAM : StreamType.VIDEO_STREAM;
        }

        @Override
        public long getDuration() {
            return isLive(v) ? -1 : v.getLong("duration", -1);
        }

        @Override
        public long getViewCount() {
            final Object plays = v.getObject("stats").get("plays");
            return plays instanceof Number ? ((Number) plays).longValue() : -1;
        }

        @Override
        public String getUploaderName() {
            return v.getObject("user").getString("name", "");
        }

        @Override
        public String getUploaderUrl() {
            return v.getObject("user").getString("link", "");
        }

        @Override
        public String getUploaderAvatarUrl() {
            return picture(v.getObject("user").get("pictures"), 100);
        }

        @Override
        public String getTextualUploadDate() {
            return v.getString("release_time", v.getString("created_time"));
        }

        @Override
        public DateWrapper getUploadDate() {
            return date(getTextualUploadDate());
        }

        @Override
        public String getShortDescription() {
            return v.getString("description");
        }
    }

    /** A user or channel in search results. */
    static final class UserItem implements ChannelInfoItemExtractor {
        private final JsonObject u;

        UserItem(final JsonObject u) {
            this.u = u;
        }

        @Override
        public String getName() {
            return u.getString("name", "");
        }

        @Override
        public String getUrl() {
            return u.getString("link", "");
        }

        @Override
        public String getThumbnailUrl() {
            return picture(u.get("pictures"), 100);
        }

        @Override
        public String getDescription() {
            final String s = u.getString("short_bio");
            return s != null ? s : u.getString("bio", "");
        }

        @Override
        public long getSubscriberCount() {
            return connectionTotal(u, "followers");
        }

        @Override
        public long getStreamCount() {
            return connectionTotal(u, "videos");
        }

        @Override
        public boolean isVerified() {
            return false;
        }
    }

    static StreamInfoItemsCollector collectVideos(final int serviceId, final JsonObject list) {
        final StreamInfoItemsCollector collector = new StreamInfoItemsCollector(serviceId);
        final JsonArray data = list.getArray("data");
        for (int i = 0; i < data.size(); i++) {
            final Object o = data.get(i);
            if (o instanceof JsonObject) {
                JsonObject v = (JsonObject) o;
                // channel/album listings may wrap the clip
                if (v.get("clip") instanceof JsonObject) {
                    v = v.getObject("clip");
                }
                if (v.getString("uri", "").startsWith("/videos/")) {
                    collector.commit(new VideoItem(v));
                }
            }
        }
        return collector;
    }

    // ---- link handlers ----

    static final class StreamLH extends LinkHandlerFactory {
        private static final Pattern VIDEO = Pattern.compile(
                "^https?://(?:www\\.|player\\.)?vimeo\\.com/(?:video/|channels/[\\w-]+/"
                        + "|groups/[\\w-]+/videos/|(?:album|showcase)/\\d+/video/"
                        + "|[\\w-]+/)?(\\d+)(?:/([0-9a-f]{6,}))?/?(?:[?#].*)?$");
        private static final Pattern H_PARAM = Pattern.compile("[?&]h=([0-9a-f]+)");

        @Override
        public String getId(final String url) throws ParsingException {
            final Matcher m = VIDEO.matcher(url);
            if (!m.find()) {
                throw new ParsingException("Not a Vimeo video: " + url);
            }
            String hash = m.group(2);
            if (hash == null) {
                final Matcher h = H_PARAM.matcher(url);
                if (h.find()) {
                    hash = h.group(1);
                }
            }
            return hash == null ? m.group(1) : m.group(1) + ":" + hash;
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
        private static final Pattern CHANNEL = Pattern.compile(
                "^https?://(?:www\\.)?vimeo\\.com/(channels/[\\w-]+|(?!(?:channels|groups"
                        + "|album|showcase|categories|search|watch|ondemand|features|blog"
                        + "|upload|log_in|join|about|help|stock|live|enterprise|solutions"
                        + "|create|manage|settings|purchases|watchlater|_next|api|video"
                        + "|terms|privacy|cookie_policy|site_map|explore|staffpicks)(?:[/?#]|$))"
                        + "[A-Za-z][\\w-]*)(?:/videos)?/?(?:[?#].*)?$");

        @Override
        public String getId(final String url) throws ParsingException {
            final Matcher m = CHANNEL.matcher(url);
            if (m.find()) {
                return m.group(1);
            }
            throw new ParsingException("Not a Vimeo channel: " + url);
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
        private static final String PREFIX = BASE + "/search?q=";

        @Override
        public String getUrl(final String q, final List<FilterItem> c, final List<FilterItem> s) {
            return PREFIX + enc(q);
        }

        @Override
        public String getSearchString(final String url) {
            return url.startsWith(PREFIX)
                    ? URLDecoder.decode(url.substring(PREFIX.length()), StandardCharsets.UTF_8)
                    : "";
        }
    }

    static final class KioskLH extends ListLinkHandlerFactory {
        private static final String URL = BASE + "/watch";

        @Override
        public String getUrl(final String id, final List<FilterItem> c, final List<FilterItem> s) {
            return URL;
        }

        @Override
        public String getId(final String url) {
            return KIOSK_STAFF_PICKS;
        }

        @Override
        public boolean onAcceptUrl(final String url) {
            return URL.equals(url);
        }
    }

    /** Comments are listed for the video URL. */
    static final class CommentsLH extends ListLinkHandlerFactory {
        private final StreamLH stream = new StreamLH();

        @Override
        public String getId(final String url) throws ParsingException {
            return stream.getId(url);
        }

        @Override
        public String getUrl(final String id, final List<FilterItem> c, final List<FilterItem> s) {
            return videoUrl(id);
        }

        @Override
        public boolean onAcceptUrl(final String url) {
            return stream.onAcceptUrl(url);
        }
    }

    // ---- extractors ----

    static final class VideoExtractor extends StreamExtractor {
        private JsonObject video = new JsonObject();      // API object (may be empty)
        private JsonObject config = new JsonObject();     // player config
        private final List<org.schabi.newpipe.extractor.stream.SubtitlesStream> subtitles =
                new ArrayList<>();

        /** PipeVR: subtitles/captions of the player config (WebVTT, links on player.vimeo.com). */
        private void readTextTracks() {
            final JsonArray tracks = config.getObject("request").getArray("text_tracks");
            for (int i = 0; i < tracks.size(); i++) {
                final JsonObject t = tracks.getObject(i);
                String url = t.getString("url", "");
                if (url.isEmpty()) {
                    continue;
                }
                if (url.startsWith("/")) {
                    url = PLAYER + url;
                }
                final String lang = t.getString("lang", "und");
                try {
                    subtitles.add(new org.schabi.newpipe.extractor.stream.SubtitlesStream.Builder()
                            .setContent(url, true)
                            .setMediaFormat(MediaFormat.VTT)
                            .setLanguageCode(lang.replaceAll("-x-autogen$", ""))
                            .setAutoGenerated(lang.contains("autogen")
                                    || t.getString("label", "").toLowerCase()
                                            .contains("auto"))
                            .setRequestHeaders(mediaHeaders())
                            .build());
                } catch (final Exception ignored) {
                    // a track with an odd language code: skip it
                }
            }
        }

        @Nonnull
        @Override
        public List<org.schabi.newpipe.extractor.stream.SubtitlesStream> getSubtitlesDefault() {
            return subtitles;
        }

        @Nonnull
        @Override
        public List<org.schabi.newpipe.extractor.stream.SubtitlesStream> getSubtitles(
                final MediaFormat format) {
            return format == MediaFormat.VTT ? subtitles : Collections.emptyList();
        }
        private final List<VideoStream> videos = new ArrayList<>();
        private String hlsUrl = "";
        private boolean live;

        VideoExtractor(final StreamingService s, final LinkHandler h) {
            super(s, h);
        }

        private String numericId() {
            final String id = getLinkHandler().getId();
            final int c = id.indexOf(':');
            return c < 0 ? id : id.substring(0, c);
        }

        @Nullable
        private String hash() {
            final String id = getLinkHandler().getId();
            final int c = id.indexOf(':');
            return c < 0 ? null : id.substring(c + 1);
        }

        private JsonObject cfgVideo() {
            return config.getObject("video");
        }

        @Nullable
        private static JsonObject parsePlayerConfig(final String html) {
            final int start = html.indexOf("window.playerConfig");
            if (start < 0) {
                return null;
            }
            final int brace = html.indexOf('{', start);
            final int end = html.indexOf("</script>", brace);
            if (brace < 0 || end < 0) {
                return null;
            }
            String json = html.substring(brace, end).trim();
            while (json.endsWith(";")) {
                json = json.substring(0, json.length() - 1).trim();
            }
            try {
                return JsonParser.object().from(json);
            } catch (final Exception e) {
                return null;
            }
        }

        @Override
        public void onFetchPage(@Nonnull final Downloader d)
                throws IOException, ExtractionException {
            ContentNotAvailableException apiError = null;
            try {
                video = api("/videos/" + getId() + "?fields=" + enc(VIDEO_FIELDS));
            } catch (final ContentNotAvailableException e) {
                apiError = e;
            } catch (final Exception e) {
                video = new JsonObject();
            }

            String embed = video.getString("player_embed_url");
            if (embed == null || embed.isEmpty()) {
                embed = PLAYER + "/video/" + numericId()
                        + (hash() != null ? "?h=" + hash() : "");
            }
            final Response page = d.get(embed, headers("Referer", BASE + "/"));
            JsonObject cfg = page.responseCode() == 200
                    ? parsePlayerConfig(page.responseBody()) : null;
            if (cfg == null) {
                final Response r = d.get(PLAYER + "/video/" + numericId() + "/config"
                                + (hash() != null ? "?h=" + hash() : ""),
                        headers("Referer", BASE + "/"));
                try {
                    cfg = JsonParser.object().from(r.responseBody());
                } catch (final Exception e) {
                    if (apiError != null) {
                        throw apiError;
                    }
                    throw new ContentNotAvailableException(
                            "Vimeo player refused this video (HTTP " + r.responseCode() + ")");
                }
            }
            config = cfg;
            readTextTracks();

            final JsonObject files = config.getObject("request").getObject("files");
            final Object liveEvent = cfgVideo().get("live_event");
            live = isLive(video) || (liveEvent instanceof JsonObject
                    && !"ended".equals(((JsonObject) liveEvent).getString("status"))
                    && !"done".equals(((JsonObject) liveEvent).getString("status")));
            final int height = cfgVideo().getInt("height", 0);
            final String label = height > 0 ? height + "p" : "1080p";
            boolean drm = false;

            final JsonObject hls = files.getObject("hls");
            final JsonObject cdns = hls.getObject("cdns");
            JsonObject cdn = cdns.getObject(hls.getString("default_cdn", ""));
            if (cdn.isEmpty() && !cdns.isEmpty()) {
                cdn = cdns.getObject(cdns.keySet().iterator().next());
            }
            // avc_url: H.264-only master (plays on every headset); url may carry AV1/HEVC
            String url = cdn.getString("avc_url", cdn.getString("url", ""));
            if (url.isEmpty()) {
                url = cdn.getString("url", "");
            }
            if (url.contains("/drm/")) {
                drm = true;
            } else if (!url.isEmpty()) {
                hlsUrl = url;
                videos.add(new VideoStream.Builder()
                        .setId("vimeo-hls-" + numericId())
                        .setContent(url, true).setIsVideoOnly(false)
                        .setMediaFormat(MediaFormat.MPEG_4)
                        .setDeliveryMethod(DeliveryMethod.HLS)
                        .setResolution(label)
                        .setRequestHeaders(mediaHeaders())
                        .build());
            }

            final JsonArray progressive = files.getArray("progressive");
            for (int i = 0; i < progressive.size(); i++) {
                final JsonObject p = progressive.getObject(i);
                final String pu = p.getString("url", "");
                if (pu.isEmpty() || pu.contains("/drm/")) {
                    continue;
                }
                final int h = p.getInt("height", 0);
                videos.add(new VideoStream.Builder()
                        .setId("vimeo-mp4-" + numericId() + "-" + i)
                        .setContent(pu, true).setIsVideoOnly(false)
                        .setMediaFormat(MediaFormat.MPEG_4)
                        .setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
                        .setResolution(p.getString("quality", h > 0 ? h + "p" : "360p"))
                        .setWidth(p.getInt("width", 0)).setHeight(h)
                        .setFps(p.getInt("fps", 0))
                        .setRequestHeaders(mediaHeaders())
                        .build());
            }

            if (videos.isEmpty()) {
                throw new ContentNotAvailableException(drm
                        ? "This Vimeo video is DRM-protected and can't be played in PipeVR"
                        : "Vimeo did not provide a playable stream for this video");
            }
        }

        @Nonnull
        @Override
        public String getName() {
            return video.getString("name", cfgVideo().getString("title", "Vimeo video"));
        }

        @Override
        public String getThumbnailUrl() {
            final String p = picture(video.get("pictures"), 1280);
            return p != null ? p : cfgVideo().getString("thumbnail_url");
        }

        @Override
        public String getUploaderUrl() {
            final String l = video.getObject("user").getString("link");
            return l != null ? l : cfgVideo().getObject("owner").getString("url", "");
        }

        @Override
        public String getUploaderName() {
            final String n = video.getObject("user").getString("name");
            return n != null ? n : cfgVideo().getObject("owner").getString("name", "");
        }

        @Override
        public String getUploaderAvatarUrl() {
            final String p = picture(video.getObject("user").get("pictures"), 100);
            return p != null ? p : cfgVideo().getObject("owner").getString("img_2x",
                    cfgVideo().getObject("owner").getString("img"));
        }

        @Nonnull
        @Override
        public Description getDescription() {
            return new Description(video.getString("description", ""), Description.PLAIN_TEXT);
        }

        @Override
        public long getLength() {
            if (live) {
                return 0;
            }
            return video.getLong("duration", cfgVideo().getLong("duration", 0));
        }

        @Override
        public long getViewCount() {
            final Object plays = video.getObject("stats").get("plays");
            return plays instanceof Number ? ((Number) plays).longValue() : -1;
        }

        @Override
        public long getLikeCount() {
            return video.isEmpty() ? -1 : connectionTotal(video, "likes");
        }

        @Override
        public String getTextualUploadDate() {
            return video.getString("release_time", video.getString("created_time"));
        }

        @Override
        public DateWrapper getUploadDate() {
            return date(getTextualUploadDate());
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

        @Override
        public StreamType getStreamType() {
            return live ? StreamType.LIVE_STREAM : StreamType.VIDEO_STREAM;
        }

        @Override
        public StreamInfoItemsCollector getRelatedItems() {
            try {
                return collectVideos(getServiceId(), api(withFields("/videos/" + numericId()
                        + "/videos?filter=related", VIDEO_FIELDS)));
            } catch (final Exception e) {
                return null;
            }
        }
    }

    /** A user (vimeo.com/{user}) or a channel (vimeo.com/channels/{name}): newest first. */
    static final class UserExtractor extends ChannelExtractor {
        private JsonObject user;
        private JsonObject first;

        UserExtractor(final StreamingService s, final ListLinkHandler h) {
            super(s, h);
        }

        private String apiPath() {
            final String id = getLinkHandler().getId();
            return id.startsWith("channels/") ? "/" + id : "/users/" + id;
        }

        @Override
        public void onFetchPage(@Nonnull final Downloader d)
                throws IOException, ExtractionException {
            user = api(apiPath() + "?fields=" + enc(USER_FIELDS));
            first = api(withFields(apiPath() + "/videos?sort=date&direction=desc",
                    VIDEO_FIELDS));
        }

        @Nonnull
        @Override
        public String getName() {
            return user.getString("name", getLinkHandler().getId());
        }

        @Override
        public String getAvatarUrl() {
            return picture(user.get("pictures"), 200);
        }

        @Override
        public String getBannerUrl() {
            return picture(user.get("header"), 1280);
        }

        @Override
        public long getSubscriberCount() {
            final long f = connectionTotal(user, "followers");
            return f >= 0 ? f : connectionTotal(user, "users");
        }

        @Override
        public String getDescription() {
            final String b = user.getString("bio");
            return b != null ? b : user.getString("description", "");
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
            return new InfoItemsPage<>(collectVideos(getServiceId(), first), nextPage(first));
        }

        @Override
        public InfoItemsPage<StreamInfoItem> getPage(final Page page)
                throws IOException, ExtractionException {
            final JsonObject list = api(page.getUrl());
            return new InfoItemsPage<>(collectVideos(getServiceId(), list), nextPage(list));
        }
    }

    /** Front page: Vimeo Staff Picks, newest first. */
    static final class StaffPicks extends KioskExtractor<StreamInfoItem> {
        private JsonObject first;

        StaffPicks(final StreamingService s, final ListLinkHandler h, final String id) {
            super(s, h, id);
        }

        @Override
        public void onFetchPage(@Nonnull final Downloader d)
                throws IOException, ExtractionException {
            first = api(withFields("/channels/staffpicks/videos?sort=date&direction=desc",
                    VIDEO_FIELDS));
        }

        @Nonnull
        @Override
        public String getName() {
            return KIOSK_STAFF_PICKS;
        }

        @Nonnull
        @Override
        public InfoItemsPage<StreamInfoItem> getInitialPage() {
            return new InfoItemsPage<>(collectVideos(getServiceId(), first), nextPage(first));
        }

        @Override
        public InfoItemsPage<StreamInfoItem> getPage(final Page page)
                throws IOException, ExtractionException {
            final JsonObject list = api(page.getUrl());
            return new InfoItemsPage<>(collectVideos(getServiceId(), list), nextPage(list));
        }
    }

    /** Videos (paginated), with the top few matching users on the first page. */
    static final class Search extends SearchExtractor {
        private JsonObject videosList;
        private JsonObject usersList;

        Search(final StreamingService s, final SearchQueryHandler h) {
            super(s, h);
        }

        @Override
        public void onFetchPage(@Nonnull final Downloader d)
                throws IOException, ExtractionException {
            final String q = enc(getSearchString());
            videosList = api(withFields("/videos?query=" + q, VIDEO_FIELDS));
            try {
                usersList = api("/users?query=" + q + "&per_page=3&fields=" + enc(USER_FIELDS));
            } catch (final Exception e) {
                usersList = new JsonObject();
            }
        }

        private void addVideos(final MultiInfoItemsCollector collector, final JsonObject list) {
            final JsonArray data = list.getArray("data");
            for (int i = 0; i < data.size(); i++) {
                final JsonObject v = data.getObject(i);
                if (v.getString("uri", "").startsWith("/videos/")) {
                    collector.commit(new VideoItem(v));
                }
            }
        }

        @Override
        protected InfoItemsPage<InfoItem> getInitialPageInternal() {
            final MultiInfoItemsCollector collector = new MultiInfoItemsCollector(getServiceId());
            final JsonArray users = usersList.getArray("data");
            for (int i = 0; i < users.size(); i++) {
                final JsonObject u = users.getObject(i);
                if (u.getString("link") != null) {
                    collector.commit(new UserItem(u));
                }
            }
            addVideos(collector, videosList);
            return new InfoItemsPage<>(collector, nextPage(videosList));
        }

        @Override
        protected InfoItemsPage<InfoItem> getPageInternal(final Page page)
                throws IOException, ExtractionException {
            final JsonObject list = api(page.getUrl());
            final MultiInfoItemsCollector collector = new MultiInfoItemsCollector(getServiceId());
            addVideos(collector, list);
            return new InfoItemsPage<>(collector, nextPage(list));
        }
    }

    /** Public comments (api.vimeo.com/videos/{id}/comments). */
    static final class Comments extends CommentsExtractor {
        private static final String FIELDS = "uri,text,created_on,user.name,user.link,"
                + "user.pictures.sizes,metadata.connections.replies.total";
        private JsonObject first;

        Comments(final StreamingService s, final ListLinkHandler h) {
            super(s, h);
        }

        private String numericId() {
            final String id = getLinkHandler().getId();
            final int c = id.indexOf(':');
            return c < 0 ? id : id.substring(0, c);
        }

        @Override
        public void onFetchPage(@Nonnull final Downloader d)
                throws IOException, ExtractionException {
            first = api(withFields("/videos/" + numericId() + "/comments", FIELDS));
        }

        private InfoItemsPage<CommentsInfoItem> page(final JsonObject list) {
            final CommentsInfoItemsCollector collector =
                    new CommentsInfoItemsCollector(getServiceId());
            final JsonArray data = list.getArray("data");
            final String videoUrl = getLinkHandler().getUrl();
            for (int i = 0; i < data.size(); i++) {
                final JsonObject c = data.getObject(i);
                collector.commit(new CommentsInfoItemExtractor() {
                    @Override
                    public String getName() {
                        return c.getObject("user").getString("name", "");
                    }

                    @Override
                    public String getUrl() {
                        return videoUrl;
                    }

                    @Override
                    public String getThumbnailUrl() {
                        return picture(c.getObject("user").get("pictures"), 100);
                    }

                    @Override
                    public Description getCommentText() {
                        return new Description(c.getString("text", ""),
                                Description.PLAIN_TEXT);
                    }

                    @Override
                    public String getCommentId() {
                        return idFromUri(c.getString("uri"));
                    }

                    @Override
                    public String getTextualUploadDate() {
                        return c.getString("created_on");
                    }

                    @Override
                    public DateWrapper getUploadDate() {
                        return date(c.getString("created_on"));
                    }

                    @Override
                    public String getUploaderName() {
                        return c.getObject("user").getString("name", "");
                    }

                    @Override
                    public String getUploaderUrl() {
                        return c.getObject("user").getString("link", "");
                    }

                    @Override
                    public String getUploaderAvatarUrl() {
                        return picture(c.getObject("user").get("pictures"), 100);
                    }

                    @Override
                    public int getReplyCount() {
                        return (int) connectionTotal(c, "replies");
                    }
                });
            }
            return new InfoItemsPage<>(collector, nextPage(list));
        }

        @Nonnull
        @Override
        public InfoItemsPage<CommentsInfoItem> getInitialPage() {
            return page(first);
        }

        @Override
        public InfoItemsPage<CommentsInfoItem> getPage(final Page p)
                throws IOException, ExtractionException {
            return page(api(p.getUrl()));
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
            list.addKioskEntry((s, url, kid) -> new StaffPicks(VimeoService.this,
                    lh.fromUrl(url), kid), lh, KIOSK_STAFF_PICKS);
            list.setDefaultKiosk(KIOSK_STAFF_PICKS);
        } catch (final Exception e) {
            throw new ExtractionException(e);
        }
        return list;
    }

    @Override
    public ChannelExtractor getChannelExtractor(final ListLinkHandler h) {
        return new UserExtractor(this, h);
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
