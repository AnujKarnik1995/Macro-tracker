import com.example.macrowidget.EnergyLayout
import com.example.macrowidget.EnergyLayout.Slot
import com.example.macrowidget.PageMetrics
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Offline check for EnergyLayout -- the Energy page's vertical geometry.
 * Pure JVM: EnergyLayout and PageMetrics carry no Android imports.
 *
 *   kotlinc app/src/main/java/com/example/macrowidget/{PageMetrics,EnergyLayout}.kt \
 *           backend/test/LayoutDriver.kt -d /tmp/layout
 *   kotlin -cp /tmp/layout LayoutDriverKt
 *
 * What this is for: the page's readability is a geometric property, so it can be asserted rather
 * than eyeballed on a device. The bug this suite exists to prevent shipped for a while and was
 * invisible to every other test -- the numbers were all correct, they were merely drawn on top of
 * each other once a gym plan was configured.
 */

var pass = 0
var fail = 0

fun ok(cond: Boolean, label: String) {
    if (cond) { pass++ } else { fail++; println("  FAIL $label") }
}

fun section(s: String) = println("\n$s")

/** Ink above/below the baseline for the default sans, as fractions of the type size. */
const val INK_ASCENT = 0.72f
const val INK_DESCENT = 0.21f

private val TEXT_SLOTS = setOf(
    Slot.HEADER, Slot.HERO, Slot.HERO_SUB, Slot.AVG_INTAKE, Slot.RATE, Slot.GAUGE_LABELS,
    Slot.ACTION, Slot.MEASURE_TITLE, Slot.MEASURE_SUB, Slot.TRAIN_HDR, Slot.GYM_HEAD, Slot.GYM_CAP
)

/** Top/bottom of what is actually painted for a slot, or null for a spacer. */
fun extent(lay: EnergyLayout.Layout, slot: Slot): Pair<Float, Float>? {
    val band = lay.band(slot)
    if (band.height <= 0f) return null
    if (slot in TEXT_SLOTS) {
        val size = lay.textSize(slot)
        val base = band.baselineFor(size)
        return (base - INK_ASCENT * size) to (base + INK_DESCENT * size)
    }
    return when (slot) {
        Slot.GAUGE -> (band.centerY - lay.gaugeZoneHalf) to (band.centerY + lay.gaugeZoneHalf)
        Slot.DOTS -> {
            val halo = lay.dotRadius * 1.6f          // the today ring, the tallest thing drawn here
            (band.centerY - halo) to (band.centerY + halo)
        }
        Slot.DIVIDER -> band.centerY to band.centerY
        Slot.FOOTER -> {
            val r = min(max(band.height * 0.44f, PageMetrics.REFRESH_MIN_RADIUS), PageMetrics.REFRESH_MAX_RADIUS)
            (band.centerY - r) to (band.centerY + r)
        }
        else -> null                                 // MEASURE_PAD_TOP / MEASURE_PAD_BOT
    }
}

/** Tile sizes worth checking: the enforced floor, realistic tiles, and absurd aspect ratios. */
val SIZES = listOf(
    320 to 320, 300 to 300, 400 to 400, 687 to 687, 687 to 400, 687 to 302,
    768 to 500, 500 to 768, 1000 to 300, 1200 to 320, 320 to 1200
)

fun main() {
    section("bands tile the usable height exactly, in every state")
    for ((w, h) in SIZES) for (training in listOf(true, false)) for (collecting in listOf(true, false)) {
        val lay = EnergyLayout.compute(w, h, training, collecting)
        val slots = lay.slots()
        val pad = lay.width * 0.045f
        val padV = pad * 0.55f
        val first = lay.band(slots.first())
        val last = lay.band(slots.last())
        val tag = "${lay.width}x${lay.height} training=$training collecting=$collecting"

        ok(abs(first.top - padV) < 0.05f, "$tag: stack starts at the top padding")
        ok(abs(last.bottom - (lay.height - padV)) < 0.05f, "$tag: stack ends at the bottom padding")

        // Contiguous: no gaps, no double-booked pixels.
        for (i in 1 until slots.size) {
            val prev = lay.band(slots[i - 1])
            val cur = lay.band(slots[i])
            ok(abs(cur.top - prev.bottom) < 0.05f, "$tag: ${slots[i]} starts where ${slots[i - 1]} ends")
            ok(cur.height > 0f, "$tag: ${slots[i]} has positive height")
        }
    }

    section("nothing drawn leaves its own band")
    for ((w, h) in SIZES) for (training in listOf(true, false)) for (collecting in listOf(true, false)) {
        val lay = EnergyLayout.compute(w, h, training, collecting)
        val tag = "${lay.width}x${lay.height} training=$training collecting=$collecting"
        for (slot in lay.slots()) {
            val e = extent(lay, slot) ?: continue
            val band = lay.band(slot)
            ok(e.first >= band.top - 0.05f, "$tag: $slot ink does not rise above its band")
            ok(e.second <= band.bottom + 0.05f, "$tag: $slot ink does not fall below its band")
        }
    }

    section("no two elements overlap, at any size or aspect ratio")
    for ((w, h) in SIZES) for (training in listOf(true, false)) for (collecting in listOf(true, false)) {
        val lay = EnergyLayout.compute(w, h, training, collecting)
        val tag = "${lay.width}x${lay.height} training=$training collecting=$collecting"
        val drawn = lay.slots().mapNotNull { s -> extent(lay, s)?.let { s to it } }
        for (i in 1 until drawn.size) {
            val (prevSlot, prev) = drawn[i - 1]
            val (curSlot, cur) = drawn[i]
            ok(cur.first >= prev.second, "$tag: $curSlot clears $prevSlot")
        }
    }

    section("the regression: a configured gym plan used to draw the hero through the title")
    run {
        // Before the band budget, the region was multiplied by 0.68 to make room for the training
        // block while the type kept sizing off the full tile. On this exact tile the hero's digits
        // started 7.9px ABOVE the title's baseline. Both must now be clear of each other.
        val lay = EnergyLayout.compute(687, 687, trainingBlock = true, collecting = false)
        val title = extent(lay, Slot.HEADER)!!
        val hero = extent(lay, Slot.HERO)!!
        ok(hero.first > title.second, "hero starts below the title's ink")
        ok(hero.first - title.second > 20f, "and by a visible margin, not a hair")

        // Turning the training block on must not move the header at all: it is fixed chrome.
        val without = EnergyLayout.compute(687, 687, trainingBlock = false, collecting = false)
        ok(abs(lay.band(Slot.HEADER).height - without.band(Slot.HEADER).height) < 0.05f,
            "header height is independent of the training block")
        ok(abs(lay.textSize(Slot.HEADER) - without.textSize(Slot.HEADER)) < 0.05f,
            "header type size is independent of the training block")
    }

    section("header matches the shared cross-page scale")
    for ((w, h) in SIZES) {
        val lay = EnergyLayout.compute(w, h, trainingBlock = true, collecting = false)
        val pad = lay.width * 0.045f
        val avail = lay.height - pad * 0.55f * 2f
        // Equal to the canonical size, or smaller because the band could not hold it -- never larger.
        ok(lay.textSize(Slot.HEADER) <= PageMetrics.titleSize(avail) + 0.05f,
            "${lay.width}x${lay.height}: header never exceeds the shared title scale")
    }

    section("the footer can always contain the refresh button")
    for ((w, h) in SIZES) {
        val lay = EnergyLayout.compute(w, h, trainingBlock = true, collecting = false)
        ok(lay.band(Slot.FOOTER).height >= PageMetrics.REFRESH_MIN_RADIUS * 2f - 0.05f,
            "${lay.width}x${lay.height}: footer band fits the button at its minimum radius")
    }

    println("\n$pass passed, $fail failed")
    if (fail > 0) throw AssertionError("$fail layout assertions failed")
}
