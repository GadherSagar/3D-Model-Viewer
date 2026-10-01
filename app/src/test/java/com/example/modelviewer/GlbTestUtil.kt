package com.example.modelviewer

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Builds a minimal, valid GLB (header + JSON chunk only) around the given glTF JSON. */
fun buildGlb(json: String): ByteArray {
    val jsonBytes = json.toByteArray(Charsets.UTF_8)
    val padded = (jsonBytes.size + 3) / 4 * 4
    val chunk = ByteArray(padded) { if (it < jsonBytes.size) jsonBytes[it] else 0x20 }
    val bb = ByteBuffer.allocate(12 + 8 + padded).order(ByteOrder.LITTLE_ENDIAN)
    bb.putInt(0x46546C67).putInt(2).putInt(20 + padded)
    bb.putInt(padded).putInt(0x4E4F534A).put(chunk)
    return bb.array()
}
