package org.schabi.newpipe.pipevr

import androidx.appcompat.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.YoutubeTvAccountFeeds
import java.util.concurrent.Executors

/**
 * PipeVR: the signed-in account's YouTube playlists (Watch later + own playlists, private
 * ones included): add/remove videos and create playlists, like the YouTube apps do.
 */
object YouTubePlaylists {
    const val WATCH_LATER = "WL"

    data class Playlist(
        val id: String,
        val title: String,
        val privacy: String?,
        val containsVideo: Boolean,
    )

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Whether YouTube playlists should be offered for this item. */
    @JvmStatic
    fun isAvailable(serviceId: Int): Boolean =
        serviceId == ServiceList.YouTube.serviceId && YouTubeAccountManager.isSignedIn()

    // ---- API (blocking) ----

    fun list(videoId: String): List<Playlist> {
        val response = YoutubeTvAccountFeeds.post("playlist/get_add_to_playlist", { b ->
            b.array("videoIds").value(videoId).end()
        }, NewPipe.getPreferredLocalization())
        val result = mutableListOf<Playlist>()
        collect(response, result)
        return result
    }

    private fun collect(node: Any?, out: MutableList<Playlist>) {
        when (node) {
            is JsonObject -> {
                val option = node.getObject("playlistAddToOptionRenderer")
                if (!option.isEmpty()) {
                    val id = option.getString("playlistId")
                    if (id != null) {
                        out += Playlist(
                            id = id,
                            title = YoutubeTvAccountFeeds.text(option.getObject("title")) ?: id,
                            privacy = option.getString("privacy"),
                            containsVideo = option.getString("containsSelectedVideos") == "ALL",
                        )
                    }
                }
                node.values.forEach { collect(it, out) }
            }
            is JsonArray -> node.forEach { collect(it, out) }
        }
    }

    fun setContains(playlistId: String, videoId: String, add: Boolean): Boolean {
        val response = YoutubeTvAccountFeeds.post("browse/edit_playlist", { b ->
            b.value("playlistId", playlistId)
                .array("actions")
                .`object`()
                .value("action", if (add) "ACTION_ADD_VIDEO" else "ACTION_REMOVE_VIDEO_BY_VIDEO_ID")
                .value(if (add) "addedVideoId" else "removedVideoId", videoId)
                .end()
                .end()
        }, NewPipe.getPreferredLocalization())
        return response.getString("status") == "STATUS_SUCCEEDED"
    }

    fun create(title: String, privacy: String, videoId: String): Boolean {
        val response = YoutubeTvAccountFeeds.post("playlist/create", { b ->
            b.value("title", title)
                .value("privacyStatus", privacy)
                .array("videoIds").value(videoId).end()
        }, NewPipe.getPreferredLocalization())
        return response.getString("playlistId") != null
    }

    // ---- UI ----

    private fun background(context: Context, work: () -> Boolean, okText: String) {
        executor.execute {
            val ok = try {
                work()
            } catch (e: Exception) {
                false
            }
            main.post {
                Toast.makeText(
                    context,
                    if (ok) okText else context.getString(R.string.pipevr_playlist_failed),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    @JvmStatic
    fun addToWatchLater(context: Context, videoUrl: String) {
        val id = PipeVrPlayback.videoIdOf(videoUrl) ?: return
        background(context, { setContains(WATCH_LATER, id, true) },
            context.getString(R.string.pipevr_added_to, context.getString(R.string.pipevr_watch_later)))
    }

    /**
     * Shows the account's playlists with checkboxes; checking/unchecking adds/removes the video
     * right away. "Local playlists" opens PipePipe's on-device playlists instead.
     */
    @JvmStatic
    fun showDialog(context: Context, videoUrl: String, openLocalPlaylists: Runnable) {
        val videoId = PipeVrPlayback.videoIdOf(videoUrl)
        if (videoId == null) {
            openLocalPlaylists.run()
            return
        }
        val loading = Toast.makeText(context, R.string.pipevr_loading_playlists, Toast.LENGTH_SHORT)
        loading.show()
        executor.execute {
            val playlists = try {
                list(videoId)
            } catch (e: Exception) {
                null
            }
            main.post {
                loading.cancel()
                if (playlists == null) {
                    Toast.makeText(context, R.string.pipevr_playlist_failed, Toast.LENGTH_SHORT)
                        .show()
                    openLocalPlaylists.run()
                    return@post
                }
                val labels = playlists.map {
                    if (it.privacy == "PRIVATE" && it.id != WATCH_LATER) {
                        it.title + "  🔒"
                    } else {
                        it.title
                    }
                }.toTypedArray()
                val checked = playlists.map { it.containsVideo }.toBooleanArray()
                AlertDialog.Builder(context)
                    .setTitle(R.string.pipevr_save_to)
                    .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                        val playlist = playlists[which]
                        background(
                            context,
                            { setContains(playlist.id, videoId, isChecked) },
                            context.getString(
                                if (isChecked) R.string.pipevr_added_to
                                else R.string.pipevr_removed_from,
                                playlist.title
                            )
                        )
                    }
                    .setPositiveButton(R.string.done, null)
                    .setNeutralButton(R.string.pipevr_new_playlist) { _, _ ->
                        showCreateDialog(context, videoId)
                    }
                    .setNegativeButton(R.string.pipevr_local_playlists) { _, _ ->
                        openLocalPlaylists.run()
                    }
                    .show()
            }
        }
    }

    private fun showCreateDialog(context: Context, videoId: String) {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setHint(R.string.pipevr_playlist_name)
        }
        val privacyLabels = arrayOf(
            context.getString(R.string.pipevr_privacy_private),
            context.getString(R.string.pipevr_privacy_unlisted),
            context.getString(R.string.pipevr_privacy_public),
        )
        val privacyValues = arrayOf("PRIVATE", "UNLISTED", "PUBLIC")
        var privacy = 0
        AlertDialog.Builder(context)
            .setTitle(R.string.pipevr_new_playlist)
            .setView(input)
            .setSingleChoiceItems(privacyLabels, 0) { _, which -> privacy = which }
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.create) { _, _ ->
                val title = input.text.toString().trim()
                if (title.isNotEmpty()) {
                    background(context, { create(title, privacyValues[privacy], videoId) },
                        context.getString(R.string.pipevr_added_to, title))
                }
            }
            .show()
    }
}
