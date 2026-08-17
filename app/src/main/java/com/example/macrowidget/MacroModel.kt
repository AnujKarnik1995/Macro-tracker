package com.example.macrowidget

import java.time.LocalDate

/** The four macros, in the same column order as the Log tab. */
enum class MacroType(val label: String, val unit: String, val keywords: List<String>) {
    CALORIES("Cals", "", listOf("cal")),
    PROTEIN("Pro", "g", listOf("prot")),
    CARBS("Carb", "g", listOf("carb")),
    FAT("Fat", "g", listOf("fat"));

    companion object {
        /** Match a Targets-tab row name to a macro by keyword (header text is messy). */
        fun fromName(raw: String): MacroType? {
            val s = raw.lowercase()
            return entries.firstOrNull { m -> m.keywords.any { s.contains(it) } }
        }
    }
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

/** Acceptable weekly weight-loss rate band (lb/week), from the Targets "Weight" row. */
data class WeightTarget(val lowerRate: Float, val upperRate: Float)

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
