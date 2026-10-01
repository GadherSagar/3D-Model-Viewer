package com.example.modelviewer.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.modelviewer.render.SceneController
import kotlin.math.hypot
import kotlin.math.max

private val LineColor = Color(0xFFFFD54F)
private val BackgroundColor = Color(0xD9101010)

/**
 * One 2D overlay for every model. Label positions come from SceneController (recomputed every
 * frame from the current camera + model transform). Reading `overlayTick` inside the draw block
 * means a new frame only invalidates *drawing* - no recomposition, no layout.
 */
@Composable
fun LabelOverlay(controller: SceneController, modifier: Modifier = Modifier) {
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE } }
    val metrics = remember { Paint.FontMetrics() }

    Canvas(modifier) {
        @Suppress("UNUSED_VARIABLE") val tick = controller.overlayTick.longValue

        paint.textSize = 12.sp.toPx()
        paint.getFontMetrics(metrics)
        val pad = 6.dp.toPx()
        val gap = 44.dp.toPx()
        val lineHeight = metrics.descent - metrics.ascent
        val strokeWidth = 1.5.dp.toPx()
        val dotRadius = 3.dp.toPx()
        val corner = CornerRadius(4.dp.toPx())

        val models = controller.loaded
        for (i in 0 until models.size) {
            val lm = models[i]
            val st = lm.state
            if (!st.labelsVisible) continue
            val centerX = st.x + st.width / 2f
            val centerY = st.y + st.height / 2f

            for (j in lm.labels.indices) {
                if (!lm.labelValid[j]) continue
                val ax = lm.labelXy[j * 2]
                val ay = lm.labelXy[j * 2 + 1]
                val text = lm.labels[j].text

                // Push the label away from the container centre so it sits outside the part it names.
                var dx = ax - centerX
                var dy = ay - centerY
                val len = hypot(dx, dy)
                if (len < 1f) { dx = 0f; dy = -1f } else { dx /= len; dy /= len }

                val w = paint.measureText(text) + 2f * pad
                val h = lineHeight + 2f * pad
                val left = (ax + dx * gap - w / 2f).coerceIn(0f, max(0f, size.width - w))
                val top = (ay + dy * gap - h / 2f).coerceIn(0f, max(0f, size.height - h))

                // Connector: from the projected anchor to the nearest point on the label box.
                val ex = ax.coerceIn(left, left + w)
                val ey = ay.coerceIn(top, top + h)
                drawLine(LineColor, Offset(ax, ay), Offset(ex, ey), strokeWidth)
                drawCircle(LineColor, dotRadius, Offset(ax, ay))
                drawRoundRect(BackgroundColor, Offset(left, top), Size(w, h), corner)
                drawIntoCanvas { it.nativeCanvas.drawText(text, left + pad, top + pad - metrics.ascent, paint) }
            }
        }
    }
}
