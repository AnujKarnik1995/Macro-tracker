package com.example.macrowidget

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One day's watch energy read from Health Connect.
 *  - [activeKcal] = HR-based active/exercise calories for the day (null = no data / not worn).
 *    A genuine rest day where the watch WAS worn comes back as 0.0, not null — the backend and
 *    the dynamic-target math treat 0 (rest) and null (missing) differently.
 *  - [basalKcal] = basal metabolic rate for the day (kcal/day), null if unavailable.
 */
data class DayBurn(val activeKcal: Double?, val basalKcal: Double?)

/**
 * Thin wrapper over Health Connect for the two metrics we use: active calories (aggregated for
 * the day) and BMR (latest reading that day). Reads only — never writes. Returns null cleanly
 * when Health Connect is unavailable or the permissions haven't been granted, so callers can
 * fall back rather than crash.
 */
class HealthConnectBurnReader(private val context: Context) {

    val permissions: Set<String> = setOf(
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(BasalMetabolicRateRecord::class)
    )

    private fun clientOrNull(): HealthConnectClient? =
        if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE)
            HealthConnectClient.getOrCreate(context) else null

    /** True only if Health Connect is installed AND both read permissions are granted. */
    suspend fun hasAccess(): Boolean {
        val client = clientOrNull() ?: return false
        return client.permissionController.getGrantedPermissions().containsAll(permissions)
    }

    /**
     * Reads [date]'s active calories (aggregated) and latest BMR. Window is the calendar day in
     * the device's zone, capped at "now" so today's in-progress total is whatever's synced so far.
     * Returns null if Health Connect is unavailable or permission is missing.
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

        val active = aggregateKcal(client, ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL, range)
        val basal = latestBasalKcalPerDay(client, range)
        return DayBurn(active, basal)
    }

    private suspend fun aggregateKcal(
        client: HealthConnectClient,
        metric: AggregateMetric<androidx.health.connect.client.units.Energy>,
        range: TimeRangeFilter
    ): Double? = try {
        client.aggregate(AggregateRequest(metrics = setOf(metric), timeRangeFilter = range))[metric]
            ?.inKilocalories
    } catch (_: Exception) {
        null
    }

    /** BMR is an instantaneous rate, so we read the day's records and take the latest. */
    private suspend fun latestBasalKcalPerDay(
        client: HealthConnectClient,
        range: TimeRangeFilter
    ): Double? = try {
        client.readRecords(ReadRecordsRequest(BasalMetabolicRateRecord::class, timeRangeFilter = range))
            .records.maxByOrNull { it.time }
            ?.basalMetabolicRate?.inKilocaloriesPerDay
    } catch (_: Exception) {
        null
    }
}
