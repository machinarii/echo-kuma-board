package dev.kumaboard.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField

/**
 * Defensive parser for Uptime Kuma's public status page endpoints (F1, F5, F6).
 *
 * Everything is read through [JsonElement] rather than strict data classes, so
 * renamed, missing or mistyped fields degrade to "no data" instead of throwing.
 * Only a body that is not JSON at all (or not an object) is reported as an error.
 */
object KumaParser {

    data class MonitorRef(val id: Int, val name: String)
    data class StatusPage(val title: String?, val monitors: List<MonitorRef>)
    data class Heartbeats(val beats: Map<Int, List<Heartbeat>>, val uptime24: Map<Int, Double>)

    class ParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** `GET /api/status-page/<slug>` */
    fun parseStatusPage(body: String): StatusPage {
        val root = parseObject(body)
        val title = root.obj("config")?.str("title")
        val seen = HashSet<Int>()
        val monitors = ArrayList<MonitorRef>()
        // Groups are flattened in Kuma order (F3). A monitor listed in two groups shows once.
        for (group in root.arr("publicGroupList").orEmpty()) {
            val list = (group as? JsonObject)?.arr("monitorList") ?: continue
            for (m in list) {
                val mo = m as? JsonObject ?: continue
                val id = mo.int("id") ?: continue
                if (!seen.add(id)) continue
                val name = mo.str("name")?.trim().takeUnless { it.isNullOrEmpty() } ?: "Monitor $id"
                monitors += MonitorRef(id, name)
            }
        }
        return StatusPage(title, monitors)
    }

    /** `GET /api/status-page/heartbeat/<slug>` */
    fun parseHeartbeats(body: String): Heartbeats {
        val root = parseObject(body)
        val beats = HashMap<Int, List<Heartbeat>>()
        root.obj("heartbeatList")?.forEach { (key, value) ->
            val id = key.toIntOrNull() ?: return@forEach
            val list = (value as? JsonArray)?.mapNotNull { parseBeat(it) }.orEmpty()
            // Kuma sends oldest-first today; sort anyway so the bar never depends on it.
            // Beats without a parseable time keep their relative position (stable sort, nulls first).
            beats[id] = list.sortedWith(compareBy(nullsFirst()) { it.timeMs })
        }
        val uptime = HashMap<Int, Double>()
        root.obj("uptimeList")?.forEach { (key, value) ->
            // Keys look like "12_24" (monitor 12, 24 h window). Accept a bare "12" too.
            val parts = key.split('_')
            val id = parts[0].toIntOrNull() ?: return@forEach
            if (parts.size > 1 && parts[1] != "24") return@forEach
            val raw = (value as? JsonPrimitive)?.doubleOrNull ?: return@forEach
            if (raw.isNaN() || raw < 0) return@forEach
            // Kuma reports a 0..1 fraction. Tolerate a percentage in case that ever changes.
            uptime[id] = if (raw > 1.0) (raw / 100.0).coerceAtMost(1.0) else raw
        }
        return Heartbeats(beats, uptime)
    }

    fun combine(page: StatusPage, hb: Heartbeats): Board = Board(
        title = page.title,
        monitors = page.monitors.map { ref ->
            Monitor(
                id = ref.id,
                name = ref.name,
                beats = hb.beats[ref.id].orEmpty(),
                uptime24 = hb.uptime24[ref.id],
            )
        },
    )

    private fun parseBeat(e: JsonElement): Heartbeat? {
        val o = e as? JsonObject ?: return null
        val statusEl = o["status"] as? JsonPrimitive
        val code = statusEl?.intOrNull ?: statusEl?.booleanOrNull?.let { if (it) 1 else 0 }
        return Heartbeat(MonitorStatus.fromCode(code), o.str("time")?.let { parseKumaTime(it) })
    }

    private fun parseObject(body: String): JsonObject {
        val el = try {
            json.parseToJsonElement(body)
        } catch (t: Throwable) {
            throw ParseException("Response is not JSON", t)
        }
        return el as? JsonObject ?: throw ParseException("Response is not a JSON object")
    }

    private val kumaFormat: DateTimeFormatter = DateTimeFormatterBuilder()
        .appendPattern("yyyy-MM-dd")
        .optionalStart().appendLiteral(' ').optionalEnd()
        .optionalStart().appendLiteral('T').optionalEnd()
        .appendPattern("HH:mm:ss")
        .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd()
        .toFormatter()

    /** Kuma heartbeat times are UTC "YYYY-MM-DD HH:mm:ss.SSS" (F6). ISO with an offset is also accepted. */
    fun parseKumaTime(s: String): Long? {
        val t = s.trim()
        if (t.isEmpty()) return null
        return try {
            if (t.endsWith("Z") || Regex("[+-]\\d{2}:?\\d{2}$").containsMatchIn(t.substringAfter('T', ""))) {
                OffsetDateTime.parse(t.replace(' ', 'T')).toInstant().toEpochMilli()
            } else {
                LocalDateTime.parse(t, kumaFormat).toInstant(ZoneOffset.UTC).toEpochMilli()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun JsonObject.obj(k: String) = this[k] as? JsonObject
    private fun JsonObject.arr(k: String) = this[k] as? JsonArray
    private fun JsonObject.str(k: String): String? =
        (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    private fun JsonObject.int(k: String): Int? {
        val p = this[k] as? JsonPrimitive ?: return null
        return p.intOrNull ?: p.content.toIntOrNull()
    }
}
