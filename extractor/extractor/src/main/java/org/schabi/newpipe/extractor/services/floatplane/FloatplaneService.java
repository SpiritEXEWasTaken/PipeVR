package org.schabi.newpipe.extractor.services.floatplane;

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
 * PipeVR: Floatplane. Creator pages and post lists work signed out; playback, search and the
 * subscriptions feed need the floatplane.com website session (sails.sid cookie) of a
 * subscriber. API usage follows the MIT-licensed FloatNative app (github.com/coulterpeterson/
 * FloatNative) and Floatplane's public web API; sign-in uses the website, not FloatNative's
 * OAuth client.
 */
public class FloatplaneService extends StreamingService {
    static final String BASE = "https://www.floatplane.com";
    private static final String UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
    /** Linus Tech Tips: the front page signed out. */
    static final String LTT_ID = "59f94c0bdd241b70349eb72b";

    @Nullable
    private static volatile String cookie;

    public static void setCookie(@Nullable final String c) {
        cookie = c;
    }

    public static boolean isSignedIn() {
        return cookie != null && !cookie.isEmpty();
    }

    public FloatplaneService(final int id) {
        super(id, "Floatplane", Arrays.asList(VIDEO, LIVE));
    }

    static Map<String, String> mediaHeaders() {
        final Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", UA);
        if (cookie != null) {
            h.put("Cookie", cookie);
        }
        return h;
    }

    static Object api(final String path) throws IOException, ExtractionException {
        final Map<String, List<String>> h = new HashMap<>();
        for (final Map.Entry<String, String> e : mediaHeaders().entrySet()) {
            h.put(e.getKey(), Collections.singletonList(e.getValue()));
        }
        h.put("Accept", Collections.singletonList("application/json"));
        final Response r = NewPipe.getDownloader().get(BASE + path, h);
        if (r.responseCode() == 401 || r.responseCode() == 403) {
            throw new ContentNotAvailableException(isSignedIn()
                    ? "Floatplane: your subscription doesn't include this"
                    : "Sign in to Floatplane (side menu) with a subscribed account");
        }
        final Object parsed;
        try {
            parsed = JsonParser.any().from(r.responseBody());
        } catch (final Exception e) {
            throw new ParsingException("Unexpected Floatplane response (HTTP "
                    + r.responseCode() + ")", e);
        }
        // Floatplane explains refused requests in an "errors" object: show it
        if (parsed instanceof JsonObject && ((JsonObject) parsed).has("errors")
                && r.responseCode() >= 400) {
            throw new ExtractionException("Floatplane: "
                    + ((JsonObject) parsed).getString("message", "HTTP " + r.responseCode()));
        }
        return parsed;
    }

    static JsonObject apiObject(final String path) throws IOException, ExtractionException {
        final Object o = api(path);
        return o instanceof JsonObject ? (JsonObject) o : new JsonObject();
    }

    static JsonArray apiArray(final String path) throws IOException, ExtractionException {
        final Object o = api(path);
        return o instanceof JsonArray ? (JsonArray) o : new JsonArray();
    }

    static String enc(final String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    static String postUrl(final String id) {
        return BASE + "/post/" + id;
    }

    static String creatorUrl(final String urlname) {
        return BASE + "/channel/" + urlname + "/home";
    }

    @Nullable
    static String image(final JsonObject img) {
        final JsonArray children = img.getArray("childImages");
        if (!children.isEmpty()) {
            return children.getObject(0).getString("path", img.getString("path"));
        }
        return img.getString("path");
    }

    /** A post in a list. */
    static final class PostItem implements StreamInfoItemExtractor {
        private final JsonObject post;

        PostItem(final JsonObject post) {
            this.post = post;
        }

        private JsonObject creator() {
            return post.get("creator") instanceof JsonObject ? post.getObject("creator")
                    : new JsonObject();
        }

        @Override
        public String getName() {
            return (post.getBoolean("isAccessible", true) ? "" : "🔒 ")
                    + post.getString("title", "Floatplane post");
        }

        @Override
        public String getUrl() {
            return postUrl(post.getString("id"));
        }

        @Override
        public String getThumbnailUrl() {
            return image(post.getObject("thumbnail"));
        }

        @Override
        public StreamType getStreamType() {
            return StreamType.VIDEO_STREAM;
        }

        @Override
        public long getDuration() {
            return post.getObject("metadata").getLong("videoDuration", -1);
        }

        @Override
        public long getViewCount() {
            return -1;
        }

        @Override
        public String getUploaderName() {
            return creator().getString("title", "");
        }

        @Override
        public String getUploaderUrl() {
            final String urlname = creator().getString("urlname");
            return urlname == null ? "" : creatorUrl(urlname);
        }

        @Override
        public String getUploaderAvatarUrl() {
            return image(creator().getObject("icon"));
        }

        @Override
        public String getTextualUploadDate() {
            return post.getString("releaseDate");
        }

        @Override
        public DateWrapper getUploadDate() {
            try {
                return new DateWrapper(OffsetDateTime.parse(post.getString("releaseDate")));
            } catch (final Exception e) {
                return null;
            }
        }
    }

    static StreamInfoItemsCollector collect(final int serviceId, final JsonArray posts) {
        final StreamInfoItemsCollector collector = new StreamInfoItemsCollector(serviceId);
        for (int i = 0; i < posts.size(); i++) {
            final JsonObject p = posts.getObject(i);
            if (p.getObject("metadata").getBoolean("hasVideo", true)) {
                collector.commit(new PostItem(p));
            }
        }
        return collector;
    }

    // ---- link handlers ----

    static final class StreamLH extends LinkHandlerFactory {
        private static final Pattern POST = Pattern.compile(
                "^https?://(?:www\\.|beta\\.)?floatplane\\.com/post/([A-Za-z0-9]+)");

        @Override
        public String getId(final String url) throws ParsingException {
            final Matcher m = POST.matcher(url);
            if (m.find()) {
                return m.group(1);
            }
            throw new ParsingException("Not a Floatplane post: " + url);
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
        private static final Pattern CHANNEL = Pattern.compile(
                "^https?://(?:www\\.|beta\\.)?floatplane\\.com/channel/([A-Za-z0-9_-]+)");

        @Override
        public String getId(final String url) throws ParsingException {
            final Matcher m = CHANNEL.matcher(url);
            if (m.find()) {
                return m.group(1);
            }
            throw new ParsingException("Not a Floatplane creator: " + url);
        }

        @Override
        public String getUrl(final String id, final List<FilterItem> c, final List<FilterItem> s) {
            return creatorUrl(id);
        }

        @Override
        public boolean onAcceptUrl(final String url) {
            return CHANNEL.matcher(url).find();
        }
    }

    static final class SearchLH extends SearchQueryHandlerFactory {
        private static final String PREFIX = BASE + "/browse?search=";

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
            return "Subscriptions".equals(id) ? BASE + "/" : creatorUrl("linustechtips");
        }

        @Override
        public String getId(final String url) {
            return url.equals(BASE + "/") ? "Subscriptions" : "Linus Tech Tips";
        }

        @Override
        public boolean onAcceptUrl(final String url) {
            // signed out, the front page is a creator's page (it was refused before)
            return url.equals(BASE + "/") || url.equals(creatorUrl("linustechtips"));
        }
    }

    // ---- extractors ----

    /** A video post (or a creator's livestream, id "live:{creatorId}"). */
    static final class PostExtractor extends StreamExtractor {
        private JsonObject post;
        private boolean live;
        private final List<VideoStream> videos = new ArrayList<>();
        private String liveUrl;

        PostExtractor(final StreamingService s, final LinkHandler h) {
            super(s, h);
        }

        private JsonArray variantsOf(final JsonObject delivery, final List<String> urls,
                                     final List<String> labels) {
            final JsonArray groups = delivery.getArray("groups");
            for (int g = 0; g < groups.size(); g++) {
                final JsonObject group = groups.getObject(g);
                final JsonArray variants = group.getArray("variants");
                for (int v = 0; v < variants.size(); v++) {
                    final JsonObject variant = variants.getObject(v);
                    if (!variant.getBoolean("enabled", true) || variant.getBoolean("hidden",
                            false)) {
                        continue;
                    }
                    String url = variant.getString("url", "");
                    if (!url.startsWith("http")) {
                        JsonArray origins = variant.getArray("origins");
                        if (origins.isEmpty()) {
                            origins = group.getArray("origins");
                        }
                        if (origins.isEmpty()) {
                            continue;
                        }
                        url = origins.getObject(0).getString("url", "").replaceAll("/+$", "")
                                + "/" + url.replaceAll("^/+", "");
                    }
                    urls.add(url);
                    labels.add(variant.getString("label", variant.getString("name", "")));
                }
            }
            return groups;
        }

        @Override
        public void onFetchPage(@Nonnull final Downloader d)
                throws IOException, ExtractionException {
            post = apiObject("/api/v3/content/post?id=" + enc(getId()));
            if (!post.getBoolean("isAccessible", true)) {
                throw new ContentNotAvailableException(isSignedIn()
                        ? "Floatplane: your subscription doesn't include this post"
                        : "Floatplane posts are for subscribers: sign in (side menu)");
            }
            // the first video in display order (videoAttachments comes back unordered)
            String videoId = null;
            final JsonArray order = post.getArray("attachmentOrder");
            final List<String> videoIds = new ArrayList<>();
            final JsonArray attachments = post.getArray("videoAttachments");
            for (int i = 0; i < attachments.size(); i++) {
                final Object a = attachments.get(i);
                videoIds.add(a instanceof JsonObject ? ((JsonObject) a).getString("id")
                        : String.valueOf(a));
            }
            for (int i = 0; i < order.size() && videoId == null; i++) {
                if (videoIds.contains(order.getString(i))) {
                    videoId = order.getString(i);
                }
            }
            if (videoId == null && !videoIds.isEmpty()) {
                videoId = videoIds.get(0);
            }
            if (videoId == null) {
                throw new ContentNotAvailableException("This Floatplane post has no video");
            }
            final JsonObject delivery = apiObject("/api/v3/delivery/info?scenario=onDemand"
                    + "&entityId=" + enc(videoId) + "&outputKind=hls.fmp4");
            final List<String> urls = new ArrayList<>();
            final List<String> labels = new ArrayList<>();
            variantsOf(delivery, urls, labels);
            for (int i = 0; i < urls.size(); i++) {
                final String label = labels.get(i).matches("\\d+p.*") ? labels.get(i)
                        : labels.get(i).equalsIgnoreCase("4K") ? "2160p" : "1080p";
                videos.add(new VideoStream.Builder()
                        .setId("floatplane-" + videoId + "-" + i)
                        .setContent(urls.get(i), true).setIsVideoOnly(false)
                        .setMediaFormat(MediaFormat.MPEG_4)
                        .setDeliveryMethod(DeliveryMethod.HLS)
                        .setResolution(label)
                        .setRequestHeaders(mediaHeaders())
                        .build());
            }
            if (videos.isEmpty()) {
                throw new ContentNotAvailableException("Floatplane did not provide this video");
            }
        }

        @Nonnull
        @Override
        public String getName() {
            return post.getString("title", "Floatplane post");
        }

        @Override
        public String getThumbnailUrl() {
            return image(post.getObject("thumbnail"));
        }

        private JsonObject creator() {
            return post.get("creator") instanceof JsonObject ? post.getObject("creator")
                    : new JsonObject();
        }

        @Override
        public String getUploaderUrl() {
            final String u = creator().getString("urlname");
            return u == null ? "" : creatorUrl(u);
        }

        @Override
        public String getUploaderName() {
            return creator().getString("title", "");
        }

        @Override
        public String getUploaderAvatarUrl() {
            return image(creator().getObject("icon"));
        }

        @Nonnull
        @Override
        public Description getDescription() {
            return new Description(post.getString("text", ""), Description.HTML);
        }

        @Override
        public long getLength() {
            return post.getObject("metadata").getLong("videoDuration", 0);
        }

        @Override
        public long getLikeCount() {
            return post.getLong("likes", -1);
        }

        @Override
        public long getDislikeCount() {
            return post.getLong("dislikes", -1);
        }

        @Override
        public String getTextualUploadDate() {
            return post.getString("releaseDate");
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
            return StreamType.VIDEO_STREAM;
        }
    }

    /** A creator's posts (newest first), loaded by offset. */
    static final class CreatorExtractor extends ChannelExtractor {
        private JsonObject creator;
        private JsonArray posts;

        CreatorExtractor(final StreamingService s, final ListLinkHandler h) {
            super(s, h);
        }

        private JsonArray fetch(final int offset) throws IOException, ExtractionException {
            return apiArray("/api/v3/content/creator?id=" + enc(creator.getString("id"))
                    + "&limit=20&fetchAfter=" + offset);
        }

        @Override
        public void onFetchPage(@Nonnull final Downloader d)
                throws IOException, ExtractionException {
            final JsonArray found = apiArray("/api/v3/creator/named?creatorURL%5B%5D="
                    + enc(getId()));
            if (found.isEmpty()) {
                throw new ContentNotAvailableException("Floatplane creator not found");
            }
            creator = found.getObject(0);
            posts = fetch(0);
        }

        @Nonnull
        @Override
        public String getName() {
            return creator.getString("title", "Floatplane");
        }

        @Override
        public String getAvatarUrl() {
            return image(creator.getObject("icon"));
        }

        @Override
        public String getBannerUrl() {
            return image(creator.getObject("cover"));
        }

        @Override
        public long getSubscriberCount() {
            return -1;
        }

        @Override
        public String getDescription() {
            return creator.getString("description", "");
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

        private InfoItemsPage<StreamInfoItem> page(final JsonArray list, final int offset)
                throws ParsingException {
            return new InfoItemsPage<>(collect(getServiceId(), list), list.size() < 20 ? null
                    : new Page(getUrl(), String.valueOf(offset + list.size())));
        }

        @Nonnull
        @Override
        public InfoItemsPage<StreamInfoItem> getInitialPage() throws ParsingException {
            return page(posts, 0);
        }

        @Override
        public InfoItemsPage<StreamInfoItem> getPage(final Page p)
                throws IOException, ExtractionException {
            final int offset = Integer.parseInt(p.getId());
            return page(fetch(offset), offset);
        }
    }

    /** Front page: subscriptions feed when signed in, Linus Tech Tips' posts otherwise. */
    static final class Front extends KioskExtractor<StreamInfoItem> {
        private JsonArray posts;
        private boolean subscriptions;

        Front(final StreamingService s, final ListLinkHandler h, final String id) {
            super(s, h, id);
        }

        @Override
        public void onFetchPage(@Nonnull final Downloader d)
                throws IOException, ExtractionException {
            subscriptions = "Subscriptions".equals(getId());
            if (subscriptions) {
                final JsonArray subs = apiArray("/api/v3/user/subscriptions");
                final StringBuilder q = new StringBuilder();
                for (int i = 0; i < subs.size(); i++) {
                    q.append("&ids%5B").append(i).append("%5D=")
                            .append(enc(subs.getObject(i).getString("creator", "")));
                }
                if (q.length() == 0) {
                    throw new ContentNotAvailableException("No Floatplane subscriptions");
                }
                posts = apiObject("/api/v3/content/creator/list?limit=20" + q)
                        .getArray("blogPosts");
            } else {
                posts = apiArray("/api/v3/content/creator?id=" + LTT_ID + "&limit=20");
            }
        }

        @Nonnull
        @Override
        public String getName() {
            return getId();
        }

        @Nonnull
        @Override
        public InfoItemsPage<StreamInfoItem> getInitialPage() {
            return new InfoItemsPage<>(collect(getServiceId(), posts), null);
        }

        @Override
        public InfoItemsPage<StreamInfoItem> getPage(final Page page) {
            return InfoItemsPage.emptyPage();
        }
    }

    /** Creator search (needs sign-in). */
    static final class Search extends SearchExtractor {
        private JsonArray creators;

        Search(final StreamingService s, final SearchQueryHandler h) {
            super(s, h);
        }

        @Override
        public void onFetchPage(@Nonnull final Downloader d)
                throws IOException, ExtractionException {
            creators = apiArray("/api/v3/creator/list?search=" + enc(getSearchString()));
        }

        @Override
        protected InfoItemsPage<InfoItem> getInitialPageInternal() {
            final MultiInfoItemsCollector collector = new MultiInfoItemsCollector(getServiceId());
            for (int i = 0; i < creators.size(); i++) {
                final JsonObject c = creators.getObject(i);
                collector.commit(new ChannelInfoItemExtractor() {
                    @Override
                    public String getName() {
                        return c.getString("title", "");
                    }

                    @Override
                    public String getUrl() {
                        return creatorUrl(c.getString("urlname", ""));
                    }

                    @Override
                    public String getThumbnailUrl() {
                        return image(c.getObject("icon"));
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
            final String id = isSignedIn() ? "Subscriptions" : "Linus Tech Tips";
            list.addKioskEntry((s, url, kid) -> new Front(FloatplaneService.this,
                    lh.fromUrl(url), kid), lh, id);
            list.setDefaultKiosk(id);
        } catch (final Exception e) {
            throw new ExtractionException(e);
        }
        return list;
    }

    @Override
    public ChannelExtractor getChannelExtractor(final ListLinkHandler h) {
        return new CreatorExtractor(this, h);
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
