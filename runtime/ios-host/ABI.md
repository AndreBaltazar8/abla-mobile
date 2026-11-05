# Provisional iOS ABI v1

`Sources/CAblaMobile/include/abla_mobile_app.h` declares the statically linked
application surface. It reuses the existing native panic/platform/effect/request
C support, plus the iOS version marker. It does not ask the compiler to generate
JNI or dynamic symbol lookup. The Abla application exports `abla_mobile_run`
through its existing `exportFunction`; the checked/panic/platform wrappers are
mobile-owned C objects compiled for the same SDK/target as the application.

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

The Swift/C host's public queue surface will have versioned names and copied
buffers, explicit byte capacities and result codes. Cancellation closes the
host-owned queue and wakes the event wait; it does not kill a runtime thread or
invent a portable Abla cancellation exception. Runtime termination/lifecycle
limits must be explicit. Do not persist or log raw trees containing input data.
