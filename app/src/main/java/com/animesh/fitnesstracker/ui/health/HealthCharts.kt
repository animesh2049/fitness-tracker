package com.animesh.fitnesstracker.ui.health

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.animesh.fitnesstracker.ui.theme.PlexMono
import com.animesh.fitnesstracker.ui.theme.PlexSans
import com.animesh.fitnesstracker.ui.theme.Tokens
import kotlin.math.abs

/*
 * Canvas charts for the Health tab. Geometry follows the design files under design/health:
 * a left gutter with mono grid labels, the plot from the gutter to 8 dp before the right edge,
 * and small sans labels under the plot. Text is measured with sp styles so it scales with the
 * device font size; dp values are converted through the DrawScope density.
 */

/** Colours the health charts use that are not in [Tokens]. */
object HealthColors {
    val HeartRate = Color(0xFFFF6B5C)
    val BarDim = Color(0xFF5D7A12)
    val RouteGround = Color(0xFF121417)
    val StressBands = listOf(Color(0xFFC8F03C), Color(0xFF6F8A1F), Color(0xFFF5B400), Color(0xFFFF6B5C))
    /** Deep, light, REM, awake, the order of the hub's sleep bar. */
    val SleepStages = listOf(Color(0xFFC8F03C), Color(0xFF5D7A12), Color(0xFFB4B8BD), Color(0xFFFF6B5C))
    /** Awake, REM, light, deep: hypnogram lanes top to bottom. */
    val HypnogramLanes = listOf(Color(0xFFFF6B5C), Color(0xFFB4B8BD), Color(0xFF5D7A12), Color(0xFFC8F03C))
    val Zones = listOf(Color(0xFF5D7A12), Color(0xFF8FB520), Color(0xFFC8F03C), Color(0xFFF5B400), Color(0xFFFF6B5C))
}

private val GridLabelStyle = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.Medium, fontSize = 10.sp)
private val TrendGridLabelStyle = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.Medium, fontSize = 11.sp)
private val AxisLabelStyle = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.Normal, fontSize = 10.sp)
private val LaneLabelStyle = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.Normal, fontSize = 10.sp)
private val BubbleStyle = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)

/** One sample: [x] is 0 at the left edge of the plot and 1 at the right, [y] is in value units. */
data class ChartSample(val x: Float, val y: Float)

/** A curve made of runs. Samples inside a run are joined; the space between runs stays empty. */
data class CurveSeries(val runs: List<List<ChartSample>>, val min: Float, val max: Float) {
    val isEmpty: Boolean get() = runs.all { it.isEmpty() }

    companion object {
        val EMPTY = CurveSeries(emptyList(), 0f, 1f)
    }
}

/**
 * Line (optionally area) chart over a fixed x range, used for the day's heart rate and Body
 * Battery and for an activity's heart rate over elapsed time. [gridValues] are drawn as horizontal
 * lines with [gridLabel] in the gutter; [referenceY] is a dashed line (the resting heart rate).
 */
@Composable
fun TimeCurveChart(
    series: CurveSeries,
    gridValues: List<Float>,
    gridLabel: (Float) -> String,
    xLabels: List<AxisLabel>,
    lineColor: Color,
    modifier: Modifier = Modifier,
    fill: Boolean = false,
    referenceY: Float? = null,
    height: Dp = 122.dp,
    plotTop: Dp = 10.dp,
    plotBottom: Dp = 96.dp,
    axisTop: Dp = 108.dp
) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier.fillMaxWidth().height(height)) {
        val left = 34.dp.toPx()
        val right = size.width - 8.dp.toPx()
        val top = plotTop.toPx()
        val bottom = plotBottom.toPx()
        val span = (series.max - series.min).takeIf { it > 0f } ?: 1f
        fun yAt(v: Float) = bottom - ((v - series.min) / span).coerceIn(0f, 1f) * (bottom - top)
        fun xAt(fraction: Float) = left + fraction.coerceIn(0f, 1f) * (right - left)

        for (v in gridValues) {
            val y = yAt(v)
            drawLine(Tokens.Border, Offset(left, y), Offset(right, y), strokeWidth = 1.dp.toPx())
            val layout = measurer.measure(AnnotatedString(gridLabel(v)), GridLabelStyle)
            drawText(layout, Tokens.Muted, topLeft = Offset(left - 6.dp.toPx() - layout.size.width, y - layout.size.height / 2f))
        }
        if (referenceY != null) {
            val y = yAt(referenceY)
            drawLine(
                Tokens.Muted, Offset(left, y), Offset(right, y), strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx()))
            )
        }

        val stroke = Stroke(width = 2.dp.toPx(), join = StrokeJoin.Round, cap = StrokeCap.Round)
        for (run in series.runs) {
            if (run.isEmpty()) continue
            if (run.size == 1) {
                drawCircle(lineColor, 2.dp.toPx(), Offset(xAt(run[0].x), yAt(run[0].y)))
                continue
            }
            val path = Path()
            run.forEachIndexed { i, s -> if (i == 0) path.moveTo(xAt(s.x), yAt(s.y)) else path.lineTo(xAt(s.x), yAt(s.y)) }
            if (fill) {
                val area = Path().apply {
                    addPath(path)
                    lineTo(xAt(run.last().x), bottom)
                    lineTo(xAt(run.first().x), bottom)
                    close()
                }
                drawPath(area, lineColor.copy(alpha = 0.14f))
            }
            drawPath(path, lineColor, style = stroke)
        }

        drawAxisLabels(measurer, xLabels, ::xAt, axisTop.toPx(), Tokens.Dim, Tokens.Dim, -1)
    }
}

/** How a bucket's secondary value is drawn on a bar chart (version 0.5). */
enum class SecondaryMode {
    NONE,
    /** The secondary value is the lower part of the bar (active calories inside total calories). */
    STACK,
    /** The secondary value is a tick across the bar (the sleep need over the time asleep). */
    MARKER
}

/**
 * The Trends chart: bars for totals, a line with points otherwise. Tapping a bar or point selects
 * it, which highlights it and shows a label bubble above it. Null buckets are left empty.
 * [secondary] and [secondaryMode] add a stacked part or a marker per bar.
 */
@Composable
fun TrendChart(
    values: List<Double?>,
    labels: List<String>,
    bars: Boolean,
    selected: Int?,
    onSelect: (Int) -> Unit,
    min: Double,
    max: Double,
    gridLabels: List<String>,
    bubbleText: String?,
    modifier: Modifier = Modifier,
    secondary: List<Double?> = emptyList(),
    secondaryMode: SecondaryMode = SecondaryMode.NONE
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val gridLayouts = remember(gridLabels, measurer) { gridLabels.map { measurer.measure(AnnotatedString(it), TrendGridLabelStyle) } }
    val gutter = remember(gridLayouts, density) {
        with(density) { maxOf(40.dp.toPx(), (gridLayouts.maxOfOrNull { it.size.width } ?: 0) + 12.dp.toPx()) }
    }
    val n = values.size
    Canvas(
        modifier
            .fillMaxWidth()
            .height(210.dp)
            .pointerInput(n, gutter) {
                val left = gutter
                val right = size.width - 8.dp.toPx()
                val slot = if (n == 0) 0f else (right - left) / n
                detectTapGestures { offset ->
                    if (n == 0) return@detectTapGestures
                    var best = -1
                    var bestDist = Float.MAX_VALUE
                    for (i in 0 until n) {
                        val d = abs(left + slot * (i + 0.5f) - offset.x)
                        if (d < bestDist) { bestDist = d; best = i }
                    }
                    if (best >= 0 && bestDist <= slot) onSelect(best)
                }
            }
    ) {
        val left = gutter
        val right = size.width - 8.dp.toPx()
        val top = 28.dp.toPx()
        val bottom = 174.dp.toPx()
        val span = (max - min).takeIf { it > 0 } ?: 1.0
        fun yAt(v: Double) = (bottom - ((v - min) / span).coerceIn(0.0, 1.0) * (bottom - top)).toFloat()
        val slot = if (n == 0) 0f else (right - left) / n
        fun xAt(i: Int) = left + slot * (i + 0.5f)

        val gridValues = listOf(min, (min + max) / 2, max)
        gridValues.forEachIndexed { i, v ->
            val y = yAt(v)
            drawLine(Tokens.Border, Offset(left, y), Offset(right, y), strokeWidth = 1.dp.toPx())
            val layout = gridLayouts.getOrNull(i) ?: return@forEachIndexed
            drawText(layout, Tokens.Muted, topLeft = Offset(left - 6.dp.toPx() - layout.size.width, y - layout.size.height / 2f))
        }

        if (bars) {
            val barW = minOf(28.dp.toPx(), slot * 0.6f)
            values.forEachIndexed { i, v ->
                if (v == null) return@forEachIndexed
                val y = yAt(v)
                drawRoundRect(
                    if (i == selected) Tokens.Accent else HealthColors.BarDim,
                    topLeft = Offset(xAt(i) - barW / 2, y),
                    size = Size(barW, (bottom - y).coerceAtLeast(2.dp.toPx())),
                    cornerRadius = CornerRadius(3.dp.toPx())
                )
                val s = secondary.getOrNull(i) ?: return@forEachIndexed
                when (secondaryMode) {
                    SecondaryMode.STACK -> {
                        val ys = yAt(s.coerceIn(min, v))
                        drawRoundRect(
                            if (i == selected) Tokens.Text.copy(alpha = 0.8f) else Tokens.Accent,
                            topLeft = Offset(xAt(i) - barW / 2, ys),
                            size = Size(barW, (bottom - ys).coerceAtLeast(0f)),
                            cornerRadius = CornerRadius(3.dp.toPx())
                        )
                    }
                    SecondaryMode.MARKER -> {
                        val ym = yAt(s)
                        drawLine(Tokens.Text, Offset(xAt(i) - barW / 2 - 4.dp.toPx(), ym), Offset(xAt(i) + barW / 2 + 4.dp.toPx(), ym), strokeWidth = 2.dp.toPx())
                    }
                    SecondaryMode.NONE -> Unit
                }
            }
        } else {
            val path = Path()
            var open = false
            values.forEachIndexed { i, v ->
                if (v == null) { open = false; return@forEachIndexed }
                if (!open) { path.moveTo(xAt(i), yAt(v)); open = true } else path.lineTo(xAt(i), yAt(v))
            }
            drawPath(path, Tokens.Accent, style = Stroke(width = 2.dp.toPx(), join = StrokeJoin.Round, cap = StrokeCap.Round))
            val ring = 2.dp.toPx()
            values.forEachIndexed { i, v ->
                if (v == null) return@forEachIndexed
                val c = Offset(xAt(i), yAt(v))
                val r = if (i == selected) 6.dp.toPx() else 4.dp.toPx()
                drawCircle(Tokens.Accent, r, c)
                drawCircle(Tokens.Surface, r, c, style = Stroke(ring))
            }
        }

        val labelTop = 188.dp.toPx()
        val axis = labels.mapIndexed { i, text -> AxisLabel(if (n <= 1) 0.5f else (i + 0.5f) / n, text) }
        drawAxisLabels(measurer, axis, { f -> left + f * (right - left) }, labelTop, Tokens.Dim, Tokens.Text, selected ?: -1)

        val sel = selected
        val text = bubbleText
        if (sel != null && sel in 0 until n && text != null) {
            val v = values[sel]
            if (v != null) {
                val layout = measurer.measure(AnnotatedString(text), BubbleStyle)
                val bw = layout.size.width + 14.dp.toPx()
                val bh = 22.dp.toPx()
                val bx = (xAt(sel) - bw / 2).coerceIn(left, maxOf(left, right - bw))
                val by = (yAt(v) - 32.dp.toPx()).coerceAtLeast(0f)
                drawRoundRect(Tokens.Text, topLeft = Offset(bx, by), size = Size(bw, bh), cornerRadius = CornerRadius(6.dp.toPx()))
                drawText(layout, Tokens.AccentInk, topLeft = Offset(bx + 7.dp.toPx(), by + (bh - layout.size.height) / 2f))
            }
        }
    }
}

/** One bar of the hypnogram: [start] and [end] are fractions of the night, [lane] 0 awake, 1 REM, 2 light, 3 deep. */
data class HypnogramBar(val start: Float, val end: Float, val lane: Int)

/** Four-lane hypnogram with lane names in the gutter and "+0h +2h ..." ticks below. */
@Composable
fun Hypnogram(bars: List<HypnogramBar>, ticks: List<AxisLabel>, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val laneNames = listOf("Awake", "REM", "Light", "Deep")
    Canvas(modifier.fillMaxWidth().height(152.dp)) {
        val left = 52.dp.toPx()
        val right = size.width - 4.dp.toPx()
        val laneTop = 12.dp.toPx()
        val laneGap = 30.dp.toPx()
        val barH = 18.dp.toPx()
        laneNames.forEachIndexed { i, name ->
            val y = laneTop + i * laneGap
            drawLine(Tokens.Border, Offset(left, y), Offset(right, y), strokeWidth = 1.dp.toPx())
            val layout = measurer.measure(AnnotatedString(name), LaneLabelStyle)
            drawText(layout, Tokens.Muted, topLeft = Offset(46.dp.toPx() - layout.size.width, y - layout.size.height / 2f))
        }
        for (b in bars) {
            val lane = b.lane.coerceIn(0, 3)
            val x0 = left + b.start.coerceIn(0f, 1f) * (right - left)
            val x1 = left + b.end.coerceIn(0f, 1f) * (right - left)
            val w = maxOf(2.dp.toPx(), x1 - x0 - 1.dp.toPx())
            drawRoundRect(
                HealthColors.HypnogramLanes[lane],
                topLeft = Offset(x0, laneTop + lane * laneGap - barH / 2),
                size = Size(w, barH),
                cornerRadius = CornerRadius(3.dp.toPx())
            )
        }
        drawAxisLabels(measurer, ticks, { f -> left + f * (right - left) }, 136.dp.toPx(), Tokens.Dim, Tokens.Dim, -1, mono = true)
    }
}

/**
 * GPS track scaled into the box with its aspect ratio kept, start marked with a dot. [points] are
 * normalised: x east 0..1, y north 0..1 (larger is further north, so the y axis is flipped here).
 */
@Composable
fun RoutePreview(points: List<Offset>, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(136.dp)) {
        drawRoundRect(HealthColors.RouteGround, cornerRadius = CornerRadius(8.dp.toPx()))
        if (points.size < 2) return@Canvas
        val pad = 16.dp.toPx()
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        val spanX = (maxX - minX).takeIf { it > 0f } ?: 1f
        val spanY = (maxY - minY).takeIf { it > 0f } ?: 1f
        val scale = minOf((size.width - 2 * pad) / spanX, (size.height - 2 * pad) / spanY)
        val offX = (size.width - spanX * scale) / 2
        val offY = (size.height - spanY * scale) / 2
        fun map(p: Offset) = Offset(offX + (p.x - minX) * scale, size.height - offY - (p.y - minY) * scale)
        val path = Path()
        points.forEachIndexed { i, p -> val m = map(p); if (i == 0) path.moveTo(m.x, m.y) else path.lineTo(m.x, m.y) }
        drawPath(path, Tokens.Accent, style = Stroke(width = 2.5.dp.toPx(), join = StrokeJoin.Round, cap = StrokeCap.Round))
        val start = map(points.first())
        drawCircle(Tokens.Text, 5.dp.toPx(), start)
        drawCircle(HealthColors.RouteGround, 5.dp.toPx(), start, style = Stroke(2.dp.toPx()))
    }
}

/** Labels centred on their x, clamped inside the canvas; the [highlight] index is drawn in [highlightColor]. */
private fun DrawScope.drawAxisLabels(
    measurer: TextMeasurer,
    labels: List<AxisLabel>,
    xAt: (Float) -> Float,
    top: Float,
    color: Color,
    highlightColor: Color,
    highlight: Int,
    mono: Boolean = false
) {
    labels.forEachIndexed { i, label ->
        val layout: TextLayoutResult = measurer.measure(AnnotatedString(label.text), if (mono) GridLabelStyle else AxisLabelStyle)
        val x = (xAt(label.fraction) - layout.size.width / 2f).coerceIn(0f, (size.width - layout.size.width).coerceAtLeast(0f))
        drawText(layout, if (i == highlight) highlightColor else color, topLeft = Offset(x, top))
    }
}
