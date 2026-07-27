package com.example.macrowidget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the weight page: a weekly-average weight trend (left axis), the current week's
 * daily weigh-ins as a cluster settling into a pending point, and a single green target
 * band anchored at the current week's target (last week's avg − 0.7 to − 0.9), edges
 * labeled 0.7 / 0.9. Each completed week's dot is labeled with its average and colored by
 * how its loss rate landed: green = in-zone, yellow = over-cut, red = under-cut. Footer =
 * page dots + refresh.
 */
object WeightRenderer {

    private const val WHITE = Color.WHITE
    private val MUTED = Color.parseColor("#9AA0A6")
    private val COOL = Color.parseColor("#AAB6C2")
    private val FAINT = Color.parseColor("#7C8288")
    private val LINE = Color.parseColor("#ECEFF3")
    private val GREEN = Color.parseColor("#15CF92")
    private val GREEN_BRIGHT = Color.parseColor("#1FE3A6")
    private val AMBER = Color.parseColor("#FFB300")   // over-cut (rate > upper): losing faster than target
    private val RED = Color.parseColor("#FF5A5A")      // under-cut (rate < lower): losing slower than target
    private val AXIS = Color.parseColor("#2B2B2B")
    private val CARD = Color.parseColor("#181818")   // for "hollow" pending point

    fun render(
        series: WeightSeries,
        target: WeightTarget?,
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
        val avail = h - padV * 2f

        val footerH = avail * 0.09f
        val footerTop = h - padV - footerH

        val titleSize = clamp(avail * 0.055f, 18f, 40f)
        val subSize = clamp(avail * 0.032f, 12f, 24f)

        // ===== header =====
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = WHITE; textSize = titleSize; isFakeBoldText = true; textAlign = Paint.Align.CENTER
        }
        c.drawText("Weight", (left + right) / 2f, padV + titleSize, title)

        if (!series.hasData) {
            c.drawText("No weight logged yet", (left + right) / 2f, h * 0.45f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MUTED; textSize = subSize * 1.2f; textAlign = Paint.Align.CENTER })
            WidgetChrome.drawFooter(c, left, right, footerTop, footerH, page, pageCount)
            return bmp
        }

        // subline: "184.3 lb · this wk −0.7 · −5.7 total"
        val sub = StringBuilder()
        series.latest?.let { sub.append(fmt1(it)).append(" lb") }
        series.thisWeekRate?.let { sub.append("  ·  this wk ").append(signed(-it)) }  // rate>0 = loss = shows "−"
        series.totalDelta?.let { sub.append("  ·  ").append(signed(it)).append(" total") }
        c.drawText(sub.toString(), (left + right) / 2f, padV + titleSize + subSize * 1.5f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MUTED; textSize = subSize; textAlign = Paint.Align.CENTER })

        // ===== plot geometry =====
        val plotT = padV + titleSize + subSize * 2.6f
        val plotB = footerTop - subSize * 1.4f          // room for x labels
        val axisL = left + subSize * 2.6f               // room for weight labels
        val plotR = right - subSize * 2.2f              // room for 0.7/0.9 labels

        // weight range across everything we plot
        val vals = ArrayList<Float>()
        series.weeks.forEach { vals.add(it.avg) }
        vals.addAll(series.currentDailies)
        series.targetLow?.let { vals.add(it) }
        series.targetHigh?.let { vals.add(it) }
        var minW = vals.minOrNull() ?: 0f
        var maxW = vals.maxOrNull() ?: 0f
        val span = max(maxW - minW, 1f)
        minW -= span * 0.18f
        maxW += span * 0.18f
        fun y(v: Float) = plotB - (v - minW) / (maxW - minW) * (plotB - plotT)

        val n = series.weeks.size
        fun x(i: Int) = axisL + (i + 0.5f) / n * (plotR - axisL)
        val slotW = (plotR - axisL) / n

        // ===== left axis + weight labels =====
        c.drawLine(axisL, plotT, axisL, plotB, Paint().apply { color = AXIS; strokeWidth = 1.5f })
        c.drawLine(axisL, plotB, plotR, plotB, Paint().apply { color = AXIS; strokeWidth = 1.5f })
        val axP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MUTED; textSize = subSize * 0.85f; textAlign = Paint.Align.RIGHT }
        for (i in 0..3) {
            val wv = minW + i / 3f * (maxW - minW)
            c.drawText(fmt1(wv), axisL - subSize * 0.35f, y(wv) + subSize * 0.3f, axP)
        }

        // ===== current-week target band (anchored where the dailies land) =====
        if (series.targetLow != null && series.targetHigh != null) {
            val bandTop = y(series.targetHigh)
            val bandBot = y(series.targetLow)
            val bandL = x(n - 1) - slotW * 0.45f
            c.drawRect(bandL, bandTop, plotR, bandBot,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GREEN; alpha = 140 })
            val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = GREEN_BRIGHT; style = Paint.Style.STROKE; strokeWidth = 1.2f
                pathEffect = android.graphics.DashPathEffect(floatArrayOf(4f, 3f), 0f)
            }
            c.drawLine(bandL, bandTop, plotR, bandTop, dash)
            c.drawLine(bandL, bandBot, plotR, bandBot, dash)
            val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GREEN_BRIGHT; textSize = subSize * 0.8f }
            c.drawText("0.7", plotR + 3f, bandTop + subSize * 0.28f, lp)
            c.drawText("0.9", plotR + 3f, bandBot + subSize * 0.28f, lp)
        }

        // ===== trend line (solid through completed, dashed to the pending current point) =====
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = LINE; style = Paint.Style.STROKE; strokeWidth = max(2f, w * 0.006f); strokeJoin = Paint.Join.ROUND
        }
        val solid = Path()
        var started = false
        var lastCompleteIdx = -1
        series.weeks.forEachIndexed { i, wk ->
            if (wk.complete) {
                if (!started) { solid.moveTo(x(i), y(wk.avg)); started = true } else solid.lineTo(x(i), y(wk.avg))
                lastCompleteIdx = i
            }
        }
        if (started) c.drawPath(solid, linePaint)
        // dashed link to the in-progress point
        val cur = series.weeks.last()
        if (!cur.complete && lastCompleteIdx >= 0) {
            c.drawLine(x(lastCompleteIdx), y(series.weeks[lastCompleteIdx].avg), x(n - 1), y(cur.avg),
                Paint(linePaint).apply {
                    pathEffect = android.graphics.DashPathEffect(floatArrayOf(5f, 4f), 0f); alpha = 180
                })
        }

        // ===== weekly points + value labels =====
        // Dot color by how the week's loss rate lands vs the target band:
        //   in-zone → green, over-cut (rate > upper) → yellow, under-cut (rate < lower) → red,
        //   first week / no rate → neutral. Each completed week is labeled with its average; the
        //   in-progress week stays a hollow pending point with no label.
        val ptR = clamp(w * 0.009f, 3.5f, 6f)
        val labelP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = subSize * 0.88f; textAlign = Paint.Align.CENTER; isFakeBoldText = true
        }
        series.weeks.forEachIndexed { i, wk ->
            val px = x(i); val py = y(wk.avg)
            if (!wk.complete) {
                // pending current point (hollow, unlabeled)
                c.drawCircle(px, py, ptR + 1f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CARD })
                c.drawCircle(px, py, ptR + 1f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; strokeWidth = 2f; color = LINE
                })
            } else {
                val col = weekColor(wk, target)
                c.drawCircle(px, py, ptR, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col })
                labelP.color = col
                c.drawText(fmt1(wk.avg), px, py - ptR * 1.9f, labelP)
            }
        }

        // ===== current week daily weigh-ins (faint cluster around the pending point) =====
        val dp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FAINT }
        val cx = x(n - 1)
        val jitter = min(slotW * 0.28f, w * 0.03f)
        series.currentDailies.forEachIndexed { i, wv ->
            val jx = cx + (if (i % 2 == 0) -1 else 1) * jitter * ((i % 3) + 1) / 3f
            c.drawCircle(jx, y(wv), ptR * 0.6f, dp)
        }

        // ===== x labels (weeks) =====
        val xp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MUTED; textSize = subSize * 0.8f; textAlign = Paint.Align.CENTER }
        for (i in 0 until n) {
            val label = if (i == n - 1 && !cur.complete) "now" else "${i + 1}"
            xp.color = if (i == n - 1 && !cur.complete) COOL else MUTED
            c.drawText(label, x(i), plotB + subSize * 1.1f, xp)
        }

        WidgetChrome.drawFooter(c, left, right, footerTop, footerH, page, pageCount)
        return bmp
    }

    /** Dot color for a completed week: green in-zone, yellow over-cut, red under-cut, neutral if
     *  there's no rate to judge (first week or no target). */
    private fun weekColor(wk: WeekWeight, target: WeightTarget?): Int {
        val rate = wk.rate ?: return LINE
        if (target == null) return LINE
        return when {
            rate > target.upperRate -> AMBER   // lost more than the band's top → cutting harder
            rate < target.lowerRate -> RED      // lost less than the band's bottom → under-cutting
            else -> GREEN                        // inside the band
        }
    }

    private fun fmt1(v: Float): String = String.format("%.1f", v)
    private fun signed(v: Float): String = (if (v <= 0f) "−" else "+") + String.format("%.1f", kotlin.math.abs(v))
    private fun clamp(v: Float, lo: Float, hi: Float) = max(lo, min(hi, v))
}
