import com.example.macrowidget.*
import java.time.LocalDate

/**
 * Offline check for the WIDE Targets format: CsvParser.parseTargets + parseWeightTargets.
 * Pure JVM -- MacroModel/CsvParser carry no Android imports.
 *
 *   kotlinc app/src/main/java/com/example/macrowidget/{MacroModel,CsvParser}.kt \
 *           backend/test/TargetsDriver.kt -include-runtime -d targets.jar
 *   java -jar targets.jar
 *
 * Covers what row-per-macro never could: one row is a COMPLETE config snapshot, so as-of resolution
 * has to pick a ROW rather than merge independently-dated per-macro rows. DESIGN-LOG.md 14.
 */

var pass = 0
var fail = 0

fun eq(actual: Any?, expected: Any?, label: String) {
    if (actual == expected) { pass++; println("  ok   $label") }
    else { fail++; println("  FAIL $label\n         got      $actual\n         expected $expected") }
}

fun section(s: String) = println("\n$s")

const val HDR = "cal low,cal high,pro low,pro high,carb low,carb high,fat low,fat high," +
    "w_delta lower,w_delta upper,deficit,floor," +
    "cal severity,pro severity,carb severity,fat severity,effective from"

/** One wide row. Severities default to the live shape: fat is the only `danger`. */
fun row(cal: Pair<String, String>, pro: Pair<String, String>, carb: Pair<String, String>,
        fat: Pair<String, String>, wl: Pair<String, String>,
        deficit: String = "", floor: String = "", eff: String = "",
        sev: List<String> = listOf("mild", "mild", "mild", "danger")) =
    listOf(cal.first, cal.second, pro.first, pro.second, carb.first, carb.second,
           fat.first, fat.second, wl.first, wl.second, deficit, floor,
           sev[0], sev[1], sev[2], sev[3], eff).joinToString(",")

fun csv(vararg rows: String) = (listOf(HDR) + rows.toList()).joinToString("\n")

fun d(s: String): LocalDate = LocalDate.parse(s)

fun main() {
    section("single undated row -- the pre-history shape")
    run {
        val c = csv(row("1200" to "2000", "120" to "150", "150" to "220", "40" to "55", "0.7" to "0.9"))
        val h = CsvParser.parseTargets(c)
        val t = h.asOf(d("2026-08-09"))
        eq(t[MacroType.CALORIES], Target(1200f, 2000f, false), "calorie band")
        eq(t[MacroType.PROTEIN],  Target(120f, 150f, false),   "protein band")
        eq(t[MacroType.CARBS],    Target(150f, 220f, false),   "carb band (supplies the half-width)")
        eq(t[MacroType.FAT],      Target(40f, 55f, true),      "fat band, danger set")
        eq(h.asOf(d("1999-01-01")).size, 4, "undated row applies to ANY date (far past)")
        eq(h.asOf(d("2099-01-01")).size, 4, "  ...and far future")
    }

    section("as-of picks a ROW, not a merge of per-macro rows")
    run {
        val c = csv(
            row("1200" to "2000", "120" to "150", "150" to "220", "40" to "55", "0.7" to "0.9"),
            row("1300" to "1900", "130" to "155", "160" to "210", "42" to "52", "0.8" to "1.0",
                "425", "1625", "2026-08-02"),
            row("1350" to "1850", "135" to "158", "165" to "205", "44" to "50", "0.6" to "0.8",
                "375", "1625", "2026-08-09"))
        val h = CsvParser.parseTargets(c)
        eq(h.asOf(d("2026-08-01"))[MacroType.PROTEIN], Target(120f, 150f, false), "before epoch 1 -> undated row")
        eq(h.asOf(d("2026-08-02"))[MacroType.PROTEIN], Target(130f, 155f, false), "on epoch 1 boundary")
        eq(h.asOf(d("2026-08-08"))[MacroType.PROTEIN], Target(130f, 155f, false), "inside epoch 1")
        eq(h.asOf(d("2026-08-09"))[MacroType.PROTEIN], Target(135f, 158f, false), "on epoch 2 boundary")
        eq(h.asOf(d("2026-12-31"))[MacroType.CARBS],   Target(165f, 205f, false), "after epoch 2, carbs move too")
        eq(h.asOf(d("2026-08-09"))[MacroType.FAT],     Target(44f, 50f, true),    "danger survives the epoch switch")

        eq(CsvParser.parseWeightTargets(c).asOf(d("2026-08-01")), WeightTarget(0.7f, 0.9f), "weight target before epoch 1")
        eq(CsvParser.parseWeightTargets(c).asOf(d("2026-08-02")), WeightTarget(0.8f, 1.0f), "weight target on epoch 1")
        eq(CsvParser.parseWeightTargets(c).asOf(d("2026-08-09")), WeightTarget(0.6f, 0.8f), "weight target on epoch 2")
    }

    section("ties and future rows")
    run {
        val c = csv(
            row("1200" to "2000", "120" to "150", "150" to "220", "40" to "55", "0.7" to "0.9",
                eff = "2026-08-02"),
            row("1300" to "1900", "130" to "155", "160" to "210", "42" to "52", "0.8" to "1.0",
                eff = "2026-08-02"),
            row("9999" to "9999", "999" to "999", "999" to "999", "99" to "99", "9.0" to "9.9",
                eff = "2027-01-01"))
        val h = CsvParser.parseTargets(c)
        eq(h.asOf(d("2026-08-05"))[MacroType.PROTEIN], Target(130f, 155f, false), "exact-date tie: later sheet row wins")
        eq(CsvParser.parseWeightTargets(c).asOf(d("2026-08-05")), WeightTarget(0.8f, 1.0f), "  same tie-break for weight target")
        eq(h.asOf(d("2026-08-05"))[MacroType.CALORIES], Target(1300f, 1900f, false), "future row ignored")
        eq(h.asOf(d("2027-06-01"))[MacroType.CALORIES], Target(9999f, 9999f, false), "future row applies once reached")
    }

    section("degradation")
    run {
        // Sheets CSV export can drop trailing empties: no severity, no date columns at all.
        val short = listOf(HDR, "1200,2000,120,150,150,220,40,55,0.7,0.9").joinToString("\n")
        val hs = CsvParser.parseTargets(short)
        eq(hs.asOf(d("2026-08-09")).size, 4, "short row (10 cols) still yields all four bands")
        eq(hs.asOf(d("2026-08-09"))[MacroType.FAT], Target(40f, 55f, false), "  missing severity -> danger false")
        eq(CsvParser.parseWeightTargets(short).asOf(d("2026-08-09")), WeightTarget(0.7f, 0.9f), "  weight target still read")

        // One macro blank on a row must not take the whole row down with it.
        val holey = csv(row("" to "", "120" to "150", "150" to "220", "40" to "55", "0.7" to "0.9"))
        val hh = CsvParser.parseTargets(holey)
        eq(hh.asOf(d("2026-08-09"))[MacroType.CALORIES], null, "blank macro skipped for that row")
        eq(hh.asOf(d("2026-08-09"))[MacroType.PROTEIN], Target(120f, 150f, false), "  siblings unaffected")

        // Transposed bounds normalise, as the old parser did.
        val flipped = csv(row("2000" to "1200", "150" to "120", "220" to "150", "55" to "40", "0.9" to "0.7"))
        eq(CsvParser.parseTargets(flipped).asOf(d("2026-08-09"))[MacroType.PROTEIN],
           Target(120f, 150f, false), "lo/hi transposed -> normalised")
        eq(CsvParser.parseWeightTargets(flipped).asOf(d("2026-08-09")), WeightTarget(0.7f, 0.9f), "  same for weight band")

        eq(CsvParser.parseTargets(HDR).isEmpty, true, "header-only csv -> EMPTY history")
        eq(CsvParser.parseWeightTargets(HDR).asOf(d("2026-08-09")), null, "header-only csv -> null weight target")
    }

    println("\n" + (if (fail == 0) "ALL PASS" else "FAILURES: $fail") + "  ($pass assertions)")
    if (fail != 0) kotlin.system.exitProcess(1)
}
