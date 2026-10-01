package com.example.modelviewer

import com.example.modelviewer.gesture.ContainerBounds
import com.example.modelviewer.gesture.ContainerLimits
import com.example.modelviewer.gesture.ContainerMath
import com.example.modelviewer.gesture.GestureKind
import com.example.modelviewer.model.InteractionMode
import com.example.modelviewer.model.ModelCatalog
import com.example.modelviewer.model.ModelInstanceState
import com.example.modelviewer.model.ModelViewerViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContainerAndStateTest {
    private val limits = ContainerLimits(200f, 150f)
    private val def = ModelCatalog.all[0]
    private fun newState() = ModelInstanceState("t-1", def, ContainerBounds(100f, 100f, 300f, 300f), 1)
    private fun apply(s: ModelInstanceState, k: GestureKind, dx: Float = 0f, dy: Float = 0f, scale: Float = 1f) =
        s.applyGesture(k, dx, dy, scale, 1000f, 2000f, limits)

    @Test fun catalogHasFiveFileBackedDefinitions() {
        assertEquals(5, ModelCatalog.all.size)
        assertEquals(5, ModelCatalog.all.map { it.id }.toSet().size)
        assertEquals("models/model1.glb", ModelCatalog.all[0].assetPath)
        assertEquals("models/model5.glb", ModelCatalog.all[4].assetPath)
        assertTrue(ModelCatalog.all.all { it.displayName.isNotBlank() })
    }

    @Test fun newInstanceDefaults() {
        val s = newState()
        assertEquals(InteractionMode.NORMAL, s.interactionMode)
        assertFalse(s.labelsVisible)
        assertEquals(1f, s.zoom, 0f)
    }

    @Test fun modeToggleFlipsBothWays() {
        val s = newState()
        s.toggleInteraction(); assertEquals(InteractionMode.INTERACTION, s.interactionMode)
        s.toggleInteraction(); assertEquals(InteractionMode.NORMAL, s.interactionMode)
    }

    @Test fun normalModeMovesAndResizesButNeverRotates() {
        val s = newState()
        apply(s, GestureKind.MOVE, dx = 50f, dy = 20f)
        assertEquals(150f, s.x, 0f); assertEquals(120f, s.y, 0f)
        apply(s, GestureKind.RESIZE, scale = 1.5f)
        assertEquals(450f, s.width, 0.01f)
        apply(s, GestureKind.ROTATE, dx = 100f, dy = 100f)
        apply(s, GestureKind.ZOOM, scale = 2f)
        assertEquals(0f, s.yawDeg, 0f); assertEquals(0f, s.pitchDeg, 0f); assertEquals(1f, s.zoom, 0f)
    }

    @Test fun interactionModeRotatesAndZoomsButNeverMovesOrResizes() {
        val s = newState()
        s.toggleInteraction()
        apply(s, GestureKind.ROTATE, dx = 100f, dy = 50f)
        assertEquals(100f * ContainerMath.DEG_PER_PIXEL, s.yawDeg, 1e-4f)
        assertEquals(50f * ContainerMath.DEG_PER_PIXEL, s.pitchDeg, 1e-4f)
        apply(s, GestureKind.ZOOM, scale = 1.5f)
        assertEquals(1.5f, s.zoom, 1e-5f)
        apply(s, GestureKind.MOVE, dx = 300f, dy = 300f)
        apply(s, GestureKind.RESIZE, scale = 2f)
        assertEquals(ContainerBounds(100f, 100f, 300f, 300f), ContainerBounds(s.x, s.y, s.width, s.height))
    }

    @Test fun pitchAndZoomAreClamped() {
        val s = newState(); s.toggleInteraction()
        apply(s, GestureKind.ROTATE, dy = 100000f)
        assertEquals(ContainerMath.MAX_PITCH_DEG, s.pitchDeg, 0f)
        apply(s, GestureKind.ROTATE, dy = -1000000f)
        assertEquals(ContainerMath.MIN_PITCH_DEG, s.pitchDeg, 0f)
        repeat(20) { apply(s, GestureKind.ZOOM, scale = 2f) }
        assertEquals(ContainerMath.MAX_ZOOM, s.zoom, 0f)
        repeat(40) { apply(s, GestureKind.ZOOM, scale = 0.5f) }
        assertEquals(ContainerMath.MIN_ZOOM, s.zoom, 0f)
    }

    @Test fun moveStaysOnScreen() {
        val s = newState()
        apply(s, GestureKind.MOVE, dx = 99999f, dy = 99999f)
        assertEquals(700f, s.x, 0f); assertEquals(1700f, s.y, 0f)
        apply(s, GestureKind.MOVE, dx = -99999f, dy = -99999f)
        assertEquals(0f, s.x, 0f); assertEquals(0f, s.y, 0f)
    }

    @Test fun resizeIsClampedAndKeepsCentre() {
        val s = newState()
        apply(s, GestureKind.RESIZE, scale = 0.01f)
        assertEquals(200f, s.width, 0.01f) // min width 200 / 300 => scale 0.667
        assertEquals(250f, s.x + s.width / 2f, 0.01f)
        apply(s, GestureKind.RESIZE, scale = 100f)
        assertTrue(s.width <= 1000f && s.height <= 2000f)
        assertTrue(s.x >= 0f && s.x + s.width <= 1000.01f)
    }

    @Test fun resizeScaleRejectsInvalidInput() {
        assertEquals(1f, ContainerMath.resizeScale(300f, 300f, Float.NaN, limits, 1000f, 2000f), 0f)
        assertEquals(1f, ContainerMath.resizeScale(300f, 300f, -1f, limits, 1000f, 2000f), 0f)
        assertEquals(1f, ContainerMath.resizeScale(0f, 300f, 2f, limits, 1000f, 2000f), 0f)
    }

    @Test fun wrapDegrees() {
        assertEquals(-170f, ContainerMath.wrapDegrees(190f), 1e-4f)
        assertEquals(170f, ContainerMath.wrapDegrees(-190f), 1e-4f)
        assertEquals(10f, ContainerMath.wrapDegrees(370f), 1e-4f)
    }

    @Test fun defaultBoundsAreOnScreenAndCascade() {
        val b0 = ContainerMath.defaultBounds(0, 1080f, 1920f, limits)
        val b1 = ContainerMath.defaultBounds(1, 1080f, 1920f, limits)
        for (b in listOf(b0, b1)) {
            assertTrue(b.x >= 0f && b.y >= 0f && b.x + b.width <= 1080f && b.y + b.height <= 1920f)
            assertTrue(b.width >= limits.minWidth && b.height >= limits.minHeight)
        }
        assertNotEquals(b0.x, b1.x)
    }

    @Test fun viewModelIdsAreUniqueAndInstancesIndependent() {
        val vm = ModelViewerViewModel()
        val a = vm.addModel(def, 1080f, 1920f, limits)
        val b = vm.addModel(def, 1080f, 1920f, limits) // same GLB twice
        val c = vm.addModel(ModelCatalog.all[1], 1080f, 1920f, limits)
        assertEquals(3, vm.instances.map { it.instanceId }.toSet().size)

        a.toggleInteraction(); a.toggleLabels()
        apply(a, GestureKind.ROTATE, dx = 40f)
        assertEquals(InteractionMode.NORMAL, b.interactionMode)
        assertFalse(b.labelsVisible)
        assertEquals(0f, b.yawDeg, 0f)
        assertEquals(0f, c.yawDeg, 0f)
    }

    @Test fun viewModelCloseAndBringToFront() {
        val vm = ModelViewerViewModel()
        val a = vm.addModel(def, 1080f, 1920f, limits)
        val b = vm.addModel(def, 1080f, 1920f, limits)
        assertTrue(b.zOrder > a.zOrder)
        vm.bringToFront(a)
        assertTrue(a.zOrder > b.zOrder)
        assertTrue(vm.closeModel(a.instanceId))
        assertFalse(vm.closeModel(a.instanceId))
        assertEquals(listOf(b), vm.instances.toList())
        assertTrue(vm.closeModel(b.instanceId))
        assertTrue(vm.instances.isEmpty()) // closing the last model is fine
    }
}
