package com.example.macrowidget

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.max
import kotlin.math.min

/** Shared bottom chrome drawn on every page: page dots (center) + refresh button (right).
 *  The refresh glyph is painted here; a transparent click-target overlays this corner in
 *  the layout so tapping it fires the refresh intent (tapping the body cycles pages). */
object WidgetChrome {

    private val DIM = Color.parseColor("#5F6468")
    private val BRIGHT = Color.parseColor("#ECEFF3")
    private val REFRESH_BG = Color.parseColor("#20242B")
    private val REFRESH_STROKE = Color.parseColor("#3C6FA5")
    private val REFRESH_FG = Color.parseColor("#AAB6C2")

    fun drawFooter(c: Canvas, left: Float, right: Float, top: Float, h: Float, page: Int, pageCount: Int) {
        val cy = top + h / 2f

        // page dots, centered
        if (pageCount > 1) {
            val dotR = clamp(h * 0.11f, 3f, 7f)
            val gap = dotR * 3.4f
            val cx0 = (left + right) / 2f - gap * (pageCount - 1) / 2f
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            for (i in 0 until pageCount) {
                p.color = if (i == page) BRIGHT else DIM
                c.drawCircle(cx0 + gap * i, cy, dotR, p)
            }
        }

        // refresh button, bottom-right
        val r = clamp(h * 0.44f, 13f, 28f)
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
