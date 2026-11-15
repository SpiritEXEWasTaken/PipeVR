package org.schabi.newpipe.extractor.services.youtube.linkHandler;

import org.schabi.newpipe.extractor.linkhandler.ListLinkHandlerFactory;
import org.schabi.newpipe.extractor.search.filter.FilterItem;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** PipeVR: internal URLs for the signed-in account kiosks (Home, Subscriptions, ...). */
public class YoutubeTvAccountKioskLinkHandlerFactory extends ListLinkHandlerFactory {
    private static final String PREFIX = "https://www.youtube.com/tv#pipevr-account/";

    @Override
    public String getUrl(final String id, final List<FilterItem> contentFilters,
                         final List<FilterItem> sortFilter) {
        try {
            return PREFIX + URLEncoder.encode(id, "UTF-8");
        } catch (final Exception e) {
            return PREFIX + id;
        }
    }

    @Override
    public String getId(final String url) {
        try {
            return URLDecoder.decode(url.substring(PREFIX.length()), "UTF-8");
        } catch (final Exception e) {
            return url.substring(PREFIX.length());
        }
    }

    @Override
    public boolean onAcceptUrl(final String url) {
        return url != null && url.startsWith(PREFIX);
    }
}
