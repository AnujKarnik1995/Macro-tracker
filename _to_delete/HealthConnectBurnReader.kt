package com.example.macrowidget

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One day's watch energy read from Health Connect.
 *  - [activeKcal] = HR-based active/exercise calories for the day (null = no data / not worn).
 *  - [basalKcal] = basal metabolic rate for the day (kcal/day), null if unavailable.
 */
data class DayBurn(val activeKcal: Double?, val basalKcal: Double?)

/**
 * Thin wrapper over Health Connect for active calories (aggregated, record sum, or total-basal difference)
 * and BMR (latest reading that day).
 */
class HealthConnectBurnReader(private val context: Context) {

    val permissions: Set<String> = setOf(
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(BasalMetabolicRateRecord::class)
    )

    private fun clientOrNull(): HealthConnectClient? =
        if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE)
            HealthConnectClient.getOrCreate(context) else null

    /** True only if Health Connect is installed AND both core read permissions are granted. */
    suspend fun hasAccess(): Boolean {
        val client = clientOrNull() ?: return false
        return client.permissionController.getGrantedPermissions().containsAll(permissions)
    }

    /**
     * Reads [date]'s active calories and latest BMR.
     * Window is the calendar day in the device's zone, capped at "now".
     */
    suspend fun read(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): DayBurn? {
        val client = clientOrNull() ?: return null
        if (!client.permissionController.getGrantedPermissions().containsAll(permissions)) return null

        val start: Instant = date.atStartOfDay(zone).toInstant()
        val dayEnd: Instant = date.plusDays(1).atStartOfDay(zone).toInstant()
        val now: Instant = Instant.now()
        val end: Instant = if (dayEnd.isAfter(now)) now else dayEnd
        if (!end.isAfter(start)) return DayBurn(null, null)     // future date / empty window
        val range = TimeRangeFilter.between(start, end)

        val basal = latestBasalKcalPerDay(client, range)
        var active = aggregateActiveKcal(client, range)

        // Fallback: if active is still null/0, try TotalCaloriesBurnedRecord minus BMR
        if ((active == null || active == 0.0) && basal != null && basal > 0.0) {
            val total = aggregateTotalKcal(client, range)
            if (total != null && total > basal) {
                active = total - basal
                Log.d("HealthConnectReader", "Active calculated via Total - Basal: $active ($total - $basal)")
            }
        }

        Log.d("HealthConnectReader", "Date: $date -> final active: $active, basal: $basal")
        return DayBurn(active, basal)
    }

    private suspend fun aggregateActiveKcal(
        client: HealthConnectClient,
        range: TimeRangeFilter
    ): Double? {
        val metric = ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL
        val agg: Double? = try {
            client.aggregate(AggregateRequest(metrics = setOf(metric), timeRangeFilter = range))[metric]
                ?.inKilocalories
        } catch (e: Exception) {
            Log.w("HealthConnectReader", "Aggregate active calories failed: ${e.message}")
            null
        }

        if (agg == null || agg == 0.0) {
            // Fallback: read individual ActiveCaloriesBurnedRecords and sum them up
            try {
                val records = client.readRecords(
                    ReadRecordsRequest(ActiveCaloriesBurnedRecord::class, timeRangeFilter = range)
                ).records
                if (records.isNotEmpty()) {
                    val sum = records.sumOf { it.energy.inKilocalories }
                    Log.d("HealthConnectReader", "Fallback readRecords sum active calories: $sum (${records.size} records)")
                    return sum
                }
            } catch (e: Exception) {
                Log.w("HealthConnectReader", "Fallback readRecords active calories failed: ${e.message}")
            }
        }
        return agg
    }

    private suspend fun aggregateTotalKcal(
        client: HealthConnectClient,
        range: TimeRangeFilter
    ): Double? {
        val metric = TotalCaloriesBurnedRecord.ENERGY_TOTAL
        var agg: Double? = try {
            client.aggregate(AggregateRequest(metrics = setOf(metric), timeRangeFilter = range))[metric]
                ?.inKilocalories
        } catch (_: Exception) {
            null
        }

        if (agg == null || agg == 0.0) {
            try {
                val records = client.readRecords(
                    ReadRecordsRequest(TotalCaloriesBurnedRecord::class, timeRangeFilter = range)
                ).records
                if (records.isNotEmpty()) {
                    return records.sumOf { it.energy.inKilocalories }
                }
            } catch (_: Exception) {}
        }
        return agg
    }

    private suspend fun latestBasalKcalPerDay(
        client: HealthConnectClient,
        range: TimeRangeFilter
    ): Double? = try {
        val records = client.readRecords(ReadRecordsRequest(BasalMetabolicRateRecord::class, timeRangeFilter = range)).records
        val latest = records.maxByOrNull { it.time }?.basalMetabolicRate?.inKilocaloriesPerDay
        latest
    } catch (e: Exception) {
        Log.w("HealthConnectReader", "Read BMR records failed: ${e.message}")
        null
    }
}
