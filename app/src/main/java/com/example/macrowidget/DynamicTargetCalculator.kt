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
    val workoutDelta: Int,    // today − typical exercise burn
    val usedFallback: Boolean, // watch data missing → no delta applied
    val outlier: Boolean       // calorie anchor strayed far from baseline → worth a sanity check
)

/**
 * Turns measured TDEE + today's watch burn into the day's carb band, holding a constant deficit.
 * Same-day, no carryover.
 *
 *   calorie anchor = TDEE + (today's exercise − typical exercise) − deficit,  floored
 *   carb center    = (calorie anchor − 4·protein_mid − 9·fat_mid) / 4         (carbs are the plug)
 *
 * "typical exercise" is the all-worn-days average over the window (rest days count as their real
 * 0; not-worn days excluded), keeping the delta consistent with TDEE — itself an all-days average.
 * Protein and fat bands never move; carbs absorbs the flex. Calories is derived, never gated.
 */
object DynamicTargetCalculator {

    /** All-worn-days average exercise burn over the trailing window (excludes today). */
    fun typicalBurn(
        entries: List<LogEntry>,
        today: LocalDate = LocalDate.now(),
        windowDays: Int = 20
    ): Float {
        val end = today.minusDays(1)
        val start = end.minusDays((windowDays - 1).toLong())
        val worn = entries
            .filter { !it.date.isBefore(start) && !it.date.isAfter(end) }
            .mapNotNull { it.exerciseBurn }          // null = not worn → excluded; 0f = rest → kept
        return if (worn.isEmpty()) 0f else worn.average().toFloat()
    }

    /**
     * The day's limits. Returns null when [tdee] isn't available yet (still collecting) — the
     * widget should fall back to the static sheet bands in that case.
     *
     * @param todayBurn today's HR exercise burn; null = watch data missing (no delta, fallback).
     */
    fun targetForDay(
        tdee: Float?,
        todayBurn: Float?,
        typicalBurn: Float,
        cfg: DeficitConfig
    ): DayTarget? {
        if (tdee == null) return null

        val worn = todayBurn != null
        val delta = if (worn) todayBurn!! - typicalBurn else 0f     // missing → no adjustment

        val anchor = max(tdee + delta - cfg.targetDeficit, cfg.calorieFloor)

        // Carbs fill what's left of the calorie anchor after aiming for the middle of the fixed
        // protein/fat bands; the band slides with the anchor, keeping the width you use today.
        val pMid = (cfg.protein.lower + cfg.protein.upper) / 2f
        val fMid = (cfg.fat.lower + cfg.fat.upper) / 2f
        val carbCenter = max(0f, (anchor - 4f * pMid - 9f * fMid) / 4f)
        val carbLo = max(0f, carbCenter - cfg.carbHalfWidth)
        val carbHi = carbCenter + cfg.carbHalfWidth

        val baseline = max(tdee - cfg.targetDeficit, cfg.calorieFloor)
        val outlier = worn && abs(anchor - baseline) > cfg.outlierKcal

        return DayTarget(
            calorieAnchor = anchor.roundToInt(),
            protein = cfg.protein,
            fat = cfg.fat,
            carbs = Target(carbLo.roundToInt().toFloat(), carbHi.roundToInt().toFloat(), underDanger = false),
            carbCenter = carbCenter.roundToInt(),
            workoutDelta = delta.roundToInt(),
            usedFallback = !worn,
            outlier = outlier
        )
    }
}
