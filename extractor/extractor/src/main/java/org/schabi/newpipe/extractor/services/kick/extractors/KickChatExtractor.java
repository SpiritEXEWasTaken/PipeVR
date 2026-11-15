package org.schabi.newpipe.extractor.services.kick.extractors;

import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.bulletComments.BulletCommentsExtractor;
import org.schabi.newpipe.extractor.bulletComments.BulletCommentsInfoItem;
import org.schabi.newpipe.extractor.bulletComments.BulletCommentsInfoItemExtractor;
import org.schabi.newpipe.extractor.bulletComments.BulletCommentsInfoItemsCollector;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.services.kick.KickApi;
import org.schabi.newpipe.extractor.services.kick.KickChat;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nonnull;

/** PipeVR: live Kick chat for PipePipe's chat tab/panel. */
public class KickChatExtractor extends BulletCommentsExtractor {
    private KickChat chat;
    private boolean live;

    public KickChatExtractor(final StreamingService service, final ListLinkHandler handler) {
        super(service, handler);
    }

    @Override
    public void onFetchPage(@Nonnull final Downloader downloader) throws ExtractionException {
        live = getId().startsWith("live:");
        if (!live) {
            return;
        }
        try {
            final long room = KickApi.getObject("/api/v2/channels/"
                    + KickApi.enc(getId().substring(5))).getObject("chatroom").getLong("id", -1);
            if (room > 0) {
                chat = KickChat.join(room);
            }
        } catch (final Exception e) {
            throw new ExtractionException("Could not join the Kick chat", e);
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
        final BulletCommentsInfoItemsCollector collector =
                new BulletCommentsInfoItemsCollector(getServiceId());
        for (final KickChat.Message m : chat.drain()) {
            collector.commit(new BulletCommentsInfoItemExtractor() {
                @Override
                public String getName() {
                    return m.author;
                }

                @Override
                public String getCommentText() {
                    return m.text;
                }

                @Override
                public String getAuthorName() {
                    return m.author;
                }

                @Override
                public Duration getDuration() {
                    return Duration.ZERO;
                }

                @Override
                public boolean isLive() {
                    return true;
                }
            });
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
        // only after the connection really ended ("not open yet" is still connecting)
        if (chat != null && (chat.isClosed() || chat.isClosing())) {
            try {
                chat = KickChat.join(chat.getChatroomId());
            } catch (final Exception ignored) {
                // stays disconnected
            }
        }
    }
}
