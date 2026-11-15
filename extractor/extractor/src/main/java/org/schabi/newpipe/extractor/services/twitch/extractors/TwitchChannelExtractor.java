package org.schabi.newpipe.extractor.services.twitch.extractors;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;

import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.channel.ChannelExtractor;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.services.twitch.TwitchApi;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector;

import java.io.IOException;

import javax.annotation.Nonnull;

/** PipeVR: a Twitch channel: its live stream (if live) and past broadcasts, newest first. */
public class TwitchChannelExtractor extends ChannelExtractor {
    private JsonObject user;

    public TwitchChannelExtractor(final StreamingService service,
                                  final ListLinkHandler linkHandler) {
        super(service, linkHandler);
    }

    private JsonObject fetchVideos(final String cursor) throws IOException, ExtractionException {
        return TwitchApi.gql("query($l: String!, $c: Cursor) { user(login: $l) { id login "
                + "displayName description profileImageURL(width: 300) bannerImageURL "
                + "followers { totalCount } stream { " + TwitchItems.STREAM_FIELDS + " } "
                + "videos(first: 30, after: $c, sort: TIME) { edges { cursor node { "
                + TwitchItems.VIDEO_FIELDS + " } } pageInfo { hasNextPage } } } }",
                TwitchApi.vars("l", getId(), "c", cursor)).getObject("user");
    }

    @Override
    public void onFetchPage(@Nonnull final Downloader downloader)
            throws IOException, ExtractionException {
        user = fetchVideos(null);
        if (user.isEmpty()) {
            throw new ContentNotAvailableException("This Twitch channel does not exist");
        }
    }

    @Nonnull
    @Override
    public String getName() throws ParsingException {
        return user.getString("displayName", getId());
    }

    @Override
    public String getAvatarUrl() {
        return user.getString("profileImageURL");
    }

    @Override
    public String getBannerUrl() {
        return user.getString("bannerImageURL");
    }

    @Override
    public long getSubscriberCount() {
        return user.getObject("followers").getLong("totalCount", -1);
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
        return false;
    }

    private InfoItemsPage<StreamInfoItem> toPage(final JsonObject u, final boolean withLive) throws ParsingException {
        final StreamInfoItemsCollector collector = new StreamInfoItemsCollector(getServiceId());
        final JsonObject stream = u.getObject("stream");
        if (withLive && !stream.isEmpty()) {
            final JsonObject live = new JsonObject(stream);
            final JsonObject broadcaster = new JsonObject();
            broadcaster.put("login", u.getString("login"));
            broadcaster.put("displayName", u.getString("displayName"));
            broadcaster.put("profileImageURL", u.getString("profileImageURL"));
            live.put("broadcaster", broadcaster);
            collector.commit(new TwitchItems.Live(live));
        }
        final JsonObject videos = u.getObject("videos");
        final JsonArray edges = videos.getArray("edges");
        String lastCursor = null;
        for (int i = 0; i < edges.size(); i++) {
            final JsonObject edge = edges.getObject(i);
            lastCursor = edge.getString("cursor");
            collector.commit(new TwitchItems.Vod(edge.getObject("node")));
        }
        final Page next = videos.getObject("pageInfo").getBoolean("hasNextPage", false)
                && lastCursor != null ? new Page(getUrl(), lastCursor) : null;
        return new InfoItemsPage<>(collector, next);
    }

    @Nonnull
    @Override
    public InfoItemsPage<StreamInfoItem> getInitialPage() throws ParsingException {
        return toPage(user, true);
    }

    @Override
    public InfoItemsPage<StreamInfoItem> getPage(final Page page)
            throws IOException, ExtractionException {
        return toPage(fetchVideos(page.getId()), false);
    }
}
