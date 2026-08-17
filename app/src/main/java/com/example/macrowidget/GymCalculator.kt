package com.example.macrowidget

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Strength-session pacing for the Energy page.
 *
 * The design decision worth knowing: this counts DOWN a fixed budget of sessions to the goal date,
 * and reports the **rate those remaining sessions now demand** — not a streak and not a weekly
 * scoreboard.
 *
 * Why not a weekly count: a calendar week that starts badly is written off by Wednesday, and the
 * counter sits there dead until Monday. A rolling window has no dead weeks — it re-reads every day.
 *
 * Why a required RATE rather than a raw "sessions left": the raw count only falls when you train,
 * so a skipped week looks identical to a trained one until you reach the deadline and discover the
 * shortfall. [requiredPerWeek] rises on its own every day you don't go, which is the point — it is
 * the one number that cannot be improved by logging a session you didn't do, because the divisor
 * shrinks with the calendar whatever you type. Padding the count just moves the shortfall to the
 * goal date, where the mirror reports it instead.
 *
 * Sessions never touch a macro target. See Code.gs GYM_LABELS for why.
 */
data class GymStats(
    /** Sessions logged from planStart through today, inclusive. */
    val done: Int,
    /** Sessions planned for the whole run (config GYM_TOTAL). */
    val total: Int,
    /** Sessions still owed. Never negative — going over budget is not a deficit. */
    val left: Int,
    /** Sessions per week the remainder now demands, or null once the goal date has passed. */
    val requiredPerWeek: Float?,
    /** The pace the plan was set at — the reference [requiredPerWeek] is graded against. */
    val planPerWeek: Float?,
    /** Trained-or-not for the last 7 days, oldest first, [6] = today. */
    val last7: List<Boolean>,
    /** Which session is up next in the A/B rotation. Never null once a plan is configured. */
    val nextSession: String,
    /** Days since the most recent session, or null if none has ever been logged. */
    val daysSinceLast: Int?,
    /** False when no plan is configured (GYM_TOTAL <= 0) — the page then omits the block. */
    val configured: Boolean
) {
    /** Sessions inside the trailing 7 days. */
    val rolling7: Int get() = last7.count { it }

    companion object {
        val NONE = GymStats(0, 0, 0, null, null, List(7) { false }, "A", null, false)
    }
}

object GymCalculator {

    /** Days shown as dots on the page. */
    const val WINDOW_DAYS = 7

    /**
     * Grading thresholds, expressed as a MULTIPLE of the plan's own pace rather than as fixed
     * sessions/week. A hard-coded "3.2 is amber" would silently mean something different the moment
     * the plan changes — at a 2/wk plan 3.2 is a disaster, at 5/wk it is ahead of schedule.
     * At the 3.0/wk plan these work out to ~3.2 and ~4.0.
     */
    const val AMBER_AT = 1.07f
    const val RED_AT = 1.34f

    /**
     * @param planStart first day of the plan; sessions before it are ignored (they belong to the
     *        old 5-day-a-week programme and would inflate `done` against a budget that never
     *        counted them).
     * @param goalDate  the deadline the remaining sessions have to fit before.
     */
    fun compute(
        entries: List<LogEntry>,
        today: LocalDate,
        planStart: LocalDate,
        goalDate: LocalDate,
        total: Int
    ): GymStats {
        if (total <= 0) return GymStats.NONE

        // One pass into a set of trained dates. Summary holds at most one row per date, so a set
        // is already the "two sessions in a day is still 1" rule — no extra guard needed.
        val trained = HashMap<LocalDate, String>()
        for (e in entries) {
            val g = e.gym ?: continue
            if (e.date.isBefore(planStart) || e.date.isAfter(today)) continue
            trained[e.date] = g
        }

        val done = trained.size
        val left = max(0, total - done)

        // Whole days remaining, today included: a session can still be done today, so today is not
        // spent yet. Without the +1 the last day of the plan would report an infinite required rate.
        val daysLeft = ChronoUnit.DAYS.between(today, goalDate).toInt() + 1
        val requiredPerWeek = if (daysLeft <= 0) null else left / (daysLeft / 7f)

        val planDays = ChronoUnit.DAYS.between(planStart, goalDate).toInt() + 1
        val planPerWeek = if (planDays <= 0) null else total / (planDays / 7f)

        val last7 = (0 until WINDOW_DAYS).map { i ->
            trained.containsKey(today.minusDays((WINDOW_DAYS - 1 - i).toLong()))
        }

        val lastDate = trained.keys.maxOrNull()
        val daysSinceLast = lastDate?.let { ChronoUnit.DAYS.between(it, today).toInt() }

        // The rotation is a POINTER, not a weekday: whatever you did last, the other one is next.
        // This is what removes "leg day" as a thing that can be missed — there is no named day to
        // miss, only a next session, and it waits for you however long you take.
        val nextSession = when (lastDate?.let { trained[it] }) {
            "A" -> "B"
            "B" -> "A"
            else -> "A"
        }

        return GymStats(
            done = done,
            total = total,
            left = left,
            requiredPerWeek = requiredPerWeek,
            planPerWeek = planPerWeek,
            last7 = last7,
            nextSession = nextSession,
            daysSinceLast = daysSinceLast,
            configured = true
        )
    }

    /** 0 = on pace, 1 = slipping, 2 = off the rails. Used to pick the colour. */
    fun zone(s: GymStats): Int {
        val req = s.requiredPerWeek ?: return 0
        val plan = s.planPerWeek ?: return 0
        if (s.left == 0) return 0                       // budget met — nothing left to demand
        return when {
            req > plan * RED_AT -> 2
            req > plan * AMBER_AT -> 1
            else -> 0
        }
    }

    /** "16 left · 3.0 / wk", or "done · 22 / 22" once the budget is met. */
    fun headline(s: GymStats): String {
        if (s.left == 0) return "done · ${s.done} / ${s.total}"
        val req = s.requiredPerWeek ?: return "${s.left} left"
        return "${s.left} left · ${fmt1(req)} / wk"
    }

    /** "next · session B", with a nudge appended once it has been a while. */
    fun caption(s: GymStats): String {
        val d = s.daysSinceLast
        return when {
            s.left == 0 -> "budget met · keep going"
            d == null -> "next · session ${s.nextSession}"
            d == 0 -> "done today · next is ${s.nextSession}"
            d >= 4 -> "$d days since last · session ${s.nextSession} is up"
            else -> "next · session ${s.nextSession}"
        }
    }

    private fun fmt1(v: Float): String = String.format("%.1f", (v * 10f).roundToInt() / 10f)
}
