package org.schabi.newpipe.extractor.services.twitch.extractors;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;

import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.kiosk.KioskExtractor;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.services.twitch.TwitchApi;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector;

import java.io.IOException;

import javax.annotation.Nonnull;

/** PipeVR: Twitch's most watched live streams right now. */
public class TwitchTopStreamsExtractor extends KioskExtractor<StreamInfoItem> {
    private JsonObject streams;

    public TwitchTopStreamsExtractor(final StreamingService service,
                                     final ListLinkHandler linkHandler, final String kioskId) {
        super(service, linkHandler, kioskId);
    }

    private JsonObject fetch(final String cursor) throws IOException, ExtractionException {
        return TwitchApi.gql("query($c: Cursor) { streams(first: 30, after: $c) { edges { "
                + "cursor node { " + TwitchItems.STREAM_FIELDS + " } } "
                + "pageInfo { hasNextPage } } }", TwitchApi.vars("c", cursor))
                .getObject("streams");
    }

    @Override
    public void onFetchPage(@Nonnull final Downloader downloader)
            throws IOException, ExtractionException {
        streams = fetch(null);
    }

    @Nonnull
    @Override
    public String getName() throws ParsingException {
        return getId();
    }

    private InfoItemsPage<StreamInfoItem> toPage(final JsonObject s) throws ParsingException {
        final StreamInfoItemsCollector collector = new StreamInfoItemsCollector(getServiceId());
        final JsonArray edges = s.getArray("edges");
        String last = null;
        for (int i = 0; i < edges.size(); i++) {
            last = edges.getObject(i).getString("cursor");
            collector.commit(new TwitchItems.Live(edges.getObject(i).getObject("node")));
        }
        final Page next = s.getObject("pageInfo").getBoolean("hasNextPage", false)
                && last != null ? new Page(getUrl(), last) : null;
        return new InfoItemsPage<>(collector, next);
    }

    @Nonnull
    @Override
    public InfoItemsPage<StreamInfoItem> getInitialPage() throws ParsingException {
        return toPage(streams);
    }

    @Override
    public InfoItemsPage<StreamInfoItem> getPage(final Page page)
            throws IOException, ExtractionException {
        return toPage(fetch(page.getId()));
    }
}
