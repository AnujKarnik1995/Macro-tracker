package com.example.macrowidget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * Reads watch energy from Health Connect and posts it to the same Google Form the rest of the
 * pipeline uses, as a burn payload:  [{"basal":1680,"burn":520,"date":"27/07/2026"}]
 *
 * Two modes:
 *  - single day (default today, or [KEY_DATE]) — the daily 2–3pm / after-midnight runs;
 *  - backfill ([KEY_DAYS] = N) — the last N days in ONE submission (array payload), for the
 *    one-time ~30-day catch-up when the feed is first enabled. Health Connect only retains ~30
 *    days by default, so N beyond that returns nothing for the older dates.
 *
 * Not-worn days: no active-calories records → [DayBurn.activeKcal] is null → `burn` is omitted (or
 * the whole day skipped) → the sheet cell stays blank → the dynamic target falls back with no delta.
 *
 * FILL IN [FORM_RESPONSE_URL] and [ENTRY_FIELD] before use — see SETUP-burn-ingestion.md.
 */
class BurnUploadWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val reader = HealthConnectBurnReader(applicationContext)
        val days = inputData.getInt(KEY_DAYS, 0)

        val items = ArrayList<String>()
        if (days > 0) {
            // Backfill: last N completed days (skip today; the daily run handles today).
            for (d in 1..days) {
                val date = LocalDate.now().minusDays(d.toLong())
                val burn = reader.read(date) ?: return@withContext Result.success()  // no HC access
                item(burn, date)?.let { items.add(it) }
            }
        } else {
            val date = inputData.getString(KEY_DATE)?.let { LocalDate.parse(it) } ?: LocalDate.now()
            val burn = reader.read(date) ?: return@withContext Result.success()
            item(burn, date)?.let { items.add(it) }
        }

        if (items.isEmpty()) return@withContext Result.success()   // nothing worn / no data
        val json = "[" + items.joinToString(",") + "]"

        return@withContext try {
            if (postToForm(json)) Result.success() else Result.retry()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    /** One JSON object for a day, or null if the watch had no data that day. */
    private fun item(burn: DayBurn, date: LocalDate): String? {
        val fields = ArrayList<String>()
        burn.activeKcal?.let { fields.add("\"burn\":${it.roundToInt()}") }
        burn.basalKcal?.let { fields.add("\"basal\":${it.roundToInt()}") }
        if (fields.isEmpty()) return null
        fields.add("\"date\":\"${date.format(DMY)}\"")
        return "{" + fields.joinToString(",") + "}"
    }

    /** POSTs the payload array as a single-field Google Form response. Returns true on HTTP 2xx. */
    private fun postToForm(payloadJson: String): Boolean {
        val body = ENTRY_FIELD + "=" + URLEncoder.encode(payloadJson, "UTF-8")
        val conn = (URL(FORM_RESPONSE_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
        }
        return try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            conn.responseCode in 200..299
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        // ---- FILL THESE IN (see SETUP-burn-ingestion.md) ----
        private const val FORM_RESPONSE_URL =
            "https://docs.google.com/forms/d/e/FORM_ID_HERE/formResponse"
        private const val ENTRY_FIELD = "entry.PAYLOAD_ENTRY_ID_HERE"
        // -----------------------------------------------------

        private val DMY: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        const val KEY_DATE = "date"   // optional "yyyy-MM-dd"; defaults to today
        const val KEY_DAYS = "days"   // backfill: last N days in one submission

        /** Fire a one-shot upload for [date] (default today). */
        fun runNow(context: Context, date: LocalDate? = null) {
            val req = OneTimeWorkRequestBuilder<BurnUploadWorker>()
                .apply { date?.let { setInputData(workDataOf(KEY_DATE to it.toString())) } }
                .build()
            WorkManager.getInstance(context).enqueue(req)
        }

        /** One-time catch-up: read + post the last [days] days (default 30) in a single submission. */
        fun runBackfill(context: Context, days: Int = 30) {
            val req = OneTimeWorkRequestBuilder<BurnUploadWorker>()
                .setInputData(workDataOf(KEY_DAYS to days))
                .build()
            WorkManager.getInstance(context).enqueue(req)
        }
    }
}
