package com.example.macrowidget

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.round

object CsvParser {

    private val dateFormats = listOf(
        DateTimeFormatter.ISO_LOCAL_DATE,            // 2026-06-15
        DateTimeFormatter.ofPattern("M/d/yyyy"),     // 6/15/2026
        DateTimeFormatter.ofPattern("yyyy/M/d"),
        DateTimeFormatter.ofPattern("d/M/yyyy")
    )

    /** Splits a CSV line, respecting double-quoted fields. */
    private fun splitLine(line: String): List<String> {
        val out = ArrayList<String>(); val sb = StringBuilder(); var q = false
        for (c in line) when {
            c == '"' -> q = !q
            c == ',' && !q -> { out.add(sb.toString()); sb.clear() }
            else -> sb.append(c)
        }
        out.add(sb.toString()); return out
    }

    private fun rows(csv: String): List<List<String>> =
        csv.replace("\r\n", "\n").replace("\r", "\n").split("\n")
            .map { it.trim() }.filter { it.isNotEmpty() }
            .map { splitLine(it) }

    private fun num(s: String): Float? =
        s.trim().trim('"').replace(",", "").replace("$", "").toFloatOrNull()

    private fun parseDate(s: String): LocalDate? {
        val t = s.trim().trim('"')
        for (f in dateFormats) try { return LocalDate.parse(t, f) } catch (_: Exception) {}
        return null
    }

    /**
     * Summary tab. Columns by position:
     *   A date, B Cal, C Protein, D Carbs, E Fat, F weight, G basal, H burn,
     *   I t_cal, J t_pro, K t_carb, L t_fat   (per-day target centers, written by Apps Script).
     * First row is assumed to be a header and skipped.
     */
    fun parseLog(csv: String): List<LogEntry> {
        val all = rows(csv)
        if (all.size <= 1) return emptyList()
        val order = listOf(MacroType.CALORIES, MacroType.PROTEIN, MacroType.CARBS, MacroType.FAT)
        return all.drop(1).mapNotNull { cols ->
            if (cols.size < 5) return@mapNotNull null
            val date = parseDate(cols[0]) ?: return@mapNotNull null
            val vals = HashMap<MacroType, Float>()
            // Round each daily total to whole units so the band check compares at gram
            // resolution: kills float artifacts and ignores sub-gram noise (159.9 -> 160).
            order.forEachIndexed { i, m -> vals[m] = round(num(cols[i + 1]) ?: 0f) }
            // Weight (col F) is optional and kept precise (0.1 lb) — the loss band is small.
            val weight = cols.getOrNull(5)?.let { num(it) }?.takeIf { it > 0f }
            // Active/exercise burn (col H) — null when blank (not worn / not posted).
            val burn = cols.getOrNull(7)?.let { num(it) }
            // Per-day target centers (cols I–L). Null map when none present → static-band fallback.
            val centers = HashMap<MacroType, Float>()
            cols.getOrNull(8)?.let { num(it) }?.let { centers[MacroType.CALORIES] = it }
            cols.getOrNull(9)?.let { num(it) }?.let { centers[MacroType.PROTEIN] = it }
            cols.getOrNull(10)?.let { num(it) }?.let { centers[MacroType.CARBS] = it }
            cols.getOrNull(11)?.let { num(it) }?.let { centers[MacroType.FAT] = it }
            LogEntry(date, vals, weight, burn, if (centers.isEmpty()) null else centers)
        }
    }

    /** Weekly weight-loss target band (lb/week), from the Targets row whose name contains
     *  "weight" (e.g. "Weight Loss, 0.7, 0.9"). Null if there's no such row. */
    fun parseWeightTarget(csv: String): WeightTarget? {
        val all = rows(csv)
        if (all.size <= 1) return null
        for (cols in all.drop(1)) {
            if (cols.size < 3) continue
            if (!cols[0].lowercase().contains("weight")) continue
            val lo = num(cols[1]) ?: continue
            val up = num(cols[2]) ?: continue
            return WeightTarget(minOf(lo, up), maxOf(lo, up))
        }
        return null
    }

    /**
     * Targets tab. Columns by position: Macro, Lower, Upper, UnderSeverity(optional),
     * EffectiveFrom(optional, col E). Header row skipped. Rows matched to macros by keyword,
     * so order is flexible, and a macro may appear on multiple rows with different
     * EffectiveFrom dates — each day is later judged against the band in effect on that day.
     * A row with no (or unparseable) EffectiveFrom defaults to [LocalDate.MIN], i.e. it always
     * applies, so a sheet without the column behaves exactly as the old single-band version.
     */
    fun parseTargets(csv: String): TargetHistory {
        val all = rows(csv)
        if (all.size <= 1) return TargetHistory.EMPTY
        val map = LinkedHashMap<MacroType, MutableList<DatedTarget>>()
        for (cols in all.drop(1)) {
            if (cols.size < 3) continue
            val macro = MacroType.fromName(cols[0]) ?: continue
            val lower = num(cols[1]) ?: continue
            val upper = num(cols[2]) ?: continue
            val danger = cols.getOrNull(3)?.lowercase()?.contains("danger") == true
            val eff = cols.getOrNull(4)?.let { parseDate(it) } ?: LocalDate.MIN
            val target = Target(minOf(lower, upper), maxOf(lower, upper), danger)
            map.getOrPut(macro) { mutableListOf() }.add(DatedTarget(eff, target))
        }
        return TargetHistory(map)
    }
}
