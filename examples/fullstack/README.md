# Full-stack contract example

This example builds a native Abla backend and a Kotlin-free Android
application from one shared `@rpc` contract implementation.

The mobile application uses:

```abla
import contract "../backend/service.ab" as service

fun requestGreeting(name: string): int = $mobileRpc(
    "https://abla-svc.oxente.pt",
    service.greet(name)
)

// Called by the `$mobile` onTap action.
pendingRequest = requestGreeting(name)
```

`$mobileRpc` is an Abla Mobile parser extension. It validates the imported
contract call and generates an Abla request
adapter. The reusable Android host performs HTTPS and posts a copied completion
to the screen's `onHttp` action. All request, response, navigation, and UI state
remains in ordinary captured Abla variables.

The small helper currently keeps one deferred parser extension outside the
other. Directly nesting `$mobileRpc` inside `$mobile` exposes a duplicate-finalizer
compiler bug; it does not require generated Kotlin or a different runtime.

Build the server with `make server`, or deploy it with `icy deploy --watch`.
The checked-in `icy.jsonnet` contains deployment shape only and no secrets.
Build the APK with `make android`.
