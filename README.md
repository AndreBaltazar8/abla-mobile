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

This repository is currently in the design stage. The architecture, proposed
API, wire contracts, compiler prerequisites, and implementation sequence are
defined in [the design specification](docs/design-spec.md). Concrete work
breakdown, dependencies, milestones, and release gates are tracked in the
[implementation plan](plan.md).
