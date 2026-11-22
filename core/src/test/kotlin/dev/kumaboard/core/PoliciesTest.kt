package dev.kumaboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoliciesTest {
    private fun m(id: Int, s: MonitorStatus) =
        Monitor(id, "m$id", if (s == MonitorStatus.NO_DATA) emptyList() else listOf(Heartbeat(s, 0)), 1.0)

    @Test fun `problems first puts the red states first, then pending, then up, stable`() {
        val list = listOf(
            m(1, MonitorStatus.UP), m(2, MonitorStatus.MAINTENANCE), m(3, MonitorStatus.DOWN),
            m(4, MonitorStatus.PENDING), m(5, MonitorStatus.DOWN), m(6, MonitorStatus.NO_DATA),
        )
        assertEquals(listOf(3, 5, 2, 6, 4, 1), Sorting.apply(list, SortMode.PROBLEMS_FIRST).map { it.id })
        assertEquals(list, Sorting.apply(list, SortMode.KUMA_ORDER))
    }

    @Test fun `down maintenance and no data are red alerts, pending and up are not`() {
        val p = Palette.MACCHIATO
        for (s in listOf(MonitorStatus.DOWN, MonitorStatus.MAINTENANCE, MonitorStatus.NO_DATA)) {
            assertTrue(s.isAlert)
            assertEquals(p.red, p.statusColor(s))
            val t = TileStyle.of(p, s, 0.99)
            assertEquals(p.red, t.accent)
            assertEquals(Colors.blend(p.base, p.red, 0.12f), t.fill)
        }
        assertFalse(MonitorStatus.PENDING.isAlert)
        assertEquals(p.yellow, p.statusColor(MonitorStatus.PENDING))
        assertFalse(MonitorStatus.UP.isAlert)
        val b = Board(null, listOf(m(1, MonitorStatus.UP), m(2, MonitorStatus.MAINTENANCE), m(3, MonitorStatus.NO_DATA), m(4, MonitorStatus.PENDING)))
        assertTrue(b.anyAlert)
        assertEquals(listOf(2, 3), b.alerts.map { it.id })
        assertFalse(Board(null, listOf(m(1, MonitorStatus.UP), m(4, MonitorStatus.PENDING))).anyAlert)
    }

    @Test fun `stale after two missed refreshes`() {
        val i = 30_000L
        assertFalse(Freshness.isStale(100_000, 90_000, 0, i))
        assertFalse(Freshness.isStale(100_000, 90_000, 1, i))
        assertTrue(Freshness.isStale(100_000, 90_000, 2, i))
        assertTrue(Freshness.isStale(200_000, 90_000, 0, i)) // loop silently stalled
        assertFalse(Freshness.isStale(0, null, 0, i))         // first load in progress
    }

    @Test fun `backoff doubles and caps at 60s`() {
        val i = 300_000L
        assertEquals(i, Backoff.delayMs(0, i))
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L), (1..7).map { Backoff.delayMs(it, i) })
        assertEquals(10_000L, Backoff.delayMs(5, 10_000L)) // never slower than the refresh interval
    }

    @Test fun `night window wraps midnight`() {
        val w = NightWindow(23 * 60, 7 * 60)
        assertTrue(w.contains(23 * 60))
        assertTrue(w.contains(2 * 60))
        assertFalse(w.contains(7 * 60))
        assertFalse(w.contains(12 * 60))
        assertTrue(NightWindow(60, 120).contains(90))
        assertFalse(NightWindow(60, 60).contains(60))
        assertEquals(23 * 60 + 5, NightWindow.parseHhMm("23:05"))
        assertEquals(null, NightWindow.parseHhMm("24:00"))
    }

    @Test fun `transitions detect down and recovery`() {
        val a = Board(null, listOf(m(1, MonitorStatus.UP), m(2, MonitorStatus.DOWN), m(3, MonitorStatus.UP)))
        val b = Board(null, listOf(m(1, MonitorStatus.DOWN), m(2, MonitorStatus.UP), m(3, MonitorStatus.UP), m(4, MonitorStatus.DOWN)))
        val t = Transitions.diff(a, b)
        assertEquals(listOf(1), t.wentDown.map { it.id })
        assertEquals(listOf(2), t.recovered.map { it.id })
        assertTrue(Transitions.diff(null, b).wentDown.isEmpty())
    }

    @Test fun `uptime format floors and never fakes 100`() {
        assertEquals("99.98%", Format.uptime(0.99987))
        assertEquals("99.9%", Format.uptime(0.99987, 1))
        assertEquals("99.99%", Format.uptime(0.999999))
        assertEquals("100%", Format.uptime(1.0))
        assertEquals("83.12%", Format.uptime(0.8312))
        assertEquals("--", Format.uptime(null))
    }

    @Test fun `elapsed labels`() {
        assertEquals("45s", Format.elapsed(45_000))
        assertEquals("12m", Format.elapsed(12 * 60_000L))
        assertEquals("3h", Format.elapsed(3 * 3_600_000L + 40 * 60_000L))
        assertEquals("2d", Format.elapsed(2 * 86_400_000L))
    }
}
