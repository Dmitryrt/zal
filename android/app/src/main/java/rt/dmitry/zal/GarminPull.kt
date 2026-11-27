package rt.dmitry.zal

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.json.JSONArray
import org.json.JSONObject

object GarminPull {
    private const val BASE = "https://personal.connectapi.garmin.com"
    private val zone = ZoneId.of("Europe/Moscow")
    private val dayFormat = DateTimeFormatter.ISO_LOCAL_DATE
    private val dateRe = Regex("""^\d{4}-\d{2}-\d{2}$""")
    private val calorieKeys = listOf(
        "burnedActive",
        "burned_active",
        "activeKilocalories",
        "activeCalories",
        "activeKcal"
    )
    private val distanceKeys = listOf(
        "totalDistanceMeters",
        "totalDistanceInMeters",
        "distanceInMeters",
        "distanceMeters",
        "totalDistance",
        "total_distance",
        "distance"
    )
    private val kinds = mapOf(
        "cycling" to "bike",
        "road_biking" to "bike",
        "road_cycling" to "bike",
        "mountain_biking" to "bike",
        "downhill_mountain_biking" to "bike",
        "enduro_mountain_biking" to "bike",
        "e_enduro_mountain_biking" to "bike",
        "e_biking" to "bike",
        "e_mountain_biking" to "bike",
        "gravel_cycling" to "bike",
        "cyclocross" to "bike",
        "bmx" to "bike",
        "indoor_cycling" to "bike",
        "virtual_ride" to "bike",
        "virtual_cycling" to "bike",
        "track_cycling" to "bike",
        "recumbent_cycling" to "bike",
        "handcycling" to "bike",
        "indoor_handcycling" to "bike",
        "motocross" to "enduro",
        "lap_swimming" to "pool",
        "pool_swim" to "pool",
        "resort_snowboarding" to "snowboard",
        "backcountry_snowboarding" to "snowboard",
        "wakeboarding" to "wakeboard",
        "strength_training" to "gym",
        "stand_up_paddleboarding" to "sup",
        "running" to "run",
        "street_running" to "run",
        "trail_running" to "run",
        "treadmill_running" to "run",
        "track_running" to "run",
        "indoor_running" to "run",
        "virtual_running" to "run",
        "ultra_running" to "run",
        "obstacle_course_racing" to "run",
        "walking" to "walk",
        "casual_walking" to "walk",
        "speed_walking" to "walk"
    )
    private val lapTypes = setOf("snowboard", "wakeboard", "enduro", "pool")

    class Failure(val auth: Boolean) : RuntimeException(if (auth) "auth" else "network")

    @JvmStatic
    fun fetch(apiKey: String): String {
        val key = apiKey.trim()
        if (key.isEmpty()) throw Failure(false)
        val token = bearer(key)
        val today = LocalDate.now(zone)
        var steps = linkedMapOf<String, Int>()
        var distance = linkedMapOf<String, Int>()
        var last: Exception? = null
        for (span in listOf(27, 20, 13)) {
            val start = today.minusDays(span.toLong()).format(dayFormat)
            try {
                val pair = fetchSteps(token, start, today.format(dayFormat))
                steps = pair.first
                distance = pair.second
                last = null
                break
            } catch (error: Exception) {
                last = error
            }
        }
        if (last != null) throw last
        val start = steps.keys.minOrNull() ?: today.minusDays(27).format(dayFormat)
        val calories = linkedMapOf<String, Int>()
        try {
            val pair = fetchCalories(token, start, today.format(dayFormat))
            calories.putAll(pair.first)
            pair.second.forEach { (day, meters) -> distance.putIfAbsent(day, meters) }
        } catch (_: Exception) {
        }
        val activities = try {
            fetchActivities(token, start, today.format(dayFormat))
        } catch (_: Exception) {
            JSONArray()
        }
        val days = JSONObject()
        (steps.keys + calories.keys + distance.keys).sorted().forEach { day ->
            val row = JSONObject()
            steps[day]?.let { row.put("steps", it) }
            calories[day]?.let { row.put("activeCalories", it) }
            distance[day]?.let { row.put("distanceM", it) }
            if (row.length() > 0) days.put(day, row)
        }
        return JSONObject().put("days", days).put("activities", activities).toString()
    }

    private fun bearer(apiKey: String): String {
        val raw = request(
            "POST",
            "$BASE/personalapitoken-service/personal-api/personal-api-key/token",
            mapOf("Accept" to "*/*", "Authorization" to "ApiKey $apiKey")
        ).trim().trim('"')
        if (raw.startsWith("{")) {
            val token = JSONObject(raw).optString("access_token").ifEmpty {
                JSONObject(raw).optString("accessToken")
            }
            if (token.isEmpty()) throw Failure(true)
            return token
        }
        if (!raw.contains(".")) throw Failure(true)
        return raw
    }

    private fun fetchSteps(token: String, start: String, end: String): Pair<LinkedHashMap<String, Int>, LinkedHashMap<String, Int>> {
        val raw = request(
            "GET",
            "$BASE/usersummary-service/stats/steps/daily/$start/$end",
            mapOf("Accept" to "application/json", "Authorization" to "Bearer $token")
        )
        return parseSteps(raw)
    }

    private fun fetchCalories(token: String, start: String, end: String): Pair<LinkedHashMap<String, Int>, LinkedHashMap<String, Int>> {
        val query = "startDate=${enc(start)}&endDate=${enc(end)}"
        val raw = request(
            "GET",
            "$BASE/usersummary-service/v2/dailysummaries?$query",
            mapOf("Accept" to "application/json", "Authorization" to "Bearer $token")
        )
        return parseCalories(raw)
    }

    private fun fetchActivities(token: String, start: String, end: String): JSONArray {
        val rows = JSONArray()
        var offset = 0
        while (offset < 200) {
            val query = "start=$offset&limit=20&startDate=${enc(start)}&endDate=${enc(end)}"
            val raw = request(
                "GET",
                "$BASE/activitylist-service/activities/search/activities?$query",
                mapOf("Accept" to "application/json", "Authorization" to "Bearer $token")
            )
            val page = activityRows(raw)
            for (i in 0 until page.length()) rows.put(page.get(i))
            if (page.length() < 20) break
            offset += 20
        }
        return compactActivities(rows)
    }

    private fun request(method: String, url: String, headers: Map<String, String>): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20000
            readTimeout = 45000
            instanceFollowRedirects = true
            doInput = true
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
            if (method == "POST") doOutput = true
        }
        return try {
            if (method == "POST") conn.outputStream.use { }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code == 401 || code == 403) throw Failure(true)
            if (code !in 200..299) throw Failure(false)
            text
        } catch (error: Failure) {
            throw error
        } catch (_: Exception) {
            throw Failure(false)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseSteps(raw: String): Pair<LinkedHashMap<String, Int>, LinkedHashMap<String, Int>> {
        val steps = linkedMapOf<String, Int>()
        val distance = linkedMapOf<String, Int>()
        for (row in stepRows(raw)) {
            val key = row.optString("calendarDate").ifEmpty {
                row.optString("calendar_date").ifEmpty { row.optString("date") }
            }
            val count = asInt(first(row, "totalSteps", "total_steps", "steps"), 0, 200000)
            if (dateRe.matches(key) && count != null) steps[key] = count
            val meters = pickDistance(row)
            if (dateRe.matches(key) && meters != null) distance[key] = meters
        }
        return steps to distance
    }

    private fun parseCalories(raw: String): Pair<LinkedHashMap<String, Int>, LinkedHashMap<String, Int>> {
        val calories = linkedMapOf<String, Int>()
        val distance = linkedMapOf<String, Int>()
        for (row in summaryRows(raw)) {
            val key = row.optString("calendarDate").ifEmpty {
                row.optString("calendar_date").ifEmpty { row.optString("date") }
            }
            if (!dateRe.matches(key)) continue
            pickCalories(row)?.let { calories[key] = it }
            pickDistance(row)?.let { distance[key] = it }
        }
        return calories to distance
    }

    private fun stepRows(raw: String): List<JSONObject> {
        val text = raw.trim()
        if (text.startsWith("[")) return objects(JSONArray(text))
        if (!text.startsWith("{")) return emptyList()
        val obj = JSONObject(text)
        val values = obj.opt("values")
        if (values is JSONArray) return objects(values)
        if (values is JSONObject) {
            val rows = mutableListOf<JSONObject>()
            val keys = values.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = values.opt(key)
                if (value is JSONObject) {
                    value.put("calendarDate", key)
                    rows.add(value)
                } else {
                    rows.add(JSONObject().put("calendarDate", key).put("totalSteps", value))
                }
            }
            return rows
        }
        if (obj.has("calendarDate") || obj.has("calendar_date")) return listOf(obj)
        return emptyList()
    }

    private fun summaryRows(raw: String): List<JSONObject> {
        val text = raw.trim()
        if (text.startsWith("[")) return objects(JSONArray(text))
        if (!text.startsWith("{")) return emptyList()
        val obj = JSONObject(text)
        for (key in listOf("data", "dailySummaries", "summaries", "values")) {
            val value = obj.opt(key)
            if (value is JSONArray) return objects(value)
        }
        if (obj.has("calendarDate") || obj.has("calendar_date")) return listOf(obj)
        return emptyList()
    }

    private fun activityRows(raw: String): JSONArray {
        val text = raw.trim()
        if (text.startsWith("[")) return JSONArray(text)
        if (!text.startsWith("{")) return JSONArray()
        val obj = JSONObject(text)
        for (key in listOf("activities", "activityList", "items")) {
            val value = obj.opt(key)
            if (value is JSONArray) return value
        }
        return JSONArray()
    }

    private fun compactActivities(rows: JSONArray): JSONArray {
        val seen = mutableSetOf<String>()
        val out = mutableListOf<JSONObject>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val typeId = kinds[activityTypeKey(row)] ?: continue
            val day = activityDay(row) ?: continue
            val id = garminId(row)
            if (id.isEmpty() || !seen.add(id)) continue
            val item = JSONObject().put("garminId", id).put("date", day).put("typeId", typeId)
            val stats = activityStats(row, typeId)
            if (stats.length() > 0) item.put("stats", stats)
            out.add(item)
        }
        out.sortWith(compareBy({ it.getString("date") }, { it.getString("garminId") }))
        val array = JSONArray()
        out.forEach { array.put(it) }
        return array
    }

    private fun activityTypeKey(row: JSONObject): String {
        for (field in listOf("activityType", "activityTypeDTO")) {
            val value = row.opt(field)
            if (value is JSONObject) {
                val key = value.optString("typeKey").ifEmpty { value.optString("type_key") }
                if (key.isNotEmpty()) return key
            } else if (value is String) return value
        }
        return row.optString("activityTypeKey").ifEmpty { row.optString("typeKey") }
    }

    private fun activityDay(row: JSONObject): String? {
        val local = row.optString("startTimeLocal").ifEmpty { row.optString("startTimeGMT") }
        return if (local.length >= 10 && dateRe.matches(local.substring(0, 10))) local.substring(0, 10) else null
    }

    private fun activityClock(row: JSONObject): String? {
        val local = row.optString("startTimeLocal")
        if (local.length >= 16 && Regex("""\d{2}:\d{2}""").matches(local.substring(11, 16))) return local.substring(11, 16)
        return null
    }

    private fun garminId(row: JSONObject): String {
        val value = if (row.has("activityId")) row.opt("activityId") else row.opt("activity_id")
        val text = when (value) {
            is Number -> {
                val number = value.toDouble()
                if (number % 1.0 == 0.0) number.toLong().toString() else return ""
            }
            is String -> value.trim()
            else -> return ""
        }
        return if (text.all { it.isDigit() }) text else ""
    }

    private fun activityStats(row: JSONObject, typeId: String): JSONObject {
        val stats = JSONObject()
        activityClock(row)?.let { stats.put("start", it) }
        asInt(first(row, "duration", "movingDuration"), 1, 86400)?.let { stats.put("durationSec", it) }
        asInt(row.opt("distance"), 1, 500000)?.let { stats.put("distanceM", it) }
        asInt(row.opt("calories"), 1, 20000)?.let { stats.put("calories", it) }
        asInt(row.opt("averageHR"), 30, 230)?.let { stats.put("avgHr", it) }
        asInt(row.opt("maxHR"), 30, 230)?.let { stats.put("maxHr", it) }
        asFloat(row.opt("averageSpeed"), 0.1, 120.0)?.let { stats.put("avgSpeed", it) }
        asFloat(row.opt("maxSpeed"), 0.1, 120.0)?.let { stats.put("maxSpeed", it) }
        asInt(row.opt("elevationGain"), 1, 20000)?.let { stats.put("elevationGainM", it) }
        asInt(row.opt("elevationLoss"), 1, 20000)?.let { stats.put("elevationLossM", it) }
        asInt(row.opt("maxElevation"), 1, 9000)?.let { stats.put("maxElevationM", it) }
        if (typeId in lapTypes) asInt(row.opt("lapCount"), 1, 500)?.let { stats.put("laps", it) }
        return stats
    }

    private fun pickCalories(row: JSONObject): Int? {
        val nested = row.optJSONObject("calories")
        if (nested != null) {
            for (key in calorieKeys) asInt(nested.opt(key), 0, 20000)?.let { return it }
        }
        for (key in calorieKeys) asInt(row.opt(key), 0, 20000)?.let { return it }
        return null
    }

    private fun pickDistance(row: JSONObject): Int? {
        val blobs = listOfNotNull(row, row.optJSONObject("movement"))
        for (blob in blobs) {
            for (key in distanceKeys) asInt(blob.opt(key), 0, 200000)?.let { return it }
        }
        return null
    }

    private fun objects(array: JSONArray): List<JSONObject> {
        val rows = mutableListOf<JSONObject>()
        for (i in 0 until array.length()) array.optJSONObject(i)?.let { rows.add(it) }
        return rows
    }

    private fun first(row: JSONObject, vararg keys: String): Any? {
        for (key in keys) {
            if (!row.has(key) || row.isNull(key)) continue
            return row.opt(key)
        }
        return null
    }

    private fun asInt(value: Any?, lo: Int, hi: Int): Int? {
        if (value == null || value == JSONObject.NULL) return null
        val number = when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull() ?: return null
            else -> return null
        }
        if (!number.isFinite()) return null
        val count = kotlin.math.round(number).toInt()
        return if (count in lo..hi) count else null
    }

    private fun asFloat(value: Any?, lo: Double, hi: Double): Double? {
        val number = when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull() ?: return null
            else -> return null
        }
        if (!number.isFinite() || number < lo || number > hi) return null
        return kotlin.math.round(number * 100.0) / 100.0
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
}
