package org.schabi.newpipe.pipevr

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.AbsListView
import android.widget.ScrollView
import androidx.core.widget.NestedScrollView
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView

/**
 * PipeVR: makes the 2D app nicer with VR controllers.
 * - Thumbstick (joystick axes) scrolls whatever list is on screen, faster the further you push.
 * - Optional "bigger UI" setting: everything is drawn ~20% larger, so buttons are easier to
 *   hit with a laser pointer.
 */
object PipeVrControllerSupport {
    const val BIG_UI_KEY = "pipevr_big_ui"
    private const val BIG_UI_SCALE = 1.2f

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (PreferenceManager.getDefaultSharedPreferences(activity)
                        .getBoolean(BIG_UI_KEY, false)
                ) {
                    enlarge(activity)
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                val window = activity.window ?: return
                val inner = window.callback ?: return
                if (inner is StickCallback) return
                window.callback = StickCallback(inner, window)
            }

            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    @Suppress("DEPRECATION")
    private fun enlarge(activity: Activity) {
        val metrics = activity.resources.displayMetrics
        val base = activity.applicationContext.resources.displayMetrics
        if (metrics.density > base.density * 1.05f) return
        metrics.density = base.density * BIG_UI_SCALE
        metrics.scaledDensity = base.scaledDensity * BIG_UI_SCALE
        metrics.densityDpi = (base.densityDpi * BIG_UI_SCALE).toInt()
    }

    /** Window callback that turns thumbstick motion into scrolling. */
    private class StickCallback(private val inner: Window.Callback, private val window: Window) :
        Window.Callback by inner {
        private var target: View? = null
        private var keyboardSeenAt = 0L

        /**
         * The on-screen keyboard is open (or just closed): the stick is being used to type,
         * not to scroll (it scrolled search results to the bottom while typing).
         */
        private fun typing(): Boolean {
            val now = android.os.SystemClock.uptimeMillis()
            val insets = window.decorView.rootWindowInsets
            val ime = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
                insets != null && insets.isVisible(android.view.WindowInsets.Type.ime())
            if (ime) keyboardSeenAt = now
            return now - keyboardSeenAt < 1500
        }

        override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
            if (event.isFromSource(InputDevice.SOURCE_JOYSTICK) &&
                event.actionMasked == MotionEvent.ACTION_MOVE
            ) {
                // let the app handle it first (e.g. the player's own joystick seeking)
                if (inner.dispatchGenericMotionEvent(event)) return true
                val y = axis(event, MotionEvent.AXIS_Y, MotionEvent.AXIS_RZ)
                if (kotlin.math.abs(y) > 0.25f && !typing()) {
                    val view = findScrollable() ?: return false
                    val px = (y * y * 90f * view.resources.displayMetrics.density).toInt() *
                        (if (y > 0) 1 else -1)
                    scroll(view, px)
                    return true
                }
                return false
            }
            return inner.dispatchGenericMotionEvent(event)
        }

        private fun axis(e: MotionEvent, a: Int, b: Int): Float {
            val va = e.getAxisValue(a)
            val vb = e.getAxisValue(b)
            return if (kotlin.math.abs(va) >= kotlin.math.abs(vb)) va else vb
        }

        private fun scroll(view: View, px: Int) {
            when (view) {
                is RecyclerView -> view.scrollBy(0, px)
                is AbsListView -> view.scrollListBy(px)
                else -> view.scrollBy(0, px)
            }
        }

        /** The focused scrollable, else the biggest visible scrollable view in the window. */
        private fun findScrollable(): View? {
            target?.let { if (it.isShown && it.isAttachedToWindow) return it }
            var best: View? = null
            var bestArea = 0
            fun walk(v: View) {
                if (!v.isShown) return
                if (v is RecyclerView || v is AbsListView || v is ScrollView ||
                    v is NestedScrollView
                ) {
                    if (v.canScrollVertically(1) || v.canScrollVertically(-1)) {
                        val area = v.width * v.height
                        if (area > bestArea) {
                            best = v; bestArea = area
                        }
                    }
                }
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(window.decorView)
            target = null // re-pick next time, the screen may change
            return best
        }
    }
}
