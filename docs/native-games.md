# Native game surfaces

`NativeGame` is the portable presentation boundary for small real-time games:

```abla
<NativeGame
    key="game"
    game="neon_drift"
    art="neon_drift"
    height={420}
    radius={22}
    background="#120D20"
    accent="#A783FF"
    sound
/>
```

The Abla tree contains only immutable scalar configuration. The Android host
creates a precompiled engine instance keyed by the node, advances it from the
Compose frame clock, renders through the native Canvas/GPU pipeline, consumes
pointer gestures locally, and releases audio and scene resources with the
composition lifecycle. No generated Kotlin, callback, closure, application
object, or Abla memory handle crosses the boundary.

The engine separates portable concepts (`GameScene`, entities, normalized
pointer input, fixed-size time steps, palette/assets, score, and sound intent)
from the Android renderer and audio sink. This keeps the protocol suitable for
a later iOS renderer driven by the same `game` identifier and scalar theme.

The Android reference implementation includes eight playable scenes:
`neon_drift`, `sky_stack`, `slime_sprint`, `orbit_dash`, `temple_hop`,
`pixel_punch`, `wave_rider`, and `moon_miner`. They intentionally share the
engine loop and input model while keeping distinct mechanics and rendering.

`art` is an optional logical asset name. Android resolves it once from the
application drawable bundle and composites it as a static GPU texture below
the dynamic scene. This gives applications an asset pipeline for original
painted environments, sprite atlases, and branded scene texture without
embedding bitmap bytes in the protocol. A future iOS host resolves the same
logical name from its asset catalog.

## High-refresh and production renderer path

The reference renderer advances directly from the platform frame clock, uses
delta-time simulation, requests the highest same-resolution display mode, and
invalidates only its draw layer. HUD composition changes only when score,
streak, or lives change. It therefore follows 60, 90, 120, or higher refresh
displays without sending animation events through Abla.

For a content-heavy 2D/3D game, the stable `NativeGame` protocol remains the
same while the host renderer can be replaced by a dedicated OpenGL ES/Vulkan
surface. The intended production pipeline adds sprite/mesh batching, texture
atlases and compression, shader/particle materials, fixed-step simulation with
render interpolation, streamed music and pooled effects, asynchronous asset
loading, visibility culling, and device quality tiers. Original raster assets
remain app resources selected by logical names rather than generated Kotlin.

`NativeGame` is intended for bundled, framework-known scenes. Dynamic game
code and arbitrary shaders are not accepted from the UI tree. HTML games may
remain available through the separately sandboxed `WebView` node as a portable
fallback.
