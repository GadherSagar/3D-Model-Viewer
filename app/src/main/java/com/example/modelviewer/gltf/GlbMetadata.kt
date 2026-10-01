package com.example.modelviewer.gltf

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

class GlbFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * One labelled node: the exact `extras.prop` text, the glTF node identity (name/index) used to find
 * the live Filament entity, and the node's static model-space position (fallback anchor).
 */
class LabelSpec(
    val text: String,
    val nodeIndex: Int,
    val nodeName: String?,
    val anchorX: Float,
    val anchorY: Float,
    val anchorZ: Float,
)

class GlbMetadata(val labels: List<LabelSpec>) {
    companion object {
        val EMPTY = GlbMetadata(emptyList())
    }
}

/** Reads only the JSON chunk of a GLB; never touches meshes/textures. */
object GlbMetadataParser {
    private const val GLB_MAGIC = 0x46546C67 // "glTF"
    private const val CHUNK_JSON = 0x4E4F534A // "JSON"
    private const val FIRST_CHUNK_OFFSET = 12

    fun parse(glb: ByteArray): GlbMetadata = parseJson(extractJson(glb))

    fun extractJson(glb: ByteArray): String {
        if (glb.size < FIRST_CHUNK_OFFSET + 8) throw GlbFormatException("File too small to be a GLB")
        val bb = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN)
        if (bb.getInt(0) != GLB_MAGIC) throw GlbFormatException("Missing glTF magic header")
        val chunkLength = bb.getInt(FIRST_CHUNK_OFFSET)
        val chunkType = bb.getInt(FIRST_CHUNK_OFFSET + 4)
        if (chunkType != CHUNK_JSON) throw GlbFormatException("First GLB chunk is not JSON")
        val start = FIRST_CHUNK_OFFSET + 8
        if (chunkLength < 0 || start.toLong() + chunkLength > glb.size) throw GlbFormatException("Truncated JSON chunk")
        return String(glb, start, chunkLength, Charsets.UTF_8)
    }

    fun parseJson(json: String): GlbMetadata {
        val element = try {
            Json.parseToJsonElement(json)
        } catch (e: Exception) {
            throw GlbFormatException("glTF JSON is malformed", e)
        }
        val root = element as? JsonObject ?: throw GlbFormatException("glTF JSON root is not an object")

        val nodes = root["nodes"] as? JsonArray ?: return GlbMetadata.EMPTY
        if (nodes.isEmpty()) return GlbMetadata.EMPTY

        val world = computeWorldMatrices(root, nodes)
        val labels = ArrayList<LabelSpec>()
        for (i in nodes.indices) {
            val node = nodes[i] as? JsonObject ?: continue
            val text = extractProp(node) ?: continue
            val m = world[i]
            val name = (node["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            labels += LabelSpec(text, i, name, m[12], m[13], m[14])
        }
        return GlbMetadata(labels)
    }

    /** Returns `extras.prop` exactly as written, or null if absent / not a value / blank. */
    internal fun extractProp(node: JsonObject): String? {
        val extras = node["extras"] as? JsonObject ?: return null
        val prop = extras["prop"] as? JsonPrimitive ?: return null
        if (prop is JsonNull) return null
        val text = prop.content
        return if (text.isBlank()) null else text
    }

    private fun computeWorldMatrices(root: JsonObject, nodes: JsonArray): Array<FloatArray> {
        val n = nodes.size
        val local = Array(n) { localMatrix(nodes[it] as? JsonObject) }
        val children = Array(n) { childIndices(nodes[it] as? JsonObject, n) }
        val isChild = BooleanArray(n)
        for (list in children) for (c in list) isChild[c] = true
        val roots = sceneRoots(root, n) ?: (0 until n).filter { !isChild[it] }

        val world = arrayOfNulls<FloatArray>(n)
        val stack = ArrayDeque<Int>()
        for (r in roots) if (world[r] == null) {
            world[r] = local[r]
            stack.addLast(r)
        }
        while (stack.isNotEmpty()) {
            val parent = stack.removeLast()
            val parentWorld = world[parent] ?: continue
            for (c in children[parent]) if (world[c] == null) { // null check also breaks cycles
                world[c] = Mat4.multiply(parentWorld, local[c])
                stack.addLast(c)
            }
        }
        return Array(n) { world[it] ?: local[it] }
    }

    private fun sceneRoots(root: JsonObject, n: Int): List<Int>? {
        val scenes = root["scenes"] as? JsonArray ?: return null
        val index = (root["scene"] as? JsonPrimitive)?.intOrNull ?: 0
        val scene = scenes.getOrNull(index) as? JsonObject ?: return null
        val list = (scene["nodes"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.intOrNull?.takeIf { i -> i in 0 until n } }
        return list?.takeIf { it.isNotEmpty() }
    }

    private fun childIndices(node: JsonObject?, n: Int): IntArray {
        val arr = node?.get("children") as? JsonArray ?: return IntArray(0)
        return arr.mapNotNull { (it as? JsonPrimitive)?.intOrNull?.takeIf { i -> i in 0 until n } }.toIntArray()
    }

    private fun localMatrix(node: JsonObject?): FloatArray {
        if (node == null) return Mat4.identity()
        node["matrix"].floats(16)?.let { return it }
        val t = node["translation"].floats(3) ?: floatArrayOf(0f, 0f, 0f)
        val r = node["rotation"].floats(4) ?: floatArrayOf(0f, 0f, 0f, 1f)
        val s = node["scale"].floats(3) ?: floatArrayOf(1f, 1f, 1f)
        return Mat4.fromTrs(t, r, s)
    }

    private fun JsonElement?.floats(size: Int): FloatArray? {
        val arr = this as? JsonArray ?: return null
        if (arr.size != size) return null
        val out = FloatArray(size)
        for (i in 0 until size) out[i] = (arr[i] as? JsonPrimitive)?.floatOrNull ?: return null
        return out
    }
}

/** Parsed once per model definition; every instance of the same GLB reuses the result. */
object GlbMetadataCache {
    private val cache = ConcurrentHashMap<String, GlbMetadata>()

    fun getOrParse(key: String, glb: ByteArray): GlbMetadata {
        cache[key]?.let { return it }
        val parsed = GlbMetadataParser.parse(glb)
        return cache.putIfAbsent(key, parsed) ?: parsed
    }

    fun clear() = cache.clear()
}
