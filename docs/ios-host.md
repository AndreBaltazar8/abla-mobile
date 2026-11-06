# iOS host: implementation preview

This feature branch contains a SwiftUI host for protocol v1. It is not an iOS
release, an App Store submission, or proof on a physical device. Android-only
callers retain the existing entry point; iOS build helpers are explicit package
submodules and require ablac0.2.47 or newer.

## Build boundary

Package consumers use an explicit revision-pinned provider, for example:

```abla
compile fun mobileSource(): ImportSource = gitImportSource(
    "abla-mobile", "https://github.com/AndreBaltazar8/abla-mobile.git", "rev",
    "65555622d38f8db8a614b512126db07da0dddc9e")
import importSubpath(mobileSource(), "ios_slices")
```

The revision above is an implementation-preview feature commit, not a stable
release. A bare `import "abla-mobile/ios_slices"` is an importer-relative path;
it does not resolve a named package automatically. All providers requesting
`abla-mobile` in one graph must use the same source and revision.

The `ios_slices` subpath exposes
`mobileBuildIosSlices(name, entry, outputDirectory, fast = true, cache = true)`.
It registers both targets and stages two static-library builds:

- `ios-arm64/libabla_app.a`: device, minimum iOS17.
- `ios-arm64-simulator/libabla_app.a`: arm64 Simulator, minimum iOS17.

The `ios_build` subpath additionally exposes
`mobileIosConfiguration(name, entry, outputDirectory, headersDirectory)` and
`mobileBuildIos(configuration)`. The default configuration requests fast,
cached builds and `AblaApp.xcframework` packaging. The compiler packages only
after both child archives succeed. A true compile-time return means the build
request was accepted, not that the artifacts already exist. Packaging requires
macOS/Xcode; Linux receives `E_IOS_XCFRAMEWORK_HOST`. The slices-only helper
does not request packaging.

Register targets and stage builds in the same compile function, rather than
separate evaluated globals. SDK overrides are `ABLA_APPLE_SDK_IPHONEOS` and
`ABLA_APPLE_SDK_IPHONESIMULATOR`. Each object, including the Darwin adapter,
must target the matching iOS platform; a macOS object is not an iOS substitute.

An application imports the `ios_runtime` subpath to export its protocol query,
and exports its long-lived entry as `abla_mobile_run`. See
`tests/native/mobile_loop_ios.ab` and One's `tests/ios_backend_exports.ab`.
Use `runtime/ios-host/AppHeaders` as the application archive headers directory.
The Swift package supplies the separate C host/support objects. The compiler
archive contains the Abla application/runtime and Darwin adapter, not the host.

## Host contract

The `runtime/ios-host` Swift package provides `AblaMobileRoot` and
`MobileRuntimeStore`; `Templates/App.swift` is a minimal embedding template.
The sample Xcode project demonstrates linkage. Its fake
producer is a component fixture, and must be excluded when linking an actual
application archive.

The host owns one background runtime thread, while SwiftUI and platform services
run on the main actor. Both the application protocol query and the application
entry execute inside the checked boundary on that same runtime thread. Do not
call the query from UIKit: exported Abla functions can initialize globals.
All buffers crossing the ABI are copied, bounded pointer/length pairs. No host
view retains an Abla pointer. The process singleton cannot restart after stop.

Events retain the revision and route identity of the rendered view. Valid
older events in the same presentation are passed unchanged to One's existing
event-map fence. Future revisions and old-route actions are refused. Root/route
identity changes clear pending editor state. UTF-8 byte identity is used for
node keys and editor replacements; Swift's canonical String equivalence must
not merge distinct application values. Secure fields disable correction,
capitalization and spelling suggestions. Secure multiline explicitly refuses.

Snapshot, event, request and response limits are explicit in the C header and
Swift models. Full queues refuse admission rather than silently dropping or
replaying actions. Failure readers expose fixed codes; snapshots and editor
values are not logged.

## Platform services and open gates

The current implementation includes timer completion, explicit Keychain
STOREGET/STORESET/STOREDEL, system document picking into an opaque bounded vault,
and URLSession HTTPS with system trust and redirects disabled. Network errors
return a fixed outcome-unknown code, with no host retry. Application body,
credentials, URLs and raw error descriptions are not placed in errors.

Multipart, file-backed request bodies, binary/save-to-file responses, rich
media, native games, WebView, native file-save/share effects and complete
styling/accessibility parity are not
implemented. Unsupported services/components refuse visibly. Platform-service
journeys are a separate CI gate; source compilation does not establish them.
No ordinary input is implicitly persisted in Keychain or editor recovery.

### Renderer coverage at this checkpoint

This is a source inventory, not a claim that every row has an end-to-end test.
The implementation lives in [OneRenderer.swift](../runtime/ios-host/Sources/AblaMobileHost/OneRenderer.swift)
and [OneDecoration.swift](../runtime/ios-host/Sources/AblaMobileHost/OneDecoration.swift).

| Contract | Current implementation and remaining gate |
| --- | --- |
| Columns/rows/stacks, vertical/horizontal scroll | Native SwiftUI containers with stable byte-based keys; layout/keyboard/rotation parity needs dedicated journeys. |
| Feed | Bounded decoded tree rendered eagerly; lazy paging/scroll-position parity remains open. |
| Text, spacer, divider | Basic native rendering; rich spans, icons, images and bitmaps refuse as unsupported. |
| Pressable/link | Native enabled state, revision-bound action, pressed decoration; actual disabled-state journey passes. |
| Input | Secure/ordinary single-line and ordinary multiline NFC/NFD replacement plus typed newline passed in the actual One app. Composition/paste/selection remain open; secure multiline refuses explicitly. |
| Styling | Padding, bounded dimensions, fill, colors, borders/radius, opacity and pressed/focused/disabled/selected colors; not a full Compose-style implementation. |
| Navigation | Application route-entry changes rebuild presentation identity and clear pending edits. Native back-stack/gesture mapping still needs an explicit contract and journey. |
| Native fallback | Last portable child is rendered when supplied; game/WebView/media adapters are not implemented. |
| Popover/accessibility | Popover is unsupported; leaf identifiers and native field/button semantics exist, but VoiceOver order/actions and complete dynamic-type parity need their own acceptance. |

Before a stable iOS release, validate composition/paste/selection, keyboard
avoidance/rotation, native navigation/back and lifecycle
transitions on the actual application. Simulator success does not replace
physical-device memory, provisioning or usability checks.

## Evidence

- [42143525951](https://github.com/AndreBaltazar8/abla-mobile/actions/runs/42143525951):
  actual One application archives, One44/44 and two Simulator journeys on
  compiler0ee1fac6; fake producer excluded from the app link.
- [42148143605](https://github.com/AndreBaltazar8/abla-mobile/actions/runs/42148143605):
  same application/host inputs, compiler1476e951 with packaging/capability
  integration; One44/44 and both actual application journeys passed.
- [42146689330](https://github.com/AndreBaltazar8/abla-mobile/actions/runs/42146689330):
  failed component regression: explicit NFC-to-NFD model replacement did not
  reach the native editor bytes.
- [42148217613](https://github.com/AndreBaltazar8/abla-mobile/actions/runs/42148217613):
  corrected exact-byte secure editor, model/ABI/SwiftUI gates and all three
  component journeys passed. This result uses a fake producer.
- [42149851610](https://github.com/AndreBaltazar8/abla-mobile/actions/runs/42149851610):
  actual Abla One application also passes the exact Unicode journey, plus the
  two earlier journeys and all44 One contract suites on compiler1476e951.

The first platform-service run42149399602 failed Keychain deletion and cold
picker presentation. Run42151222532 passed the Keychain roundtrip after giving
the disposable Simulator fixture explicit entitlements, but exposed a pending
request after picker cancellation; it remains failed. The correction uses
SwiftUI's separate iOS17 `onCancellation` callback. Neither failed run reached
the local HTTPS acceptance steps. The Simulator's linked entitlement section
is distinct from the ad-hoc signature's entitlement dictionary.

[42152645707](https://github.com/AndreBaltazar8/abla-mobile/actions/runs/42152645707)
passed the corrected service gate: five ordinary Simulator journeys (including
Keychain roundtrip and picker cancellation), unknown-CA refusal before installing
a disposable CA, then trusted HTTPS/redirect refusal/lost-answer handling.
The peer recorded one successful GET, one302 and one POST whose answer was lost,
with zero redirected requests or replays. Linked Simulator entitlements were
read from the actual Mach-O section; malformed-reader controls were refused.
This gate uses a fake application producer with the real host/platform services.

See Apple's [fileImporter cancellation API](https://developer.apple.com/documentation/swiftui/view/fileimporter(ispresented:allowedcontenttypes:allowsmultipleselection:oncompletion:oncancellation:))
and [Keychain entitlement diagnosis](https://developer.apple.com/documentation/security/errsecmissingentitlement).
The fixture identity is local to the disposable Simulator. It supplies no Apple
developer team or device provisioning profile.

Released ablac0.2.47 acceptance is recorded separately:

- [42156991980](https://github.com/AndreBaltazar8/abla-mobile/actions/runs/42156991980)
  passes ordinary and secure single-line byte replacement, route fencing,
  Keychain/picker, both local HTTPS cases and missing-entitlement refusal.
  The HTTPS fixture uses a fresh trusted Simulator plus independent peer
  liveness checks; host trust, deadlines and no-replay behavior are unchanged.
- [42159504541](https://github.com/AndreBaltazar8/abla-mobile/actions/runs/42159504541)
  passes the actual One application:44 One contracts, device/Simulator archives,
  exact two-slice xcframework/headers/module maps/Swift import, and three UI
  journeys including ordinary and secure NFC-to-NFD replacement. Inputs are
  Mobile65555622 and One3ee4793b. The fake producer is excluded from this app.

The ordinary-field control42155531277 remains failed: its NFC replacement
arrived, but NFD did not. This prompted the common byte-exact single-line UIKit
editor. A prior services rerun42153788841 timed out before any HTTP request
reached the local peer and remains failed; it is not relabelled by later passes.
The multiline control
[42159655067](https://github.com/AndreBaltazar8/abla-mobile/actions/runs/42159655067)
failed specifically at NFD after NFC/newline bytes arrived; the other five
ordinary journeys passed. Candidate b167a852 uses a byte-aware UITextView,
retains actual newlines and bounds the visible height with native scrolling.
The correction was subsequently accepted by the actual-app runs below; the
original failed control remains part of the evidence.

[42160960015](https://github.com/AndreBaltazar8/abla-mobile/actions/runs/42160960015)
accepted multiline replacement through the actual One application (44 contracts,
exact xcframework and four UI journeys). Then
[42162978605](https://github.com/AndreBaltazar8/abla-mobile/actions/runs/42162978605)
accepted native typed-newline delivery as well: all five actual-app journeys
passed on released0.2.47, Mobile5c07ea41 and One5e9dcab7.

The component revalidation42163527083 remains failed: seven ordinary UI cases
and unknown-CA refusal passed, while trusted HTTPS reached a fixed failure
phase. The peer saw one new TCP connection and one TLS error, no completed TLS
handshake and no HTTP. The action was delivered; the precise TLS refusal is
unproved. This is separate from the actual application gate.

Candidate715930b adds a fixture-only OS trust precondition using the generated
public chain and the normal SSL hostname policy. It applies no custom anchor,
exception or date override. The probe runs off the UI thread, following Apple's
[trust-evaluation guidance](https://developer.apple.com/documentation/security/sectrustevaluatewitherror(_:_:)),
with [certificate network fetching disabled](https://developer.apple.com/documentation/security/sectrustsetnetworkfetchallowed(_:_:)).
Only fixed status codes and peer TLS error codes persist. The production
URLSession client, trust policy, request deadline and no-replay behavior remain
unchanged. Its combined run42167180679 did not complete within the job budget:
the fixed OS-trust marker passed, while the later request failed and the peer
recorded ECONNRESET. Severe Simulator startup/automation delays preceded the
probe; this does not establish a trust-policy failure.

Focused run42170822457 passed unknown-CA refusal, then failed the independent
host health request before the trusted app testcase. Candidate3abbd5be limits
the diagnostic job to one booted Simulator at a time and records host memory
and boot states. Run42173007448 is pending. It changes no production code or
client policy, and its fixture hypothesis is unproved.

Simulator memory and successful archive linkage do not establish physical
iPhone VM limits, device provisioning or signing acceptance. Build/sign/install
on a device and publication need their own approval and evidence.
