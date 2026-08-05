# Abla Mobile

Abla Mobile is a proposed native-mobile framework for the
[Abla](https://github.com/AndreBaltazar8/ablac) language. Android is the first
target; iOS follows after the required Mach-O/iOS target support exists in the
compiler.

The framework is intended to provide:

- a process-owned Abla application that owns all business and UI-driving state;
- any number of ordinary Abla variables captured by `$mobile` render/event
  closures, with no required root state object;
- automatic whole-root rerendering after local events and managed async
  completions;
- a reusable precompiled Jetpack Compose host that renders bounded semantic UI
  tree revisions published by Abla;
- a fixed Android/iOS boundary with no foreign-owned Abla application, state,
  object, or callback handles;
- application packaging with no generated Kotlin or Swift source;
- Abla-owned, schema-checked RPC clients plus generated Abla server routes; and
- optional user-owned Kotlin/Java/C/C++ and Swift/Objective-C escape hatches.

The Android vertical slice is implemented: an Abla-owned event loop publishes
semantic trees to a reusable Compose host, receives events, and rerenders while
keeping independent variables inside Abla. Application modules contain no
Kotlin source. The mobile runtime API lives in `src/runtime.ab`; Android
allocation, collection, panic policy, and the target ABI adapter live under
`runtime/` and are linked by the application template. `../ablac` is consumed
unchanged.

The architecture and wire contracts are defined in
[the design specification](docs/design-spec.md). Remaining work and release
gates are tracked in the [implementation plan](plan.md).
