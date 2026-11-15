package org.schabi.newpipe.extractor.services.twitch.linkHandler;

import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandlerFactory;
import org.schabi.newpipe.extractor.search.filter.FilterItem;

import java.util.List;

/** The chat of a Twitch stream: same ids/urls as the stream. */
public class TwitchChatLinkHandlerFactory extends ListLinkHandlerFactory {
    private final TwitchStreamLinkHandlerFactory streams = new TwitchStreamLinkHandlerFactory();

    @Override
    public String getId(final String url) throws ParsingException {
        return streams.getId(url);
    }

    @Override
    public String getUrl(final String id, final List<FilterItem> contentFilter,
                         final List<FilterItem> sortFilter) throws ParsingException {
        return streams.getUrl(id);
    }

    @Override
    public boolean onAcceptUrl(final String url) {
        return streams.onAcceptUrl(url);
    }
}
