package com.example.modelviewer.render

import kotlin.math.cos
import kotlin.math.sin

/** Allocation-free quaternion helpers. Quaternions are FloatArray(x, y, z, w). */
object OrientationMath {
    /** q = qYaw(around +Y) * qPitch(around +X): a turntable. Drag right => +yaw, drag down => +pitch. */
    fun yawPitch(yawDeg: Float, pitchDeg: Float, out: FloatArray) {
        val hy = Math.toRadians(yawDeg.toDouble() / 2.0)
        val hp = Math.toRadians(pitchDeg.toDouble() / 2.0)
        val sy = sin(hy).toFloat(); val cy = cos(hy).toFloat()
        val sx = sin(hp).toFloat(); val cx = cos(hp).toFloat()
        out[0] = cy * sx
        out[1] = sy * cx
        out[2] = -sy * sx
        out[3] = cy * cx
    }

    /** Rotates (vx,vy,vz) by unit quaternion q into out[0..2]. */
    fun rotate(q: FloatArray, vx: Float, vy: Float, vz: Float, out: FloatArray) {
        val qx = q[0]; val qy = q[1]; val qz = q[2]; val qw = q[3]
        // t = 2 * cross(q.xyz, v)
        val tx = 2f * (qy * vz - qz * vy)
        val ty = 2f * (qz * vx - qx * vz)
        val tz = 2f * (qx * vy - qy * vx)
        // v' = v + w * t + cross(q.xyz, t)
        out[0] = vx + qw * tx + (qy * tz - qz * ty)
        out[1] = vy + qw * ty + (qz * tx - qx * tz)
        out[2] = vz + qw * tz + (qx * ty - qy * tx)
    }
}
