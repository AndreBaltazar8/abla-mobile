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

The engine separates portable concepts (scene identity, entities, particles,
normalized pointer input, fixed-size time steps, missions, terminal states,
palette/assets, score, and sound intent)
from the Android renderer and audio sink. This keeps the protocol suitable for
a later iOS renderer driven by the same `game` identifier and scalar theme.

The Android reference implementation includes eight playable scenes:
`neon_drift`, `sky_stack`, `slime_sprint`, `orbit_dash`, `temple_hop`,
`pixel_punch`, `wave_rider`, and `moon_miner`. They share a deterministic 120
Hz fixed-step loop, bounded particle system, explicit victory/game-over/retry
state machine, lives, combo scoring, and mission progress while keeping
distinct mechanics, collision rules, objectives, and rendering.

`art` is an optional logical asset name. Android resolves it once from the
application drawable bundle and composites it as a static GPU texture below
the dynamic scene. This gives applications an asset pipeline for original
painted environments, sprite atlases, and branded scene texture without
embedding bitmap bytes in the protocol. A future iOS host resolves the same
logical name from its asset catalog.

## High-refresh and production renderer path

The reference renderer advances directly from the platform frame clock,
accumulates deterministic 120 Hz simulation steps, requests the highest
same-resolution display mode, and invalidates only its draw layer. HUD
composition changes only when score, combo, lives, progress, or phase changes.
It therefore follows 60, 90, 120, or higher refresh displays without sending
animation events through Abla.

On Android 13 and newer, the scene backdrop is a real AGSL `RuntimeShader`
feeding the GPU Canvas pipeline; older versions receive a deterministic
gradient fallback. The renderer layers painted application art, transparent
hero sprites, procedural worlds, glow materials, parallax, trails, screen
shake, hit flashes, and a bounded particle pass. Short game effects are
generated as multi-oscillator PCM and played through `AudioTrack`, avoiding
system notification tones while keeping the framework asset-independent.

For a content-heavy 3D game, the stable `NativeGame` protocol remains the
same while the host renderer can be replaced by a dedicated OpenGL ES/Vulkan
surface. The intended production pipeline adds sprite/mesh batching, texture
atlases, skeletal animation, streamed music, asynchronous asset loading,
visibility culling, and device quality tiers. Original raster assets
remain app resources selected by logical names rather than generated Kotlin.

`NativeGame` is intended for bundled, framework-known scenes. Dynamic game
code and arbitrary shaders are not accepted from the UI tree. HTML games may
remain available through the separately sandboxed `WebView` node as a portable
fallback.
