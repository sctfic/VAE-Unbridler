package com.alban.ebike.terrain

import com.alban.ebike.scene.SceneWindow

/** Bounded work per view: actual IGN samples, render vertices, contour interval in metres. */
enum class TerrainDetail(val sourceSize: Int, val renderSize: Int, val contourM: Double) {
    CLOSE(129, 257, 5.0), REGIONAL(97, 193, 10.0), OVERVIEW(65, 129, 20.0);

    companion object {
        fun forWindow(window: SceneWindow) = when (window) {
            SceneWindow.LAST_500_M -> CLOSE
            SceneWindow.LAST_2_KM -> REGIONAL
            SceneWindow.ALL -> OVERVIEW
        }
    }
}
