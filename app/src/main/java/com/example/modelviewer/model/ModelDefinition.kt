package com.example.modelviewer.model

/** A bundled GLB that can be added to the scene (any number of times). */
data class ModelDefinition(
    val id: String,
    val displayName: String,
    /** Path relative to the app's assets directory. */
    val assetPath: String,
)

object ModelCatalog {
    const val ASSET_DIR = "models"

    /** The five assessment models: assets/models/model1.glb .. model5.glb */
    val all: List<ModelDefinition> = (1..5).map { i ->
        ModelDefinition(id = "model$i", displayName = "Model $i", assetPath = "$ASSET_DIR/model$i.glb")
    }

    fun byId(id: String): ModelDefinition? = all.firstOrNull { it.id == id }
}
