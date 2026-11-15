package org.schabi.newpipe.extractor.services.kick.extractors;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;

import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.MultiInfoItemsCollector;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.schabi.newpipe.extractor.services.kick.KickApi;

import java.io.IOException;

import javax.annotation.Nonnull;

/** PipeVR: Kick search: live channels first (watchable), then channels. */
public class KickSearchExtractor extends SearchExtractor {
    private JsonObject result;

    public KickSearchExtractor(final StreamingService service, final SearchQueryHandler h) {
        super(service, h);
    }

    @Override
    public void onFetchPage(@Nonnull final Downloader downloader)
            throws IOException, ExtractionException {
        result = KickApi.getObject("/api/search?searched_word=" + KickApi.enc(getSearchString()));
    }

    @Override
    protected InfoItemsPage<InfoItem> getInitialPageInternal() {
        final MultiInfoItemsCollector collector = new MultiInfoItemsCollector(getServiceId());
        final JsonArray channels = result.getArray("channels");
        int looked = 0;
        for (int i = 0; i < channels.size(); i++) {
            final JsonObject c = channels.getObject(i);
            JsonObject live = c.getObject("livestream");
            if (live.isEmpty() && c.getBoolean("isLive", false) && looked < 6) {
                // search results no longer carry the stream: look it up (live ones only)
                looked++;
                try {
                    live = KickApi.getObject("/api/v2/channels/"
                            + KickApi.enc(c.getString("slug"))).getObject("livestream");
                } catch (final Exception ignored) {
                    live = new JsonObject();
                }
            }
            if (!live.isEmpty() && live.getBoolean("is_live", false)) {
                collector.commit(new KickItems.Live(live, c.getString("slug"),
                        c.getObject("user").getString("username", c.getString("slug")),
                        KickItems.Live.avatarOf(c.getObject("user"))));
            }
        }
        for (int i = 0; i < channels.size(); i++) {
            collector.commit(new KickItems.Channel(channels.getObject(i)));
        }
        return new InfoItemsPage<>(collector, null);
    }

    @Override
    protected InfoItemsPage<InfoItem> getPageInternal(final Page page) {
        return InfoItemsPage.emptyPage();
    }
}
