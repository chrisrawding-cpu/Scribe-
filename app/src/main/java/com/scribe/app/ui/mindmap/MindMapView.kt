package com.scribe.app.ui.mindmap

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import com.scribe.app.data.MindNode
import com.scribe.app.ui.theme.Palette

private class LaidNode(
    val path: String,
    val depth: Int,
    val text: TextLayoutResult,
    val w: Float,
    val h: Float,
    val color: Color,
    val childCount: Int,
    val collapsed: Boolean,
) {
    var x = 0f
    var y = 0f
    val children = mutableListOf<LaidNode>()
    val cy get() = y + h / 2
}

private class MapLayout(val root: LaidNode, val all: List<LaidNode>, val width: Float, val height: Float)

private fun layout(
    root: MindNode,
    collapsed: Set<String>,
    measurer: TextMeasurer,
    density: Float,
    primary: Color,
): MapLayout {
    val padX = 14 * density
    val padY = 9 * density
    val hGap = 44 * density
    val vGap = 10 * density
    val all = mutableListOf<LaidNode>()

    fun build(n: MindNode, path: String, depth: Int, color: Color): LaidNode {
        val style = TextStyle(
            fontSize = when (depth) { 0 -> 18.sp; 1 -> 15.sp; else -> 13.sp },
            fontWeight = if (depth <= 1) FontWeight.SemiBold else FontWeight.Normal,
        )
        val maxW = ((if (depth == 0) 220 else 180) * density).toInt()
        val t = measurer.measure(n.label.ifBlank { "…" }, style, constraints = Constraints(maxWidth = maxW))
        val isCollapsed = path in collapsed
        val ln = LaidNode(path, depth, t, t.size.width + 2 * padX, t.size.height + 2 * padY, color, n.children.size, isCollapsed)
        all += ln
        if (!isCollapsed) n.children.forEachIndexed { i, c ->
            val cc = if (depth == 0) Palette[i % Palette.size] else color
            ln.children += build(c, "$path/$i", depth + 1, cc)
        }
        return ln
    }

    val r = build(root, "0", 0, primary)

    fun place(n: LaidNode, x: Float, top: Float): Float {
        n.x = x
        if (n.children.isEmpty()) {
            n.y = top
            return top + n.h + vGap
        }
        var t = top
        n.children.forEach { t = place(it, x + n.w + hGap, t) }
        val mid = (n.children.first().cy + n.children.last().cy) / 2
        n.y = mid - n.h / 2
        if (n.y < top) {
            // parent taller than its children block — shift children down
            val shift = top - n.y
            fun move(m: LaidNode) { m.y += shift; m.children.forEach { move(it) } }
            n.children.forEach { move(it) }
            n.y = top
            t += shift
        }
        return maxOf(t, n.y + n.h + vGap)
    }
    val totalH = place(r, 0f, 0f)
    val totalW = all.maxOf { it.x + it.w }
    return MapLayout(r, all, totalW, totalH)
}

@Composable
fun MindMapView(root: MindNode, modifier: Modifier = Modifier, fitKey: Int = 0) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current.density
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val surface = MaterialTheme.colorScheme.surface
    var collapsed by remember(root) { mutableStateOf(setOf<String>()) }
    val map = remember(root, collapsed, primary) { layout(root, collapsed, measurer, density, primary) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    BoxWithConstraints(modifier.fillMaxSize().clipToBounds()) {
        val vw = constraints.maxWidth.toFloat()
        val vh = constraints.maxHeight.toFloat()
        LaunchedEffect(root, fitKey, vw, vh) {
            val m = layout(root, emptySet(), measurer, density, primary)
            collapsed = emptySet()
            val pad = 24 * density
            val s = minOf((vw - 2 * pad) / m.width, (vh - 2 * pad) / m.height, 1.3f).coerceAtLeast(0.25f)
            scale = s
            offset = Offset((vw - m.width * s) / 2, (vh - m.height * s) / 2)
        }
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val ns = (scale * zoom).coerceIn(0.15f, 5f)
                        offset = (offset - centroid) * (ns / scale) + centroid + pan
                        scale = ns
                    }
                }
                .pointerInput(map) {
                    detectTapGestures(onDoubleTap = { p ->
                        val ns = (scale * 1.6f).coerceAtMost(5f)
                        offset = (offset - p) * (ns / scale) + p
                        scale = ns
                    }) { p ->
                        val c = (p - offset) / scale
                        map.all.firstOrNull { c.x in it.x..(it.x + it.w) && c.y in it.y..(it.y + it.h) }?.let { hit ->
                            if (hit.childCount > 0) collapsed = if (hit.path in collapsed) collapsed - hit.path else collapsed + hit.path
                        }
                    }
                }
        ) {
            withTransform({
                translate(offset.x, offset.y)
                scale(scale, scale, Offset.Zero)
            }) {
                // edges
                map.all.forEach { p ->
                    p.children.forEach { c ->
                        val start = Offset(p.x + p.w, p.cy)
                        val end = Offset(c.x, c.cy)
                        val dx = (end.x - start.x) / 2
                        val path = Path().apply {
                            moveTo(start.x, start.y)
                            cubicTo(start.x + dx, start.y, end.x - dx, end.y, end.x, end.y)
                        }
                        drawPath(path, c.color.copy(alpha = 0.7f), style = Stroke(width = (if (c.depth == 1) 3f else 2f) * density))
                    }
                }
                // nodes
                map.all.forEach { n ->
                    val radius = CornerRadius(12 * density, 12 * density)
                    when (n.depth) {
                        0 -> drawRoundRect(n.color, Offset(n.x, n.y), Size(n.w, n.h), radius)
                        1 -> drawRoundRect(n.color, Offset(n.x, n.y), Size(n.w, n.h), radius)
                        else -> {
                            drawRoundRect(surface, Offset(n.x, n.y), Size(n.w, n.h), radius)
                            drawRoundRect(n.color.copy(alpha = 0.14f), Offset(n.x, n.y), Size(n.w, n.h), radius)
                            drawRoundRect(n.color.copy(alpha = 0.6f), Offset(n.x, n.y), Size(n.w, n.h), radius, style = Stroke(1.5f * density))
                        }
                    }
                    val textColor = if (n.depth <= 1) Color.White else onSurface
                    translate(n.x + (n.w - n.text.size.width) / 2, n.y + (n.h - n.text.size.height) / 2) {
                        drawText(n.text, color = textColor)
                    }
                    if (n.collapsed && n.childCount > 0) {
                        val cx = n.x + n.w + 10 * density
                        drawCircle(n.color, 9 * density, Offset(cx, n.cy))
                        val t = measurer.measure("+${n.childCount}", TextStyle(fontSize = 10.sp, color = Color.White))
                        drawText(t, topLeft = Offset(cx - t.size.width / 2, n.cy - t.size.height / 2))
                    }
                }
            }
        }
    }
}
