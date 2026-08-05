# Abla Mobile design specification

Status: proposed  
Target release: Android developer preview first, iOS later  
Last updated: 2026-08-04

## 1. Decision summary

Abla Mobile is an Abla-owned application runtime with a reusable native host.
It is not a Kotlin source generator and it does not mirror an Abla application
object through native handles.

The Android application contains:

```text
Abla application (`libabla_app.so`)
  - owns all business and UI-driving state
  - owns render and event closures
  - performs local logic, RPC, navigation, and effect coordination
  - publishes immutable UI tree revisions
                 |
                 | fixed, versioned mobile ABI
                 v
precompiled `abla-mobile-android.aar`
  - owns Activity/Compose lifecycle and platform scheduling
  - validates and renders the latest UI tree with Jetpack Compose
  - forwards event payloads to the Abla dispatcher
  - implements registered Android services and native components
```

There is no application-specific Kotlin generated at application build time.
The framework's Kotlin and Compose code is compiled once when the Android host
AAR is released. A standalone app may generate Gradle, manifest, resource, and
packaging files, but its generated source set contains no Kotlin.

There is also no application handle, state handle, object pointer, getter
surface, or callback pointer exposed to Kotlin. The first Android release has
one Abla mobile program per process. That program and every closure or value it
retains are rooted and owned inside the Abla runtime. The boundary may carry
immutable UI bytes, scalar event payloads, stable node/event IDs, request IDs,
and error values. Those values are routing or presentation data, not handles to
Abla storage.

The reusable Kotlin host is necessarily a generic renderer. Jetpack Compose
composable calls must execute from Kotlin code compiled by the Compose compiler;
native Abla code cannot directly call arbitrary `@Composable` functions.
Abla therefore calls framework UI-building functions such as `Text`, `Column`,
and `Button`. Those functions build an Abla-owned semantic tree. The precompiled
host maps that tree to real Compose components.

Reactivity in the first release is event-boundary rerendering:

```text
Compose event
   -> fixed native dispatch(event ID, payload)
   -> Abla event closure mutates any Abla variables it needs
   -> Abla reruns the root render closure
   -> Abla publishes immutable UI tree revision N + 1
   -> host replaces its presentation snapshot
   -> Compose recomposes the affected host renderer
```

This model requires no single `State` object. An application may use any number
of captured locals, objects, collections, cells, or domain values. It also
requires no generated binding getters and no compiler-derived read/write graph.
The Kotlin host stores only presentation snapshots and host-local UI mechanics;
it never becomes the source of truth for business state.

Whole-root rerendering is the correctness baseline. Keyed subtrees and a later
dynamic render-scope dependency system may reduce work without changing the
ownership boundary, but fine-grained invalidation is not an MVP prerequisite.

## 2. Non-negotiable architectural constraints

1. Application builds generate no Kotlin or Swift source.
2. Kotlin/Swift never owns or receives an Abla application, state, closure, or
   runtime-object handle.
3. All business state and UI-driving state lives in Abla.
4. State is not required to have one root object or one annotated variable.
5. The native host is reusable and application-independent.
6. Abla UI code calls a framework API that lowers to a semantic UI tree; the
   platform host performs the actual Compose/SwiftUI calls.
7. Local events execute in Abla and automatically schedule a rerender after a
   successful event boundary.
8. RPC request construction, decoding, completion logic, and result mutation
   live in Abla. Kotlin is not the application networking layer.
9. The platform boundary is fixed, bounded, versioned, and contains no raw
   pointers or retained foreign callbacks.
10. Platform-only state such as focus, text selection, animation progress, and
    scroll position may remain in Compose when it has no business meaning.
11. Application-specific Kotlin remains an optional, user-owned escape hatch;
    it is never generated and receives only typed presentation data/events.
12. Android panic containment is required before a production claim.

If an implementation needs application-specific Kotlin, a host-owned Abla
token, or a Kotlin mirror of application state, it is a different architecture
and requires a new design decision.

## 3. Goals and non-goals

### 3.1 Goals

The framework must:

1. Let an application declare native screens in Abla with a concise `$mobile`
   syntax and ordinary Abla expressions.
2. Let a screen read any number of Abla variables without placing them inside
   a framework-mandated state class.
3. Automatically update the UI after local events and managed asynchronous
   completions.
4. Render a useful semantic node set through a precompiled Compose host.
5. Keep domain rules, navigation intent, RPC contracts, request state, and
   application errors in Abla.
6. Support stable keyed identity for lists and stateful native controls.
7. Provide bounded native services and user-owned native component escape
   hatches without passing platform objects into Abla.
8. Package a standalone Android app with no generated Kotlin source.
9. Allow a hand-written Android app to embed the same precompiled host.
10. Produce a counter example that proves multiple independent Abla variables,
    local reactivity, text input, and an Abla-owned RPC completion.

### 3.2 Non-goals for v1

The first release will not:

- generate application-specific Compose or SwiftUI source;
- let Abla call arbitrary third-party composables with no registered adapter;
- expose every Android API through Abla;
- promise pixel identity across platforms;
- keep more than one independent Abla mobile program in one process;
- detect every mutation performed by an unmanaged background thread;
- provide fine-grained variable dependency tracking before it is needed;
- serialize complete Abla state through the UI protocol;
- use Kotlin Multiplatform as a second business-logic runtime;
- support background services, widgets, watches, or app extensions; or
- claim production safety while an Abla panic can cross JNI uncontained.

## 4. State and application lifetime

### 4.1 Abla owns the program

Exactly one `@mobile_app` function is selected for an Android application. It
returns a `MobileProgram`, not an application state object and not a compile-time
`MobileView`.

`MobileProgram` contains Abla-owned render and event closures. The mobile runtime
roots the program internally for the life of the process. Kotlin starts and
attaches to the program through fixed entrypoints, but it never receives a
token identifying that program.

The intended source form is:

```abla
#import(github("AndreBaltazar8/abla-mobile"))
#import("../shared/counter.ab")

@mobile_app
fun counterApplication(): MobileProgram {
    var count = 0
    var status = "Ready"
    var requests = 0

    return $mobile
        <Screen id="counter" title="Abla Counter">
            <Column padding="large" spacing="medium" align="center">
                <Text key="count" style="headline">Count: {count}</Text>
                <Text key="status">{status}</Text>
                <Button onTap={
                    count = nextCount(count)
                    status = "Updated locally"
                }>
                    Increment locally
                </Button>
            </Column>
        </Screen>
}
```

The example has three independent mutable variables. The compiler lowers their
mutable captures according to ordinary Abla closure rules. `$mobile` does not
invent a `CounterState`, and the Android host cannot inspect any of the three
variables.

The exact multiline event-block syntax remains subject to parser validation.
If the initial subparser can only accept expressions, a named local function or
an expression block with the same capture semantics is acceptable. The state
and ownership model must not change to accommodate parser convenience.

### 4.2 Lifetime and recreation

The Android v1 runtime has one process-owned mobile program:

- `start` initializes it at most once;
- an Activity/embedded host may attach and request the current tree;
- configuration changes detach and reattach the host without recreating Abla
  state;
- process death destroys the state with the process; and
- a new process starts a fresh program unless the app explicitly restores an
  Abla-defined persisted value.

The host does not call `create(app): Long` or `destroy(handle)`. Runtime cleanup
is internal. Multiple windows or simultaneous independent embedded roots are a
later feature because supporting them without external application handles
requires an internally owned scene model.

### 4.3 What may remain platform-local

Compose may own transient interaction mechanics that are not application state:

- focus and keyboard visibility;
- cursor/selection position;
- animation clocks and gesture progress;
- lazy-list measurement and scroll position; and
- temporary ripple/pressed state.

If one of those values affects business rules, navigation, restoration, or
server behavior, the application must model the meaningful value in Abla. The
host-local value is then merely a rendering detail.

## 5. Reactivity

### 5.1 Correctness model

`$mobile` produces a render closure. Every render reads the current Abla values
and constructs a complete immutable semantic tree. A successful mutation
boundary marks the program dirty. The runtime coalesces dirty marks and reruns
the render closure, then publishes the next tree revision.

Automatic dirty boundaries are:

- completion of a local UI event;
- completion of an Abla-managed RPC action;
- completion of a platform service request;
- navigation mutation through the mobile runtime; and
- a timer/task completion scheduled through the mobile runtime.

No user-authored `setState`, redraw action, application handle, getter, or
binding list is required for those paths.

The render transaction is:

1. execute all state changes for the event on the serialized Abla executor;
2. if the event returns normally, mark the root dirty;
3. rerender once after the current batch of work;
4. validate and publish one immutable tree revision; and
5. let the Compose host replace its current presentation snapshot.

If an event panics, no partial UI tree is published. Because Abla mutation is
not transactional, the runtime reports a bounded fatal/program error and must
not pretend it rolled state back.

### 5.2 Unmanaged mutations

An arbitrary write from code running outside a framework event/task boundary
cannot update Compose by magic. The first release uses this rule:

- code scheduled by Abla Mobile invalidates automatically;
- code integrated through another scheduler must call `mobileInvalidate()`
  after committing its Abla changes; and
- unsynchronized mutation from foreign or background threads is rejected or
  outside the supported model.

`mobileInvalidate()` is an Abla runtime call, not a Kotlin state setter. It
marks only the renderer dirty and does not expose or transfer state.

### 5.3 Compose observation

The reusable host keeps an observable `UiTreeSnapshot` or monotonically
increasing presentation revision. That is host rendering state, not application
state. When Abla publishes a new revision, the host updates it on Android's main
thread and Compose recomposes the generic renderer.

Stable node keys allow Compose to preserve the identity of stateful controls
and skip unchanged subtrees where its normal equality/stability rules permit.
The correctness model does not depend on Compose detecting Abla variable writes.

### 5.4 Later optimization: render scopes

Only after measurement shows whole-root rendering is too expensive, the tree
protocol may introduce keyed render scopes. A scope can keep an Abla-owned
version and republish only a subtree. Dynamic dependency tracking could record
which observable Abla cells a scope read and dirty it when one changes.

That optimization must preserve these rules:

- dependencies are tracked inside Abla, never as Kotlin binding getters;
- Kotlin still receives no state or closure handles;
- missing an update is forbidden; unknown writes dirty the root; and
- the whole-root renderer remains the fallback and reference implementation.

Compiler-derived field access paths are therefore not required for the MVP.

## 6. The `$mobile` language

### 6.1 Lowering

`$mobile` remains an Abla-defined staged subparser, but its output changes. It
does not retain a compile-only tree for Kotlin generation. It lowers the node
structure and embedded expressions into executable Abla render code using the
`abla/mobile/ui` API.

Conceptually:

```abla
$mobile
    <Column>
        <Text>Count: {count}</Text>
        <Button onTap={count = count + 1}>Increment</Button>
    </Column>
```

lowers to code equivalent to:

```text
render closure:
  ui.beginColumn(...)
  ui.text("Count: " + count)
  ui.button(stableEventId, "Increment", ...)
  ui.endColumn()

event table in Abla:
  stableEventId -> closure { count = count + 1 }
```

The event table is owned and rooted by the Abla `MobileProgram`. Kotlin sees
only the stable event ID embedded in the immutable tree. It does not retain an
Abla callback or function pointer.

The finalizer must type-check interpolation values, node properties, event
payload types, ownership, effects, and RPC/service calls. It does not need to
derive binding read paths or handler write paths for initial reactivity.

### 6.2 Common node set

The first portable set is intentionally small:

| Category | Nodes |
| --- | --- |
| Structure | `Screen`, `Column`, `Row`, `Box`, `Scroll`, `List`, `Item`, `Spacer` |
| Content | `Text`, `Image`, `Icon`, `Divider`, `Progress` |
| Input | `Button`, `TextField`, `Toggle` |
| Navigation | `NavigationStack`, `Link` |
| Escape hatch | `Native` |

Required preview nodes are `Screen`, `Column`, `Row`, `Text`, `Button`,
`TextField`, `Spacer`, and `Native`.

Properties use semantic values such as spacing tokens, alignment, text role,
enabled state, accessibility label, content description, and stable key. They
do not attempt to serialize a Kotlin `Modifier` or any platform object.

### 6.3 Identity and events

- Every screen has a stable string ID.
- Lists and stateful controls require stable keys.
- Event IDs derive from semantic node identity and event role, not source byte
  offsets.
- A new render builds a revision-scoped event table transactionally. The
  runtime retains the currently displayed and newly published tables until the
  host acknowledges which revision it displays, then discards older tables
  within a strict bounded window.
- The host rejects events for an obsolete tree revision instead of dispatching
  them to a newly reused ID.
- Event payloads are bounded typed values such as `bool`, `int`, `string`, or a
  small framework-defined record.

## 7. UI tree protocol

### 7.1 Purpose

The UI tree is a presentation snapshot, not serialized application state. It
contains only what the platform needs to render the current frame and route
events:

- protocol version and feature flags;
- application/tree revision;
- node kinds, stable keys, and child relationships;
- bounded visual/accessibility properties;
- current control values needed for presentation; and
- event IDs and expected payload schemas.

It contains no Abla pointer, closure, object identity, memory address, state
path, getter symbol, or application token.

### 7.2 Encoding

The first implementation should use a compact deterministic binary command/tree
format with explicit lengths and limits. JSON may be available in development
diagnostics, but it is not the required hot-path representation.

The producer builds into an Abla-owned buffer and publishes an immutable copy.
The host validates before replacing the current snapshot. No host code retains
a pointer into the Abla heap after the publish call returns.

Required validation includes:

- supported protocol version;
- maximum tree bytes, depth, nodes, children, and string lengths;
- known node/property/event tags;
- unique keys among siblings where required;
- valid child ranges and no cycles;
- valid UTF-8; and
- payload schema compatibility.

### 7.3 Versioning

The AAR and Abla framework package declare a shared protocol major/minor pair.
Unknown required features or a major mismatch fail at startup with a bounded
diagnostic. Optional minor features use explicit capability flags. Application
source must not depend on whichever host happens to be newest on a machine;
the lock file pins the framework/host pair.

## 8. Fixed Android boundary

### 8.1 Host-to-Abla calls

The generic host needs only a small fixed surface, conceptually:

```c
abla_mobile_status abla_mobile_start(void);
abla_mobile_status abla_mobile_attach(void);
abla_mobile_status abla_mobile_dispatch(
    uint64_t tree_revision,
    uint64_t event_id,
    abla_mobile_bytes payload
);
abla_mobile_status abla_mobile_displayed(uint64_t tree_revision);
void abla_mobile_detach(void);
```

Exact names and ownership rules belong in `abla.mobile.abi.v1`. These functions
address the one process-owned program and accept no application handle.
`attach` causes the latest tree to be published to the current host.
`displayed` acknowledges a presentation revision so Abla can retire obsolete
event tables safely. The host emits events only for the revision it currently
displays.

The precompiled host's JNI library invokes these fixed C symbols. Application
builds therefore do not generate per-screen JNI or Kotlin methods.

### 8.2 Abla-to-host calls

The Android host exposes a fixed imported surface to the Abla mobile runtime:

```c
abla_host_status abla_mobile_host_publish_tree(
    uint64_t revision,
    abla_mobile_bytes tree
);
abla_host_status abla_mobile_host_request_effect(
    uint64_t request_id,
    uint32_t effect_kind,
    abla_mobile_bytes request
);
abla_host_status abla_mobile_host_report_error(
    uint32_t code,
    abla_mobile_bytes message
);
```

The byte arguments are copied or synchronously borrowed only for the documented
call. An effect request ID correlates a later value response; it does not point
to an Abla object. The Abla runtime owns the completion table.

### 8.3 Threading

All mutations, event dispatch, render closure execution, and completion commits
are serialized through one Abla mobile executor in v1. The host may perform
platform I/O concurrently, but it delivers results back through the serialized
dispatcher. Tree publication is marshalled to Android's main thread before
updating Compose-observed state.

No JNI call may retain local references or let an Abla panic unwind into the
JVM. Byte ownership and release behavior must be explicit in the ABI manifest.

## 9. Android host

### 9.1 Precompiled AAR

`abla-mobile-android.aar` contains:

- a `ComponentActivity` for standalone mode;
- an `AblaMobileHost` composable for embedded mode;
- the generic semantic-tree decoder and Compose renderer;
- the fixed JNI bridge and native-call serializer;
- observable presentation snapshot storage;
- lifecycle attach/detach logic;
- platform service and native component registries; and
- protocol/error diagnostics.

The AAR version pins compatible Kotlin, Compose BOM/runtime, Android Gradle
Plugin range, minimum SDK, NDK ABI contract, and UI protocol version. Updating
Compose is a host-library release, not source regeneration in each application.

### 9.2 Rendering

The host maps semantic nodes to ordinary Compose calls. It may use internal
specialized composables for stable node identity and performance. It must:

- apply accessibility semantics;
- preserve keyed control identity across snapshots;
- avoid emitting events for a snapshot no longer displayed;
- keep host-local interaction state scoped to stable keys;
- render a bounded error screen for invalid trees; and
- expose tree revision/node diagnostics in debug builds.

### 9.3 Standalone and embedded modes

`standalone` generates or assembles a minimal Android module containing the
manifest, resources, Gradle metadata, the precompiled AAR, and
`libabla_app.so`. The Activity comes from the AAR; there is no generated Kotlin
source directory.

`embedded` lets a user-owned Android project depend on the AAR and place
`AblaMobileHost()` in hand-written Compose code. The first release permits only
one attached host at a time.

## 10. Native components and platform services

### 10.1 Native components

Applications that need an Android-specific composable write and compile their
own Kotlin. They register a stable component ID with the host registry. Abla
emits a `Native` node containing bounded typed properties and event IDs.

The component implementation receives no Abla handle. It receives only its
decoded properties, current tree revision, and an event sink that sends bounded
payloads through the generic dispatcher.

The registry may be populated by a user-owned Android module or another
precompiled AAR. Missing components require an explicit fallback or produce a
clear startup/render diagnostic.

### 10.2 Platform services

Camera, biometrics, location, sharing, notifications, secure storage, and
similar APIs use versioned request/response schemas. Abla owns request intent,
loading/error state, and completion behavior. The host owns Android lifecycle,
permissions, and SDK calls.

An Abla service call:

1. allocates an inert request ID in the Abla mobile runtime;
2. sends a bounded request to the host;
3. stores the completion closure only in Abla;
4. receives a typed success/denied/cancelled/error response; and
5. commits the result and automatically rerenders.

No `Context`, Java object, JNI reference, or foreign callback enters Abla.

## 11. RPC

`@rpc` remains the only remote marker. Contract imports expose signatures and
metadata without backend implementations:

```abla
#import(contract("../backend/counter_rpc.ab"))
```

Unlike the previous design, the mobile client is not generated Kotlin. The Abla
framework lowers a consumed contract call to Abla-owned request encoding,
transport, response decoding, and completion logic. Platform TLS/socket support
may come from standard runtime primitives, but Kotlin does not own RPC state or
write results into Abla.

An intended source form is:

```abla
<Button onTap={
    status = "Loading"
    val result = await incrementRemotely(count)
    count = result
    status = "Ready"
}>
    Increment on server
</Button>
```

If the event syntax cannot suspend initially, the framework may expose an
Abla-owned `task`/completion form. Either way, successful and failed completions
are mutation boundaries and rerender automatically.

RPC schemas, service digests, server allowlists, bounded JSON codecs, timeouts,
cancellation, and public errors remain required. Credentials are provided to
the Abla transport through a bounded secure platform service or application
configuration; they are never placed in UI tree diagnostics.

## 12. Package and build layout

An application may use:

```text
my-product/
  abla.toml
  abla.lock
  build.ab
  shared/
  mobile/
    app.ab
  backend/
  platform/
    android/                 # optional user-owned project/overlays/Kotlin
  tests/
  build/
    abla-mobile/
      manifests/
        mobile-protocol.json
        rpc-schema.json
        dependencies.json
      android/
        app/                 # Gradle/manifest/resources, no generated Kotlin
        jniLibs/
          arm64-v8a/libabla_app.so
          x86_64/libabla_app.so
      server/
```

The framework repository should evolve toward:

```text
src/
  abla-mobile.ab
  mobileview.ab
  program.ab
  ui.ab
  event.ab
  protocol.ab
  rpc.ab
  service.ab
build/
  entry.ab
  android.ab
  protocol_manifest.ab
runtime/
  android-host/             # reusable Kotlin/Compose AAR source
  protocol/
examples/
tests/
```

Framework Kotlin exists only under `runtime/android-host` and is compiled when
the framework host artifact is released. It is never templated per screen or
written into an application's generated directory.

Generated output is deterministic, staged transactionally, and restricted to
the configured build directory. User-owned platform files are never
overwritten.

## 13. Required compiler/runtime work

### 13.1 Required for the Android vertical slice

The framework/compiler seam needs:

1. a runtime-owned, process-scoped root for one `MobileProgram`;
2. safe retention and invocation of its `FnMut` render/event closures entirely
   inside Abla;
3. fixed exported Android entrypoints that may reach this runtime-owned state
   without accepting or returning foreign handles;
4. fixed imported host calls for publishing copied bytes and requesting
   effects;
5. a serialized mobile executor and a way for managed task completions to post
   back to it;
6. Android checked panic containment before production;
7. deterministic `$mobile` lowering to executable UI builder calls; and
8. arm64 and x86_64 Android shared-library output.

This design does not require foreign-owned instance handles, per-binding export
generation, reactive access-path reflection, or hygienically generated Kotlin
getters.

### 13.2 RPC prerequisites

- contract imports and mandatory consumption of contract-only calls;
- strict bounded runtime JSON parse/stringify and derived codecs;
- an Abla-accessible HTTP/TLS client or a bounded platform transport service;
- async task/cancellation behavior integrated with the mobile executor; and
- generated server route/schema support.

### 13.3 Later optimization prerequisites

Fine-grained Abla-owned reactivity may add observable cells, render scopes, and
dynamic read tracking. It should not reuse a design whose purpose is generating
Kotlin getters or exposing state paths across JNI.

## 14. Diagnostics and safety

Framework failures must identify the source node/event when possible and stay
bounded at the platform boundary. Required diagnostics include:

- duplicate or unstable keys;
- invalid node/property nesting;
- unsupported interpolation or event payload type;
- stale event/tree revision;
- protocol mismatch or malformed tree;
- tree/resource limit exceeded;
- missing native component/service;
- illegal unmanaged-thread mutation;
- RPC schema mismatch/timeout/limit error; and
- contained Abla panic with no internal path or secret leaked in release mode.

The Android production gate requires checked panic containment, strict byte and
tree limits, JNI ownership audits, UI-thread publication, serialized Abla state
access, arm64 device tests, x86_64 emulator tests, and deterministic packaging.

## 15. Test strategy

### 15.1 Framework and compiler

- `$mobile` parser and lowering tests for every node/property/event form;
- mutable capture tests with several independent locals and nested objects;
- stable node/event ID tests across whitespace and unrelated edits;
- event-table replacement and stale-revision tests;
- deterministic tree encoding and hostile decoder fixtures;
- automatic rerender tests for local events, RPC, services, and managed tasks;
- explicit invalidation tests for externally scheduled Abla work;
- proof that no generated application path contains `.kt` or `.swift`; and
- symbol/manifest tests proving no create/destroy/getter/application-handle ABI.

### 15.2 Android

- Compose semantics and interaction tests for every supported node;
- counter test with at least three separate Abla variables;
- text input round trip through an event payload and rerender;
- keyed list identity and stale-event rejection;
- configuration-change attach/detach without Abla state loss;
- fresh start after process recreation unless explicit restoration is enabled;
- native component and platform service tests with no Abla handles;
- malformed/oversized tree rejection;
- repeated JNI calls, thread serialization, byte ownership, and panic behavior;
- arm64 APK/AAB and x86_64 emulator packaging; and
- embedded AAR integration with a user-owned app.

## 16. iOS direction

iOS reuses the Abla `MobileProgram`, render/event ownership, semantic protocol,
and Abla-owned reactivity. A precompiled SwiftUI host interprets the same
semantic tree and forwards events through a fixed C ABI. Application builds do
not generate Swift.

iOS remains gated on supported Apple targets, Mach-O/static library output,
headers/module packaging, XCFramework creation, checked panic containment, and
macOS/Xcode CI. Platform-specific nodes and services use the same bounded
registry model.

## 17. Android preview acceptance criteria

A clean checkout must be able to:

1. compile one `@mobile_app` into `libabla_app.so` for arm64 and x86_64;
2. package it with the precompiled Android host without generating Kotlin;
3. keep count, status, input, and request state as separate Abla values;
4. render those values through a semantic UI tree;
5. dispatch a button event by stable ID without passing an Abla callback/state
   handle to Kotlin;
6. mutate Abla values and automatically publish one new tree revision;
7. reject a stale event from the prior tree;
8. perform one `@rpc` call in Abla and rerender its success/error result;
9. use one user-owned native component or platform service through bounded
   properties/events;
10. survive Activity recreation with the process-owned Abla program intact;
11. pass parser, protocol, native, Compose, RPC, and packaging tests; and
12. reproduce byte-identical framework output from identical locked inputs.

The first vertical slice should optimize for ownership clarity and a working
render loop, not fine-grained invalidation. Whole-tree rerendering gives correct
reactivity for arbitrary Abla state today. More precise updates can be added
inside Abla later without generating Kotlin or exposing Abla state to the host.
