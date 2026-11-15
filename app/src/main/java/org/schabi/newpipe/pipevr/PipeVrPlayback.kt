package org.schabi.newpipe.pipevr

import androidx.appcompat.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.services.youtube.YoutubeOAuth
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.util.Collections

/**
 * PipeVR: how videos that need an account are played.
 *
 * - Everything plays anonymously first (best formats, no account involved).
 * - Members-only: if signed in, it is retried with the account and gets a green badge.
 * - Age-restricted: if signed in, the user is warned and can play it with the account;
 *   if not signed in, the user is told to sign in.
 */
object PipeVrPlayback {
    private val membersOnlyIds: MutableSet<String> = Collections.synchronizedSet(HashSet())

    private const val MEMBERS_GREEN = "#2E7D32"

    @JvmStatic
    fun videoIdOf(url: String?): String? = try {
        if (url == null) null else YoutubeStreamLinkHandlerFactory.getInstance().getId(url)
    } catch (e: Exception) {
        null
    }

    /** Used by ExtractorHelper instead of a plain StreamInfo.getInfo(). */
    @JvmStatic
    fun getStreamInfo(serviceId: Int, url: String): StreamInfo {
        val service = NewPipe.getService(serviceId)
        try {
            return StreamInfo.getInfo(service, url)
        } catch (e: PaidContentException) {
            val id = videoIdOf(url)
            val isMembersOnly = e.message?.contains("members", ignoreCase = true) == true
            if (serviceId != ServiceList.YouTube.serviceId || id == null || !isMembersOnly ||
                !YouTubeAccountManager.isSignedIn()
            ) {
                throw e
            }
            YoutubeOAuth.requestAuthFor(id)
            membersOnlyIds += id
            org.schabi.newpipe.util.InfoCache.getInstance().clearCache()
            return StreamInfo.getInfo(service, url)
        }
    }

    @JvmStatic
    fun isMembersOnly(url: String?): Boolean = videoIdOf(url)?.let { it in membersOnlyIds } == true

    /** The title with a small green "Members only" badge after it, for members-only videos. */
    @JvmStatic
    fun decorateTitle(context: Context, title: CharSequence?, url: String?): CharSequence? {
        if (title == null || !isMembersOnly(url)) return title
        val badge = " " + context.getString(R.string.pipevr_members_only) + " "
        return SpannableStringBuilder(title).append("  ").apply {
            val start = length
            append(badge)
            setSpan(BackgroundColorSpan(Color.parseColor(MEMBERS_GREEN)), start, length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(Color.WHITE), start, length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(RelativeSizeSpan(0.7f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /**
     * For an age-restricted video: warn, then mark it to be played with the account.
     * [onConfirmed] reloads the video.
     */
    @JvmStatic
    fun confirmAgeRestricted(context: Context, url: String?, onConfirmed: Runnable) {
        val id = videoIdOf(url) ?: return
        val account = YouTubeAccountManager.getSelected() ?: return
        AlertDialog.Builder(context)
            .setTitle(R.string.pipevr_age_restricted_title)
            .setMessage(context.getString(R.string.pipevr_age_restricted_message, account.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.pipevr_age_restricted_play) { _, _ ->
                YoutubeOAuth.requestAuthFor(id)
                // comments/related were cached from the anonymous attempt ("disabled")
                org.schabi.newpipe.util.InfoCache.getInstance().clearCache()
                onConfirmed.run()
            }
            .show()
    }

    @JvmStatic
    fun openSignIn(context: Context) {
        context.startActivity(
            Intent(context, YouTubeAccountsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
