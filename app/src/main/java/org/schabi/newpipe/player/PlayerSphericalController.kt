package org.schabi.newpipe.player

import android.graphics.Color
import android.view.Surface
import android.view.View
import android.widget.Toast
import com.google.android.exoplayer2.video.spherical.SphericalGLSurfaceView
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.util.Locale

/**
 * PipeVR: plays 360°/VR videos through ExoPlayer's [SphericalGLSurfaceView] (drag to look
 * around) instead of the flat surface view.
 *
 * The mode is switched on automatically for videos whose title or tags mark them as 360°/VR,
 * once per video, and can always be toggled with the "360°" button in the player top bar.
 */
class PlayerSphericalController(private val player: Player) {

    var isSphericalMode = false
        private set

    private var autoDetectedStreamUrl: String? = null
    private var surfaceListener: SphericalGLSurfaceView.VideoSurfaceListener? = null

    private val isVrHeadset: Boolean
        get() = org.schabi.newpipe.util.DeviceUtils.isVrHeadset(player.context)

    /** PipeVR: watch the current video in full VR (head tracking), on VR headsets. */
    fun enterImmersive() {
        val info = player.currentStreamInfo.orElse(null)
        org.schabi.newpipe.pipevr.ImmersiveVideoActivity.chooseAndStart(
            player.context, player.parentActivity, info?.name,
            info != null && looksLikeSphericalVideo(info)
        )
    }

    private fun toggleFlat360() {
        setSphericalMode(!isSphericalMode)
        Toast.makeText(
            player.context,
            if (isSphericalMode) R.string.pipevr_360_on else R.string.pipevr_360_off,
            Toast.LENGTH_SHORT
        ).show()
    }

    /** Wires the 360° toggle and the tap-to-show-controls behaviour of the spherical view. */
    fun setupListeners() {
        val binding = player.binding
        // on a VR headset: tap = full VR (360° around you, or the theater screen for normal
        // and 3D videos), long press = 360° inside the window
        if (isVrHeadset) {
            binding.sphericalModeButton.text = "VR"
        }
        binding.sphericalModeButton.setOnClickListener {
            if (isVrHeadset) enterImmersive() else toggleFlat360()
        }
        binding.sphericalModeButton.setOnLongClickListener {
            toggleFlat360()
            true
        }
        // the spherical view consumes touches (drag to look around) and reports taps as clicks
        binding.sphericalView.setOnClickListener {
            if (player.isControlsVisible) {
                player.hideControls(150, 0)
            } else {
                player.showControlsThenHide()
            }
        }
    }

    /** Called for each new video, before the stream related views are updated. */
    fun onMetadataChanged(info: StreamInfo) {
        if (info.url != autoDetectedStreamUrl) {
            autoDetectedStreamUrl = info.url
            setSphericalMode(looksLikeSphericalVideo(info))
        }
    }

    /** Called after the layout controller decided whether the video surface is shown. */
    fun onStreamViewsUpdated() {
        val binding = player.binding
        val hasVideo = binding.surfaceView.visibility == View.VISIBLE ||
            binding.sphericalView.visibility == View.VISIBLE
        binding.sphericalModeButton.visibility = if (hasVideo) View.VISIBLE else View.GONE
        if (hasVideo) {
            updateViewsVisibility()
        }
    }

    fun setSphericalMode(enabled: Boolean) {
        if (isSphericalMode == enabled) {
            return
        }
        isSphericalMode = enabled
        val binding = player.binding ?: return
        binding.sphericalModeButton.setTextColor(if (enabled) ACCENT_COLOR else Color.WHITE)
        updateViewsVisibility()
        if (!player.exoPlayerIsNull()) {
            // re-attach ExoPlayer to the right surface
            player.surfaceController.setupVideoSurface()
        }
    }

    /** Attaches ExoPlayer to the spherical view. Returns false when not in 360° mode. */
    fun setupVideoSurface(): Boolean {
        if (!isSphericalMode) {
            return false
        }
        val exoPlayer = player.simpleExoPlayer ?: return true
        val view = player.binding.sphericalView
        exoPlayer.setVideoFrameMetadataListener(view.videoFrameMetadataListener)
        exoPlayer.setCameraMotionListener(view.cameraMotionListener)
        val listener = object : SphericalGLSurfaceView.VideoSurfaceListener {
            override fun onVideoSurfaceCreated(surface: Surface) {
                // VR mode owns the video output: don't take it back (black VR screen)
                if (player.surfaceController.hasExternalSurface) return
                player.simpleExoPlayer?.setVideoSurface(surface)
            }

            override fun onVideoSurfaceDestroyed(surface: Surface) {
                player.simpleExoPlayer?.clearVideoSurface(surface)
            }
        }
        surfaceListener = listener
        view.addVideoSurfaceListener(listener)
        view.videoSurface?.let { exoPlayer.setVideoSurface(it) }
        return true
    }

    fun cleanupVideoSurface() {
        val listener = surfaceListener ?: return
        surfaceListener = null
        val view = player.binding?.sphericalView ?: return
        view.removeVideoSurfaceListener(listener)
        player.simpleExoPlayer?.let {
            it.clearVideoFrameMetadataListener(view.videoFrameMetadataListener)
            it.clearCameraMotionListener(view.cameraMotionListener)
            view.videoSurface?.let { surface -> it.clearVideoSurface(surface) }
        }
    }

    fun destroy() {
        player.binding?.sphericalView?.onPause()
    }

    private fun updateViewsVisibility() {
        val binding = player.binding ?: return
        val videoShown = binding.surfaceView.visibility == View.VISIBLE ||
            binding.sphericalView.visibility == View.VISIBLE
        if (!videoShown) {
            return
        }
        binding.surfaceView.visibility = if (isSphericalMode) View.GONE else View.VISIBLE
        if (isSphericalMode && binding.sphericalView.visibility != View.VISIBLE) {
            binding.sphericalView.visibility = View.VISIBLE
            binding.sphericalView.onResume()
        } else if (!isSphericalMode && binding.sphericalView.visibility == View.VISIBLE) {
            binding.sphericalView.onPause()
            binding.sphericalView.visibility = View.GONE
        }
    }

    companion object {
        private const val ACCENT_COLOR = 0xFF4FC3F7.toInt()

        private val TITLE_PATTERN = Regex(
            "(?i)(\\b360\\s*(°|º|deg|degree|video|vr)|\\bvr\\s*360\\b|\\bvr\\s*180\\b" +
                "|\\b180\\s*vr\\b|#360video|\\b360\\b.*\\b(vr|virtual reality)\\b)"
        )

        private val SPHERICAL_TAGS = setOf(
            "360", "360 video", "360°", "360 vr", "vr 360", "vr180", "vr 180",
            "360 degree video", "360 degree", "360video"
        )

        fun looksLikeSphericalVideo(info: StreamInfo): Boolean {
            if (TITLE_PATTERN.containsMatchIn(info.name ?: "")) {
                return true
            }
            return info.tags.orEmpty().any {
                it.lowercase(Locale.ROOT).trim() in SPHERICAL_TAGS
            }
        }
    }
}
