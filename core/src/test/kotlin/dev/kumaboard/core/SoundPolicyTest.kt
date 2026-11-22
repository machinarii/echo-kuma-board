package dev.kumaboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class SoundPolicyTest {
    private val pacific = ZoneId.of("America/Los_Angeles")
    private fun at(h: Int, m: Int, month: Int = 11) =
        ZonedDateTime.of(2026, month, 10, h, m, 0, 0, pacific).toInstant().toEpochMilli()
    private val min = 60_000L
    private val config = BoardConfig()

    /** A monitor whose beats say it has been down since [downSince] (and was up before). */
    private fun down(id: Int, downSince: Long) = Monitor(
        id, "m$id", listOf(Heartbeat(MonitorStatus.UP, downSince - min), Heartbeat(MonitorStatus.DOWN, downSince)), 1.0,
    )
    private fun up(id: Int, t: Long) = Monitor(id, "m$id", listOf(Heartbeat(MonitorStatus.UP, t)), 1.0)
    private fun eval(now: Long, vararg ms: Monitor, announced: Set<String> = emptySet(), c: BoardConfig = config) =
        SoundPolicy.evaluate(Board(null, ms.toList()), now, c, announced, emptyMap())

    @Test fun `defaults are on, 5 minutes, quiet 22 to 9 Pacific`() {
        assertTrue(config.soundEnabled)
        assertEquals(5 * min, SoundPolicy.THRESHOLD_MS)
        assertEquals(22 * 60, config.quietStartMinute)
        assertEquals(9 * 60, config.quietEndMinute)
    }

    @Test fun `silent until down for more than 5 minutes, then once`() {
        val since = at(12, 0)
        assertTrue(eval(since + 1 * min, down(1, since)).announce.isEmpty())
        assertTrue(eval(since + 5 * min, down(1, since)).announce.isEmpty())
        val r = eval(since + 5 * min + 30_000, down(1, since))
        assertEquals(listOf(1), r.announce.map { it.id })
        // Next refreshes during the same outage: no repeat.
        val again = eval(since + 20 * min, down(1, since), announced = r.announced)
        assertTrue(again.announce.isEmpty())
        assertEquals(r.announced, again.announced)
    }

    @Test fun `a new outage after recovery quacks again`() {
        val first = eval(at(12, 6), down(1, at(12, 0)))
        val recovered = eval(at(12, 10), up(1, at(12, 10)), announced = first.announced)
        assertTrue(recovered.announced.isEmpty())
        val second = eval(at(12, 30), down(1, at(12, 20)), announced = recovered.announced)
        assertEquals(1, second.announce.size)
    }

    @Test fun `a long outage whose down-since time drifts still quacks only once`() {
        val first = eval(at(12, 6), down(1, at(12, 0)))
        assertEquals(1, first.announce.size)
        // Later the oldest retained beat is newer, so the computed down-since moves forward.
        val later = eval(at(14, 0), down(1, at(12, 20)), announced = first.announced)
        assertTrue(later.announce.isEmpty())
    }

    @Test fun `pending, maintenance and no data never quack`() {
        val t = at(12, 0)
        val pending = Monitor(1, "p", listOf(Heartbeat(MonitorStatus.PENDING, t - 30 * min)), 1.0)
        val maint = Monitor(2, "m", listOf(Heartbeat(MonitorStatus.MAINTENANCE, t - 30 * min)), 1.0)
        val noData = Monitor(3, "n", emptyList(), null)
        assertTrue(eval(t, pending, maint, noData).announce.isEmpty())
    }

    @Test fun `quiet hours 22 to 9 Pacific, deferred to the morning if still down`() {
        val since = at(21, 58)
        assertTrue(eval(at(22, 30), down(1, since)).announce.isEmpty())
        assertTrue(eval(at(3, 0), down(1, at(2, 0))).announce.isEmpty())
        assertTrue(eval(at(8, 59), down(1, at(2, 0))).announce.isEmpty())
        assertEquals(1, eval(at(9, 0), down(1, at(2, 0))).announce.size)
        assertEquals(1, eval(at(21, 59), down(1, at(21, 50))).announce.size)
    }

    @Test fun `quiet hours follow Pacific daylight time`() {
        assertEquals(1, eval(at(21, 30, month = 7), down(1, at(21, 0, month = 7))).announce.size)
        assertTrue(eval(at(22, 30, month = 7), down(1, at(22, 0, month = 7))).announce.isEmpty())
    }

    @Test fun `switch off means silent`() {
        assertTrue(eval(at(12, 30), down(1, at(12, 0)), c = config.copy(soundEnabled = false)).announce.isEmpty())
    }

    @Test fun `without heartbeat times the local first-seen time is used`() {
        val m = Monitor(1, "x", listOf(Heartbeat(MonitorStatus.DOWN, null)), 1.0)
        val board = Board(null, listOf(m))
        val r1 = SoundPolicy.evaluate(board, at(12, 0), config, emptySet(), emptyMap())
        assertTrue(r1.announce.isEmpty())
        val r2 = SoundPolicy.evaluate(board, at(12, 6), config, r1.announced, r1.firstSeenDown)
        assertEquals(1, r2.announce.size)
    }

    @Test fun `quiet hours are configurable`() {
        val c = (Provisioning.applyJson(config, """{"quiet":{"start":"23:30","end":"06:15"}}""") as Validated.Ok).value
        assertEquals(1, eval(at(23, 0), down(1, at(22, 50)), c = c).announce.size)
        assertTrue(eval(at(23, 45), down(1, at(23, 35)), c = c).announce.isEmpty())
        assertTrue(Provisioning.apply(config, quietStart = "25:00") is Validated.Err)
    }
}
