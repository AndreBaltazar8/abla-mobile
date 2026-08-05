# Mobile HTTP and contract RPC

Abla Mobile supports result-bearing HTTPS work without moving application
state into Kotlin. `mobileHttpRequest`, `mobileHttpGet`, and `mobileHttpPost`
copy a bounded request into the native platform queue and return an integer
request ID. The reusable Android host performs the request and sends one
serialized completion event back through the ordinary Abla event loop.

A screen handles completions with `onHttp`:

```abla
var pending = 0
var message = "Ready"
var completion = MobileHttpResult(0, 0, "", "", false)

mobileRun($mobile
    <Screen title="Network example" onHttp={
        completion = mobileHttpResult(value);
        if (completion.valid && completion.requestId == pending) {
            pending = 0;
            message = completion.body
        }
    }>
        <Column padding={20} spacing={12}>
            <Button onTap={pending = mobileHttpGet(
                "https://example.com/message"
            )}>Load</Button>
            <Text>{message}</Text>
        </Column>
    </Screen>
)
```

`$mobileRpc` is a library-owned parser extension, not built-in Abla syntax.
It consumes the metadata-only call supplied by `import contract`, verifies the
target carries `@rpc`, and generates an Abla request adapter:

```abla
import contract "../backend/service.ab" as service
import "../../../src/rpc.ab"

mobileRun($mobile
    <Screen title="RPC">
        <Button onTap={
            pending = $mobileRpc(
                "https://abla-svc.oxente.pt",
                service.greet(name)
            )
        }>Call service</Button>
    </Screen>
)
```

The backend imports the same implementation normally. `src/rpc_server.ab`
discovers its annotated functions and writes an Abla HTTP router. The current
first rung deliberately supports `@rpc (string) -> string`; JSON data shapes,
versioned errors, authentication, cancellation, and additional parameter/result
types remain follow-up work.

Parser extensions can be composed directly: `$mobileRpc` may appear inside a
`$mobile` action as shown above. `ablac` preserves stable declaration handles
when an outer raw subparser expansion reveals nested generated declarations;
the compiler fixture and the full-stack mobile build cover this composition.

`examples/fullstack` contains the complete shared contract, generated server,
Kotlin-free Android client, proprietary `icy` deployment configuration, and
repeatable local/build/device tests. The deployed example is available at
`https://abla-svc.oxente.pt`.

## Limits and security

- methods are restricted to `GET` and `POST`, and schemes to `http`/`https`;
- requests are limited to 32 queued entries, 2 KiB URLs, and 16 KiB bodies;
- the host uses four bounded workers with 10-second connect/read timeouts;
- response bodies are limited to 3 KiB and completion payloads to 4 KiB;
- redirects are not followed automatically;
- Android's normal TLS validation is retained; there is no trust bypass; and
- request IDs and copied bytes cross JNI, never an Abla callback or state
  pointer.
