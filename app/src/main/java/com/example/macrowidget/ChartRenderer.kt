package com.example.macrowidget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the whole stacked widget to a Bitmap:
 *   ribbon -> streak (green-day tally) + countdown chips
 *   Today  -> graded bullet rows with the relevant band bound marked
 *   Week   -> brighter progress rings (1 row when wide, 2x2 otherwise)
 * Background is transparent; the card drawable behind the ImageView supplies the
 * dark rounded surface. Type and rings scale to fill the tile; headers shrink to fit
 * width so nothing overflows on narrow tiles.
 */
object ChartRenderer {

    private const val WHITE = Color.WHITE
    private val MUTED = Color.parseColor("#9AA0A6")
    private val MUTED_COOL = Color.parseColor("#AAB6C2")
    private val RING_LABEL = Color.parseColor("#ECEFF3") // bright white-ish labels under rings
    private val DIVIDER = Color.parseColor("#2B2B2B")
    private val ERR = Color.parseColor("#FF6B6B")
    private val BOUND = Color.parseColor("#E2E6EA")

    private val STREAK_BG = Color.parseColor("#14241F")
    private val STREAK_STROKE = Color.parseColor("#1D9E75")
    private val COUNT_BG = Color.parseColor("#15202E")
    private val COUNT_STROKE = Color.parseColor("#3C6FA5")

    private val ORDER = listOf(MacroType.CALORIES, MacroType.PROTEIN, MacroType.CARBS, MacroType.FAT)

    fun render(
        today: LogEntry?,
        weekly: WeeklyAverage?,
        targets: Map<MacroType, Target>,
        successfulCount: Int,
        totalDays: Int,
        daysToGoal: Int,
        goalLabel: String,
        phaseLabel: String,
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
        val padV = pad * 0.55f // tighter top/bottom so the content fills the tile vertically
        val left = pad
        val right = w - pad
        val contentW = right - left
        val avail = h - padV * 2f

        // ----- vertical budget (fractions of usable height) -----
        val ribbonH = avail * 0.100f
        val todayHdrH = avail * 0.070f
        val bulletsH = avail * 0.290f
        val legendH = avail * 0.046f
        val dividerH = avail * 0.024f
        val weekHdrH = avail * 0.064f
        val footerH = avail * 0.070f   // page dots + refresh button
        val ringsH = avail - ribbonH - todayHdrH - bulletsH - legendH - dividerH - weekHdrH - footerH

        val titleSize = clamp(todayHdrH * 0.76f, 24f, 70f) * 0.95f // header dialed 5% smaller
        val weekTitleSize = clamp(weekHdrH * 0.70f, 22f, 60f)
        val subSize = clamp(todayHdrH * 0.46f, 14f, 34f)
        val labelSize = clamp((bulletsH / ORDER.size) * 0.52f, 20f, 60f)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WHITE; isFakeBoldText = true; textAlign = Paint.Align.CENTER }

        var y = padV

        // ===== ribbon: streak + countdown chips =====
        val gap = pad * 0.6f
        val chipW = (contentW - gap) / 2f
        val chipH = ribbonH * 0.78f
        val chipTop = y + (ribbonH - chipH) / 2f
        val chipText = clamp(chipH * 0.46f, 14f, 36f)
        drawChip(c, left, chipTop, chipW, chipH,
            "✓ $successfulCount / $totalDays",
            STREAK_BG, STREAK_STROKE, chipText)
        drawChip(c, left + chipW + gap, chipTop, chipW, chipH,
            "$daysToGoal d → $goalLabel", COUNT_BG, COUNT_STROKE, chipText)
        y += ribbonH

        // ===== Header: "<phase> · Today", centered as one block on the tile =====
        // (Previously the dot was pinned to the tile centre and each flank drawn outward, which
        //  left the wider phrase hanging left of centre. Centre the whole string instead.)
        val centerX = (left + right) / 2f
        val fullTitle = "$phaseLabel · Today"
        titlePaint.textSize = titleSize
        titlePaint.textAlign = Paint.Align.CENTER
        fitToWidth(titlePaint, fullTitle, contentW, 16f)
        c.drawText(fullTitle, centerX, centerBaseline(y, todayHdrH, titlePaint.textSize), titlePaint)
        y += todayHdrH

        // ===== Today bullet rows =====
        if (today == null) {
            c.drawText("No entry logged today", left, y + bulletsH / 2f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MUTED; textSize = labelSize })
        } else {
            drawBulletRows(c, today.values, targets, left, y, right, bulletsH, labelSize)
        }
        y += bulletsH

        // ===== legend strip =====
        drawLegend(c, left, y, right, legendH, subSize)
        y += legendH

        // ===== divider =====
        y += dividerH * 0.5f
        c.drawLine(left, y, right, y, Paint().apply { color = DIVIDER; strokeWidth = max(1f, w * 0.003f) })
        y += dividerH * 0.5f

        // ===== Week header =====
        val weekTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WHITE; isFakeBoldText = true }
        val weekRightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MUTED_COOL; textSize = subSize; textAlign = Paint.Align.RIGHT }
        val weekRight = weekly?.let {
            "${it.start.dayOfWeek.short()}–${it.end.dayOfWeek.short()} · ${it.dayCount} day${if (it.dayCount == 1) "" else "s"}"
        } ?: ""
        c.drawText(weekRight, right, centerBaseline(y, weekHdrH, subSize), weekRightPaint)
        weekTitle.textSize = weekTitleSize
        fitToWidth(weekTitle, "This week · avg", contentW - weekRightPaint.measureText(weekRight) - pad)
        c.drawText("This week · avg", left, centerBaseline(y, weekHdrH, weekTitle.textSize), weekTitle)
        y += weekHdrH

        // ===== Week rings (1 row when clearly wide, else 2x2) =====
        val cols = if (w > h * 1.35f) 4 else 2
        if (weekly == null) {
            c.drawText("No data this week yet", left, y + ringsH / 2f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MUTED_COOL; textSize = labelSize })
        } else {
            // Rings grade the week's average against the week's MEAN per-day band, not today's.
            drawRings(c, weekly.values, weekly.bands, left, y, right, ringsH, cols)
        }

        // ===== footer: page dots + refresh button =====
        WidgetChrome.drawFooter(c, left, right, h - padV - footerH, footerH, page, pageCount)

        return bmp
    }

    /** Which band edge to mark per macro. All currently mark the upper (ceiling) bound;
     *  the per-macro branch is kept so any macro can be flipped back to its lower bound. */
    private fun boundFor(m: MacroType, t: Target): Pair<Float, Boolean> = when (m) {
        MacroType.PROTEIN, MacroType.FAT -> t.upper to true
        else -> t.upper to true
    }

    private fun drawBulletRows(
        c: Canvas, values: Map<MacroType, Float>, targets: Map<MacroType, Target>,
        left: Float, top: Float, right: Float, blockH: Float, labelSize: Float
    ) {
        val contentW = right - left
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WHITE; textSize = labelSize }
        val valPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#D6D6D6"); textSize = labelSize * 0.94f; textAlign = Paint.Align.RIGHT
        }
        val boundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = MUTED_COOL; textSize = labelSize * 0.58f; textAlign = Paint.Align.CENTER
        }
        val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MUTED; textAlign = Paint.Align.RIGHT }

        // Bound widths by tile width so the track stays usable on narrow/tall tiles.
        val labelW = min(labelSize * 2.7f, contentW * 0.26f)
        val valW = min(labelSize * 3.0f, contentW * 0.30f)
        val trackL = left + labelW
        val trackR = right - valW
        val rowH = blockH / ORDER.size
        val barH = clamp(rowH * 0.46f, 12f, labelSize * 1.25f)

        ORDER.forEachIndexed { i, m ->
            val cy = top + rowH * i + rowH / 2f
            val barTop = cy - barH / 2f
            val barBot = barTop + barH
            val target = targets[m]
            val value = values[m] ?: 0f

            c.drawText(m.label, left, cy + labelSize * 0.35f, labelPaint)

            c.drawRoundRect(trackL, barTop, trackR, barBot, barH / 2f, barH / 2f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ColorRamp.DAILY.track })

            if (target != null) {
                val scaleMax = max(target.upper * 1.35f, value)
                fun x(v: Float) = trackL + (trackR - trackL) * (v / scaleMax).coerceIn(0f, 1f)
                c.drawRect(x(target.lower), barTop, x(target.upper), barBot,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ColorRamp.DAILY.good; alpha = 60 })
                c.drawRoundRect(trackL, barTop, x(value), barBot, barH / 2f, barH / 2f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = ColorRamp.zoneColor(value, target, ColorRamp.DAILY) })
                // bound marker: a tick at the band edge that matters + its number above
                val (boundVal, isUpper) = boundFor(m, target)
                val bx = x(boundVal)
                c.drawRect(bx - 1.2f, barTop - barH * 0.3f, bx + 1.2f, barBot + barH * 0.3f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BOUND })
                val txt = (if (isUpper) "≤" else "≥") + fmt(boundVal)
                val half = boundPaint.measureText(txt) / 2f
                c.drawText(txt, bx.coerceIn(trackL + half, trackR - half), barTop - barH * 0.45f, boundPaint)
            }

            c.drawText(fmt(value) + m.unit, right, cy + labelSize * 0.32f, valPaint)

            // "to go today": how much more to reach the comfortable middle of the band.
            // Purely informational — does NOT affect green/success (that stays lower..upper).
            // Skipped on short rows where there isn't vertical room under the value.
            if (target != null && rowH > labelSize * 1.8f) {
                val mid = (target.lower + target.upper) / 2f
                if (value < mid) {
                    hintPaint.textSize = labelSize * 0.56f
                    val hint = "+" + fmt(mid - value) + " to " + fmt(mid)
                    // allow a little past the value column (into the empty right track below the bar)
                    fitToWidth(hintPaint, hint, valW * 1.25f, 10f)
                    // baseline well below the value so it clears the value's descender (the "g")
                    c.drawText(hint, right, cy + labelSize * 1.0f, hintPaint)
                }
            }
        }
    }

    private fun drawLegend(c: Canvas, left: Float, top: Float, right: Float, h: Float, textSize: Float) {
        val ts = textSize * 1.15f // larger "under"/"over" labels
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MUTED; this.textSize = ts }
        val tpR = Paint(tp).apply { textAlign = Paint.Align.RIGHT }
        val barH = max(5f, h * 0.34f)
        val barTop = top + (h - barH) / 2f
        val barBot = barTop + barH
        val textBaseline = barTop + barH / 2f + ts * 0.35f // vertically centered on the bar
        val labW = ts * 2.7f
        val gx0 = left + labW
        val gx1 = right - labW
        c.drawText("under", left, textBaseline, tp)
        c.drawText("over", right, textBaseline, tpR)
        val stops = intArrayOf(
            ColorRamp.DAILY.over, ColorRamp.DAILY.under, ColorRamp.DAILY.good,
            ColorRamp.DAILY.under, ColorRamp.DAILY.over
        )
        val segs = 40
        val segW = (gx1 - gx0) / segs
        for (s in 0 until segs) {
            val f = s.toFloat() / (segs - 1)
            c.drawRect(gx0 + segW * s, barTop, gx0 + segW * (s + 1) + 1f, barBot,
                Paint().apply { color = sampleStops(stops, f) })
        }
    }

    private fun drawRings(
        c: Canvas, values: Map<MacroType, Float>, targets: Map<MacroType, Target>,
        left: Float, top: Float, right: Float, blockH: Float, cols: Int
    ) {
        val n = ORDER.size
        val rows = ceil(n / cols.toFloat()).toInt()
        val cellW = (right - left) / cols
        val cellH = blockH / rows
        val labelSize = clamp(min(cellW, cellH) * 0.22f, 14f, 36f)
        // Reserve enough room below each ring for its label so labels never touch the
        // ring above or the ring in the row below.
        val labelRoom = labelSize * 1.6f
        val ringArea = cellH - labelRoom
        val r = min(cellW * 0.40f, ringArea * 0.47f)
        val stroke = max(7f, r * 0.32f)

        val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = RING_LABEL; textSize = labelSize; textAlign = Paint.Align.CENTER
        }

        ORDER.forEachIndexed { i, m ->
            val col = i % cols
            val row = i / cols
            val cx = left + cellW * col + cellW / 2f
            val cellTop = top + cellH * row
            val cyRing = cellTop + ringArea / 2f
            val target = targets[m]
            val value = values[m] ?: 0f
            val rect = RectF(cx - r, cyRing - r, cx + r, cyRing + r)

            c.drawArc(rect, 0f, 360f, false, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = stroke; color = ColorRamp.WEEKLY.track
            })
            if (target != null) {
                val frac = (value / target.upper).coerceIn(0f, 1f)
                c.drawArc(rect, -90f, frac * 360f, false, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; strokeWidth = stroke
                    color = ColorRamp.zoneColor(value, target, ColorRamp.WEEKLY)
                    strokeCap = Paint.Cap.ROUND
                })
            }
            // Fit the value inside the ring's *inner* circle so digits never touch the stroke.
            val txt = fmt(value) + m.unit
            val inner = (r - stroke) * 2f
            numPaint.textSize = clamp(r * 0.5f, 14f, 46f)
            fitToWidth(numPaint, txt, inner * 0.86f, 12f)
            c.drawText(txt, cx, cyRing + numPaint.textSize * 0.35f, numPaint)
            c.drawText(m.label, cx, cellTop + cellH - labelSize * 0.45f, labelPaint)
        }
    }

    private fun drawChip(
        c: Canvas, l: Float, t: Float, w: Float, h: Float,
        text: String, bg: Int, stroke: Int, baseTextSize: Float
    ) {
        val r = h / 2f
        c.drawRoundRect(l, t, l + w, t + h, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg })
        c.drawRoundRect(l + 0.75f, t + 0.75f, l + w - 0.75f, t + h - 0.75f, r, r,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = max(1.5f, h * 0.05f); color = stroke
            })
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WHITE; textAlign = Paint.Align.CENTER; textSize = baseTextSize }
        fitToWidth(tp, text, w * 0.86f, 10f)
        c.drawText(text, l + w / 2f, t + h / 2f + tp.textSize * 0.35f, tp)
    }

    fun renderError(message: String, widthPx: Int, heightPx: Int): Bitmap {
        val w = max(widthPx, 320); val h = max(heightPx, 200)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ERR; textSize = clamp(w * 0.04f, 16f, 30f) }
        var y = h * 0.4f
        val perLine = (w / (p.textSize * 0.55f)).toInt().coerceAtLeast(10)
        message.chunked(perLine).forEach { c.drawText(it, w * 0.05f, y, p); y += p.textSize * 1.3f }
        c.drawText("Tap to retry", w * 0.05f, y + p.textSize, Paint(p).apply { color = MUTED })
        return bmp
    }

    /** Shrink a paint's text size until [text] fits within [maxW]. */
    private fun fitToWidth(p: Paint, text: String, maxW: Float, min: Float = 12f) {
        while (p.textSize > min && p.measureText(text) > maxW) p.textSize = p.textSize - 1f
    }

    private fun centerBaseline(bandTop: Float, bandH: Float, textSize: Float) =
        bandTop + bandH / 2f + textSize * 0.36f

    private fun sampleStops(stops: IntArray, f: Float): Int {
        val pos = f * (stops.size - 1)
        val i = pos.toInt().coerceIn(0, stops.size - 2)
        val t = pos - i
        val a = stops[i]; val b = stops[i + 1]
        val r = (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt()
        val g = (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt()
        val bl = (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
        return Color.rgb(r, g, bl)
    }

    private fun clamp(v: Float, lo: Float, hi: Float) = max(lo, min(hi, v))

    private fun fmt(v: Float): String =
        if (v == v.toLong().toFloat()) v.toLong().toString() else String.format("%.0f", v)
}

private fun java.time.DayOfWeek.short(): String =
    name.substring(0, 1) + name.substring(1, 3).lowercase()
