package com.example.macrowidget

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.round

/**
 * Empirically back-calculated total daily energy expenditure (TDEE) over a trailing window:
 *
 *     TDEE ≈ avg daily intake − (weight slope in lb/day × 3500)
 *
 * The slope comes from a least-squares regression over the window's weigh-ins, so it doesn't
 * hinge on two noisy endpoints (regression over the raw daily points smooths water/glycogen
 * noise the same way weekly-average endpoints would, without picking an endpoint week).
 *
 * The window excludes **today** — today's intake is still in progress, so it would bias the
 * average low. Only completed days feed the estimate, matching the rest of the widget.
 */
data class TdeeResult(
    val tdee: Float?,        // kcal/day; null while still collecting data
    val avgIntake: Float?,   // kcal/day, window mean of days that logged calories
    val lbPerWeek: Float?,   // measured loss rate over the window (+ = losing)
    val windowDays: Int,     // span actually covered (first..last weigh-in), inclusive
    val weighIns: Int,       // weigh-ins used in the regression
    val intakeDays: Int,     // days with a calorie total
    val collecting: Boolean, // true when below the minimum-data bar
    val daysNeeded: Int      // rough days still needed before ready (0 when ready)
) {
    /** The intake that would produce [ratePerWeek] lb/wk of loss at this TDEE. */
    fun intakeForRate(ratePerWeek: Float): Float? =
        tdee?.let { round(it - ratePerWeek * KCAL_PER_LB / 7f) }

    companion object {
        const val KCAL_PER_LB = 3500f
    }
}

object TdeeCalculator {

    const val KCAL_PER_LB = 3500f

    /**
     * 28 days, not 20 — must match Code.gs TDEE_WINDOW_DAYS.
     *
     * In a 20-day least-squares fit the four edge weigh-ins carry ~58% of the slope and the four
     * middle ones ~3%, so one water-low reading at the window edge moves TDEE by hundreds of kcal.
     * Measured against the 2 Aug 2026 overshoot: the 20-day window read 2443, then 2107 five days
     * later, against a true ~2250 — a 336 kcal round trip on an unchanged body. The 28-day window
     * read 2325 then 2253, swing 72. Exponential weighting was tested and made it worse at every
     * half-life: up-weighting recent points re-creates the endpoint leverage. See ASSUMPTIONS.md.
     */
    const val DEFAULT_WINDOW_DAYS = 28

    // Minimum-data bar before we show a number rather than "collecting".
    private const val MIN_SPAN_DAYS = 14    // at least two weeks of spread between first/last weigh-in
    private const val MIN_WEIGH_INS = 8     // ~4x/week over two weeks
    private const val MIN_INTAKE_DAYS = 10  // enough logged days for a stable intake mean

    /** A day below this fraction of the window's MEDIAN intake is an unfinished log, not a light day. */
    private const val INTAKE_COMPLETE_FRAC = 0.65f

    /**
     * @param windowDays trailing window length (default 28). The window ends *yesterday*
     *        (completed days only) and spans [windowDays] days back from there.
     */
    fun compute(
        entries: List<LogEntry>,
        today: LocalDate = LocalDate.now(),
        windowDays: Int = DEFAULT_WINDOW_DAYS
    ): TdeeResult {
        val end = today.minusDays(1)                                   // exclude today's partial intake
        val start = end.minusDays((windowDays - 1).toLong())
        val inWindow = entries.filter { !it.date.isBefore(start) && !it.date.isAfter(end) }

        val weights = inWindow.mapNotNull { e -> e.weight?.let { e.date to it } }.sortedBy { it.first }
        val rawIntakes = inWindow.mapNotNull { e ->
            e.values[MacroType.CALORIES]?.takeIf { it > 0f }?.let { e.date to it }
        }
        // Drop unfinished logs before the count check, so a half-logged day can't satisfy the bar.
        val intakes = completeIntakes(rawIntakes)

        val weighIns = weights.size
        val intakeDays = intakes.size
        val spanDays = if (weighIns >= 2)
            ChronoUnit.DAYS.between(weights.first().first, weights.last().first).toInt() + 1 else 0

        val ready = weighIns >= MIN_WEIGH_INS && intakeDays >= MIN_INTAKE_DAYS && spanDays >= MIN_SPAN_DAYS
        if (!ready) {
            val need = maxOf(
                MIN_WEIGH_INS - weighIns,
                MIN_INTAKE_DAYS - intakeDays,
                MIN_SPAN_DAYS - spanDays
            ).coerceAtLeast(1)
            return TdeeResult(null, avgOrNull(intakes), null, spanDays, weighIns, intakeDays, true, need)
        }

        val slopePerDay = slopeLbPerDay(weights)                       // lb/day, negative = losing
        val avgIntake = intakes.map { it.second }.average().toFloat()
        val tdee = round(avgIntake - slopePerDay * KCAL_PER_LB)        // whole kcal
        val lbPerWeek = round(-slopePerDay * 7f * 10f) / 10f           // 0.1 lb/wk, + = losing

        return TdeeResult(tdee, round(avgIntake), lbPerWeek, spanDays, weighIns, intakeDays, false, 0)
    }

    private fun avgOrNull(intakes: List<Pair<LocalDate, Float>>): Float? =
        if (intakes.isEmpty()) null else round(intakes.map { it.second }.average().toFloat())

    /**
     * Drops days whose logged calories are implausibly low for a COMPLETE day — a log that was
     * started and abandoned rather than a genuinely light day. Mirrors Code.gs completeIntakes().
     *
     * Judged against the window's own MEDIAN, not a fixed number and deliberately NOT against
     * protein. Protein looks like the obvious signal and is the wrong one: on real data, seven of
     * eight low-protein days (e.g. 2040 kcal at 106 g) were full days of eating badly. Dropping
     * those would bias the intake mean UP and inflate TDEE. Median rather than mean so the outliers
     * cannot move their own threshold.
     */
    private fun completeIntakes(intakes: List<Pair<LocalDate, Float>>): List<Pair<LocalDate, Float>> {
        if (intakes.size < 7) return intakes
        val sorted = intakes.map { it.second }.sorted()
        val mid = sorted.size / 2
        val median = if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
        val kept = intakes.filter { it.second >= INTAKE_COMPLETE_FRAC * median }
        // Never let the filter gut the sample; if it would, trust the raw data instead.
        val floor = maxOf(MIN_INTAKE_DAYS, Math.ceil(intakes.size * 0.6).toInt())
        return if (kept.size >= floor) kept else intakes
    }

    /** Least-squares slope (lb per day) of weight vs day index within the window. */
    private fun slopeLbPerDay(points: List<Pair<LocalDate, Float>>): Float {
        val x0 = points.first().first
        val xs = points.map { ChronoUnit.DAYS.between(x0, it.first).toDouble() }
        val ys = points.map { it.second.toDouble() }
        val mx = xs.average(); val my = ys.average()
        var num = 0.0; var den = 0.0
        for (i in xs.indices) {
            val dx = xs[i] - mx
            num += dx * (ys[i] - my)
            den += dx * dx
        }
        return if (den == 0.0) 0f else (num / den).toFloat()
    }
}
