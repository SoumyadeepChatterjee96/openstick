package org.openstick.ui

import android.app.ActivityOptions
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import kotlin.math.min

object WindowPlacement {
    private const val MIN_W = 480
    private const val MIN_H = 360
    private const val MARGIN = 32
    private var cascadeIndex = 0

    fun calculateDefaultBounds(context: Context): Rect {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (wm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = wm.maximumWindowMetrics
            val screenBounds = metrics.bounds
            val screenW = screenBounds.width()
            val screenH = screenBounds.height()

            // Generous pop-up window: ~88% width, ~72% height
            val targetW = (screenW * 0.88f).toInt().coerceIn(MIN_W, screenW)
            val targetH = (screenH * 0.72f).toInt().coerceIn(MIN_H, screenH)

            val cascadeStep = 40
            val offset = (cascadeIndex++ % 5) * cascadeStep

            val left = ((screenW - targetW) / 2 + offset).coerceIn(MARGIN, maxOf(MARGIN, screenW - targetW - MARGIN))
            val top = ((screenH - targetH) / 2 + offset).coerceIn(MARGIN * 2, maxOf(MARGIN * 2, screenH - targetH - MARGIN))
            val right = min(screenW - MARGIN, left + targetW)
            val bottom = min(screenH - MARGIN, top + targetH)

            return Rect(left, top, right, bottom)
        }
        // Fallback bounds
        return Rect(60, 120, 960, 1600)
    }

    fun makeOptionsBundle(context: Context, bounds: Rect = calculateDefaultBounds(context)): Bundle {
        return ActivityOptions.makeBasic().setLaunchBounds(bounds).toBundle()
    }
}
