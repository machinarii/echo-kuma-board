package dev.kumaboard.core

/**
 * Shows several Kuma monitors as one tile. Each [CombineRule] names the tile and its members:
 *  - mode ANY: up if any member is up, down only when all are down (redundant services, e.g. two
 *    DNS resolvers).
 *  - mode ALL: up only when all members are up, down if any is down (one service that needs every
 *    part, e.g. an auth stack).
 * Members with no data are ignored unless none has data.
 *
 * [group]: the name of a Kuma group monitor holding the members. When it is on the status page the
 * tile shows the group's own status and history and the members are left off; otherwise the
 * members are combined by [mode]. Matched by exact name only.
 */
data class CombineRule(val name: String, val members: List<String>, val mode: Mode = Mode.ANY, val group: String? = null) {
    enum class Mode(val key: String) { ANY("any"), ALL("all") }
}

object Combine {
    /** No tiles are combined until rules are provisioned. */
    val DEFAULT_RULES = emptyList<CombineRule>()

    /** Example rules, used in the README and tests. */
    val EXAMPLE_RULES = listOf(
        CombineRule("DNS", listOf("DNS 1", "DNS 2"), CombineRule.Mode.ANY, group = "DNS"),
        CombineRule("Auth", listOf("login", "tokens", "sessions", "proxy"), CombineRule.Mode.ALL, group = "Auth"),
        CombineRule("UPS", listOf("UPS A", "UPS B"), CombineRule.Mode.ALL),
    )

    /**
     * A member name matches a Kuma monitor named exactly that, or whose last " - " part is that
     * (so "tokens" matches "Auth - tokens"). Case and extra spaces are ignored.
     */
    fun matches(member: String, kumaName: String): Boolean {
        val m = norm(member)
        val k = norm(kumaName)
        return k == m || k.substringAfterLast(" - ") == m
    }

    private fun norm(s: String) = s.trim().split(Regex("\\s+")).joinToString(" ").lowercase()

    /** Best first. Members without data are ignored unless no member has data. */
    private val rank = listOf(
        MonitorStatus.UP, MonitorStatus.PENDING, MonitorStatus.MAINTENANCE, MonitorStatus.DOWN, MonitorStatus.NO_DATA,
    )

    fun best(statuses: Collection<MonitorStatus>): MonitorStatus =
        statuses.minByOrNull { rank.indexOf(it) } ?: MonitorStatus.NO_DATA

    /** Worst status among members that have data (ALL mode). */
    fun worst(statuses: Collection<MonitorStatus>): MonitorStatus {
        val withData = statuses.filter { it != MonitorStatus.NO_DATA }
        return withData.maxByOrNull { rank.indexOf(it) } ?: MonitorStatus.NO_DATA
    }

    private fun pick(mode: CombineRule.Mode, statuses: Collection<MonitorStatus>) =
        if (mode == CombineRule.Mode.ALL) worst(statuses) else best(statuses)

    fun apply(board: Board, rules: List<CombineRule>): Board {
        if (rules.isEmpty()) return board
        val out = ArrayList<Monitor>(board.monitors.size)
        val consumed = HashSet<Int>()
        fun isGroup(r: CombineRule, m: Monitor) = r.group != null && norm(r.group) == norm(m.name)
        for (m in board.monitors) {
            if (m.id in consumed) continue
            val rule = rules.firstOrNull { r -> isGroup(r, m) || r.members.any { matches(it, m.name) } }
            if (rule == null) {
                out += m
                continue
            }
            val group = board.monitors.firstOrNull { it.id !in consumed && isGroup(rule, it) }
            if (group != null) {
                // Kuma's group monitor stands for the whole tile; its members are left off.
                consumed += group.id
                consumed += board.monitors.filter { mm -> rule.members.any { matches(it, mm.name) } }.map { it.id }
                out += group.copy(name = rule.name)
                continue
            }
            val members = board.monitors.filter { mm -> mm.id !in consumed && rule.members.any { matches(it, mm.name) } }
            consumed += members.map { it.id }
            out += merge(rule.name, members, rule.mode)
        }
        return board.copy(monitors = out)
    }

    /** Merged monitor: stable negative id derived from the first member's id. */
    fun merge(name: String, members: List<Monitor>, mode: CombineRule.Mode = CombineRule.Mode.ANY): Monitor {
        if (members.size == 1) return members[0].copy(name = name)
        // Align beats from the newest backwards (members share Kuma's check interval in practice).
        val n = members.maxOf { it.beats.size }
        val beats = (n - 1 downTo 0).map { back ->
            val slot = members.mapNotNull { m -> m.beats.getOrNull(m.beats.size - 1 - back) }
            Heartbeat(pick(mode, slot.map { it.status }), slot.mapNotNull { it.timeMs }.maxOrNull())
        }
        return Monitor(
            id = -members.first().id,
            name = name,
            beats = beats,
            // Kuma doesn't report combined availability. ANY: the best member's uptime (a lower bound).
            // ALL: the worst member's uptime (an upper bound).
            uptime24 = members.mapNotNull { it.uptime24 }.let { if (mode == CombineRule.Mode.ALL) it.minOrNull() else it.maxOrNull() },
        )
    }
}
