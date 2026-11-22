package dev.kumaboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CombineTest {
    private val t0 = 1_794_045_600_000L
    private val min = 60_000L
    private fun mon(id: Int, name: String, vararg s: MonitorStatus, uptime: Double? = 0.99) =
        Monitor(id, name, s.mapIndexed { i, st -> Heartbeat(st, t0 + i * min) }, uptime)
    private val U = MonitorStatus.UP
    private val D = MonitorStatus.DOWN
    private val P = MonitorStatus.PENDING
    private fun combined(a: Monitor, b: Monitor, vararg others: Monitor) =
        Combine.apply(Board(null, listOf(*others, a, b)), Combine.EXAMPLE_RULES).monitors.first { it.name == "DNS" }

    @Test fun `one down shows up, both down shows down`() {
        assertEquals(U, combined(mon(6, "DNS 1", U), mon(7, "DNS 2", U)).status)
        assertEquals(U, combined(mon(6, "DNS 1", D), mon(7, "DNS 2", U)).status)
        assertEquals(U, combined(mon(6, "DNS 1", U), mon(7, "DNS 2", D)).status)
        assertEquals(D, combined(mon(6, "DNS 1", D), mon(7, "DNS 2", D)).status)
        assertEquals(P, combined(mon(6, "DNS 1", D), mon(7, "DNS 2", P)).status)
    }

    @Test fun `no-data member is ignored unless both have none`() {
        assertEquals(D, combined(mon(6, "DNS 1", D), Monitor(7, "DNS 2", emptyList(), null)).status)
        assertEquals(MonitorStatus.NO_DATA, combined(Monitor(6, "DNS 1", emptyList(), null), Monitor(7, "DNS 2", emptyList(), null)).status)
    }

    @Test fun `one tile, in the first member's place, best beat per slot, best uptime`() {
        val b = Board(null, listOf(
            mon(1, "Internet", U), mon(6, "DNS 1", U, D, D, U, uptime = 0.97), mon(9, "Kestrel API", U),
            mon(7, "dns 2", D, U, D, uptime = 0.995),
        ))
        val out = Combine.apply(b, Combine.EXAMPLE_RULES).monitors
        assertEquals(listOf("Internet", "DNS", "Kestrel API"), out.map { it.name })
        val c = out[1]
        assertEquals(-6, c.id)
        // Aligned from newest: [U] + [D,U] , [D,D] , [U,D] -> U, D, U, U ... oldest first:
        assertEquals(listOf(U, D, U, U), c.beats.map { it.status })
        assertEquals(0.995, c.uptime24!!, 1e-9)
    }

    @Test fun `a single matching member is just renamed`() {
        val out = Combine.apply(Board(null, listOf(mon(6, "DNS 1", D))), Combine.EXAMPLE_RULES).monitors
        assertEquals(listOf("DNS"), out.map { it.name })
        assertEquals(6, out[0].id)
    }

    @Test fun `quack only when both have been down for more than 5 minutes`() {
        val now = t0 + 10 * min
        fun downSince(id: Int, name: String, sinceMin: Int) = Monitor(id, name, listOf(
            Heartbeat(U, t0), Heartbeat(D, t0 + sinceMin * min), Heartbeat(D, now)), 0.9)
        val cfg = BoardConfig(quietStartMinute = 0, quietEndMinute = 0) // no quiet hours for this test
        val bothDown = Combine.apply(Board(null, listOf(downSince(6, "DNS 1", 1), downSince(7, "DNS 2", 2))), Combine.EXAMPLE_RULES)
        assertEquals(1, SoundPolicy.evaluate(bothDown, now, cfg, emptySet(), emptyMap()).announce.size)
        val oneDown = Combine.apply(Board(null, listOf(downSince(6, "DNS 1", 1), mon(7, "DNS 2", U, U, U))), Combine.EXAMPLE_RULES)
        assertTrue(SoundPolicy.evaluate(oneDown, now, cfg, emptySet(), emptyMap()).announce.isEmpty())
    }

    private fun auth(vararg st: Pair<String, MonitorStatus>) = Combine.apply(
        Board(null, listOf(mon(1, "Internet", U)) + st.mapIndexed { i, (n, s) -> mon(20 + i, n, s, uptime = 0.99 - i / 100.0) }),
        Combine.EXAMPLE_RULES,
    ).monitors

    @Test fun `auth is up only when all are up`() {
        val all = arrayOf("Auth - login" to U, "Auth - tokens" to U, "Auth - sessions" to U, "Auth - proxy" to U)
        val up = auth(*all)
        assertEquals(listOf("Internet", "Auth"), up.map { it.name })
        assertEquals(U, up[1].status)
        assertEquals(0.96, up[1].uptime24!!, 1e-9) // worst member
        for (i in all.indices) {
            val oneDown = all.copyOf().also { it[i] = it[i].first to D }
            assertEquals("member $i down", D, auth(*oneDown)[1].status)
        }
        val pending = all.copyOf().also { it[2] = it[2].first to P }
        assertEquals(P, auth(*pending)[1].status)
    }

    @Test fun `auth matches short or prefixed names and ignores members without data`() {
        val v = auth("login" to U, "tokens" to U)
        assertEquals(listOf("Internet", "Auth"), v.map { it.name })
        assertEquals(U, v[1].status)
        val withNoData = Combine.apply(Board(null, listOf(
            mon(1, "Auth - login", U), Monitor(2, "Auth - sessions", emptyList(), null),
        )), Combine.EXAMPLE_RULES).monitors
        assertEquals(U, withNoData.single().status)
        // Unrelated names don't match: "Auth Gateway" is not "tokens", and "tokens2" is not "tokens".
        assertTrue(Combine.matches("tokens", "Auth - tokens"))
        assertTrue(!Combine.matches("tokens", "tokens2"))
        assertTrue(!Combine.matches("sessions", "Session Store"))
    }

    @Test fun `auth tile uses the kuma group when present, else its members`() {
        // Without the group: the members, all must be up.
        val now = auth("Auth - login" to U, "Auth - tokens" to D)
        assertEquals(listOf("Internet", "Auth"), now.map { it.name })
        assertEquals(D, now[1].status)
        // With Kuma's "Auth" group monitor on the page, the group's own status wins (one tile).
        val withGroup = auth("Auth - login" to U, "Auth" to D, "Auth - tokens" to U)
        assertEquals(listOf("Internet", "Auth"), withGroup.map { it.name })
        assertEquals(D, withGroup[1].status)
        assertEquals(21, withGroup[1].id) // the group monitor itself, not a merge
        assertEquals(U, auth("Auth" to U)[1].status)
    }

    @Test fun `dns uses the kuma group when present, else any of its members`() {
        fun board(vararg st: Pair<String, MonitorStatus>) = Combine.apply(
            Board(null, st.mapIndexed { i, (n, s) -> mon(30 + i, n, s) }), Combine.EXAMPLE_RULES,
        ).monitors
        // No group on the page: redundant pair, up if either is up.
        assertEquals(listOf("DNS" to U), board("DNS 1" to U, "DNS 2" to D).map { it.name to it.status })
        // Group on the page: Kuma's group status decides (Kuma marks the group down when a child is down).
        assertEquals(listOf("DNS" to D), board("DNS 1" to U, "DNS" to D, "DNS 2" to D).map { it.name to it.status })
        assertEquals(listOf("DNS" to U), board("DNS" to U).map { it.name to it.status })
    }

    @Test fun `combine group round-trips through provisioning json`() {
        val rules = Combine.EXAMPLE_RULES
        assertEquals(rules, Provisioning.combineFromJson(Provisioning.combineToJson(rules)))
        val c = (Provisioning.applyJson(BoardConfig(), """{"combine":[{"name":"N","members":["a"],"group":"N grp"}]}""") as Validated.Ok).value
        assertEquals("N grp", c.combine.single().group)
        assertTrue(Provisioning.applyJson(BoardConfig(), """{"combine":[{"name":"N","members":["a"],"group":5}]}""") is Validated.Err)
    }

    @Test fun `ups is down when either ups is down`() {
        fun ups(a: MonitorStatus, b: MonitorStatus) = Combine.apply(Board(null, listOf(
            mon(17, "UPS A", a), mon(19, "UPS B", b, uptime = 0.97),
        )), Combine.EXAMPLE_RULES).monitors.single()
        assertEquals("UPS", ups(U, U).name)
        assertEquals(U, ups(U, U).status)
        assertEquals(0.97, ups(U, U).uptime24!!, 1e-9) // worst member
        assertEquals(D, ups(D, U).status)
        assertEquals(D, ups(U, D).status)
        assertEquals(D, ups(D, D).status)
    }

    @Test fun `combine rules come from provisioning json`() {
        val c = (Provisioning.applyJson(BoardConfig(), """{"combine":[{"name":"DNS","members":["A","B"]}]}""") as Validated.Ok).value
        assertEquals(listOf(CombineRule("DNS", listOf("A", "B"))), c.combine)
        val all = (Provisioning.applyJson(BoardConfig(), """{"combine":[{"name":"V","members":["a"],"mode":"all"}]}""") as Validated.Ok).value
        assertEquals(CombineRule.Mode.ALL, all.combine.single().mode)
        assertEquals(c.combine, Provisioning.combineFromJson(Provisioning.combineToJson(c.combine)))
        assertEquals(Combine.EXAMPLE_RULES, Provisioning.combineFromJson(Provisioning.combineToJson(Combine.EXAMPLE_RULES)))
        assertEquals(emptyList<CombineRule>(), BoardConfig().combine)
    }
}
