package com.example.modelviewer.model

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.ViewModel
import com.example.modelviewer.gesture.ContainerLimits
import com.example.modelviewer.gesture.ContainerMath

/**
 * Screen-level state only: the list of model instances. It holds no Filament objects, so it is
 * safe to outlive the Activity; the scene is rebuilt from this list by SceneController.sync().
 */
class ModelViewerViewModel : ViewModel() {

    val instances: SnapshotStateList<ModelInstanceState> = mutableStateListOf()

    private var nextInstanceNumber = 1L
    private var nextZ = 1

    /** Creates a new, independent instance (labels hidden, interaction mode off). */
    fun addModel(
        definition: ModelDefinition,
        viewportWidth: Float,
        viewportHeight: Float,
        limits: ContainerLimits,
    ): ModelInstanceState {
        val n = nextInstanceNumber++
        val bounds = ContainerMath.defaultBounds((n - 1).toInt(), viewportWidth, viewportHeight, limits)
        val state = ModelInstanceState("${definition.id}-$n", definition, bounds, nextZ++)
        instances.add(state)
        return state
    }

    fun closeModel(instanceId: String): Boolean = instances.removeAll { it.instanceId == instanceId }

    fun bringToFront(state: ModelInstanceState) {
        if (state.zOrder != nextZ - 1) state.zOrder = nextZ++
    }

    /** Keeps containers on screen after the viewport size changes (e.g. rotation). */
    fun clampAll(viewportWidth: Float, viewportHeight: Float) {
        if (viewportWidth <= 0f || viewportHeight <= 0f) return
        for (s in instances) {
            s.width = s.width.coerceAtMost(viewportWidth)
            s.height = s.height.coerceAtMost(viewportHeight)
            s.x = ContainerMath.clampOrigin(s.x, s.width, viewportWidth)
            s.y = ContainerMath.clampOrigin(s.y, s.height, viewportHeight)
        }
    }
}
