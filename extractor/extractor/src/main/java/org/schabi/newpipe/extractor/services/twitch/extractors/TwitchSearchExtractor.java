package org.schabi.newpipe.extractor.services.twitch.extractors;

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
import org.schabi.newpipe.extractor.services.twitch.TwitchApi;

import java.io.IOException;

import javax.annotation.Nonnull;

/** PipeVR: Twitch search: live channels first (watchable), then channels. */
public class TwitchSearchExtractor extends SearchExtractor {
    private JsonObject result;

    public TwitchSearchExtractor(final StreamingService service,
                                 final SearchQueryHandler linkHandler) {
        super(service, linkHandler);
    }

    @Override
    public void onFetchPage(@Nonnull final Downloader downloader)
            throws IOException, ExtractionException {
        result = TwitchApi.gql("query($q: String!) { searchUsers(userQuery: $q, first: 25) "
                + "{ edges { node { login displayName description profileImageURL(width: 150) "
                + "followers { totalCount } stream { " + TwitchItems.STREAM_FIELDS + " } } } } }",
                TwitchApi.vars("q", getSearchString())).getObject("searchUsers");
    }

    @Override
    protected InfoItemsPage<InfoItem> getInitialPageInternal() {
        final MultiInfoItemsCollector collector = new MultiInfoItemsCollector(getServiceId());
        final JsonArray edges = result.getArray("edges");
        // live channels first, as streams you can open right away
        for (int i = 0; i < edges.size(); i++) {
            final JsonObject node = edges.getObject(i).getObject("node");
            final JsonObject stream = node.getObject("stream");
            if (!stream.isEmpty()) {
                final JsonObject live = new JsonObject(stream);
                final JsonObject broadcaster = new JsonObject();
                broadcaster.put("login", node.getString("login"));
                broadcaster.put("displayName", node.getString("displayName"));
                broadcaster.put("profileImageURL", node.getString("profileImageURL"));
                live.put("broadcaster", broadcaster);
                collector.commit(new TwitchItems.Live(live));
            }
        }
        for (int i = 0; i < edges.size(); i++) {
            collector.commit(new TwitchItems.Channel(edges.getObject(i).getObject("node")));
        }
        return new InfoItemsPage<>(collector, null);
    }

    @Override
    protected InfoItemsPage<InfoItem> getPageInternal(final Page page) {
        return InfoItemsPage.emptyPage();
    }
}
