package dev.kumaboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigTest {
    private fun ok(input: String) = (ServerAddress.parse(input) as Validated.Ok).value
    private fun err(input: String) = ServerAddress.parse(input) is Validated.Err

    @Test fun `full status page URL yields base and slug`() {
        val p = ok("https://status.example.ts.net/status/echo")
        assertEquals("https://status.example.ts.net", p.baseUrl)
        assertEquals("echo", p.slugFromUrl)
    }

    @Test fun `liberal input`() {
        assertEquals("https://kuma.example.com", ok("  kuma.example.com/  ").baseUrl)
        assertEquals("https://kuma.example.com:8443/sub", ok("https://Kuma.Example.com:8443/sub/").baseUrl)
        assertEquals("http://192.168.1.20:3001", ok("http://192.168.1.20:3001").baseUrl)
    }

    @Test fun `https only except private networks`() {
        assertTrue(err("http://kuma.example.com"))
        assertTrue(err("ftp://192.168.1.2"))
        assertTrue(err("https://user:pw@kuma.example.com"))
        assertFalse(err("http://10.0.0.5:3001"))
        assertFalse(err("http://100.101.102.103"))
        assertFalse(err("http://kuma.lan"))
        assertFalse(err("http://kuma"))
        assertTrue(err("http://8.8.8.8"))
        assertTrue(err("http://172.32.0.1"))
    }

    @Test fun `endpoint urls`() {
        val c = BoardConfig()
        assertEquals("https://kuma.example.com/api/status-page/echo", c.statusPageApiUrl())
        assertEquals("https://kuma.example.com/api/status-page/heartbeat/echo", c.heartbeatApiUrl())
    }

    @Test fun `provisioning json applies and validates`() {
        val json = """
            { "url": "https://kuma.example.com/status/home", "refreshSeconds": 15, "sort": "problems",
              "columns": 0, "dim": { "enabled": true, "start": "22:30", "end": "06:45", "level": 10 },
              "unknown": 1 }
        """
        val c = (Provisioning.applyJson(BoardConfig(), json) as Validated.Ok).value
        assertEquals("https://kuma.example.com", c.baseUrl)
        assertEquals("home", c.slug)
        assertEquals(15, c.refreshSeconds)
        assertEquals(SortMode.PROBLEMS_FIRST, c.sortMode)
        assertEquals(22 * 60 + 30, c.dimStartMinute)
        assertEquals(6 * 60 + 45, c.dimEndMinute)
        assertEquals(0.10f, c.dimLevel, 1e-6f)
    }

    @Test fun `explicit slug wins over url slug and bad values are rejected`() {
        val c = (Provisioning.apply(BoardConfig(), url = "https://k.example.com/status/a", slug = "b") as Validated.Ok).value
        assertEquals("b", c.slug)
        assertTrue(Provisioning.apply(BoardConfig(), refreshSeconds = 5) is Validated.Err)
        assertTrue(Provisioning.apply(BoardConfig(), slug = "../x") is Validated.Err)
        assertTrue(Provisioning.applyJson(BoardConfig(), "not json") is Validated.Err)
    }
}
