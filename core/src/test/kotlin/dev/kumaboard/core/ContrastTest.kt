package dev.kumaboard.core

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Acceptance criterion 10 / §8.6 display rules: body text ≥ 4.5:1 against its tile surface,
 * markers, chips and bars ≥ 3:1. Checked for the day palette and the night variant.
 */
class ContrastTest {
    private val palettes = mapOf("day" to Palette.MACCHIATO, "night" to Palette.MACCHIATO.nightVariant())

    private fun check(label: String, fg: Int, bg: Int, min: Double) {
        val r = Colors.contrast(fg, bg)
        assertTrue("$label: %.2f < %.1f".format(r, min), r >= min)
    }

    @Test fun `text on every tile state`() {
        for ((pn, p) in palettes) for (s in MonitorStatus.values()) {
            for (uptime in listOf(null, 0.5, 0.999)) {
                val t = TileStyle.of(p, s, uptime)
                check("$pn $s name", t.name, t.fill, 4.5)
                check("$pn $s numeral uptime=$uptime", t.numeral, t.fill, 4.5)
                check("$pn $s subtext labels", p.subtext0, t.fill, 4.5)
            }
        }
    }

    @Test fun `chips markers and bars`() {
        for ((pn, p) in palettes) for (s in MonitorStatus.values()) {
            val t = TileStyle.of(p, s, 0.99)
            check("$pn $s chip text", t.chipText, t.chipFill, 3.0)
            check("$pn $s accent", t.accent, t.fill, 3.0)
            check("$pn $s bar", p.statusColor(s), t.fill, 3.0)
        }
    }

    @Test fun `banner and stale chip`() {
        val p = Palette.MACCHIATO
        val bannerFill = Colors.blend(p.mantle, p.red, 0.16f)
        check("banner text", p.text, bannerFill, 4.5)
        check("banner marker", p.red, bannerFill, 3.0)
        check("stale chip", p.subtext0, p.crust, 4.5)
        check("paging dot", p.mauve, p.mantle, 3.0)
    }
}
