package org.schabi.newpipe.extractor.services.twitch.extractors;

import com.grack.nanojson.JsonObject;

import org.schabi.newpipe.extractor.channel.ChannelInfoItemExtractor;
import org.schabi.newpipe.extractor.localization.DateWrapper;
import org.schabi.newpipe.extractor.services.twitch.TwitchApi;
import org.schabi.newpipe.extractor.stream.StreamInfoItemExtractor;
import org.schabi.newpipe.extractor.stream.StreamType;

import java.time.OffsetDateTime;

/** PipeVR: list items of Twitch (live streams, past broadcasts, channels). */
public final class TwitchItems {
    /** GraphQL fields of a live stream item. */
    public static final String STREAM_FIELDS = "id title viewersCount createdAt "
            + "previewImageURL(width: 640, height: 360) "
            + "broadcaster { login displayName profileImageURL(width: 70) } game { name }";
    /** GraphQL fields of a past broadcast item. */
    public static final String VIDEO_FIELDS = "id title lengthSeconds viewCount publishedAt "
            + "previewThumbnailURL(width: 640, height: 360) "
            + "owner { login displayName profileImageURL(width: 70) }";

    private TwitchItems() {
    }

    /** A live stream (node with STREAM_FIELDS). */
    public static final class Live implements StreamInfoItemExtractor {
        private final JsonObject node;

        public Live(final JsonObject node) {
            this.node = node;
        }

        private JsonObject broadcaster() {
            return node.getObject("broadcaster");
        }

        @Override
        public String getName() {
            final String title = node.getString("title", "");
            final String game = node.getObject("game").getString("name");
            return game == null ? title : title + " · " + game;
        }

        @Override
        public String getUrl() {
            return TwitchApi.liveUrl(broadcaster().getString("login"));
        }

        @Override
        public String getThumbnailUrl() {
            return node.getString("previewImageURL");
        }

        @Override
        public StreamType getStreamType() {
            return StreamType.LIVE_STREAM;
        }

        @Override
        public long getDuration() {
            return -1;
        }

        @Override
        public long getViewCount() {
            return node.getLong("viewersCount", -1);
        }

        @Override
        public String getUploaderName() {
            return broadcaster().getString("displayName");
        }

        @Override
        public String getUploaderUrl() {
            return TwitchApi.channelUrl(broadcaster().getString("login"));
        }

        @Override
        public String getUploaderAvatarUrl() {
            return broadcaster().getString("profileImageURL");
        }

        @Override
        public String getTextualUploadDate() {
            return null;
        }

        @Override
        public DateWrapper getUploadDate() {
            return null;
        }
    }

    /** A past broadcast (node with VIDEO_FIELDS). */
    public static final class Vod implements StreamInfoItemExtractor {
        private final JsonObject node;

        public Vod(final JsonObject node) {
            this.node = node;
        }

        @Override
        public String getName() {
            return node.getString("title", "");
        }

        @Override
        public String getUrl() {
            return TwitchApi.vodUrl(node.getString("id"));
        }

        @Override
        public String getThumbnailUrl() {
            return node.getString("previewThumbnailURL");
        }

        @Override
        public StreamType getStreamType() {
            return StreamType.VIDEO_STREAM;
        }

        @Override
        public long getDuration() {
            return node.getLong("lengthSeconds", -1);
        }

        @Override
        public long getViewCount() {
            return node.getLong("viewCount", -1);
        }

        @Override
        public String getUploaderName() {
            return node.getObject("owner").getString("displayName");
        }

        @Override
        public String getUploaderUrl() {
            return TwitchApi.channelUrl(node.getObject("owner").getString("login"));
        }

        @Override
        public String getUploaderAvatarUrl() {
            return node.getObject("owner").getString("profileImageURL");
        }

        @Override
        public String getTextualUploadDate() {
            return node.getString("publishedAt");
        }

        @Override
        public DateWrapper getUploadDate() {
            final String date = node.getString("publishedAt");
            try {
                return date == null ? null : new DateWrapper(OffsetDateTime.parse(date));
            } catch (final Exception e) {
                return null;
            }
        }
    }

    /** A channel (user node with login displayName profileImageURL followers stream). */
    public static final class Channel implements ChannelInfoItemExtractor {
        private final JsonObject node;

        public Channel(final JsonObject node) {
            this.node = node;
        }

        @Override
        public String getName() {
            final String name = node.getString("displayName", node.getString("login"));
            return node.getObject("stream").isEmpty() ? name : name + " 🔴 LIVE";
        }

        @Override
        public String getUrl() {
            return TwitchApi.channelUrl(node.getString("login"));
        }

        @Override
        public String getThumbnailUrl() {
            return node.getString("profileImageURL");
        }

        @Override
        public String getDescription() {
            return node.getObject("stream").getString("title", node.getString("description"));
        }

        @Override
        public long getSubscriberCount() {
            return node.getObject("followers").getLong("totalCount", -1);
        }

        @Override
        public long getStreamCount() {
            return -1;
        }

        @Override
        public boolean isVerified() {
            return false;
        }
    }
}
