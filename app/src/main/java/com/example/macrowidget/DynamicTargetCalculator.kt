package com.example.macrowidget

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Rules/config for the dynamic constant-deficit target. Inputs (not per-day): the deficit to
 * hold, the intake floor (≈ current sheet calorie limit — the anti-starve safety), the fixed
 * protein & fat bands (unchanged day to day), the carb band's half-width (its center is what
 * slides), plus tuning knobs.
 */
data class DeficitConfig(
    val targetDeficit: Float = 425f,   // kcal/day deficit to hold (400–450 band midpoint)
    val calorieFloor: Float,           // calorie target never goes below this
    val protein: Target,               // fixed two-sided band
    val fat: Target,                   // fixed two-sided band
    val carbHalfWidth: Float,          // half-width of the sliding carb band (from your current carb band)
    val windowDays: Int = 20,          // "typical exercise" baseline window (matches TDEE)
    val outlierKcal: Float = 400f      // flag when the day's calorie target strays this far from baseline
)

/**
 * The day's limits. Protein and fat are the fixed bands passed straight through; only the carb
 * band moves. Calories is the computed anchor that positions the carb band — shown for context,
 * NOT a success gate (success stays the current protein/carbs/fat band check).
 */
data class DayTarget(
    val calorieAnchor: Int,   // TDEE + delta − deficit, floored — display/context only
    val protein: Target,      // fixed band (pass-through)
    val fat: Target,          // fixed band (pass-through)
    val carbs: Target,        // sliding band: [center − halfWidth, center + halfWidth]
    val carbCenter: Int,
    val workoutDelta: Int,       // today − typical training burn (negative on a rest day)
    val noTrainingLogged: Boolean, // nothing logged today → treated as a 0 kcal rest day
    val outlier: Boolean         // calorie anchor strayed far from baseline → worth a sanity check
)

/**
 * Turns measured TDEE + today's watch burn into the day's carb band, holding a constant deficit.
 * Same-day, no carryover.
 *
 *   calorie anchor = TDEE + (today's exercise − typical exercise) − deficit,  floored
 *   carb center    = (calorie anchor − 4·protein_mid − 9·fat_mid) / 4         (carbs are the plug)
 *
 * "typical exercise" is the ALL-DAYS average over the window — a day with no training logged is a
 * rest day worth 0, not a day to skip. That keeps the delta consistent with TDEE (itself an
 * all-days average) and makes avg(delta) = 0 across the window, so the workout flex can't quietly
 * erode the deficit. Averaging only the LOGGED days (the old behaviour) made a rest day score
 * better than a light session: baseline 300 gave rest -> 0 but a 250 kcal session -> -50.
 * Protein and fat bands never move; carbs absorbs the flex. Calories is derived, never gated.
 * Mirrors Code.gs typicalBurn/readBurn — the two must agree or the delta compares different things.
 */
object DynamicTargetCalculator {

    /** All-days average training burn over the trailing window (excludes today; blank = 0). */
    fun typicalBurn(
        entries: List<LogEntry>,
        today: LocalDate = LocalDate.now(),
        windowDays: Int = 20
    ): Float {
        val end = today.minusDays(1)
        val start = end.minusDays((windowDays - 1).toLong())
        val days = entries.filter { !it.date.isBefore(start) && !it.date.isAfter(end) }
        if (days.isEmpty()) return 0f
        return days.map { it.exerciseBurn ?: 0f }.average().toFloat()   // blank = rest day = 0
    }

    /**
     * The day's limits. Returns null when [tdee] isn't available yet (still collecting) — the
     * widget should fall back to the static sheet bands in that case.
     *
     * @param todayBurn today's training burn; null/blank = nothing logged = a 0 kcal rest day.
     */
    fun targetForDay(
        tdee: Float?,
        todayBurn: Float?,
        typicalBurn: Float,
        cfg: DeficitConfig
    ): DayTarget? {
        if (tdee == null) return null

        // Blank is a real 0, not "unknown": both sides of the delta are now all-days figures.
        val delta = (todayBurn ?: 0f) - typicalBurn

        val anchor = max(tdee + delta - cfg.targetDeficit, cfg.calorieFloor)

        // Carbs fill what's left of the calorie anchor after aiming for the middle of the fixed
        // protein/fat bands; the band slides with the anchor, keeping the width you use today.
        val pMid = (cfg.protein.lower + cfg.protein.upper) / 2f
        val fMid = (cfg.fat.lower + cfg.fat.upper) / 2f
        val carbCenter = max(0f, (anchor - 4f * pMid - 9f * fMid) / 4f)
        val carbLo = max(0f, carbCenter - cfg.carbHalfWidth)
        val carbHi = carbCenter + cfg.carbHalfWidth

        val baseline = max(tdee - cfg.targetDeficit, cfg.calorieFloor)
        val outlier = abs(anchor - baseline) > cfg.outlierKcal

        return DayTarget(
            calorieAnchor = anchor.roundToInt(),
            protein = cfg.protein,
            fat = cfg.fat,
            carbs = Target(carbLo.roundToInt().toFloat(), carbHi.roundToInt().toFloat(), underDanger = false),
            carbCenter = carbCenter.roundToInt(),
            workoutDelta = delta.roundToInt(),
            noTrainingLogged = todayBurn == null,
            outlier = outlier
        )
    }
}
