package com.example.macrowidget

import kotlin.math.max
import kotlin.math.min

/**
 * Metrics shared by every page, kept in one place so the pages cannot drift apart.
 *
 * No Android imports, so a page that keeps its geometry in a pure layout object stays verifiable
 * on the JVM.
 */
object PageMetrics {

    /**
     * Title size for a page whose usable (vertically padded) height is [avail] pixels.
     *
     * The three pages used to size their headers independently -- effectively avail*0.0505 on
     * Today, avail*0.075 on Burn rate and avail*0.055 on Weight -- so on one 687px tile the same
     * piece of chrome rendered at 33.3, 49.0 and 36.0px and Burn rate read as a different app.
     * The Weight page's scale is the canonical one here because it is the only one of the three
     * that never overflowed its own header band at the small end.
     */
    fun titleSize(avail: Float): Float = clamp(avail * 0.055f, 18f, 40f)

    /**
     * Smallest radius the refresh button is ever drawn at (see WidgetChrome.drawFooter).
     *
     * A floor here means the button stops scaling down on a short tile, so a footer sized as a
     * plain fraction of the tile can end up shorter than the button is tall. Any page that reserves
     * a footer band has to keep it at least [REFRESH_MIN_RADIUS] * 2 tall or the button spills into
     * the content above it.
     */
    const val REFRESH_MIN_RADIUS = 13f

    /** Largest radius the refresh button is drawn at, so it stays a corner control on a big tile. */
    const val REFRESH_MAX_RADIUS = 28f

    private fun clamp(v: Float, lo: Float, hi: Float) = max(lo, min(hi, v))
}
