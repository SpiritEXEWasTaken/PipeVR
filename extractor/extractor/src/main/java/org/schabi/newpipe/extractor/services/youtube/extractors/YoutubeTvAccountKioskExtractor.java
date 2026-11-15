package org.schabi.newpipe.extractor.services.youtube.extractors;

import com.grack.nanojson.JsonObject;

import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.kiosk.KioskExtractor;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.localization.TimeAgoParser;
import org.schabi.newpipe.extractor.services.youtube.YoutubeTvAccountFeeds;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import javax.annotation.Nonnull;

/**
 * PipeVR: the signed-in account's Home, Subscriptions, History, Watch later and Liked videos,
 * loaded from the YouTube TV app API with the account's OAuth token.
 */
public class YoutubeTvAccountKioskExtractor extends KioskExtractor<StreamInfoItem> {
    private JsonObject initialData;

    public YoutubeTvAccountKioskExtractor(final StreamingService service,
                                          final ListLinkHandler linkHandler,
                                          final String kioskId) {
        super(service, linkHandler, kioskId);
    }

    @Override
    public void onFetchPage(@Nonnull final Downloader downloader)
            throws IOException, ExtractionException {
        final String browseId = YoutubeTvAccountFeeds.browseIdOf(getId());
        initialData = YoutubeTvAccountFeeds.post("browse",
                b -> b.value("browseId", browseId), getExtractorLocalization());
    }

    @Nonnull
    @Override
    public String getName() {
        return getId();
    }

    @Nonnull
    @Override
    public InfoItemsPage<StreamInfoItem> getInitialPage() throws ParsingException {
        return toPage(initialData);
    }

    @Override
    public InfoItemsPage<StreamInfoItem> getPage(final Page page)
            throws IOException, ExtractionException {
        if (page == null || page.getId() == null || page.getId().isEmpty()) {
            return InfoItemsPage.emptyPage();
        }
        final JsonObject response = YoutubeTvAccountFeeds.post("browse",
                b -> b.value("continuation", page.getId()), getExtractorLocalization());
        return toPage(response);
    }

    private InfoItemsPage<StreamInfoItem> toPage(@Nonnull final JsonObject response)
            throws ParsingException {
        final StreamInfoItemsCollector collector = new StreamInfoItemsCollector(getServiceId());
        final TimeAgoParser timeAgoParser = getTimeAgoParser();
        // Home and Subscriptions: no Shorts (own mode) and no news/politics rows
        final boolean feed = YoutubeTvAccountFeeds.HOME.equals(getId())
                || YoutubeTvAccountFeeds.SUBSCRIPTIONS.equals(getId());
        final YoutubeTvAccountFeeds.Items items =
                YoutubeTvAccountFeeds.collect(response, false, feed);
        final Set<String> seen = new HashSet<>();
        for (final JsonObject wrapped : items.renderers) {
            final YoutubeTvTileInfoItemExtractor item =
                    new YoutubeTvTileInfoItemExtractor(wrapped, timeAgoParser);
            final String id = item.getVideoId();
            if (id != null && seen.add(id)) {
                collector.commit(item);
            }
        }
        final Page next = items.continuation == null ? null
                : new Page(getUrl(), items.continuation);
        return new InfoItemsPage<>(collector, next);
    }
}
