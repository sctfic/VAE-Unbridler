package com.alban.ebike.scene

/** Segment quads expanded in screen space by the shader, independent of GL line-width limits. */
data class RouteRibbon(val positions: FloatArray, val colors: FloatArray,
    val neighbors: FloatArray, val sides: FloatArray) {
    companion object {
        fun build(lines: FloatArray, colors: FloatArray): RouteRibbon {
            require(lines.size % 6 == 0 && (colors.isEmpty() || colors.size == lines.size))
            val positions = FloatArray(lines.size * 3)
            val shades = FloatArray(positions.size)
            val neighbors = FloatArray(positions.size)
            val sides = FloatArray(positions.size / 3)
            val ends = intArrayOf(0, 0, 1, 1, 0, 1)
            val edges = floatArrayOf(-1f, 1f, -1f, -1f, 1f, 1f)
            for (segment in 0 until lines.size / 6) for (corner in 0..5) {
                val vertex = segment * 6 + corner
                val end = ends[corner]
                val from = segment * 6 + end * 3
                val other = segment * 6 + (1 - end) * 3
                repeat(3) { axis ->
                    positions[vertex * 3 + axis] = lines[from + axis]
                    shades[vertex * 3 + axis] = if (colors.isEmpty()) 1f else colors[from + axis]
                    neighbors[vertex * 3 + axis] = lines[other + axis]
                }
                sides[vertex] = edges[corner] * if (end == 0) 1f else -1f
            }
            return RouteRibbon(positions, shades, neighbors, sides)
        }
    }
}
