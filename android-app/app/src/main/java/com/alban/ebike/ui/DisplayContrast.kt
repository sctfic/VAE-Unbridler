package com.alban.ebike.ui

object DisplayContrast {
    const val MINIMUM = .18f
    fun fromLux(lux: Float): Float = if (!lux.isFinite()) MINIMUM else
        MINIMUM + (1 - MINIMUM) * (lux / 8000f).coerceIn(0f, 1f)
}
