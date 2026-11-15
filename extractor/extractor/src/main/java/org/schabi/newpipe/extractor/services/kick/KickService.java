package org.schabi.newpipe.extractor.services.kick;

import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.bulletComments.BulletCommentsExtractor;
import org.schabi.newpipe.extractor.channel.ChannelExtractor;
import org.schabi.newpipe.extractor.channel.ChannelTabExtractor;
import org.schabi.newpipe.extractor.comments.CommentsExtractor;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.kiosk.KioskList;
import org.schabi.newpipe.extractor.linkhandler.LinkHandler;
import org.schabi.newpipe.extractor.linkhandler.LinkHandlerFactory;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandlerFactory;
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler;
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandlerFactory;
import org.schabi.newpipe.extractor.playlist.PlaylistExtractor;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.schabi.newpipe.extractor.services.kick.extractors.KickChannelExtractor;
import org.schabi.newpipe.extractor.services.kick.extractors.KickChatExtractor;
import org.schabi.newpipe.extractor.services.kick.extractors.KickLiveExtractor;
import org.schabi.newpipe.extractor.services.kick.extractors.KickSearchExtractor;
import org.schabi.newpipe.extractor.services.kick.extractors.KickStreamExtractor;
import org.schabi.newpipe.extractor.services.kick.linkHandler.KickLinkHandlers;
import org.schabi.newpipe.extractor.stream.StreamExtractor;
import org.schabi.newpipe.extractor.subscription.SubscriptionExtractor;
import org.schabi.newpipe.extractor.suggestion.SuggestionExtractor;

import java.util.Arrays;

import static org.schabi.newpipe.extractor.StreamingService.ServiceInfo.MediaCapability.BULLET_COMMENTS;
import static org.schabi.newpipe.extractor.StreamingService.ServiceInfo.MediaCapability.LIVE;
import static org.schabi.newpipe.extractor.StreamingService.ServiceInfo.MediaCapability.VIDEO;

/** PipeVR: Kick (live streams, past broadcasts, channels, search, chat). */
public class KickService extends StreamingService {
    public KickService(final int id) {
        super(id, "Kick", Arrays.asList(VIDEO, LIVE, BULLET_COMMENTS));
    }

    @Override
    public String getBaseUrl() {
        return KickApi.BASE_URL;
    }

    @Override
    public LinkHandlerFactory getStreamLHFactory() {
        return new KickLinkHandlers.Stream();
    }

    @Override
    public ListLinkHandlerFactory getChannelLHFactory() {
        return new KickLinkHandlers.Channel();
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
        return new KickLinkHandlers.Search();
    }

    @Override
    public ListLinkHandlerFactory getCommentsLHFactory() {
        return null;
    }

    @Override
    public SearchExtractor getSearchExtractor(final SearchQueryHandler queryHandler) {
        return new KickSearchExtractor(this, queryHandler);
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
        final KickLinkHandlers.Kiosk lh = new KickLinkHandlers.Kiosk();
        try {
            list.addKioskEntry((s, url, id) -> new KickLiveExtractor(KickService.this,
                    lh.fromUrl(url), id), lh, KickLinkHandlers.Kiosk.LIVE);
            if (KickApi.isSignedIn()) {
                list.addKioskEntry((s, url, id) -> new KickLiveExtractor(KickService.this,
                        lh.fromUrl(url), id), lh, KickLinkHandlers.Kiosk.FOLLOWING);
                list.setDefaultKiosk(KickLinkHandlers.Kiosk.FOLLOWING);
            } else {
                list.setDefaultKiosk(KickLinkHandlers.Kiosk.LIVE);
            }
        } catch (final Exception e) {
            throw new ExtractionException(e);
        }
        return list;
    }

    @Override
    public ChannelExtractor getChannelExtractor(final ListLinkHandler linkHandler) {
        return new KickChannelExtractor(this, linkHandler);
    }

    @Override
    public ChannelTabExtractor getChannelTabExtractor(final ListLinkHandler linkHandler) {
        return null;
    }

    @Override
    public PlaylistExtractor getPlaylistExtractor(final ListLinkHandler linkHandler) {
        return null;
    }

    @Override
    public StreamExtractor getStreamExtractor(final LinkHandler linkHandler) {
        return new KickStreamExtractor(this, linkHandler);
    }

    @Override
    public CommentsExtractor getCommentsExtractor(final ListLinkHandler linkHandler) {
        return null;
    }

    @Override
    public ListLinkHandlerFactory getBulletCommentsLHFactory() {
        return new KickLinkHandlers.Chat();
    }

    @Override
    public BulletCommentsExtractor getBulletCommentsExtractor(final ListLinkHandler linkHandler) {
        return new KickChatExtractor(this, linkHandler);
    }
}
