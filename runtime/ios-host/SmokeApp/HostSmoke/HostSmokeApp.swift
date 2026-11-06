import SwiftUI
import UIKit
import AblaMobileHost

@main struct HostSmokeApp: App {
    @StateObject private var runtime = MobileRuntimeStore()
    @State private var traits = "secure_traits_waiting"
    @State private var editorBytes = "editor_bytes_other"
    var body: some Scene {
        WindowGroup {
            AblaMobileRoot(store: runtime)
                .background(SecureTraitsProbe(result: $traits, bytes: $editorBytes).frame(width: 0, height: 0))
                .overlay(alignment: .bottom) {
                    VStack {
                        Text(traits).accessibilityIdentifier("fixture.traits")
                        Text(editorBytes).accessibilityIdentifier("fixture.bytes")
                    }
                }
        }
    }
}

// Fixture-only UIKit inspection: assert the real focused native editor's
// traits without copying its text, password, screenshot or accessibility value.
private struct SecureTraitsProbe: UIViewRepresentable {
    @Binding var result: String
    @Binding var bytes: String
    func makeUIView(context: Context) -> ProbeView {
        let view = ProbeView()
        view.report = { result = $0 }
        view.reportBytes = { bytes = $0 }
        return view
    }
    func updateUIView(_ view: ProbeView, context: Context) {}
    @MainActor final class ProbeView: UIView {
        var report: ((String) -> Void)?
        var reportBytes: ((String) -> Void)?
        private var probe: Task<Void, Never>?
        override func didMoveToWindow() {
            super.didMoveToWindow()
            probe?.cancel()
            guard window != nil else { return }
            probe = Task { [weak self] in
                for _ in 0..<1200 {
                    guard !Task.isCancelled, let self else { return }
                    if let field = self.findSecure(in: self.window) {
                        if field.isFirstResponder {
                            self.report?(field.autocorrectionType == .no && field.autocapitalizationType == .none
                                ? "secure_traits_valid" : "secure_traits_invalid")
                        }
                        // Only booleans about two hard-coded fixture values
                        // leave this probe; no text, hashes or lengths are logged.
                        let raw = Array((field.text ?? "").utf8)
                        self.reportBytes?(raw == [0xc3, 0xa9] ? "editor_bytes_nfc" :
                            raw == [0x65, 0xcc, 0x81] ? "editor_bytes_nfd" : "editor_bytes_other")
                    }
                    do { try await Task.sleep(for: .milliseconds(100)) } catch { return }
                }
            }
        }
        private func findSecure(in view: UIView?) -> UITextField? {
            guard let view else { return nil }
            if let field = view as? UITextField, field.isSecureTextEntry { return field }
            for child in view.subviews { if let field = findSecure(in: child) { return field } }
            return nil
        }
    }
}
