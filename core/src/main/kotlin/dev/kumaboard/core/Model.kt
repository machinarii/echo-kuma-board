package dev.kumaboard.core

/** Kuma heartbeat status codes (F6): 0 down, 1 up, 2 pending, 3 maintenance. */
enum class MonitorStatus {
    UP, DOWN, PENDING, MAINTENANCE, NO_DATA;

    /** Lower rank = more urgent. Used by "problems first" sorting (F12): the red states first. */
    val urgency: Int
        get() = when (this) {
            DOWN -> 0
            MAINTENANCE -> 1
            NO_DATA -> 2
            PENDING -> 3
            UP -> 4
        }

    /**
     * Owner decision (2026-11-07): down, maintenance and no data all render red and count as
     * needing attention (banner, night-dimming override). Each keeps its own marker.
     */
    val isAlert: Boolean get() = this == DOWN || this == MAINTENANCE || this == NO_DATA

    /** Any state other than up gets the tinted tile recipe. */
    val isTinted: Boolean get() = this != UP

    companion object {
        fun fromCode(code: Int?): MonitorStatus = when (code) {
            0 -> DOWN
            1 -> UP
            2 -> PENDING
            3 -> MAINTENANCE
            else -> NO_DATA
        }
    }
}

data class Heartbeat(
    val status: MonitorStatus,
    /** Epoch millis (UTC), or null if Kuma sent a time we could not parse. */
    val timeMs: Long?,
)

data class Monitor(
    val id: Int,
    val name: String,
    /** Oldest first. */
    val beats: List<Heartbeat>,
    /** 24 h uptime as a fraction 0..1, or null when Kuma did not report one. */
    val uptime24: Double?,
) {
    /** Current status is the newest beat's status; no beats means no data (F5). */
    val status: MonitorStatus get() = beats.lastOrNull()?.status ?: MonitorStatus.NO_DATA

    /** Epoch millis since which the monitor has been in its current status, if known. */
    val statusSinceMs: Long?
        get() {
            if (beats.isEmpty()) return null
            val current = status
            var since: Long? = beats.last().timeMs
            for (i in beats.indices.reversed()) {
                if (beats[i].status != current) break
                since = beats[i].timeMs ?: since
            }
            return since
        }
}

data class Board(
    val title: String?,
    /** Flattened across groups, in Kuma order (F3). */
    val monitors: List<Monitor>,
) {
    val downCount: Int get() = monitors.count { it.status == MonitorStatus.DOWN }
    val alerts: List<Monitor> get() = monitors.filter { it.status.isAlert }
    /** Drives the banner and the night-dimming override. */
    val anyAlert: Boolean get() = monitors.any { it.status.isAlert }
}
