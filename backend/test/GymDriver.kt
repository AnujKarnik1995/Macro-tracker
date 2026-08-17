import com.example.macrowidget.*
import java.time.LocalDate

/**
 * Offline check for GymCalculator + the CsvParser gym column.
 * Pure JVM — MacroModel/CsvParser/GymCalculator carry no Android imports.
 *
 *   kotlinc MacroModel.kt CsvParser.kt GymCalculator.kt GymDriver.kt -include-runtime -d gym.jar
 *   java -jar gym.jar
 */

var pass = 0
var fail = 0

fun eq(actual: Any?, expected: Any?, label: String) {
    if (actual == expected) { pass++; println("  ok   $label") }
    else { fail++; println("  FAIL $label\n         got      $actual\n         expected $expected") }
}

fun section(s: String) = println("\n$s")

fun entry(date: String, gym: String? = null, cal: Float = 1900f) =
    LogEntry(LocalDate.parse(date), mapOf(MacroType.CALORIES to cal), null, null, null, gym)

// Plan matching config.properties: 22 sessions, 17 Aug -> 7 Oct.
val START: LocalDate = LocalDate.parse("2026-08-17")
val GOAL: LocalDate = LocalDate.parse("2026-10-07")
const val TOTAL = 22

fun main() {
    // ==========================================================================
    section("plan arithmetic — 22 sessions, 17 Aug -> 7 Oct")
    run {
        val s = GymCalculator.compute(emptyList(), START, START, GOAL, TOTAL)
        eq(s.done, 0, "nothing logged yet")
        eq(s.left, 22, "full budget owed")
        // 17 Aug..7 Oct inclusive = 52 days = 7.4286 weeks -> 22/7.4286 = 2.96
        eq(String.format("%.2f", s.requiredPerWeek), "2.96", "required rate ~3.0/wk on day 1")
        eq(String.format("%.2f", s.planPerWeek), "2.96", "plan rate equals required on day 1")
        eq(GymCalculator.zone(s), 0, "on pace")
        eq(s.nextSession, "A", "rotation starts at A")
        eq(s.daysSinceLast, null, "never trained")
        eq(GymCalculator.caption(s), "next · session A", "caption")
    }

    // ==========================================================================
    section("required rate RISES on its own when nothing is logged")
    run {
        val rates = listOf(0, 7, 14, 21).map { d ->
            val s = GymCalculator.compute(emptyList(), START.plusDays(d.toLong()), START, GOAL, TOTAL)
            String.format("%.1f", s.requiredPerWeek)
        }
        // Verified by hand: 52d/45d/38d/31d remaining -> 2.96 / 3.42 / 4.05 / 4.97
        eq(rates, listOf("3.0", "3.4", "4.1", "5.0"), "3.0 -> 3.4 -> 4.1 -> 5.0 over three idle weeks")

        // This is the anti-gaming property: the divisor shrinks with the calendar whatever you type.
        val idle2w = GymCalculator.compute(emptyList(), START.plusDays(14), START, GOAL, TOTAL)
        eq(GymCalculator.zone(idle2w), 2, "two idle weeks reads RED")
    }

    // ==========================================================================
    section("zone thresholds are multiples of the plan's own pace")
    run {
        // On pace: 6 sessions in the first 2 weeks (plan wants ~5.9)
        val onPace = (0 until 6).map { entry(START.plusDays((it * 2).toLong()).toString(), if (it % 2 == 0) "A" else "B") }
        val s = GymCalculator.compute(onPace, START.plusDays(14), START, GOAL, TOTAL)
        eq(s.done, 6, "six sessions counted")
        eq(s.left, 16, "16 owed")
        // 16 sessions over the 38 days remaining = 2.95, which displays as 2.9.
        eq(String.format("%.1f", s.requiredPerWeek), "2.9", "16 left over 5.43 wk")
        eq(GymCalculator.zone(s), 0, "green")
        eq(GymCalculator.headline(s), "16 left · 2.9 / wk", "headline shape matches the render")

        // Slipping: only 2 sessions in those 2 weeks
        val slipping = listOf(entry("2026-08-18", "A"), entry("2026-08-22", "B"))
        val s2 = GymCalculator.compute(slipping, START.plusDays(14), START, GOAL, TOTAL)
        eq(String.format("%.1f", s2.requiredPerWeek), "3.7", "20 left over 5.43 wk")
        eq(GymCalculator.zone(s2), 1, "amber")
    }

    // ==========================================================================
    section("two sessions in one day is still ONE")
    run {
        // Summary holds one row per date, so this is really a guard on the date-set logic.
        val dup = listOf(entry("2026-08-18", "A"), entry("2026-08-18", "B"))
        val s = GymCalculator.compute(dup, LocalDate.parse("2026-08-18"), START, GOAL, TOTAL)
        eq(s.done, 1, "no banking ahead")
        eq(s.rolling7, 1, "rolling count agrees")
    }

    // ==========================================================================
    section("sessions before the plan start are ignored")
    run {
        val old = listOf(entry("2026-08-10", "A"), entry("2026-08-12", "B"), entry("2026-08-18", "A"))
        val s = GymCalculator.compute(old, LocalDate.parse("2026-08-18"), START, GOAL, TOTAL)
        eq(s.done, 1, "only the in-plan session counts")
    }

    // ==========================================================================
    section("the seven dots — oldest first, today last")
    run {
        val today = LocalDate.parse("2026-08-23")
        // trained on 18th, 20th, 23rd
        val es = listOf(entry("2026-08-18", "A"), entry("2026-08-20", "B"), entry("2026-08-23", "A"))
        val s = GymCalculator.compute(es, today, START, GOAL, TOTAL)
        // window is 17..23 Aug -> 17=F 18=T 19=F 20=T 21=F 22=F 23=T
        eq(s.last7, listOf(false, true, false, true, false, false, true), "dot pattern")
        eq(s.rolling7, 3, "3 of last 7")
        eq(s.last7.last(), true, "today is the right-hand dot")
        eq(s.daysSinceLast, 0, "trained today")
        eq(s.nextSession, "B", "last was A -> B is next")
        eq(GymCalculator.caption(s), "done today · next is B", "caption reflects it")
    }

    // ==========================================================================
    section("rotation is a pointer, never a weekday")
    run {
        val t = LocalDate.parse("2026-08-25")
        eq(GymCalculator.compute(listOf(entry("2026-08-20", "A")), t, START, GOAL, TOTAL).nextSession, "B", "A -> B")
        eq(GymCalculator.compute(listOf(entry("2026-08-20", "B")), t, START, GOAL, TOTAL).nextSession, "A", "B -> A")
        // A five-day gap does not reset or skip anything — the next session just waits.
        val s = GymCalculator.compute(listOf(entry("2026-08-19", "B")), t, START, GOAL, TOTAL)
        eq(s.nextSession, "A", "gap doesn't shuffle the rotation")
        eq(s.daysSinceLast, 6, "six days since")
        eq(GymCalculator.caption(s), "6 days since last · session A is up", "nudge appears past 4 days")
    }

    // ==========================================================================
    section("edge cases")
    run {
        eq(GymCalculator.compute(emptyList(), START, START, GOAL, 0).configured, false, "GYM_TOTAL=0 hides the block")

        // Budget met early — no negative 'left', no infinite rate.
        val many = (0 until 25).map { entry(START.plusDays(it.toLong()).toString(), "A") }
        val s = GymCalculator.compute(many, START.plusDays(24), START, GOAL, TOTAL)
        eq(s.left, 0, "left floors at 0, never negative")
        eq(GymCalculator.zone(s), 0, "met budget is green, not red")
        eq(GymCalculator.headline(s), "done · 25 / 22", "headline switches to done")

        // The last day of the plan must not divide by zero.
        val lastDay = GymCalculator.compute(emptyList(), GOAL, START, GOAL, TOTAL)
        eq(lastDay.requiredPerWeek != null, true, "goal day still produces a number")
        eq(String.format("%.1f", lastDay.requiredPerWeek), "154.0", "22 sessions in the 1 remaining day")

        // Past the goal date: no rate rather than a negative one.
        eq(GymCalculator.compute(emptyList(), GOAL.plusDays(5), START, GOAL, TOTAL).requiredPerWeek, null,
            "past the deadline -> null, not negative")
    }

    // ==========================================================================
    section("CsvParser reads col M without disturbing anything before it")
    run {
        val csv = listOf(
            "date,cal,p,c,f,weight,unused,burn,t_cal,t_pro,t_carb,t_fat,gym",
            "2026-08-17,1900,140,200,47,152.4,,,1925,151.5,236,47.5,A",
            "2026-08-18,1850,145,190,46,152.1,,,1930,151.5,238,47.5,b",
            "2026-08-19,1875,138,205,48,152.0,,,1928,151.5,237,47.5,",
            "2026-08-20,1880,142,198,45,151.8,,,1931,151.5,239,47.5,Z"
        ).joinToString("\n")
        val es = CsvParser.parseLog(csv)
        eq(es.size, 4, "four rows")
        eq(es[0].gym, "A", "\"A\" parsed")
        eq(es[1].gym, "B", "lowercase \"b\" normalised to B")
        eq(es[2].gym, null, "blank -> null (no session)")
        eq(es[3].gym, "A", "unknown label still counts, falls back to A")
        eq(es[0].targetCenters?.get(MacroType.CARBS), 236f, "target centres unshifted")
        eq(es[0].weight, 152.4f, "weight unshifted")

        // A sheet published BEFORE the gym column existed must still parse.
        val legacy = listOf(
            "date,cal,p,c,f,weight,unused,burn,t_cal,t_pro,t_carb,t_fat",
            "2026-08-17,1900,140,200,47,152.4,,,1925,151.5,236,47.5"
        ).joinToString("\n")
        val le = CsvParser.parseLog(legacy)
        eq(le.size, 1, "legacy 12-column sheet parses")
        eq(le[0].gym, null, "  no gym column -> null")
        eq(le[0].targetCenters?.get(MacroType.FAT), 47.5f, "  targets still read")
    }

    println("\n" + (if (fail == 0) "ALL PASS" else "FAILURES: $fail") + "  ($pass assertions)")
    if (fail != 0) kotlin.system.exitProcess(1)
}
