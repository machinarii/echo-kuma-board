package dev.kumaboard.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.net.URI
import java.net.URLEncoder

/** All user settings (F22). No secrets live here (F24). */
data class BoardConfig(
    val baseUrl: String = DEFAULT_BASE_URL,
    val slug: String = DEFAULT_SLUG,
    val refreshSeconds: Int = 30,
    val sortMode: SortMode = SortMode.KUMA_ORDER,
    /** 0 = automatic grid. */
    val columns: Int = 0,
    val dimEnabled: Boolean = true,
    val dimStartMinute: Int = 23 * 60,
    val dimEndMinute: Int = 7 * 60,
    /** Window brightness during the night window, 0.01..1. */
    val dimLevel: Float = 0.08f,
    /** F25: quack when a monitor goes down. Owner decision 2026-11-10: on by default. */
    val soundEnabled: Boolean = true,
    /** No sound inside this window, evaluated in [QUIET_ZONE] (Pacific). Default 22:00–09:00. */
    val quietStartMinute: Int = 22 * 60,
    val quietEndMinute: Int = 9 * 60,
    /** Monitors shown as one tile; see [CombineRule]. None by default. */
    val combine: List<CombineRule> = Combine.DEFAULT_RULES,
    /** Display names, hidden monitors and tile order; see [BoardLayout]. */
    val layout: BoardLayout = BoardLayout.DEFAULT,
    /** Weekly Claude/Codex usage file for the bottom strip; null hides the strip. See [UsageParser]. */
    val usageUrl: String? = null,
) {
    val nightWindow: NightWindow get() = NightWindow(dimStartMinute, dimEndMinute)
    val quietWindow: NightWindow get() = NightWindow(quietStartMinute, quietEndMinute)
    val refreshIntervalMs: Long get() = refreshSeconds * 1000L

    fun statusPageApiUrl(): String = "$baseUrl/api/status-page/${encodeSlug(slug)}"
    fun heartbeatApiUrl(): String = "$baseUrl/api/status-page/heartbeat/${encodeSlug(slug)}"

    /** Human-facing address, used in messages ("Connecting to …"). */
    fun host(): String = runCatching { URI(baseUrl).host }.getOrNull() ?: baseUrl

    companion object {
        const val DEFAULT_BASE_URL = "https://kuma.example.com"
        const val DEFAULT_SLUG = "echo"
        val REFRESH_RANGE = 10..300
        val COLUMN_RANGE = 0..10
        /** Quiet hours are always Pacific time, whatever the device's timezone. */
        const val QUIET_ZONE = "America/Los_Angeles"

        private fun encodeSlug(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}

sealed interface Validated<out T> {
    data class Ok<T>(val value: T) : Validated<T>
    data class Err(val message: String) : Validated<Nothing>
}

/**
 * Normalises and checks the server address (Postel's law on input, strict on output).
 *
 * Accepts a bare host ("monitor.example.net"), a base URL, or a whole status page URL
 * ("https://host/status/echo"), from which the slug is also extracted.
 *
 * Security (§9): HTTPS only, except plain HTTP to explicit private-network hosts.
 */
object ServerAddress {
    data class Parsed(val baseUrl: String, val slugFromUrl: String?)

    fun parse(input: String): Validated<Parsed> {
        var s = input.trim()
        if (s.isEmpty()) return Validated.Err("Enter the Kuma server address")
        if (!Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(s)) s = "https://$s"
        val uri = try {
            URI(s)
        } catch (_: Exception) {
            return Validated.Err("That address is not a valid URL")
        }
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase() ?: return Validated.Err("The address has no host name")
        when (scheme) {
            "https" -> Unit
            "http" -> if (!isPrivateHost(host)) {
                return Validated.Err("Use https:// — plain http is only allowed for private-network addresses")
            }
            else -> return Validated.Err("Only https:// addresses are supported")
        }
        if (uri.userInfo != null) return Validated.Err("Do not put credentials in the address")

        var path = (uri.rawPath ?: "").trimEnd('/')
        var slug: String? = null
        val m = Regex("^(.*)/status/([^/]+)$").find(path)
        if (m != null) {
            path = m.groupValues[1]
            slug = java.net.URLDecoder.decode(m.groupValues[2], "UTF-8")
        }
        val port = if (uri.port == -1) "" else ":${uri.port}"
        val hostPart = if (host.contains(':')) "[$host]" else host
        return Validated.Ok(Parsed("$scheme://$hostPart$port$path", slug))
    }

    fun isPrivateHost(host: String): Boolean {
        val h = host.trim('[', ']').lowercase()
        if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan") ||
            h.endsWith(".home.arpa") || h.endsWith(".internal") || h.endsWith(".ts.net")
        ) return true
        if (!h.contains('.') && !h.contains(':')) return true // single-label LAN name, e.g. "kuma"
        val v4 = h.split('.').mapNotNull { it.toIntOrNull() }
        if (v4.size == 4 && h.count { it == '.' } == 3 && v4.all { it in 0..255 }) {
            val (a, b) = v4
            return a == 10 || a == 127 ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) ||
                (a == 169 && b == 254) ||
                (a == 100 && b in 64..127) // CGNAT, used by Tailscale
        }
        if (h.contains(':')) return h == "::1" || h.startsWith("fd") || h.startsWith("fc") || h.startsWith("fe80")
        return false
    }

    fun normaliseSlug(input: String): Validated<String> {
        val s = input.trim().trim('/')
        if (s.isEmpty()) return Validated.Err("Enter the status page slug")
        if (!Regex("^[A-Za-z0-9._~-]+$").matches(s)) return Validated.Err("The slug may only contain letters, digits, '-', '_', '.' and '~'")
        return Validated.Ok(s)
    }
}

/**
 * Non-touch provisioning (F23): a JSON object, from a file on shared storage or an adb intent.
 *
 * ```json
 * { "url": "https://monitor.example.net/status/echo", "refreshSeconds": 30,
 *   "sort": "problems", "columns": 0, "dim": { "enabled": true, "start": "23:00", "end": "07:00", "level": 0.08 },
 *   "sound": true, "quiet": { "start": "22:00", "end": "09:00" },
 *   "usageUrl": "https://your-mac.example.ts.net/kuma-usage/usage.json" }
 * ```
 * `url` may be a full status page URL; `baseUrl` + `slug` are accepted too. `"usageUrl": ""` hides the
 * usage strip. Unknown keys are ignored.
 */
object Provisioning {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun applyJson(current: BoardConfig, body: String): Validated<BoardConfig> {
        val root = try {
            json.parseToJsonElement(body) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return Validated.Err("Provisioning file is not a JSON object")

        fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun int(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.let { it.intOrNull ?: it.content.toIntOrNull() }
        fun bool(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.content.toBooleanStrictOrNull() }
        fun dbl(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }

        val dim = root["dim"] as? JsonObject
        return apply(
            current,
            url = str(root, "url") ?: str(root, "baseUrl"),
            slug = str(root, "slug"),
            refreshSeconds = int(root, "refreshSeconds"),
            sort = str(root, "sort"),
            columns = int(root, "columns"),
            dimEnabled = dim?.let { bool(it, "enabled") } ?: bool(root, "dimEnabled"),
            dimStart = dim?.let { str(it, "start") } ?: str(root, "dimStart"),
            dimEnd = dim?.let { str(it, "end") } ?: str(root, "dimEnd"),
            dimLevel = dim?.let { dbl(it, "level") } ?: dbl(root, "dimLevel"),
            sound = bool(root, "sound"),
            quietStart = (root["quiet"] as? JsonObject)?.let { str(it, "start") } ?: str(root, "quietStart"),
            quietEnd = (root["quiet"] as? JsonObject)?.let { str(it, "end") } ?: str(root, "quietEnd"),
            combine = (root["combine"] as? kotlinx.serialization.json.JsonArray)?.let { parseCombine(it) ?: return Validated.Err("combine must be a list of {name, members:[...]}") },
            layout = if (root.containsKey("rename") || root.containsKey("hide") || root.containsKey("order")) {
                parseLayout(root, current.layout) ?: return Validated.Err("rename must be an object of names; hide and order must be lists of names")
            } else null,
            usageUrl = if (root.containsKey("usageUrl")) {
                str(root, "usageUrl") ?: return Validated.Err("usageUrl must be a string")
            } else null,
        )
    }

    fun parseCombine(arr: kotlinx.serialization.json.JsonArray): List<CombineRule>? = arr.map { el ->
        val o = el as? JsonObject ?: return null
        val name = (o["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val members = (o["members"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim()?.takeIf { s -> s.isNotEmpty() } }
            ?.takeIf { it.isNotEmpty() } ?: return null
        val mode = when ((o["mode"] as? JsonPrimitive)?.content?.trim()?.lowercase()) {
            null, "any" -> CombineRule.Mode.ANY
            "all" -> CombineRule.Mode.ALL
            else -> return null
        }
        val group = if (!o.containsKey("group")) null
            else (o["group"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        CombineRule(name, members, mode, group)
    }

    /** Keys present in [o] replace those parts of [base]; absent keys keep [base]'s value. */
    fun parseLayout(o: JsonObject, base: BoardLayout): BoardLayout? {
        var bad = false
        fun names(k: String): List<String>? {
            if (!o.containsKey(k)) return null
            val arr = o[k] as? kotlinx.serialization.json.JsonArray ?: run { bad = true; return null }
            return arr.mapNotNull {
                (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim()?.takeIf { s -> s.isNotEmpty() } ?: run { bad = true; null }
            }
        }
        val rename = if (!o.containsKey("rename")) base.rename else (o["rename"] as? JsonObject)?.entries?.associate { (k, v) ->
            k.trim() to ((v as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() } ?: return null)
        } ?: return null
        val hide = names("hide") ?: base.hide
        val order = names("order") ?: base.order
        return if (bad) null else BoardLayout(rename, hide, order)
    }

    fun layoutToJson(l: BoardLayout): String = kotlinx.serialization.json.buildJsonObject {
        put("rename", kotlinx.serialization.json.buildJsonObject { l.rename.forEach { (k, v) -> put(k, JsonPrimitive(v)) } })
        put("hide", kotlinx.serialization.json.JsonArray(l.hide.map { JsonPrimitive(it) }))
        put("order", kotlinx.serialization.json.JsonArray(l.order.map { JsonPrimitive(it) }))
    }.toString()

    fun layoutFromJson(s: String?): BoardLayout? = try {
        (s?.let { json.parseToJsonElement(it) } as? JsonObject)?.let { parseLayout(it, BoardLayout()) }
    } catch (_: Exception) { null }

    fun combineToJson(rules: List<CombineRule>): String = kotlinx.serialization.json.buildJsonArray {
        for (r in rules) add(kotlinx.serialization.json.buildJsonObject {
            put("name", JsonPrimitive(r.name))
            put("members", kotlinx.serialization.json.JsonArray(r.members.map { JsonPrimitive(it) }))
            put("mode", JsonPrimitive(r.mode.key))
            r.group?.let { put("group", JsonPrimitive(it)) }
        })
    }.toString()

    fun combineFromJson(s: String?): List<CombineRule>? = try {
        (s?.let { json.parseToJsonElement(it) } as? kotlinx.serialization.json.JsonArray)?.let { parseCombine(it) }
    } catch (_: Exception) { null }

    /** Shared by the JSON file, the adb broadcast extras, and the on-device settings form. */
    fun apply(
        current: BoardConfig,
        url: String? = null,
        slug: String? = null,
        refreshSeconds: Int? = null,
        sort: String? = null,
        columns: Int? = null,
        dimEnabled: Boolean? = null,
        dimStart: String? = null,
        dimEnd: String? = null,
        dimLevel: Double? = null,
        sound: Boolean? = null,
        quietStart: String? = null,
        quietEnd: String? = null,
        combine: List<CombineRule>? = null,
        layout: BoardLayout? = null,
        /** "" clears it. */
        usageUrl: String? = null,
    ): Validated<BoardConfig> {
        var c = current
        if (url != null) {
            when (val p = ServerAddress.parse(url)) {
                is Validated.Err -> return p
                is Validated.Ok -> {
                    c = c.copy(baseUrl = p.value.baseUrl)
                    if (p.value.slugFromUrl != null && slug == null) c = c.copy(slug = p.value.slugFromUrl)
                }
            }
        }
        if (slug != null) {
            when (val s = ServerAddress.normaliseSlug(slug)) {
                is Validated.Err -> return s
                is Validated.Ok -> c = c.copy(slug = s.value)
            }
        }
        if (refreshSeconds != null) {
            if (refreshSeconds !in BoardConfig.REFRESH_RANGE) return Validated.Err("Refresh must be 10–300 seconds")
            c = c.copy(refreshSeconds = refreshSeconds)
        }
        if (sort != null) {
            c = c.copy(sortMode = SortMode.fromKey(sort) ?: return Validated.Err("Sort must be \"kuma\" or \"problems\""))
        }
        if (columns != null) {
            if (columns !in BoardConfig.COLUMN_RANGE) return Validated.Err("Columns must be 0 (auto) to 10")
            c = c.copy(columns = columns)
        }
        if (dimEnabled != null) c = c.copy(dimEnabled = dimEnabled)
        if (dimStart != null) c = c.copy(dimStartMinute = NightWindow.parseHhMm(dimStart) ?: return Validated.Err("Dim start must be HH:MM"))
        if (dimEnd != null) c = c.copy(dimEndMinute = NightWindow.parseHhMm(dimEnd) ?: return Validated.Err("Dim end must be HH:MM"))
        if (dimLevel != null) {
            // Accept 0..1 or a percentage.
            val f = if (dimLevel > 1.0) dimLevel / 100.0 else dimLevel
            if (f !in 0.01..1.0) return Validated.Err("Dim level must be between 1% and 100%")
            c = c.copy(dimLevel = f.toFloat())
        }
        if (sound != null) c = c.copy(soundEnabled = sound)
        if (quietStart != null) c = c.copy(quietStartMinute = NightWindow.parseHhMm(quietStart) ?: return Validated.Err("Quiet start must be HH:MM"))
        if (combine != null) c = c.copy(combine = combine)
        if (layout != null) c = c.copy(layout = layout)
        if (usageUrl != null) {
            when (val u = UsageAddress.parse(usageUrl)) {
                is Validated.Err -> return u
                is Validated.Ok -> c = c.copy(usageUrl = u.value)
            }
        }
        if (quietEnd != null) c = c.copy(quietEndMinute = NightWindow.parseHhMm(quietEnd) ?: return Validated.Err("Quiet end must be HH:MM"))
        return Validated.Ok(c)
    }
}
