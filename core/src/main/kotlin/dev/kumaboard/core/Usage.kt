package dev.kumaboard.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Weekly Claude and Codex plan usage for the bottom strip.
 *
 * The board reads one small JSON file that a computer running Claude Code or Codex publishes (`tools/ai-usage`). It
 * holds numbers only; no tokens or account data ever reach the board:
 *
 * ```json
 * { "version": 1, "generated_at": 1795000000,
 *   "claude": { "week_pct": 42.0, "resets_at": 1795300000, "as_of": 1794999000 },
 *   "codex":  { "week_pct": 75.0, "resets_at": 1795400000, "as_of": 1794998000 },
 *   "models": [ { "tool": "claude", "name": "Fable", "week_pct": 61.0, "resets_at": 1795300000, "as_of": 1794999000 } ] }
 * ```
 * Times are Unix epoch seconds. A tool whose numbers are missing or invalid is shown as unknown.
 * `models` (optional) holds the separate weekly windows for frontier models; invalid entries are dropped.
 */
data class UsageMeter(val percent: Double, val resetsAtMs: Long?, val asOfMs: Long)

/** A per-model weekly window under one tool ("claude" or "codex"), e.g. Claude's "Fable" week. */
data class ModelMeter(val tool: String, val name: String, val meter: UsageMeter)

data class UsageReport(val claude: UsageMeter?, val codex: UsageMeter?, val models: List<ModelMeter> = emptyList())

object UsageParser {
    const val MAX_BYTES = 16L * 1024
    class ParseException(message: String) : Exception(message)

    private val json = Json { ignoreUnknownKeys = true }
    /** Epoch seconds between 2020 and 2100; anything else is junk. */
    private val SANE_SECONDS = 1_577_836_800L..4_102_444_800L

    fun parse(body: String): UsageReport {
        if (body.length > MAX_BYTES) throw ParseException("Usage file too large")
        val root = try {
            json.parseToJsonElement(body) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: throw ParseException("Usage file is not a JSON object")
        return UsageReport(meter(root["claude"]), meter(root["codex"]), models(root["models"]))
    }

    /** At most this many model windows are kept; the strip has room for few. */
    const val MAX_MODELS = 4
    val TOOLS = listOf("claude", "codex")
    /** Short display names only: letters, digits, space and . + - (no control or layout characters). */
    private val NAME = Regex("^[A-Za-z0-9][A-Za-z0-9 .+-]{0,15}$")

    private fun models(el: Any?): List<ModelMeter> {
        val arr = el as? JsonArray ?: return emptyList()
        return arr.asSequence().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val tool = (o["tool"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it in TOOLS }
                ?: return@mapNotNull null
            val name = (o["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { NAME.matches(it) }
                ?: return@mapNotNull null
            val m = meter(o) ?: return@mapNotNull null
            ModelMeter(tool, name, m)
        }.distinctBy { it.tool to it.name.lowercase() }.take(MAX_MODELS).toList()
    }

    private fun meter(el: Any?): UsageMeter? {
        val o = el as? JsonObject ?: return null
        val pct = (o["week_pct"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: return null
        if (pct.isNaN() || pct < 0.0 || pct > 1000.0) return null
        val asOf = seconds(o["as_of"]) ?: return null
        return UsageMeter(pct.coerceAtMost(100.0), seconds(o["resets_at"]), asOf)
    }

    private fun seconds(el: Any?): Long? {
        val p = el as? JsonPrimitive ?: return null
        if (p.isString) return null
        val s = p.longOrNull ?: p.doubleOrNull?.toLong() ?: return null
        return if (s in SANE_SECONDS) s * 1000 else null
    }
}

/** What the strip shows for each tool. Pure, so the rules are unit-tested. */
object UsageDisplay {
    enum class Level { NORMAL, WARN, CRITICAL, UNKNOWN }

    const val WARN_AT = 80.0
    const val CRITICAL_AT = 95.0
    /** Numbers older than this are greyed and labelled with their age. */
    const val OLD_AFTER_MS = 24 * 3_600_000L

    data class Item(
        val name: String,
        /** What is left of the weekly window: "58%" (drawn as "58% left"), or "—". */
        val percentText: String,
        /** 0..1 of the window left, for the meter. */
        val fraction: Float,
        val level: Level,
        /** "resets Thu 9 AM", "as of Mon 3 PM", "window reset" … */
        val detail: String?,
        /** Greyed: the number may be out of date. */
        val old: Boolean,
        /** A per-model window drawn right after its tool, as part of that tool's group. */
        val sub: Boolean = false,
    )

    /**
     * Claude, its model windows, Codex, its model windows. A model window repeats its reset time only
     * when it differs from the tool's. When none differs, the tool's reset text moves to the end of
     * its group: "Claude ▬ 58% left  Fable ▬ 17%  resets Fri 12 AM".
     */
    fun items(report: UsageReport?, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): List<Item> =
        listOf("claude" to "Claude", "codex" to "Codex").flatMap { (tool, label) ->
            val main = item(label, if (tool == "claude") report?.claude else report?.codex, nowMs, zone)
            val subs = report?.models.orEmpty().filter { it.tool == tool }.map { mm ->
                val sub = item(mm.name, mm.meter, nowMs, zone).copy(sub = true)
                if (sub.detail != null && sub.detail == main.detail) sub.copy(detail = null) else sub
            }
            if (subs.isNotEmpty() && subs.all { it.detail == null } && main.detail != null) {
                listOf(main.copy(detail = null)) + subs.dropLast(1) + subs.last().copy(detail = main.detail)
            } else {
                listOf(main) + subs
            }
        }

    fun item(name: String, m: UsageMeter?, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Item {
        if (m == null) return Item(name, "—", 0f, Level.UNKNOWN, null, old = false)
        // The window has rolled over since the last reading: the old number no longer applies.
        if (m.resetsAtMs != null && m.resetsAtMs <= nowMs) {
            return Item(name, "—", 0f, Level.UNKNOWN, "window reset", old = true)
        }
        val pct = m.percent.coerceIn(0.0, 100.0)
        val level = when {
            pct >= CRITICAL_AT -> Level.CRITICAL
            pct >= WARN_AT -> Level.WARN
            else -> Level.NORMAL
        }
        val left = 100.0 - pct
        val old = nowMs - m.asOfMs > OLD_AFTER_MS
        val detail = when {
            old -> "as of ${when_(m.asOfMs, zone)}"
            m.resetsAtMs != null -> "resets ${when_(m.resetsAtMs, zone)}"
            else -> null
        }
        return Item(name, "${left.roundToInt()}%", (left / 100.0).toFloat(), level, detail, old)
    }

    /**
     * Meter colour by how much is left: red when empty, through peach and yellow, to green when full.
     * Interpolated between the palette's own colours, so it follows the night variant too.
     */
    fun meterColor(fractionLeft: Float, p: Palette): Int {
        val stops = listOf(0f to p.red, 0.25f to p.peach, 0.5f to p.yellow, 1f to p.green)
        val f = fractionLeft.coerceIn(0f, 1f)
        val i = stops.indexOfLast { it.first <= f }.coerceAtMost(stops.size - 2)
        val (f0, c0) = stops[i]
        val (f1, c1) = stops[i + 1]
        return Colors.lerp(c0, c1, (f - f0) / (f1 - f0))
    }

    private val dayHour = DateTimeFormatter.ofPattern("EEE h a", Locale.US)
    private fun when_(ms: Long, zone: ZoneId) = dayHour.format(Instant.ofEpochMilli(ms).atZone(zone))
}

/** The usage file address: https, or plain http only to a private-network host; no credentials. */
object UsageAddress {
    /** Blank input clears the setting (the strip is hidden). */
    fun parse(input: String): Validated<String?> {
        val s = input.trim()
        if (s.isEmpty()) return Validated.Ok(null)
        val uri = try {
            URI(if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(s)) s else "https://$s")
        } catch (_: Exception) {
            return Validated.Err("usageUrl is not a valid URL")
        }
        val host = uri.host?.lowercase() ?: return Validated.Err("usageUrl has no host name")
        when (uri.scheme?.lowercase()) {
            "https" -> Unit
            "http" -> if (!ServerAddress.isPrivateHost(host)) return Validated.Err("usageUrl must use https:// outside a private network")
            else -> return Validated.Err("usageUrl must be an https:// address")
        }
        if (uri.userInfo != null || uri.rawQuery != null || uri.rawFragment != null) {
            return Validated.Err("usageUrl must not contain credentials, a query or a fragment")
        }
        return Validated.Ok(uri.toString())
    }
}
