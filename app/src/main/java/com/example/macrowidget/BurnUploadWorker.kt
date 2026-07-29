package com.example.macrowidget

import android.content.Context
import android.util.Log
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

class BurnUploadWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val days = inputData.getInt(KEY_DAYS, 0)
        val singleDateStr = inputData.getString(KEY_DATE)
        val date = singleDateStr?.let { LocalDate.parse(it) }

        val res = performSync(applicationContext, days = if (days > 0) days else 0, date = date)
        Log.d(TAG, "doWork result: $res")
        if (res.startsWith("SUCCESS")) Result.success() else Result.retry()
    }

    companion object {
        private const val TAG = "BurnUpload"
        private const val FORM_RESPONSE_URL =
            "https://docs.google.com/forms/d/e/1FAIpQLSdiiSS14OABpuJueMuJjNjm2Oo9IMTE-dW_7yaKfc_pBtyhHw/formResponse"
        private const val ENTRY_FIELD = "entry.508582545"

        private val DMY: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        const val KEY_DATE = "date"   // optional "yyyy-MM-dd"; defaults to today
        const val KEY_DAYS = "days"   // backfill: last N days in one submission

        suspend fun performSync(context: Context, days: Int = 0, date: LocalDate? = null): String =
            withContext(Dispatchers.IO) {
                val reader = HealthConnectBurnReader(context)
                if (!reader.hasAccess()) {
                    return@withContext "ERROR: Health Connect permissions not granted"
                }

                val items = ArrayList<String>()
                if (days > 0) {
                    for (d in 1..days) {
                        val dDate = LocalDate.now().minusDays(d.toLong())
                        val burn = try { reader.read(dDate) } catch (e: Exception) {
                            Log.e(TAG, "Error reading $dDate", e)
                            null
                        }
                        if (burn != null) {
                            item(burn, dDate)?.let { items.add(it) }
                        }
                    }
                } else {
                    val targetDate = date ?: LocalDate.now()
                    val burn = try { reader.read(targetDate) } catch (e: Exception) {
                        Log.e(TAG, "Error reading $targetDate", e)
                        null
                    }
                    if (burn != null) {
                        item(burn, targetDate)?.let { items.add(it) }
                    }
                }

                if (items.isEmpty()) {
                    Log.w(TAG, "No Health Connect data found for specified period.")
                    return@withContext "NO DATA: Health Connect returned 0 records for watch burn."
                }

                val json = "[" + items.joinToString(",") + "]"
                Log.d(TAG, "Posting payload (${items.size} days): $json")

                return@withContext try {
                    val responseCode = postToForm(json)
                    if (responseCode in 200..299) {
                        "SUCCESS: Posted ${items.size} days to Google Sheet (HTTP $responseCode)"
                    } else {
                        "ERROR: Google Form HTTP response $responseCode"
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Form post exception", e)
                    "ERROR: Exception posting form: ${e.message}"
                }
            }

        private fun item(burn: DayBurn, date: LocalDate): String? {
            val fields = ArrayList<String>()
            burn.activeKcal?.let { fields.add("\"burn\":${it.roundToInt()}") }
            burn.basalKcal?.let { fields.add("\"basal\":${it.roundToInt()}") }
            if (fields.isEmpty()) return null
            fields.add("\"date\":\"${date.format(DMY)}\"")
            return "{" + fields.joinToString(",") + "}"
        }

        private fun postToForm(payloadJson: String): Int {
            val encoded = URLEncoder.encode(payloadJson, "UTF-8")
            val body = if (ENTRY_FIELD.endsWith(".other_option_response")) {
                val baseEntry = ENTRY_FIELD.removeSuffix(".other_option_response")
                "$baseEntry=__other_option__&$ENTRY_FIELD=$encoded"
            } else {
                "$ENTRY_FIELD=__other_option__&$ENTRY_FIELD.other_option_response=$encoded"
            }
            Log.d(TAG, "POST URL: $FORM_RESPONSE_URL")
            Log.d(TAG, "POST body: $body")
            val conn = (URL(FORM_RESPONSE_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
            }
            return try {
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
                val code = conn.responseCode
                Log.d(TAG, "Form response code: $code")
                code
            } catch (e: Exception) {
                Log.e(TAG, "Form POST exception", e)
                -1
            } finally {
                conn.disconnect()
            }
        }

        fun runNow(context: Context, date: LocalDate? = null) {
            val req = OneTimeWorkRequestBuilder<BurnUploadWorker>()
                .apply { date?.let { setInputData(workDataOf(KEY_DATE to it.toString())) } }
                .build()
            WorkManager.getInstance(context).enqueue(req)
        }

        fun runBackfill(context: Context, days: Int = 30) {
            val req = OneTimeWorkRequestBuilder<BurnUploadWorker>()
                .setInputData(workDataOf(KEY_DAYS to days))
                .build()
            WorkManager.getInstance(context).enqueue(req)
        }
    }
}
