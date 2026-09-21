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

    /** One macro's three columns on the wide Targets row. */
    private data class MacroCols(val macro: MacroType, val lo: Int, val hi: Int, val sev: Int)

    /**
     * Targets tab is ONE WIDE ROW PER CONFIG EPOCH, fixed by position (no name column, no keyword
     * matching). Must stay in step with Code.gs `TG`:
     *
     *   A-B cal lo/hi   C-D pro lo/hi   E-F carb lo/hi   G-H fat lo/hi
     *   I-J weight-loss lo/hi           K deficit        L floor
     *   M-P under-severity (cal/pro/carb/fat)            Q effective from
     */
    private val MACRO_COLS = listOf(
        MacroCols(MacroType.CALORIES, 0, 1, 12),
        MacroCols(MacroType.PROTEIN,  2, 3, 13),
        MacroCols(MacroType.CARBS,    4, 5, 14),
        MacroCols(MacroType.FAT,      6, 7, 15)
    )
    private const val TG_WL_LO = 8
    private const val TG_WL_HI = 9
    private const val TG_EFFECTIVE_FROM = 16

    /**
     * Summary tab. Columns by position:
     *   A date, B Cal, C Protein, D Carbs, E Fat, F weight, G unused (always blank), H burn,
     *   I t_cal, J t_pro, K t_carb, L t_fat   (per-day target centers, written by Apps Script),
     *   M gym  ("A"/"B" when a strength session was logged that day, blank otherwise).
     * First row is assumed to be a header and skipped.
     *
     * Every column past E is read with getOrNull, so a sheet published before the gym column
     * existed still parses — those rows simply come back with gym = null.
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
            // Training burn (col H) — null when blank; treated as a 0 rest day downstream.
            val burn = cols.getOrNull(7)?.let { num(it) }
            // Per-day target centers (cols I–L). Null map when none present → static-band fallback.
            val centers = HashMap<MacroType, Float>()
            cols.getOrNull(8)?.let { num(it) }?.let { centers[MacroType.CALORIES] = it }
            cols.getOrNull(9)?.let { num(it) }?.let { centers[MacroType.PROTEIN] = it }
            cols.getOrNull(10)?.let { num(it) }?.let { centers[MacroType.CARBS] = it }
            cols.getOrNull(11)?.let { num(it) }?.let { centers[MacroType.FAT] = it }
            // Gym session label (col M). Anything non-blank counts as a session; an unrecognised
            // label still counts as trained and just falls back to "A" for the rotation pointer.
            val gym = cols.getOrNull(12)?.trim()?.trim('"')?.uppercase()
                ?.takeIf { it.isNotEmpty() }
                ?.let { if (it == "B") "B" else "A" }
            LogEntry(date, vals, weight, burn, if (centers.isEmpty()) null else centers, gym)
        }
    }

    /**
     * Weight-change bands from cols I-J, one per config epoch, tagged with the row's Effective
     * From (col Q). Kept in sheet order so [WeightTargetHistory.asOf]'s exact-date tie-break
     * (later sheet row wins) matches [TargetHistory.asOf].
     *
     * Returns the whole history, not one band: the weight page judges each week against the band
     * in force when that week ended. DESIGN-LOG.md §11.
     */
    fun parseWeightTargets(csv: String): WeightTargetHistory {
        val all = rows(csv)
        if (all.size <= 1) return WeightTargetHistory.EMPTY
        val out = ArrayList<DatedWeightTarget>()
        for (cols in all.drop(1)) {
            val eff = cols.getOrNull(TG_EFFECTIVE_FROM)?.let { parseDate(it) } ?: LocalDate.MIN
            val lo = cols.getOrNull(TG_WL_LO)?.let { num(it) }
            val up = cols.getOrNull(TG_WL_HI)?.let { num(it) }
            // Blank w_delta on a dated row is a DECLARED BREAK, not a row to skip: it is emitted
            // with a null band so it still wins the as-of lookup and the previous band cannot leak
            // through. Skipping it here would silently apply the cut's band to a vacation.
            val band = if (lo != null && up != null) WeightTarget(minOf(lo, up), maxOf(lo, up)) else null
            out.add(DatedWeightTarget(eff, band))
        }
        return WeightTargetHistory(out)
    }

    /**
     * Targets tab → full band history. Each row is a COMPLETE config snapshot read by position
     * ([MACRO_COLS]), contributing one [DatedTarget] per macro, all sharing that row's Effective
     * From (col Q). A row with no (or unparseable) date defaults to [LocalDate.MIN] and therefore
     * always applies — that is what keeps days logged before the first dated epoch judged against
     * bands instead of silently dropping out of the success check.
     *
     * Rows are appended in sheet order, so [TargetHistory.asOf]'s exact-date tie-break (later sheet
     * row wins) still holds. A macro whose lo/hi cells are blank on a row is skipped for that row
     * only, so a partially filled epoch degrades per-macro rather than failing the whole row.
     */
    fun parseTargets(csv: String): TargetHistory {
        val all = rows(csv)
        if (all.size <= 1) return TargetHistory.EMPTY
        val map = LinkedHashMap<MacroType, MutableList<DatedTarget>>()
        for (cols in all.drop(1)) {
            val eff = cols.getOrNull(TG_EFFECTIVE_FROM)?.let { parseDate(it) } ?: LocalDate.MIN
            for (m in MACRO_COLS) {
                val lower = cols.getOrNull(m.lo)?.let { num(it) } ?: continue
                val upper = cols.getOrNull(m.hi)?.let { num(it) } ?: continue
                val danger = cols.getOrNull(m.sev)?.lowercase()?.contains("danger") == true
                val target = Target(minOf(lower, upper), maxOf(lower, upper), danger)
                map.getOrPut(m.macro) { mutableListOf() }.add(DatedTarget(eff, target))
            }
        }
        return TargetHistory(map)
    }
}
