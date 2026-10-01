package com.example.modelviewer.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.google.android.filament.Engine
import com.google.android.filament.IndirectLight
import io.github.sceneview.environment.Environment

// Tunable. A flat, procedural ambient term: no HDR/KTX file, no skybox, nearly free to render.
private const val AMBIENT_SH = 0.8f
private const val AMBIENT_INTENSITY = 30_000f

/** Cheap constant-colour image based light so PBR materials are not black without an HDR asset. */
@Composable
fun rememberAmbientEnvironment(engine: Engine): Environment {
    val environment = remember(engine) {
        val light = IndirectLight.Builder()
            .irradiance(1, floatArrayOf(AMBIENT_SH, AMBIENT_SH, AMBIENT_SH))
            .intensity(AMBIENT_INTENSITY)
            .build(engine)
        Environment(indirectLight = light)
    }
    DisposableEffect(environment) {
        onDispose {
            runCatching { environment.indirectLight?.let { engine.destroyIndirectLight(it) } }
        }
    }
    return environment
}
