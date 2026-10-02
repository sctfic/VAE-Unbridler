package com.alban.ebike.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.random.Random

/** Displaces a minority of horizontal glyph strips; keeps most of the number readable. */
@Composable
internal fun speedGlitch(enabled: Boolean): Modifier {
    var bands by remember { mutableStateOf(FloatArray(14)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(enabled, lifecycle) {
        if (!enabled) { bands = FloatArray(14); return@LaunchedEffect }
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                bands = FloatArray(14) { if (Random.nextFloat() < .25f) Random.nextFloat() * 10f - 5f else 0f }
                delay(140)
            }
        }
    }
    return if (!enabled) Modifier else Modifier.drawWithContent {
        val offsets = bands
        offsets.forEachIndexed { index, offset ->
            clipRect(left = -6.dp.toPx(), top = size.height * index / offsets.size,
                right = size.width + 6.dp.toPx(), bottom = size.height * (index + 1) / offsets.size) {
                translate(left = offset.dp.toPx()) { this@drawWithContent.drawContent() }
            }
        }
    }
}
