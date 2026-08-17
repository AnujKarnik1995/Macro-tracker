package com.example.macrowidget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the weight page: a weekly-average weight trend (left axis), the current week's daily
 * weigh-ins placed on their own day-of-week columns, and a single green target band anchored at
 * the current week's target (last week's avg − 0.7 to − 0.9), edges labeled 0.7 / 0.9. Each
 * completed week's dot is colored by how its loss rate landed: green = in-zone, yellow =
 * over-cut, red = under-cut. Footer = page dots + refresh.
 *
 * Everything here is sized off a **window of recent weeks**, never off total history, so the
 * page reads the same at 7 weeks and at 100:
 *
 *  - the window holds the last [MIN_WEEKS]..[MAX_WEEKS] weeks, chosen so a slot is wide enough
 *    for the seven day-of-week positions of the current week's weigh-ins;
 *  - the y span is capped at whatever still renders the target band [BAND_MIN_PX] tall, and the
 *    window is shortened rather than the trend clipped when it doesn't fit;
 *  - value labels are packed newest-first and dropped once they would touch;
 *  - a week with no weigh-ins keeps its slot, draws nothing, and is bridged with a dashed line.
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

    /** Window bounds. Below MIN_WEEKS the trend stops being a trend; above MAX_WEEKS the dots
     *  merge no matter how wide the widget is. */
    private const val MIN_WEEKS = 6
    private const val MAX_WEEKS = 14

    /** The target band is only (upper − lower) lb tall — 0.2 by default. This is the floor on
     *  how few pixels that is allowed to become, and it's what caps the y span. */
    private const val BAND_MIN_PX = 8f

    /** Floor on the y span, so a flat stretch doesn't magnify daily scale noise into a cliff. */
    private const val MIN_SPAN_LB = 4f

    /** Headroom above and below the plotted values, as a fraction of their spread. */
    private const val PAD_FRAC = 0.12f

    /** Fraction of the current week's slot the seven day-of-week positions span. */
    private const val DOW_SPREAD = 0.92f

    private val X_FMT = DateTimeFormatter.ofPattern("MMM d", Locale.US)

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

        val titleSize = PageMetrics.titleSize(avail) // shared across all three pages
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

        // subline: "184.3 lb · this wk −0.7 · −5.7 total". Whole-history facts live here, which
        // is what lets the plot below show only a recent window.
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
        val plotW = plotR - axisL
        val plotH = plotB - plotT

        // ===== pick the window =====
        // A slot has to fit seven day-of-week positions for the current week's weigh-ins; at the
        // sizes this widget renders at that lands around 1.7x the subline text, and scales with
        // the widget instead of being pinned to one device.
        val slotTarget = subSize * 1.7f
        val k = min((plotW / slotTarget).toInt().coerceIn(MIN_WEEKS, MAX_WEEKS), series.weeks.size)

        // The band's own height in lb decides how tall the y axis is allowed to be. Cap the span
        // at the value that still renders it BAND_MIN_PX tall.
        val bandLb = if (series.targetLow != null && series.targetHigh != null)
            series.targetHigh - series.targetLow else 0f
        val maxSpan = if (bandLb > 0f) bandLb * plotH / BAND_MIN_PX else Float.MAX_VALUE

        // Shorten the window until its natural range fits under that cap. Dropping the oldest
        // weeks is honest; clipping the trend line off the top of the plot is not.
        var weeks = series.weeks.takeLast(k)
        while (weeks.size > MIN_WEEKS && spanOf(weeks, series) > maxSpan) weeks = weeks.drop(1)

        val n = weeks.size
        fun x(i: Int) = axisL + (i + 0.5f) / n * plotW
        val slotW = plotW / n

        // ===== y range =====
        val natural = rangeOf(weeks, series)
        var minW: Float
        var maxW: Float
        if (natural.second - natural.first > maxSpan) {
            // Even the shortest window is too tall (an unusually fast week). Anchor on the newest
            // data and the band so those stay readable; the oldest weeks clip off the top.
            val anchor = rangeOf(weeks.takeLast(1), series)
            val mid = (anchor.first + anchor.second) / 2f
            minW = mid - maxSpan / 2f; maxW = mid + maxSpan / 2f
        } else {
            val mid = (natural.first + natural.second) / 2f
            val span = min(max(natural.second - natural.first, MIN_SPAN_LB), maxSpan)
            minW = mid - span / 2f; maxW = mid + span / 2f
        }
        fun y(v: Float) = plotB - (v - minW) / (maxW - minW) * plotH

        // ===== left axis + weight labels =====
        c.drawLine(axisL, plotT, axisL, plotB, Paint().apply { color = AXIS; strokeWidth = 1.5f })
        c.drawLine(axisL, plotB, plotR, plotB, Paint().apply { color = AXIS; strokeWidth = 1.5f })
        val axP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MUTED; textSize = subSize * 0.85f; textAlign = Paint.Align.RIGHT }
        for (i in 0..3) {
            val wv = minW + i / 3f * (maxW - minW)
            c.drawText(fmt1(wv), axisL - subSize * 0.35f, y(wv) + subSize * 0.3f, axP)
        }

        // Everything data-driven is clipped to the plot box. The span cap above can legitimately
        // push the oldest weeks in the window off the top (a very fast cut); without this they
        // would be painted straight over the title, because Canvas doesn't clip by default.
        // The top edge is lifted by one text line so a value label on the highest point still fits.
        c.save()
        c.clipRect(axisL, plotT - subSize, w.toFloat(), plotB + 1f)

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

        // ===== trend line =====
        // Solid only between two adjacent, completed weeks. Any other hop — across a week with no
        // weigh-ins, or forward into the in-progress week — is dashed, so a gap can't read as a
        // normal week-to-week move. The missing week itself draws nothing at all.
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = LINE; style = Paint.Style.STROKE; strokeWidth = max(2f, w * 0.006f)
            strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        }
        val bridgePaint = Paint(linePaint).apply {
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(5f, 4f), 0f); alpha = 180
        }
        var prevIdx = -1
        for (i in 0 until n) {
            val av = weeks[i].avg ?: continue
            if (prevIdx >= 0) {
                val prev = weeks[prevIdx]
                val contiguous = (i - prevIdx == 1) && prev.complete && weeks[i].complete
                c.drawLine(x(prevIdx), y(prev.avg!!), x(i), y(av),
                    if (contiguous) linePaint else bridgePaint)
            }
            prevIdx = i
        }

        // ===== weekly points + value labels =====
        // Dot color by how the week's loss rate lands vs the target band:
        //   in-zone → green, over-cut (rate > upper) → yellow, under-cut (rate < lower) → red,
        //   first week / week after a gap / no target → neutral.
        // Labels are packed newest-first and dropped as soon as one would touch its neighbour —
        // colour survives at 3 px, text does not.
        val ptR = clamp(w * 0.009f, 3.5f, 6f)
        val labelP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = subSize * 0.88f; textAlign = Paint.Align.CENTER; isFakeBoldText = true
        }
        val labW = labelP.measureText("188.8") * 1.15f
        val labelled = HashSet<Int>()
        var lastLabelX = Float.MAX_VALUE
        for (i in n - 1 downTo 0) {
            val wk = weeks[i]
            if (wk.avg == null || !wk.complete) continue
            if (lastLabelX - x(i) >= labW) { labelled.add(i); lastLabelX = x(i) }
        }

        for (i in 0 until n) {
            val wk = weeks[i]
            val av = wk.avg ?: continue          // week with no weigh-ins: nothing drawn
            val px = x(i); val py = y(av)
            if (!wk.complete) {
                // pending current point (hollow, unlabeled)
                c.drawCircle(px, py, ptR + 1f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CARD })
                c.drawCircle(px, py, ptR + 1f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; strokeWidth = 2f; color = LINE
                })
            } else {
                val col = weekColor(wk, target)
                c.drawCircle(px, py, ptR, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col })
                if (i in labelled) {
                    labelP.color = col
                    val lx = clamp(px, axisL + labW / 2f, plotR - labW / 2f)
                    c.drawText(fmt1(av), lx, py - ptR * 1.9f, labelP)
                }
            }
        }

        // ===== current week daily weigh-ins (placed on their own day, Sun → Sat) =====
        val dp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FAINT }
        val cx = x(n - 1)
        val dayW = slotW * DOW_SPREAD / 7f
        series.currentDailies.forEach { d ->
            val dow = d.date.dayOfWeek.value % 7          // Sunday = 0 … Saturday = 6
            c.drawCircle(cx + (dow - 3) * dayW, y(d.lb), ptR * 0.6f, dp)
        }

        c.restore()

        // ===== x labels (week-ending dates, thinned; a missed week gets none) =====
        val xp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MUTED; textSize = subSize * 0.8f; textAlign = Paint.Align.CENTER }
        val xLabW = xp.measureText("Jun 20") * 1.35f
        var lastXLabel = Float.MAX_VALUE
        for (i in n - 1 downTo 0) {
            val wk = weeks[i]
            val newest = (i == n - 1)
            if (!newest && (wk.avg == null || lastXLabel - x(i) < xLabW)) continue
            xp.color = if (newest && !wk.complete) COOL else MUTED
            c.drawText(if (newest && !wk.complete) "now" else wk.end.format(X_FMT),
                x(i), plotB + subSize * 1.1f, xp)
            lastXLabel = x(i)
        }

        WidgetChrome.drawFooter(c, left, right, footerTop, footerH, page, pageCount)
        return bmp
    }

    /** Padded [lo, hi] the plot has to cover: the window's weekly averages, the current week's
     *  weigh-ins, and the target band. Weeks with no data contribute nothing. */
    private fun rangeOf(weeks: List<WeekWeight>, series: WeightSeries): Pair<Float, Float> {
        val vals = ArrayList<Float>()
        weeks.forEach { wk -> wk.avg?.let { vals.add(it) } }
        series.currentDailies.forEach { vals.add(it.lb) }
        series.targetLow?.let { vals.add(it) }
        series.targetHigh?.let { vals.add(it) }
        var lo = vals.minOrNull() ?: 0f
        var hi = vals.maxOrNull() ?: 0f
        val span = max(hi - lo, 1f)
        lo -= span * PAD_FRAC
        hi += span * PAD_FRAC
        return lo to hi
    }

    private fun spanOf(weeks: List<WeekWeight>, series: WeightSeries): Float {
        val (lo, hi) = rangeOf(weeks, series)
        return hi - lo
    }

    /** Dot color for a completed week: green in-zone, yellow over-cut, red under-cut, neutral if
     *  there's no rate to judge (first week, the week after a gap, or no target). */
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
