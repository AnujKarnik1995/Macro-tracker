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
     * Total tally of successful days across the whole log (up to and including today).
     * Each day is judged against the target band that was in effect *on that day*
     * ([history].asOf(date)), so past green days don't move when the targets later change.
     * Unlogged days simply aren't in [entries], so they're ignored rather than counted.
     */
    fun successfulDays(
        entries: List<LogEntry>,
        history: TargetHistory,
        today: LocalDate = LocalDate.now()
    ): Int = entries.count { !it.date.isAfter(today) && dayIsSuccessful(it, history.asOf(it.date)) }

    /**
     * Average over the current week (most recent Sunday through today, inclusive),
     * counting only dates that actually have a row. Today's running total is
     * included. Missing days simply drop out of the divisor.
     */
    fun weeklyAverage(entries: List<LogEntry>, today: LocalDate = LocalDate.now()): WeeklyAverage? {
        val start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
        val inWeek = entries.filter { !it.date.isBefore(start) && !it.date.isAfter(today) }
        if (inWeek.isEmpty()) return null
        val avg = HashMap<MacroType, Float>()
        for (m in MacroType.entries) {
            avg[m] = inWeek.map { it.values[m] ?: 0f }.average().toFloat()
        }
        return WeeklyAverage(avg, inWeek.size, start, today)
    }
}
