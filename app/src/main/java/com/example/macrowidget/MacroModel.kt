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

/** Acceptable weekly weight-loss rate band (lb/week), from the Targets "Weight" row. */
data class WeightTarget(val lowerRate: Float, val upperRate: Float)

/** One row of the daily Log. `weight` is the day's body weight (lb), null if none logged. */
data class LogEntry(
    val date: LocalDate,
    val values: Map<MacroType, Float>,
    val weight: Float? = null
)

/** Result of the weekly average computation. */
data class WeeklyAverage(
    val values: Map<MacroType, Float>,
    val dayCount: Int,
    val start: LocalDate,
    val end: LocalDate
)
