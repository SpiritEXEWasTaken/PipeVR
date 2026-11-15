package org.schabi.newpipe.player

import android.view.View
import io.reactivex.rxjava3.disposables.CompositeDisposable
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.pipevr.PipeVrRatingBinder
import org.schabi.newpipe.pipevr.PipeVrShorts
import org.schabi.newpipe.util.Localization

/**
 * PipeVR: the Shorts layout of the player, active while the queue is a Shorts queue: each short
 * loops, and a side column offers like/dislike (with counts), comments, previous and next.
 */
class PlayerShortsController(private val player: Player) {
    private val disposables = CompositeDisposable()
    private var repeatBeforeShorts: RepeatMode? = null

    private var lastInfo: StreamInfo? = null

    /** PipeVR: whether the current video is shown as a short (VR uses this too). */
    var isActive = false
        private set

    /** The short being shown. */
    val currentInfo: StreamInfo?
        get() = lastInfo

    /** A YouTube short opened anywhere: a /shorts/ link, or under 3 minutes and vertical. */
    private fun looksLikeShort(info: StreamInfo): Boolean {
        if (info.serviceId != org.schabi.newpipe.extractor.ServiceList.YouTube.serviceId) {
            return false
        }
        if (info.url?.contains("/shorts/") == true || info.isShortFormContent) return true
        val size = player.videoSize
        val vertical = size != null && size.size >= 2 && size[1] > size[0] && size[0] > 0
        return vertical && info.duration in 1..180
    }

    /** The video's shape is known only once it plays: check again then. */
    fun onVideoSizeChanged() {
        lastInfo?.let { onMetadataChanged(it) }
    }

    fun onMetadataChanged(info: StreamInfo) {
        val binding = player.binding ?: return
        lastInfo = info
        val shorts = PipeVrShorts.isShortsQueue(player.playQueue) || looksLikeShort(info)
        isActive = shorts
        binding.shortsOverlay.visibility = if (shorts) View.VISIBLE else View.GONE
        disposables.clear()
        if (!shorts) {
            repeatBeforeShorts?.let { player.setRepeatMode(it) }
            repeatBeforeShorts = null
            return
        }
        if (repeatBeforeShorts == null) {
            repeatBeforeShorts = player.repeatMode
        }
        player.setRepeatMode(RepeatMode.ONE)  // shorts loop, like on YouTube

        val context = player.context
        binding.shortsLikeCount.text =
            if (info.likeCount >= 0) Localization.shortCount(context, info.likeCount) else ""
        binding.shortsDislikeCount.text =
            if (info.dislikeCount >= 0) Localization.shortCount(context, info.dislikeCount) else ""
        PipeVrRatingBinder.bind(
            info, binding.shortsLike, binding.shortsLikeCount,
            binding.shortsDislike, binding.shortsDislikeCount, disposables
        )
        binding.shortsComments.setOnClickListener {
            PipeVrShorts.showComments(player.parentActivity, info.serviceId, info.url, info.name)
        }
        binding.shortsPrevious.setOnClickListener { player.playPrevious() }
        binding.shortsNext.setOnClickListener { player.playNext() }
        PipeVrShorts.maybeLoadMore(player.playQueue)
    }

    fun destroy() {
        disposables.clear()
    }
}
