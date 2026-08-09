package com.example.macrowidget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws the Energy page: measured TDEE (hero), the measured loss rate colored against the
 * 0.7–0.9 lb/wk band (green in-zone, amber cutting-hard, red under-cutting), a target-band
 * gauge with the current marker, and the intake adjustment to settle into the band. Falls back
 * to a "measuring" state until the TDEE window has enough data. Footer = page dots + refresh.
 */
object EnergyRenderer {

    private const val WHITE = Color.WHITE
    private val MUTED = Color.parseColor("#9AA0A6")
    private val FAINT = Color.parseColor("#7C8288")
    private val LINE = Color.parseColor("#ECEFF3")
    private val GREEN = Color.parseColor("#15CF92")
    private val GREEN_BRIGHT = Color.parseColor("#1FE3A6")
    private val AMBER = Color.parseColor("#FFB300")
    private val RED = Color.parseColor("#FF5A5A")
    private val AXIS = Color.parseColor("#2B2B2B")

    fun render(
        tdee: TdeeResult,
        band: WeightTarget?,
        page: Int,
        pageCount: Int,
        widthPx: Int,
        heightPx: Int
    ): Bitmap {
        val w = max(widthPx, 320)
        val h = max(heightPx, 320)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        val pad = w * 0.045f
        val padV = pad * 0.55f
        val left = pad
        val right = w - pad
        val cx = (left + right) / 2f
        val avail = h - padV * 2f

        val footerH = avail * 0.09f
        val footerTop = h - padV - footerH

        // Sizes fill the tile: the old ceilings (title 36 / sub 22) left the whole page a small
        // cluster of text under a lone hero number on a large widget. Scale up and cap higher.
        val titleSize = clamp(avail * 0.075f, 22f, 58f)
        val subSize = clamp(avail * 0.046f, 15f, 34f)

        // ===== header =====
        c.drawText("Burn rate", cx, padV + titleSize,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = WHITE; textSize = titleSize; isFakeBoldText = true; textAlign = Paint.Align.CENTER
            })

        // ===== collecting state =====
        if (tdee.collecting || tdee.tdee == null) {
            c.drawText("Measuring your burn", cx, h * 0.44f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MUTED; textSize = subSize * 1.2f; textAlign = Paint.Align.CENTER })
            c.drawText("${tdee.daysNeeded} more day${if (tdee.daysNeeded == 1) "" else "s"} of data",
                cx, h * 0.44f + subSize * 1.8f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FAINT; textSize = subSize; textAlign = Paint.Align.CENTER })
            WidgetChrome.drawFooter(c, left, right, footerTop, footerH, page, pageCount)
            return bmp
        }

        val tdeeVal = tdee.tdee.roundToInt()
        val rate = tdee.lbPerWeek

        // Lay the body out across the whole region between the title and the footer, anchoring
        // each element to a fraction of that height so it fills the tile instead of clustering
        // at the top (the clamped text sizes otherwise leave the lower half empty on big tiles).
        val regionTop = padV + titleSize
        val regionH = footerTop - regionTop

        // ===== hero: TDEE =====
        val heroSize = clamp(avail * 0.175f, 40f, 132f)
        val heroY = regionTop + regionH * 0.20f
        c.drawText(String.format("%,d", tdeeVal), cx, heroY,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WHITE; textSize = heroSize; isFakeBoldText = true; textAlign = Paint.Align.CENTER })
        c.drawText("kcal/day · ${tdee.weighIns} weigh-in${if (tdee.weighIns == 1) "" else "s"}", cx, heroY + subSize * 1.5f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MUTED; textSize = subSize; textAlign = Paint.Align.CENTER })

        // ===== secondary stat: measured avg intake (fills the mid-tile gap) =====
        tdee.avgIntake?.let {
            val ip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FAINT; textSize = subSize * 0.95f; textAlign = Paint.Align.CENTER }
            val txt = "Avg intake ${String.format("%,d", it.roundToInt())} kcal/day"
            fitToWidth(ip, txt, right - left)
            c.drawText(txt, cx, regionTop + regionH * 0.41f, ip)
        }

        // ===== measured rate, colored vs band =====
        val col = colorForRate(rate, band)
        val rateY = regionTop + regionH * 0.57f
        if (rate != null) {
            c.drawText("${fmt1(rate)} lb/wk", cx, rateY,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col; textSize = subSize * 1.5f; isFakeBoldText = true; textAlign = Paint.Align.CENTER })
        }

        // ===== target-band gauge =====
        if (band != null && rate != null) {
            val lo = band.lowerRate
            val hi = band.upperRate
            val rmin = max(0f, lo - 0.4f)
            // Fixed headroom to ~1.8 lb/wk so a fast week (e.g. 1.3) sits well inside the track
            // instead of pinned to the right edge; still expands if the band's upper is higher.
            val rmax = max(hi + 0.4f, 1.8f)
            val gx0 = left + subSize * 1.5f
            val gx1 = right - subSize * 1.5f
            val gy = regionTop + regionH * 0.75f
            fun gx(r: Float) = gx0 + (clamp(r, rmin, rmax) - rmin) / (rmax - rmin) * (gx1 - gx0)

            // track
            c.drawRoundRect(gx0, gy - subSize * 0.22f, gx1, gy + subSize * 0.22f, 6f, 6f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AXIS })
            // green zone
            c.drawRoundRect(gx(lo), gy - subSize * 0.34f, gx(hi), gy + subSize * 0.34f, 4f, 4f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GREEN; alpha = 150 })
            // zone edge labels
            val zp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GREEN_BRIGHT; textSize = subSize * 0.78f; textAlign = Paint.Align.CENTER }
            c.drawText(fmt1(lo), gx(lo), gy + subSize * 1.25f, zp)
            c.drawText(fmt1(hi), gx(hi), gy + subSize * 1.25f, zp)
            // current marker
            c.drawCircle(gx(rate), gy, clamp(w * 0.013f, 5f, 11f),
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col })

            // ===== action line =====
            val mid = (lo + hi) / 2f
            val req = tdee.intakeForRate(mid)
            val avg = tdee.avgIntake
            val actY = regionTop + regionH * 0.93f
            val ap = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = subSize * 0.95f; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
            val roundedRate = round1(rate)
            val actText = if (roundedRate in lo..hi || req == null || avg == null) {
                ap.color = GREEN; "On target · hold here"
            } else {
                val delta = (req - avg).roundToInt()
                ap.color = if (delta >= 0) GREEN else AMBER
                "Eat ${signedInt(delta)} kcal/day → ${fmt1(mid)} lb/wk"
            }
            fitToWidth(ap, actText, right - left)
            c.drawText(actText, cx, actY, ap)
        }

        WidgetChrome.drawFooter(c, left, right, footerTop, footerH, page, pageCount)
        return bmp
    }

    /** Green in-zone, amber over-cut (rate > upper), red under-cut (rate < lower), neutral if no band. */
    private fun colorForRate(rate: Float?, band: WeightTarget?): Int {
        if (rate == null || band == null) return LINE
        val r = round1(rate)
        return when {
            r > band.upperRate -> AMBER
            r < band.lowerRate -> RED
            else -> GREEN
        }
    }

    private fun fitToWidth(p: Paint, text: String, maxW: Float, min: Float = 10f) {
        while (p.textSize > min && p.measureText(text) > maxW) p.textSize = p.textSize - 1f
    }

    private fun round1(v: Float): Float = (v * 10f).roundToInt() / 10f
    private fun fmt1(v: Float): String = String.format("%.1f", v)
    private fun signedInt(v: Int): String = (if (v >= 0) "+" else "−") + abs(v)
    private fun clamp(v: Float, lo: Float, hi: Float) = max(lo, min(hi, v))
}
