package com.example.modelviewer.render

import kotlin.math.tan

/**
 * The single shared camera: a pinhole at the origin looking down -Z with a vertical field of view.
 * SceneController pushes exactly these parameters into the Filament camera every frame, so this
 * math and the rendered image agree. Used both to place models behind their 2D containers
 * (screen -> world) and to place labels (world -> screen). Pure Kotlin, allocation-free.
 */
class CameraRig(val fovYDegrees: Double) {
    private val tanHalfFov = tan(Math.toRadians(fovYDegrees / 2.0)).toFloat()
    var viewWidth = 0f
        private set
    var viewHeight = 0f
        private set

    fun setViewport(width: Float, height: Float) {
        viewWidth = width
        viewHeight = height
    }

    /** World units covered by one screen pixel on a plane [depth] units in front of the camera. */
    fun worldUnitsPerPixel(depth: Float): Float = 2f * depth * tanHalfFov / viewHeight

    fun screenToWorldX(px: Float, depth: Float): Float = (px - viewWidth / 2f) * worldUnitsPerPixel(depth)

    fun screenToWorldY(py: Float, depth: Float): Float = -(py - viewHeight / 2f) * worldUnitsPerPixel(depth)

    /**
     * Projects a world position to pixels, writing x,y into out[offset], out[offset+1].
     * Returns false if the point is behind / too close to the camera or not finite.
     */
    fun project(wx: Float, wy: Float, wz: Float, nearPlane: Float, out: FloatArray, offset: Int): Boolean {
        val depth = -wz
        if (!(depth > nearPlane) || !wx.isFinite() || !wy.isFinite()) return false
        val pixelsPerUnit = viewHeight / (2f * depth * tanHalfFov)
        out[offset] = viewWidth / 2f + wx * pixelsPerUnit
        out[offset + 1] = viewHeight / 2f - wy * pixelsPerUnit
        return true
    }
}
