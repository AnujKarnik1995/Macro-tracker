import com.example.macrowidget.*
import java.io.File
import java.time.LocalDate

fun main(args: Array<String>) {
    val summaryCsv = File(args[0]).readText()
    val targetsCsv = File(args[1]).readText()
    val today = LocalDate.parse(args[2])

    val entries = CsvParser.parseLog(summaryCsv)
    val history = CsvParser.parseTargets(targetsCsv)
    val wTarget = CsvParser.parseWeightTarget(targetsCsv)

    println("{")
    println("  \"rowsParsed\": ${entries.size},")
    println("  \"datesDistinct\": ${entries.map{it.date}.distinct().size},")
    println("  \"duplicateDates\": ${entries.size - entries.map{it.date}.distinct().size},")

    val green = MacroCalculator.successfulDays(entries, history, today)
    println("  \"greenDays\": $green,")

    val t = TdeeCalculator.compute(entries, today)
    println("  \"tdee\": ${t.tdee}, \"avgIntake\": ${t.avgIntake}, \"lbPerWeek\": ${t.lbPerWeek},")
    println("  \"weighIns\": ${t.weighIns}, \"intakeDays\": ${t.intakeDays}, \"collecting\": ${t.collecting},")

    val ws = WeightCalculator.series(entries, wTarget, today)
    println("  \"weeks\": ${ws.weeks.size}, \"latest\": ${ws.latest}, \"thisWeekRate\": ${ws.thisWeekRate},")
    println("  \"weeklyAvgs\": [${ws.weeks.joinToString(","){ "%.2f".format(it.avg) }}],")
    println("  \"weeklyRates\": [${ws.weeks.joinToString(","){ it.rate?.let{r->"%.2f".format(r)} ?: "null" }}],")
    println("  \"weekComplete\": [${ws.weeks.joinToString(","){ it.complete.toString() }}],")

    val wk = MacroCalculator.weeklyAverage(entries, history, today)
    val wkBands = wk?.let { w -> listOf(MacroType.CALORIES, MacroType.PROTEIN, MacroType.CARBS, MacroType.FAT).joinToString(";") { m ->
        w.bands[m]?.let { t -> "${m.label}:${"%.1f".format(t.lower)}-${"%.1f".format(t.upper)}" } ?: "${m.label}:none" } } ?: "none"
    val wkVals = wk?.let { w -> listOf(MacroType.CALORIES, MacroType.PROTEIN, MacroType.CARBS, MacroType.FAT).joinToString(";") { m ->
        "${m.label}:${"%.1f".format(w.values[m] ?: 0f)}" } } ?: "none"
    println("  \"weeklyValues\": \"$wkVals\",")
    println("  \"weeklyBands\": \"$wkBands\",")

    // per-day effective bands + pass/fail, so a Code.gs change that moves t_* is caught exactly
    val rows = entries.sortedBy { it.date }.map { e ->
        val eff = MacroCalculator.effectiveTargets(e, history)
        val ok = MacroCalculator.dayIsSuccessful(e, eff)
        val b = listOf(MacroType.PROTEIN, MacroType.CARBS, MacroType.FAT).joinToString(";") { m ->
            eff[m]?.let { "${m.label}:${"%.1f".format(it.lower)}-${"%.1f".format(it.upper)}" } ?: "${m.label}:none"
        }
        "    {\"d\":\"${e.date}\",\"green\":$ok,\"bands\":\"$b\"}"
    }
    println("  \"days\": [\n${rows.joinToString(",\n")}\n  ]")
    println("}")
}
