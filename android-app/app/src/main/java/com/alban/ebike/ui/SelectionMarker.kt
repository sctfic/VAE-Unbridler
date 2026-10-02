package com.alban.ebike.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Same screen-space ring for the altitude profile and its selected 3D pivot. */
internal fun DrawScope.drawSelectionMarker(position: Offset) {
    drawCircle(Color(0xFF050A11), 8.dp.toPx(), position, style = Stroke(5.dp.toPx()))
    drawCircle(Color.White, 8.dp.toPx(), position, style = Stroke(2.dp.toPx()))
}

/** Thin magenta edges leave the original route colour visible in the centre. */
internal fun DrawScope.drawPauseBorder(start: Offset, end: Offset, routeWidth: Float) {
    val delta = end - start
    val length = delta.getDistance()
    val edge = routeWidth / 2 + .5.dp.toPx()
    if (length < .5f) {
        drawCircle(Color.Magenta, edge, start, style = Stroke(1.dp.toPx()))
        return
    }
    val normal = Offset(-delta.y / length, delta.x / length) * edge
    drawLine(Color.Magenta, start + normal, end + normal, 1.dp.toPx())
    drawLine(Color.Magenta, start - normal, end - normal, 1.dp.toPx())
}
