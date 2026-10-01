package com.example.modelviewer.gesture

import kotlin.math.max
import kotlin.math.min

data class ContainerBounds(val x: Float, val y: Float, val width: Float, val height: Float)

data class ContainerLimits(val minWidth: Float, val minHeight: Float)

/** Pure, allocation-free geometry helpers for containers and model orientation. Unit tested. */
object ContainerMath {
    const val DEG_PER_PIXEL = 0.4f
    const val MIN_PITCH_DEG = -85f
    const val MAX_PITCH_DEG = 85f
    const val MIN_ZOOM = 0.4f
    const val MAX_ZOOM = 3f
    private const val CASCADE_SLOTS = 6

    /** Keeps [value, value+size] inside [0, viewport] (pins to 0 if the box is larger than the viewport). */
    fun clampOrigin(value: Float, size: Float, viewport: Float): Float =
        value.coerceIn(0f, max(0f, viewport - size))

    /**
     * Clamps a requested uniform resize factor so both dimensions stay within
     * [limits.min*, viewport]. Returns 1 for invalid input.
     */
    fun resizeScale(
        width: Float,
        height: Float,
        requested: Float,
        limits: ContainerLimits,
        viewportWidth: Float,
        viewportHeight: Float,
    ): Float {
        if (width <= 0f || height <= 0f || !requested.isFinite() || requested <= 0f) return 1f
        val maxScale = min(viewportWidth / width, viewportHeight / height)
        val minScale = min(max(limits.minWidth / width, limits.minHeight / height), maxScale)
        return requested.coerceIn(minScale, maxScale)
    }

    fun wrapDegrees(degrees: Float): Float {
        var r = degrees % 360f
        if (r > 180f) r -= 360f else if (r < -180f) r += 360f
        return r
    }

    fun clampPitch(degrees: Float): Float = degrees.coerceIn(MIN_PITCH_DEG, MAX_PITCH_DEG)

    fun clampZoom(zoom: Float): Float = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)

    /** Cascading default placement so successive models do not sit exactly on top of each other. */
    fun defaultBounds(index: Int, viewportWidth: Float, viewportHeight: Float, limits: ContainerLimits): ContainerBounds {
        val vw = if (viewportWidth > 0f) viewportWidth else 1080f
        val vh = if (viewportHeight > 0f) viewportHeight else 1920f
        val shortSide = min(vw, vh)
        val side = (shortSide * 0.55f)
            .coerceAtLeast(max(limits.minWidth, limits.minHeight))
            .coerceAtMost(shortSide)
        val slot = (index % CASCADE_SLOTS).toFloat() - (CASCADE_SLOTS - 1) / 2f
        val step = shortSide * 0.07f
        return ContainerBounds(
            x = clampOrigin((vw - side) / 2f + slot * step, side, vw),
            y = clampOrigin((vh - side) / 2f + slot * step, side, vh),
            width = side,
            height = side,
        )
    }
}
