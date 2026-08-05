# Abla Mobile protocol v1

Protocol v1 connects one process-long Abla application to one Android host. It
has four channels: a synchronous scalar command call, a bounded native event
queue, a bounded native platform-effect queue, and a bounded platform-request
queue.

No protocol value is an Abla pointer or handle.

## App/host attachment

Before calling the checked application entrypoint, the bridge resolves and
invokes:

```c
int64_t abla_mobile_platform_attach(
    int64_t (*host_call)(void *context, int64_t value),
    void *context
);
```

The function/context pair belongs to the fixed native host. It is retained by
the app-side mobile transport and is invisible to the Abla program and Kotlin.
Attachment happens once per process.

The bridge then calls this fixed ABI:

```c
int64_t abla_mobile_run_checked(void);
int64_t abla_mobile_failure_size(void);
int64_t abla_mobile_failure_byte(int64_t index);
```

The checked entry returns `1` for an explicit Abla runtime panic, `0` if the
application entry unexpectedly returns, and `-1` for reentrant invocation.
After a panic, the bridge copies at most 1,024 diagnostic bytes through the two
scalar accessors before publishing the failure. No diagnostic pointer crosses
the app/host boundary. This boundary deliberately does not recover native
memory faults, signals, or failures on unrelated threads.

Native failures are reported to Kotlin as one of four categories: startup,
protocol, runtime panic, or unexpected return. Kotlin adds a fifth platform
category for failures detected by the reusable Android host itself. Once the
application entry stops, the bridge rejects further events.

## Scalar commands

Abla calls `abla_mobile_host_call(int64_t)` through wrappers in
`src/protocol.ab`.

| Value | Meaning | Result |
| ---: | --- | --- |
| 1 | begin tree | `0` or negative error |
| 2 | finish and publish tree | `0` or negative error |
| 3 | block for next event | event ID |
| 4 | current event revision | revision |
| 5 | current event payload size | byte count |
| 6 | consume next payload byte | `0..255` |
| `256..511` | append tree byte `value - 256` | `0` or negative error |

A tree publication begins with command 1, sends every encoded byte, and ends
with command 2. The native buffer is reset at begin and limited to 1 MiB. The
host copies the completed byte array into Kotlin before parsing.

Command 3 drains pending platform effects and HTTP requests and then waits on
the event condition variable. Once it returns an event ID, commands 4–6 read
the copied current event. Payload reads beyond the announced size return zero.

## Event queue

Compose enqueues:

```text
revision: signed 64-bit positive tree revision
identifier: signed 64-bit positive semantic event ID
payload: 0..4096 copied UTF-8 bytes
```

The FIFO contains at most 64 entries. Enqueueing while full returns false and
the host displays a bounded failure. The Abla preview accepts positive
revisions no newer than its last published revision; this permits queued text
composition and slider changes while later trees reach Compose.

`$mobile` assigns positive identifiers to action attributes in deterministic
source traversal order and embeds those identifiers in the corresponding
nodes. Direct builder users may supply their own positive semantic IDs. Event
zero is reserved for host shutdown and is never assigned to an interactive
template element.

Identifier `-2` is reserved for a platform HTTP completion. Its revision is
independent of a particular presentation and its payload is bounded JSON with
`requestId`, `status`, `body`, and `error`. `$mobile` dispatches it only to an
optional `Screen.onHttp` action.

## Tree JSON

The root object is:

```json
{
  "protocol": 1,
  "revision": 1,
  "title": "Application title",
  "darkTheme": false,
  "root": {
    "type": "column",
    "key": "root",
    "properties": {},
    "children": []
  }
}
```

Every node has exactly the semantic fields `type`, `key`, `properties`, and
`children`. Properties must be JSON scalars. Protocol v1 supports these node
types and properties:

| Type | Properties |
| --- | --- |
| `column` | `padding`, `spacing`, `scroll` |
| `row` | `spacing` |
| `text` | `text`, `style` |
| `button` | `text`, `event`, `enabled`, `appearance` |
| `textField` | `label`, `value`, `event` |
| `switch` | `label`, `checked`, `event` |
| `checkbox` | `label`, `checked`, `event` |
| `slider` | `label`, `value`, `minimum`, `maximum`, `event` |
| `progress` | `value`, `maximum`, `circular` |
| `divider` | none |
| `spacer` | `height` |
| `card` | `title` |
| `chip` | `text`, `selected`, `event`, `payload` |
| `list` | none |

Sibling keys must be unique. Unknown node kinds are rejected for protocol 1.
The host validates all structural limits listed in the design specification
before composing the tree.

## Platform effects

Abla calls `abla_mobile_platform_effect(kind, cstring)` directly. The app-side
runtime copies the payload before returning and reports `1` for success or `0`
for an invalid kind, oversized payload, null input, or full queue.

| Kind | Meaning |
| ---: | --- |
| 1 | short toast text |
| 2 | clipboard plain text |
| 3 | `http`/`https` browser URI |
| 4 | confirmation haptic |

The bridge drains effects at the next event wait through fixed dlsym-resolved
query functions, publishes copied data into a Kotlin `SharedFlow`, and executes
the adapter on the main thread.

## Platform HTTP requests

Abla queues a copied method, URL, and body and immediately receives a positive
request ID. The app-side FIFO holds 32 requests; methods are at most 8 bytes,
URLs 2 KiB, and bodies 16 KiB. The fixed bridge drains requests at event wait
and invokes the reusable host with copied byte arrays.

The host accepts `GET` and `POST` over `http`/`https`, does not follow redirects,
uses 10-second connect/read timeouts, and caps response data at 3 KiB. A worker
posts the completion through the same 64-entry event FIFO. No request contains
an Abla callback, closure, object, or state pointer.

## Versioning

An incompatible semantic tree change increments the top-level `protocol`
integer. A host rejects versions it does not implement. New scalar commands or
effect kinds require coordinated app-runtime and host versions and must remain
outside existing byte ranges.

A later binary representation may replace JSON only with a new negotiated
protocol version and equivalent validation/ownership limits.
