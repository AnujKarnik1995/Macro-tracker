package com.example.macrowidget

import kotlin.math.max
import kotlin.math.min

/**
 * Vertical geometry for the Energy page.
 *
 * The tile is cut into a stack of disjoint horizontal BANDS, and every element draws inside
 * exactly one of them. Text is centred on its band and sized from that band's own height, so a
 * glyph cannot reach into a neighbour: overlap is ruled out by the structure rather than avoided
 * by tuning offsets one at a time.
 *
 * This replaces a scheme that placed each element at a hand-picked fraction of the content region
 * and treated that fraction as a text BASELINE. It failed three ways at once:
 *
 *  - the fraction knew nothing about glyph height, and fraction 0.0 was the title's baseline
 *    rather than its bottom, so there was no reserved space under the header at all;
 *  - the region was multiplied by 0.68 to make room for the training block while the type kept
 *    sizing off the full tile, so configuring a gym plan drew the hero number through the page
 *    title at every tile size (-7.9px of clearance on a 687px tile, and worse when short);
 *  - adjacent lines mixed anchoring schemes -- the hero's caption sat at an absolute offset from
 *    the hero while the line directly below it sat at a fraction of the region -- so the two
 *    crossed over as the region shrank.
 *
 * Kept free of Android imports so the geometry can be checked on the JVM (see backend/test).
 */
object EnergyLayout {

    /** Share of the usable height reserved for the title, whatever the body happens to hold. */
    private const val HEADER_SHARE = 0.090f

    /** Share reserved for the refresh corner. */
    private const val FOOTER_SHARE = 0.078f

    /**
     * Floor on the footer band. The refresh button stops shrinking at
     * [PageMetrics.REFRESH_MIN_RADIUS], so on a short tile a footer taken as a plain share of the
     * height is not tall enough to contain it and the button reaches up into the last line of
     * content. Reserving its full diameter is what keeps the band honest.
     */
    private const val FOOTER_MIN = PageMetrics.REFRESH_MIN_RADIUS * 2f

    /**
     * No text may exceed this fraction of its own band's height.
     *
     * This is the property the whole page rests on. Ink for the default sans runs about 0.72 of the
     * type size above the baseline and 0.21 below, so a centred line occupies at most 0.93 * 0.80 =
     * 0.744 of its band and leaves ~13% of the band clear at each edge. Adjacent bands therefore
     * cannot touch, whatever the tile's size or aspect ratio -- no per-element tuning involved.
     */
    private const val FIT_CAP = 0.80f

    /** Horizontal insets, as a fraction of content width, for the gauge and the divider. */
    private const val GAUGE_INSET = 0.0575f
    private const val DIVIDER_INSET = 0.048f

    /** A horizontal slice of the tile. Nothing belonging to a band is drawn outside it. */
    data class Band(val top: Float, val height: Float) {
        val bottom: Float get() = top + height
        val centerY: Float get() = top + height / 2f

        /**
         * Baseline placing text of [textSize] on the band's optical centre. The 0.36 factor is
         * about half a cap height for the default sans, matching ChartRenderer.centerBaseline so
         * the two pages sit their type on a band identically.
         */
        fun baselineFor(textSize: Float): Float = top + height / 2f + textSize * 0.36f
    }

    enum class Slot {
        HEADER,
        // measured state
        HERO, HERO_SUB, AVG_INTAKE, RATE, GAUGE, GAUGE_LABELS, ACTION,
        // "measuring" state; the pads are what centre the two lines in whatever space is left
        MEASURE_PAD_TOP, MEASURE_TITLE, MEASURE_SUB, MEASURE_PAD_BOT,
        // training block, drawn whenever a plan is configured, in either state above
        DIVIDER, TRAIN_HDR, DOTS, GYM_HEAD, GYM_CAP,
        FOOTER
    }

    /**
     * @param weight    relative height. Only ratios matter -- see [compute].
     * @param textRatio text size as a fraction of the band's height; 0 for bands that hold no text.
     */
    private class Spec(
        val slot: Slot,
        val weight: Float,
        val textRatio: Float = 0f,
        val minSize: Float = 0f,
        val maxSize: Float = 0f
    )

    private val MEASURED = listOf(
        Spec(Slot.HERO,         0.170f, 0.80f, 34f, 132f),
        Spec(Slot.HERO_SUB,     0.058f, 0.72f, 12f, 30f),
        Spec(Slot.AVG_INTAKE,   0.058f, 0.70f, 12f, 28f),
        Spec(Slot.RATE,         0.088f, 0.72f, 16f, 46f),
        Spec(Slot.GAUGE,        0.070f),
        Spec(Slot.GAUGE_LABELS, 0.050f, 0.70f, 11f, 24f),
        Spec(Slot.ACTION,       0.066f, 0.68f, 12f, 30f)
    )

    private val COLLECTING = listOf(
        Spec(Slot.MEASURE_PAD_TOP, 0.140f),
        Spec(Slot.MEASURE_TITLE,   0.110f, 0.42f, 14f, 40f),
        Spec(Slot.MEASURE_SUB,     0.080f, 0.46f, 12f, 30f),
        Spec(Slot.MEASURE_PAD_BOT, 0.140f)
    )

    private val TRAINING = listOf(
        Spec(Slot.DIVIDER,   0.030f),
        Spec(Slot.TRAIN_HDR, 0.048f, 0.62f, 10f, 20f),
        Spec(Slot.DOTS,      0.075f),
        Spec(Slot.GYM_HEAD,  0.070f, 0.72f, 14f, 34f),
        Spec(Slot.GYM_CAP,   0.049f, 0.66f, 11f, 24f)
    )

    class Layout internal constructor(
        val width: Int,
        val height: Int,
        val left: Float,
        val right: Float,
        private val bands: Map<Slot, Band>,
        private val sizes: Map<Slot, Float>
    ) {
        val cx: Float get() = (left + right) / 2f
        val contentW: Float get() = right - left

        /** A band that is not part of this state's stack comes back empty rather than throwing. */
        fun band(slot: Slot): Band = bands[slot] ?: Band(0f, 0f)
        fun textSize(slot: Slot): Float = sizes[slot] ?: 0f

        /** Every band this state uses, in top-to-bottom order. Drives the offline overlap check. */
        fun slots(): List<Slot> = bands.keys.toList()

        val gaugeLeft: Float get() = left + contentW * GAUGE_INSET
        val gaugeRight: Float get() = right - contentW * GAUGE_INSET
        val dividerLeft: Float get() = left + contentW * DIVIDER_INSET
        val dividerRight: Float get() = right - contentW * DIVIDER_INSET

        private val gaugeH: Float get() = band(Slot.GAUGE).height
        val gaugeTrackHalf: Float get() = gaugeH * 0.10f
        val gaugeZoneHalf: Float get() = gaugeH * 0.17f
        val gaugeMarkerR: Float get() = clamp(gaugeH * 0.26f, 5f, 13f)

        /**
         * Dot radius is capped by the dots band as well as by tile width, so the today halo
         * (1.6x this) stays inside the band instead of reaching into the headline below it.
         */
        val dotRadius: Float get() = clamp(min(width * 0.019f, band(Slot.DOTS).height * 0.24f), 5f, 14f)
    }

    /**
     * Build the band stack for one tile.
     *
     * Header and footer take fixed shares so the page chrome does not resize with the body's
     * contents; everything between them is normalised to fill exactly what those two leave. That
     * normalisation is the safety property: the weights need not sum to any particular figure, so
     * retuning a band -- or adding one -- can never overflow the tile or leave a dead strip, which
     * is precisely how a hand-summed table of absolute fractions goes wrong.
     */
    fun compute(widthPx: Int, heightPx: Int, trainingBlock: Boolean, collecting: Boolean): Layout {
        val w = max(widthPx, 320)
        val h = max(heightPx, 320)
        val pad = w * 0.045f
        val padV = pad * 0.55f
        val avail = h - padV * 2f

        val body = ArrayList<Spec>(12)
        body.addAll(if (collecting) COLLECTING else MEASURED)
        if (trainingBlock) body.addAll(TRAINING)

        val headerH = avail * HEADER_SHARE
        val footerH = max(avail * FOOTER_SHARE, FOOTER_MIN)
        val bodyH = max(0f, avail - headerH - footerH)
        var bodyWeight = 0f
        for (s in body) bodyWeight += s.weight

        val bands = LinkedHashMap<Slot, Band>()
        val sizes = LinkedHashMap<Slot, Float>()

        var y = padV

        bands[Slot.HEADER] = Band(y, headerH)
        // Cross-page canonical title size, held to FIT_CAP of the band so it still cannot spill.
        sizes[Slot.HEADER] = min(PageMetrics.titleSize(avail), headerH * FIT_CAP)
        y += headerH

        for (s in body) {
            val bh = if (bodyWeight <= 0f) 0f else bodyH * (s.weight / bodyWeight)
            bands[s.slot] = Band(y, bh)
            if (s.textRatio > 0f) {
                // FIT_CAP is applied LAST, after the legibility clamp, so a min size can never
                // win against it. Applying it first was how the old code failed: a floor bigger
                // than the space available left the glyphs to overflow rather than the type to
                // shrink. On an extreme tile this yields small text, which beats overlapping text.
                sizes[s.slot] = min(clamp(bh * s.textRatio, s.minSize, s.maxSize), bh * FIT_CAP)
            }
            y += bh
        }

        bands[Slot.FOOTER] = Band(y, footerH)

        return Layout(w, h, pad, w - pad, bands, sizes)
    }

    private fun clamp(v: Float, lo: Float, hi: Float) = max(lo, min(hi, v))
}
