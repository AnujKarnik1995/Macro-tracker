import com.example.macrowidget.*
import java.time.LocalDate

/**
 * Offline check for the weight page's SIGN CONVENTION and DATED BANDS.
 * Pure JVM -- MacroModel/CsvParser/WeightCalculator carry no Android imports.
 *
 *   kotlinc app/src/main/java/com/example/macrowidget/{MacroModel,CsvParser,WeightCalculator}.kt \
 *           backend/test/WeightDriver.kt -include-runtime -d weight.jar
 *   java -jar weight.jar
 *
 * Pins two things the old code got wrong (DESIGN-LOG.md 11):
 *   1. delta is SCALE-SIGNED -- negative means lost. The old `rate` was loss-positive, which forced
 *      a negation in the renderer and crossed targetLow/targetHigh against lower/upper.
 *   2. each week is judged against the band in force when THAT WEEK ended, so changing the band
 *      no longer re-colours history.
 */

var pass = 0
var fail = 0

fun eq(actual: Any?, expected: Any?, label: String) {
    if (actual == expected) { pass++; println("  ok   $label") }
    else { fail++; println("  FAIL $label\n         got      $actual\n         expected $expected") }
}

fun section(s: String) = println("\n$s")

/** For values the pipeline never rounds -- totalDelta and targetLow/High are display-only, unlike
 *  WeekWeight.delta which IS rounded to 0.1 before the zone check. Float32 gives ~1e-5 lb of drift
 *  on a ~200 lb subtraction; asserting exact equality on those would be testing IEEE754. */
fun approx(actual: Float?, expected: Float, label: String, tol: Float = 0.001f) {
    if (actual != null && kotlin.math.abs(actual - expected) <= tol) { pass++; println("  ok   $label") }
    else { fail++; println("  FAIL $label\n         got      $actual\n         expected ~$expected") }
}

fun d(s: String): LocalDate = LocalDate.parse(s)

/** A weigh-in row; macros are irrelevant to the weight page. */
fun w(date: String, lb: Float) = LogEntry(d(date), emptyMap(), lb)

// 2026-08-02 is a Sunday, so these are four clean Sun->Sat weeks.
val ENTRIES = listOf(
    w("2026-08-05", 200.0f),   // wk1 avg 200.0
    w("2026-08-12", 199.0f),   // wk2 avg 199.0  -> delta -1.0
    w("2026-08-19", 198.2f),   // wk3 avg 198.2  -> delta -0.8
    w("2026-08-26", 198.7f),   // wk4 avg 198.7  -> delta +0.5
    w("2026-08-31", 199.2f)    // current week (starts 2026-08-30)
)
val TODAY: LocalDate = d("2026-08-31")   // must not precede the current week's weigh-in

const val HDR = "cal low,cal high,pro low,pro high,carb low,carb high,fat low,fat high," +
    "w_delta lower,w_delta upper,deficit,floor," +
    "cal severity,pro severity,carb severity,fat severity,effective from"

fun wrow(lo: String, hi: String, eff: String) =
    listOf("1625","1750","145","158","160","170","45","50", lo, hi, "", "",
           "mild","mild","mild","danger", eff).joinToString(",")

fun main() {
    section("sign: delta is what the scale did")
    run {
        val h = WeightTargetHistory(listOf(DatedWeightTarget(LocalDate.MIN, WeightTarget(-1.2f, -0.6f))))
        val s = WeightCalculator.series(ENTRIES, h, TODAY)
        val byEnd = s.weeks.associateBy { it.end }
        eq(byEnd[d("2026-08-15")]?.delta, -1.0f, "lost 1.0 lb -> delta -1.0")
        eq(byEnd[d("2026-08-22")]?.delta, -0.8f, "lost 0.8 lb -> delta -0.8")
        eq(byEnd[d("2026-08-29")]?.delta, 0.5f,  "gained 0.5 lb -> delta +0.5")
        eq(byEnd[d("2026-08-08")]?.delta, null,  "first week has no predecessor -> null")
        eq(s.thisWeekDelta, 0.5f, "thisWeekDelta 199.2 vs 198.7 -> +0.5")
        approx(s.totalDelta, -0.8f, "totalDelta 199.2 - 200.0 -> -0.8")

        eq(byEnd[d("2026-08-15")]?.inZone, true,  "-1.0 inside (-1.2,-0.6)")
        eq(byEnd[d("2026-08-22")]?.inZone, true,  "-0.8 inside")
        eq(byEnd[d("2026-08-29")]?.inZone, false, "+0.5 outside a cut band")
    }

    section("targetLow/targetHigh no longer cross over")
    run {
        val h = WeightTargetHistory(listOf(DatedWeightTarget(LocalDate.MIN, WeightTarget(-1.2f, -0.6f))))
        val s = WeightCalculator.series(ENTRIES, h, TODAY)
        // prev week avg = 198.7
        approx(s.targetLow,  197.5f, "targetLow  = prevAvg + lowerDelta (198.7 - 1.2)")
        approx(s.targetHigh, 198.1f, "targetHigh = prevAvg + upperDelta (198.7 - 0.6)")
        eq(s.targetLow!! < s.targetHigh!!, true, "low really is below high")
    }

    section("frozen history: a later band must not re-colour earlier weeks")
    run {
        // Cut until 2026-08-23, then a maintenance band that would flip every cut week's verdict.
        val h = WeightTargetHistory(listOf(
            DatedWeightTarget(LocalDate.MIN,    WeightTarget(-1.2f, -0.6f)),
            DatedWeightTarget(d("2026-08-23"),  WeightTarget(-0.2f,  1.0f))))
        val s = WeightCalculator.series(ENTRIES, h, TODAY)
        val byEnd = s.weeks.associateBy { it.end }
        eq(byEnd[d("2026-08-15")]?.inZone, true, "wk ending 8/15 still judged by the cut band")
        eq(byEnd[d("2026-08-22")]?.inZone, true, "wk ending 8/22 still judged by the cut band")
        eq(byEnd[d("2026-08-29")]?.inZone, true, "wk ending 8/29 judged by maintenance -> +0.5 is fine")
        // Under the old single-band code every one of those used the CURRENT band:
        val old = WeightCalculator.series(ENTRIES,
            WeightTargetHistory(listOf(DatedWeightTarget(LocalDate.MIN, WeightTarget(-0.2f, 1.0f)))), TODAY)
        eq(old.weeks.associateBy { it.end }[d("2026-08-15")]?.inZone, false,
           "  (control) one band everywhere would have failed 8/15")
    }

    section("declared break: a dated row with no band")
    run {
        val h = WeightTargetHistory(listOf(
            DatedWeightTarget(LocalDate.MIN,   WeightTarget(-1.2f, -0.6f)),
            DatedWeightTarget(d("2026-08-23"), null)))
        val s = WeightCalculator.series(ENTRIES, h, TODAY)
        val byEnd = s.weeks.associateBy { it.end }
        eq(byEnd[d("2026-08-22")]?.inZone, true, "week before the break keeps its verdict")
        eq(byEnd[d("2026-08-29")]?.inZone, null, "break week is unjudged, not failed")
        eq(byEnd[d("2026-08-29")]?.delta, 0.5f,  "  delta still measured during a break")
        eq(s.targetLow, null,  "no band -> no current-week target low")
        eq(s.targetHigh, null, "no band -> no current-week target high")
        eq(h.asOf(d("2026-08-25")), null, "asOf inside the break returns null, not the cut band")
        eq(h.asOf(d("2026-08-22")), WeightTarget(-1.2f, -0.6f), "asOf before it still returns the cut band")
    }

    section("parsing: blank w_delta on a dated row is a break, not a skipped row")
    run {
        val csv = listOf(HDR,
            wrow("-0.9", "-0.7", ""),
            wrow("", "", "2026-08-23"),
            wrow("-1.1", "-0.95", "2026-09-01")).joinToString("\n")
        val h = CsvParser.parseWeightTargets(csv)
        eq(h.entries.size, 3, "all three rows emitted, including the blank one")
        eq(h.asOf(d("2026-08-10")), WeightTarget(-0.9f, -0.7f), "epoch 1")
        eq(h.asOf(d("2026-08-25")), null, "blank row wins -> no band (cut band does NOT leak through)")
        eq(h.asOf(d("2026-09-05")), WeightTarget(-1.1f, -0.95f), "epoch 3 resumes")
        eq(CsvParser.parseWeightTargets(HDR).isEmpty, true, "header-only -> EMPTY")
    }

    section("negatives survive the CSV round trip")
    run {
        val csv = listOf(HDR, wrow("-0.95", "-1.10", "2026-08-02")).joinToString("\n")
        eq(CsvParser.parseWeightTargets(csv).asOf(d("2026-08-10")), WeightTarget(-1.10f, -0.95f),
           "transposed negatives normalise (min/max, not abs)")
    }

    println("\n" + (if (fail == 0) "ALL PASS" else "FAILURES: $fail") + "  ($pass assertions)")
    if (fail != 0) kotlin.system.exitProcess(1)
}
