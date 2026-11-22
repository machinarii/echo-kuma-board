package dev.kumaboard.core

/**
 * Display-only board layout. Kuma itself is not changed.
 *  - [rename]: Kuma name -> name shown on the tile.
 *  - [hide]: monitors left off the board (Kuma name or shown name).
 *  - [order]: shown names in board order; anything not listed follows, in Kuma order.
 * Names match ignoring case and extra spaces. Applied after [Combine], so a combined tile is
 * ordered by its combined name ("DNS").
 */
data class BoardLayout(
    val rename: Map<String, String> = emptyMap(),
    val hide: List<String> = emptyList(),
    val order: List<String> = emptyList(),
) {
    fun apply(board: Board): Board {
        val renames = rename.entries.associate { norm(it.key) to it.value }
        val hidden = hide.map(::norm).toSet()
        val rank = order.mapIndexed { i, n -> norm(n) to i }.toMap()
        val shown = board.monitors
            .filter { norm(it.name) !in hidden && norm(renames[norm(it.name)] ?: it.name) !in hidden }
            .map { m -> renames[norm(m.name)]?.let { m.copy(name = it) } ?: m }
        // Stable sort: listed names first in list order, the rest keep Kuma order.
        val sorted = shown.withIndex()
            .sortedWith(compareBy({ rank[norm(it.value.name)] ?: Int.MAX_VALUE }, { it.index }))
            .map { it.value }
        return board.copy(monitors = sorted)
    }

    companion object {
        private fun norm(s: String) = s.trim().split(Regex("\\s+")).joinToString(" ").lowercase()

        /** No renames, nothing hidden, Kuma's own order. */
        val DEFAULT = BoardLayout()
    }
}
