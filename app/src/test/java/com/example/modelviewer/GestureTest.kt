package com.example.modelviewer

import com.example.modelviewer.gesture.GestureInterpreter
import com.example.modelviewer.gesture.GestureKind
import com.example.modelviewer.gesture.GestureSession
import com.example.modelviewer.model.InteractionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GestureTest {
    private val normal = InteractionMode.NORMAL
    private val interact = InteractionMode.INTERACTION

    @Test fun gestureMatrix() {
        assertEquals(GestureKind.MOVE, GestureInterpreter.kindFor(normal, 1))
        assertEquals(GestureKind.RESIZE, GestureInterpreter.kindFor(normal, 2))
        assertEquals(GestureKind.ROTATE, GestureInterpreter.kindFor(interact, 1))
        assertEquals(GestureKind.ZOOM, GestureInterpreter.kindFor(interact, 2))
        assertEquals(GestureKind.RESIZE, GestureInterpreter.kindFor(normal, 3))
        assertNull(GestureInterpreter.kindFor(normal, 0))
    }

    @Test fun kindsBelongToExactlyOneMode() {
        assertEquals(normal, GestureKind.MOVE.requiredMode)
        assertEquals(normal, GestureKind.RESIZE.requiredMode)
        assertEquals(interact, GestureKind.ROTATE.requiredMode)
        assertEquals(interact, GestureKind.ZOOM.requiredMode)
    }

    @Test fun normalModeInterpretation() {
        val s = GestureSession()
        assertEquals(GestureKind.MOVE, s.update(normal, 1, 4f, -2f, 1f))
        assertEquals(4f, s.dx, 0f); assertEquals(-2f, s.dy, 0f)
        assertEquals(GestureKind.RESIZE, s.update(normal, 2, 0f, 0f, 1.25f))
        assertEquals(1.25f, s.scale, 0f)
    }

    @Test fun interactionModeInterpretation() {
        val s = GestureSession()
        assertEquals(GestureKind.ROTATE, s.update(interact, 1, 3f, 9f, 1f))
        assertEquals(3f, s.dx, 0f); assertEquals(9f, s.dy, 0f)
        assertEquals(GestureKind.ZOOM, s.update(interact, 2, 0f, 0f, 0.8f))
        assertEquals(0.8f, s.scale, 0f)
    }

    @Test fun remainingFingerAfterPinchIsIgnoredUntilAllLift() {
        val s = GestureSession()
        s.update(normal, 2, 0f, 0f, 1.1f)
        assertNull(s.update(normal, 1, 10f, 10f, 1f)) // would otherwise MOVE
        s.reset() // next gesture
        assertEquals(GestureKind.MOVE, s.update(normal, 1, 1f, 1f, 1f))
    }

    @Test fun resetClearsStaleValues() {
        val s = GestureSession()
        s.update(interact, 2, 0f, 0f, 2f)
        s.reset()
        assertEquals(1f, s.scale, 0f); assertEquals(0f, s.dx, 0f)
    }
}
