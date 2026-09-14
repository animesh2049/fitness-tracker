package com.animesh.workouttracker.ui.progress

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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.animesh.workouttracker.ui.theme.PlexMono
import com.animesh.workouttracker.ui.theme.PlexSans
import com.animesh.workouttracker.ui.theme.Tokens

private val GridLabelStyle = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.Medium, fontSize = 11.sp)
private val DateLabelStyle = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.Normal, fontSize = 10.sp)
private val BubbleStyle = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)

/**
 * Single-series line chart drawn on a Canvas, matching the Progress design: three grid lines with
 * labels in a left gutter, a 2 dp accent polyline, 8 dp markers (12 dp when selected), date labels
 * under each point and a label bubble above the selected point. Tapping selects the nearest point.
 */
@Composable
fun ProgressChart(chart: ChartData, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val labelled = remember(chart.points.size, chart.selectedIndex) {
        ProgressMath.labelIndices(chart.points.size, chart.selectedIndex)
    }
    val gridLayouts = remember(chart.gridLabels, measurer) {
        chart.gridLabels.map { measurer.measure(AnnotatedString(it), GridLabelStyle) }
    }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(200.dp)
            .pointerInput(chart.points, gridLayouts) {
                val gutter = gutterWidth(gridLayouts.maxOfOrNull { it.size.width } ?: 0)
                val left = gutter
                val right = size.width - 8.dp.toPx()
                val hit = 24.dp.toPx()
                detectTapGestures { offset ->
                    val xs = ProgressMath.xPositions(chart.points.size, left, right)
                    ProgressMath.nearestIndex(xs, offset.x, hit)?.let(onSelect)
                }
            }
    ) {
        val n = chart.points.size
        if (n == 0) return@Canvas
        val left = gutterWidth(gridLayouts.maxOfOrNull { it.size.width } ?: 0)
        val right = size.width - 8.dp.toPx()
        val top = 34.dp.toPx()
        val bottom = 164.dp.toPx()
        val xs = ProgressMath.xPositions(n, left, right)
        fun yAt(value: Double) = bottom - chart.axis.fraction(value) * (bottom - top)

        // Grid lines and their labels in the gutter.
        chart.axis.gridValues.forEachIndexed { i, v ->
            val y = yAt(v)
            drawLine(Tokens.Border, Offset(left, y), Offset(right, y), strokeWidth = 1.dp.toPx())
            val layout = gridLayouts.getOrNull(i) ?: return@forEachIndexed
            drawText(layout, Tokens.Muted, topLeft = Offset(left - 6.dp.toPx() - layout.size.width, y - layout.size.height / 2f))
        }

        // The series.
        val ys = chart.points.map { yAt(it.value) }
        if (n > 1) {
            val path = Path().apply {
                moveTo(xs[0], ys[0])
                for (i in 1 until n) lineTo(xs[i], ys[i])
            }
            drawPath(path, Tokens.Accent, style = Stroke(width = 2.dp.toPx(), join = StrokeJoin.Round, cap = StrokeCap.Round))
        }

        // Markers: accent fill with a 2 dp surface ring; the selected one is larger.
        val ring = 2.dp.toPx()
        for (i in 0 until n) {
            val r = if (i == chart.selectedIndex) 6.dp.toPx() else 4.dp.toPx()
            val c = Offset(xs[i], ys[i])
            drawCircle(Tokens.Accent, r, c)
            drawCircle(Tokens.Surface, r, c, style = Stroke(ring))
        }

        // Date labels under the points, thinned when crowded.
        val labelTop = 176.dp.toPx()
        for (i in labelled) {
            val layout = measurer.measure(AnnotatedString(chart.points[i].dateLabel), DateLabelStyle)
            val x = (xs[i] - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width)
            drawText(layout, if (i == chart.selectedIndex) Tokens.Text else Tokens.Dim, topLeft = Offset(x, labelTop))
        }

        // Label bubble above the selected point, kept inside the plot horizontally.
        val sel = chart.selectedIndex
        if (sel in 0 until n) {
            val layout = measurer.measure(AnnotatedString(chart.points[sel].valueLabel), BubbleStyle)
            val bw = layout.size.width + 12.dp.toPx()
            val bh = 22.dp.toPx()
            val bx = ProgressMath.clampBubbleLeft(xs[sel], bw, left, right)
            val by = (ys[sel] - 32.dp.toPx()).coerceAtLeast(0f)
            drawRoundRect(Tokens.Text, topLeft = Offset(bx, by), size = Size(bw, bh), cornerRadius = CornerRadius(6.dp.toPx()))
            drawText(layout, Tokens.AccentInk, topLeft = Offset(bx + 6.dp.toPx(), by + (bh - layout.size.height) / 2f))
        }
    }
}

/** Left gutter in px: the design's 40 dp, widened when a grid label would not fit. */
private fun androidx.compose.ui.unit.Density.gutterWidth(maxLabelWidthPx: Int): Float =
    maxOf(40.dp.toPx(), maxLabelWidthPx + 10.dp.toPx())
