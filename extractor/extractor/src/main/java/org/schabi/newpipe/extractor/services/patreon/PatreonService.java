package org.schabi.newpipe.extractor.services.patreon;

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
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static org.schabi.newpipe.extractor.StreamingService.ServiceInfo.MediaCapability.AUDIO;
import static org.schabi.newpipe.extractor.StreamingService.ServiceInfo.MediaCapability.VIDEO;

/**
 * PipeVR: Patreon (creators' video/audio posts; YouTube-linked posts open in the YouTube
 * player). Written from the JSON API the patreon.com website uses.
 */
public class PatreonService extends StreamingService {
    static final String BASE = "https://www.patreon.com";
    private static final String UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
    private static final String POST_FIELDS = "fields%5Bpost%5D=title%2Cpost_type%2C"
            + "published_at%2Cimage%2Cthumbnail%2Curl%2Ccurrent_user_can_view%2Cpost_file%2C"
            + "embed%2Cteaser_text%2Ccontent&fields%5Bcampaign%5D=name%2Cavatar_photo_url%2Curl"
            + "&include=campaign&json-api-version=1.0";

    /** Cookie header of the signed-in Patreon account (session_id), or null. */
    @Nullable
    private static volatile String cookie;

    public static void setCookie(@Nullable final String c) {
        cookie = c;
    }

    public static boolean isSignedIn() {
        return cookie != null && !cookie.isEmpty();
    }

    public PatreonService(final int id) {
        super(id, "Patreon", Arrays.asList(VIDEO, AUDIO));
    }

    static JsonObject api(final String path) throws IOException, ExtractionException {
        final Map<String, List<String>> h = new HashMap<>();
        h.put("User-Agent", Collections.singletonList(UA));
        h.put("Accept", Collections.singletonList("application/vnd.api+json"));
        if (cookie != null) {
            h.put("Cookie", Collections.singletonList(cookie));
        }
        final Response r = NewPipe.getDownloader().get(BASE + path, h);
        if (r.responseCode() == 401 || r.responseCode() == 403) {
            throw new ContentNotAvailableException("Sign in to Patreon to see this");
        }
        try {
            return JsonParser.object().from(r.responseBody());
        } catch (final Exception e) {
            throw new ParsingException("Unexpected Patreon response (HTTP "
                    + r.responseCode() + ")", e);
        }
    }

    static String enc(final String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    static String campaignUrl(final String id) {
        return BASE + "/pipevr-campaign/" + id;
    }

    static String postUrl(final String id) {
        return BASE + "/posts/" + id;
    }

    /** Campaign (name, avatar) included in a posts response, by id. */
    static Map<String, JsonObject> campaigns(final JsonObject response) {
        final Map<String, JsonObject> out = new HashMap<>();
        final JsonArray included = response.getArray("included");
        for (int i = 0; i < included.size(); i++) {
            final JsonObject o = included.getObject(i);
            if ("campaign".equals(o.getString("type"))) {
                out.put(o.getString("id"), o.getObject("attributes"));
            }
        }
        return out;
    }

    static boolean isMedia(final JsonObject attributes) {
        final String type = attributes.getString("post_type", "");
        // video_external_file: Patreon's own video uploads (Mux HLS), the common type now
        return type.equals("video_file") || type.equals("audio_file")
                || type.equals("video_external_file")
                || type.equals("video_embed") || type.equals("podcast")
                || type.equals("livestream_youtube") || type.equals("livestream_crowdcast");
    }

    /** A post in a list. YouTube-embed posts point straight at the YouTube video. */
    static final class PostItem implements StreamInfoItemExtractor {
        private final JsonObject post;
        private final JsonObject attributes;
        private final JsonObject campaign;

        PostItem(final JsonObject post, final JsonObject campaign) {
            this.post = post;
            this.attributes = post.getObject("attributes");
            this.campaign = campaign == null ? new JsonObject() : campaign;
        }

        @Override
        public String getName() {
            // locked: Patreon says so, or it gives no video for a video post
            final String t = attributes.getString("post_type", "");
            final boolean needsFile = t.equals("video_file") || t.equals("video_external_file")
                    || t.equals("audio_file") || t.equals("podcast");
            final boolean noFile = attributes.getObject("post_file").getString("url") == null
                    && attributes.getObject("embed").getString("url") == null;
            final boolean locked = !attributes.getBoolean("current_user_can_view", true)
                    || (needsFile && noFile);
            final String type = attributes.getString("post_type", "");
            final String icon = type.equals("audio_file") || type.equals("podcast") ? "🎵 " : "";
            return (locked ? "🔒 " : "") + icon + attributes.getString("title", "Patreon post");
        }

        @Override
        public String getUrl() {
            final String embed = attributes.getObject("embed").getString("url");
            if (embed != null && (embed.contains("youtu.be") || embed.contains("youtube.com"))) {
                return embed;
            }
            return postUrl(post.getString("id"));
        }

        @Override
        public String getThumbnailUrl() {
            final JsonObject image = attributes.getObject("image");
            final String url = image.getString("large_url", image.getString("url"));
            return url != null ? url : attributes.getObject("thumbnail").getString("large",
                    attributes.getObject("post_file").getString("default_thumbnail", null));
        }

        @Override
        public StreamType getStreamType() {
            final String type = attributes.getString("post_type", "");
            return type.equals("audio_file") || type.equals("podcast")
                    ? StreamType.AUDIO_STREAM : StreamType.VIDEO_STREAM;
        }

        @Override
        public long getDuration() {
            return attributes.getObject("post_file").getLong("duration", -1);
        }

        @Override
        public long getViewCount() {
            return -1;
        }

        @Override
        public String getUploaderName() {
            return campaign.getString("name", "");
        }

        @Override
        public String getUploaderUrl() {
            final String id = post.getObject("relationships").getObject("campaign")
                    .getObject("data").getString("id");
            return id == null ? "" : campaignUrl(id);
        }

        @Override
        public String getUploaderAvatarUrl() {
            return campaign.getString("avatar_photo_url");
        }

        @Override
        public String getTextualUploadDate() {
            return attributes.getString("published_at");
        }

        @Override
        public DateWrapper getUploadDate() {
            try {
                return new DateWrapper(OffsetDateTime.parse(attributes.getString("published_at")));
            } catch (final Exception e) {
                return null;
            }
        }
    }

    static StreamInfoItemsCollector collectPosts(final int serviceId, final JsonObject response) {
        final StreamInfoItemsCollector collector = new StreamInfoItemsCollector(serviceId);
        final Map<String, JsonObject> campaigns = campaigns(response);
        final JsonArray data = response.getArray("data");
        for (int i = 0; i < data.size(); i++) {
            final JsonObject post = data.getObject(i);
            if (!isMedia(post.getObject("attributes"))) {
                continue;
            }
            final String campaignId = post.getObject("relationships").getObject("campaign")
                    .getObject("data").getString("id");
            collector.commit(new PostItem(post, campaigns.get(campaignId)));
        }
        return collector;
    }

    @Nullable
    static String nextCursor(final JsonObject response) {
        return response.getObject("meta").getObject("pagination").getObject("cursors")
                .getString("next");
    }

    // ---- link handlers ----

    static final class StreamLH extends LinkHandlerFactory {
        private static final Pattern POST = Pattern.compile(
                "^https?://(?:www\\.)?patreon\\.com/posts/(?:[^/?#]*-)?(\\d+)");

        @Override
        public String getId(final String url) throws ParsingException {
            final Matcher m = POST.matcher(url);
            if (m.find()) {
                return m.group(1);
            }
            throw new ParsingException("Not a Patreon post: " + url);
        }

        @Override
        public String getUrl(final String id) {
            return postUrl(id);
        }

        @Override
        public boolean onAcceptUrl(final String url) {
            return POST.matcher(url).find();
        }
    }

    static final class ChannelLH extends ListLinkHandlerFactory {
        private static final Pattern CAMPAIGN = Pattern.compile(
                "^https?://(?:www\\.)?patreon\\.com/pipevr-campaign/(\\d+)");
        private static final Pattern VANITY = Pattern.compile(
                "^https?://(?:www\\.)?patreon\\.com/(?:c/)?([A-Za-z0-9_-]+)/?(?:posts)?/?$");

        @Override
        public String getId(final String url) throws ParsingException {
            Matcher m = CAMPAIGN.matcher(url);
            if (m.find()) {
                return m.group(1);
            }
            m = VANITY.matcher(url);
            if (m.find()) {
                return "vanity:" + m.group(1);
            }
            throw new ParsingException("Not a Patreon creator: " + url);
        }

        @Override
        public String getUrl(final String id, final List<FilterItem> c, final List<FilterItem> s) {
            return id.startsWith("vanity:") ? BASE + "/" + id.substring(7) : campaignUrl(id);
        }

        @Override
        public boolean onAcceptUrl(final String url) {
            return CAMPAIGN.matcher(url).find() || (VANITY.matcher(url).find()
                    && !url.contains("/posts/") && !url.contains("/login"));
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
        @Override
        public String getUrl(final String id, final List<FilterItem> c, final List<FilterItem> s) {
            return BASE + "/home";
        }

        @Override
        public String getId(final String url) {
            return "Home";
        }

        @Override
        public boolean onAcceptUrl(final String url) {
            return url.equals(BASE + "/home");
        }
    }

    // ---- extractors ----

    /** A video, audio or podcast post. */
    static final class PostExtractor extends StreamExtractor {
        private JsonObject attributes;
        private JsonObject campaign;
        private String campaignId;

        PostExtractor(final StreamingService s, final LinkHandler h) {
            super(s, h);
        }

        @Override
        public void onFetchPage(@Nonnull final Downloader d)
                throws IOException, ExtractionException {
            final JsonObject response = api("/api/posts/" + getId() + "?" + POST_FIELDS);
            final JsonObject post = response.getObject("data");
            attributes = post.getObject("attributes");
            campaignId = post.getObject("relationships").getObject("campaign").getObject("data")
                    .getString("id");
            campaign = campaigns(response).getOrDefault(campaignId, new JsonObject());
            if (!attributes.getBoolean("current_user_can_view", true)) {
                throw new ContentNotAvailableException(isSignedIn()
                        ? "Your Patreon membership doesn't include this post"
                        : "This Patreon post is for members: sign in to Patreon");
            }
            final String embed = attributes.getObject("embed").getString("url");
            if (attributes.getObject("post_file").getString("url") == null && embed != null) {
                throw new ContentNotAvailableException("This post links to " + embed);
            }
            // a video post without its video: Patreon didn't give it to this account
            if (attributes.getObject("post_file").getString("url") == null && embed == null) {
                throw new ContentNotAvailableException(isSignedIn()
                        ? "Your Patreon membership doesn't include this post"
                        : "This Patreon post is for members: sign in to Patreon");
            }
        }

        @Nonnull
        @Override
        public String getName() {
            return attributes.getString("title", "Patreon post");
        }

        @Override
        public String getThumbnailUrl() {
            final JsonObject image = attributes.getObject("image");
            return image.getString("large_url", image.getString("url"));
        }

        @Override
        public String getUploaderUrl() {
            return campaignId == null ? "" : campaignUrl(campaignId);
        }

        @Override
        public String getUploaderName() {
            return campaign.getString("name", "");
        }

        @Override
        public String getUploaderAvatarUrl() {
            return campaign.getString("avatar_photo_url");
        }

        @Nonnull
        @Override
        public Description getDescription() {
            final String html = attributes.getString("content", attributes.getString(
                    "teaser_text", ""));
            return new Description(html == null ? "" : html, Description.HTML);
        }

        @Override
        public long getLength() {
            return attributes.getObject("post_file").getLong("duration", 0);
        }

        @Override
        public String getTextualUploadDate() {
            return attributes.getString("published_at");
        }

        private boolean isAudio() {
            final String type = attributes.getString("post_type", "");
            return type.equals("audio_file") || type.equals("podcast");
        }

        private String fileUrl() {
            return attributes.getObject("post_file").getString("url");
        }

        @Override
        public List<AudioStream> getAudioStreams() throws ExtractionException {
            if (!isAudio() || fileUrl() == null) {
                return Collections.emptyList();
            }
            return Collections.singletonList(new AudioStream.Builder()
                    .setId("patreon-" + getId()).setContent(fileUrl(), true)
                    .setMediaFormat(MediaFormat.MP3).setAverageBitrate(128)
                    .build());
        }

        @Override
        public List<VideoStream> getVideoStreams() throws ExtractionException {
            if (isAudio() || fileUrl() == null) {
                return Collections.emptyList();
            }
            final String url = fileUrl();
            final boolean hls = url.contains(".m3u8");
            final JsonObject file = attributes.getObject("post_file");
            // Patreon's video host expects the request to come from patreon.com
            final Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", UA);
            headers.put("Referer", BASE + "/");
            headers.put("Origin", BASE);
            return Collections.singletonList(new VideoStream.Builder()
                    .setRequestHeaders(headers)
                    .setId("patreon-" + getId()).setContent(url, true).setIsVideoOnly(false)
                    .setMediaFormat(MediaFormat.MPEG_4)
                    .setDeliveryMethod(hls ? DeliveryMethod.HLS : DeliveryMethod.PROGRESSIVE_HTTP)
                    .setResolution(file.getInt("height", 0) > 0
                            ? file.getInt("height", 0) + "p" : "720p")
                    .build());
        }

        @Override
        public List<VideoStream> getVideoOnlyStreams() {
            return Collections.emptyList();
        }

        @Override
        public StreamType getStreamType() {
            return isAudio() ? StreamType.AUDIO_STREAM : StreamType.VIDEO_STREAM;
        }
    }

    /** A creator: their video/audio posts, newest first. */
    static final class CampaignExtractor extends ChannelExtractor {
        private String campaignId;
        private JsonObject campaign;
        private JsonObject posts;

        CampaignExtractor(final StreamingService s, final ListLinkHandler h) {
            super(s, h);
        }

        private JsonObject fetchPosts(@Nullable final String cursor)
                throws IOException, ExtractionException {
            return api("/api/posts?filter%5Bcampaign_id%5D=" + campaignId
                    + "&sort=-published_at&page%5Bsize%5D=30"
                    + (cursor != null ? "&page%5Bcursor%5D=" + enc(cursor) : "")
                    + "&" + POST_FIELDS);
        }

        @Override
        public void onFetchPage(@Nonnull final Downloader d)
                throws IOException, ExtractionException {
            campaignId = getId();
            if (campaignId.startsWith("vanity:")) {
                final JsonArray found = api("/api/campaigns?filter%5Bvanity%5D="
                        + enc(campaignId.substring(7))).getArray("data");
                if (found.isEmpty()) {
                    throw new ContentNotAvailableException("Patreon creator not found");
                }
                campaignId = found.getObject(0).getString("id");
            }
            campaign = api("/api/campaigns/" + campaignId + "?json-api-version=1.0")
                    .getObject("data").getObject("attributes");
            posts = fetchPosts(null);
        }

        @Nonnull
        @Override
        public String getName() {
            return campaign.getString("name", "Patreon");
        }

        @Override
        public String getAvatarUrl() {
            return campaign.getString("avatar_photo_url");
        }

        @Override
        public String getBannerUrl() {
            return campaign.getString("cover_photo_url");
        }

        @Override
        public long getSubscriberCount() {
            return campaign.getLong("patron_count", -1);
        }

        @Override
        public String getDescription() {
            return campaign.getString("creation_name", "");
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

        private InfoItemsPage<StreamInfoItem> toPage(final JsonObject response)
                throws ParsingException {
            final String next = nextCursor(response);
            return new InfoItemsPage<>(collectPosts(getServiceId(), response),
                    next == null ? null : new Page(getUrl(), next));
        }

        @Nonnull
        @Override
        public InfoItemsPage<StreamInfoItem> getInitialPage() throws ParsingException {
            return toPage(posts);
        }

        @Override
        public InfoItemsPage<StreamInfoItem> getPage(final Page page)
                throws IOException, ExtractionException {
            return toPage(fetchPosts(page.getId()));
        }
    }

    /** Signed in: the home feed (posts of the creators you follow or support). */
    static final class HomeExtractor extends KioskExtractor<StreamInfoItem> {
        private JsonObject feed;

        HomeExtractor(final StreamingService s, final ListLinkHandler h, final String id) {
            super(s, h, id);
        }

        private JsonObject fetch(@Nullable final String cursor)
                throws IOException, ExtractionException {
            if (!isSignedIn()) {
                throw new ContentNotAvailableException(
                        "Sign in to Patreon (side menu) to see your feed, or search for a creator");
            }
            return api("/api/stream?page%5Bsize%5D=30"
                    + (cursor != null ? "&page%5Bcursor%5D=" + enc(cursor) : "")
                    + "&" + POST_FIELDS);
        }

        @Override
        public void onFetchPage(@Nonnull final Downloader d)
                throws IOException, ExtractionException {
            feed = fetch(null);
        }

        @Nonnull
        @Override
        public String getName() {
            return getId();
        }

        @Nonnull
        @Override
        public InfoItemsPage<StreamInfoItem> getInitialPage() throws ParsingException {
            final String next = nextCursor(feed);
            return new InfoItemsPage<>(collectPosts(getServiceId(), feed),
                    next == null ? null : new Page(getUrl(), next));
        }

        @Override
        public InfoItemsPage<StreamInfoItem> getPage(final Page page)
                throws IOException, ExtractionException {
            final JsonObject more = fetch(page.getId());
            final String next = nextCursor(more);
            return new InfoItemsPage<>(collectPosts(getServiceId(), more),
                    next == null ? null : new Page(getUrl(), next));
        }
    }

    /** Creator search. */
    static final class Search extends SearchExtractor {
        private JsonObject result;

        Search(final StreamingService s, final SearchQueryHandler h) {
            super(s, h);
        }

        @Override
        public void onFetchPage(@Nonnull final Downloader d)
                throws IOException, ExtractionException {
            result = api("/api/search?q=" + enc(getSearchString())
                    + "&page%5Bsize%5D=25&json-api-version=1.0");
        }

        @Override
        protected InfoItemsPage<InfoItem> getInitialPageInternal() {
            final MultiInfoItemsCollector collector = new MultiInfoItemsCollector(getServiceId());
            final JsonArray data = result.getArray("data");
            for (int i = 0; i < data.size(); i++) {
                final JsonObject item = data.getObject(i);
                final String id = item.getString("id", "").replace("campaign_", "");
                final JsonObject a = item.getObject("attributes");
                collector.commit(new ChannelInfoItemExtractor() {
                    @Override
                    public String getName() {
                        return a.getString("name", "");
                    }

                    @Override
                    public String getUrl() {
                        return campaignUrl(id);
                    }

                    @Override
                    public String getThumbnailUrl() {
                        return a.getString("avatar_photo_url");
                    }

                    @Override
                    public String getDescription() {
                        return a.getString("creation_name", a.getString("summary", ""));
                    }

                    @Override
                    public long getSubscriberCount() {
                        return a.getLong("patron_count", -1);
                    }

                    @Override
                    public long getStreamCount() {
                        return -1;
                    }

                    @Override
                    public boolean isVerified() {
                        return false;
                    }
                });
            }
            return new InfoItemsPage<>(collector, null);
        }

        @Override
        protected InfoItemsPage<InfoItem> getPageInternal(final Page page) {
            return InfoItemsPage.emptyPage();
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
            list.addKioskEntry((s, url, id) -> new HomeExtractor(PatreonService.this,
                    lh.fromUrl(url), id), lh, "Home");
            list.setDefaultKiosk("Home");
        } catch (final Exception e) {
            throw new ExtractionException(e);
        }
        return list;
    }

    @Override
    public ChannelExtractor getChannelExtractor(final ListLinkHandler h) {
        return new CampaignExtractor(this, h);
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
        return new PostExtractor(this, h);
    }

    @Override
    public CommentsExtractor getCommentsExtractor(final ListLinkHandler h) {
        return null;
    }
}
