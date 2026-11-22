package dev.kumaboard

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import dev.kumaboard.core.Board
import dev.kumaboard.core.Colors
import dev.kumaboard.core.Format
import dev.kumaboard.core.GridLayout
import dev.kumaboard.core.GridLayouts
import dev.kumaboard.core.LayoutSpec
import dev.kumaboard.core.Monitor
import dev.kumaboard.core.MonitorStatus
import dev.kumaboard.core.Palette
import dev.kumaboard.core.TileStyle
import dev.kumaboard.core.UsageDisplay
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The whole board, drawn on one canvas (§10: no view per tile or per beat, keeps memory low).
 *
 * Everything is laid out in *reference pixels* — native pixels of the 960×480 Echo Show 5 panel,
 * which is where every size in §8.6 is specified — and scaled to the real window, so on the
 * target device 1 reference px = 1 physical px.
 *
 * Structure: a permanent status bar (counts left, clock right), then the tile grid on a flat
 * canvas. Each tile: full-width heartbeat bars on top, bold name, then the status chip and
 * uptime row at the bottom.
 */
@SuppressLint("ViewConstructor")
class BoardView(context: Context) : View(context) {

    data class Model(
        /** Already sorted. Null until the first successful fetch. */
        val board: Board? = null,
        val columns: Int = 0,
        val page: Int = 0,
        val stale: Boolean = false,
        /** The latest fetch failed (yellow dot in the status bar even before data is stale). */
        val offline: Boolean = false,
        /** Time of the last successful fetch, e.g. "14:32" (shown when offline). */
        val updatedLabel: String? = null,
        /** Current time for the status bar, e.g. "18:42". */
        val clockLabel: String = "",
        /** Full-screen message when there is no board to show yet. */
        val message: String? = null,
        val messageDetail: String? = null,
        val night: Boolean = false,
        val nowMs: Long = System.currentTimeMillis(),
        /** Burn-in drift (F21), reference px. */
        val driftX: Float = 0f,
        val driftY: Float = 0f,
        /** Bottom strip: weekly Claude and Codex usage, plus per-model windows. Null = feature off, no strip, full-height grid. */
        val usage: List<UsageDisplay.Item>? = null,
    )

    var onSettingsGesture: (() -> Unit)? = null

    /** Number of pages for the current board and window size (read by the paging loop). */
    var pageCount: Int = 1
        private set

    private var model = Model()
    private var palette = Palette.MACCHIATO

    // ---- Geometry (reference px) ----
    private var scale = 1f
    private var refW = REF_W
    private var refH = REF_H

    // ---- Paints ----
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    // Greybeard is monospaced, so figures are tabular without a font feature.
    private val namePaint = text()
    private val pctPaint = text()
    private val chipPaint = text()
    private val barPaint = text()
    private val barStrong = text()
    private val msgTitle = text()
    private val msgBody = text()
    private fun font(paint: TextPaint, target: Float, bold: Boolean) = PixelFont.apply(context, paint, target, bold)
    private val rect = RectF()
    private val checkPath = android.graphics.Path()
    private val rect2 = RectF()

    // ---- Caches ----
    private data class NameKey(val name: String, val width: Int, val size: Float, val maxLines: Int)
    private val nameCache = object : LinkedHashMap<NameKey, List<String>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<NameKey, List<String>>?) = size > 120
    }

    // ---- State-change fades (§8.6 Motion: the only motion besides drift) ----
    private class Fade(val from: TileStyle, val to: TileStyle, val startMs: Long)
    private val fades = HashMap<Int, Fade>()

    fun setModel(m: Model) {
        palette = if (m.night) Palette.MACCHIATO.nightVariant() else Palette.MACCHIATO
        val board = m.board
        if (board != null) {
            val now = SystemClock.uptimeMillis()
            for (mon in board.monitors) {
                val target = TileStyle.of(palette, mon.status, mon.uptime24)
                val f = fades[mon.id]
                if (f == null) {
                    fades[mon.id] = Fade(target, target, 0)
                } else if (f.to != target) {
                    val current = styleAt(f, now)
                    fades[mon.id] = Fade(current, target, if (animationsEnabled()) now else 0)
                }
            }
        }
        model = m
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        scale = min(w / REF_W, h / REF_H).takeIf { it > 0f } ?: 1f
        refW = w / scale
        refH = h / scale
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        // Flat canvas: Mantle by day, Crust at night. No inset panel.
        canvas.drawColor(palette.background) // black, day and night
        canvas.save()
        canvas.scale(scale, scale)
        canvas.translate(model.driftX, model.driftY)

        drawStatusBar(canvas, model.board)
        val board = model.board
        when {
            board == null -> drawMessage(canvas, model.message ?: "Starting…", model.messageDetail)
            board.monitors.isEmpty() -> drawMessage(canvas, "No monitors on this status page", model.messageDetail ?: "Add monitors to the status page in Kuma.")
            else -> drawGrid(canvas, board)
        }
        model.usage?.let { drawUsageBar(canvas, it) }
        canvas.restore()

        if (fades.values.any { it.startMs != 0L && SystemClock.uptimeMillis() - it.startMs < FADE_MS }) postInvalidateOnAnimation()
    }

    /**
     * Permanent status bar, iOS-style: plain text on the canvas, no container.
     * Left: "● 10 up  ● 2 down  ● 1 maint.  ● 1 no data  ● 1 pending". Right: offline note, then the clock.
     */
    private fun drawStatusBar(canvas: Canvas, board: Board?) {
        val p = palette
        val cy = STATUS_BAR_H / 2f + 1f
        font(barPaint, 15f, bold = false)
        font(barStrong, 15f, bold = true)
        fun baseline(paint: Paint) = cy - (paint.descent() + paint.ascent()) / 2f

        // Right: clock on the far right.
        font(barStrong, 16f, bold = true)
        barStrong.color = p.text
        val clockW = barStrong.measureText(model.clockLabel)
        var right = refW - MARGIN - clockW
        canvas.text(model.clockLabel, right, baseline(barStrong), barStrong)
        font(barStrong, 15f, bold = true)

        // Offline indicator just left of the clock (replaces the old floating stale chip).
        if (model.stale) {
            val t = "Offline · updated ${model.updatedLabel ?: "--:--"}"
            barPaint.color = p.subtext0
            val w = barPaint.measureText(t)
            right -= 18f + w
            canvas.text(t, right, baseline(barPaint), barPaint)
            fill.color = p.yellow
            canvas.drawCircle(right - 10f, cy, 4f, fill)
            right -= 18f
        } else if (model.offline) {
            fill.color = p.yellow
            right -= 18f
            canvas.drawCircle(right + 4f, cy, 4f, fill)
        }

        // Left: counts.
        if (board == null) return
        val ms = board.monitors
        val items = ArrayList<Triple<Int, Int, String>>() // count, colour, word
        val up = ms.count { it.status == MonitorStatus.UP }
        val down = ms.count { it.status == MonitorStatus.DOWN }
        items += Triple(up, p.green, "up")
        items += Triple(down, if (down > 0) p.red else p.overlay0, "down")
        ms.count { it.status == MonitorStatus.MAINTENANCE }.takeIf { it > 0 }?.let { items += Triple(it, p.red, "maint.") }
        ms.count { it.status == MonitorStatus.NO_DATA }.takeIf { it > 0 }?.let { items += Triple(it, p.red, "no data") }
        ms.count { it.status == MonitorStatus.PENDING }.takeIf { it > 0 }?.let { items += Triple(it, p.yellow, "pending") }

        var x = MARGIN + 2f
        for ((count, color, word) in items) {
            val n = count.toString()
            val label = " $word"
            val w = 14f + barStrong.measureText(n) + barPaint.measureText(label)
            if (x + w > right - 16f) break
            fill.color = color
            canvas.drawCircle(x + 4f, cy, 4f, fill)
            x += 14f
            barStrong.color = if (count == 0) p.subtext0 else p.text
            canvas.text(n, x, baseline(barStrong), barStrong)
            x += barStrong.measureText(n)
            barPaint.color = p.subtext0
            canvas.text(label, x, baseline(barPaint), barPaint)
            x += barPaint.measureText(label) + 18f
        }
    }

    private fun drawGrid(canvas: Canvas, board: Board) {
        val left = MARGIN
        val top = GRID_TOP
        val width = refW - 2 * MARGIN
        val bottom = gridBottom()

        val monitors = board.monitors
        val grid = GridLayouts.compute(monitors.size, width, bottom - top, LAYOUT_SPEC, model.columns.takeIf { it > 0 })
        pageCount = grid.pages
        val page = model.page.coerceIn(0, grid.pages - 1)
        val slice = monitors.subList(page * grid.perPage, min(monitors.size, (page + 1) * grid.perPage))

        // Stale: whole grid dims to 70% (§8.6). Free cells stay empty, room for new monitors.
        val dim = model.stale
        if (dim) canvas.saveLayerAlpha(0f, top - 1f, refW, refH, (255 * STALE_ALPHA).roundToInt())
        slice.forEachIndexed { i, m ->
            val c = i % grid.cols
            val r = i / grid.cols
            val x = left + c * (grid.tileWidth + LAYOUT_SPEC.gap)
            val y = top + r * (grid.tileHeight + LAYOUT_SPEC.gap)
            rect.set(x, y, x + grid.tileWidth, y + grid.tileHeight)
            drawTile(canvas, rect, m)
        }
        if (dim) canvas.restore()

        if (grid.pages > 1) drawPageDots(canvas, grid, page)
    }

    /**
     * One tile, adaptive to its height:
     * heartbeat bars (top) · bold name (2 lines when there is room, else 1) · status chip + uptime (bottom).
     */
    private fun drawTile(canvas: Canvas, r: RectF, m: Monitor) {
        val st = currentStyle(m)
        // F14: everything scales up with larger tiles; never below the base sizes.
        val k = min(r.width() / 176f, r.height() / 134f).coerceIn(1f, 2.2f)
        val roomy = r.height() >= ROOMY_MIN_H * k
        val pad = 12f * k

        // Surface
        fill.color = st.fill
        canvas.drawRoundRect(r, RADIUS, RADIUS, fill)
        stroke.color = st.border
        stroke.strokeWidth = if (m.status.isTinted) 1.5f else 1f
        rect2.set(r.left + 0.5f, r.top + 0.5f, r.right - 0.5f, r.bottom - 0.5f)
        canvas.drawRoundRect(rect2, RADIUS - 0.5f, RADIUS - 0.5f, stroke)

        val cl = r.left + pad
        val cr = r.right - pad
        val cw = cr - cl

        // Top: heartbeat bars, full content width (in place of the old colour bar).
        val beatsH = (if (roomy) 14f else 12f) * k
        val beatsTop = r.top + (if (roomy) 12f else 10f) * k
        val beatsBottom = beatsTop + beatsH
        drawBeats(canvas, m, cl, beatsTop, cw, beatsBottom, k)

        // Bottom row: status chip left, uptime right (smaller than the name, regular weight).
        val rowH = 20f * k
        val rowBottom = r.bottom - (if (roomy) 10f else 8f) * k
        val rowMid = rowBottom - rowH / 2f
        font(pctPaint, (if (roomy) 15f else 14f) * k, bold = false)
        pctPaint.color = st.numeral
        val chipH = 20f * k
        val label = chipLabel(m.status)
        val fullChipW = chipWidth(label, m.status, k)
        val pctMax = cw - chipH - 6f * k
        var pct = Format.uptime(m.uptime24, 2)
        if (pctPaint.measureText(pct) > pctMax) pct = Format.uptime(m.uptime24, 1)
        val pctW = pctPaint.measureText(pct)
        // Uptime right-aligned; status chip on the left.
        canvas.text(pct, cr - pctW, rowMid - (pctPaint.descent() + pctPaint.ascent()) / 2f, pctPaint)
        // Marker (never colour alone, F11): text chip when it fits, else the icon. Up shows a chip only when roomy.
        val showLabel = roomy && pctW + 8f * k + fullChipW <= cw
        if (m.status != MonitorStatus.UP || showLabel) {
            val chipW = if (showLabel) fullChipW else chipH
            rect2.set(cl, rowMid - chipH / 2f, cl + chipW, rowMid + chipH / 2f)
            drawChip(canvas, rect2, m.status, st, if (showLabel) label else null, k)
        }

        // Name: bold, as many lines (max 2) as fit between the bars and the bottom row.
        font(namePaint, 18f * k, bold = true)
        namePaint.color = st.name
        val lineH = 22f * k
        val nameTop = beatsBottom + 8f * k
        val avail = rowBottom - rowH - 2f * k - nameTop
        val maxLines = floor(avail / lineH).toInt().coerceIn(1, 2)
        nameLines(m.name, cw, namePaint, maxLines).forEachIndexed { i, line ->
            canvas.text(line, cl, nameTop + lineH * i + (lineH - (namePaint.descent() + namePaint.ascent())) / 2f, namePaint)
        }
    }

    /** Draws the beats right-aligned and fitted to [w] (newest at the right edge). */
    private fun drawBeats(canvas: Canvas, m: Monitor, x: Float, top: Float, w: Float, bottom: Float, k: Float) {
        val barW = max(3f, (3f * k).roundToInt().toFloat())
        val gap = max(1f, k.roundToInt().toFloat())
        val n = floor((w + gap) / (barW + gap)).toInt().coerceAtLeast(1)
        val used = n * barW + (n - 1) * gap
        val x0 = x + (w - used) / 2f
        val beats = m.beats.takeLast(n)
        val empty = n - beats.size
        val tileFill = currentStyle(m).fill
        for (i in 0 until n) {
            val bx = x0 + i * (barW + gap)
            rect2.set(bx, top, bx + barW, bottom)
            val bi = i - empty
            fill.color = if (bi < 0) {
                palette.surface0
            } else {
                val c = palette.statusColor(beats[bi].status)
                // Newest 5 at full strength, older at 70%.
                if (bi >= beats.size - 5) c else Colors.blend(tileFill, c, 0.7f)
            }
            canvas.drawRoundRect(rect2, 2f, 2f, fill)
        }
    }

    private fun drawChip(canvas: Canvas, r: RectF, status: MonitorStatus, st: TileStyle, label: String?, k: Float) {
        val radius = 6f * k
        fill.color = st.chipFill
        canvas.drawRoundRect(r, radius, radius, fill)
        stroke.color = st.chipBorder
        stroke.strokeWidth = 1f
        rect.set(r.left + 0.5f, r.top + 0.5f, r.right - 0.5f, r.bottom - 0.5f)
        canvas.drawRoundRect(rect, radius, radius, stroke)

        val iconSize = 10f * k
        var x = if (label == null) r.centerX() - iconSize / 2f else r.left + 7f * k
        if (marks(status)) {
            drawMarker(canvas, status, x, r.centerY() - iconSize / 2f, iconSize, st.chipText, 2f * min(k, 1.3f))
            x += iconSize + 4f * k
        }
        if (label != null) {
            font(chipPaint, 13f * k, bold = false)
            chipPaint.color = st.chipText
            canvas.text(label, x, r.centerY() - (chipPaint.descent() + chipPaint.ascent()) / 2f, chipPaint)
        }
    }

    private fun chipWidth(label: String, status: MonitorStatus, k: Float): Float {
        font(chipPaint, 13f * k, bold = false)
        val iconPart = if (marks(status)) 10f * k + 4f * k else 0f
        return 7f * k + iconPart + chipPaint.measureText(label) + 7f * k
    }

    /** Outlined 2 px-stroke markers: ✓ up, ✕ down, ! pending, wrench maintenance, ? no data (F11). */
    private fun drawMarker(canvas: Canvas, s: MonitorStatus, x: Float, y: Float, size: Float, color: Int, strokeW: Float) {
        icon.color = color
        icon.strokeWidth = strokeW
        when (s) {
            MonitorStatus.UP -> {
                // Check mark: short leg down-right, long leg up-right.
                checkPath.rewind()
                checkPath.moveTo(x + 0.5f, y + size * 0.55f)
                checkPath.lineTo(x + size * 0.38f, y + size - 1.5f)
                checkPath.lineTo(x + size - 0.5f, y + 1.5f)
                canvas.drawPath(checkPath, icon)
            }
            MonitorStatus.DOWN -> {
                canvas.drawLine(x + 1f, y + 1f, x + size - 1f, y + size - 1f, icon)
                canvas.drawLine(x + size - 1f, y + 1f, x + 1f, y + size - 1f, icon)
            }
            MonitorStatus.PENDING -> {
                val cx = x + size / 2f
                canvas.drawLine(cx, y + 0.5f, cx, y + size * 0.62f, icon)
                canvas.drawPoint(cx, y + size - 0.5f, icon)
            }
            MonitorStatus.MAINTENANCE -> {
                // Open-jaw head at top-right, handle down to bottom-left.
                val r = size * 0.3f
                val hx = x + size - r - 0.5f
                val hy = y + r + 0.5f
                rect.set(hx - r, hy - r, hx + r, hy + r)
                canvas.drawArc(rect, 90f, 270f, false, icon)
                canvas.drawLine(hx - r * 0.7f, hy + r * 0.7f, x + 1f, y + size - 1f, icon)
            }
            MonitorStatus.NO_DATA -> {
                // Question mark: hook arc, short stem, dot.
                val cx = x + size / 2f
                val r = size * 0.28f
                rect.set(cx - r, y + 0.5f, cx + r, y + 0.5f + 2 * r)
                canvas.drawArc(rect, 180f, 270f, false, icon)
                canvas.drawLine(cx, y + 0.5f + 2 * r, cx, y + size * 0.68f, icon)
                canvas.drawPoint(cx, y + size - 0.5f, icon)
            }
            else -> Unit
        }
    }

    private fun gridBottom() = if (model.usage != null) refH - USAGE_BAR_H else refH - MARGIN

    /**
     * Bottom strip, plain text on the canvas like the status bar:
     * "Claude ▬▬▭ 58% left  Fable ▬▭▭ 30%  resets Thu 9 AM     Codex ▬▭▭ 25% left  resets Sat 2 PM".
     * Shows what is left of each weekly window: the meter shrinks as it is used and its colour runs
     * from green (full) through yellow and peach to red (empty). The number always carries the value;
     * colour only reinforces it, and the number itself turns yellow at ≤ 20% left and red at ≤ 5%.
     * Old or unknown numbers are greyed, never shown as current.
     */
    private fun drawUsageBar(canvas: Canvas, items: List<UsageDisplay.Item>) {
        val p = palette
        val cy = refH - USAGE_BAR_H / 2f + 1f
        font(barPaint, 15f, bold = false)
        font(barStrong, 15f, bold = true)
        fun baseline(paint: Paint) = cy - (paint.descent() + paint.ascent()) / 2f
        val limit = if (pageCount > 1) refW - MARGIN - pageCount * 12f - 16f else refW - MARGIN
        var x = MARGIN + 2f
        for ((i, item) in items.withIndex()) {
            // Proximity: a model window sits close to its tool; tools are spaced further apart.
            if (i > 0) x += if (item.sub) SUB_GAP else GROUP_GAP
            val color = if (item.old || item.level == UsageDisplay.Level.UNKNOWN) p.overlay0
                else UsageDisplay.meterColor(item.fraction, p)
            val name = item.name + " "
            // "left" once per group, on the tool; its model windows read the same way, and the strip stays short.
            val suffix = if (item.sub || item.level == UsageDisplay.Level.UNKNOWN) "" else " left"
            val core = barPaint.measureText(name) + METER_W + 8f + barStrong.measureText(item.percentText) + barPaint.measureText(suffix)
            if (x + core > limit) break
            // Out of room: keep the number, drop the reset text.
            val detail = item.detail?.let { "  $it" }?.takeIf { x + core + barPaint.measureText(it) <= limit } ?: ""
            barPaint.color = p.subtext0
            canvas.text(name, x, baseline(barPaint), barPaint)
            x += barPaint.measureText(name)
            // Meter: track plus fill.
            rect2.set(x, cy - 3f, x + METER_W, cy + 3f)
            fill.color = p.surface1
            canvas.drawRoundRect(rect2, 3f, 3f, fill)
            if (item.fraction > 0f) {
                rect2.set(x, cy - 3f, x + max(6f, METER_W * item.fraction.coerceIn(0f, 1f)), cy + 3f)
                fill.color = color
                canvas.drawRoundRect(rect2, 3f, 3f, fill)
            }
            x += METER_W + 8f
            barStrong.color = if (item.old || item.level == UsageDisplay.Level.UNKNOWN) p.subtext0 else when (item.level) {
                UsageDisplay.Level.CRITICAL -> p.red
                UsageDisplay.Level.WARN -> p.yellow
                else -> p.text
            }
            canvas.text(item.percentText, x, baseline(barStrong), barStrong)
            x += barStrong.measureText(item.percentText)
            if (suffix.isNotEmpty()) {
                barPaint.color = p.subtext0
                canvas.text(suffix, x, baseline(barPaint), barPaint)
                x += barPaint.measureText(suffix)
            }
            if (detail.isNotEmpty()) {
                barPaint.color = p.subtext0
                canvas.text(detail, x, baseline(barPaint), barPaint)
                x += barPaint.measureText(detail)
            }
        }
    }

    private fun drawPageDots(canvas: Canvas, grid: GridLayout, page: Int) {
        val spacing = 12f
        // With the usage strip, the dots move to its right end so they never cover the numbers.
        val strip = model.usage != null
        val cy = if (strip) refH - USAGE_BAR_H / 2f + 1f else refH - MARGIN / 2f
        val start = if (strip) refW - MARGIN - (grid.pages - 1) * spacing - 4f else refW / 2f - (grid.pages - 1) * spacing / 2f
        for (i in 0 until grid.pages) {
            fill.color = if (i == page) palette.mauve else palette.surface1
            canvas.drawCircle(start + i * spacing, cy, if (i == page) 3.5f else 3f, fill)
        }
    }

    private fun drawMessage(canvas: Canvas, title: String, detail: String?) {
        val p = palette
        val w = min(620f, refW - 2 * MARGIN)
        val h = 150f
        val cy = (GRID_TOP + refH - MARGIN) / 2f
        rect.set((refW - w) / 2f, cy - h / 2f, (refW + w) / 2f, cy + h / 2f)
        fill.color = p.base
        canvas.drawRoundRect(rect, RADIUS, RADIUS, fill)
        stroke.color = p.surface0
        stroke.strokeWidth = 1f
        canvas.drawRoundRect(rect, RADIUS, RADIUS, stroke)

        font(msgTitle, 22f, bold = true)
        msgTitle.color = p.text
        val t = TextUtils.ellipsize(title, msgTitle, w - 48f, TextUtils.TruncateAt.END)
        canvas.text(t, rect.left + 24f, rect.top + 48f, msgTitle)
        font(msgBody, 15f, bold = false)
        msgBody.color = p.subtext0
        var y = rect.top + 80f
        if (detail != null) {
            for (line in nameLines(detail, w - 48f, msgBody, 2)) {
                canvas.text(line, rect.left + 24f, y, msgBody); y += 21f
            }
        }
        canvas.text("Long-press the top-left corner for settings", rect.left + 24f, rect.bottom - 22f, msgBody)
    }

    // ------------------------------------------------------------------ helpers

    private fun chipLabel(s: MonitorStatus): String = when (s) {
        MonitorStatus.UP -> "Up"
        MonitorStatus.DOWN -> "Down"
        MonitorStatus.PENDING -> "Pending"
        MonitorStatus.MAINTENANCE -> "Maint."
        MonitorStatus.NO_DATA -> "No data"
    }

    /** Every status has an icon; up gets a check (shown only beside the "Up" label on roomy tiles). */
    private fun marks(s: MonitorStatus) = true

    /** Greedy word wrap into at most [maxLines] (1 or 2) lines; the last is ellipsised (F14). Cached. */
    private fun nameLines(name: String, width: Float, paint: TextPaint, maxLines: Int): List<String> {
        val key = NameKey(name, width.roundToInt(), paint.textSize, maxLines)
        nameCache[key]?.let { return it }
        val result: List<String> = when {
            paint.measureText(name) <= width -> listOf(name)
            maxLines <= 1 -> listOf(TextUtils.ellipsize(name, paint, width, TextUtils.TruncateAt.END).toString())
            else -> {
                var cut = paint.breakText(name, true, width, null)
                val space = name.lastIndexOf(' ', cut)
                if (space > 0 && cut < name.length) cut = space
                val first = name.substring(0, cut).trimEnd()
                val rest = name.substring(cut).trimStart()
                val second = TextUtils.ellipsize(rest, paint, width, TextUtils.TruncateAt.END).toString()
                listOf(first, second).filter { it.isNotEmpty() }
            }
        }
        nameCache[key] = result
        return result
    }

    private fun currentStyle(m: Monitor): TileStyle {
        val f = fades[m.id] ?: return TileStyle.of(palette, m.status, m.uptime24)
        return styleAt(f, SystemClock.uptimeMillis())
    }

    private fun styleAt(f: Fade, now: Long): TileStyle {
        if (f.startMs == 0L) return f.to
        val t = ((now - f.startMs) / FADE_MS.toFloat()).coerceIn(0f, 1f)
        if (t >= 1f) return f.to
        fun l(a: Int, b: Int) = Colors.lerp(a, b, t)
        return TileStyle(
            fill = l(f.from.fill, f.to.fill), border = l(f.from.border, f.to.border), accent = l(f.from.accent, f.to.accent),
            name = l(f.from.name, f.to.name), numeral = l(f.from.numeral, f.to.numeral), chipFill = l(f.from.chipFill, f.to.chipFill),
            chipBorder = l(f.from.chipBorder, f.to.chipBorder), chipText = l(f.from.chipText, f.to.chipText),
        )
    }

    private fun animationsEnabled() = ValueAnimator.areAnimatorsEnabled()

    // No subpixel positioning: bitmap glyphs must land on whole pixels.
    private fun text() = TextPaint(Paint.ANTI_ALIAS_FLAG)

    /** Draws text with its origin snapped to whole device pixels so the bitmap font stays crisp. */
    private fun Canvas.text(t: CharSequence, x: Float, y: Float, paint: Paint) {
        val sx = Math.round(x * scale) / scale
        val sy = Math.round(y * scale) / scale
        drawText(t, 0, t.length, sx, sy, paint)
    }

    // ------------------------------------------------------------------ settings gesture (F19)

    private val longPress = Runnable { onSettingsGesture?.invoke() }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val corner = CORNER * scale
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (e.x <= corner && e.y <= corner) {
                    postDelayed(longPress, LONG_PRESS_MS)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> if (e.x > corner || e.y > corner) removeCallbacks(longPress)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
        }
        return true // swallow every other touch: a casual tap changes nothing
    }

    companion object {
        const val REF_W = 960f
        const val REF_H = 480f
        const val MARGIN = 16f
        /** Permanent status bar height; the grid starts just below it. */
        const val STATUS_BAR_H = 32f
        const val GRID_TOP = 36f
        /** Bottom usage strip (only when a usage file is configured); replaces the 16 px bottom margin. */
        const val USAGE_BAR_H = 28f
        const val METER_W = 44f
        const val GROUP_GAP = 32f
        const val SUB_GAP = 20f
        const val RADIUS = 14f
        /** Tiles at least this tall (×scale) show 2-line names, the text chip and the taller bar. */
        const val ROOMY_MIN_H = 120f
        const val STALE_ALPHA = 0.7f
        const val FADE_MS = 400L
        const val CORNER = 80f
        const val LONG_PRESS_MS = 1500L
        val LAYOUT_SPEC = LayoutSpec()
    }
}

/**
 * Greybeard (MIT, a vector port of the UW ttyp0 bitmap font) ships one file per pixel size.
 * It is only crisp at those sizes or whole multiples of them, so every text size goes through
 * [apply], which picks the largest native size × multiple that does not exceed the target.
 * Files are subset to Latin + punctuation (assets/fonts/greybeard, LICENSE.txt alongside).
 */
object PixelFont {
    val SIZES = intArrayOf(12, 13, 14, 15, 16, 17, 18, 22)
    private val cache = HashMap<String, Typeface>()

    data class Pick(val file: Int, val multiple: Int) {
        val px: Int get() = file * multiple
    }

    /** Largest pixel-perfect size ≤ [target] px; the smallest native size if the target is below it. */
    fun pick(target: Float): Pick {
        var best = Pick(SIZES.first(), 1)
        for (m in 1..4) for (s in SIZES) {
            val px = s * m
            if (px <= target + 0.01f && px > best.px) best = Pick(s, m)
        }
        return best
    }

    fun typeface(ctx: Context, file: Int, bold: Boolean): Typeface {
        val name = "fonts/greybeard/Greybeard-${file}px${if (bold) "-Bold" else ""}.ttf"
        return cache.getOrPut(name) {
            try {
                Typeface.createFromAsset(ctx.assets, name)
            } catch (_: Exception) {
                Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
            }
        }
    }

    /** Sets typeface and size on [paint] for a pixel-perfect rendering near [target] px. */
    fun apply(ctx: Context, paint: Paint, target: Float, bold: Boolean): Paint {
        val p = pick(target)
        paint.typeface = typeface(ctx, p.file, bold)
        paint.textSize = p.px.toFloat()
        return paint
    }
}
