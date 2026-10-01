package com.example.modelviewer.ui

import android.content.res.AssetManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.modelviewer.gesture.ContainerLimits
import com.example.modelviewer.gltf.GlbMetadata
import com.example.modelviewer.model.LoadState
import com.example.modelviewer.model.ModelCatalog
import com.example.modelviewer.model.ModelInstanceState
import com.example.modelviewer.model.ModelViewerViewModel
import com.example.modelviewer.render.SceneController
import com.example.modelviewer.render.describeLoadError
import com.example.modelviewer.render.readGlbMetadata
import io.github.sceneview.SceneScope
import io.github.sceneview.SceneView
import io.github.sceneview.math.Position
import io.github.sceneview.node.Node
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private const val LOAD_TIMEOUT_MS = 20_000L

/**
 * Layers, back to front:
 *   1. ONE full-screen SceneView (one Engine / Renderer / Scene / Camera for every model)
 *   2. one transparent ModelContainer per instance (border, controls, gestures)
 *   3. one label overlay canvas
 *   4. the Add button and picker
 *
 * Models are declared inside `SceneView { }`, so SceneView's composition creates AND destroys their
 * nodes and assets. Closing a model just removes its entry from the list.
 */
@Composable
fun ModelViewerScreen(viewModel: ModelViewerViewModel = viewModel()) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val limits = remember(density) {
        with(density) { ContainerLimits(minWidth = 220.dp.toPx(), minHeight = 170.dp.toPx()) }
    }

    // Shared rendering infrastructure - created once, destroyed when this screen leaves composition.
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val cameraNode = rememberCameraNode(engine) { position = Position(0f, 0f, 0f) }
    val controller = remember(engine, cameraNode) { SceneController(engine, cameraNode) }
    val assets = remember(context) { context.applicationContext.assets }

    // window = the SceneView surface (full-bleed, behind the system bars).
    // viewport/contentOrigin = the safe UI area inside it, where containers live.
    var window by remember { mutableStateOf(IntSize.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var contentOrigin by remember { mutableStateOf(IntOffset.Zero) }
    var showPicker by remember { mutableStateOf(false) }

    // Event-driven placement: whenever anything that affects a model's placement changes, re-place
    // all models. The block only reads state and mixes the raw bits into a Long (no allocations);
    // snapshotFlow emits when that Long changes.
    LaunchedEffect(controller) {
        snapshotFlow {
            var h = 17L
            val list = viewModel.instances
            for (i in 0 until list.size) {
                val s = list[i]
                h = h * 31 + s.x.toRawBits()
                h = h * 31 + s.y.toRawBits()
                h = h * 31 + s.width.toRawBits()
                h = h * 31 + s.height.toRawBits()
                h = h * 31 + s.yawDeg.toRawBits()
                h = h * 31 + s.pitchDeg.toRawBits()
                h = h * 31 + s.zoom.toRawBits()
                h = h * 31 + s.zOrder
                h = h * 31 + (if (s.labelsVisible) 1 else 0)
            }
            h * 31 + list.size
        }.collect { controller.updateAll() }
    }
    LaunchedEffect(window, viewport, contentOrigin) {
        controller.setGeometry(window.width, window.height, contentOrigin.x, contentOrigin.y)
        viewModel.clampAll(viewport.width.toFloat(), viewport.height.toFloat())
        controller.updateAll()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { window = it },
    ) {
        SceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            cameraNode = cameraNode,
            cameraManipulator = null,   // the camera is fixed; models move, the camera does not
            autoCenterContent = false,  // we position every model ourselves; do not re-centre them
            onGestureListener = null,
            onTouchEvent = null,
            onFrame = { controller.onFrame(it) },
        ) {
            for (instance in viewModel.instances) {
                key(instance.instanceId) {
                    ModelEntry(instance, controller, assets)
                }
            }
        }

        // Safe UI area: everything interactive lives here, clear of status bar / nav bar / cutout.
        Box(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .onGloballyPositioned { coords ->
                    viewport = coords.size
                    contentOrigin = IntOffset(coords.positionInRoot().x.toInt(), coords.positionInRoot().y.toInt())
                },
        ) {
            Box(Modifier.fillMaxSize()) {
                for (instance in viewModel.instances) {
                    key(instance.instanceId) {
                        ModelContainer(
                            state = instance,
                            viewport = viewport,
                            limits = limits,
                            onBringToFront = { viewModel.bringToFront(instance) },
                            onClose = { viewModel.closeModel(instance.instanceId) },
                        )
                    }
                }
            }

            LabelOverlay(controller, Modifier.fillMaxSize().zIndex(1000f))

            if (viewModel.instances.isEmpty()) {
                Text(
                    "Tap “Add model” to begin",
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            Button(
                onClick = { showPicker = true },
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).zIndex(2000f),
            ) { Text("Add model") }
        }
    }

    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("Add model") },
            text = {
                Column {
                    for (definition in ModelCatalog.all) {
                        TextButton(
                            onClick = {
                                showPicker = false
                                viewModel.addModel(
                                    definition,
                                    viewport.width.toFloat(),
                                    viewport.height.toFloat(),
                                    limits,
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(definition.displayName) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPicker = false }) { Text("Cancel") } },
        )
    }

    DisposableEffect(controller) {
        onDispose { controller.release() }
    }
}

private class PivotRef {
    var node: Node? = null
}

/**
 * One model in the shared scene: a pivot [Node] (we drive its position/rotation/scale) holding the
 * ModelNode (centred on its bounding box). Leaving the composition - i.e. closing the model - makes
 * SceneView destroy both nodes and release the model asset in the right order; we only drop our
 * references first.
 */
@Composable
private fun SceneScope.ModelEntry(
    state: ModelInstanceState,
    controller: SceneController,
    assets: AssetManager,
) {
    val id = state.instanceId
    DisposableEffect(id) { onDispose { controller.detach(id) } }

    // 1. Pre-flight on IO: header + JSON chunk only. Missing/malformed files fail here, per model.
    val metadata: GlbMetadata? by produceState<GlbMetadata?>(null, state.definition.id) {
        value = try {
            readGlbMetadata(assets, state.definition)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            state.loadState = LoadState.Error(describeLoadError(e, state.definition))
            null
        }
    }
    val meta = metadata ?: return

    // 2. Async model load handled by SceneView (IO read, main-thread Filament creation).
    val instance = rememberModelInstance(modelLoader, state.definition.assetPath)

    LaunchedEffect(instance == null) {
        if (instance == null) {
            delay(LOAD_TIMEOUT_MS)
            if (state.loadState is LoadState.Loading) {
                state.loadState = LoadState.Error("Timed out loading ${state.definition.displayName}")
            }
        }
    }
    val modelInstance = instance ?: return

    // 3. Declare the nodes. `apply` runs once when each node is created.
    val pivotRef = remember(modelInstance) { PivotRef() }
    // Centre the model on its bounding box by offsetting the ModelNode inside the pivot. This is a real
    // `position` argument (not centerOrigin) so SceneView re-applying parameters can never reset it.
    val centreOffset = remember(modelInstance) {
        val c = modelInstance.asset.boundingBox.center
        Position(-c[0], -c[1], -c[2])
    }
    Node(apply = { pivotRef.node = this }) {
        ModelNode(
            modelInstance = modelInstance,
            autoAnimate = true,
            position = centreOffset,
            apply = {
                pivotRef.node?.let { pivot -> controller.attach(state, pivot, this, meta) }
            },
        )
    }

    SideEffect {
        // Runs after the Node composables above, so it also restores our transform if SceneView
        // re-applied the (default) Node parameters on a recomposition.
        if (state.loadState !is LoadState.Error) state.loadState = LoadState.Ready
        controller.updateAll()
    }
}
