package dev.kumaboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutTest {
    private fun m(id: Int, name: String) = Monitor(id, name, listOf(Heartbeat(MonitorStatus.UP, 0)), 1.0)

    /** A sample Kuma page, in Kuma order. */
    private val page = Board(null, listOf(
        "DNS 1", "DNS 2", "Kestrel API", "Cloud VM", "Router - admin UI", "Hypervisor UI",
        "Metrics VM", "Storage (NAS)", "Internet (ISP)", "VPN (cloud)",
        "Apps - Orchard CRM (vm2)", "Auth - login", "Auth - tokens", "Tidepool", "Marigold Wiki", "Juniper Mail",
    ).mapIndexed { i, n -> m(i + 1, n) })

    private val example = BoardLayout(
        rename = mapOf(
            "Internet (ISP)" to "Internet", "VPN (cloud)" to "VPN", "Router - admin UI" to "Router",
            "Storage (NAS)" to "NAS", "Apps - Orchard CRM (vm2)" to "Orchard CRM",
        ),
        hide = listOf("Hypervisor UI", "Metrics VM"),
        order = listOf(
            "Internet", "VPN", "DNS", "Router", "UPS", "Auth", "NAS", "Kestrel API",
            "Orchard CRM", "Tidepool", "Marigold Wiki", "Juniper Mail", "Cloud VM",
        ),
    )

    @Test fun `example layout on a sample page`() {
        val board = example.apply(Combine.apply(page, Combine.EXAMPLE_RULES))
        assertEquals(listOf(
            "Internet", "VPN", "DNS", "Router", "Auth", "NAS", // hidden: Hypervisor UI, Metrics VM
            "Kestrel API", "Orchard CRM", "Tidepool", "Marigold Wiki", "Juniper Mail", "Cloud VM",
        ), board.monitors.map { it.name })
    }

    @Test fun `combined ups tile takes its place in the order`() {
        val withUps = page.copy(monitors = page.monitors + m(17, "UPS A") + m(19, "UPS B"))
        val names = example.apply(Combine.apply(withUps, Combine.EXAMPLE_RULES)).monitors.map { it.name }
        assertEquals(listOf("Internet", "VPN", "DNS", "Router", "UPS", "Auth"), names.take(6))
        assertEquals(13, names.size)
        assertTrue("Metrics VM" !in names)
    }

    @Test fun `default layout changes nothing`() {
        assertEquals(page, BoardLayout.DEFAULT.apply(page))
    }

    @Test fun `hide matches kuma or shown name, rename and order ignore case`() {
        val l = BoardLayout(rename = mapOf("a (x)" to "A"), hide = listOf("b"), order = listOf("c", "a"))
        val out = l.apply(Board(null, listOf(m(1, "A (X)"), m(2, "B"), m(3, "d"), m(4, "C"))))
        assertEquals(listOf("C", "A", "d"), out.monitors.map { it.name })
        val hideByShown = BoardLayout(rename = mapOf("x" to "Y"), hide = listOf("y")).apply(Board(null, listOf(m(1, "x"))))
        assertEquals(emptyList<String>(), hideByShown.monitors.map { it.name })
    }

    @Test fun `layout comes from provisioning json and round-trips`() {
        val json = """{"rename":{"Internet (ISP)":"Net"},"hide":["Hypervisor UI"],"order":["Net","DNS"]}"""
        val c = (Provisioning.applyJson(BoardConfig(), json) as Validated.Ok).value
        assertEquals(BoardLayout(mapOf("Internet (ISP)" to "Net"), listOf("Hypervisor UI"), listOf("Net", "DNS")), c.layout)
        assertEquals(c.layout, Provisioning.layoutFromJson(Provisioning.layoutToJson(c.layout)))
        assertEquals(BoardLayout.DEFAULT, BoardConfig().layout)
    }
}
