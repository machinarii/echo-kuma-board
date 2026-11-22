package dev.kumaboard.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Colour tokens (§8.6). Every colour drawn by the app comes from a [Palette] — swap the
 * instance to add another flavour (e.g. Mocha) later. Values are opaque ARGB ints.
 */
data class Palette(
    /** App background behind the tiles. Owner decision 2026-11-10: pure black. */
    val background: Int,
    val crust: Int,
    val mantle: Int,
    val base: Int,
    val surface0: Int,
    val surface1: Int,
    val text: Int,
    val subtext0: Int,
    val overlay0: Int,
    val green: Int,
    val red: Int,
    val yellow: Int,
    val blue: Int,
    val mauve: Int,
    val peach: Int,
) {
    fun statusColor(s: MonitorStatus): Int = when (s) {
        MonitorStatus.UP -> green
        MonitorStatus.DOWN -> red
        MonitorStatus.PENDING -> yellow
        // Owner decision: maintenance and no data are red like down (markers tell them apart).
        MonitorStatus.MAINTENANCE -> red
        MonitorStatus.NO_DATA -> red
    }

    /** Night window rule: accents lose ~30% saturation. Neutral tokens are unchanged. */
    fun nightVariant(): Palette = copy(
        green = Colors.desaturate(green, 0.3f),
        red = Colors.desaturate(red, 0.3f),
        yellow = Colors.desaturate(yellow, 0.3f),
        blue = Colors.desaturate(blue, 0.3f),
        mauve = Colors.desaturate(mauve, 0.3f),
        peach = Colors.desaturate(peach, 0.3f),
    )

    companion object {
        val MACCHIATO = Palette(
            background = 0xFF000000.toInt(),
            crust = 0xFF181926.toInt(),
            mantle = 0xFF1E2030.toInt(),
            base = 0xFF24273A.toInt(),
            surface0 = 0xFF363A4F.toInt(),
            surface1 = 0xFF494D64.toInt(),
            text = 0xFFCAD3F5.toInt(),
            subtext0 = 0xFFA5ADCB.toInt(),
            overlay0 = 0xFF6E738D.toInt(),
            green = 0xFFA6DA95.toInt(),
            red = 0xFFED8796.toInt(),
            yellow = 0xFFEED49F.toInt(),
            blue = 0xFF8AADF4.toInt(),
            mauve = 0xFFC6A0F6.toInt(),
            peach = 0xFFF5A97F.toInt(),
        )
    }
}

/** Colour maths on ARGB ints, shared by the renderer and the contrast tests. */
object Colors {
    fun a(c: Int) = (c ushr 24) and 0xFF
    fun r(c: Int) = (c shr 16) and 0xFF
    fun g(c: Int) = (c shr 8) and 0xFF
    fun b(c: Int) = c and 0xFF
    fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    fun withAlpha(c: Int, alpha: Float): Int = (c and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255).roundToInt() shl 24)

    /** Opaque result of painting [top] at [amount] opacity over opaque [bottom]. */
    fun blend(bottom: Int, top: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        fun mix(x: Int, y: Int) = (x + (y - x) * t).roundToInt()
        return argb(0xFF, mix(r(bottom), r(top)), mix(g(bottom), g(top)), mix(b(bottom), b(top)))
    }

    /** Linear interpolation of all four channels, for state-change fades. */
    fun lerp(from: Int, to: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun mix(x: Int, y: Int) = (x + (y - x) * k).roundToInt()
        return argb(mix(a(from), a(to)), mix(r(from), r(to)), mix(g(from), g(to)), mix(b(from), b(to)))
    }

    /** Moves a colour toward its own luminance grey by [amount] (0..1). */
    fun desaturate(c: Int, amount: Float): Int {
        val grey = (0.2126 * r(c) + 0.7152 * g(c) + 0.0722 * b(c)).roundToInt()
        val k = amount.coerceIn(0f, 1f)
        fun mix(x: Int) = (x + (grey - x) * k).roundToInt()
        return argb(a(c), mix(r(c)), mix(g(c)), mix(b(c)))
    }

    /** WCAG 2.x relative luminance of an opaque colour. */
    fun luminance(c: Int): Double {
        fun ch(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch(r(c)) + 0.7152 * ch(g(c)) + 0.0722 * ch(b(c))
    }

    fun contrast(fg: Int, bg: Int): Double {
        val l1 = luminance(fg)
        val l2 = luminance(bg)
        return (max(l1, l2) + 0.05) / (min(l1, l2) + 0.05)
    }
}

/**
 * The tile recipes from §8.6 resolved to concrete colours, so the renderer and the tests
 * share one definition. Problem states tint the fill 12% and draw the border at ~55%.
 */
data class TileStyle(
    val fill: Int,
    val border: Int,
    val accent: Int,
    val name: Int,
    val numeral: Int,
    /** Opaque colour of the chip's 16% fill, as composited on [fill]. */
    val chipFill: Int,
    val chipBorder: Int,
    val chipText: Int,
) {
    companion object {
        fun of(p: Palette, status: MonitorStatus, uptime24: Double?): TileStyle {
            val sc = p.statusColor(status)
            val tinted = status.isTinted
            val fill = if (tinted) Colors.blend(p.base, sc, 0.12f) else p.base
            val border = if (tinted) Colors.blend(fill, sc, 0.55f) else p.surface0
            val lowUptime = uptime24 != null && uptime24 < 0.95
            // Numeral is Text, or the status colour when 24 h uptime is below 95% (§8.6).
            val numeral = when {
                uptime24 == null || status == MonitorStatus.NO_DATA -> p.subtext0
                lowUptime -> sc
                else -> p.text
            }
            return TileStyle(
                fill = fill,
                border = border,
                accent = sc,
                name = p.text,
                numeral = numeral,
                chipFill = Colors.blend(fill, sc, 0.16f),
                chipBorder = Colors.blend(fill, sc, 0.40f),
                chipText = sc,
            )
        }
    }
}
