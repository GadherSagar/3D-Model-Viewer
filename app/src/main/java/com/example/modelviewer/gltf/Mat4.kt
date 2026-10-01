package com.example.modelviewer.gltf

/** Minimal column-major 4x4 helpers (same layout as glTF and Filament). */
object Mat4 {
    fun identity(): FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

    /** Returns a * b. */
    fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(16)
        for (c in 0 until 4) for (r in 0 until 4) {
            var sum = 0f
            for (k in 0 until 4) sum += a[k * 4 + r] * b[c * 4 + k]
            out[c * 4 + r] = sum
        }
        return out
    }

    /** Builds T * R * S from translation, unit quaternion (x,y,z,w) and scale. */
    fun fromTrs(t: FloatArray, q: FloatArray, s: FloatArray): FloatArray {
        val x = q[0]; val y = q[1]; val z = q[2]; val w = q[3]
        val xx = x * x; val yy = y * y; val zz = z * z
        val xy = x * y; val xz = x * z; val yz = y * z
        val wx = w * x; val wy = w * y; val wz = w * z
        return floatArrayOf(
            (1f - 2f * (yy + zz)) * s[0], (2f * (xy + wz)) * s[0], (2f * (xz - wy)) * s[0], 0f,
            (2f * (xy - wz)) * s[1], (1f - 2f * (xx + zz)) * s[1], (2f * (yz + wx)) * s[1], 0f,
            (2f * (xz + wy)) * s[2], (2f * (yz - wx)) * s[2], (1f - 2f * (xx + yy)) * s[2], 0f,
            t[0], t[1], t[2], 1f,
        )
    }
}
