package com.example.macrowidget

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.round

/** One weigh-in inside the current week, kept with its date so the renderer can place it on
 *  its own day-of-week column instead of at an arbitrary jitter offset. */
data class DailyWeight(val date: LocalDate, val lb: Float)

/**
 * One calendar week's consolidated weight.
 *
 * [avg] is null when nothing was logged that week. The week is still emitted so it keeps its
 * slot on the x axis — otherwise a missed week silently closes up and the trend line draws a
 * two-week segment that reads exactly like a one-week one.
 *
 * [rate] = loss vs the *immediately preceding calendar week* (lb, positive = lost). It is null
 * whenever either side of that comparison has no data, which also leaves the dot neutral rather
 * than colouring it off a two-week delta.
 */
data class WeekWeight(
    val end: LocalDate,
    val avg: Float?,
    val complete: Boolean,
    val rate: Float?,
    val inZone: Boolean?
)

/** Everything the weight page needs, derived from the daily weigh-ins. */
data class WeightSeries(
    val weeks: List<WeekWeight>,       // chronological, ONE ENTRY PER CALENDAR WEEK incl. gaps
    val currentDailies: List<DailyWeight>, // the current week's weigh-ins, in date order
    val targetLow: Float?,             // current-week target band bottom (more loss)
    val targetHigh: Float?,            // current-week target band top (less loss)
    val latest: Float?,                // most recent daily weight
    val totalDelta: Float?,            // latest - first logged (negative = net loss)
    val thisWeekRate: Float?           // prev week avg - current week avg-so-far (positive = losing)
) {
    val hasData: Boolean get() = weeks.any { it.avg != null }
}

/** Turns the daily weigh-ins in the log into the weekly-average trend, week-over-week loss rate,
 *  in-zone flags, and the current week's target band. Week = Sunday→Saturday.
 *
 *  The week list is built off the **calendar**, not off the rows that happen to exist, so a week
 *  with no weigh-ins survives as a gap instead of vanishing. */
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

        // Bucket by week; keep each weigh-in's date so we can tell when the week's own Saturday
        // reading has landed, and so the current week's readings can be placed by day.
        val byWeek = HashMap<LocalDate, MutableList<Pair<LocalDate, Float>>>()
        for ((d, w) in daily) byWeek.getOrPut(weekStart(d)) { mutableListOf() }.add(d to w)

        val curStart = weekStart(today)
        val firstStart = weekStart(daily.first().first)
        // Guard against a future-dated row: never stop before the week we're actually in.
        val lastStart = maxOf(curStart, weekStart(daily.last().first))

        val weeks = ArrayList<WeekWeight>()
        var prevAvg: Float? = null
        var s = firstStart
        while (!s.isAfter(lastStart)) {
            val rows = byWeek[s]
            val end = s.plusDays(6)
            val avg = rows?.map { it.second }?.average()?.toFloat()
            // Round the rate to 0.1 lb/wk before the zone check — kills float artifacts
            // (e.g. 188.8-187.9 = 0.90000003) that would otherwise fail a 0.9 upper edge.
            val prev = prevAvg
            val rate = if (avg != null && prev != null) round((prev - avg) * 10f) / 10f else null
            val inZone = if (target != null && rate != null)
                rate >= target.lowerRate && rate <= target.upperRate else null
            // A week finalizes when the calendar has passed its Saturday, OR the moment that
            // Saturday's own weigh-in is logged — so the current week's point becomes a solid,
            // labeled average as soon as the Saturday reading arrives, without waiting for Sunday.
            // Past weeks that never logged a Saturday still finalize via the date check.
            val hasEndReading = rows?.any { it.first == end } == true
            val complete = end.isBefore(today) || (!end.isAfter(today) && hasEndReading)
            weeks.add(WeekWeight(end, avg, complete, rate, inZone))
            // A missed week deliberately breaks the chain: the week after it gets rate = null
            // rather than a two-week delta masquerading as a weekly rate.
            prevAvg = avg
            s = s.plusWeeks(1)
        }

        val currentDailies = (byWeek[curStart] ?: emptyList())
            .filter { it.first <= today }
            .sortedBy { it.first }
            .map { DailyWeight(it.first, it.second) }

        // Baseline for the current week's target band is the *immediately preceding* calendar
        // week. If that week was missed there is no honest baseline, so no band and no rate —
        // better than anchoring the band on a stale average from several weeks back.
        val prevWeekAvg = byWeek[curStart.minusWeeks(1)]?.map { it.second }?.average()?.toFloat()
        val targetHigh = if (prevWeekAvg != null && target != null) prevWeekAvg - target.lowerRate else null
        val targetLow = if (prevWeekAvg != null && target != null) prevWeekAvg - target.upperRate else null

        val latest = daily.last().second
        val first = daily.first().second
        val curAvg = if (currentDailies.isNotEmpty())
            currentDailies.map { it.lb }.average().toFloat() else null
        val thisWeekRate = if (prevWeekAvg != null && curAvg != null)
            round((prevWeekAvg - curAvg) * 10f) / 10f else null

        return WeightSeries(weeks, currentDailies, targetLow, targetHigh, latest, latest - first, thisWeekRate)
    }
}
