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

/** Opposing brackets remain distinct even when a pause starts and ends at the same position. */
internal fun DrawScope.drawPauseBracket(position: Offset, start: Boolean) {
    val direction = if (start) -1f else 1f
    val x = position.x + direction * 7.dp.toPx()
    val top = position.y - 9.dp.toPx()
    val bottom = position.y + 9.dp.toPx()
    for ((color, width) in listOf(Color.Black to 5.dp.toPx(), Color.Magenta to 2.dp.toPx())) {
        drawLine(color, Offset(x, top), Offset(x, bottom), width)
        drawLine(color, Offset(x, top), Offset(x - direction * 5.dp.toPx(), top), width)
        drawLine(color, Offset(x, bottom), Offset(x - direction * 5.dp.toPx(), bottom), width)
    }
}
