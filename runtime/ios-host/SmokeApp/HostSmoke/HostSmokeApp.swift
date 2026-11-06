import SwiftUI
import UIKit
import AblaMobileHost

@main struct HostSmokeApp: App {
    @StateObject private var runtime = MobileRuntimeStore()
    @State private var traits = "secure_traits_waiting"
    var body: some Scene {
        WindowGroup {
            AblaMobileRoot(store: runtime)
                .background(SecureTraitsProbe(result: $traits).frame(width: 0, height: 0))
                .overlay(alignment: .bottom) { Text(traits).accessibilityIdentifier("fixture.traits") }
        }
    }
}

// Fixture-only UIKit inspection: assert the real focused native editor's
// traits without copying its text, password, screenshot or accessibility value.
private struct SecureTraitsProbe: UIViewRepresentable {
    @Binding var result: String
    func makeUIView(context: Context) -> ProbeView {
        let view = ProbeView()
        view.report = { result = $0 }
        return view
    }
    func updateUIView(_ view: ProbeView, context: Context) {}
    @MainActor final class ProbeView: UIView {
        var report: ((String) -> Void)?
        private var probe: Task<Void, Never>?
        override func didMoveToWindow() {
            super.didMoveToWindow()
            probe?.cancel()
            guard window != nil else { return }
            probe = Task { [weak self] in
                for _ in 0..<1200 {
                    guard !Task.isCancelled, let self else { return }
                    if let field = self.findFocused(in: self.window) {
                        self.report?(field.isSecureTextEntry && field.autocorrectionType == .no &&
                            field.autocapitalizationType == .none ? "secure_traits_valid" : "secure_traits_invalid")
                        return
                    }
                    do { try await Task.sleep(for: .milliseconds(100)) } catch { return }
                }
            }
        }
        private func findFocused(in view: UIView?) -> UITextField? {
            guard let view else { return nil }
            if let field = view as? UITextField, field.isFirstResponder { return field }
            for child in view.subviews { if let field = findFocused(in: child) { return field } }
            return nil
        }
    }
}
