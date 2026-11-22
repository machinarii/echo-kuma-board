package dev.kumaboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class UsageTest {
    private val now = 1_795_000_000_000L // Wed 2026-11-18 03:46 Pacific
    private val nowS = now / 1000
    private val la = ZoneId.of("America/Los_Angeles")

    @Test fun `parses the published file`() {
        val r = UsageParser.parse("""{"version":1,"generated_at":$nowS,
            "claude":{"week_pct":42.4,"resets_at":${nowS + 86_400},"as_of":${nowS - 60}},
            "codex":{"week_pct":75,"resets_at":${nowS + 3 * 86_400},"as_of":${nowS - 600}}}""")
        assertEquals(42.4, r.claude!!.percent, 1e-9)
        assertEquals((nowS + 86_400) * 1000, r.claude!!.resetsAtMs)
        assertEquals((nowS - 600) * 1000, r.codex!!.asOfMs)
    }

    @Test fun `junk is rejected per tool, never shown as a number`() {
        val r = UsageParser.parse("""{"claude":{"week_pct":"42","as_of":$nowS},
            "codex":{"week_pct":-3,"as_of":$nowS},"extra":{"token":"ignored"}}""")
        assertNull(r.claude) // string percent
        assertNull(r.codex) // negative
        assertNull(UsageParser.parse("""{"claude":{"week_pct":10}}""").claude) // no as_of
        assertNull(UsageParser.parse("""{"claude":{"week_pct":10,"as_of":12}}""").claude) // absurd time
        assertEquals(100.0, UsageParser.parse("""{"claude":{"week_pct":130,"as_of":$nowS}}""").claude!!.percent, 0.0)
        assertTrue(runCatching { UsageParser.parse("<html>") }.isFailure)
        assertTrue(runCatching { UsageParser.parse("[1,2]") }.isFailure)
        assertTrue(runCatching { UsageParser.parse("{\"a\":\"" + "x".repeat(20_000) + "\"}") }.isFailure)
    }

    @Test fun `levels, reset and age`() {
        fun item(pct: Double, resets: Long? = now + 86_400_000, asOf: Long = now - 60_000) =
            UsageDisplay.item("Claude", UsageMeter(pct, resets, asOf), now, la)
        assertEquals(UsageDisplay.Level.NORMAL, item(79.4).level)
        assertEquals("21%", item(79.4).percentText) // what is left
        assertEquals(0.206f, item(79.4).fraction, 1e-3f)
        assertEquals(UsageDisplay.Level.WARN, item(80.0).level)
        assertEquals(UsageDisplay.Level.CRITICAL, item(95.0).level)
        assertEquals("resets Thu 3 AM", item(10.0).detail) // now + 24 h
        val reset = item(90.0, resets = now - 1)
        assertEquals("—", reset.percentText)
        assertEquals(UsageDisplay.Level.UNKNOWN, reset.level)
        val old = item(30.0, asOf = now - 2 * 86_400_000L)
        assertTrue(old.old)
        assertTrue(old.detail!!.startsWith("as of "))
        val unknown = UsageDisplay.item("Codex", null, now, la)
        assertEquals("—", unknown.percentText)
        assertEquals(listOf("Claude", "Codex"), UsageDisplay.items(null, now, la).map { it.name })
    }

    @Test fun `usage address is https or private http, without credentials`() {
        fun ok(s: String) = (UsageAddress.parse(s) as Validated.Ok).value
        assertEquals("https://mac.example.ts.net/kuma-usage/usage.json", ok("mac.example.ts.net/kuma-usage/usage.json"))
        assertEquals("http://100.70.1.2:8080/usage.json", ok("http://100.70.1.2:8080/usage.json"))
        assertNull(ok("  "))
        assertTrue(UsageAddress.parse("http://example.com/usage.json") is Validated.Err)
        assertTrue(UsageAddress.parse("https://user:pw@example.com/u.json") is Validated.Err)
        assertTrue(UsageAddress.parse("https://example.com/u.json?token=x") is Validated.Err)
        assertTrue(UsageAddress.parse("ftp://example.com/u.json") is Validated.Err)
    }

    @Test fun `provisioning sets and clears the usage url`() {
        val on = (Provisioning.applyJson(BoardConfig(), """{"usageUrl":"https://mac.example.ts.net/kuma-usage/usage.json"}""") as Validated.Ok).value
        assertEquals("https://mac.example.ts.net/kuma-usage/usage.json", on.usageUrl)
        val off = (Provisioning.applyJson(on, """{"usageUrl":""}""") as Validated.Ok).value
        assertNull(off.usageUrl)
        assertEquals(on.usageUrl, (Provisioning.applyJson(on, """{"refreshSeconds":30}""") as Validated.Ok).value.usageUrl)
        assertTrue(Provisioning.applyJson(on, """{"usageUrl":"http://example.com/u.json"}""") is Validated.Err)
        assertNull(BoardConfig().usageUrl)
    }

    @Test fun `frontier model windows are parsed, validated and grouped under their tool`() {
        val r = UsageParser.parse("""{"claude":{"week_pct":42,"resets_at":${nowS + 86_400},"as_of":$nowS},
            "codex":{"week_pct":75,"resets_at":${nowS + 3 * 86_400},"as_of":$nowS},
            "models":[
              {"tool":"claude","name":"Fable","week_pct":61.2,"resets_at":${nowS + 86_400},"as_of":$nowS},
              {"tool":"codex","name":"Premium","week_pct":12,"resets_at":${nowS + 2 * 86_400},"as_of":$nowS},
              {"tool":"claude","name":"fable","week_pct":1,"as_of":$nowS},
              {"tool":"gemini","name":"Pro","week_pct":5,"as_of":$nowS},
              {"tool":"claude","name":"<b>x</b>","week_pct":5,"as_of":$nowS},
              {"tool":"claude","name":"Opus","week_pct":"5","as_of":$nowS}]}""")
        assertEquals(listOf("Fable", "Premium"), r.models.map { it.name }) // dup, unknown tool, bad name, string pct dropped
        val items = UsageDisplay.items(r, now, la)
        assertEquals(listOf("Claude", "Fable", "Codex", "Premium"), items.map { it.name })
        assertEquals(listOf(false, true, false, true), items.map { it.sub })
        assertEquals("39%", items[1].percentText)
        assertNull(items[0].detail) // same reset as Fable: said once, at the end of the group
        assertEquals("resets Thu 3 AM", items[1].detail)
        assertEquals("resets Sat 3 AM", items[2].detail) // Premium's reset differs: each keeps its own
        assertEquals("resets Fri 3 AM", items[3].detail)
        assertTrue(UsageParser.parse("""{"models":{"tool":"claude"}}""").models.isEmpty())
    }

    @Test fun `meter colour runs from red when empty to green when full`() {
        val p = Palette.MACCHIATO
        assertEquals(p.red, UsageDisplay.meterColor(0f, p))
        assertEquals(p.peach, UsageDisplay.meterColor(0.25f, p))
        assertEquals(p.yellow, UsageDisplay.meterColor(0.5f, p))
        assertEquals(p.green, UsageDisplay.meterColor(1f, p))
        assertEquals(p.green, UsageDisplay.meterColor(1.5f, p))
        assertEquals(Colors.lerp(p.yellow, p.green, 0.5f), UsageDisplay.meterColor(0.75f, p))
    }
}
