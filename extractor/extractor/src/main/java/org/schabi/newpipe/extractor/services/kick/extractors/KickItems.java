package org.schabi.newpipe.extractor.services.kick.extractors;

import com.grack.nanojson.JsonObject;

import org.schabi.newpipe.extractor.channel.ChannelInfoItemExtractor;
import org.schabi.newpipe.extractor.localization.DateWrapper;
import org.schabi.newpipe.extractor.services.kick.KickApi;
import org.schabi.newpipe.extractor.stream.StreamInfoItemExtractor;
import org.schabi.newpipe.extractor.stream.StreamType;

/** PipeVR: Kick list items. */
public final class KickItems {
    private KickItems() {
    }

    /** A live stream (livestreams list item with a "channel" object, or channel.livestream). */
    public static final class Live implements StreamInfoItemExtractor {
        private final JsonObject live;
        private final String slug;
        private final String name;
        private final String avatar;

        public Live(final JsonObject live, final String slug, final String name,
                    final String avatar) {
            this.live = live;
            this.slug = slug;
            this.name = name;
            this.avatar = avatar;
        }

        /** From an item of /stream/livestreams. */
        public static Live fromListItem(final JsonObject item) {
            final JsonObject channel = item.getObject("channel");
            final JsonObject user = channel.getObject("user");
            return new Live(item, channel.getString("slug"),
                    user.getString("username", channel.getString("slug")),
                    avatarOf(user));
        }

        /** Kick has used profile_pic, profilepic and (now) profilePic. */
        public static String avatarOf(final JsonObject user) {
            return user.getString("profilePic",
                    user.getString("profile_pic", user.getString("profilepic")));
        }

        @Override
        public String getName() {
            final String title = live.getString("session_title", slug);
            final String mature = live.getBoolean("is_mature", false) ? "🔞 " : "";
            return mature + title;
        }

        @Override
        public String getUrl() {
            return KickApi.liveUrl(slug);
        }

        @Override
        public String getThumbnailUrl() {
            return KickApi.thumbnail(live.get("thumbnail"));
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
            return live.getLong("viewer_count", live.getLong("viewers", -1));
        }

        @Override
        public String getUploaderName() {
            return name;
        }

        @Override
        public String getUploaderUrl() {
            return KickApi.channelUrl(slug);
        }

        @Override
        public String getUploaderAvatarUrl() {
            return avatar;
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

    /** A past broadcast (item of /api/v2/channels/{slug}/videos). */
    public static final class Vod implements StreamInfoItemExtractor {
        private final JsonObject item;
        private final String slug;
        private final String name;

        public Vod(final JsonObject item, final String slug, final String name) {
            this.item = item;
            this.slug = slug;
            this.name = name;
        }

        @Override
        public String getName() {
            return (item.getBoolean("is_mature", false) ? "🔞 " : "")
                    + item.getString("session_title", "");
        }

        @Override
        public String getUrl() {
            return KickApi.vodUrl(item.getObject("video").getString("uuid"));
        }

        @Override
        public String getThumbnailUrl() {
            return KickApi.thumbnail(item.get("thumbnail"));
        }

        @Override
        public StreamType getStreamType() {
            return StreamType.VIDEO_STREAM;
        }

        @Override
        public long getDuration() {
            return item.getLong("duration", -1000) / 1000;
        }

        @Override
        public long getViewCount() {
            return item.getObject("video").getLong("views", item.getLong("views", -1));
        }

        @Override
        public String getUploaderName() {
            return name;
        }

        @Override
        public String getUploaderUrl() {
            return KickApi.channelUrl(slug);
        }

        @Override
        public String getTextualUploadDate() {
            return item.getString("start_time", item.getString("created_at"));
        }

        @Override
        public DateWrapper getUploadDate() {
            return null;
        }
    }

    /** A channel (search result / channel object). */
    public static final class Channel implements ChannelInfoItemExtractor {
        private final JsonObject channel;

        public Channel(final JsonObject channel) {
            this.channel = channel;
        }

        @Override
        public String getName() {
            final String n = channel.getObject("user").getString("username",
                    channel.getString("slug"));
            final JsonObject live = channel.getObject("livestream");
            final boolean isLive = (!live.isEmpty() && live.getBoolean("is_live", false))
                    || channel.getBoolean("isLive", false);
            return isLive ? n + " 🔴 LIVE" : n;
        }

        @Override
        public String getUrl() {
            return KickApi.channelUrl(channel.getString("slug"));
        }

        @Override
        public String getThumbnailUrl() {
            return Live.avatarOf(channel.getObject("user"));
        }

        @Override
        public String getDescription() {
            return channel.getObject("user").getString("bio", "");
        }

        @Override
        public long getSubscriberCount() {
            return channel.getLong("followers_count", channel.getLong("followersCount", -1));
        }

        @Override
        public long getStreamCount() {
            return -1;
        }

        @Override
        public boolean isVerified() {
            return channel.has("verified") && channel.get("verified") != null
                    && !(channel.get("verified") instanceof Boolean
                    && !channel.getBoolean("verified"));
        }
    }
}
