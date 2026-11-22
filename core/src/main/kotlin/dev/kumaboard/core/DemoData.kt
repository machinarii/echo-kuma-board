package dev.kumaboard.core

/**
 * Deterministic sample board with made-up service names. Used by debug builds
 * (`--ez demo true`) and tests to check the layout without a live Kuma server.
 */
object DemoData {
    val NAMES = listOf(
        "Internet", "VPN", "DNS", "Router", "NAS", "Backups", "Kestrel API",
        "Orchard CRM", "Tidepool", "Marigold Wiki", "Juniper Mail",
        "Halcyon Git", "Saffron Photos",
        "Quokka Chat", "Bramble Docs",
    )

    /**
     * @param count number of monitors (names repeat with a suffix beyond the list)
     * @param scenario "ok", "down" (two down, one pending, one maintenance) or "nodata"
     */
    fun board(nowMs: Long, count: Int = NAMES.size, scenario: String = "down"): Board {
        val beatEvery = 60_000L
        val monitors = (0 until count).map { i ->
            val name = NAMES[i % NAMES.size] + if (i >= NAMES.size) " ${i / NAMES.size + 1}" else ""
            val status = when (scenario) {
                "down" -> when (i) {
                    1 -> MonitorStatus.DOWN
                    5 -> MonitorStatus.DOWN
                    7 -> MonitorStatus.PENDING
                    10 -> MonitorStatus.MAINTENANCE
                    else -> MonitorStatus.UP
                }
                else -> MonitorStatus.UP
            }
            val noData = scenario == "nodata" && i == 2 || (scenario == "down" && i == 12)
            val beats = if (noData) emptyList() else (0 until 100).map { b ->
                val age = (99 - b) * beatEvery
                val s = when {
                    b >= 100 - (if (i == 1) 8 else i % 4 + 3) && status != MonitorStatus.UP -> status // VPN: 7 min outage
                    b < 94 && (b * 7 + i * 13) % 53 == 0 -> MonitorStatus.DOWN
                    b < 94 && (b * 5 + i * 3) % 71 == 0 -> MonitorStatus.PENDING
                    else -> MonitorStatus.UP
                }
                Heartbeat(s, nowMs - age)
            }
            val uptime = when {
                noData -> null
                status == MonitorStatus.DOWN && i == 1 -> 0.8312
                else -> 1.0 - ((i * 37) % 23) / 10_000.0
            }
            Monitor(id = i + 1, name = name, beats = beats, uptime24 = uptime)
        }
        return Board("Demo", monitors)
    }
}
