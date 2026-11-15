package org.schabi.newpipe.extractor.services.twitch.extractors;

import com.grack.nanojson.JsonObject;

import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.linkhandler.LinkHandler;
import org.schabi.newpipe.extractor.localization.DateWrapper;
import org.schabi.newpipe.extractor.services.twitch.TwitchApi;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.Description;
import org.schabi.newpipe.extractor.stream.StreamExtractor;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nonnull;

/** PipeVR: a Twitch live stream or past broadcast, played from Twitch's HLS playlists. */
public class TwitchStreamExtractor extends StreamExtractor {
    private boolean live;
    private String login;
    private JsonObject data;
    private String hlsUrl;

    public TwitchStreamExtractor(final StreamingService service, final LinkHandler linkHandler) {
        super(service, linkHandler);
    }

    @Override
    public void onFetchPage(@Nonnull final Downloader downloader)
            throws IOException, ExtractionException {
        final String id = getId();
        live = id.startsWith("live:");
        if (live) {
            login = id.substring(5);
            data = TwitchApi.gql("query($l: String!) { user(login: $l) { login displayName "
                    + "description profileImageURL(width: 150) followers { totalCount } "
                    + "broadcastSettings { isMature } "
                    + "stream { id title viewersCount createdAt "
                    + "contentClassificationLabels { id } "
                    + "previewImageURL(width: 1280, height: 720) game { name } } } }",
                    TwitchApi.vars("l", login)).getObject("user");
            if (data.getObject("stream").isEmpty()) {
                throw new ContentNotAvailableException(
                        data.getString("displayName", login) + " is offline");
            }
            hlsUrl = TwitchApi.liveHlsUrl(login);
        } else {
            final String vodId = id.substring(4);
            data = TwitchApi.gql("query($id: ID!) { video(id: $id) { id title description "
                    + "lengthSeconds viewCount publishedAt "
                    + "previewThumbnailURL(width: 1280, height: 720) "
                    + "owner { login displayName profileImageURL(width: 150) "
                    + "followers { totalCount } } game { name } } }",
                    TwitchApi.vars("id", vodId)).getObject("video");
            if (data.isEmpty()) {
                throw new ContentNotAvailableException("This Twitch video is not available");
            }
            login = data.getObject("owner").getString("login");
            hlsUrl = TwitchApi.vodHlsUrl(vodId);
        }
    }

    private JsonObject owner() {
        return live ? data : data.getObject("owner");
    }

    @Nonnull
    @Override
    public String getName() {
        return live ? data.getObject("stream").getString("title", login)
                : data.getString("title", "");
    }

    @Override
    public String getThumbnailUrl() {
        return live ? data.getObject("stream").getString("previewImageURL")
                : data.getString("previewThumbnailURL");
    }

    @Override
    public String getUploaderUrl() {
        return TwitchApi.channelUrl(login);
    }

    @Override
    public String getUploaderName() {
        return owner().getString("displayName", login);
    }

    @Override
    public String getUploaderAvatarUrl() {
        return owner().getString("profileImageURL");
    }

    @Override
    public long getUploaderSubscriberCount() {
        return owner().getObject("followers").getLong("totalCount", -1);
    }

    /** PipeVR: mature streams are flagged 18+ so the app warns before playing them. */
    @Override
    public int getAgeLimit() {
        if (!live) {
            return NO_AGE_LIMIT;
        }
        final boolean mature = data.getObject("broadcastSettings").getBoolean("isMature", false)
                || !data.getObject("stream").getArray("contentClassificationLabels").isEmpty();
        return mature ? 18 : NO_AGE_LIMIT;
    }

    @Nonnull
    @Override
    public Description getDescription() {
        final String game = (live ? data.getObject("stream") : data).getObject("game")
                .getString("name");
        final String text = live ? data.getString("description", "")
                : data.getString("description", "");
        return new Description((game != null ? "Playing " + game + "\n\n" : "")
                + (text == null ? "" : text), Description.PLAIN_TEXT);
    }

    @Override
    public long getLength() {
        return live ? 0 : data.getLong("lengthSeconds", 0);
    }

    @Override
    public long getViewCount() {
        return live ? data.getObject("stream").getLong("viewersCount", -1)
                : data.getLong("viewCount", -1);
    }

    @Override
    public String getTextualUploadDate() {
        return live ? data.getObject("stream").getString("createdAt")
                : data.getString("publishedAt");
    }

    @Override
    public DateWrapper getUploadDate() {
        final String date = getTextualUploadDate();
        try {
            return date == null ? null : new DateWrapper(OffsetDateTime.parse(date));
        } catch (final Exception e) {
            return null;
        }
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
        // the master playlist: the player picks among Twitch's qualities (source ... 160p)
        return Collections.singletonList(new VideoStream.Builder()
                .setId("twitch-" + getId())
                .setContent(hlsUrl, true)
                .setIsVideoOnly(false)
                .setMediaFormat(MediaFormat.MPEG_4)
                .setDeliveryMethod(DeliveryMethod.HLS)
                .setResolution("1080p")
                .build());
    }

    @Override
    public List<VideoStream> getVideoOnlyStreams() {
        return Collections.emptyList();
    }

    @Override
    public StreamType getStreamType() {
        return live ? StreamType.LIVE_STREAM : StreamType.VIDEO_STREAM;
    }
}
