package com.example.modelviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.modelviewer.gesture.ContainerLimits
import com.example.modelviewer.gesture.GestureSession
import com.example.modelviewer.model.InteractionMode
import com.example.modelviewer.model.LoadState
import com.example.modelviewer.model.ModelInstanceState
import kotlin.math.roundToInt

private val NormalBorder = Color(0xFF4FC3F7)
private val InteractionBorder = Color(0xFFFFA726)
private val ButtonBackground = Color(0xCC202020)

/**
 * The 2D frame for one model. The 3D pixels are drawn by the shared scene *behind* this composable;
 * this box only provides the border, controls, hit-testing and gestures.
 *
 * Position and size are read inside layout lambdas, so dragging/resizing re-runs layout for this
 * container only - it never recomposes anything.
 */
@Composable
fun ModelContainer(
    state: ModelInstanceState,
    viewport: IntSize,
    limits: ContainerLimits,
    onBringToFront: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentViewport by rememberUpdatedState(viewport)
    val currentLimits by rememberUpdatedState(limits)
    val currentBringToFront by rememberUpdatedState(onBringToFront)
    val session = remember(state.instanceId) { GestureSession() }

    val mode = state.interactionMode // recomposes only this container when the mode flips
    val borderColor = if (mode == InteractionMode.INTERACTION) InteractionBorder else NormalBorder

    Box(
        modifier
            .offset { IntOffset(state.x.roundToInt(), state.y.roundToInt()) }
            .layout { measurable, _ ->
                val w = state.width.roundToInt().coerceAtLeast(1)
                val h = state.height.roundToInt().coerceAtLeast(1)
                val placeable = measurable.measure(Constraints.fixed(w, h))
                layout(w, h) { placeable.place(0, 0) }
            }
            .zIndex(state.zOrder.toFloat())
            .border(2.dp, borderColor)
            // Keyed on the mode: switching modes cancels the running gesture block, so no pointer
            // tracking or partial gesture can leak from one mode into the other.
            .pointerInput(state.instanceId, mode) {
                session.reset()
                awaitEachGesture {
                    // requireUnconsumed = true: a tap on a control button is consumed by the button.
                    awaitFirstDown()
                    session.reset()
                    currentBringToFront()
                    var pressedCount: Int
                    do {
                        val event = awaitPointerEvent()
                        pressedCount = 0
                        var first: PointerInputChange? = null
                        for (i in event.changes.indices) {
                            val c = event.changes[i]
                            if (c.pressed) {
                                pressedCount++
                                if (first == null) first = c
                            }
                        }
                        val pan = first?.positionChange() ?: Offset.Zero
                        val zoom = if (pressedCount >= 2) event.calculateZoom() else 1f
                        val kind = session.update(mode, pressedCount, pan.x, pan.y, zoom)
                        if (kind != null) {
                            state.applyGesture(
                                kind, session.dx, session.dy, session.scale,
                                currentViewport.width.toFloat(), currentViewport.height.toFloat(), currentLimits,
                            )
                        }
                        // Consume so nothing underneath (e.g. the 3D view) reacts to this gesture.
                        for (i in event.changes.indices) {
                            if (event.changes[i].positionChanged()) event.changes[i].consume()
                        }
                    } while (pressedCount > 0)
                }
            },
    ) {
        Text(
            text = state.definition.displayName,
            color = Color.White,
            fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(4.dp)
                .background(ButtonBackground, RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )

        // Always-visible controls.
        Row(Modifier.align(Alignment.TopEnd).padding(2.dp)) {
            ControlButton("Interact", active = mode == InteractionMode.INTERACTION, onClick = state::toggleInteraction)
            ControlButton("Labels", active = state.labelsVisible, onClick = state::toggleLabels)
            ControlButton("✕", active = false, onClick = onClose)
        }

        when (val load = state.loadState) {
            LoadState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            is LoadState.Error -> Text(
                text = load.message,
                color = Color(0xFFFF8A80),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(16.dp),
            )
            LoadState.Ready -> Unit
        }
    }
}

@Composable
private fun ControlButton(text: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) InteractionBorder else ButtonBackground)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Text(text, color = if (active) Color.Black else Color.White, fontSize = 12.sp)
    }
}
