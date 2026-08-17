package com.example.macrowidget

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.max
import kotlin.math.min

/** Shared bottom chrome drawn on every page: the refresh button (bottom-right). Page dots were
 *  removed — navigation is the left/right tap halves. The refresh glyph is painted here; a
 *  transparent click-target overlays this corner in the layout so tapping it fires refresh. */
object WidgetChrome {

    private val REFRESH_BG = Color.parseColor("#20242B")
    private val REFRESH_STROKE = Color.parseColor("#3C6FA5")
    private val REFRESH_FG = Color.parseColor("#AAB6C2")

    // page/pageCount are retained for call-site compatibility; the page-dot indicator was
    // removed (navigation is now the left/right tap halves), so only the refresh button draws.
    fun drawFooter(c: Canvas, left: Float, right: Float, top: Float, h: Float, page: Int, pageCount: Int) {
        val cy = top + h / 2f

        // refresh button, bottom-right
        val r = clamp(h * 0.44f, PageMetrics.REFRESH_MIN_RADIUS, PageMetrics.REFRESH_MAX_RADIUS)
        val rcx = right - r - 2f
        c.drawCircle(rcx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = REFRESH_BG })
        c.drawCircle(rcx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = max(1.2f, r * 0.07f); color = REFRESH_STROKE
        })
        c.drawText("↻", rcx, cy + r * 0.40f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = REFRESH_FG; textAlign = Paint.Align.CENTER; textSize = r * 1.2f
        })
    }

    private fun clamp(v: Float, lo: Float, hi: Float) = max(lo, min(hi, v))
}
