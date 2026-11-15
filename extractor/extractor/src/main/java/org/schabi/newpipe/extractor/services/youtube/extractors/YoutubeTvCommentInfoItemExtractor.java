package org.schabi.newpipe.extractor.services.youtube.extractors;

import com.grack.nanojson.JsonObject;

import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.localization.TimeAgoParser;
import org.schabi.newpipe.extractor.services.youtube.YoutubeTvAccountFeeds;

import javax.annotation.Nonnull;

/**
 * PipeVR: a comment from the signed-in TV app. Its replies are loaded from the comment's
 * detail view (which also has the like actions).
 */
public class YoutubeTvCommentInfoItemExtractor extends YoutubeCommentsInfoItemExtractor {
    private final JsonObject renderer;
    private final String pageUrl;

    public YoutubeTvCommentInfoItemExtractor(@Nonnull final JsonObject commentRenderer,
                                             @Nonnull final String url,
                                             @Nonnull final TimeAgoParser timeAgoParser) {
        super(commentRenderer, null, url, timeAgoParser);
        this.renderer = commentRenderer;
        this.pageUrl = url;
        YoutubeTvAccountFeeds.recordComment(commentRenderer);
    }

    /** The TV app gives no channel id, but "@handle" works as a channel address. */
    @Override
    public String getUploaderUrl() {
        final String author = YoutubeTvAccountFeeds.text(renderer.getObject("authorText"));
        if (author != null && author.startsWith("@")) {
            return "https://www.youtube.com/" + author.trim();
        }
        return "";
    }

    @Override
    public Page getReplies() {
        if (renderer.getInt("replyCount", 0) <= 0) {
            return null;
        }
        final String token = YoutubeTvAccountFeeds.COMMENT_DETAIL_TOKENS
                .get(renderer.getString("commentId", ""));
        return token == null ? null : new Page(pageUrl, token);
    }
}
