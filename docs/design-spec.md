# Abla Mobile design specification

Status: Android developer preview implemented
Last updated: 2026-08-05

## 1. Product definition

Abla Mobile lets an Android application keep its state, rendering decisions,
event handling, and platform intent in Abla while a reusable library performs
the actual Jetpack Compose and Android calls.

The framework is not a Kotlin generator and does not project an Abla object
model into Kotlin. Application builds compile one Abla object into
`libabla_app.so`; the application Gradle module contains manifest, packaging,
and native build configuration but no Kotlin source.

The Android process contains:

```text
libabla_app.so
  process-long abla_mobile_run()
  arbitrary Abla locals/objects/arrays/closures
  semantic UI builders and event logic
  Abla Mobile C transport, allocator, collector, and platform-effect queue
             |
             | copied bytes and scalar commands only
             v
abla-mobile Android host
  fixed JNI/native bridge
  validated immutable UiTree snapshot
  generic Material 3 Compose renderer
  bounded event queue and Android effect adapters
```

## 2. Ownership invariants

These are architectural release gates:

1. Application builds generate no Kotlin or Swift source.
2. Kotlin receives no Abla application, state, object, closure, callback, or
   memory handle.
3. `abla_mobile_run()` takes no application or host parameter.
4. All business and UI-driving state remains ordinary Abla state.
5. An app may use any number of variables; no framework root-state class is
   required.
6. UI trees and payloads are copied and bounded before crossing layers.
7. The reusable host is application-independent.
8. Platform-local mechanics such as focus, cursor selection, scroll position,
   animation clocks, and pressed state may remain in Compose.
9. Abla state is accessed by one serialized event loop in the preview.
10. Android is not called production-safe until native panic containment is
    implemented for this target.

The mobile native transport does retain a platform-owned C function and C
context installed before Abla starts. Neither value points to Abla memory and
neither is visible to Abla or Kotlin. It exists solely to connect two fixed
native libraries; it is not an application or state handle.

## 3. Program lifetime

The host loads `libabla_app.so`, resolves the fixed platform ABI, installs its
native transport, and starts `abla_mobile_run()` once on a detached native
thread. The call is expected to remain active for the Android process lifetime.

Ordinary locals in that call are compiler-rooted at the framework's explicit
memory safepoint. `mobileRuntimeSafepoint()` keeps a reachable collection call
in the exported event-loop graph and collects once live managed allocations
reach 8 MiB. The configured hard limit defaults to 256 MiB in the showcase.

Activity recreation does not restart Abla. `NativeBridge` is a process object
whose latest immutable tree is a `StateFlow`; a new Activity observes the same
tree and application state. Android process death destroys the program.
Persistence after process death is application work and is not implied by this
lifetime model.

If the library cannot load, lacks a required ABI symbol, emits an invalid tree,
or returns unexpectedly, the host switches to a bounded visible failure state.

## 4. Reactivity

The preview uses event-boundary whole-root rendering:

```text
Compose interaction
  -> native event queue copies revision, event ID, and payload
  -> mobileWaitEvent() resumes the serialized Abla loop
  -> Abla mutates any variables needed by that event
  -> the loop builds and publishes revision N + 1
  -> host validates and replaces its UiTree StateFlow value
  -> Compose recomposes the generic renderer
```

This requires no generated bindings and no single `State` variable. Whole-root
rendering is the correctness reference implementation. Stable node keys let
Compose preserve control/list identity. Keyed subtrees or dependency-tracked
render scopes may be added after measurement, but must preserve Abla ownership.

The framework cannot observe arbitrary unsynchronized mutations. Work outside
the serialized event loop will require a future mobile task/completion API or
an explicit invalidation message.

## 5. Abla API

`src/ui.ab` defines immutable semantic nodes and protocol encoding:

- `mobileColumn`, `mobileRow`, `mobileSpacer`, and `mobileDivider`;
- `mobileText`, `mobileCard`, and `mobileList`;
- `mobileButton`, `mobileTextField`, `mobileSwitch`, and `mobileCheckbox`;
- `mobileSlider`, `mobileProgress`, and `mobileChip`; and
- `mobileEncodeTree`.

`src/protocol.ab` defines direct mobile runtime calls:

- `mobilePublishTree`;
- `mobileWaitEvent`;
- `mobileEventRevision`; and
- `mobileEventPayload`.

`src/runtime.ab` configures and observes managed memory and supplies the rooted
safepoint. `src/platform.ab` exposes toast, clipboard, safe browser intent, and
haptic effects.

These modules use ordinary `extern:"c"` declarations. Android-specific
implementations live in this repository and link with the application object.
No source or runtime modification in `ablac` is required.

The builder API is normative for the developer preview. A future `$mobile`
subparser is optional syntax sugar that must lower to these calls. It may not
generate Compose code, invent a state object, or change the host boundary.

## 6. Android host

The host AAR owns:

- `AblaMobileActivity` and the public `AblaMobileHost` composable;
- one process `NativeBridge` with observable loading/ready/failure state;
- JSON parsing and structural validation;
- Material 3 rendering for the supported node set;
- transient text editing, focus, scroll, and Compose control mechanics;
- event encoding into the bounded native queue; and
- main-thread execution of validated platform effects.

The host does not inspect or mutate Abla variables. Its `UiTree` is a
presentation snapshot, not an application-state mirror.

Text fields keep a temporary Compose editing value while focused so IME
composition remains responsive. Every complete value is copied to Abla. When
focus leaves, the control reconciles to the latest Abla value.

## 7. Protocol and limits

Protocol v1 is documented in [protocol.md](protocol.md). The transport uses a
scalar command stream for trees/events and a private native queue for effects.
The semantic tree payload is UTF-8 JSON. JSON is retained for inspectability in
the preview; a future binary codec must preserve the same semantic/versioned
contract and is not required for reactivity.

Hard limits include:

| Item | Limit |
| --- | ---: |
| Encoded tree | 1 MiB |
| Native queued events | 64 |
| Event/effect payload | 4 KiB |
| Effect queue | 64 |
| Parsed nodes | 4,096 |
| Tree nesting | 64 levels |
| Children of one node | 1,024 |
| Properties of one node | 32 |
| Key | 128 characters |
| Scalar string property | 16,384 characters |

An event contains the tree revision from which it originated. The preview
accepts positive already-published revisions because IME/slider input can queue
while newer presentations are arriving, and rejects malformed or future
revisions. Event IDs are stable semantic integers chosen by the Abla app.

## 8. Platform effects

Effects are requested by Abla through noescape C externs. The app-side native
runtime immediately copies the payload into a 64-entry queue. At the next event
wait the host drains that queue, copies it into a Kotlin `SharedFlow`, and runs
the action on Android's main thread.

Supported effect kinds are:

1. short toast;
2. copy plain text to clipboard;
3. open an `http` or `https` URI through `ACTION_VIEW`; and
4. confirmation haptic feedback.

Effect identifiers and payloads do not identify Abla state. Browser schemes are
allowlisted. Queue overflow returns `false` to Abla.

Future services that return values—permissions, documents, media, or sensors—
must use copied request IDs and post copied completions into the serialized
event loop. They may not store an Abla closure or state pointer.

## 9. Target/runtime integration

`src/android_build.ab` defines the AArch64 Android target and emits the Abla
application object. `runtime/native/CMakeLists.txt` links it with:

- Android-owned allocation, conservative collection, roots, and panic policy;
- the fixed app/host transport and platform effect queue;
- the portable `ablac` value runtime compiled unchanged under private names;
- a pointer-only value bridge; and
- an AArch64 LLVM ABI shim matching emitted `sret`/`byval` value declarations.

The target adapter belongs to Abla Mobile. `../ablac` remains unchanged. A new
compiler RFC is justified only if a capability needed by multiple targets is
actually absent; no RFC is needed for the current Android preview.

## 10. Packaging contract

The application module must contain no `.kt` source. It supplies:

- its manifest/application metadata;
- a Gradle dependency on the reusable host AAR/module;
- a CMake file calling `abla_mobile_add_android_application`; and
- the compiler-emitted app object.

The reusable host is framework Kotlin compiled with the Compose plugin. It is
not generated application code. Application-owned Kotlin may be supported as a
deliberate future escape hatch, but is neither required nor generated.

## 11. Verification gates

The Android preview is accepted when all of these pass:

1. native loop test proves multiple Abla locals, rerendering, no callback or
   handle in the exported ABI;
2. UI codec tests cover the complete node surface;
3. host showcase test publishes a real tree from compiled Abla;
4. ARM64 object and APK build from clean sources;
5. the application module contains no Kotlin;
6. ADB test verifies initial render, event mutation, exact stress-event count,
   GC under pressure, Android effect dispatch, and no fatal log;
7. `../ablac` remains clean; and
8. generated artifacts are excluded from the repository.

## 12. Explicit preview limits and future work

The preview is suitable for experimenting with useful local Android apps, not
yet for a production safety claim. Remaining independent extensions are:

- contained Android panics and structured fatal diagnostics;
- x86_64 application object/ABI support for emulators;
- mobile tasks, timers, cancellation, and lifecycle completion messages;
- permissions and result-bearing platform services;
- image/resources, dialogs, navigation stack, and native component registry;
- persistence and state restoration;
- Abla-owned HTTP/RPC convenience APIs;
- binary tree encoding if profiling justifies it;
- optional `$mobile` source syntax; and
- iOS host and target integration.

None of those items may introduce application Kotlin generation or a
foreign-owned handle to Abla state.
