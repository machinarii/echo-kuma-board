package dev.kumaboard

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

/**
 * F15: hide the status bar (top) and the navigation bar (Back/Home/Recents) on every screen.
 *
 * Uses both the Android 11 WindowInsetsController and the legacy immersive-sticky flags,
 * because some custom ROMs (LineageOS ports included) honour one more reliably than the
 * other. If a bar is revealed (edge swipe, a dialog, the keyboard closing), it is hidden again
 * after a short delay. The bars can still be pulled in by swiping from an edge, so the device
 * stays recoverable.
 */
object Immersive {
    private const val REHIDE_MS = 2_500L

    fun install(activity: Activity) {
        val window = activity.window
        window.setDecorFitsSystemWindows(false)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        val decor = window.decorView
        val handler = Handler(Looper.getMainLooper())
        val rehide = Runnable { hide(activity) }
        decor.setOnApplyWindowInsetsListener { v, insets ->
            val barsVisible = insets.isVisible(WindowInsets.Type.statusBars()) ||
                insets.isVisible(WindowInsets.Type.navigationBars())
            if (barsVisible && activity.hasWindowFocus()) {
                handler.removeCallbacks(rehide)
                handler.postDelayed(rehide, REHIDE_MS)
            }
            v.onApplyWindowInsets(insets)
        }
        hide(activity)
    }

    @Suppress("DEPRECATION")
    fun hide(activity: Activity) {
        val window = activity.window
        window.insetsController?.let {
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        }
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
    }
}
