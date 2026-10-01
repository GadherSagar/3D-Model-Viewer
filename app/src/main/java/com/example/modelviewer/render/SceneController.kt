package com.example.modelviewer.render

import android.content.res.AssetManager
import android.util.Log
import androidx.compose.runtime.mutableLongStateOf
import com.example.modelviewer.gltf.GlbFormatException
import com.example.modelviewer.gltf.GlbMetadata
import com.example.modelviewer.gltf.GlbMetadataCache
import com.example.modelviewer.model.ModelDefinition
import com.example.modelviewer.model.ModelInstanceState
import com.google.android.filament.Engine
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion
import io.github.sceneview.node.CameraNode
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.EOFException
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

private const val TAG = "SceneController"

private const val DEFAULT_NEAR = 0.05f
private const val BASE_DEPTH = 4f
/** Gap between stacked models; larger than a model's depth extent so overlapping models layer cleanly. */
private const val DEPTH_STEP = 3f
/** Fraction of the container's half-side that the model's bounding sphere fills at zoom = 1. */
private const val FIT = 0.9f
private const val GLB_PREFIX_BYTES = 20
private const val MAX_JSON_CHUNK_BYTES = 64 * 1024 * 1024

/** A label resolved against one loaded model. [tmInstance] == 0 means "use the static fallback anchor". */
class RuntimeLabel(
    val text: String,
    val tmInstance: Int,
    val fx: Float,
    val fy: Float,
    val fz: Float,
)

/** Per-model scene data. The nodes themselves are owned (created/destroyed) by SceneView's composition. */
class LoadedModel(
    val state: ModelInstanceState,
    val pivot: Node,
    val modelNode: ModelNode,
    val radius: Float,
    val cx: Float,
    val cy: Float,
    val cz: Float,
    val labels: Array<RuntimeLabel>,
) {
    /** Projected label anchors in pixels: (x0,y0,x1,y1,...). Read by the overlay's draw phase. */
    val labelXy = FloatArray(labels.size * 2)
    val labelValid = BooleanArray(labels.size)

    // Scratch values reused every update - nothing here is reallocated per frame.
    val q = FloatArray(4)
    val position = Float3()
    val scaleVec = Float3(1f, 1f, 1f)
    val quaternion = Quaternion()
    var appliedScale = 1f
    var px = 0f
    var py = 0f
    var pz = 0f
}

/**
 * Bridges Compose state (containers) to the shared SceneView scene.
 *
 * Ownership: node creation and destruction belong to SceneView's declarative composition
 * (`SceneView { ModelNode(...) }`), which is what makes closing a model safe. This class only keeps
 * a registry of attached pivots, writes their transforms, and projects label anchors.
 *
 * Updates are event-driven, not per-frame polling: [updateAll] runs when container/model state
 * changes (see ModelViewerScreen) and every node write invalidates SceneView's on-demand loop by itself.
 * [onFrame] (called after a frame is presented) only refreshes label positions, so labels also follow
 * glTF animations.
 */
class SceneController(
    private val engine: Engine,
    private val cameraNode: CameraNode,
) {
    private val transformManager = engine.transformManager

    /** Bumped when label positions change; only the overlay's draw phase reads it. */
    val overlayTick = mutableLongStateOf(0L)

    /** Attached models. Main-thread only. */
    val loaded = ArrayList<LoadedModel>()

    private var near = DEFAULT_NEAR
    private var labelsWereVisible = false

    // Window (= SceneView surface) size and the origin of the inset UI area inside it, in pixels.
    // Container coordinates and label output are relative to that UI area.
    private var windowWidth = 0f
    private var windowHeight = 0f
    private var originX = 0f
    private var originY = 0f

    // Live camera, re-read from Filament: projection scale and camera-to-world axes.
    private val projection = DoubleArray(16)
    private val camModel = FloatArray(16)
    private var p0 = 0.0
    private var p5 = 0.0
    private var cameraSignature = Double.NaN

    private val scratchMatrix = FloatArray(16)
    private val scratchVec = FloatArray(3)
    private val scratchWorld = FloatArray(3)

    /** [width]x[height] = SceneView surface; ([originX],[originY]) = top-left of the safe UI area in it. */
    fun setGeometry(width: Int, height: Int, originX: Int, originY: Int) {
        windowWidth = width.toFloat()
        windowHeight = height.toFloat()
        this.originX = originX.toFloat()
        this.originY = originY.toFloat()
    }

    // ---------------------------------------------------------------- attach / detach

    /** Called once the pivot and its ModelNode exist. Replaces any earlier entry for the same instance. */
    fun attach(state: ModelInstanceState, pivot: Node, modelNode: ModelNode, metadata: GlbMetadata) {
        detach(state.instanceId)

        val box = modelNode.modelInstance.asset.boundingBox
        val c = box.center
        val h = box.halfExtent
        var radius = sqrt(h[0] * h[0] + h[1] * h[1] + h[2] * h[2])
        if (!(radius > 1e-4f)) radius = 1f // also catches NaN

        val asset = modelNode.modelInstance.asset
        val labels = ArrayList<RuntimeLabel>(metadata.labels.size)
        for (spec in metadata.labels) {
            var tm = 0
            val name = spec.nodeName
            if (name != null) {
                val entity = asset.getFirstEntityByName(name)
                if (entity != 0 && transformManager.hasComponent(entity)) tm = transformManager.getInstance(entity)
            }
            if (tm == 0) Log.w(TAG, "No live entity for node '${spec.nodeName}' (#${spec.nodeIndex}); using static anchor")
            labels.add(RuntimeLabel(spec.text, tm, spec.anchorX, spec.anchorY, spec.anchorZ))
        }

        loaded.add(LoadedModel(state, pivot, modelNode, radius, c[0], c[1], c[2], labels.toTypedArray()))
        updateAll()
    }

    /**
     * Forgets a model. Call this from the composable's onDispose: it only drops our references, so
     * no label/transform update can touch a node that SceneView is about to destroy.
     */
    fun detach(instanceId: String) {
        val index = loaded.indexOfFirst { it.state.instanceId == instanceId }
        if (index >= 0) {
            loaded.removeAt(index)
            updateAll()
        }
    }

    /** Screen teardown: drop references only; SceneView destroys the nodes and the Engine itself. */
    fun release() {
        loaded.clear()
    }

    // ---------------------------------------------------------------- camera maths (live camera)

    /**
     * Reads the camera that is actually rendering: projection scale (P[0], P[5]) and pose (model
     * matrix). Returns false until SceneView has configured a valid perspective projection.
     * Sets [cameraChanged] when anything differs from the previous read.
     */
    private var cameraChanged = false

    private fun readCamera(): Boolean {
        val camera = cameraNode.camera
        camera.getProjectionMatrix(projection)
        camera.getModelMatrix(camModel)
        p0 = projection[0]
        p5 = projection[5]
        if (!(p0 > 1e-6 && p5 > 1e-6)) return false
        near = camera.near.toFloat().takeIf { it > 0f } ?: DEFAULT_NEAR
        var sig = p0 * 1000.0 + p5
        for (i in 0 until 16) sig += camModel[i] * (i + 1)
        cameraChanged = sig != cameraSignature
        cameraSignature = sig
        return true
    }

    /** World units per pixel on the plane [depth] in front of the camera. */
    private fun unitsPerPixel(depth: Float): Float = (2.0 * depth / p5).toFloat() / windowHeight

    /** Window pixel -> world position on the plane [depth] in front of the camera. */
    private fun unproject(px: Float, py: Float, depth: Float, out: FloatArray) {
        val ndcX = px / windowWidth * 2f - 1f
        val ndcY = 1f - py / windowHeight * 2f
        val vx = (ndcX * depth / p0).toFloat()
        val vy = (ndcY * depth / p5).toFloat()
        val m = camModel
        // camera axes in world space: right = col0, up = col1, back = col2; looks along -back
        out[0] = m[12] + m[0] * vx + m[4] * vy - m[8] * depth
        out[1] = m[13] + m[1] * vx + m[5] * vy - m[9] * depth
        out[2] = m[14] + m[2] * vx + m[6] * vy - m[10] * depth
    }

    /**
     * World position -> pixels relative to the safe UI area, written to out[offset], out[offset+1].
     * False if behind/too close to the camera or not finite.
     */
    private fun project(wx: Float, wy: Float, wz: Float, out: FloatArray, offset: Int): Boolean {
        val m = camModel
        val dx = wx - m[12]
        val dy = wy - m[13]
        val dz = wz - m[14]
        val vx = m[0] * dx + m[1] * dy + m[2] * dz
        val vy = m[4] * dx + m[5] * dy + m[6] * dz
        val depth = -(m[8] * dx + m[9] * dy + m[10] * dz)
        if (!(depth > near) || !vx.isFinite() || !vy.isFinite()) return false
        val ndcX = (p0 * vx / depth).toFloat()
        val ndcY = (p5 * vy / depth).toFloat()
        out[offset] = (ndcX * 0.5f + 0.5f) * windowWidth - originX
        out[offset + 1] = (0.5f - ndcY * 0.5f) * windowHeight - originY
        return true
    }

    // ---------------------------------------------------------------- updates

    /** Re-places every model on its container and re-projects labels. Cheap; runs on state changes. */
    fun updateAll() {
        if (windowWidth <= 0f || windowHeight <= 0f) return
        if (!readCamera()) return
        val count = loaded.size
        for (i in 0 until count) {
            val lm = loaded[i]
            var rank = 0
            for (k in 0 until count) if (loaded[k].state.zOrder < lm.state.zOrder) rank++
            updateTransform(lm, rank, count)
            if (lm.state.labelsVisible && lm.labels.isNotEmpty()) updateLabels(lm)
        }
        overlayTick.longValue = overlayTick.longValue + 1
    }

    /** Called by SceneView after each presented frame. Never writes node transforms. */
    fun onFrame(@Suppress("UNUSED_PARAMETER") frameTimeNanos: Long) {
        if (windowWidth <= 0f || windowHeight <= 0f) return
        if (!readCamera()) return
        if (cameraChanged) {
            updateAll()
            return
        }
        var any = false
        for (i in 0 until loaded.size) {
            val lm = loaded[i]
            if (lm.state.labelsVisible && lm.labels.isNotEmpty()) {
                any = true
                updateLabels(lm)
            }
        }
        if (any || labelsWereVisible) overlayTick.longValue = overlayTick.longValue + 1
        labelsWereVisible = any
    }

    /**
     * Places the pivot at the container's centre on this model's depth plane and scales it so the
     * model's bounding sphere fills the container. The ModelNode child is offset by -boundingBoxCentre
     * (see ModelEntry), so rotating the pivot spins the model about its own centre.
     */
    private fun updateTransform(lm: LoadedModel, rank: Int, count: Int) {
        val st = lm.state
        // Front-most container (highest zOrder) gets the smallest depth.
        val depth = BASE_DEPTH + (count - 1 - rank) * DEPTH_STEP
        val halfSidePx = 0.5f * min(st.width, st.height)
        val scale = halfSidePx * unitsPerPixel(depth) * FIT * st.zoom / lm.radius

        OrientationMath.yawPitch(st.yawDeg, st.pitchDeg, lm.q)
        unproject(originX + st.x + st.width / 2f, originY + st.y + st.height / 2f, depth, scratchWorld)

        lm.px = scratchWorld[0]; lm.py = scratchWorld[1]; lm.pz = scratchWorld[2]
        lm.appliedScale = scale

        val quat = lm.quaternion
        quat.x = lm.q[0]; quat.y = lm.q[1]; quat.z = lm.q[2]; quat.w = lm.q[3]
        lm.scaleVec.x = scale; lm.scaleVec.y = scale; lm.scaleVec.z = scale
        lm.position.x = lm.px; lm.position.y = lm.py; lm.position.z = lm.pz

        val pivot = lm.pivot
        pivot.quaternion = quat
        pivot.scale = lm.scaleVec
        pivot.position = lm.position // node setters ask SceneView for a frame by themselves
    }

    /**
     * node local position -> model transform -> world -> current camera -> screen pixels.
     * Preferred: the live world transform of the glTF node's Filament entity (follows animation and
     * every ancestor transform, including the pivot we just wrote). Fallback: the static model-space
     * anchor pushed through the same pivot transform.
     */
    private fun updateLabels(lm: LoadedModel) {
        val labels = lm.labels
        for (j in labels.indices) {
            val l = labels[j]
            val wx: Float
            val wy: Float
            val wz: Float
            if (l.tmInstance != 0) {
                transformManager.getWorldTransform(l.tmInstance, scratchMatrix)
                wx = scratchMatrix[12]; wy = scratchMatrix[13]; wz = scratchMatrix[14]
            } else {
                val s = lm.appliedScale
                OrientationMath.rotate(lm.q, (l.fx - lm.cx) * s, (l.fy - lm.cy) * s, (l.fz - lm.cz) * s, scratchVec)
                wx = lm.px + scratchVec[0]; wy = lm.py + scratchVec[1]; wz = lm.pz + scratchVec[2]
            }
            lm.labelValid[j] = project(wx, wy, wz, lm.labelXy, j * 2)
        }
    }
}

// ---------------------------------------------------------------- GLB pre-flight

/**
 * Reads ONLY the GLB header + JSON chunk (not meshes/textures) and parses label metadata once per
 * definition (cached). Runs on IO. Throws FileNotFoundException / GlbFormatException for bad input,
 * which lets us show a per-model error before SceneView ever tries to load the file.
 */
suspend fun readGlbMetadata(assets: AssetManager, definition: ModelDefinition): GlbMetadata =
    withContext(Dispatchers.IO) {
        assets.open(definition.assetPath).use { stream ->
            val input = DataInputStream(stream)
            val head = ByteArray(GLB_PREFIX_BYTES)
            try {
                input.readFully(head)
            } catch (e: EOFException) {
                throw GlbFormatException("File too small to be a GLB", e)
            }
            val chunkLength = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getInt(12)
            if (chunkLength < 0 || chunkLength > MAX_JSON_CHUNK_BYTES) throw GlbFormatException("Invalid JSON chunk length")
            val prefix = ByteArray(GLB_PREFIX_BYTES + chunkLength)
            System.arraycopy(head, 0, prefix, 0, GLB_PREFIX_BYTES)
            try {
                input.readFully(prefix, GLB_PREFIX_BYTES, chunkLength)
            } catch (e: EOFException) {
                throw GlbFormatException("Truncated JSON chunk", e)
            }
            GlbMetadataCache.getOrParse(definition.id, prefix)
        }
    }

fun describeLoadError(e: Throwable, definition: ModelDefinition): String = when (e) {
    is FileNotFoundException -> "File not found: ${definition.assetPath}"
    is GlbFormatException -> "Invalid GLB: ${e.message}"
    is OutOfMemoryError -> "Out of memory loading ${definition.displayName}"
    else -> e.message ?: e.javaClass.simpleName
}
