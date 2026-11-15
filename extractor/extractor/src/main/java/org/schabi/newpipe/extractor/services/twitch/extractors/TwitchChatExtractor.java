package org.schabi.newpipe.extractor.services.twitch.extractors;

import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.bulletComments.BulletCommentsExtractor;
import org.schabi.newpipe.extractor.bulletComments.BulletCommentsInfoItem;
import org.schabi.newpipe.extractor.bulletComments.BulletCommentsInfoItemExtractor;
import org.schabi.newpipe.extractor.bulletComments.BulletCommentsInfoItemsCollector;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.services.twitch.TwitchChat;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nonnull;

/** PipeVR: live Twitch chat, shown in PipePipe's chat tab/panel and as bullet comments. */
public class TwitchChatExtractor extends BulletCommentsExtractor {
    private TwitchChat chat;
    private boolean live;

    public TwitchChatExtractor(final StreamingService service, final ListLinkHandler handler) {
        super(service, handler);
    }

    @Override
    public void onFetchPage(@Nonnull final Downloader downloader) throws ExtractionException {
        final String id = getId();
        live = id.startsWith("live:");
        if (!live) {
            return; // past broadcasts: no chat replay yet
        }
        try {
            chat = TwitchChat.join(id.substring(5));
        } catch (final Exception e) {
            throw new ExtractionException("Could not join the Twitch chat", e);
        }
    }

    @Nonnull
    @Override
    public InfoItemsPage<BulletCommentsInfoItem> getInitialPage() {
        return new InfoItemsPage<>(new BulletCommentsInfoItemsCollector(getServiceId()), null);
    }

    @Override
    public List<BulletCommentsInfoItem> getLiveMessages() {
        if (chat == null) {
            return Collections.emptyList();
        }
        // PipeVR: a newer connection to the same channel (e.g. the read-only fallback, or
        // another load of this stream) replaced ours: follow it instead of a closed one
        final TwitchChat cur = TwitchChat.current();
        if (cur != null && cur != chat && cur.getChannel().equals(chat.getChannel())
                && !cur.isClosed()) {
            chat = cur;
        } else if (chat.isClosed()) {
            reconnect();
        }
        final BulletCommentsInfoItemsCollector collector =
                new BulletCommentsInfoItemsCollector(getServiceId());
        for (final TwitchChat.Message m : chat.drain()) {
            collector.commit(new Item(m));
        }
        return new InfoItemsPage<>(collector, null).getItems();
    }

    @Override
    public boolean isLive() {
        return live;
    }

    @Override
    public boolean isDisabled() {
        return !live;
    }

    @Override
    public void disconnect() {
        if (chat != null) {
            chat.close();
        }
    }

    @Override
    public void reconnect() {
        // PipeVR: only after the connection really ended; "not open yet" is just connecting
        // (re-joining then closed every new connection and dropped most messages)
        if (chat != null && (chat.isClosed() || chat.isClosing())) {
            try {
                chat = TwitchChat.join(chat.getChannel());
            } catch (final Exception ignored) {
                // stays disconnected
            }
        }
    }

    private static final class Item implements BulletCommentsInfoItemExtractor {
        private final TwitchChat.Message message;

        Item(final TwitchChat.Message message) {
            this.message = message;
        }

        @Override
        public String getName() {
            return message.author;
        }

        @Override
        public String getCommentText() {
            return message.text;
        }

        @Override
        public String getAuthorName() {
            return message.author;
        }

        @Override
        public int getArgbColor() {
            return 0xFFFFFFFF;
        }

        @Override
        public Duration getDuration() {
            return Duration.ZERO;
        }

        @Override
        public boolean isLive() {
            return true;
        }
    }
}
