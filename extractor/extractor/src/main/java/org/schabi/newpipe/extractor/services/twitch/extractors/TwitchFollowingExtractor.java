package org.schabi.newpipe.extractor.services.twitch.extractors;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;

import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.kiosk.KioskExtractor;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.services.twitch.TwitchApi;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector;

import java.io.IOException;

import javax.annotation.Nonnull;

/** PipeVR: live channels the signed-in Twitch account follows. */
public class TwitchFollowingExtractor extends KioskExtractor<StreamInfoItem> {
    private JsonArray edges;

    public TwitchFollowingExtractor(final StreamingService service,
                                    final ListLinkHandler linkHandler, final String kioskId) {
        super(service, linkHandler, kioskId);
    }

    @Override
    public void onFetchPage(@Nonnull final Downloader downloader)
            throws IOException, ExtractionException {
        edges = TwitchApi.followedLive();
    }

    @Nonnull
    @Override
    public String getName() {
        return getId();
    }

    @Nonnull
    @Override
    public InfoItemsPage<StreamInfoItem> getInitialPage() {
        final StreamInfoItemsCollector collector = new StreamInfoItemsCollector(getServiceId());
        for (int i = 0; i < edges.size(); i++) {
            final JsonObject node = edges.getObject(i).getObject("node");
            final JsonObject stream = node.getObject("stream");
            if (stream.isEmpty()) {
                continue;
            }
            final JsonObject live = new JsonObject(stream);
            final JsonObject broadcaster = new JsonObject();
            broadcaster.put("login", node.getString("login"));
            broadcaster.put("displayName", node.getString("displayName"));
            broadcaster.put("profileImageURL", node.getString("profileImageURL"));
            live.put("broadcaster", broadcaster);
            collector.commit(new TwitchItems.Live(live));
        }
        return new InfoItemsPage<>(collector, null);
    }

    @Override
    public InfoItemsPage<StreamInfoItem> getPage(final Page page) {
        return InfoItemsPage.emptyPage();
    }
}
