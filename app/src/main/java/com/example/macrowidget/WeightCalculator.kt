package com.example.macrowidget

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.round

/** One week's consolidated weight. rate = loss vs the previous week (lb, positive = lost). */
data class WeekWeight(
    val end: LocalDate,
    val avg: Float,
    val complete: Boolean,
    val rate: Float?,
    val inZone: Boolean?
)

/** Everything the weight page needs, derived from the daily weigh-ins. */
data class WeightSeries(
    val weeks: List<WeekWeight>,       // chronological, includes the current (in-progress) week
    val currentDailies: List<Float>,   // the current week's daily weigh-ins, in date order
    val targetLow: Float?,             // current-week target band bottom (more loss)
    val targetHigh: Float?,            // current-week target band top (less loss)
    val latest: Float?,                // most recent daily weight
    val totalDelta: Float?,            // latest - first logged (negative = net loss)
    val thisWeekRate: Float?           // prev week avg - current week avg-so-far (positive = losing)
) {
    val hasData: Boolean get() = weeks.isNotEmpty()
}

/** Turns the daily weigh-ins in the log into the weekly-average trend, week-over-week
 *  loss rate, in-zone flags, and the current week's target band. Week = Sunday→Saturday. */
object WeightCalculator {

    fun series(
        entries: List<LogEntry>,
        target: WeightTarget?,
        today: LocalDate = LocalDate.now()
    ): WeightSeries {
        val daily = entries.mapNotNull { e -> e.weight?.let { e.date to it } }
            .sortedBy { it.first }
        if (daily.isEmpty()) return WeightSeries(emptyList(), emptyList(), null, null, null, null, null)

        fun weekStart(d: LocalDate) = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))

        // Average by week; keep each weigh-in's date so we can tell when the week's own
        // Saturday reading has landed.
        val byWeek = LinkedHashMap<LocalDate, MutableList<Pair<LocalDate, Float>>>()
        for ((d, w) in daily) byWeek.getOrPut(weekStart(d)) { mutableListOf() }.add(d to w)
        val starts = byWeek.keys.sorted()

        val avgs = starts.map { s -> s to (byWeek[s]!!.map { it.second }.average().toFloat()) }
        val weeks = ArrayList<WeekWeight>(avgs.size)
        for (i in avgs.indices) {
            val (start, avg) = avgs[i]
            val end = start.plusDays(6)
            // Round the rate to 0.1 lb/wk before the zone check — kills float artifacts
            // (e.g. 188.8-187.9 = 0.90000003) that would otherwise fail a 0.9 upper edge.
            val rate = if (i > 0) round((avgs[i - 1].second - avg) * 10f) / 10f else null
            val inZone = if (target != null && rate != null)
                rate >= target.lowerRate && rate <= target.upperRate else null
            // A week finalizes when the calendar has passed its Saturday, OR the moment that
            // Saturday's own weigh-in is logged — so the current week's point becomes a solid,
            // labeled average as soon as the Saturday reading arrives, without waiting for Sunday.
            // Past weeks that never logged a Saturday still finalize via the date check.
            val hasEndReading = byWeek[start]!!.any { it.first == end }
            val complete = end.isBefore(today) || (!end.isAfter(today) && hasEndReading)
            weeks.add(WeekWeight(end, avg, complete, rate, inZone))
        }

        // Current week (the one containing today) + its target band from the previous week.
        val curStart = weekStart(today)
        val currentDailies = daily.filter { !weekStart(it.first).isBefore(curStart) && it.first <= today }
            .map { it.second }
        val prevAvg = avgs.lastOrNull { it.first.isBefore(curStart) }?.second
        val targetHigh = if (prevAvg != null && target != null) prevAvg - target.lowerRate else null
        val targetLow = if (prevAvg != null && target != null) prevAvg - target.upperRate else null

        val latest = daily.last().second
        val first = daily.first().second
        val curAvg = if (currentDailies.isNotEmpty()) currentDailies.average().toFloat() else null
        val thisWeekRate = if (prevAvg != null && curAvg != null)
            round((prevAvg - curAvg) * 10f) / 10f else null

        return WeightSeries(weeks, currentDailies, targetLow, targetHigh, latest, latest - first, thisWeekRate)
    }
}
