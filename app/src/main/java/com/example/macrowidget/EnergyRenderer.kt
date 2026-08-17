package com.example.macrowidget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.example.macrowidget.EnergyLayout.Layout
import com.example.macrowidget.EnergyLayout.Slot
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws the Energy page: measured TDEE (hero), the measured loss rate coloured against the
 * 0.7-0.9 lb/wk band (green in-zone, amber cutting-hard, red under-cutting), a target-band gauge
 * with the current marker, and the intake adjustment to settle into the band. Falls back to a
 * "measuring" state until the TDEE window has enough data. Footer = refresh corner.
 *
 * All vertical geometry lives in EnergyLayout: this file decides what to draw and in which band,
 * never where the band sits. Every string goes through [drawInBand], so nothing here can place a
 * baseline by hand and re-introduce the overlap that made the page unreadable.
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
        gym: GymStats,
        page: Int,
        pageCount: Int,
        widthPx: Int,
        heightPx: Int
    ): Bitmap {
        val tdeeVal = tdee.tdee
        val collecting = tdee.collecting || tdeeVal == null

        // The training block is laid out whenever a plan is configured, including while TDEE is
        // still collecting: counting sessions needs no weigh-ins, so gating it behind the energy
        // data would blank the one number that works from day one.
        val lay = EnergyLayout.compute(widthPx, heightPx, gym.configured, collecting)

        val bmp = Bitmap.createBitmap(lay.width, lay.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        drawInBand(c, lay, Slot.HEADER, "Burn rate", WHITE, bold = true)

        if (collecting) {
            drawInBand(c, lay, Slot.MEASURE_TITLE, "Measuring your burn", MUTED)
            val days = tdee.daysNeeded
            drawInBand(c, lay, Slot.MEASURE_SUB,
                "$days more day${if (days == 1) "" else "s"} of data", FAINT)
        } else {
            drawMeasured(c, lay, tdee, tdeeVal!!, band)
        }

        if (gym.configured) drawTraining(c, lay, gym)

        val footer = lay.band(Slot.FOOTER)
        WidgetChrome.drawFooter(c, lay.left, lay.right, footer.top, footer.height, page, pageCount)
        return bmp
    }

    // ===== measured state =====

    private fun drawMeasured(c: Canvas, lay: Layout, tdee: TdeeResult, tdeeVal: Float, band: WeightTarget?) {
        drawInBand(c, lay, Slot.HERO, String.format("%,d", tdeeVal.roundToInt()), WHITE, bold = true)

        val n = tdee.weighIns
        drawInBand(c, lay, Slot.HERO_SUB,
            "kcal/day \u00B7 $n weigh-in${if (n == 1) "" else "s"}", MUTED)

        tdee.avgIntake?.let {
            drawInBand(c, lay, Slot.AVG_INTAKE,
                "Avg intake ${String.format("%,d", it.roundToInt())} kcal/day", FAINT)
        }

        val rate = tdee.lbPerWeek
        val col = colorForRate(rate, band)
        if (rate != null) drawInBand(c, lay, Slot.RATE, "${fmt1(rate)} lb/wk", col, bold = true)

        if (band != null && rate != null) {
            drawGauge(c, lay, rate, band, col)
            drawAction(c, lay, tdee, rate, band)
        }
    }

    private fun drawGauge(c: Canvas, lay: Layout, rate: Float, band: WeightTarget, col: Int) {
        val lo = band.lowerRate
        val hi = band.upperRate
        val rmin = max(0f, lo - 0.4f)
        // Fixed headroom to ~1.8 lb/wk so a fast week (e.g. 1.3) sits well inside the track instead
        // of pinned to the right edge; still expands if the band's own upper bound is higher.
        val rmax = max(hi + 0.4f, 1.8f)

        val gx0 = lay.gaugeLeft
        val gx1 = lay.gaugeRight
        val gy = lay.band(Slot.GAUGE).centerY
        fun gx(r: Float) = gx0 + (clamp(r, rmin, rmax) - rmin) / (rmax - rmin) * (gx1 - gx0)

        val th = lay.gaugeTrackHalf
        val zh = lay.gaugeZoneHalf

        c.drawRoundRect(gx0, gy - th, gx1, gy + th, th, th,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AXIS })
        c.drawRoundRect(gx(lo), gy - zh, gx(hi), gy + zh, zh * 0.5f, zh * 0.5f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GREEN; alpha = 150 })
        c.drawCircle(gx(rate), gy, lay.gaugeMarkerR,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col })

        // Zone edge numbers sit in their own band, so they clear both the gauge and the line below.
        val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GREEN_BRIGHT; textSize = lay.textSize(Slot.GAUGE_LABELS); textAlign = Paint.Align.CENTER
        }
        val ly = lay.band(Slot.GAUGE_LABELS).baselineFor(lp.textSize)
        c.drawText(fmt1(lo), gx(lo), ly, lp)
        c.drawText(fmt1(hi), gx(hi), ly, lp)
    }

    private fun drawAction(c: Canvas, lay: Layout, tdee: TdeeResult, rate: Float, band: WeightTarget) {
        val lo = band.lowerRate
        val hi = band.upperRate
        val mid = (lo + hi) / 2f
        val req = tdee.intakeForRate(mid)
        val avg = tdee.avgIntake

        val text: String
        val color: Int
        if (round1(rate) in lo..hi || req == null || avg == null) {
            text = "On target \u00B7 hold here"
            color = GREEN
        } else {
            val delta = (req - avg).roundToInt()
            text = "Eat ${signedInt(delta)} kcal/day \u2192 ${fmt1(mid)} lb/wk"
            color = if (delta >= 0) GREEN else AMBER
        }
        drawInBand(c, lay, Slot.ACTION, text, color, bold = true)
    }

    // ===== training block =====

    /**
     * Divider, header, seven day-dots, the required-rate headline and the next-session caption.
     * Occupies the same bands whether the energy half above it is measuring or measured, because
     * the band stack appends this section in both states.
     */
    private fun drawTraining(c: Canvas, lay: Layout, gym: GymStats) {
        val col = when (GymCalculator.zone(gym)) {
            2 -> RED
            1 -> AMBER
            else -> GREEN
        }

        val divY = lay.band(Slot.DIVIDER).centerY
        c.drawLine(lay.dividerLeft, divY, lay.dividerRight, divY,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = AXIS; strokeWidth = max(1f, lay.width * 0.0032f)
            })

        drawInBand(c, lay, Slot.TRAIN_HDR, "TRAINING", FAINT, bold = true, letterSpacing = 0.18f)

        // ===== seven day-dots, oldest left, today on the right =====
        // Deliberately raw: a filled dot is a session, an empty one is not. No "rest day" state,
        // because there is no rest-day log -- absence IS the miss. Clustering shows up on its own
        // here (4 in a row reads differently from 4 spread out) without the widget saying anything.
        val dotR = lay.dotRadius
        val gap = dotR * 3.4f
        val n = gym.last7.size
        val x0 = lay.cx - gap * (n - 1) / 2f
        val dotY = lay.band(Slot.DOTS).centerY
        val onPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col }
        val offPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AXIS }
        gym.last7.forEachIndexed { i, trained ->
            c.drawCircle(x0 + gap * i, dotY, dotR, if (trained) onPaint else offPaint)
        }
        // today gets a halo so the row reads as a timeline rather than a plain tally
        if (gym.last7.isNotEmpty()) {
            c.drawCircle(x0 + gap * (n - 1), dotY, dotR * 1.6f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (gym.last7.last()) col else FAINT
                    style = Paint.Style.STROKE
                    strokeWidth = max(1f, lay.width * 0.0035f)
                    alpha = 115
                })
        }

        drawInBand(c, lay, Slot.GYM_HEAD, GymCalculator.headline(gym), col, bold = true)
        drawInBand(c, lay, Slot.GYM_CAP, GymCalculator.caption(gym), FAINT)
    }

    // ===== helpers =====

    /**
     * Draw [text] centred in [slot]'s band, at that band's own type size.
     *
     * Shrinking to fit the tile's width can only reduce the type, which moves the ink further
     * inside the band, so the no-overlap property survives the fit. The baseline is recomputed
     * from the final size for the same reason.
     */
    private fun drawInBand(
        c: Canvas,
        lay: Layout,
        slot: Slot,
        text: String,
        color: Int,
        bold: Boolean = false,
        letterSpacing: Float = 0f
    ) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = lay.textSize(slot)
            isFakeBoldText = bold
            textAlign = Paint.Align.CENTER
            this.letterSpacing = letterSpacing
        }
        fitToWidth(p, text, lay.contentW)
        c.drawText(text, lay.cx, lay.band(slot).baselineFor(p.textSize), p)
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
    private fun signedInt(v: Int): String = (if (v >= 0) "+" else "\u2212") + abs(v)
    private fun clamp(v: Float, lo: Float, hi: Float) = max(lo, min(hi, v))
}
