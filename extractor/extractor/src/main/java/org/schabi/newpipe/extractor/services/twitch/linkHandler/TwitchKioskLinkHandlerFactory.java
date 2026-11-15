package org.schabi.newpipe.extractor.services.twitch.linkHandler;

import org.schabi.newpipe.extractor.linkhandler.ListLinkHandlerFactory;
import org.schabi.newpipe.extractor.search.filter.FilterItem;

import java.util.List;

/** Twitch front pages: "Top live" (twitch.tv/directory) and "Following" (signed in). */
public class TwitchKioskLinkHandlerFactory extends ListLinkHandlerFactory {
    public static final String TOP = "Top live";
    public static final String FOLLOWING = "Following";

    @Override
    public String getUrl(final String id, final List<FilterItem> contentFilter,
                         final List<FilterItem> sortFilter) {
        return FOLLOWING.equals(id) ? "https://www.twitch.tv/directory/following"
                : "https://www.twitch.tv/directory";
    }

    @Override
    public String getId(final String url) {
        return url.endsWith("/following") ? FOLLOWING : TOP;
    }

    @Override
    public boolean onAcceptUrl(final String url) {
        return url.equals("https://www.twitch.tv/directory")
                || url.equals("https://www.twitch.tv/directory/following");
    }
}
