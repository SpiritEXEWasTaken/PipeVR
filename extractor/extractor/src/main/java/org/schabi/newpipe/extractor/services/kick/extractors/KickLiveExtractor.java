package org.schabi.newpipe.extractor.services.kick.extractors;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;

import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.kiosk.KioskExtractor;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.services.kick.KickApi;
import org.schabi.newpipe.extractor.services.kick.linkHandler.KickLinkHandlers;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector;

import java.io.IOException;

import javax.annotation.Nonnull;

/** PipeVR: Kick front pages: most watched live streams, or followed channels that are live. */
public class KickLiveExtractor extends KioskExtractor<StreamInfoItem> {
    private JsonObject first;

    public KickLiveExtractor(final StreamingService service, final ListLinkHandler handler,
                             final String kioskId) {
        super(service, handler, kioskId);
    }

    private boolean following() {
        return KickLinkHandlers.Kiosk.FOLLOWING.equals(getId());
    }

    private JsonObject fetch(final int page) throws IOException, ExtractionException {
        if (following()) {
            return KickApi.getObject("/api/v2/channels/followed?cursor=0");
        }
        return KickApi.getObject("/stream/livestreams/en?limit=24&sort=desc&page=" + page);
    }

    @Override
    public void onFetchPage(@Nonnull final Downloader downloader)
            throws IOException, ExtractionException {
        first = fetch(1);
    }

    @Nonnull
    @Override
    public String getName() {
        return getId();
    }

    private InfoItemsPage<StreamInfoItem> toPage(final JsonObject response, final int page) {
        final StreamInfoItemsCollector collector = new StreamInfoItemsCollector(getServiceId());
        if (following()) {
            // followed channels: keep the live ones
            final JsonArray channels = response.getArray("channels");
            for (int i = 0; i < channels.size(); i++) {
                final JsonObject c = channels.getObject(i);
                if (!c.getBoolean("is_live", false)) {
                    continue;
                }
                final String slug = c.getString("channel_slug", c.getString("slug"));
                final JsonObject live = new JsonObject();
                live.put("session_title", c.getString("session_title", slug));
                live.put("viewer_count", c.getLong("viewer_count", -1));
                live.put("thumbnail", c.get("thumbnail"));
                collector.commit(new KickItems.Live(live, slug,
                        c.getString("user_username", slug), c.getString("profile_picture")));
            }
            return new InfoItemsPage<>(collector, null);
        }
        final JsonArray data = response.getArray("data");
        for (int i = 0; i < data.size(); i++) {
            collector.commit(KickItems.Live.fromListItem(data.getObject(i)));
        }
        final Page next = response.getString("next_page_url") != null
                ? new Page(KickApi.BASE_URL + "/browse", String.valueOf(page + 1)) : null;
        return new InfoItemsPage<>(collector, next);
    }

    @Nonnull
    @Override
    public InfoItemsPage<StreamInfoItem> getInitialPage() {
        return toPage(first, 1);
    }

    @Override
    public InfoItemsPage<StreamInfoItem> getPage(final Page page)
            throws IOException, ExtractionException {
        final int n = Integer.parseInt(page.getId());
        return toPage(fetch(n), n);
    }
}
