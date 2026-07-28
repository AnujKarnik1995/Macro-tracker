package com.example.macrowidget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.util.TypedValue
import android.widget.RemoteViews
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Fetch both CSVs, compute today + weekly, render, and push to each widget. */
class ChartWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ids = inputData.getIntArray(KEY_WIDGET_IDS) ?: return@withContext Result.failure()
        val mgr = AppWidgetManager.getInstance(applicationContext)

        for (id in ids) {
            val (wPx, hPx) = sizePx(mgr, id)
            val views = RemoteViews(applicationContext.packageName, R.layout.widget_chart)
            SheetWidgetProvider.applyClicks(applicationContext, views, id)  // body=page, corner=refresh

            val logUrl = WidgetPrefs.logUrl(applicationContext, id)
            val targetsUrl = WidgetPrefs.targetsUrl(applicationContext, id)
            if (logUrl.isNullOrBlank() || targetsUrl.isNullOrBlank()) {
                views.setImageViewBitmap(R.id.chart_image,
                    ChartRenderer.renderError("Not configured. Remove and re-add the widget to set the Log and Targets URLs.", wPx, hPx))
                mgr.updateAppWidget(id, views)
                continue
            }

            // Fetch fresh CSVs; on failure fall back to the last successfully cached ones
            // (shown silently — no stale marker). Only the error card if nothing is cached.
            var errMsg: String? = null
            val csvs: Pair<String, String>? = try {
                Pair(
                    SheetFetcher.fetchCsv(applicationContext, logUrl),
                    SheetFetcher.fetchCsv(applicationContext, targetsUrl)
                )
            } catch (e: Exception) {
                errMsg = e.message
                val cachedLog = SheetCache.loadBody(applicationContext, logUrl)
                val cachedTargets = SheetCache.loadBody(applicationContext, targetsUrl)
                if (cachedLog != null && cachedTargets != null) cachedLog to cachedTargets else null
            }

            if (csvs == null) {
                WidgetPrefs.setSignature(applicationContext, id, "error")
                views.setImageViewBitmap(R.id.chart_image,
                    ChartRenderer.renderError("Couldn't load: ${errMsg ?: "no data"}", wPx, hPx))
                mgr.updateAppWidget(id, views)
                continue
            }

            val entries = CsvParser.parseLog(csvs.first)
            val targetHistory = CsvParser.parseTargets(csvs.second)
            val weightTarget = CsvParser.parseWeightTarget(csvs.second)
            val today = LocalDate.now()
            val page = WidgetPrefs.page(applicationContext, id)

            // Today's rings + the render signature use the bands in effect right now; the tally
            // judges each past day against the band that applied on that day (frozen greens).
            val targets = targetHistory.current(today)

            val daysLeft = countdownDays(today)
            val totalDays = challengeDays(today)
            val todayEntry = MacroCalculator.today(entries, today)
            val weekly = MacroCalculator.weeklyAverage(entries, today)
            val successfulCount = MacroCalculator.successfulDays(entries, targetHistory, today)
            val weightSeries = WeightCalculator.series(entries, weightTarget, today)

            // Skip the re-render+push entirely when nothing that affects pixels changed
            // (includes the page, so a page toggle always re-renders).
            val sig = signature(wPx, hPx, page, todayEntry, weekly, targets,
                successfulCount, totalDays, daysLeft, weightSignature(weightSeries))
            if (sig == WidgetPrefs.signature(applicationContext, id) &&
                BitmapCache.load(applicationContext, id) != null) {
                continue
            }

            val bmp = if (page == 1) {
                WeightRenderer.render(weightSeries, weightTarget, page,
                    SheetWidgetProvider.PAGE_COUNT, wPx, hPx)
            } else {
                ChartRenderer.render(
                    today = todayEntry,
                    weekly = weekly,
                    targets = targets,
                    successfulCount = successfulCount,
                    totalDays = totalDays,
                    daysToGoal = daysLeft,
                    goalLabel = GOAL_LABEL,
                    phaseLabel = PHASE_LABEL,
                    page = page,
                    pageCount = SheetWidgetProvider.PAGE_COUNT,
                    widthPx = wPx, heightPx = hPx
                )
            }
            BitmapCache.save(applicationContext, id, bmp)
            WidgetPrefs.setSignature(applicationContext, id, sig)
            views.setImageViewBitmap(R.id.chart_image, bmp)
            mgr.updateAppWidget(id, views)
        }
        Result.success()
    }

    private fun sizePx(mgr: AppWidgetManager, id: Int): Pair<Int, Int> {
        val o: Bundle = mgr.getAppWidgetOptions(id)
        // The options bundle reports MIN/MAX for portrait/landscape. Pick the pair that
        // matches the CURRENT orientation, otherwise a tall portrait tile gets a short,
        // wide bitmap that fitCenter letterboxes (the wasted top/bottom bands).
        val portrait = applicationContext.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        val wDp = o.getInt(
            if (portrait) AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 250)
        val hDp = o.getInt(
            if (portrait) AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 250)
        val dm = applicationContext.resources.displayMetrics
        fun px(dp: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), dm).toInt()
        var w = px(if (wDp > 0) wDp else 250)
        var h = px(if (hDp > 0) hDp else 250)
        val cap = 768 // higher render resolution (less upscaling = crisper) while staying under the bitmap-memory limit
        val longest = maxOf(w, h)
        if (longest > cap) {
            val f = cap.toFloat() / longest
            w = (w * f).toInt()
            h = (h * f).toInt()
        }
        return w.coerceAtLeast(300) to h.coerceAtLeast(300)
    }

    private fun countdownDays(today: LocalDate): Int =
        ChronoUnit.DAYS.between(today, GOAL_DATE).toInt().coerceAtLeast(0)

    /** Days since the challenge began, inclusive (start day = 1). */
    private fun challengeDays(today: LocalDate): Int =
        (ChronoUnit.DAYS.between(CHALLENGE_START, today).toInt() + 1).coerceAtLeast(1)

    /** Everything that affects the rendered pixels (both pages + which page is showing). */
    private fun signature(
        w: Int, h: Int, page: Int, today: LogEntry?, weekly: WeeklyAverage?,
        targets: Map<MacroType, Target>, streak: Int, total: Int, daysToGoal: Int, weight: String
    ): String = buildString {
        append(w).append('x').append(h).append("|p").append(page)
        append('|').append(streak).append('/').append(total).append('|').append(daysToGoal).append('|')
        append(today?.let { e -> MacroType.entries.joinToString(",") { (e.values[it] ?: 0f).toString() } } ?: "none")
        append('|')
        append(weekly?.let { wk ->
            wk.dayCount.toString() + ":" + MacroType.entries.joinToString(",") { (wk.values[it] ?: 0f).toString() }
        } ?: "none")
        append('|')
        append(targets.entries.sortedBy { it.key.ordinal }
            .joinToString(",") { (m, t) -> "${m.name}:${t.lower}:${t.upper}:${t.underDanger}" })
        append('|').append(weight)
    }

    /** Compact fingerprint of the weight page's data, for the render-skip signature. */
    private fun weightSignature(s: WeightSeries): String {
        if (!s.hasData) return "none"
        return buildString {
            append(s.weeks.joinToString(",") { "${it.end}:${it.avg}:${it.complete}:${it.inZone}" })
            append('|').append(s.currentDailies.joinToString(","))
            append('|').append(s.targetLow).append(':').append(s.targetHigh)
            append('|').append(s.latest).append(':').append(s.totalDelta).append(':').append(s.thisWeekRate)
        }
    }

    companion object {
        const val KEY_WIDGET_IDS = "widget_ids"
        // Countdown target. Change here if the goal date moves.
        private val GOAL_DATE: LocalDate = LocalDate.of(2026, 10, 7)
        private const val GOAL_LABEL = "Oct 7"
        // Challenge start — denominator of the "green days / total days" tally.
        private val CHALLENGE_START: LocalDate = LocalDate.of(2026, 6, 15)
        // Phase label shown to the left of "Today".
        private const val PHASE_LABEL = "Cutting Phase"
    }
}
