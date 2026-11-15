package org.schabi.newpipe.extractor.services.kick.linkHandler;

import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.LinkHandlerFactory;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandlerFactory;
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandlerFactory;
import org.schabi.newpipe.extractor.search.filter.FilterItem;
import org.schabi.newpipe.extractor.services.kick.KickApi;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** PipeVR: Kick link handlers. */
public final class KickLinkHandlers {
    private static final Set<String> RESERVED = new HashSet<>(Arrays.asList("video", "browse",
            "categories", "search", "following", "dashboard", "settings", "terms-of-service",
            "privacy-policy", "community-guidelines", "api", "stream", "clips", "category"));

    private KickLinkHandlers() {
    }

    /** kick.com/{slug} (live, "live:{slug}"), kick.com/video/{uuid} or /{slug}/videos/{uuid}. */
    public static class Stream extends LinkHandlerFactory {
        private static final Pattern VOD = Pattern.compile(
                "^https?://(?:www\\.)?kick\\.com/(?:video|[A-Za-z0-9_-]+/videos)/([0-9a-f-]{36})");
        private static final Pattern LIVE = Pattern.compile(
                "^https?://(?:www\\.)?kick\\.com/([A-Za-z0-9_-]{2,40})/?(?:\\?.*)?$");

        @Override
        public String getId(final String url) throws ParsingException {
            Matcher m = VOD.matcher(url);
            if (m.find()) {
                return "vod:" + m.group(1);
            }
            m = LIVE.matcher(url);
            if (m.find() && !RESERVED.contains(m.group(1).toLowerCase())) {
                return "live:" + m.group(1).toLowerCase();
            }
            throw new ParsingException("Not a Kick stream: " + url);
        }

        @Override
        public String getUrl(final String id) throws ParsingException {
            if (id.startsWith("vod:")) {
                return KickApi.vodUrl(id.substring(4));
            }
            if (id.startsWith("live:")) {
                return KickApi.liveUrl(id.substring(5));
            }
            throw new ParsingException("Bad Kick id: " + id);
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

    /** A Kick channel page: kick.com/{slug}/videos (or /about). */
    public static class Channel extends ListLinkHandlerFactory {
        private static final Pattern CHANNEL = Pattern.compile(
                "^https?://(?:www\\.)?kick\\.com/([A-Za-z0-9_-]{2,40})/(?:videos|about)/?$");

        @Override
        public String getId(final String url) throws ParsingException {
            final Matcher m = CHANNEL.matcher(url);
            if (m.find()) {
                return m.group(1).toLowerCase();
            }
            throw new ParsingException("Not a Kick channel: " + url);
        }

        @Override
        public String getUrl(final String id, final List<FilterItem> c, final List<FilterItem> s) {
            return KickApi.channelUrl(id);
        }

        @Override
        public boolean onAcceptUrl(final String url) {
            return CHANNEL.matcher(url).find();
        }
    }

    public static class Search extends SearchQueryHandlerFactory {
        private static final String PREFIX = "https://kick.com/search?query=";

        @Override
        public String getUrl(final String query, final List<FilterItem> c,
                             final List<FilterItem> s) {
            return PREFIX + KickApi.enc(query);
        }

        @Override
        public String getSearchString(final String url) {
            return url.startsWith(PREFIX)
                    ? URLDecoder.decode(url.substring(PREFIX.length()), StandardCharsets.UTF_8) : "";
        }
    }

    /** Front pages: "Live" (most watched) and "Following" (signed in). */
    public static class Kiosk extends ListLinkHandlerFactory {
        public static final String LIVE = "Live";
        public static final String FOLLOWING = "Following";

        @Override
        public String getUrl(final String id, final List<FilterItem> c, final List<FilterItem> s) {
            return FOLLOWING.equals(id) ? "https://kick.com/following" : "https://kick.com/browse";
        }

        @Override
        public String getId(final String url) {
            return url.endsWith("/following") ? FOLLOWING : LIVE;
        }

        @Override
        public boolean onAcceptUrl(final String url) {
            return url.equals("https://kick.com/browse") || url.equals("https://kick.com/following");
        }
    }

    /** Chat of a Kick stream: same ids/urls as the stream. */
    public static class Chat extends ListLinkHandlerFactory {
        private final Stream streams = new Stream();

        @Override
        public String getId(final String url) throws ParsingException {
            return streams.getId(url);
        }

        @Override
        public String getUrl(final String id, final List<FilterItem> c, final List<FilterItem> s)
                throws ParsingException {
            return streams.getUrl(id);
        }

        @Override
        public boolean onAcceptUrl(final String url) {
            return streams.onAcceptUrl(url);
        }
    }
}
