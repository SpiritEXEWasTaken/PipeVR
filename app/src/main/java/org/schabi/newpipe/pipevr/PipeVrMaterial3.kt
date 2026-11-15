package org.schabi.newpipe.pipevr

import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import org.schabi.newpipe.R
import org.schabi.newpipe.util.ThemeHelper

/** PipeVR: Material 3 touches that a theme alone can't give (only while Material 3 is on). */
object PipeVrMaterial3 {
    private const val TAG_KEY = R.id.itemThumbnailView

    /** Rounded corners on a list or grid item's thumbnail (12 dp, like Material 3 cards). */
    @JvmStatic
    fun roundThumbnail(itemView: View) {
        val thumb = itemView.findViewById<View?>(R.id.itemThumbnailView) ?: return
        if (thumb.getTag(TAG_KEY) == true) return
        thumb.setTag(TAG_KEY, true)
        if (!ThemeHelper.isMaterial3(itemView.context)) return
        val radius = 12 * itemView.resources.displayMetrics.density
        thumb.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        thumb.clipToOutline = true
    }
}
