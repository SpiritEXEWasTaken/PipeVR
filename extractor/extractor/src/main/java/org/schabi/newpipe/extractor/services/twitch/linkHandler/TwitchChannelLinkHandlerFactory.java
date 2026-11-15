package org.schabi.newpipe.extractor.services.twitch.linkHandler;

import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandlerFactory;
import org.schabi.newpipe.extractor.search.filter.FilterItem;
import org.schabi.newpipe.extractor.services.twitch.TwitchApi;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A Twitch channel page: twitch.tv/{login}/videos (or /about, /schedule). */
public class TwitchChannelLinkHandlerFactory extends ListLinkHandlerFactory {
    private static final Pattern CHANNEL = Pattern.compile(
            "^https?://(?:www\\.|m\\.)?twitch\\.tv/([A-Za-z0-9_]{2,25})/(?:videos|about|schedule)");

    @Override
    public String getId(final String url) throws ParsingException {
        final Matcher m = CHANNEL.matcher(url);
        if (m.find()) {
            return m.group(1).toLowerCase();
        }
        throw new ParsingException("Not a Twitch channel: " + url);
    }

    @Override
    public String getUrl(final String id, final List<FilterItem> contentFilter,
                         final List<FilterItem> sortFilter) {
        return TwitchApi.channelUrl(id);
    }

    @Override
    public boolean onAcceptUrl(final String url) {
        return CHANNEL.matcher(url).find();
    }
}
