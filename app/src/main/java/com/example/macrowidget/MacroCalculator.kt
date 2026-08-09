package com.example.macrowidget

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Pulls "today" and the running weekly average out of the parsed log. */
object MacroCalculator {

    /**
     * Macros that must be in band for a day to count as "successful".
     * Calories are intentionally excluded (allowed out of band).
     */
    private val SUCCESS_MACROS = listOf(MacroType.PROTEIN, MacroType.CARBS, MacroType.FAT)

    /** Today's row, or null if nothing logged yet today. */
    fun today(entries: List<LogEntry>, today: LocalDate = LocalDate.now()): LogEntry? =
        entries.firstOrNull { it.date == today }

    /**
     * Is this logged day "successful"? Protein, Carbs and Fat must all sit inside
     * their band; Calories are ignored. Only macros that actually have a target are
     * checked, and at least one must be present.
     */
    fun dayIsSuccessful(entry: LogEntry, targets: Map<MacroType, Target>): Boolean {
        val checked = SUCCESS_MACROS.mapNotNull { m ->
            val t = targets[m] ?: return@mapNotNull null
            val v = entry.values[m] ?: return@mapNotNull null
            v >= t.lower && v <= t.upper
        }
        return checked.isNotEmpty() && checked.all { it }
    }

    /**
     * The effective band for each macro on [entry]'s day. If the row carries a per-day target
     * center (Summary I–L), the band is that center ± the config band's half-width; otherwise it
     * falls back to the static dated band ([history].asOf). This is how the sliding carb band takes
     * effect while protein/fat stay put (their centers equal their static midpoints) and days with
     * no computed targets keep the old static behavior.
     */
    fun effectiveTargets(entry: LogEntry, history: TargetHistory): Map<MacroType, Target> {
        val base = history.asOf(entry.date)
        val centers = entry.targetCenters ?: return base
        val out = HashMap<MacroType, Target>()
        for ((m, t) in base) {
            val c = centers[m]
            out[m] = if (c != null) {
                val hw = (t.upper - t.lower) / 2f
                Target(c - hw, c + hw, t.underDanger)
            } else t
        }
        return out
    }

    /**
     * Total tally of successful days across the whole log (up to and including today).
     * Each day is judged against its *effective* band ([effectiveTargets]) — the per-day center
     * where present, else the static dated band — so past green days don't move when config changes,
     * and the sliding carb band is respected. Unlogged days aren't in [entries], so they're ignored.
     */
    fun successfulDays(
        entries: List<LogEntry>,
        history: TargetHistory,
        today: LocalDate = LocalDate.now()
    ): Int = entries.count { !it.date.isAfter(today) && dayIsSuccessful(it, effectiveTargets(it, history)) }

    /**
     * Average over the current week (most recent Sunday through today, inclusive),
     * counting only dates that actually have a row. Today's running total is
     * included. Missing days simply drop out of the divisor.
     */
    fun weeklyAverage(
        entries: List<LogEntry>,
        history: TargetHistory,
        today: LocalDate = LocalDate.now()
    ): WeeklyAverage? {
        val start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
        val inWeek = entries.filter { !it.date.isBefore(start) && !it.date.isAfter(today) }
        if (inWeek.isEmpty()) return null
        val avg = HashMap<MacroType, Float>()
        for (m in MacroType.entries) {
            avg[m] = inWeek.map { it.values[m] ?: 0f }.average().toFloat()
        }
        // Weekly ring band = mean of each macro's per-day EFFECTIVE band across the in-week days.
        // A day contributes a macro only if it had a band that day; a macro no day banded simply
        // drops out and its ring shows neutral (same as an absent target). This is what makes the
        // rings track the sliding TDEE targets instead of today's single (possibly floor-day) band.
        val loSum = HashMap<MacroType, Float>(); val hiSum = HashMap<MacroType, Float>()
        val cnt = HashMap<MacroType, Int>(); val danger = HashMap<MacroType, Boolean>()
        for (e in inWeek) {
            for ((m, t) in effectiveTargets(e, history)) {
                loSum[m] = (loSum[m] ?: 0f) + t.lower
                hiSum[m] = (hiSum[m] ?: 0f) + t.upper
                cnt[m] = (cnt[m] ?: 0) + 1
                danger[m] = t.underDanger
            }
        }
        val bands = HashMap<MacroType, Target>()
        for (m in cnt.keys) {
            val n = cnt[m]!!
            bands[m] = Target(loSum[m]!! / n, hiSum[m]!! / n, danger[m] ?: false)
        }
        return WeeklyAverage(avg, bands, inWeek.size, start, today)
    }
}
