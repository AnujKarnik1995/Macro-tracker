package com.example.macrowidget

import java.time.LocalDate

/** The four macros, in the same column order as the Log tab. */
enum class MacroType(val label: String, val unit: String) {
    CALORIES("Cals", ""),
    PROTEIN("Pro", "g"),
    CARBS("Carb", "g"),
    FAT("Fat", "g")
}

/** A target band for one macro. underDanger = being below lower is dangerous (e.g. fat). */
data class Target(val lower: Float, val upper: Float, val underDanger: Boolean)

/** A macro's target band, tagged with the date it took effect. */
data class DatedTarget(val effectiveFrom: LocalDate, val target: Target)

/**
 * Full history of macro target bands so a day can be judged against the band that was in
 * effect *when it was logged* (freezing past green days when the targets later change).
 * Each macro maps to its dated bands in sheet order. A macro with no dates behaves exactly
 * as before: its single [LocalDate.MIN] entry always applies.
 */
data class TargetHistory(val byMacro: Map<MacroType, List<DatedTarget>>) {

    /**
     * The band in effect for each macro on [date] = the dated row with the greatest
     * effectiveFrom that is not after [date]. On a tie (same date) the later sheet row wins.
     * Macros whose earliest row is after [date] drop out, so they simply aren't checked that
     * day — matching the existing "only macros that have a target are checked" rule.
     */
    fun asOf(date: LocalDate): Map<MacroType, Target> {
        val out = HashMap<MacroType, Target>()
        for ((m, list) in byMacro) {
            var best: DatedTarget? = null
            for (dt in list) {
                if (dt.effectiveFrom.isAfter(date)) continue
                // >= keeps the later sheet row on an exact-date tie (iteration is in sheet order)
                if (best == null || !dt.effectiveFrom.isBefore(best.effectiveFrom)) best = dt
            }
            best?.let { out[m] = it.target }
        }
        return out
    }

    /** The bands in effect today — for drawing today's rings and the render signature. */
    fun current(today: LocalDate): Map<MacroType, Target> = asOf(today)

    val isEmpty: Boolean get() = byMacro.isEmpty()

    companion object { val EMPTY = TargetHistory(emptyMap()) }
}

/**
 * Acceptable weekly weight-CHANGE band (lb/week), from the Targets `w_delta` columns (I-J).
 *
 * SIGN: a DELTA, not a loss rate — **negative means losing**, positive means gaining, matching
 * what the scale does. A cut band is `(-1.10, -0.95)`; a maintenance or break band may straddle
 * zero, e.g. `(-0.5, 1.5)`. The old loss-positive convention forced a negation in the renderer
 * and crossed `targetLow`/`targetHigh` against `lower`/`upper`. DESIGN-LOG.md §11.
 */
data class WeightTarget(val lowerDelta: Float, val upperDelta: Float)

/**
 * A weight-change band tagged with the date it took effect.
 *
 * [target] is NULL for an epoch that deliberately has no band — a declared break or vacation,
 * entered as a dated row with blank `w_delta` cells. That is distinct from having no row at all:
 * a null-band row still WINS the as-of lookup, so the previous band does not leak through it.
 */
data class DatedWeightTarget(val effectiveFrom: LocalDate, val target: WeightTarget?)

/**
 * Full history of weight-change bands, so a week is judged against the band in force when that
 * week ENDED rather than against whatever is current now.
 *
 * Without this, changing the band re-colours every past week on the chart — the same hazard
 * [TargetHistory] exists to prevent for macros, which went unnoticed only because the band had
 * never changed before. DESIGN-LOG.md §11.
 */
data class WeightTargetHistory(val entries: List<DatedWeightTarget>) {

    /** Band in force on [date]: greatest effectiveFrom not after it, later sheet row winning a tie. */
    fun asOf(date: LocalDate): WeightTarget? {
        var best: DatedWeightTarget? = null
        for (e in entries) {
            if (e.effectiveFrom.isAfter(date)) continue
            if (best == null || !e.effectiveFrom.isBefore(best.effectiveFrom)) best = e
        }
        return best?.target
    }

    val isEmpty: Boolean get() = entries.isEmpty()

    companion object { val EMPTY = WeightTargetHistory(emptyList()) }
}

/**
 * One row of the daily Log. `weight` is the day's body weight (lb), null if none logged.
 * `exerciseBurn` is the day's HR-based active/exercise kcal from the watch:
 *   null  = no watch data (not worn / not synced) — treated as missing, no delta,
 *   0f    = worn rest day (a real zero that legitimately tightens the day's target).
 */
data class LogEntry(
    val date: LocalDate,
    val values: Map<MacroType, Float>,
    val weight: Float? = null,
    val exerciseBurn: Float? = null,
    /** Per-day macro target CENTERS from Summary cols I–L (t_cal/t_pro/t_carb/t_fat), or null when
     *  the row has no computed targets — the widget then falls back to the static config bands. */
    val targetCenters: Map<MacroType, Float>? = null,
    /** Strength session logged that day (Summary col M): "A" or "B", null if none.
     *  A checkbox, not a quantity — it never feeds a macro target. Absence IS the miss; there is
     *  no "logged a rest day" value, by design. */
    val gym: String? = null
)

/** Result of the weekly average computation.
 *  [bands] = each macro's per-day effective band averaged across the days in the week — the
 *  reference the weekly rings are coloured against. Averaging the band (not using today's single
 *  band) keeps a low-ceiling floor day from painting the whole week's average out of zone. */
data class WeeklyAverage(
    val values: Map<MacroType, Float>,
    val bands: Map<MacroType, Target>,
    val dayCount: Int,
    val start: LocalDate,
    val end: LocalDate
)
