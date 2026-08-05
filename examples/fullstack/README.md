# Full-stack contract example

This example builds a native Abla backend and a Kotlin-free Android
application from one shared `@rpc` contract implementation.

The mobile application uses:

```abla
import contract "../backend/service.ab" as service
import "../../../src/rpc.ab"

mobileRun($mobile
    <Screen title="Full stack">
        <Button onTap={
            pendingRequest = $mobileRpc(
                "https://abla-svc.oxente.pt",
                service.greet(name)
            )
        }>Call backend</Button>
    </Screen>
)
```

`$mobileRpc` is an Abla Mobile parser extension. It validates the imported
contract call and generates an Abla request
adapter. The reusable Android host performs HTTPS and posts a copied completion
to the screen's `onHttp` action. All request, response, navigation, and UI state
remains in ordinary captured Abla variables.

`$mobileRpc` is nested directly inside the `$mobile` action. The compiler's
general staged-parser declaration mapping keeps both generated adapters
distinct; the application still needs no generated Kotlin or separate helper
module.

Build the server with `make server`, or deploy it with `icy deploy --watch`.
The checked-in `icy.jsonnet` contains deployment shape only and no secrets.
Build the APK with `make android`.
