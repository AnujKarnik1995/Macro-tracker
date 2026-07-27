package com.example.macrowidget

import android.graphics.Color
import kotlin.math.max

/** A three-anchor palette: under / good / over, plus a neutral track. */
data class Palette(val under: Int, val good: Int, val over: Int, val track: Int)

object ColorRamp {

    // Daily bullet rows — Bold palette
    val DAILY = Palette(
        under = Color.parseColor("#FFB300"),
        good = Color.parseColor("#15CF92"),
        over = Color.parseColor("#FF4D4F"),
        track = Color.parseColor("#2B2B2B")
    )

    // Weekly rings — Bold palette (brighter, cooler)
    val WEEKLY = Palette(
        under = Color.parseColor("#FFC72B"),
        good = Color.parseColor("#1FE3A6"),
        over = Color.parseColor("#FF4F8B"),
        track = Color.parseColor("#2E3A4D")
    )

    /**
     * Continuous color for a value vs its band. Inside the band -> good.
     * Outside, interpolate toward amber/red over a span tied to the band width,
     * so "just outside" stays near-green and "way off" saturates.
     * Being under a danger-low macro (e.g. fat) reds out instead of going amber.
     */
    fun zoneColor(value: Float, target: Target?, p: Palette): Int {
        if (target == null) return Color.parseColor("#888888")
        if (value >= target.lower && value <= target.upper) return p.good
        val band = max(target.upper - target.lower, 1f)
        val span = max(band, 0.15f * target.upper)
        return if (value < target.lower) {
            val t = ((target.lower - value) / span).coerceIn(0f, 1f)
            lerp(p.good, if (target.underDanger) p.over else p.under, t)
        } else {
            val t = ((value - target.upper) / span).coerceIn(0f, 1f)
            lerp(p.good, p.over, t)
        }
    }

    private fun lerp(a: Int, b: Int, t: Float): Int {
        val r = (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt()
        val g = (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt()
        val bl = (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
        return Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), bl.coerceIn(0, 255))
    }
}
