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
 * [delta] = change vs the *immediately preceding calendar week* (lb). **Negative = lost**, matching
 * the scale. Null whenever either side of that comparison has no data, which also leaves the dot
 * neutral rather than colouring it off a two-week gap.
 *
 * [inZone] is judged against the band in force when THIS week ended, not the current band — so
 * changing the target does not re-colour history. DESIGN-LOG.md §11.
 */
data class WeekWeight(
    val end: LocalDate,
    val avg: Float?,
    val complete: Boolean,
    val delta: Float?,
    val inZone: Boolean?
)

/** Everything the weight page needs, derived from the daily weigh-ins. */
data class WeightSeries(
    val weeks: List<WeekWeight>,       // chronological, ONE ENTRY PER CALENDAR WEEK incl. gaps
    val currentDailies: List<DailyWeight>, // the current week's weigh-ins, in date order
    val targetLow: Float?,             // current-week band bottom = prevWeekAvg + lowerDelta
    val targetHigh: Float?,            // current-week band top    = prevWeekAvg + upperDelta
    val latest: Float?,                // most recent daily weight
    val totalDelta: Float?,            // latest - first logged (negative = net loss)
    val thisWeekDelta: Float?          // current week avg-so-far - prev week avg (negative = losing)
) {
    val hasData: Boolean get() = weeks.any { it.avg != null }
}

/** Turns the daily weigh-ins in the log into the weekly-average trend, week-over-week loss rate,
 *  in-zone flags, and the current week's target band. Week = Sunday→Saturday.
 *  Deltas are scale-signed: negative = lost. Each week is judged against the band in force on its
 *  own end date, via [WeightTargetHistory.asOf].
 *
 *  The week list is built off the **calendar**, not off the rows that happen to exist, so a week
 *  with no weigh-ins survives as a gap instead of vanishing. */
object WeightCalculator {

    fun series(
        entries: List<LogEntry>,
        history: WeightTargetHistory?,
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
            // Round the delta to 0.1 lb/wk before the zone check — kills float artifacts
            // (e.g. 187.9-188.8 = -0.90000003) that would otherwise fail a -0.9 edge.
            val prev = prevAvg
            val delta = if (avg != null && prev != null) round((avg - prev) * 10f) / 10f else null
            // The band in force when this week ENDED, so a later config change cannot re-colour it.
            val band = history?.asOf(end)
            val inZone = if (band != null && delta != null)
                delta >= band.lowerDelta && delta <= band.upperDelta else null
            // A week finalizes when the calendar has passed its Saturday, OR the moment that
            // Saturday's own weigh-in is logged — so the current week's point becomes a solid,
            // labeled average as soon as the Saturday reading arrives, without waiting for Sunday.
            // Past weeks that never logged a Saturday still finalize via the date check.
            val hasEndReading = rows?.any { it.first == end } == true
            val complete = end.isBefore(today) || (!end.isAfter(today) && hasEndReading)
            weeks.add(WeekWeight(end, avg, complete, delta, inZone))
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
        // No crossover now: lowerDelta is the more negative bound, so it maps to the LOWER weight.
        val curBand = history?.asOf(curStart.plusDays(6))
        val targetLow = if (prevWeekAvg != null && curBand != null) prevWeekAvg + curBand.lowerDelta else null
        val targetHigh = if (prevWeekAvg != null && curBand != null) prevWeekAvg + curBand.upperDelta else null

        val latest = daily.last().second
        val first = daily.first().second
        val curAvg = if (currentDailies.isNotEmpty())
            currentDailies.map { it.lb }.average().toFloat() else null
        val thisWeekDelta = if (prevWeekAvg != null && curAvg != null)
            round((curAvg - prevWeekAvg) * 10f) / 10f else null

        return WeightSeries(weeks, currentDailies, targetLow, targetHigh, latest, latest - first, thisWeekDelta)
    }
}
