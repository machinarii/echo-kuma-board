package dev.kumaboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KumaParserTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    private val board by lazy {
        KumaParser.combine(
            KumaParser.parseStatusPage(res("status-page.json")),
            KumaParser.parseHeartbeats(res("heartbeat.json")),
        )
    }

    @Test fun `groups are flattened in Kuma order without duplicates`() {
        assertEquals(listOf(3, 7, 9, 12, 13, 21), board.monitors.map { it.id })
        assertEquals("Echo", board.title)
        assertEquals("Monitor 21", board.monitors.last().name) // blank name falls back
    }

    @Test fun `current status is the newest beat after sorting by time`() {
        val wg = board.monitors.first { it.id == 7 }
        assertEquals(MonitorStatus.DOWN, wg.status)
        assertEquals(4, wg.beats.size)
        assertEquals(MonitorStatus.DOWN, wg.beats.first().status) // 10:00 was down, oldest
        assertEquals(KumaParser.parseKumaTime("2026-11-07 10:03:00.500"), wg.statusSinceMs)
        assertEquals(1, board.downCount)
    }

    @Test fun `uptime uses the 24h key and tolerates percentages and junk`() {
        assertEquals(0.99987, board.monitors.first { it.id == 3 }.uptime24!!, 1e-9)
        assertEquals(0.8312, board.monitors.first { it.id == 7 }.uptime24!!, 1e-9)
        assertEquals(0.995, board.monitors.first { it.id == 12 }.uptime24!!, 1e-9)
        assertNull(board.monitors.first { it.id == 13 }.uptime24)
    }

    @Test fun `missing or empty data renders as no data`() {
        assertEquals(MonitorStatus.NO_DATA, board.monitors.first { it.id == 9 }.status)
        assertEquals(MonitorStatus.NO_DATA, board.monitors.first { it.id == 13 }.status)
        // Unparseable status in the newest slot -> NO_DATA, never a crash.
        val agent = board.monitors.first { it.id == 12 }
        assertEquals(2, agent.beats.size)
    }

    @Test fun `times are parsed as UTC`() {
        assertEquals(1_794_045_600_000L, KumaParser.parseKumaTime("2026-11-07 10:00:00.000"))
        assertEquals(1_794_045_600_000L, KumaParser.parseKumaTime("2026-11-07 10:00:00"))
        assertEquals(1_794_045_600_000L, KumaParser.parseKumaTime("2026-11-07T10:00:00Z"))
        assertEquals(1_794_045_600_000L, KumaParser.parseKumaTime("2026-11-07T11:00:00+01:00"))
        assertNull(KumaParser.parseKumaTime("yesterday"))
    }

    @Test(expected = KumaParser.ParseException::class)
    fun `html instead of json is a parse error`() {
        KumaParser.parseStatusPage("<!doctype html><html></html>")
    }

    @Test fun `empty object yields an empty board`() {
        val b = KumaParser.combine(KumaParser.parseStatusPage("{}"), KumaParser.parseHeartbeats("{}"))
        assertTrue(b.monitors.isEmpty())
    }
}
