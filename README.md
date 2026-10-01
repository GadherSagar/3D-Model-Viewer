# 3D Model Viewer

## Architecture
```
MainActivity
 └─ ModelViewerScreen               (Compose; owns the shared Engine/Scene/Camera/ModelLoader)
     ├─ Scene (SceneView, ONE)      full-screen, fixed camera, no camera manipulator
     ├─ ModelContainer × N          transparent frame: border, controls, gestures (2D)
     ├─ LabelOverlay (ONE)          Canvas drawing all labels + connector lines
     └─ Add button / picker dialog
SceneController                     owns one ModelNode per open model; sync/load/dispose; per-frame update
ModelViewerViewModel                list of ModelInstanceState only (no Filament objects)
```
| Package | Contents |
|---|---|
| `model` | `ModelDefinition`/`ModelCatalog`, `ModelInstanceState`, `ModelViewerViewModel` |
| `gesture` | `GestureKind`, `GestureInterpreter`, `GestureSession`, `ContainerMath` (all pure Kotlin) |
| `gltf` | `GlbMetadataParser` (JSON chunk → labels), `GlbMetadataCache`, `Mat4` |
| `render` | `SceneController`, `CameraRig`, `OrientationMath`, `AmbientEnvironment` |
| `ui` | `ModelViewerScreen`, `ModelContainer`, `LabelOverlay` |

### Rendering architecture
There is exactly one SceneView surface, one Filament Engine/Renderer/Scene and one Camera. Each container is a 2D rectangle; the model's `ModelNode` is placed in the shared scene so its bounding sphere is centred on, and sized to, that rectangle:

`container pixels → world position on a depth plane in front of the pinhole camera` and `container size → uniform node scale`. Models are stacked on separate depth planes ordered by container z-order, so overlapping models layer correctly. Resizing a container only changes the node's scale; models are never re-created.

The camera is fixed at the origin looking down −Z. `CameraRig` holds the FOV, and `SceneController` pushes the same projection into the Filament camera every frame, so the layout/projection math and the rendered image agree.

## Gesture handling
| | NORMAL | INTERACTION |
|---|---|---|
| 1 finger | move container | rotate model (yaw/pitch, pitch clamped ±85°) |
| 2 fingers | resize container (min 220×170 dp, max = screen) | zoom model (0.4×–3×) |

* Mode is per-model state (`ModelInstanceState.interactionMode`).
* `ModelContainer`'s `pointerInput` is keyed on the mode, so toggling the mode cancels the running gesture block and resets the `GestureSession` — no pointer tracking survives a switch.
* `GestureInterpreter.kindFor(mode, fingers)` is the only place the matrix lives; `ModelInstanceState.applyGesture` additionally ignores any gesture kind that doesn't belong to the current mode.
* After a second finger has been seen in a gesture, the remaining finger is ignored until all fingers lift (no accidental drag after a pinch).
* Events are consumed by the container, so they never reach the 3D view; each container has its own pointer handler, so a gesture on A cannot touch B. Control buttons consume their own touches.
* Position/size are read inside `offset {}` / `layout {}` lambdas → dragging only re-runs layout for that container, no recomposition.

## Model loading
`SceneController.sync()` reconciles the ViewModel list with the scene. For each new instance: (IO thread) read asset bytes, parse label metadata once per definition (`GlbMetadataCache`), stage a direct `ByteBuffer`; (main thread) `modelLoader.createModel`, create the `ModelNode`, disable shadow casting/receiving on its renderables, resolve labels, register. Failures (missing file, malformed GLB, Filament failure, OOM) set that instance to an error state, destroy anything half-created, and never affect other models. Closing while loading cancels the coroutine before any Filament object exists.

## Label system
1. **Parse once:** `GlbMetadataParser` reads only the GLB's JSON chunk. Every node with a non-blank string `extras.prop` becomes a `LabelSpec(text, nodeIndex, nodeName, static model-space anchor)`. Text is used exactly as written (never the node name). Nodes without `extras`/`prop`, with `null`/blank/non-object extras are skipped. Cached per definition id.
2. **Bind per instance:** at load time each spec is resolved to the live Filament transform of the node's entity (by node name). If a node can't be resolved (e.g. unnamed), the static anchor is used instead.
3. **Project every frame** (only while that model's labels are ON): node world transform (live, includes the model's position/rotation/scale and animation) → `CameraRig.project` (same camera parameters as the renderer) → pixel coordinates written into a preallocated `FloatArray`.
4. **Draw:** `LabelOverlay` draws label box + connector line + anchor dot in a Canvas. It reads a tick state inside the draw block, so a new frame invalidates drawing only — no recomposition. The label sits offset away from the container centre, clamped to the screen.
Labels are OFF by default and toggled per model.

## Performance optimizations (implemented, not measured)
* One Engine/Renderer/Scene/Camera for all models; per-model cost is only its own entities.
* Label metadata parsed once per GLB definition; no JSON work per frame.
* Per-frame path is allocation-free (scratch arrays, reused `Float3`/`Quaternion`, no lambdas/boxing in loops); node transforms are only written when they changed.
* Shadows disabled per renderable; no post-effects added; flat procedural ambient light instead of an HDR/KTX environment; default single directional light.
* Gestures write to per-value snapshot states read only in layout/draw/frame code.
* Asset files are stored uncompressed (`noCompress "glb"`).
* Only the projection work for models whose labels are visible is done each frame.

## Resource management
* Close: cancel load job → remove entities from the Filament scene → remove/destroy the `ModelNode` → destroy the model asset → drop state (gesture state is a `remember`ed object in the removed composable).
* The Engine, Scene, Camera, loader and environment are created once by `rememberEngine`, `rememberScene`, … and destroyed with the screen. `SceneController.release()` runs first (effect declared after `Scene`) so models are destroyed while the Engine is alive.
* The Activity declares `configChanges` so rotation doesn't recreate it (which would rebuild the Engine). If the system does recreate it, the ViewModel's list survives and `sync()` reloads every model into the new Engine.
* Only the application context is held by `SceneController`; no Activity/View references.
* All Filament calls run on the main thread.

## Testing
```
./gradlew test          # after generating the wrapper, or use Android Studio's test runner
```
Unit tests (JVM, no device): GLB/`extras.prop` parsing (missing/blank/null extras, hierarchy anchors, malformed input, caching), gesture matrix and session logic, container geometry and clamping, mode isolation, ID uniqueness/independence, close/z-order, camera projection round-trips and orientation math. **They have not been executed.**

## Trade-offs
* One full-screen view with 3D drawn behind Compose keeps a single renderer, but 3D pixels can't be clipped to a container: a model zoomed past the container edge (or a long model that pokes past the bounding-sphere fit) draws outside its frame.
* Fit uses the bounding sphere so rotation never changes apparent size; the model can look slightly small in its frame.
* Duplicate instances of the same GLB each load their own asset copy (simple and safe) rather than sharing geometry via Filament instancing.
* No dynamic resolution or MSAA tuning — not enabled blindly without measurements.

## Known limitations
* Ambient light strength (`AMBIENT_SH`, `AMBIENT_INTENSITY`) is a guess; tune if models look too dark/bright.
* Labels are not occlusion-tested (a part hidden behind the model still shows its label) and label boxes are not de-overlapped.
* Live node lookup is by node *name*; duplicate names resolve to the first match, unnamed nodes use the static (non-animated) anchor.
* State is not persisted across process death.
