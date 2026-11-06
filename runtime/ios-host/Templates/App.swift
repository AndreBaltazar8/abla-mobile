import SwiftUI
import AblaMobileHost

/// Minimal embedding template. Link the matching Abla application slice and
/// the AblaMobileHost package; no fake producer or test probes belong here.
@main struct Application: App {
    @StateObject private var runtime = MobileRuntimeStore()
    var body: some Scene {
        WindowGroup { AblaMobileRoot(store: runtime) }
    }
}
