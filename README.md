# Abla Mobile

Abla Mobile is an Android framework for writing application state, rendering,
and event logic in [Abla](https://github.com/AndreBaltazar8/ablac) while using a
reusable Jetpack Compose host.

The Android developer preview is functional. It builds and installs an
ARM64 APK, renders a useful Material 3 component set, sends events back to a
process-long Abla program, collects managed memory under sustained input, and
lets Abla request bounded Android effects. Application modules contain no
Kotlin source.

## Architecture

There is one `abla_mobile_run()` call per Android process. It owns any number
of ordinary Abla variables for its entire lifetime. The app calls importable
Abla Mobile functions such as `mobilePublishTree()`, `mobileWaitEvent()`,
`mobileToast()`, and `mobileCopyText()`; those functions use mobile-owned
`extern:"c"` implementations linked into `libabla_app.so`.

Kotlin receives only validated immutable presentation trees and copied event
or effect payloads. It never receives an Abla application, state, object,
closure, callback, or memory handle. `abla_mobile_run` itself has no parameters
and returns only an integer if it exits unexpectedly.

Reactivity is deliberately simple and predictable:

1. Compose sends `(tree revision, event ID, copied payload)`.
2. The serialized Abla loop mutates whichever variables the event needs.
3. Abla builds and publishes the next immutable tree.
4. The host replaces a `StateFlow` snapshot and Compose recomposes.

No root `State` class or generated getter surface is required. The showcase
keeps counter, text, preferences, section, list, progress, and status as
independent Abla variables.

## Implemented Android surface

The generic host currently renders:

- columns, rows, spacers, dividers, text, cards, and keyed lazy lists;
- filled, outlined, and text buttons;
- text fields, switches, checkboxes, sliders, and filter chips;
- linear and circular progress indicators;
- light and dark Material 3 themes; and
- stable node keys and semantic test tags.

Abla can request copied, bounded platform effects for toasts, clipboard text,
safe `http`/`https` browser intents, and haptic feedback.

The host enforces a 1 MiB encoded-tree limit, 4 KiB event/effect payloads, a
64-event native queue, 4,096 nodes, 64 levels of nesting, and bounded keys,
properties, and child counts. Native startup and protocol failures become a
visible Compose error screen.

## Build and test

The current toolchain is developed alongside `../ablac`. Build `ablac` first,
then from this repository run:

```sh
./tools/test-native-loop.sh
./tools/test-ui-tree.sh
./tools/test-showcase-host.sh
./tools/build-showcase-android.sh
```

With an unlocked Android device connected through ADB:

```sh
ABLA_ADB_SERIAL=127.0.0.1:47102 ./tools/test-showcase-device.sh
```

The device test temporarily prevents screen timeout and disables autofill,
installs and launches the APK, verifies rendering and Abla-owned state, sends
250 rapid events to exercise GC, invokes an Android toast from Abla, checks logs
for fatal errors, and restores the previous device settings.

The resulting debug APK is written under:

```text
examples/showcase/android-app/build/outputs/apk/debug/android-app-debug.apk
```

`tools/build-showcase-android.sh` fails if the application source tree contains
any `.kt` file.

## Creating another app

Use `examples/showcase` as the preview template:

1. Write the process-long entry in Abla and export it as `abla_mobile_run`.
2. Build an Android ARM64 object with `mobileBuildAndroidArm64Object()`.
3. Point the app module's CMake configuration at that object.
4. Depend on `:runtime:android-host`; do not add application Kotlin.

The minimum event loop is:

```abla
import "abla/build"
import "path/to/abla-mobile/src/protocol.ab"
import "path/to/abla-mobile/src/runtime.ab"
import "path/to/abla-mobile/src/ui.ab"

fun application(): int {
    mobileConfigureRuntime(268435456)
    var revision = 0
    var count = 0
    while (true) {
        revision = revision + 1
        mobilePublishTree(mobileEncodeTree(
            revision,
            "Counter",
            false,
            mobileColumn("root", 20, 12, false, [
                mobileText("count", "$count", "display"),
                mobileButton("increment", "+1", 1, true, "filled")
            ])
        ))
        mobileRuntimeSafepoint()
        val event = mobileWaitEvent()
        val eventRevision = mobileEventRevision()
        if (eventRevision > 0 && eventRevision <= revision && event == 1) {
            count = count + 1
        }
    }
    0
}

val exported = #exportFunction("application", "abla_mobile_run")
```

## Preview limits

- The app object target is ARM64 Android; the reusable host also builds for
  x86_64, but an x86_64 Abla app target adapter is not included yet.
- There is one Abla mobile program and one root UI tree per process.
- Events execute serially. Long-running work needs a future task/service API.
- Trees use bounded protocol-v1 JSON. A binary codec is an optimization, not a
  correctness dependency.
- Panic containment is not yet available for the Android target, so this is a
  developer preview rather than a production-safety claim.
- Persistence, permissions, networking/RPC orchestration, timers, images,
  dialogs, and custom native component registries remain future modules.
- The builder API is the supported source surface. A `$mobile` parser can be
  added later as syntax sugar without changing ownership or runtime behavior.

See [the design specification](docs/design-spec.md), [protocol](docs/protocol.md),
and [implementation plan](plan.md) for the exact contracts and remaining work.

## License

Abla Mobile is licensed under the [Mozilla Public License 2.0](LICENSE).
