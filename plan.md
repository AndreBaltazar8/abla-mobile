# Abla Mobile implementation plan

Status: Draft  
Updated: 2026-08-04  
Primary target: Android  
Later target: iOS

This plan implements the architecture in
[the design specification](docs/design-spec.md): a process-owned Abla mobile
program, a fixed native protocol, and a reusable precompiled Compose host.
Application builds do not generate Kotlin, Kotlin owns no Abla handle, and Abla
state may consist of any number of variables.

## 1. Architectural invariants

These are release gates, not optional optimizations:

1. No application-specific Kotlin or Swift source is generated.
2. Android uses a precompiled, versioned `abla-mobile-android.aar` containing
   the generic Compose renderer and fixed JNI bridge.
3. The first release has one process-owned `MobileProgram`, rooted entirely by
   the Abla runtime.
4. No application/state/closure/object handle or pointer crosses into Kotlin.
5. Stable node IDs, event IDs, tree revisions, and request IDs are inert routing
   values and never identify Abla memory.
6. `$mobile` lowers to executable Abla UI-builder and event-registration code,
   not Compose source.
7. Abla publishes bounded immutable presentation trees through a fixed protocol.
8. Kotlin renders the latest tree and forwards typed event payloads. It does
   not evaluate application bindings or mutate application state.
9. A successful local event or managed async completion automatically rerenders
   the Abla root once.
10. Whole-root rerendering is the MVP correctness model. Fine-grained reactive
    scopes are a later measured optimization.
11. RPC request/result handling lives in Abla, not in generated Kotlin.
12. All Abla state access is serialized in v1, and native panics may not unwind
    through JNI in a production build.

A proposed shortcut that violates one of these points must be handled as an
explicit architecture revision.

## 2. Ownership and workstreams

| Workstream | Repository/artifact | Responsibility |
| --- | --- | --- |
| Mobile program runtime | `ablac` runtime + `abla-mobile` | Process-owned program root, render/event closure lifetime, serialized executor, dirty scheduling. |
| Fixed mobile ABI | `ablac` + `abla-mobile` | Fixed entrypoints/imports, copied bytes, status/errors, threading, panic behavior, manifests. |
| Mobile language | `abla-mobile` | `$mobile` grammar, typed lowering to executable builders, keys, event IDs, diagnostics. |
| UI protocol | `abla-mobile` | Immutable tree model, deterministic binary codec, limits, versioning, stale-event rules. |
| Android host | `runtime/android-host` | Precompiled AAR, Activity/embedded composable, tree decoder, generic Compose renderer, lifecycle, registries. |
| RPC | `ablac` standard library + `abla-mobile` | Contract projection, Abla client transport/codecs/tasks, generated server routes and schemas. |
| Platform integration | Android host + user modules | Native components, services, permissions, lifecycle, user-owned Kotlin escape hatches. |
| Verification | Both repositories | Compiler conformance, protocol fuzzing, Compose tests, JNI tests, packaging, determinism. |

Compose and Android SDK policy stays out of `ablac`. Runtime rooting, closure
safety, fixed exports/imports, threading, and panic containment remain
framework-neutral compiler/runtime capabilities where possible.

## 3. Dependencies and removed dependencies

### 3.1 Compiler/runtime work required

| Capability | Blocks |
| --- | --- |
| Runtime-owned process mobile root | Retaining `MobileProgram` and arbitrary captured Abla state after startup. |
| Safe retained `FnMut` closures | Executable render and event tables. |
| Fixed Android exports/imports | Host startup, dispatch, tree publication, effects, and errors. |
| Serialized completion queue | Local events, timers, RPC, and platform-service completions mutating the same state safely. |
| Android panic containment | Production-safe JNI boundary. |
| x86_64 Android target | Emulator CI and developer preview usability. |
| Contract imports + runtime JSON/HTTP | End-to-end Abla-owned RPC. |

### 3.2 No longer required for the MVP

The implementation must not wait for or build around:

- foreign-owned application instance handles;
- generated per-binding getters and per-event native functions;
- compiler-reflected binding read paths/handler write paths;
- Kotlin `ViewModel` mirrors of Abla presentation values; or
- Kotlin RPC clients that commit results through an Abla handle.

Reactive access-path reflection may remain useful to other frameworks. It is
not the foundation of this mobile design.

## 4. Intended repository structure

```text
abla-mobile/
  abla.toml
  README.md
  plan.md
  docs/
    design-spec.md
    protocol.md                 # extracted when the first codec lands
  src/
    abla-mobile.ab
    mobileview.ab
    program.ab
    ui.ab
    event.ab
    protocol.ab
    rpc.ab
    service.ab
    diagnostics.ab
  build/
    entry.ab
    android.ab
    manifest.ab
    package.ab
  runtime/
    android-host/
      settings.gradle.kts
      build.gradle.kts
      src/main/kotlin/          # framework-owned, built into the release AAR
      src/main/cpp/             # fixed JNI bridge only
      src/test/
      src/androidTest/
    protocol-fixtures/
  examples/
    counter/
      shared/
      mobile/
      backend/
      platform/android/         # optional user-owned component example
  tests/
    parser/
    lowering/
    protocol/
    runtime/
    rpc/
    android/
```

The Kotlin directory above is framework source. Application builds consume its
published AAR and never copy, template, or generate those files.

## 5. Application output layout

The standalone builder may publish only below the configured build directory:

```text
build/abla-mobile/
  manifests/
    application.json
    dependencies.json
    mobile-protocol.json
    rpc-schema.json
    abla.mobile.abi.v1.json
  android/
    settings.gradle.kts
    build.gradle.kts
    gradle/libs.versions.toml
    app/
      build.gradle.kts
      src/main/AndroidManifest.xml
      src/main/res/
      src/main/jniLibs/arm64-v8a/libabla_app.so
      src/main/jniLibs/x86_64/libabla_app.so
  server/
    generated_rpc.ab
    rpc-schema.json
```

There must be no generated `src/main/kotlin`, `.kt`, or `.swift` path. The
generated Gradle module depends on the pinned binary host artifact. User-owned
files below `platform/android` are inputs/overlays and are never overwritten.

## 6. Milestone 0: prove the fixed render loop

This spike validates the central architectural choice before building the
language surface. It may use a small hand-written native fixture or mock Abla
runtime where current compiler capabilities are missing.

### Deliverables

- Define `abla.mobile.protocol.v1` headers for:
  - tree revision and protocol version;
  - `Text`, `Column`, and `Button` nodes;
  - stable keys and event IDs;
  - scalar/string event payloads; and
  - bounded status/error values.
- Create the reusable Android host library with:
  - an `AblaMobileHost` composable;
  - observable immutable snapshot storage;
  - a minimal binary decoder/validator;
  - a recursive/keyed Compose renderer; and
  - a fake/fixed native dispatch adapter.
- Publish two hardcoded tree revisions through the same boundary planned for
  Abla.
- Tap a button, send `(tree revision, event ID, payload)` through the fixed
  dispatcher, and publish the next tree.
- Add a build assertion that the sample application output contains no Kotlin
  source.
- Record protocol limits and byte ownership in a draft ABI manifest.

### Exit gate

An emulator displays `Count: 0`; tapping the button crosses the fixed native
dispatcher and displays `Count: 1`. The Android host uses no application/state
handle and the application artifact contains no generated Kotlin.

## 7. Milestone 1: runtime-owned `MobileProgram`

This milestone replaces the spike fixture with real Abla state and lifecycle.

### Compiler/runtime work

- Add one process-scoped runtime root for the selected mobile entry.
- Define startup validation for exactly one `@mobile_app` function.
- Retain one affine `MobileProgram` containing render/event closures without
  exposing it through the foreign ABI.
- Support fixed exported calls:
  - start;
  - attach/current-tree publish;
  - dispatch by revision/event ID;
  - displayed-revision acknowledgement;
  - detach; and
  - bounded error inspection if needed.
- Support fixed imported host calls:
  - publish copied tree bytes;
  - request a platform effect; and
  - report a bounded fatal/development diagnostic.
- Serialize start, event dispatch, completion commit, and render on one mobile
  executor.
- Specify process lifetime, idempotent start, attach/detach, and configuration
  recreation behavior.
- Add checked native fixtures for wrong-thread calls, repeated attach/detach,
  invalid payloads, and post-failure behavior.

### Framework integration

- Implement a temporary ordinary-Abla builder API for the three spike nodes.
- Create a `MobileProgram` with at least three separately captured mutable
  values, not a state class.
- Keep the event closure table in Abla and publish only stable event IDs.
- Rebuild the table transactionally with each tree revision.
- Retain only the bounded displayed/pending revision window and retire older
  tables after the host's displayed-revision acknowledgement.
- Reject events whose revision is neither displayed nor validly in flight.

### Exit gate

A real Abla counter owns `count`, `status`, and `tapCount` as separate values.
The precompiled host starts it, renders its tree, dispatches a tap by ID, and
shows all three changed values without an application handle, generated getter,
or generated Kotlin method.

## 8. Milestone 2: executable `$mobile` lowering

### Parser and semantic model

- Register the `$mobile` subparser from the framework package.
- Implement the initial grammar for:
  - `Screen`;
  - `Column`;
  - `Row`;
  - `Text`;
  - `Button`;
  - `TextField`;
  - `Spacer`; and
  - `Native`.
- Parse literal properties, text/interpolation expressions, event expressions,
  payload placeholders, stable keys, and source spans.
- Type-check every interpolation, property, event payload, and captured value.
- Reject platform objects, unsupported resources, or escaping foreign values.
- Derive stable node/event identities from qualified application/screen/node
  identity and event role, never source offsets.

### Lowering

- Lower nodes to executable calls into `abla/mobile/ui`.
- Lower interpolations to render-time Abla expressions.
- Lower event expressions to Abla-owned closures registered in the next event
  table.
- Use ordinary closure lowering for independently captured mutable locals.
- Produce no binding getters, write-path graph, native event exports, or Kotlin
  AST/source.
- Preserve Abla source spans in debug tree metadata and diagnostics.
- Publish the new tree and event table atomically from the host's perspective.

### Exit gate

The counter is authored only with `$mobile`, directly reads multiple local
variables during render, and mutates them from inline/named Abla event logic.
Changing whitespace or unrelated source does not change its stable keys/events.

## 9. Milestone 3: complete coarse-grained reactivity

### Dirty scheduling

- Mark the mobile root dirty after every normally completed local UI event.
- Coalesce repeated dirty marks within one executor turn into one render.
- Do not publish an intermediate tree while one handler is still mutating
  state.
- Expose `mobileInvalidate()` for work scheduled outside framework-managed
  boundaries.
- Make framework timers/tasks post completions onto the serialized mobile
  executor and invalidate automatically.
- Define panic behavior: publish no partial tree, report a bounded failure, and
  never claim rollback.

### Tree protocol

- Complete deterministic encoding/decoding for the required node set.
- Enforce byte, depth, node, child, string, and property limits on both sides.
- Validate UTF-8, child ranges, property tags, key uniqueness, and event payload
  schemas.
- Add feature negotiation and major/minor protocol compatibility.
- Keep diagnostic JSON optional and off the release hot path.

### Compose integration

- Render a whole new immutable tree revision through Compose-observed snapshot
  state.
- Preserve focus/selection/scroll/animation mechanics by stable node key where
  appropriate.
- Reject stale control events after the displayed revision changes.
- Add controlled text input round trips: native input payload to Abla, Abla
  mutation, new value in the next tree.
- Add keyed lists after their identity and limit behavior is covered by tests.

### Exit gate

Local buttons, text input, managed timers, and one explicitly invalidated
external Abla task all update the UI correctly. No compiler dependency analysis
or Kotlin state mirror participates in correctness.

## 10. Milestone 4: Abla-owned RPC

### Prerequisites

- Land strict bounded runtime JSON parse/stringify and required codecs.
- Land contract imports with module-qualified declaration identities.
- Require consumption of implementation-free contract calls.
- Provide an Abla HTTP/TLS client or a bounded generic platform transport
  service.
- Integrate tasks, cancellation, and deadlines with the mobile executor.

### Mobile side

- Recognize resolved contract-only `@rpc` calls in event/task position.
- Encode arguments and decode responses in Abla.
- Keep loading, result, error, request-key, and stale-response policy in Abla.
- Resume completions on the mobile executor.
- Automatically dirty/rerender after a completion commits.
- Generate no Kotlin RPC methods and no native completion setters.

### Backend side

- Discover real top-level `@rpc` implementations.
- Generate a closed route allowlist and typed decoder/call/encoder adapters.
- Generate canonical method schemas and a service digest.
- Reject schema mismatches before invoking application code.
- Bound input, output, timeout, and public error data.

### Exit gate

The counter performs a remote increment. Request and completion logic execute
in Abla, mutate the same independent values used by the render closure, and
publish loading/success/error trees through the fixed host protocol.

## 11. Milestone 5: native components and services

### Native components

- Define the precompiled host registry keyed by stable component ID/version.
- Decode bounded typed properties from `Native` nodes.
- Supply an event sink containing only tree revision, event ID, and bounded
  payload.
- Prove a component never receives an Abla application/state/callback handle.
- Support user-owned Android modules and precompiled AAR registrations.
- Require explicit fallback behavior for unavailable platform components.

### Platform services

- Define request/response schemas for one initial service such as sharing or
  secure storage.
- Keep request IDs and completion closures owned by Abla.
- Implement Android lifecycle, permission, cancellation, and denial behavior in
  the host.
- Deliver results to the serialized Abla executor and rerender automatically.
- Add bounded diagnostics that exclude secrets and raw platform errors.

### Exit gate

The example includes one user-owned Compose component and one platform service.
Neither requires application-specific generated Kotlin or exposes an Abla
handle.

## 12. Milestone 6: Android hardening and packaging

- Add checked panic containment for all Android entrypoints and host callbacks.
- Audit JNI local/global reference ownership, byte copies, and native thread
  attachment.
- Verify all Abla mutation/render work is serialized.
- Add x86_64 output and emulator CI alongside arm64 device CI.
- Pin AAR, Kotlin, Compose, AGP, SDK, NDK, Gradle, and protocol versions.
- Add accessibility, RTL, localization, theming, assets, dark mode, and
  configuration-change coverage.
- Add process-death behavior:
  - default fresh Abla program;
  - optional versioned Abla persistence codec; and
  - never automatic state mirroring through Kotlin.
- Build standalone debug/release APKs and AABs.
- Test embedded mode inside a user-owned Compose app.
- Add R8/consumer rules and native symbol/size inspection.
- Fuzz the tree decoder, event decoder, RPC decoder, and version negotiation.

### Production gate

Android is not production-ready until:

- a panic cannot unwind across JNI;
- malformed/oversized trees and payloads fail safely;
- no use-after-return of tree/payload memory is possible;
- stale events cannot target a newly rendered closure accidentally;
- process/configuration lifecycle tests pass;
- arm64 and x86_64 suites pass;
- app outputs contain no generated Kotlin;
- native ABI inspection finds no application create/destroy/getter handle
  surface; and
- identical locked inputs reproduce identical framework-generated artifacts.

## 13. Milestone 7: iOS

Do not begin production iOS work until the compiler can emit supported Apple
artifacts on macOS.

### Prerequisites

- device and Simulator targets;
- Mach-O/static library output;
- Apple SDK/sysroot linker integration;
- generated C headers/module metadata;
- XCFramework packaging; and
- checked panic containment.

### Framework work

- Publish a precompiled SwiftUI host rather than generated Swift.
- Reuse the semantic tree protocol, Abla-owned event table, and rerender model.
- Forward stable events through a fixed C boundary with no Abla handles.
- Marshal tree publication onto the main actor.
- Add typed native component/service registries.
- Validate semantic parity without promising pixel identity.

### Exit gate

The same counter source runs through a precompiled SwiftUI host. Local and
remote events mutate only Abla values and update the UI via a new tree revision.

## 14. Proposed first pull requests

Keep the vertical slice reviewable:

1. Protocol ADR, v1 headers, limits, fixtures, and ABI manifest schema.
2. Reusable Android host AAR with fake tree source and static Compose rendering.
3. Fixed native event round trip and two-revision counter spike.
4. `ablac` process-owned mobile root and fixed entrypoint/import proof.
5. Abla `MobileProgram`, event table, and three-variable counter using builders.
6. `$mobile` parser and executable lowering for `Column`, `Text`, and `Button`.
7. Dirty scheduler, transactional publish, stale-event rejection, and text
   input.
8. Remaining preview nodes, keys/lists, accessibility, and host diagnostics.
9. Runtime JSON/HTTP plus contract-import compiler work.
10. Abla-owned RPC client and generated backend route.
11. User-owned native component and first platform service.
12. Android hardening, x86_64 CI, standalone AAB, and embedded example.

Pull requests 1–3 can proceed with native fixtures while compiler work is under
review. The fixture must be deleted or isolated from release packaging once the
real `MobileProgram` path lands.

## 15. Test matrix

| Layer | Required coverage |
| --- | --- |
| Parser/lowering | Nodes, properties, interpolation, inline/named events, payloads, captures, keys, source spans, diagnostics. |
| Program runtime | Start once, retained closures, independent variables, attach/detach, serialization, invalidation, panic path. |
| Protocol | Deterministic bytes, versions, feature flags, limits, malformed trees, UTF-8, stale revisions, hostile payloads. |
| Android host | Generic renderer, semantics, keyed identity, text input, lifecycle, snapshot publication, bounded error UI. |
| Boundary | Fixed symbols, no app handles/getters, byte ownership, repeated calls, wrong threads, panic containment. |
| RPC | Contract/schema parity, Abla encode/decode, success/errors, cancellation, timeouts, stale results, limits. |
| Native integration | Registered component properties/events, missing fallback, service permission/denial/cancellation. |
| Packaging | No generated Kotlin/Swift, arm64/x86_64 libraries, APK/AAB, embedded AAR, manifests and symbols. |
| Determinism | Byte-identical protocol/framework/build outputs for identical source, lock, target, and host revision. |

## 16. Risk register

| Risk | Mitigation |
| --- | --- |
| Whole-tree rerender is too slow | Begin with strict size limits and measurements; add keyed render scopes/dynamic tracking inside Abla only after profiling. |
| Generic renderer limits platform capabilities | Keep a small common node set plus user-owned registered native components/services. |
| UI snapshots are mistaken for mirrored state | Protocol contains presentation-only values and events; prohibit state paths, objects, getters, and persistence through the tree. |
| Process singleton limits multi-window/embedding | Declare one attached root in v1; design an internal scene model before expanding without exporting state handles. |
| Event IDs are reused after rerender | Bind every event to a tree revision and transactionally swap tree/event tables. |
| Mutable closure lifetime is unsound | Make `MobileProgram` an affine runtime-owned root and add compiler/runtime conformance tests before UI expansion. |
| Async work mutates from the wrong thread | Resume all managed completions on the serialized mobile executor; reject unsupported mutation paths. |
| JNI becomes chatty | Publish one bounded binary tree per render and one event call per input; measure before adding subtree frames. |
| Protocol/AAR versions drift | Pin both in `abla.lock`/manifest and fail required-feature or major mismatches at startup. |
| Compose upgrades break behavior | Release/test the host AAR independently with pinned toolchains and compatibility metadata. |
| Panic leaves partially mutated Abla state | Publish no partial tree, surface a bounded fatal policy, and do not claim rollback. |
| RPC secrets leak into UI diagnostics | Separate secure configuration/service values from tree and redact native/runtime errors. |

## 17. Decisions to lock before the preview

- exact `@mobile_app` return/entry syntax;
- whether multiline inline event blocks land in the first parser version;
- binary tree tag layout and hard limits;
- fixed C/JNI symbol names, byte ownership, and status model;
- process root startup/failure/restart policy;
- executor ownership and managed task API;
- stale-event and event-ID derivation rules;
- Activity recreation and optional Abla persistence API;
- initial Android host artifact distribution and Gradle compatibility range;
- initial RPC transport/TLS implementation;
- minimum Android API, ABIs, and pinned toolchain versions; and
- criteria and design constraints for later keyed render scopes.

## 18. Android MVP definition of done

The developer preview is complete when a clean checkout can:

1. resolve one lock for shared, mobile, backend, and Android host artifacts;
2. compile an Abla `MobileProgram` to arm64 and x86_64 shared libraries;
3. package the precompiled Compose host without generating Kotlin source;
4. retain several independent Abla variables through Activity recreation;
5. render them through a bounded immutable semantic tree;
6. dispatch local events by revision/event ID with no Abla handle in Kotlin;
7. automatically rerender once after a successful event;
8. handle text input and reject stale events;
9. perform one schema-checked RPC entirely from Abla and rerender its result;
10. expose one user-owned native component and one platform service through
    bounded presentation/event data;
11. pass parser, runtime, protocol, JNI, Compose, RPC, lifecycle, and packaging
    tests;
12. pass an artifact audit proving no generated `.kt`/`.swift` and no
    create/destroy/getter application-handle ABI; and
13. reproduce byte-identical framework-generated artifacts from identical
    locked inputs.

The implementation should stop and revisit the architecture if this vertical
slice cannot be achieved without reintroducing generated native UI source or a
foreign-owned Abla application handle.
