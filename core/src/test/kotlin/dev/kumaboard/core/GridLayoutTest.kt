package dev.kumaboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F7/F8 at the reference 960×480 panel. The grid sits below the 36 px status bar with a
 * 16 px margin at the sides and bottom: 928×428, 12 px gaps.
 */
class GridLayoutTest {
    private val w = 928f
    private val h = 428f

    private fun grid(n: Int) = GridLayouts.compute(n, w, h).let { "${it.cols}x${it.rows}" }

    @Test fun `1 to 4 monitors use 2x2`() = (1..4).forEach { assertEquals("n=$it", "2x2", grid(it)) }
    @Test fun `5 to 6 use 3x2`() = (5..6).forEach { assertEquals("n=$it", "3x2", grid(it)) }
    @Test fun `7 to 9 use 3x3`() = (7..9).forEach { assertEquals("n=$it", "3x3", grid(it)) }
    @Test fun `10 to 12 use 4x3`() = (10..12).forEach { assertEquals("n=$it", "4x3", grid(it)) }
    @Test fun `13 to 15 use 5x3`() = (13..15).forEach { assertEquals("n=$it", "5x3", grid(it)) }

    /**
     * Deviation from the F8 table, which predicts 5×4 for 16 monitors: the F7 rule
     * (largest tile area among valid grids) picks 4×4 (223×103 px tiles vs 176×103 px),
     * which gives names ~27% more width. See docs/IMPLEMENTATION.md.
     */
    @Test fun `16 uses 4x4 and 17 to 20 use 5x4`() {
        assertEquals("4x4", grid(16))
        (17..20).forEach { assertEquals("n=$it", "5x4", grid(it)) }
    }

    @Test fun `current 14 monitors give 5x3 with one empty cell and 176x135 tiles`() {
        val g = GridLayouts.compute(14, w, h)
        assertEquals(5, g.cols)
        assertEquals(3, g.rows)
        assertEquals(1, g.cols * g.rows - 14)
        assertEquals(176f, g.tileWidth, 0.5f)
        assertEquals(134.7f, g.tileHeight, 0.5f)
        assertTrue("roomy tile: 2-line names", g.tileHeight >= 120f)
        assertEquals(1, g.pages)
    }

    @Test fun `above 20 pages at 20 per page`() {
        val g = GridLayouts.compute(27, w, h)
        assertEquals("5x4", "${g.cols}x${g.rows}")
        assertEquals(20, g.perPage)
        assertEquals(2, g.pages)
    }

    @Test fun `every auto layout keeps aspect and size minimums`() {
        val spec = LayoutSpec()
        for (n in 1..40) {
            val g = GridLayouts.compute(n, w, h)
            val aspect = g.tileWidth / g.tileHeight
            assertTrue("n=$n aspect=$aspect", aspect in spec.minAspect..spec.maxAspect)
            assertTrue(g.tileWidth >= spec.minTileWidth && g.tileHeight >= spec.minTileHeight)
            assertTrue(g.cols * g.rows >= g.perPage)
        }
    }

    @Test fun `column override is honoured`() {
        val g = GridLayouts.compute(14, w, h, columnOverride = 4)
        assertEquals(4, g.cols)
        assertEquals(4, g.rows)
        assertEquals(1, g.pages)
    }

    @Test fun `zero monitors does not crash`() {
        val g = GridLayouts.compute(0, w, h)
        assertEquals(1, g.pages)
    }

    @Test fun `tiny window falls back to one tile per page`() {
        val g = GridLayouts.compute(5, 100f, 60f)
        assertEquals(1, g.perPage)
        assertEquals(5, g.pages)
    }
}
