package dev.kumaboard.core

import kotlin.math.min
import kotlin.math.pow

enum class SortMode(val key: String) {
    KUMA_ORDER("kuma"),
    PROBLEMS_FIRST("problems");

    companion object {
        fun fromKey(s: String?): SortMode? = when (s?.trim()?.lowercase()) {
            "kuma", "kuma_order", "kuma-order", "default" -> KUMA_ORDER
            "problems", "problems_first", "problems-first" -> PROBLEMS_FIRST
            else -> null
        }
    }
}

object Sorting {
    /** F12. Stable: within a status bucket, Kuma order is kept. */
    fun apply(monitors: List<Monitor>, mode: SortMode): List<Monitor> = when (mode) {
        SortMode.KUMA_ORDER -> monitors
        SortMode.PROBLEMS_FIRST -> monitors.sortedBy { it.status.urgency }
    }
}

/** F4: keep last good data; mark stale after 2 missed refreshes. */
object Freshness {
    const val MISSED_REFRESHES_FOR_STALE = 2

    fun isStale(nowMs: Long, lastSuccessMs: Long?, consecutiveFailures: Int, refreshIntervalMs: Long): Boolean {
        if (lastSuccessMs == null) return consecutiveFailures > 0
        if (consecutiveFailures >= MISSED_REFRESHES_FOR_STALE) return true
        // Also catch a loop that silently stopped running (no failures counted, just no successes).
        return nowMs - lastSuccessMs > refreshIntervalMs * (MISSED_REFRESHES_FOR_STALE + 0.5)
    }
}

/** F18: exponential backoff capped at 60 s, never slower than the normal refresh interval. */
object Backoff {
    const val CAP_MS = 60_000L
    private const val BASE_MS = 2_000L

    fun delayMs(consecutiveFailures: Int, refreshIntervalMs: Long): Long {
        if (consecutiveFailures <= 0) return refreshIntervalMs
        val exp = BASE_MS * 2.0.pow((consecutiveFailures - 1).coerceAtMost(10))
        return min(min(exp.toLong(), CAP_MS), refreshIntervalMs)
    }
}

/** F20: night window in minutes since midnight; handles windows that wrap past midnight. */
data class NightWindow(val startMinute: Int, val endMinute: Int) {
    fun contains(minuteOfDay: Int): Boolean = when {
        startMinute == endMinute -> false
        startMinute < endMinute -> minuteOfDay in startMinute until endMinute
        else -> minuteOfDay >= startMinute || minuteOfDay < endMinute
    }

    companion object {
        fun parseHhMm(s: String?): Int? {
            val m = Regex("^\\s*(\\d{1,2}):(\\d{2})\\s*$").find(s ?: return null) ?: return null
            val h = m.groupValues[1].toInt()
            val mm = m.groupValues[2].toInt()
            if (h !in 0..23 || mm !in 0..59) return null
            return h * 60 + mm
        }

        fun formatHhMm(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)
    }
}

/** Status transitions between two refreshes, for the optional audible cue (F25). */
object Transitions {
    data class Result(val wentDown: List<Monitor>, val recovered: List<Monitor>)

    fun diff(previous: Board?, next: Board): Result {
        if (previous == null) return Result(emptyList(), emptyList())
        val prev = previous.monitors.associate { it.id to it.status }
        val down = ArrayList<Monitor>()
        val up = ArrayList<Monitor>()
        for (m in next.monitors) {
            val before = prev[m.id] ?: continue
            if (m.status == MonitorStatus.DOWN && before != MonitorStatus.DOWN) down += m
            if (m.status == MonitorStatus.UP && before == MonitorStatus.DOWN) up += m
        }
        return Result(down, up)
    }
}

/**
 * When to quack (F25). Owner rules, 2026-11-10:
 * - only for a monitor that has been DOWN continuously for more than [THRESHOLD_MS] (5 min),
 *   measured from Kuma's heartbeat times, so a board restart does not reset the clock;
 * - once per outage: a monitor that has been announced stays announced until it is seen not DOWN
 *   (keyed by monitor id; Kuma's "down since" time can drift once the outage outlives the
 *   ~100 beats the status page returns, so it is not part of the key);
 * - never 22:00–09:00 Pacific. An outage that crosses the threshold during quiet hours and is
 *   still down when they end quacks then, once.
 */
object SoundPolicy {
    const val THRESHOLD_MS = 5 * 60_000L
    private val zone = java.time.ZoneId.of(BoardConfig.QUIET_ZONE)

    data class Result(
        /** Monitors to announce now (non-empty means play the sound once). */
        val announce: List<Monitor>,
        /** Outage keys already announced, pruned to outages still in progress. Persist this. */
        val announced: Set<String>,
        /** Local first-seen-down times, used only when Kuma sent no heartbeat times. */
        val firstSeenDown: Map<Int, Long>,
    )

    fun minuteOfDayPacific(nowMs: Long): Int {
        val t = java.time.Instant.ofEpochMilli(nowMs).atZone(zone)
        return t.hour * 60 + t.minute
    }

    fun isQuiet(nowMs: Long, config: BoardConfig): Boolean = config.quietWindow.contains(minuteOfDayPacific(nowMs))

    fun evaluate(
        board: Board,
        nowMs: Long,
        config: BoardConfig,
        announced: Set<String>,
        firstSeenDown: Map<Int, Long>,
    ): Result {
        val canPlay = config.soundEnabled && !isQuiet(nowMs, config)
        val seen = HashMap<Int, Long>()
        val stillDown = HashSet<String>()
        val announce = ArrayList<Monitor>()
        for (m in board.monitors) {
            if (m.status != MonitorStatus.DOWN) continue
            val since = m.statusSinceMs ?: (firstSeenDown[m.id] ?: nowMs).also { seen[m.id] = it }
            val key = m.id.toString()
            if (key in announced) {
                stillDown += key
            } else if (nowMs - since > THRESHOLD_MS && canPlay) {
                announce += m
                stillDown += key
            }
        }
        return Result(announce, stillDown, seen)
    }
}

object Format {
    /**
     * 24 h uptime as shown on the tile. Rounds down so 99.996% never reads as a clean 100%.
     * [decimals] is chosen by the renderer to fit the tile.
     */
    fun uptime(fraction: Double?, decimals: Int = 2): String {
        if (fraction == null || fraction.isNaN()) return "--"
        val pct = fraction.coerceIn(0.0, 1.0) * 100.0
        if (pct >= 100.0) return "100%"
        val scale = 10.0.pow(decimals)
        val floored = kotlin.math.floor(pct * scale) / scale
        return "%.${decimals}f%%".format(java.util.Locale.ROOT, floored)
    }

    /** Short elapsed label for the oldest beat on the bar: "45s", "12m", "3h", "2d". */
    fun elapsed(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m"
            s < 86_400 -> "${s / 3600}h"
            else -> "${s / 86_400}d"
        }
    }
}

/** Owner rule 2026-11-10: every sound stream is set to 40% when the app starts. */
object StartupVolume {
    const val PERCENT = 40

    /** The stream index for [PERCENT]% of a stream's range, rounded, kept within [min]..[max]. */
    fun level(min: Int, max: Int, percent: Int = PERCENT): Int {
        if (max <= min) return max
        return Math.round(max * percent / 100.0).toInt().coerceIn(min, max)
    }
}
