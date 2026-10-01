package com.example.modelviewer.model

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.modelviewer.gesture.ContainerBounds
import com.example.modelviewer.gesture.ContainerLimits
import com.example.modelviewer.gesture.ContainerMath
import com.example.modelviewer.gesture.GestureKind

enum class InteractionMode { NORMAL, INTERACTION }

sealed interface LoadState {
    data object Loading : LoadState
    data object Ready : LoadState
    data class Error(val message: String) : LoadState
}

/**
 * All per-instance state. Each rapidly-changing value is its own snapshot state so that Compose
 * only re-runs the phase that actually reads it (layout for x/y/width/height, the render loop
 * reads yaw/pitch/zoom directly). Nothing here causes a screen-wide recomposition while dragging.
 */
@Stable
class ModelInstanceState(
    val instanceId: String,
    val definition: ModelDefinition,
    bounds: ContainerBounds,
    zOrder: Int,
) {
    // Container geometry in pixels, relative to the viewer's top-left.
    var x by mutableFloatStateOf(bounds.x)
    var y by mutableFloatStateOf(bounds.y)
    var width by mutableFloatStateOf(bounds.width)
    var height by mutableFloatStateOf(bounds.height)

    // Model orientation / zoom inside the container.
    var yawDeg by mutableFloatStateOf(0f)
    var pitchDeg by mutableFloatStateOf(0f)
    var zoom by mutableFloatStateOf(1f)

    var interactionMode by mutableStateOf(InteractionMode.NORMAL)
    var labelsVisible by mutableStateOf(false)
    var loadState by mutableStateOf<LoadState>(LoadState.Loading)

    /** Higher = drawn in front. */
    var zOrder by mutableIntStateOf(zOrder)

    fun toggleInteraction() {
        interactionMode =
            if (interactionMode == InteractionMode.NORMAL) InteractionMode.INTERACTION else InteractionMode.NORMAL
    }

    fun toggleLabels() {
        labelsVisible = !labelsVisible
    }

    /**
     * Applies one gesture step. A gesture kind that does not belong to the current mode is ignored
     * (belt and braces on top of [com.example.modelviewer.gesture.GestureInterpreter]).
     */
    fun applyGesture(
        kind: GestureKind,
        dx: Float,
        dy: Float,
        scale: Float,
        viewportWidth: Float,
        viewportHeight: Float,
        limits: ContainerLimits,
    ) {
        if (kind.requiredMode != interactionMode) return
        when (kind) {
            GestureKind.MOVE -> {
                x = ContainerMath.clampOrigin(x + dx, width, viewportWidth)
                y = ContainerMath.clampOrigin(y + dy, height, viewportHeight)
            }
            GestureKind.RESIZE -> {
                val s = ContainerMath.resizeScale(width, height, scale, limits, viewportWidth, viewportHeight)
                val cx = x + width / 2f
                val cy = y + height / 2f
                width *= s
                height *= s
                x = ContainerMath.clampOrigin(cx - width / 2f, width, viewportWidth)
                y = ContainerMath.clampOrigin(cy - height / 2f, height, viewportHeight)
            }
            GestureKind.ROTATE -> {
                yawDeg = ContainerMath.wrapDegrees(yawDeg + dx * ContainerMath.DEG_PER_PIXEL)
                pitchDeg = ContainerMath.clampPitch(pitchDeg + dy * ContainerMath.DEG_PER_PIXEL)
            }
            GestureKind.ZOOM -> zoom = ContainerMath.clampZoom(zoom * scale)
        }
    }
}
