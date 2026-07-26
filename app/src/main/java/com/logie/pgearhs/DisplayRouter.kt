package com.logie.pgearhs

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display

/**
 * The AYN Thor exposes two physical displays: a 16:9 top display and a
 * nearly-square (31:27) bottom display. Android has no built-in notion of
 * "top" vs "bottom" screen, so we tell them apart by aspect ratio and route
 * the launch there ourselves, since the stock launcher always starts new
 * activities on the default (top) display.
 */
object DisplayRouter {

    private const val PREFS_NAME = "pgearhs_settings"
    private const val KEY_PREFER_TOP_DISPLAY = "prefer_top_display"
    private const val EXTRA_ROUTED = "com.logie.pgearhs.EXTRA_ROUTED"

    // Bottom display is ~31:27 (~1.148 long/short ratio); top is 16:9 (~1.778).
    // Anything more square than this splits the two.
    private const val ASPECT_RATIO_SPLIT = 1.45

    private data class ClassifiedDisplays(val top: Display?, val bottom: Display?)

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isTopDisplayPreferred(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PREFER_TOP_DISPLAY, false)

    fun setTopDisplayPreferred(context: Context, preferTop: Boolean) {
        prefs(context).edit().putBoolean(KEY_PREFER_TOP_DISPLAY, preferTop).apply()
    }

    private fun aspectRatioOf(context: Context, display: Display): Double {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        val long = maxOf(metrics.widthPixels, metrics.heightPixels).toDouble()
        val short = minOf(metrics.widthPixels, metrics.heightPixels).toDouble()
        return if (short == 0.0) Double.MAX_VALUE else long / short
    }

    private fun classifyDisplays(context: Context): ClassifiedDisplays {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val displays = displayManager.displays
        if (displays.size < 2) {
            return ClassifiedDisplays(top = displays.firstOrNull(), bottom = null)
        }

        // Pick the most square display as "bottom" and the most widescreen as "top".
        val bySquareness = displays.sortedBy { aspectRatioOf(context, it) }
        val mostSquare = bySquareness.first()
        val mostWide = bySquareness.last()

        return if (aspectRatioOf(context, mostSquare) < ASPECT_RATIO_SPLIT) {
            ClassifiedDisplays(top = mostWide, bottom = mostSquare)
        } else {
            // Both displays look widescreen (e.g. running in an emulator) -
            // nothing to distinguish, so don't try to route.
            ClassifiedDisplays(top = displays.first(), bottom = null)
        }
    }

    private fun currentDisplayId(activity: Activity): Int {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            activity.display?.displayId ?: Display.DEFAULT_DISPLAY
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay.displayId
        }
    }

    /**
     * If the activity isn't already on the screen the user prefers, relaunch
     * it there and return true (caller should finish() the current instance).
     */
    fun routeIfNeeded(activity: Activity): Boolean {
        if (activity.intent?.getBooleanExtra(EXTRA_ROUTED, false) == true) {
            return false
        }

        val classified = classifyDisplays(activity)
        val target = if (isTopDisplayPreferred(activity)) classified.top else classified.bottom
        val destination = target ?: return false

        if (currentDisplayId(activity) == destination.displayId) {
            return false
        }

        return try {
            val intent = Intent(activity, activity.javaClass).apply {
                putExtra(EXTRA_ROUTED, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val options = ActivityOptions.makeBasic()
            options.launchDisplayId = destination.displayId
            activity.startActivity(intent, options.toBundle())
            true
        } catch (_: SecurityException) {
            // Device doesn't allow cross-display launch from this context; stay put.
            false
        }
    }
}
