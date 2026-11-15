package org.schabi.newpipe.extractor.services.youtube;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonWriter;

import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * PipeVR: the YouTube Shorts feed (what the Shorts tab plays), from the reel endpoints the
 * YouTube website uses (as SmartTube does): a seedless reel watch, then watch sequences.
 */
public final class YoutubeShortsFeed {
    /** One page of shorts: their video ids and the token for the next page. */
    public static final class Page {
        public final List<String> videoIds = new ArrayList<>();
        @Nullable
        public String nextToken;
    }

    private YoutubeShortsFeed() {
    }

    /** First page of the feed (personalized when an account is signed in). */
    @Nonnull
    public static Page start(@Nonnull final Localization localization,
                             @Nonnull final ContentCountry country)
            throws IOException, ExtractionException {
        if (YoutubeOAuth.isSignedIn()) {
            try {
                return startSignedIn(localization);
            } catch (final Exception e) {
                // fall back to the anonymous feed
            }
        }
        final byte[] body = JsonWriter.string(
                YoutubeParsingHelper.prepareDesktopJsonBuilder(localization, country)
                        .value("disablePlayerResponse", true)
                        .value("inputType", "REEL_WATCH_INPUT_TYPE_SEEDLESS")
                        .value("params", "CA8=")
                        .done()).getBytes(StandardCharsets.UTF_8);
        final JsonObject first = YoutubeParsingHelper.getJsonPostResponse(
                "reel/reel_item_watch", body, localization);
        final Page page = new Page();
        final String firstId = first.getObject("replacementEndpoint")
                .getObject("reelWatchEndpoint").getString("videoId");
        if (firstId != null) {
            page.videoIds.add(firstId);
        }
        String token = first.getString("sequenceContinuation");
        if (token == null) {
            token = first.getObject("continuationEndpoint").getObject("continuationCommand")
                    .getString("token");
        }
        if (token != null) {
            final Page more = next(token, localization, country);
            page.videoIds.addAll(more.videoIds);
            page.nextToken = more.nextToken;
        }
        return page;
    }

    /** Next page of the feed. */
    @Nonnull
    public static Page next(@Nonnull final String token,
                            @Nonnull final Localization localization,
                            @Nonnull final ContentCountry country)
            throws IOException, ExtractionException {
        if (YoutubeOAuth.isSignedIn()) {
            try {
                return parseSequence(YoutubeTvAccountFeeds.post("reel/reel_watch_sequence",
                        b -> b.value("sequenceParams", token), localization));
            } catch (final Exception e) {
                // fall back to the anonymous request
            }
        }
        final byte[] body = JsonWriter.string(
                YoutubeParsingHelper.prepareDesktopJsonBuilder(localization, country)
                        .value("sequenceParams", token)
                        .done()).getBytes(StandardCharsets.UTF_8);
        return parseSequence(YoutubeParsingHelper.getJsonPostResponse(
                "reel/reel_watch_sequence", body, localization));
    }

    /**
     * Signed in: new shorts of subscribed channels first, then the account's personalized
     * shorts sequence (the TV app's reel endpoints with the account's token, like SmartTube).
     */
    @Nonnull
    private static Page startSignedIn(@Nonnull final Localization localization)
            throws IOException, ExtractionException {
        final Page page = new Page();
        try {
            final JsonObject subs = YoutubeTvAccountFeeds.post("browse",
                    b -> b.value("browseId", "FEsubscriptions"), localization);
            collectReelIds(subs, page.videoIds, 5);
        } catch (final Exception ignored) {
            // the personalized sequence alone is fine
        }
        final JsonObject first = YoutubeTvAccountFeeds.post("reel/reel_item_watch",
                b -> b.value("disablePlayerResponse", true)
                        .value("inputType", "REEL_WATCH_INPUT_TYPE_SEEDLESS")
                        .value("params", "CA8="), localization);
        final String firstId = first.getObject("replacementEndpoint")
                .getObject("reelWatchEndpoint").getString("videoId");
        if (firstId != null && !page.videoIds.contains(firstId)) {
            page.videoIds.add(firstId);
        }
        String token = first.getString("sequenceContinuation");
        if (token == null) {
            token = first.getObject("continuationEndpoint").getObject("continuationCommand")
                    .getString("token");
        }
        if (token != null) {
            final String sequenceToken = token;
            final Page more = parseSequence(YoutubeTvAccountFeeds.post(
                    "reel/reel_watch_sequence",
                    b -> b.value("sequenceParams", sequenceToken), localization));
            for (final String id : more.videoIds) {
                if (!page.videoIds.contains(id)) {
                    page.videoIds.add(id);
                }
            }
            page.nextToken = more.nextToken;
        }
        if (page.videoIds.isEmpty()) {
            throw new ExtractionException("No personalized shorts");
        }
        return page;
    }

    private static void collectReelIds(final Object node, final List<String> out,
                                       final int max) {
        if (out.size() >= max) {
            return;
        }
        if (node instanceof JsonObject) {
            final JsonObject o = (JsonObject) node;
            final String id = o.getObject("reelWatchEndpoint").getString("videoId");
            if (id != null && !out.contains(id)) {
                out.add(id);
            }
            for (final Object v : o.values()) {
                collectReelIds(v, out, max);
            }
        } else if (node instanceof JsonArray) {
            for (final Object v : (JsonArray) node) {
                collectReelIds(v, out, max);
            }
        }
    }

    @Nonnull
    private static Page parseSequence(@Nonnull final JsonObject response) {
        final Page page = new Page();
        final JsonArray entries = response.getArray("entries");
        for (int i = 0; i < entries.size(); i++) {
            final String id = entries.getObject(i).getObject("command")
                    .getObject("reelWatchEndpoint").getString("videoId");
            if (id != null && !page.videoIds.contains(id)) {
                page.videoIds.add(id);
            }
        }
        page.nextToken = response.getString("continuation");
        if (page.nextToken == null) {
            page.nextToken = response.getObject("continuationEndpoint")
                    .getObject("continuationCommand").getString("token");
        }
        return page;
    }
}
