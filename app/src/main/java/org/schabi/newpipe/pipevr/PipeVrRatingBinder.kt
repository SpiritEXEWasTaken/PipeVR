package org.schabi.newpipe.pipevr

import android.graphics.Color
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.util.Localization
import org.schabi.newpipe.pipevr.YouTubeAccountActions.Rating

/**
 * PipeVR: makes the like/dislike counts on the video page work as buttons for the signed-in
 * YouTube account (highlighted when the account liked/disliked the video).
 */
object PipeVrRatingBinder {
    private val ACCENT = Color.parseColor("#4FC3F7")

    @JvmStatic
    fun bind(
        info: StreamInfo,
        upImage: ImageView,
        upCount: TextView,
        downImage: ImageView,
        downCount: TextView,
        disposables: CompositeDisposable,
    ) {
        val context = upImage.context
        val videoId = PipeVrPlayback.videoIdOf(info.url)
        if (videoId == null || !YouTubePlaylists.isAvailable(info.serviceId)) {
            listOf(upImage, upCount, downImage, downCount).forEach { it.setOnClickListener(null) }
            return
        }
        val normalCountColor = upCount.currentTextColor
        var rating = Rating.NONE
        val baseLikes = info.likeCount
        val baseDislikes = info.dislikeCount

        fun render() {
            val liked = rating == Rating.LIKE
            val disliked = rating == Rating.DISLIKE
            if (liked) upImage.setColorFilter(ACCENT) else upImage.clearColorFilter()
            if (disliked) downImage.setColorFilter(ACCENT) else downImage.clearColorFilter()
            upCount.setTextColor(if (liked) ACCENT else normalCountColor)
            downCount.setTextColor(if (disliked) ACCENT else normalCountColor)
        }

        fun setCounts(newRating: Rating, oldRating: Rating) {
            if (baseLikes >= 0) {
                var likes = upCount.tag as? Long ?: baseLikes
                if (oldRating == Rating.LIKE) likes--
                if (newRating == Rating.LIKE) likes++
                upCount.tag = likes
                upCount.text = Localization.shortCount(context, likes)
            }
            if (baseDislikes >= 0) {
                var dislikes = downCount.tag as? Long ?: baseDislikes
                if (oldRating == Rating.DISLIKE) dislikes--
                if (newRating == Rating.DISLIKE) dislikes++
                downCount.tag = dislikes
                downCount.text = Localization.shortCount(context, dislikes)
            }
        }

        fun change(target: Rating) {
            val old = rating
            val new = if (old == target) Rating.NONE else target
            rating = new
            setCounts(new, old)
            render()
            disposables.add(
                Single.fromCallable { YouTubeAccountActions.rate(videoId, new) }
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe({ ok ->
                        if (!ok) {
                            rating = old
                            setCounts(old, new)
                            render()
                            Toast.makeText(context, R.string.pipevr_playlist_failed,
                                Toast.LENGTH_SHORT).show()
                        }
                    }, {
                        rating = old
                        setCounts(old, new)
                        render()
                        Toast.makeText(context, R.string.pipevr_playlist_failed,
                            Toast.LENGTH_SHORT).show()
                    })
            )
        }

        upCount.tag = null
        downCount.tag = null
        upImage.setOnClickListener { change(Rating.LIKE) }
        upCount.setOnClickListener { change(Rating.LIKE) }
        downImage.setOnClickListener { change(Rating.DISLIKE) }
        downCount.setOnClickListener { change(Rating.DISLIKE) }

        // the account's current rating
        disposables.add(
            Single.fromCallable { YouTubeAccountActions.videoState(videoId) }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ state ->
                    rating = state.rating
                    render()
                }, { /* keep neutral */ })
        )
    }
}
