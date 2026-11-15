package org.schabi.newpipe.extractor.services.youtube.extractors;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;

import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.localization.DateWrapper;
import org.schabi.newpipe.extractor.localization.TimeAgoParser;
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper;
import org.schabi.newpipe.extractor.services.youtube.YoutubeTvAccountFeeds;
import org.schabi.newpipe.extractor.stream.StreamInfoItemExtractor;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.utils.Utils;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * PipeVR: a video card of the YouTube TV app ({@code tileRenderer}), plus the classic
 * {@code videoRenderer}-like cards the TV app sometimes returns.
 */
public class YoutubeTvTileInfoItemExtractor implements StreamInfoItemExtractor {
    private final String type;
    private final JsonObject renderer;
    @Nullable
    private final TimeAgoParser timeAgoParser;

    public YoutubeTvTileInfoItemExtractor(@Nonnull final JsonObject wrapped,
                                          @Nullable final TimeAgoParser timeAgoParser) {
        this.type = wrapped.getString("type");
        this.renderer = wrapped.getObject("renderer");
        this.timeAgoParser = timeAgoParser;
        recordResumePosition();
    }

    /** Remembers where YouTube says the account stopped watching this video. */
    private void recordResumePosition() {
        if (!isTile()) {
            return;
        }
        final String id = getVideoId();
        if (id == null) {
            return;
        }
        int percent = -1;
        final JsonArray overlays = tileHeader().getArray("thumbnailOverlays");
        for (int i = 0; i < overlays.size(); i++) {
            final JsonObject resume = overlays.getObject(i)
                    .getObject("thumbnailOverlayResumePlaybackRenderer");
            if (resume.has("percentDurationWatched")) {
                percent = resume.getInt("percentDurationWatched", -1);
            }
        }
        final int start = renderer.getObject("onSelectCommand").getObject("watchEndpoint")
                .getInt("startTimeSeconds", -1);
        if (start > 0 && percent < 100) {
            YoutubeTvAccountFeeds.RESUME_SECONDS.put(id, start);
        } else if (percent >= 100) {
            YoutubeTvAccountFeeds.RESUME_SECONDS.put(id, 0);
        }
    }

    private boolean isTile() {
        return "tileRenderer".equals(type);
    }

    /** @return the video id, or null if this card is not a playable video (channel, ...) */
    @Nullable
    public String getVideoId() {
        if (!isTile()) {
            final String id = renderer.getString("videoId");
            return id == null || id.isEmpty() ? null : id;
        }
        final JsonObject command = renderer.getObject("onSelectCommand");
        String id = command.getObject("watchEndpoint").getString("videoId");
        if (id == null) {
            id = command.getObject("reelWatchEndpoint").getString("videoId");
        }
        return id == null || id.isEmpty() ? null : id;
    }

    private JsonObject tileHeader() {
        return renderer.getObject("header").getObject("tileHeaderRenderer");
    }

    /** Text of every item of every line of the tile metadata, line by line. */
    private List<List<String>> tileLines() {
        final List<List<String>> lines = new ArrayList<>();
        final JsonArray lineArray = renderer.getObject("metadata")
                .getObject("tileMetadataRenderer").getArray("lines");
        for (int i = 0; i < lineArray.size(); i++) {
            final List<String> line = new ArrayList<>();
            final JsonArray items = lineArray.getObject(i).getObject("lineRenderer")
                    .getArray("items");
            for (int j = 0; j < items.size(); j++) {
                final String t = YoutubeTvAccountFeeds.text(items.getObject(j)
                        .getObject("lineItemRenderer").getObject("text"));
                if (t != null && !t.trim().isEmpty() && !t.trim().equals("•")) {
                    line.add(t.trim());
                }
            }
            lines.add(line);
        }
        return lines;
    }

    @Override
    public String getName() throws ParsingException {
        final String name = isTile()
                ? YoutubeTvAccountFeeds.text(renderer.getObject("metadata")
                        .getObject("tileMetadataRenderer").getObject("title"))
                : YoutubeTvAccountFeeds.text(renderer.getObject("title"));
        if (name == null) {
            throw new ParsingException("Could not get name");
        }
        return name;
    }

    @Override
    public String getUrl() throws ParsingException {
        final String id = getVideoId();
        if (id == null) {
            throw new ParsingException("Not a video");
        }
        return "https://www.youtube.com/watch?v=" + id;
    }

    @Override
    public String getThumbnailUrl() throws ParsingException {
        final JsonArray thumbs = (isTile() ? tileHeader() : renderer)
                .getObject("thumbnail").getArray("thumbnails");
        if (!thumbs.isEmpty()) {
            final String url = thumbs.getObject(thumbs.size() - 1).getString("url");
            if (url != null) {
                return YoutubeParsingHelper.fixThumbnailUrl(url);
            }
        }
        final String id = getVideoId();
        return id == null ? null : "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg";
    }

    @Nullable
    private String overlayStyle() {
        final JsonArray overlays = isTile() ? tileHeader().getArray("thumbnailOverlays")
                : renderer.getArray("thumbnailOverlays");
        for (int i = 0; i < overlays.size(); i++) {
            final JsonObject status = overlays.getObject(i)
                    .getObject("thumbnailOverlayTimeStatusRenderer");
            if (!status.isEmpty()) {
                return status.getString("style");
            }
        }
        return null;
    }

    @Override
    public StreamType getStreamType() {
        return "LIVE".equals(overlayStyle()) ? StreamType.LIVE_STREAM : StreamType.VIDEO_STREAM;
    }

    @Override
    public long getDuration() {
        String text = null;
        if (isTile()) {
            final JsonArray overlays = tileHeader().getArray("thumbnailOverlays");
            for (int i = 0; i < overlays.size() && text == null; i++) {
                text = YoutubeTvAccountFeeds.text(overlays.getObject(i)
                        .getObject("thumbnailOverlayTimeStatusRenderer").getObject("text"));
            }
        } else {
            text = YoutubeTvAccountFeeds.text(renderer.getObject("lengthText"));
        }
        if (text == null || !text.contains(":")) {
            return -1;
        }
        try {
            return YoutubeParsingHelper.parseDurationString(text.trim());
        } catch (final Exception e) {
            return -1;
        }
    }

    @Nullable
    private String findLineItem(@Nonnull final String... contains) {
        if (!isTile()) {
            return null;
        }
        for (final List<String> line : tileLines()) {
            for (final String item : line) {
                final String lower = item.toLowerCase();
                for (final String c : contains) {
                    if (lower.contains(c)) {
                        return item;
                    }
                }
            }
        }
        return null;
    }

    @Override
    public long getViewCount() {
        String text = isTile() ? findLineItem("view", "watching")
                : YoutubeTvAccountFeeds.text(renderer.getObject("shortViewCountText"));
        if (text == null && !isTile()) {
            text = YoutubeTvAccountFeeds.text(renderer.getObject("viewCountText"));
        }
        if (text == null) {
            return -1;
        }
        try {
            return Utils.mixedNumberWordToLong(text.replaceAll("(?i)\\s*(views?|watching)", ""));
        } catch (final Exception e) {
            return -1;
        }
    }

    @Override
    public String getUploaderName() {
        if (isTile()) {
            // line 0 is the channel name on TV tiles
            final List<List<String>> lines = tileLines();
            if (!lines.isEmpty() && !lines.get(0).isEmpty()) {
                return lines.get(0).get(0);
            }
            return null;
        }
        String name = YoutubeTvAccountFeeds.text(renderer.getObject("shortBylineText"));
        if (name == null) {
            name = YoutubeTvAccountFeeds.text(renderer.getObject("longBylineText"));
        }
        return name;
    }

    @Override
    public String getTextualUploadDate() {
        return isTile() ? findLineItem(" ago", "streamed", "premiere")
                : YoutubeTvAccountFeeds.text(renderer.getObject("publishedTimeText"));
    }

    @Override
    public DateWrapper getUploadDate() {
        final String textual = getTextualUploadDate();
        if (textual == null || timeAgoParser == null) {
            return null;
        }
        try {
            return timeAgoParser.parse(textual);
        } catch (final Exception e) {
            return null;
        }
    }
}
