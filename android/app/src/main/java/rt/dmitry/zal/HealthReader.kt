package rt.dmitry.zal

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt
import kotlin.reflect.KClass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object HealthReader {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var reading = false

    private val stepsPermission = HealthPermission.getReadPermission(StepsRecord::class)
    private val distancePermission = HealthPermission.getReadPermission(DistanceRecord::class)
    private val caloriesPermission = HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class)
    private val exercisePermission = HealthPermission.getReadPermission(ExerciseSessionRecord::class)
    private val heartPermission = HealthPermission.getReadPermission(HeartRateRecord::class)
    private val speedPermission = HealthPermission.getReadPermission(SpeedRecord::class)
    private val elevationPermission = HealthPermission.getReadPermission(ElevationGainedRecord::class)

    @JvmField
    val permissions: Set<String> = setOf(
        stepsPermission,
        distancePermission,
        caloriesPermission,
        exercisePermission,
        heartPermission,
        speedPermission,
        elevationPermission
    )

    private val kinds = mapOf(
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING to "bike",
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY to "bike",
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING to "run",
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL to "run",
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING to "walk",
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING to "walk",
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL to "pool",
        ExerciseSessionRecord.EXERCISE_TYPE_SNOWBOARDING to "snowboard",
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING to "gym",
        ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING to "gym",
        ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS to "bars",
        ExerciseSessionRecord.EXERCISE_TYPE_PADDLING to "sup",
        ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL to "cardio",
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING to "cardio",
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING to "cardio",
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE to "cardio"
    )

    private val lapKinds = setOf("snowboard", "wakeboard", "enduro", "pool")

    @JvmStatic
    fun pull(activity: MainActivity) {
        if (reading) return
        reading = true
        scope.launch {
            try {
                val status = HealthConnectClient.getSdkStatus(activity)
                if (status != HealthConnectClient.SDK_AVAILABLE) {
                    val message = if (status == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) {
                        "Обновите приложение Health Connect"
                    } else {
                        "На телефоне нет Health Connect"
                    }
                    activity.showHealthNote(message)
                    return@launch
                }
                val client = HealthConnectClient.getOrCreate(activity)
                val granted = client.permissionController.getGrantedPermissions()
                if (!granted.containsAll(permissions) && !activity.isHealthPromptDone()) {
                    activity.markHealthPromptDone()
                    activity.launchHealthPermission(permissions)
                    return@launch
                }
                val json = withContext(Dispatchers.IO) { readPayload(client, granted) }
                activity.deliverHealth(json)
            } catch (_: Exception) {
                activity.showHealthNote("Health Connect не отдал данные")
            } finally {
                reading = false
            }
        }
    }

    private suspend fun readPayload(client: HealthConnectClient, granted: Set<String>): String {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val start = today.minusDays(27).atStartOfDay(zone).toInstant()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant()
        val days = linkedMapOf<LocalDate, JSONObject>()

        fun day(date: LocalDate): JSONObject = days.getOrPut(date) { JSONObject() }

        if (granted.contains(stepsPermission)) {
            val totals = mutableMapOf<LocalDate, Double>()
            readAll(client, StepsRecord::class, start, end).forEach { record ->
                addSpan(record.startTime, record.endTime, record.count.toDouble(), zone, totals)
            }
            totals.forEach { (date, value) ->
                val steps = value.roundToInt()
                if (steps in 0..200000) day(date).put("steps", steps)
            }
        }
        if (granted.contains(distancePermission)) {
            val totals = mutableMapOf<LocalDate, Double>()
            readAll(client, DistanceRecord::class, start, end).forEach { record ->
                addSpan(record.startTime, record.endTime, record.distance.inMeters, zone, totals)
            }
            totals.forEach { (date, value) ->
                val meters = value.roundToInt()
                if (meters in 0..200000) day(date).put("distanceM", meters)
            }
        }
        if (granted.contains(caloriesPermission)) {
            val totals = mutableMapOf<LocalDate, Double>()
            readAll(client, ActiveCaloriesBurnedRecord::class, start, end).forEach { record ->
                addSpan(record.startTime, record.endTime, record.energy.inKilocalories, zone, totals)
            }
            totals.forEach { (date, value) ->
                val calories = value.roundToInt()
                if (calories in 0..20000) day(date).put("activeCalories", calories)
            }
        }

        val activities = JSONArray()
        if (granted.contains(exercisePermission)) {
            val sessions = readAll(client, ExerciseSessionRecord::class, start, end)
            val heart = if (granted.contains(heartPermission)) readAll(client, HeartRateRecord::class, start, end) else emptyList()
            val speed = if (granted.contains(speedPermission)) readAll(client, SpeedRecord::class, start, end) else emptyList()
            val sessionDistance = if (granted.contains(distancePermission)) readAll(client, DistanceRecord::class, start, end) else emptyList()
            val sessionCalories = if (granted.contains(caloriesPermission)) readAll(client, ActiveCaloriesBurnedRecord::class, start, end) else emptyList()
            val elevation = if (granted.contains(elevationPermission)) readAll(client, ElevationGainedRecord::class, start, end) else emptyList()
            sessions.forEach { session ->
                val item = activityJson(session, zone, heart, speed, sessionDistance, sessionCalories, elevation) ?: return@forEach
                activities.put(item)
            }
        }

        val daysJson = JSONObject()
        days.toSortedMap().forEach { (date, obj) ->
            if (obj.length() > 0) daysJson.put(date.toString(), obj)
        }
        return JSONObject()
            .put("source", "health-connect")
            .put("days", daysJson)
            .put("activities", activities)
            .toString()
    }

    private fun activityJson(
        session: ExerciseSessionRecord,
        zone: ZoneId,
        heart: List<HeartRateRecord>,
        speed: List<SpeedRecord>,
        distance: List<DistanceRecord>,
        calories: List<ActiveCaloriesBurnedRecord>,
        elevation: List<ElevationGainedRecord>
    ): JSONObject? {
        val typeId = kinds[session.exerciseType] ?: return null
        val id = session.metadata.id.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(80)
        if (id.isEmpty()) return null
        val local = if (session.startZoneOffset != null) {
            session.startTime.atOffset(session.startZoneOffset).toLocalDateTime()
        } else {
            session.startTime.atZone(zone).toLocalDateTime()
        }
        val stats = JSONObject()
        stats.put("start", "%02d:%02d".format(local.hour, local.minute))
        val duration = ChronoUnit.SECONDS.between(session.startTime, session.endTime)
        if (duration in 1..86400) stats.put("durationSec", duration)
        val meters = overlapping(distance, session.startTime, session.endTime) { Triple(it.startTime, it.endTime, it.distance.inMeters) }
        if (meters in 1.0..500000.0) stats.put("distanceM", meters.roundToInt())
        val kcal = overlapping(calories, session.startTime, session.endTime) { Triple(it.startTime, it.endTime, it.energy.inKilocalories) }
        if (kcal in 1.0..20000.0) stats.put("calories", kcal.roundToInt())
        val beats = heart.flatMap { record -> record.samples }
            .filter { sample -> !sample.time.isBefore(session.startTime) && sample.time.isBefore(session.endTime) }
            .map { it.beatsPerMinute }
        if (beats.isNotEmpty()) {
            val avg = beats.average().roundToInt()
            val max = beats.maxOrNull() ?: 0
            if (avg in 30..230) stats.put("avgHr", avg)
            if (max in 30..230) stats.put("maxHr", max)
        }
        val speeds = speed.flatMap { record -> record.samples }
            .filter { sample -> !sample.time.isBefore(session.startTime) && sample.time.isBefore(session.endTime) }
            .map { it.speed.inMetersPerSecond }
            .filter { it in 0.1..120.0 }
        if (speeds.isNotEmpty()) {
            stats.put("avgSpeed", (speeds.average() * 100).roundToInt() / 100.0)
            stats.put("maxSpeed", (speeds.max() * 100).roundToInt() / 100.0)
        }
        val gain = overlapping(elevation, session.startTime, session.endTime) { Triple(it.startTime, it.endTime, it.elevation.inMeters) }
        if (gain in 1.0..20000.0) stats.put("elevationGainM", gain.roundToInt())
        if (typeId in lapKinds && session.laps.isNotEmpty()) {
            val laps = session.laps.size
            if (laps in 1..500) stats.put("laps", laps)
        }
        return JSONObject()
            .put("garminId", id)
            .put("date", local.toLocalDate().toString())
            .put("typeId", typeId)
            .put("stats", stats)
    }

    private fun <T> overlapping(
        records: List<T>,
        start: Instant,
        end: Instant,
        span: (T) -> Triple<Instant, Instant, Double>
    ): Double {
        var total = 0.0
        records.forEach { record ->
            val (recordStart, recordEnd, value) = span(record)
            if (!recordEnd.isAfter(start) || !recordStart.isBefore(end) || value <= 0.0) return@forEach
            val whole = recordEnd.toEpochMilli() - recordStart.toEpochMilli()
            if (whole <= 0L) {
                total += value
                return@forEach
            }
            val from = maxOf(recordStart, start).toEpochMilli()
            val to = minOf(recordEnd, end).toEpochMilli()
            if (to > from) total += value * (to - from).toDouble() / whole.toDouble()
        }
        return total
    }

    private fun addSpan(start: Instant, end: Instant, total: Double, zone: ZoneId, into: MutableMap<LocalDate, Double>) {
        if (total < 0) return
        if (!end.isAfter(start)) {
            val date = start.atZone(zone).toLocalDate()
            into[date] = (into[date] ?: 0.0) + total
            return
        }
        var cursor = start
        val whole = end.toEpochMilli() - start.toEpochMilli()
        while (cursor.isBefore(end)) {
            val date = cursor.atZone(zone).toLocalDate()
            val next = date.plusDays(1).atStartOfDay(zone).toInstant()
            val sliceEnd = if (next.isBefore(end)) next else end
            val fraction = (sliceEnd.toEpochMilli() - cursor.toEpochMilli()).toDouble() / whole.toDouble()
            into[date] = (into[date] ?: 0.0) + total * fraction
            cursor = sliceEnd
        }
    }

    private suspend fun <T : Record> readAll(
        client: HealthConnectClient,
        type: KClass<T>,
        start: Instant,
        end: Instant
    ): List<T> {
        val out = mutableListOf<T>()
        var token: String? = null
        do {
            val page = client.readRecords(
                ReadRecordsRequest(
                    recordType = type,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    pageToken = token
                )
            )
            out += page.records
            token = page.pageToken
        } while (!token.isNullOrEmpty())
        return out
    }
}
