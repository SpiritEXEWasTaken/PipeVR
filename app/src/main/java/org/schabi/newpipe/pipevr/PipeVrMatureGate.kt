package org.schabi.newpipe.pipevr

import androidx.appcompat.app.AlertDialog
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.player.Player
import java.util.Collections

/**
 * PipeVR: Twitch/Kick streams marked 18+ (mature) are paused with a warning until the user
 * confirms (once per stream per session).
 */
object PipeVrMatureGate {
    private val confirmed: MutableSet<String> = Collections.synchronizedSet(HashSet())

    @JvmStatic
    fun check(player: Player, info: StreamInfo) {
        val gated = info.serviceId == ServiceList.Twitch.serviceId ||
            info.serviceId == ServiceList.Kick.serviceId
        if (!gated || info.ageLimit < 18 || info.url in confirmed) return
        val activity = player.parentActivity ?: return
        player.pause()
        AlertDialog.Builder(activity)
            .setTitle(R.string.pipevr_mature_title)
            .setMessage(R.string.pipevr_mature_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.pipevr_mature_continue) { _, _ ->
                confirmed += info.url
                player.play()
            }
            .setCancelable(false)
            .show()
    }
}
