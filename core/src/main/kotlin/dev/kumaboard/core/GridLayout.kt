package dev.kumaboard.core

import kotlin.math.ceil

/**
 * Inputs for the grid function (F7–F9). All sizes are in reference pixels, i.e. native
 * pixels of the 960×480 Echo Show 5 panel; the app scales them to the real window.
 */
data class LayoutSpec(
    val gap: Float = 12f,
    /**
     * Text-fit minimums: the smallest tile needs colour bar 9+6, gap 8, one 22 px name line,
     * 2, a 20 px uptime row, 4, a 12 px heartbeat bar and 10 px padding ≈ 93 px.
     */
    val minTileWidth: Float = 140f,
    val minTileHeight: Float = 94f,
    val minAspect: Float = 1.2f,
    val maxAspect: Float = 3.0f,
    /** Never stretch fewer monitors into one giant tile: 1–4 monitors share a 2×2 grid (F8). */
    val minCols: Int = 2,
    val minRows: Int = 2,
    val maxCols: Int = 10,
    val maxRows: Int = 8,
    /** F9: above this many monitors, page. */
    val maxPerPage: Int = 20,
)

data class GridLayout(
    val cols: Int,
    val rows: Int,
    val tileWidth: Float,
    val tileHeight: Float,
    /** Monitors shown on one page (≤ cols × rows). */
    val perPage: Int,
    val pages: Int,
) {
    val tileArea: Float get() = tileWidth * tileHeight
}

object GridLayouts {

    /**
     * Pure layout function (§10): among grids that meet the text-fit minimums and keep the tile
     * aspect ratio within [LayoutSpec.minAspect]..[LayoutSpec.maxAspect], pick the largest tile area.
     * Ties go to the grid with fewer empty cells, then fewer columns.
     *
     * @param width usable width of the grid area (screen minus margins), reference px
     * @param height usable height of the grid area, reference px
     * @param columnOverride a fixed column count from settings (F22); null or ≤ 0 means auto
     */
    fun compute(
        count: Int,
        width: Float,
        height: Float,
        spec: LayoutSpec = LayoutSpec(),
        columnOverride: Int? = null,
    ): GridLayout {
        val total = count.coerceAtLeast(0)
        if (columnOverride != null && columnOverride > 0) {
            return withColumns(total, width, height, spec, columnOverride)
        }
        // Largest page that still has a valid grid. Normally that is min(count, maxPerPage).
        var perPage = total.coerceIn(1, spec.maxPerPage.coerceAtLeast(1))
        while (perPage >= 1) {
            best(perPage, width, height, spec)?.let { (c, r) ->
                return build(c, r, width, height, spec, perPage, total)
            }
            perPage--
        }
        // Screen too small for any valid grid: show one tile per page rather than nothing.
        return build(1, 1, width, height, spec, 1, total)
    }

    private fun best(n: Int, width: Float, height: Float, spec: LayoutSpec): Pair<Int, Int>? {
        var best: Pair<Int, Int>? = null
        var bestArea = -1f
        var bestEmpty = Int.MAX_VALUE
        for (c in spec.minCols..spec.maxCols) {
            for (r in spec.minRows..spec.maxRows) {
                if (c * r < n) continue
                val w = tile(width, c, spec.gap)
                val h = tile(height, r, spec.gap)
                if (w < spec.minTileWidth || h < spec.minTileHeight) continue
                val aspect = w / h
                if (aspect < spec.minAspect || aspect > spec.maxAspect) continue
                val area = w * h
                val empty = c * r - n
                val better = area > bestArea + EPS ||
                    (area > bestArea - EPS && (empty < bestEmpty || (empty == bestEmpty && c < (best?.first ?: Int.MAX_VALUE))))
                if (better) {
                    best = c to r
                    bestArea = area
                    bestEmpty = empty
                }
                // Larger r only shrinks tiles for this c once c*r ≥ n.
                break
            }
        }
        return best
    }

    private fun withColumns(total: Int, width: Float, height: Float, spec: LayoutSpec, cols: Int): GridLayout {
        val c = cols.coerceIn(1, spec.maxCols)
        val maxRowsThatFit = generateSequence(1) { it + 1 }
            .takeWhile { it <= spec.maxRows && tile(height, it, spec.gap) >= spec.minTileHeight }
            .lastOrNull() ?: 1
        val neededRows = ceil(total.coerceAtLeast(1) / c.toDouble()).toInt()
        val r = neededRows.coerceIn(1, maxRowsThatFit)
        val perPage = (c * r).coerceAtMost(spec.maxPerPage).coerceAtMost(total.coerceAtLeast(1))
        return build(c, r, width, height, spec, perPage, total)
    }

    private fun build(c: Int, r: Int, width: Float, height: Float, spec: LayoutSpec, perPage: Int, total: Int) =
        GridLayout(
            cols = c,
            rows = r,
            tileWidth = tile(width, c, spec.gap),
            tileHeight = tile(height, r, spec.gap),
            perPage = perPage,
            pages = if (total <= 0) 1 else ceil(total / perPage.toDouble()).toInt(),
        )

    fun tile(extent: Float, n: Int, gap: Float): Float = (extent - gap * (n - 1)) / n

    private const val EPS = 0.5f
}
