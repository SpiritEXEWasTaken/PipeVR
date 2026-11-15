package org.schabi.newpipe.extractor.services.kick.extractors;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;

import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.channel.ChannelExtractor;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.services.kick.KickApi;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector;

import java.io.IOException;

import javax.annotation.Nonnull;

/** PipeVR: a Kick channel: its live stream (if live) and past broadcasts. */
public class KickChannelExtractor extends ChannelExtractor {
    private JsonObject channel;
    private JsonArray videos;

    public KickChannelExtractor(final StreamingService service, final ListLinkHandler handler) {
        super(service, handler);
    }

    @Override
    public void onFetchPage(@Nonnull final Downloader downloader)
            throws IOException, ExtractionException {
        channel = KickApi.getObject("/api/v2/channels/" + KickApi.enc(getId()));
        try {
            videos = KickApi.getArray("/api/v2/channels/" + KickApi.enc(getId()) + "/videos");
        } catch (final Exception e) {
            videos = new JsonArray();
        }
    }

    private String username() throws ParsingException {
        return channel.getObject("user").getString("username", getId());
    }

    @Nonnull
    @Override
    public String getName() throws ParsingException {
        return username();
    }

    @Override
    public String getAvatarUrl() {
        return KickItems.Live.avatarOf(channel.getObject("user"));
    }

    @Override
    public String getBannerUrl() {
        return KickApi.thumbnail(channel.get("banner_image"));
    }

    @Override
    public long getSubscriberCount() {
        return channel.getLong("followers_count", -1);
    }

    @Override
    public String getDescription() {
        return channel.getObject("user").getString("bio", "");
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
        return channel.get("verified") != null && !(channel.get("verified") instanceof Boolean
                && !channel.getBoolean("verified"));
    }

    @Nonnull
    @Override
    public InfoItemsPage<StreamInfoItem> getInitialPage() throws ParsingException {
        final StreamInfoItemsCollector collector = new StreamInfoItemsCollector(getServiceId());
        final String slug = getId();
        final JsonObject live = channel.getObject("livestream");
        if (!live.isEmpty() && live.getBoolean("is_live", false)) {
            collector.commit(new KickItems.Live(live, slug, username(),
                    KickItems.Live.avatarOf(channel.getObject("user"))));
        }
        for (int i = 0; i < videos.size(); i++) {
            collector.commit(new KickItems.Vod(videos.getObject(i), slug, username()));
        }
        return new InfoItemsPage<>(collector, null);
    }

    @Override
    public InfoItemsPage<StreamInfoItem> getPage(final Page page) {
        return InfoItemsPage.emptyPage();
    }
}
