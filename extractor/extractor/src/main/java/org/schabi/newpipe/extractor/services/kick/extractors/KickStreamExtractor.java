package org.schabi.newpipe.extractor.services.kick.extractors;

import com.grack.nanojson.JsonObject;

import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.linkhandler.LinkHandler;
import org.schabi.newpipe.extractor.services.kick.KickApi;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.Description;
import org.schabi.newpipe.extractor.stream.StreamExtractor;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nonnull;

/** PipeVR: a Kick live stream or past broadcast (HLS). */
public class KickStreamExtractor extends StreamExtractor {
    private boolean live;
    private String slug;
    private JsonObject channel;
    private JsonObject stream;
    private String hlsUrl;

    public KickStreamExtractor(final StreamingService service, final LinkHandler linkHandler) {
        super(service, linkHandler);
    }

    @Override
    public void onFetchPage(@Nonnull final Downloader downloader)
            throws IOException, ExtractionException {
        final String id = getId();
        live = id.startsWith("live:");
        if (live) {
            slug = id.substring(5);
            channel = KickApi.getObject("/api/v2/channels/" + KickApi.enc(slug));
            stream = channel.getObject("livestream");
            if (stream.isEmpty() || !stream.getBoolean("is_live", false)) {
                throw new ContentNotAvailableException(
                        channel.getObject("user").getString("username", slug) + " is offline");
            }
            hlsUrl = channel.getString("playback_url");
        } else {
            final JsonObject video = KickApi.getObject("/api/v1/video/" + id.substring(4));
            stream = video.getObject("livestream");
            channel = stream.getObject("channel");
            slug = channel.getString("slug");
            hlsUrl = video.getString("source");
            if (stream.isEmpty()) {
                stream = video;
            }
        }
        if (hlsUrl == null || hlsUrl.isEmpty()) {
            throw new ContentNotAvailableException("Kick did not provide this video");
        }
    }

    @Nonnull
    @Override
    public String getName() {
        return stream.getString("session_title", slug);
    }

    @Override
    public String getThumbnailUrl() {
        return KickApi.thumbnail(stream.get("thumbnail"));
    }

    @Override
    public String getUploaderUrl() {
        return KickApi.channelUrl(slug);
    }

    @Override
    public String getUploaderName() {
        return channel.getObject("user").getString("username", slug);
    }

    @Override
    public String getUploaderAvatarUrl() {
        return KickItems.Live.avatarOf(channel.getObject("user"));
    }

    @Override
    public long getUploaderSubscriberCount() {
        return channel.getLong("followers_count", -1);
    }

    /** PipeVR: 18+ streams are flagged so the app warns before playing them. */
    @Override
    public int getAgeLimit() {
        return stream.getBoolean("is_mature", false) ? 18 : NO_AGE_LIMIT;
    }

    @Nonnull
    @Override
    public Description getDescription() {
        final StringBuilder text = new StringBuilder();
        if (!stream.getArray("categories").isEmpty()) {
            text.append("Playing ").append(stream.getArray("categories").getObject(0)
                    .getString("name", "")).append("\n\n");
        }
        text.append(channel.getObject("user").getString("bio", ""));
        return new Description(text.toString(), Description.PLAIN_TEXT);
    }

    @Override
    public long getLength() {
        return live ? 0 : stream.getLong("duration", 0) / 1000;
    }

    @Override
    public long getViewCount() {
        return live ? stream.getLong("viewer_count", -1) : stream.getLong("views", -1);
    }

    @Override
    public String getTextualUploadDate() {
        return stream.getString("start_time", stream.getString("created_at"));
    }

    @Nonnull
    @Override
    public String getHlsUrl() {
        return live ? hlsUrl : "";
    }

    @Override
    public List<AudioStream> getAudioStreams() {
        return Collections.emptyList();
    }

    @Override
    public List<VideoStream> getVideoStreams() throws ExtractionException {
        return Collections.singletonList(new VideoStream.Builder()
                .setId("kick-" + getId()).setContent(hlsUrl, true).setIsVideoOnly(false)
                .setMediaFormat(MediaFormat.MPEG_4).setDeliveryMethod(DeliveryMethod.HLS)
                .setResolution("1080p").build());
    }

    @Override
    public List<VideoStream> getVideoOnlyStreams() {
        return Collections.emptyList();
    }

    @Override
    public StreamType getStreamType() {
        return live ? StreamType.LIVE_STREAM : StreamType.VIDEO_STREAM;
    }

    /** The chatroom of this stream (for chat). */
    public long getChatroomId() {
        return channel.getObject("chatroom").getLong("id", -1);
    }
}
