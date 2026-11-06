import SwiftUI
import AblaMobileHost

@main struct HostSmokeApp: App {
    @StateObject private var runtime = MobileRuntimeStore()
    var body: some Scene {
        WindowGroup { AblaMobileRoot(store: runtime) }
    }
}
