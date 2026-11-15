package org.schabi.newpipe.extractor.services.twitch;

import org.schabi.newpipe.extractor.StreamingService;
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
import org.schabi.newpipe.extractor.services.twitch.extractors.TwitchChannelExtractor;
import org.schabi.newpipe.extractor.services.twitch.extractors.TwitchSearchExtractor;
import org.schabi.newpipe.extractor.services.twitch.extractors.TwitchStreamExtractor;
import org.schabi.newpipe.extractor.services.twitch.extractors.TwitchTopStreamsExtractor;
import org.schabi.newpipe.extractor.services.twitch.linkHandler.TwitchChannelLinkHandlerFactory;
import org.schabi.newpipe.extractor.services.twitch.linkHandler.TwitchKioskLinkHandlerFactory;
import org.schabi.newpipe.extractor.services.twitch.linkHandler.TwitchSearchQueryHandlerFactory;
import org.schabi.newpipe.extractor.services.twitch.linkHandler.TwitchStreamLinkHandlerFactory;
import org.schabi.newpipe.extractor.stream.StreamExtractor;
import org.schabi.newpipe.extractor.subscription.SubscriptionExtractor;
import org.schabi.newpipe.extractor.suggestion.SuggestionExtractor;

import java.util.Arrays;

import static org.schabi.newpipe.extractor.StreamingService.ServiceInfo.MediaCapability.BULLET_COMMENTS;
import static org.schabi.newpipe.extractor.StreamingService.ServiceInfo.MediaCapability.LIVE;
import static org.schabi.newpipe.extractor.StreamingService.ServiceInfo.MediaCapability.VIDEO;

/** PipeVR: Twitch (live streams, past broadcasts, channels, search). */
public class TwitchService extends StreamingService {
    public TwitchService(final int id) {
        super(id, "Twitch", Arrays.asList(VIDEO, LIVE, BULLET_COMMENTS));
    }

    @Override
    public String getBaseUrl() {
        return TwitchApi.BASE_URL;
    }

    @Override
    public LinkHandlerFactory getStreamLHFactory() {
        return new TwitchStreamLinkHandlerFactory();
    }

    @Override
    public ListLinkHandlerFactory getChannelLHFactory() {
        return new TwitchChannelLinkHandlerFactory();
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
        return new TwitchSearchQueryHandlerFactory();
    }

    @Override
    public ListLinkHandlerFactory getCommentsLHFactory() {
        return null;
    }

    @Override
    public SearchExtractor getSearchExtractor(final SearchQueryHandler queryHandler) {
        return new TwitchSearchExtractor(this, queryHandler);
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
        final TwitchKioskLinkHandlerFactory lh = new TwitchKioskLinkHandlerFactory();
        try {
            list.addKioskEntry((service, url, id) -> new TwitchTopStreamsExtractor(
                    TwitchService.this, lh.fromUrl(url), id), lh, TwitchKioskLinkHandlerFactory.TOP);
            if (TwitchApi.isSignedIn()) {
                list.addKioskEntry((service, url, id) -> new org.schabi.newpipe.extractor
                        .services.twitch.extractors.TwitchFollowingExtractor(
                        TwitchService.this, lh.fromUrl(url), id), lh,
                        TwitchKioskLinkHandlerFactory.FOLLOWING);
                list.setDefaultKiosk(TwitchKioskLinkHandlerFactory.FOLLOWING);
            } else {
                list.setDefaultKiosk(TwitchKioskLinkHandlerFactory.TOP);
            }
        } catch (final Exception e) {
            throw new ExtractionException(e);
        }
        return list;
    }

    @Override
    public ChannelExtractor getChannelExtractor(final ListLinkHandler linkHandler) {
        return new TwitchChannelExtractor(this, linkHandler);
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
        return new TwitchStreamExtractor(this, linkHandler);
    }

    @Override
    public CommentsExtractor getCommentsExtractor(final ListLinkHandler linkHandler) {
        return null;
    }

    @Override
    public ListLinkHandlerFactory getBulletCommentsLHFactory() {
        return new org.schabi.newpipe.extractor.services.twitch.linkHandler
                .TwitchChatLinkHandlerFactory();
    }

    @Override
    public org.schabi.newpipe.extractor.bulletComments.BulletCommentsExtractor
            getBulletCommentsExtractor(final ListLinkHandler linkHandler) {
        return new org.schabi.newpipe.extractor.services.twitch.extractors
                .TwitchChatExtractor(this, linkHandler);
    }
}
