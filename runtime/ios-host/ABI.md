# Provisional iOS ABI v1

`Sources/CAblaMobile/include/abla_mobile_app.h` declares the statically linked
application surface. It reuses the existing native panic/platform/effect/request
C support, plus the iOS version marker. It does not ask the compiler to generate
JNI or dynamic symbol lookup. The Abla application exports `abla_mobile_run`
through its existing `exportFunction`; the checked/panic/platform wrappers are
mobile-owned C objects compiled for the same SDK/target as the application.
The Swift package compiles these support objects beside the final host; the
compiler-produced Abla archive must not include a second copy of them.

Compiler smoke fixture already present: `tests/native/mobile_loop.ab`. It uses
only protocol v1, exports `abla_mobile_run`, exchanges copied UTF-8 payloads and
returns42 after its expected3-tree/two-event sequence. See
`tests/native/mobile_loop.c` for the host protocol driver. It is a compiler/ABI
smoke, not a SwiftUI renderer test. A full protocol-v1 semantic tree fixture and
simulator UI tests will be separate.

Call platform_attach once before checked start. The runtime remains on the one
background thread that called checked start. Its failure readers are thread
local and must be copied on that same thread before publishing a safe UI error.
The host must not call Abla heap functions from the UI/network threads.

The Swift/C host's public queue surface has versioned names and copied
buffers, explicit byte capacities and result codes. Cancellation closes the
host-owned queue and wakes the event wait; it does not kill a runtime thread or
invent a portable Abla cancellation exception. Runtime termination/lifecycle
limits must be explicit. Do not persist or log raw trees containing input data.

The process-owned queue allows 64 pending events and 32 copied platform
messages, with separate 8 MiB byte budgets. Consecutive changes to the same
revision/event may replace the newest queued change; an action is an ordering
barrier. Queue refusal is explicit. Tree snapshots are copied coherently and
limited to 1 MiB. Runtime failure publishes fixed numeric reasons, not raw panic
contents. Shutdown wakes the event wait cooperatively and clears copied
snapshots, queued messages and events after the application returns. It cannot
interrupt a stuck application computation and does not permit runtime restart.

`Tests/NativeBridge` links a deliberately fake application producer to the real
C bridge and existing support objects. It proves transport boundaries without
claiming an Abla runtime or Simulator UI execution. SwiftUI target compilation
and a real Abla-linked Simulator journey are separate gates.

Every iOS application entry imports `src/ios_runtime.ab` (the One iOS backend
does so). It exports `abla_mobile_application_protocol_version` **from the
application archive**. A missing query is a link failure; a mismatched version
is refused before the application run begins. The query executes on the same
runtime thread, inside the iOS checked panic boundary: an exported Abla query
may initialize globals and must not do so on UIKit's thread. UI/event admission
and tree publication remain closed until the query succeeds. The separate host-owned iOS ABI marker
does not substitute for this application query. The iOS loop build fixture is
`tests/native/mobile_loop_ios.ab`, which adds it to the generic loop fixture.
