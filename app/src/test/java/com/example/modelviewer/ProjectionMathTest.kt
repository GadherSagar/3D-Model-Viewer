package com.example.modelviewer

import com.example.modelviewer.render.CameraRig
import com.example.modelviewer.render.OrientationMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionMathTest {
    private val rig = CameraRig(40.0).apply { setViewport(1000f, 2000f) }

    @Test fun screenToWorldToScreenRoundTrips() {
        val out = FloatArray(2)
        for (depth in listOf(2f, 4f, 13f)) {
            for ((px, py) in listOf(500f to 1000f, 0f to 0f, 1000f to 2000f, 123f to 1777f)) {
                val wx = rig.screenToWorldX(px, depth)
                val wy = rig.screenToWorldY(py, depth)
                assertTrue(rig.project(wx, wy, -depth, 0.05f, out, 0))
                assertEquals(px, out[0], 0.05f)
                assertEquals(py, out[1], 0.05f)
            }
        }
    }

    @Test fun centreProjectsToViewportCentre() {
        val out = FloatArray(2)
        rig.project(0f, 0f, -5f, 0.05f, out, 0)
        assertEquals(500f, out[0], 1e-3f); assertEquals(1000f, out[1], 1e-3f)
    }

    @Test fun upIsUpAndRightIsRight() {
        val out = FloatArray(2)
        rig.project(1f, 1f, -5f, 0.05f, out, 0)
        assertTrue(out[0] > 500f)
        assertTrue(out[1] < 1000f) // screen y grows downward
    }

    @Test fun pointsBehindOrAtCameraAreRejected() {
        val out = FloatArray(2)
        assertFalse(rig.project(0f, 0f, 1f, 0.05f, out, 0))
        assertFalse(rig.project(0f, 0f, 0f, 0.05f, out, 0))
        assertFalse(rig.project(Float.NaN, 0f, -5f, 0.05f, out, 0))
    }

    @Test fun projectWritesAtOffset() {
        val out = FloatArray(6)
        rig.project(0f, 0f, -5f, 0.05f, out, 4)
        assertEquals(500f, out[4], 1e-3f); assertEquals(0f, out[0], 0f)
    }

    @Test fun yawTurnsFrontTowardsRight() {
        val q = FloatArray(4); val v = FloatArray(3)
        OrientationMath.yawPitch(90f, 0f, q)
        OrientationMath.rotate(q, 0f, 0f, 1f, v)
        assertEquals(1f, v[0], 1e-5f); assertEquals(0f, v[2], 1e-5f)
    }

    @Test fun pitchTurnsTopTowardsViewer() {
        val q = FloatArray(4); val v = FloatArray(3)
        OrientationMath.yawPitch(0f, 90f, q)
        OrientationMath.rotate(q, 0f, 1f, 0f, v)
        assertEquals(1f, v[2], 1e-5f); assertEquals(0f, v[1], 1e-5f)
    }

    @Test fun identityOrientationKeepsVector() {
        val q = FloatArray(4); val v = FloatArray(3)
        OrientationMath.yawPitch(0f, 0f, q)
        OrientationMath.rotate(q, 1f, 2f, 3f, v)
        assertEquals(1f, v[0], 1e-6f); assertEquals(2f, v[1], 1e-6f); assertEquals(3f, v[2], 1e-6f)
    }
}
