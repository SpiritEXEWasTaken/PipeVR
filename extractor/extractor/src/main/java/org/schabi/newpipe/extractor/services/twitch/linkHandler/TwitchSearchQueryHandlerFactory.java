package org.schabi.newpipe.extractor.services.twitch.linkHandler;

import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandlerFactory;
import org.schabi.newpipe.extractor.search.filter.FilterItem;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class TwitchSearchQueryHandlerFactory extends SearchQueryHandlerFactory {
    private static final String PREFIX = "https://www.twitch.tv/search?term=";

    @Override
    public String getUrl(final String query, final List<FilterItem> contentFilter,
                         final List<FilterItem> sortFilter) {
        return PREFIX + URLEncoder.encode(query, StandardCharsets.UTF_8);
    }

    @Override
    public String getSearchString(final String url) {
        return url.startsWith(PREFIX)
                ? URLDecoder.decode(url.substring(PREFIX.length()), StandardCharsets.UTF_8) : "";
    }
}
