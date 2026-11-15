package org.schabi.newpipe.extractor.services.dailymotion;

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
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException;
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
import java.net.URI;
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
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static org.schabi.newpipe.extractor.StreamingService.ServiceInfo.MediaCapability.LIVE;
import static org.schabi.newpipe.extractor.StreamingService.ServiceInfo.MediaCapability.VIDEO;

/**
 * PipeVR: Dailymotion. Everything works signed out: lists come from the public REST API
 * (api.dailymotion.com), streams from the embed player's metadata endpoint
 * (www.dailymotion.com/player/metadata/video/{id}), which hands out an HLS master playlist.
 * The master playlist host (cdndirector) rejects non-browser User-Agents, so a browser UA is
 * sent with it; the variant playlists and segments it points to need no headers.
 * Endpoint knowledge from yt-dlp's (Unlicense) Dailymotion extractor.
 */
public class DailymotionService extends StreamingService {
    static final String BASE = "https://www.dailymotion.com";
    static final String API = "https://api.dailymotion.com";
    static final String UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
    static final int LIMIT = 20;
    static final String ITEM_FIELDS = "id,title,thumbnail_480_url,duration,views_total,"
            + "created_time,mode,onair,owner.screenname,owner.username,owner.avatar_80_url";
    static final String KIOSK_FEATURED = "Featured";
    static final String KIOSK_TRENDING = "Trending";

    public DailymotionService(final int id) {
        super(id, "Dailymotion", Arrays.asList(VIDEO, LIVE));
    }

    // ---- http ----

    static Map<String, String> mediaHeaders() {
        final Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", UA);
        h.put("Referer", BASE + "/");
        h.put("Origin", BASE);
        return h;
    }

    static String getText(final String url) throws IOException, ExtractionException {
        final Map<String, List<String>> h = new HashMap<>();
        for (final Map.Entry<String, String> e : mediaHeaders().entrySet()) {
            h.put(e.getKey(), Collections.singletonList(e.getValue()));
        }
        final Response r = NewPipe.getDownloader().get(url, h);
        if (r.responseCode() >= 400 && r.responseCode() != 404 && r.responseCode() != 403) {
            throw new ParsingException("Dailymotion: HTTP " + r.responseCode() + " for " + url);
        }
        return r.responseBody();
    }

    static JsonObject json(final String url) throws IOException, ExtractionException {
        final String body = getText(url);
        final JsonObject o;
        try {
            o = JsonParser.object().from(body);
        } catch (final Exception e) {
            throw new ParsingException("Unexpected Dailymotion response", e);
        }
        if (o.has("error") && o.get("error") instanceof JsonObject && !o.has("qualities")) {
            final JsonObject err = o.getObject("error");
            final String msg = err.getString("title", err.getString("message",
                    err.getString("raw_message", "Dailymotion error")));
            if ("DM007".equals(err.getString("code"))) {
                throw new GeographicRestrictionException("Dailymotion: " + msg);
            }
            throw new ContentNotAvailableException("Dailymotion: " + msg);
        }
        return o;
    }

    static String enc(final String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    static String videoUrl(final String id) {
        return BASE + "/video/" + id;
    }

    static String channelUrl(final String username) {
        return BASE + "/" + username;
    }

    @Nullable
    static DateWrapper date(final long epoch) {
        return epoch <= 0 ? null : new DateWrapper(
                OffsetDateTime.ofInstant(Instant.ofEpochSecond(epoch), ZoneOffset.UTC));
    }

    static boolean isLive(final JsonObject v) {
        return "live".equals(v.getString("mode")) && v.getBoolean("onair", false);
    }

    /** A video from a REST API list (fields = ITEM_FIELDS). */
    static final class VideoItem implements StreamInfoItemExtractor {
        private final JsonObject v;

        VideoItem(final JsonObject v) {
            this.v = v;
        }

        @Override
        public String getName() {
            return v.getString("title", "Dailymotion video");
        }

        @Override
        public String getUrl() {
            return videoUrl(v.getString("id"));
        }

        @Override
        public String getThumbnailUrl() {
            return v.getString("thumbnail_480_url");
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
            return v.getLong("views_total", -1);
        }

        @Override
        public String getUploaderName() {
            return v.getString("owner.screenname", "");
        }

        @Override
        public String getUploaderUrl() {
            final String u = v.getString("owner.username");
            return u == null ? "" : channelUrl(u);
        }

        @Override
        public String getUploaderAvatarUrl() {
            return v.getString("owner.avatar_80_url");
        }

        @Override
        public String getTextualUploadDate() {
            final DateWrapper d = getUploadDate();
            return d == null ? null : d.offsetDateTime().toString();
        }

        @Override
        public DateWrapper getUploadDate() {
            return date(v.getLong("created_time", 0));
        }
    }

    static StreamInfoItemsCollector collect(final int serviceId, final JsonObject resp) {
        final StreamInfoItemsCollector collector = new StreamInfoItemsCollector(serviceId);
        final JsonArray list = resp.getArray("list");
        for (int i = 0; i < list.size(); i++) {
            collector.commit(new VideoItem(list.getObject(i)));
        }
        return collector;
    }

    /** Adds page=N to an API list URL. */
    static String withPage(final String url, final int page) {
        return url + (url.contains("?") ? "&" : "?") + "limit=" + LIMIT + "&page=" + page
                + "&fields=" + ITEM_FIELDS;
    }

    @Nullable
    static Page next(final String baseUrl, final JsonObject resp, final int page) {
        return resp.getBoolean("has_more", false) && page < 1000 / LIMIT
                ? new Page(baseUrl, String.valueOf(page + 1)) : null;
    }

    // ---- link handlers ----

    static final class StreamLH extends LinkHandlerFactory {
        private static final Pattern[] PATTERNS = {
                // www/touch/geo.dailymotion.com/[embed/|crawler/]video/<id>
                Pattern.compile("^(?:https?://)?(?:(?:www|touch|geo)\\.)?dailymotion\\.[a-z]{2,3}"
                        + "/(?:(?:embed|crawler|swf)/)?video/([a-zA-Z0-9]+)"),
                // dai.ly/<id>
                Pattern.compile("^(?:https?://)?(?:www\\.)?dai\\.ly/([a-zA-Z0-9]+)"),
                // geo.dailymotion.com/player[/xyz].html?video=<id>
                Pattern.compile("^(?:https?://)?(?:(?:www|geo)\\.)?dailymotion\\.[a-z]{2,3}"
                        + "/player(?:/[a-zA-Z0-9]+)?\\.html\\?(?:.*&)?video=([a-zA-Z0-9]+)"),
        };

        @Override
        public String getId(final String url) throws ParsingException {
            for (final Pattern p : PATTERNS) {
                final Matcher m = p.matcher(url);
                if (m.find()) {
                    return m.group(1);
                }
            }
            throw new ParsingException("Not a Dailymotion video: " + url);
        }

        @Override
        public String getUrl(final String id) {
            return videoUrl(id);
        }

        @Override
        public boolean onAcceptUrl(final String url) {
            for (final Pattern p : PATTERNS) {
                if (p.matcher(url).find()) {
                    return true;
                }
            }
            return false;
        }
    }

    static final class ChannelLH extends ListLinkHandlerFactory {
        private static final Pattern CHANNEL = Pattern.compile(
                "^(?:https?://)?(?:(?:www|touch)\\.)?dailymotion\\.[a-z]{2,3}/"
                        + "(?!(?:embed|swf|video|playlist|search|crawler|player|live|signin|"
                        + "library|settings|discover|following|feed)(?:[/?#.]|$))"
                        + "(?:(?:old/)?user/)?([a-zA-Z0-9._-]+)/?(?:[?#]|$|/videos)");

        @Override
        public String getId(final String url) throws ParsingException {
            final Matcher m = CHANNEL.matcher(url);
            if (m.find()) {
                return m.group(1);
            }
            throw new ParsingException("Not a Dailymotion channel: " + url);
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
        private static final String PREFIX = BASE + "/search/";

        @Override
        public String getUrl(final String q, final List<FilterItem> c, final List<FilterItem> s) {
            return PREFIX + enc(q).replace("+", "%20") + "/videos";
        }

        @Override
        public String getSearchString(final String url) {
            if (!url.startsWith(PREFIX)) {
                return "";
            }
            String q = url.substring(PREFIX.length());
            final int slash = q.indexOf('/');
            if (slash >= 0) {
                q = q.substring(0, slash);
            }
            return URLDecoder.decode(q, StandardCharsets.UTF_8);
        }
    }

    static final class KioskLH extends ListLinkHandlerFactory {
        static final String FEATURED_URL = API + "/videos?flags=featured&sort=trending";
        static final String TRENDING_URL = API + "/videos?sort=trending";

        @Override
        public String getUrl(final String id, final List<FilterItem> c, final List<FilterItem> s) {
            return KIOSK_TRENDING.equals(id) ? TRENDING_URL : FEATURED_URL;
        }

        @Override
        public String getId(final String url) {
            return url.startsWith(TRENDING_URL) ? KIOSK_TRENDING : KIOSK_FEATURED;
        }

        @Override
        public boolean onAcceptUrl(final String url) {
            return url.startsWith(FEATURED_URL) || url.startsWith(TRENDING_URL);
        }
    }

    // ---- extractors ----

    static final class VideoExtractor extends StreamExtractor {
        private static final String FIELDS = "id,title,description,thumbnail_720_url,duration,"
                + "views_total,likes_total,created_time,mode,onair,explicit,tags,channel,"
                + "owner.screenname,owner.username,owner.avatar_240_url,owner.verified";
        private static final Pattern STREAM_INF = Pattern.compile(
                "#EXT-X-STREAM-INF:([^\\n]*)\\n([^#\\n][^\\n]*)");
        private static final Pattern RESOLUTION = Pattern.compile("RESOLUTION=(\\d+)x(\\d+)");
        private static final Pattern FRAME_RATE = Pattern.compile("FRAME-RATE=([\\d.]+)");

        private JsonObject video;
        private JsonObject meta;

        /** PipeVR: subtitles from the player metadata (.srt files on Dailymotion's CDN). */
        @javax.annotation.Nonnull
        @Override
        public java.util.List<org.schabi.newpipe.extractor.stream.SubtitlesStream>
                getSubtitlesDefault() {
            final java.util.List<org.schabi.newpipe.extractor.stream.SubtitlesStream> out =
                    new java.util.ArrayList<>();
            final JsonObject subs = meta == null ? new JsonObject() : meta.getObject("subtitles");
            if (!subs.getBoolean("enable", false)) {
                return out;
            }
            final JsonObject data = subs.getObject("data");
            for (final String lang : data.keySet()) {
                final com.grack.nanojson.JsonArray urls = data.getObject(lang).getArray("urls");
                if (urls.isEmpty()) {
                    continue;
                }
                final String url = urls.getString(0, "");
                if (url.isEmpty()) {
                    continue;
                }
                try {
                    out.add(new org.schabi.newpipe.extractor.stream.SubtitlesStream.Builder()
                            .setContent(url, true)
                            .setMediaFormat(url.endsWith(".vtt")
                                    ? org.schabi.newpipe.extractor.MediaFormat.VTT
                                    : org.schabi.newpipe.extractor.MediaFormat.SRT)
                            .setLanguageCode(lang.replace("-auto", ""))
                            .setAutoGenerated(lang.contains("auto"))
                            .setRequestHeaders(mediaHeaders())
                            .build());
                } catch (final Exception ignored) {
                    // unusual language code
                }
            }
            return out;
        }

        @javax.annotation.Nonnull
        @Override
        public java.util.List<org.schabi.newpipe.extractor.stream.SubtitlesStream> getSubtitles(
                final org.schabi.newpipe.extractor.MediaFormat format) {
            final java.util.List<org.schabi.newpipe.extractor.stream.SubtitlesStream> out =
                    new java.util.ArrayList<>();
            for (final org.schabi.newpipe.extractor.stream.SubtitlesStream st
                    : getSubtitlesDefault()) {
                if (st.getFormat() == format) {
                    out.add(st);
                }
            }
            return out;
        }
        private String hlsUrl;
        private final List<VideoStream> videos = new ArrayList<>();

        VideoExtractor(final StreamingService s, final LinkHandler h) {
            super(s, h);
        }

        @Override
        public void onFetchPage(@Nonnull final Downloader d)
                throws IOException, ExtractionException {
            video = json(API + "/video/" + enc(getId()) + "?fields=" + FIELDS);
            meta = json(BASE + "/player/metadata/video/" + enc(getId())
                    + "?app=com.dailymotion.neon");
            final JsonObject qualities = meta.getObject("qualities");
            for (final String q : qualities.keySet()) {
                final JsonArray list = qualities.getArray(q);
                for (int i = 0; i < list.size() && hlsUrl == null; i++) {
                    final JsonObject m = list.getObject(i);
                    if ("application/x-mpegURL".equals(m.getString("type"))) {
                        hlsUrl = m.getString("url");
                    }
                }
            }
            if (hlsUrl == null) {
                throw new ContentNotAvailableException("Dailymotion did not provide this video");
            }
            // Split the master playlist into one stream per quality so the app can label them.
            try {
                final String master = getText(hlsUrl).replace("\r", "");
                final Matcher m = STREAM_INF.matcher(master);
                final List<String> seen = new ArrayList<>();
                while (m.find()) {
                    final String attrs = m.group(1);
                    final String url = URI.create(hlsUrl).resolve(m.group(2).trim()).toString();
                    final Matcher r = RESOLUTION.matcher(attrs);
                    if (!r.find()) {
                        continue;
                    }
                    final int h = Math.min(Integer.parseInt(r.group(1)),
                            Integer.parseInt(r.group(2)));
                    final Matcher f = FRAME_RATE.matcher(attrs);
                    final double fps = f.find() ? Double.parseDouble(f.group(1)) : 0;
                    final String label = h + "p" + (fps > 30.5 ? String.valueOf(Math.round(fps))
                            : "");
                    if (seen.contains(label)) {
                        continue;
                    }
                    seen.add(label);
                    videos.add(new VideoStream.Builder()
                            .setId("dailymotion-" + getId() + "-" + label)
                            .setContent(url, true).setIsVideoOnly(false)
                            .setMediaFormat(MediaFormat.MPEG_4)
                            .setDeliveryMethod(DeliveryMethod.HLS)
                            .setResolution(label)
                            .setRequestHeaders(mediaHeaders())
                            .build());
                }
            } catch (final Exception e) {
                videos.clear();
            }
            if (videos.isEmpty()) {
                // fall back to the master playlist itself
                videos.add(new VideoStream.Builder()
                        .setId("dailymotion-" + getId())
                        .setContent(hlsUrl, true).setIsVideoOnly(false)
                        .setMediaFormat(MediaFormat.MPEG_4)
                        .setDeliveryMethod(DeliveryMethod.HLS)
                        .setResolution("720p")
                        .setRequestHeaders(mediaHeaders())
                        .build());
            }
            // highest quality first
            videos.sort((a, b) -> Integer.compare(height(b), height(a)));
        }

        private static int height(final VideoStream v) {
            try {
                return Integer.parseInt(v.getResolution().replaceAll("p.*", ""));
            } catch (final Exception e) {
                return 0;
            }
        }

        @Nonnull
        @Override
        public String getName() {
            return video.getString("title", meta.getString("title", "Dailymotion video"));
        }

        @Override
        public String getThumbnailUrl() {
            final JsonObject t = meta.getObject("thumbnails");
            return video.getString("thumbnail_720_url", t.getString("1080", t.getString("720")));
        }

        @Override
        public String getUploaderUrl() {
            final String u = video.getString("owner.username");
            return u == null ? meta.getObject("owner").getString("url", "") : channelUrl(u);
        }

        @Override
        public String getUploaderName() {
            return video.getString("owner.screenname",
                    meta.getObject("owner").getString("screenname", ""));
        }

        @Override
        public String getUploaderAvatarUrl() {
            return video.getString("owner.avatar_240_url");
        }

        @Override
        public boolean isUploaderVerified() {
            return video.getBoolean("owner.verified", false);
        }

        @Nonnull
        @Override
        public Description getDescription() {
            return new Description(video.getString("description", ""), Description.HTML);
        }

        @Override
        public long getLength() {
            return isLive(video) ? 0 : video.getLong("duration", 0);
        }

        @Override
        public long getViewCount() {
            return video.getLong("views_total", -1);
        }

        @Override
        public long getLikeCount() {
            return video.getLong("likes_total", -1);
        }

        @Override
        public int getAgeLimit() {
            return video.getBoolean("explicit", false) ? 18 : 0;
        }

        @Override
        public String getTextualUploadDate() {
            final DateWrapper d = getUploadDate();
            return d == null ? null : d.offsetDateTime().toString();
        }

        @Override
        public DateWrapper getUploadDate() {
            return date(video.getLong("created_time", 0));
        }

        @Override
        public String getCategory() {
            return video.getString("channel", "");
        }

        @Override
        public List<String> getTags() {
            final List<String> tags = new ArrayList<>();
            final JsonArray a = video.getArray("tags");
            for (int i = 0; i < a.size(); i++) {
                tags.add(a.getString(i));
            }
            return tags;
        }

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
            return isLive(video) ? StreamType.LIVE_STREAM : StreamType.VIDEO_STREAM;
        }

        @Override
        public boolean isSupportComments() {
            return false;
        }

        @Override
        public boolean isSupportBulletComments() {
            return false;
        }

        @Override
        public StreamInfoItemsCollector getRelatedItems() throws IOException, ExtractionException {
            try {
                return collect(getServiceId(), json(withPage(API + "/video/" + enc(getId())
                        + "/related", 1)));
            } catch (final Exception e) {
                return null;
            }
        }
    }

    /** A user's uploads, newest first. Id = username (or xid). */
    static final class UserExtractor extends ChannelExtractor {
        private JsonObject user;
        private String listUrl;
        private JsonObject first;

        UserExtractor(final StreamingService s, final ListLinkHandler h) {
            super(s, h);
        }

        @Override
        public void onFetchPage(@Nonnull final Downloader d)
                throws IOException, ExtractionException {
            user = json(API + "/user/" + enc(getId()) + "?fields=id,username,screenname,"
                    + "avatar_240_url,cover_url,description,followers_total,verified");
            listUrl = API + "/user/" + enc(user.getString("id", getId()))
                    + "/videos?sort=recent";
            first = json(withPage(listUrl, 1));
        }

        @Nonnull
        @Override
        public String getName() throws ParsingException {
            return user.getString("screenname", user.getString("username", getId()));
        }

        @Override
        public String getAvatarUrl() {
            return user.getString("avatar_240_url");
        }

        @Override
        public String getBannerUrl() {
            return user.getString("cover_url");
        }

        @Override
        public long getSubscriberCount() {
            return user.getLong("followers_total", -1);
        }

        @Override
        public String getDescription() {
            return user.getString("description", "");
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
            return user.getBoolean("verified", false);
        }

        @Nonnull
        @Override
        public InfoItemsPage<StreamInfoItem> getInitialPage() {
            return new InfoItemsPage<>(collect(getServiceId(), first), next(listUrl, first, 1));
        }

        @Override
        public InfoItemsPage<StreamInfoItem> getPage(final Page p)
                throws IOException, ExtractionException {
            final int page = Integer.parseInt(p.getId());
            final JsonObject resp = json(withPage(p.getUrl(), page));
            return new InfoItemsPage<>(collect(getServiceId(), resp), next(p.getUrl(), resp, page));
        }
    }

    /** Featured / trending videos. */
    static final class Front extends KioskExtractor<StreamInfoItem> {
        private JsonObject first;

        Front(final StreamingService s, final ListLinkHandler h, final String id) {
            super(s, h, id);
        }

        @Override
        public void onFetchPage(@Nonnull final Downloader d)
                throws IOException, ExtractionException {
            first = json(withPage(getUrl(), 1));
        }

        @Nonnull
        @Override
        public String getName() {
            return getId();
        }

        @Nonnull
        @Override
        public InfoItemsPage<StreamInfoItem> getInitialPage() throws ParsingException {
            return new InfoItemsPage<>(collect(getServiceId(), first), next(getUrl(), first, 1));
        }

        @Override
        public InfoItemsPage<StreamInfoItem> getPage(final Page p)
                throws IOException, ExtractionException {
            final int page = Integer.parseInt(p.getId());
            final JsonObject resp = json(withPage(p.getUrl(), page));
            return new InfoItemsPage<>(collect(getServiceId(), resp), next(p.getUrl(), resp, page));
        }
    }

    /** Video search; the first page also lists a few matching channels. */
    static final class Search extends SearchExtractor {
        private JsonObject first;
        private JsonObject users;
        private String listUrl;

        Search(final StreamingService s, final SearchQueryHandler h) {
            super(s, h);
        }

        @Override
        public void onFetchPage(@Nonnull final Downloader d)
                throws IOException, ExtractionException {
            listUrl = API + "/videos?search=" + enc(getSearchString()) + "&sort=relevance";
            first = json(withPage(listUrl, 1));
            try {
                users = json(API + "/users?search=" + enc(getSearchString())
                        + "&sort=relevance&limit=3&fields=username,screenname,avatar_240_url,"
                        + "description,followers_total,videos_total,verified");
            } catch (final Exception e) {
                users = new JsonObject();
            }
        }

        private void addVideos(final MultiInfoItemsCollector c, final JsonObject resp) {
            final JsonArray list = resp.getArray("list");
            for (int i = 0; i < list.size(); i++) {
                c.commit(new VideoItem(list.getObject(i)));
            }
        }

        @Override
        protected InfoItemsPage<InfoItem> getInitialPageInternal() {
            final MultiInfoItemsCollector collector = new MultiInfoItemsCollector(getServiceId());
            final JsonArray list = users.getArray("list");
            for (int i = 0; i < list.size(); i++) {
                final JsonObject u = list.getObject(i);
                if (u.getLong("videos_total", 0) <= 0) {
                    continue;
                }
                collector.commit(new ChannelInfoItemExtractor() {
                    @Override
                    public String getName() {
                        return u.getString("screenname", u.getString("username", ""));
                    }

                    @Override
                    public String getUrl() {
                        return channelUrl(u.getString("username", ""));
                    }

                    @Override
                    public String getThumbnailUrl() {
                        return u.getString("avatar_240_url");
                    }

                    @Override
                    public String getDescription() {
                        return u.getString("description", "");
                    }

                    @Override
                    public long getSubscriberCount() {
                        return u.getLong("followers_total", -1);
                    }

                    @Override
                    public long getStreamCount() {
                        return u.getLong("videos_total", -1);
                    }

                    @Override
                    public boolean isVerified() {
                        return u.getBoolean("verified", false);
                    }
                });
            }
            addVideos(collector, first);
            return new InfoItemsPage<>(collector, next(listUrl, first, 1));
        }

        @Override
        protected InfoItemsPage<InfoItem> getPageInternal(final Page p)
                throws IOException, ExtractionException {
            final int page = Integer.parseInt(p.getId());
            final JsonObject resp = json(withPage(p.getUrl(), page));
            final MultiInfoItemsCollector collector = new MultiInfoItemsCollector(getServiceId());
            addVideos(collector, resp);
            return new InfoItemsPage<>(collector, next(p.getUrl(), resp, page));
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
            for (final String id : new String[]{KIOSK_FEATURED, KIOSK_TRENDING}) {
                list.addKioskEntry((s, url, kid) -> new Front(DailymotionService.this,
                        lh.fromUrl(url), kid), lh, id);
            }
            list.setDefaultKiosk(KIOSK_FEATURED);
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
        return null;
    }
}
