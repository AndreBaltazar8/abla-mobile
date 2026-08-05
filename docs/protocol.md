# Abla Mobile protocol v1

Protocol v1 connects one process-long Abla application to one Android host. It
has three channels: a synchronous scalar command call, a bounded native event
queue, and a bounded native platform-effect queue.

No protocol value is an Abla pointer or handle.

## App/host attachment

Before calling `abla_mobile_run()`, the bridge resolves and invokes:

```c
int64_t abla_mobile_platform_attach(
    int64_t (*host_call)(void *context, int64_t value),
    void *context
);
```

The function/context pair belongs to the fixed native host. It is retained by
the app-side mobile transport and is invisible to the Abla program and Kotlin.
Attachment happens once per process.

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

Command 3 drains pending platform effects and then waits on the event condition
variable. Once it returns an event ID, commands 4–6 read the copied current
event. Payload reads beyond the announced size return zero.

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

## Versioning

An incompatible semantic tree change increments the top-level `protocol`
integer. A host rejects versions it does not implement. New scalar commands or
effect kinds require coordinated app-runtime and host versions and must remain
outside existing byte ranges.

A later binary representation may replace JSON only with a new negotiated
protocol version and equivalent validation/ownership limits.
