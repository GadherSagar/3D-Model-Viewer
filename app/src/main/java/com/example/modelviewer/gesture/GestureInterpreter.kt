package com.example.modelviewer.gesture

import com.example.modelviewer.model.InteractionMode

/**
 * The gesture matrix:
 *
 *              NORMAL    INTERACTION
 *  1 finger    MOVE      ROTATE
 *  2 fingers   RESIZE    ZOOM
 */
enum class GestureKind(val requiredMode: InteractionMode) {
    MOVE(InteractionMode.NORMAL),
    RESIZE(InteractionMode.NORMAL),
    ROTATE(InteractionMode.INTERACTION),
    ZOOM(InteractionMode.INTERACTION),
}

object GestureInterpreter {
    fun kindFor(mode: InteractionMode, pointerCount: Int): GestureKind? = when {
        pointerCount <= 0 -> null
        pointerCount == 1 -> if (mode == InteractionMode.NORMAL) GestureKind.MOVE else GestureKind.ROTATE
        else -> if (mode == InteractionMode.NORMAL) GestureKind.RESIZE else GestureKind.ZOOM
    }
}

/**
 * Per-container, per-gesture bookkeeping. Reusable and allocation-free: results are read from
 * [dx]/[dy]/[scale] after [update] returns a kind.
 *
 * Once a second finger has been seen, the remaining finger is ignored until every finger lifts,
 * so lifting one finger of a pinch cannot turn into an unintended drag/rotate.
 */
class GestureSession {
    var dx = 0f
        private set
    var dy = 0f
        private set
    var scale = 1f
        private set
    private var multiTouchSeen = false

    /** Drops any stale tracking (called on mode change and at the start of every gesture). */
    fun reset() {
        multiTouchSeen = false
        dx = 0f
        dy = 0f
        scale = 1f
    }

    fun update(mode: InteractionMode, pointerCount: Int, panDx: Float, panDy: Float, zoom: Float): GestureKind? {
        if (pointerCount >= 2) multiTouchSeen = true else if (multiTouchSeen) return null
        val kind = GestureInterpreter.kindFor(mode, pointerCount) ?: return null
        when (kind) {
            GestureKind.MOVE, GestureKind.ROTATE -> { dx = panDx; dy = panDy }
            GestureKind.RESIZE, GestureKind.ZOOM -> scale = zoom
        }
        return kind
    }
}
