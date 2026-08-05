# Abla Mobile implementation plan

Status: Template-driven Android developer preview complete
Updated: 2026-08-05

This plan records what is implemented for the first useful Android release and
keeps later product work separate. The architecture is defined by
[the design specification](docs/design-spec.md).

## Release invariants

- No application Kotlin is generated or required.
- Kotlin receives no Abla state/application/object/closure/callback handle.
- `abla_mobile_run()` has no parameters and owns arbitrary Abla variables for
  the process lifetime.
- Application code calls importable Abla Mobile functions backed by arbitrary
  `extern:"c"` implementations in this repository.
- The host accepts only copied, bounded trees, events, and effects.
- Events and Abla state access are serialized.
- Whole-root rerendering is the correctness model.
- `ablac` contains no mobile runtime or Android special case; its one supporting
  RFC is the general inferred-lambda builder needed by initial subparsers.

## Completed preview milestones

### 1. Repository and architecture

- Initialized the repository and committed the original specification.
- Replaced generated-Kotlin/state-handle assumptions with an Abla-owned,
  process-long application model.
- Audited compiler history and current extern/export/target/root capabilities.
- Followed `ablac`'s RFC-first history for the missing general ability to build
  context-typed capturing lambdas from an initial-phase subparser.

### 2. Native Abla Mobile runtime

- Added direct scalar host externs and a parameterless `abla_mobile_run` ABI.
- Added a platform-only native attachment layer with no Abla pointer.
- Added Android allocation, memory limits, conservative collection, root
  frames, explicit event-loop safepoints, and fatal logging.
- Reused the portable Abla value runtime unchanged under private names.
- Added an AArch64 emitted-value ABI shim and pointer-only C bridge.
- Added bounded native event and platform-effect queues.

### 3. Semantic UI and reactivity

- Added immutable protocol-v1 tree builders and deterministic JSON encoding.
- Implemented columns, rows, text, buttons, text fields, switches, checkboxes,
  sliders, progress, dividers, spacers, cards, chips, and keyed lists.
- Implemented revisioned event dispatch and copied text/scalar payloads.
- Proved multiple independent Abla variables update through event-boundary
  whole-root rerendering with no state class.

### 4. `$mobile` language and one-call runtime

- Added an XML-like subparser for the complete preview component surface.
- Added quoted/dynamic attributes, runtime text interpolation, conditional
  groups, dynamic zero/many child groups, visibility, and deterministic keys.
- Added inline `onTap`/`onChange` Abla actions with multiple semicolon-separated
  expressions and the copied `value` payload.
- Lowered a whole template to one capturing state-machine closure passed to
  `mobileRun`; no application event loop, event constants, or state object is
  required.
- Added general `ablac` `syntaxInferredLambda` support, call-site parameter
  inference, and safe `Fn` use in a repeatable `FnMut` callback slot.

### 5. Reusable Android/Compose host

- Added framework-owned Activity, host composable, StateFlow bridge, and fixed
  JNI library.
- Added Material 3 recursive/keyed rendering and host-local IME/focus/scroll
  mechanics.
- Added structural tree validation and bounded loading/failure screens.
- Added idempotent process startup and Activity recreation-safe snapshot state.
- Added Abla-callable toast, clipboard, safe browser, and haptic effects.

### 6. Android packaging and showcase

- Added Android ARM64 target/object emission from Abla.
- Added reusable CMake application linkage and Gradle host/application modules.
- Built a broad showcase with independent counter, input, theme, preference,
  slider, section, list, progress, status, and platform-effect state/behavior.
- Rewrote the showcase itself as one `mobileRun($mobile ...)` application with
  inline actions, conditionals, and dynamic list groups.
- Asserted that the application module contains no `.kt` source.

### 7. Verification

- Native event-loop ABI proof: three renders, two events, no callback/handle.
- Semantic tree codec proof covering the component set.
- Eight-render template proof covering independent captures, all input payload
  types, action blocks, text interpolation, conditionals, and dynamic groups.
- Compiled showcase host proof using real Abla output.
- ARM64 Android object and debug APK build.
- ADB install/launch and UI assertions on a Samsung ARM64 device.
- Exact rapid-event stress count with threshold GC and no panic.
- Real Abla-requested Android toast verified in UI state and native logs.
- Repeatable build and device scripts that preserve the user's screen timeout.

## Developer-preview exit gate

The preview goal is reached when, from a clean checkout:

1. all four host/native scripts pass, including `test-mobile-template.sh`;
2. `tools/build-showcase-android.sh` builds the APK and confirms no app Kotlin;
3. `tools/test-showcase-device.sh` passes against an unlocked ADB device;
4. public ELF/ABI inspection shows only the intended app/mobile symbols and no
   state-handle ABI;
5. the `ablac` inferred-lambda RFC implementation passes its focused fixture
   and the complete self-hosted compiler suite;
6. documentation matches the template/JSON/extern architecture; and
7. generated binaries/build directories are absent before commit and push.

## Post-preview roadmap

These are independent follow-up releases, not hidden requirements of the
working local-app preview.

### Production hardening

- Add checked panic containment for Android native entrypoints.
- Fuzz JSON trees, event payloads, native queues, and failure transitions.
- Add configuration-recreation and process-background instrumentation tests.
- Add release AAR publishing, API compatibility checks, and version manifests.
- Add x86_64 app object/value ABI support for emulator parity.

### App capabilities

- Add task/timer scheduling and copied completion messages.
- Add permission, document, media, sensor, and lifecycle services with request
  IDs rather than Abla handles.
- Add images/resources, dialogs, navigation-stack nodes, and accessibility
  properties.
- Add persistence/restoration controlled by Abla.
- Add a bounded user-owned native component registry.
- Add packaged splash-theme/icon configuration and typed manifest capability
  helpers without generating application Kotlin.
- Extend copied Android effects with share/email/dial/maps intents, then add
  request-ID/completion-event APIs for permissions and document/media pickers.

### Networking and language ergonomics

- Compose existing Abla HTTP/JSON/contract facilities into an Abla-owned mobile
  RPC/task module; Kotlin must not become the application networking layer.
- Add cancellation, timeouts, size limits, and stale-completion behavior.
- Add template-level reusable components and navigation without weakening the
  no-generated-Kotlin or Abla-owned-state boundary.
- Profile JSON/tree rebuild cost before considering a binary codec or keyed
  render-scope invalidation.

### Other platforms

- Add iOS target/runtime integration and a reusable SwiftUI renderer while
  preserving the same ownership and no-generated-app-source invariants.
