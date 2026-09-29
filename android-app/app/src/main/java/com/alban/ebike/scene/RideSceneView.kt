package com.alban.ebike.scene

import android.content.Context
import android.opengl.GLES20.*
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*

class RideSceneView(context: Context) : GLSurfaceView(context) {
    private val sceneRenderer = SceneRenderer()
    var onResetRequested: () -> Unit = {}
    var onViewModeRequested: () -> Unit = {}
    private val scaleGestures = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val factor = detector.scaleFactor.toDouble()
            queueEvent { sceneRenderer.orbit.scale(factor) }
            return true
        }
    }).apply { isQuickScaleEnabled = false }
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onScroll(first: MotionEvent?, current: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (current.pointerCount > 1 || scaleGestures.isInProgress) return true
            val delta = -distanceX / width.coerceAtLeast(1) * Math.PI * 2
            val tiltDelta = distanceY / height.coerceAtLeast(1) * Math.PI / 2
            queueEvent { sceneRenderer.orbit.drag(delta); sceneRenderer.orbit.incline(tiltDelta) }
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean { onResetRequested(); return true }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { onViewModeRequested(); return true }
    })
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
            queueEvent { sceneRenderer.orbit.touch() }
        }
        scaleGestures.onTouchEvent(event)
        gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            val now = SystemClock.uptimeMillis()
            queueEvent { sceneRenderer.orbit.release(now) }
            parent?.requestDisallowInterceptTouchEvent(false)
            if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    fun updateMovement(distanceM: Double, grade: Float = 0f) {
        val now = SystemClock.uptimeMillis()
        queueEvent { sceneRenderer.orbit.movement(distanceM, now); sceneRenderer.grade = grade }
    }
    fun cameraPose() = sceneRenderer.cameraPose()
    private var running = false
    private val frame = object : Runnable {
        override fun run() {
            if (!running) return
            requestRender()
            postDelayed(this, 33) // No continuously running GPU loop in the background.
        }
    }
    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 0, 24, 0)
        preserveEGLContextOnPause = true
        setRenderer(sceneRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }
    fun submit(mesh: SceneMesh) { sceneRenderer.pending = mesh; requestRender() }
    fun resumeScene() {
        if (running) return
        running = true; onResume(); post(frame)
    }
    fun pauseScene() {
        if (!running) return
        running = false; removeCallbacks(frame); onPause()
    }
    override fun onDetachedFromWindow() { pauseScene(); super.onDetachedFromWindow() }
}

data class CameraPose(val heading: Double = 0.0, val tilt: Double = TrackCamera.TILT)

private class SceneRenderer : GLSurfaceView.Renderer {
    val orbit = SceneOrbit()
    var grade = 0f
    @Volatile var pending: SceneMesh? = null
    private var mesh: SceneMesh? = null
    private var buffers = emptyList<FloatBuffer>()
    private var framePoints = emptyList<WorldPoint>()
    private var fittedHeading = Double.NaN
    private var fittedAspect = 0.0
    private var fittedTilt = Double.NaN
    private var fit = 200.0
    private var lighting: FloatBuffer? = null
    private var lightHandle = 0
    private var program = 0
    private var aspect = 1.0
    private var heading = 0.0
    @Volatile private var visiblePose = CameraPose()
    fun cameraPose() = visiblePose
    private var distance = 100.0
    private var maxLineWidth = 1f
    private var positionHandle = 0
    private var colorHandle = 0
    private val uniforms = HashMap<String, Int>()
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            fun shader(type: Int, code: String): Int {
                val id = glCreateShader(type)
                glShaderSource(id, code); glCompileShader(id)
                val ok = IntArray(1); glGetShaderiv(id, GL_COMPILE_STATUS, ok, 0)
                check(ok[0] != 0) { glGetShaderInfoLog(id) }
                return id
            }
            val vs = shader(GL_VERTEX_SHADER, VERTEX)
            val fs = shader(GL_FRAGMENT_SHADER, FRAGMENT)
            program = glCreateProgram()
            glAttachShader(program, vs); glAttachShader(program, fs); glLinkProgram(program)
            val ok = IntArray(1); glGetProgramiv(program, GL_LINK_STATUS, ok, 0)
            check(ok[0] != 0) { glGetProgramInfoLog(program) }
            glDeleteShader(vs); glDeleteShader(fs)
            positionHandle = glGetAttribLocation(program, "a_position")
            lightHandle = glGetAttribLocation(program, "a_light")
            colorHandle = glGetAttribLocation(program, "a_color")
            uniforms.clear()
            listOf("u_center", "u_camera", "u_color", "u_points", "u_size", "u_lift", "u_tilt").forEach { uniforms[it] = glGetUniformLocation(program, it) }
            val widths = FloatArray(2); glGetFloatv(GL_ALIASED_LINE_WIDTH_RANGE, widths, 0); maxLineWidth = widths[1]
        } catch (error: Exception) { program = 0; Log.e("EBikeScene", "GL initialization failed", error) }
        glClearColor(.018f, .038f, .064f, 1f)
        glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glEnable(GL_DEPTH_TEST); glDepthFunc(GL_LEQUAL)
    }
    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        glViewport(0, 0, width, height); aspect = width.toDouble() / height.coerceAtLeast(1)
    }
    override fun onDrawFrame(gl: GL10?) {
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        val next = pending
        if (next != null && next !== mesh) {
            val previous = mesh
            mesh = next
            framePoints = next.frame.map { WorldPoint(it.east - next.center.east, it.north - next.center.north, it.height - next.center.height) }
            fittedHeading = Double.NaN
            val arrays = listOf(next.surface, next.grid, next.contours, next.route, next.marker, next.routeColors, next.roads, next.waterways)
            val previousArrays = previous?.let { listOf(it.surface, it.grid, it.contours, it.route, it.marker, it.routeColors, it.roads, it.waterways) }
            buffers = arrays.mapIndexed { i, values ->
                if (previousArrays?.get(i) === values && buffers.size > i) buffers[i]
                else ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }
            }
            if (previous?.surface !== next.surface) {
            val light = FloatArray(next.surface.size / 3)
            for (i in next.surface.indices step 9) {
                val p = next.surface
                val ux = p[i + 3] - p[i]; val uy = p[i + 4] - p[i + 1]; val uz = p[i + 5] - p[i + 2]
                val vx = p[i + 6] - p[i]; val vy = p[i + 7] - p[i + 1]; val vz = p[i + 8] - p[i + 2]
                val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
                val length = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(.001f)
                val value = .35f + .65f * ((-.4f * nx - .5f * ny + .76f * nz) / length).coerceIn(0f, 1f)
                repeat(3) { light[i / 3 + it] = value }
            }
            lighting = ByteBuffer.allocateDirect(light.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(light); position(0) }
            }
        }
        val scene = mesh ?: return
        if (program == 0) return
        heading = orbit.advance(scene.heading, SystemClock.uptimeMillis(), grade)
        visiblePose = CameraPose(heading, orbit.tilt)
        if (!fittedHeading.isFinite() || abs(GeoFrame.angleDelta(fittedHeading, heading)) > .0001 || fittedAspect != aspect || fittedTilt != orbit.tilt) {
            fit = TrackCamera.fit(framePoints, heading, aspect, orbit.tilt)
            fittedHeading = heading; fittedAspect = aspect; fittedTilt = orbit.tilt
        }
        // Expand immediately to keep every point inside; ease in when the route becomes smaller.
        distance = if (fit > distance) fit else distance + (fit - distance) * .055
        glUseProgram(program)
        glUniform3f(uniforms.getValue("u_center"), scene.center.east.toFloat(), scene.center.north.toFloat(), scene.center.height.toFloat())
        glUniform4f(uniforms.getValue("u_camera"), sin(heading).toFloat(), cos(heading).toFloat(), (distance / orbit.zoom).toFloat(), aspect.toFloat())
        glUniform2f(uniforms.getValue("u_tilt"), sin(orbit.tilt).toFloat(), cos(orbit.tilt).toFloat())
        glEnableVertexAttribArray(positionHandle)
        fun draw(index: Int, primitive: Int, color: FloatArray, width: Float = 1f, lift: Float = 0f, pointSize: Float = 1f, colored: Boolean = false) {
            val buffer = buffers[index]
            if (buffer.capacity() == 0) return
            if (colored && buffers[5].capacity() == buffer.capacity()) {
                glEnableVertexAttribArray(colorHandle)
                glVertexAttribPointer(colorHandle, 3, GL_FLOAT, false, 0, buffers[5])
            } else { glDisableVertexAttribArray(colorHandle); glVertexAttrib3f(colorHandle, 1f, 1f, 1f) }
            glUniform4fv(uniforms.getValue("u_color"), 1, color, 0)
            glUniform1f(uniforms.getValue("u_points"), if (primitive == GL_POINTS) 1f else 0f)
            glUniform1f(uniforms.getValue("u_size"), pointSize)
            glUniform1f(uniforms.getValue("u_lift"), lift)
            glLineWidth(width.coerceIn(1f, maxLineWidth.coerceAtLeast(1f)))
            glVertexAttribPointer(positionHandle, 3, GL_FLOAT, false, 0, buffer)
            glDrawArrays(primitive, 0, buffer.capacity() / 3)
        }
        glDepthMask(true)
        glEnableVertexAttribArray(lightHandle)
        glVertexAttribPointer(lightHandle, 1, GL_FLOAT, false, 0, lighting)
        // Bias the filled ground behind its contour overlay to prevent depth fighting.
        glEnable(GL_POLYGON_OFFSET_FILL)
        glPolygonOffset(1f, 2f)
        draw(0, GL_TRIANGLES, floatArrayOf(.025f, .11f, .16f, 1f))
        glDisable(GL_POLYGON_OFFSET_FILL)
        glDisableVertexAttribArray(lightHandle); glVertexAttrib1f(lightHandle, 1f)
        glDepthMask(false)
        draw(1, GL_LINES, floatArrayOf(.10f, .42f, .50f, .34f))
        draw(2, GL_LINES, floatArrayOf(.18f, .71f, .76f, .65f))
        draw(6, GL_LINES, floatArrayOf(.72f, .80f, .84f, .8f), 2f)
        draw(7, GL_LINES, floatArrayOf(.12f, .55f, 1f, .95f), 3f)
        val rgb = if (scene.speedMode) floatArrayOf(1f, .23f, .30f) else floatArrayOf(.25f, .79f, 1f)
        // Screen-space widths stay readable as the camera pulls away.
        draw(3, GL_LINES, floatArrayOf(0f, 0f, .02f, .8f), 9f, -1.5f)
        glDisable(GL_DEPTH_TEST) // Route and rider remain identifiable behind ridges.
        draw(3, GL_LINES, floatArrayOf(1f, 1f, 1f, .13f), 14f, colored = true)
        draw(3, GL_LINES, floatArrayOf(1f, 1f, 1f, .35f), 8f, colored = true)
        draw(3, GL_LINES, floatArrayOf(1f, 1f, 1f, 1f), 4f, colored = true)
        val phase = ((SystemClock.uptimeMillis() % 2200) / 2200f)
        draw(4, GL_POINTS, floatArrayOf(rgb[0], rgb[1], rgb[2], .3f * (1 - phase)), pointSize = 30 + 35 * phase)
        draw(4, GL_POINTS, floatArrayOf(rgb[0], rgb[1], rgb[2], 1f), pointSize = 17f)
        draw(4, GL_POINTS, floatArrayOf(.9f, 1f, 1f, 1f), pointSize = 7f)
        glEnable(GL_DEPTH_TEST); glDepthMask(true)
        glDisableVertexAttribArray(positionHandle)
    }

    companion object {
        private const val VERTEX = """
            attribute vec3 a_position;
            attribute float a_light;
            attribute vec3 a_color;
            varying vec3 v_color;
            uniform vec3 u_center;
            uniform vec4 u_camera;
            uniform vec2 u_tilt;
            uniform float u_size;
            uniform float u_lift;
            varying float v_fog;
            varying float v_height;
            varying float v_light;
            void main() {
                vec3 p = a_position - u_center;
                p.z += u_lift;
                float right = p.x * u_camera.y - p.y * u_camera.x;
                float forward = p.x * u_camera.x + p.y * u_camera.y;
                float vertical = forward * u_tilt.x + p.z * u_tilt.y;
                float depth = u_camera.z + forward * u_tilt.y - p.z * u_tilt.x;
                float nearPlane = max(0.5, u_camera.z * 0.001);
                gl_Position = vec4(right * 1.9 / u_camera.w, vertical * 1.9,
                    depth - 2.0 * nearPlane, depth);
                gl_PointSize = u_size;
                v_fog = clamp(depth / (u_camera.z * 3.0), 0.0, 0.82);
                v_height = a_position.z;
                v_light = a_light;
                v_color = a_color;
            }
        """
        private const val FRAGMENT = """
            precision mediump float;
            uniform vec4 u_color;
            uniform float u_points;
            varying float v_fog;
            varying float v_height;
            varying float v_light;
            varying vec3 v_color;
            void main() {
                float alpha = u_color.a;
                if (u_points > 0.5) {
                    float d = length(gl_PointCoord - vec2(0.5));
                    if (d > 0.5) discard;
                    alpha *= 1.0 - smoothstep(0.25, 0.5, d);
                }
                float light = (0.86 + 0.14 * sin(v_height * 0.03)) * v_light;
                gl_FragColor = vec4(mix(u_color.rgb * v_color * light, vec3(0.018, 0.038, 0.064), v_fog), alpha);
            }
        """
    }
}
