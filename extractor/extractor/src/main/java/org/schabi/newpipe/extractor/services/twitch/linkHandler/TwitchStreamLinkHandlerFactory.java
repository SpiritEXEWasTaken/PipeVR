package org.schabi.newpipe.extractor.services.twitch.linkHandler;

import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.LinkHandlerFactory;
import org.schabi.newpipe.extractor.services.twitch.TwitchApi;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * twitch.tv/{login} (live stream, id "live:{login}") and twitch.tv/videos/{id} (past
 * broadcast, id "vod:{id}").
 */
public class TwitchStreamLinkHandlerFactory extends LinkHandlerFactory {
    private static final Pattern VOD = Pattern.compile(
            "^https?://(?:www\\.|m\\.)?twitch\\.tv/videos/(\\d+)");
    private static final Pattern LIVE = Pattern.compile(
            "^https?://(?:www\\.|m\\.)?twitch\\.tv/([A-Za-z0-9_]{2,25})/?(?:\\?.*)?$");
    private static final java.util.Set<String> RESERVED = new java.util.HashSet<>(
            java.util.Arrays.asList("directory", "videos", "settings", "search", "downloads",
                    "p", "jobs", "turbo", "login", "signup", "subscriptions", "inventory",
                    "wallet", "drops"));

    @Override
    public String getId(final String url) throws ParsingException {
        Matcher m = VOD.matcher(url);
        if (m.find()) {
            return "vod:" + m.group(1);
        }
        m = LIVE.matcher(url);
        if (m.find() && !RESERVED.contains(m.group(1).toLowerCase())) {
            return "live:" + m.group(1).toLowerCase();
        }
        throw new ParsingException("Not a Twitch stream: " + url);
    }

    @Override
    public String getUrl(final String id) throws ParsingException {
        if (id.startsWith("vod:")) {
            return TwitchApi.vodUrl(id.substring(4));
        }
        if (id.startsWith("live:")) {
            return TwitchApi.liveUrl(id.substring(5));
        }
        throw new ParsingException("Bad Twitch id: " + id);
    }

    @Override
    public boolean onAcceptUrl(final String url) {
        try {
            getId(url);
            return true;
        } catch (final ParsingException e) {
            return false;
        }
    }
}
