import SwiftUI
import UIKit
import AblaMobileHost

@main struct HostSmokeApp: App {
    @StateObject private var runtime = MobileRuntimeStore()
    @State private var traits = "secure_traits_waiting"
    @State private var editorBytes = "editor_bytes_other"
    @State private var ordinaryBytes = "ordinary_bytes_other"
    @State private var multilineBytes = "multiline_bytes_other"
    var body: some Scene {
        WindowGroup {
            AblaMobileRoot(store: runtime)
                .background(SecureTraitsProbe(result: $traits, bytes: $editorBytes, ordinary: $ordinaryBytes, multiline: $multilineBytes).frame(width: 0, height: 0))
                .overlay(alignment: .bottom) {
                    VStack {
                        Text(traits).accessibilityIdentifier("fixture.traits")
                        Text(editorBytes).accessibilityIdentifier("fixture.bytes")
                        Text(ordinaryBytes).accessibilityIdentifier("fixture.ordinary")
                        Text(multilineBytes).accessibilityIdentifier("fixture.multiline")
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
    @Binding var ordinary: String
    @Binding var multiline: String
    func makeUIView(context: Context) -> ProbeView {
        let view = ProbeView()
        view.report = { result = $0 }
        view.reportBytes = { bytes = $0 }
        view.reportOrdinary = { ordinary = $0 }
        view.reportMultiline = { multiline = $0 }
        return view
    }
    func updateUIView(_ view: ProbeView, context: Context) {}
    @MainActor final class ProbeView: UIView {
        var report: ((String) -> Void)?
        var reportBytes: ((String) -> Void)?
        var reportOrdinary: ((String) -> Void)?
        var reportMultiline: ((String) -> Void)?
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
                    if let field = self.findOrdinary(in: self.window) {
                        let raw = Array((field.text ?? "").utf8)
                        self.reportOrdinary?(raw == [0xc3, 0xa9] ? "ordinary_bytes_nfc" :
                            raw == [0x65, 0xcc, 0x81] ? "ordinary_bytes_nfd" : "ordinary_bytes_other")
                    }
                    if let text = self.findMultiline(in: self.window) {
                        let raw = Array(text.utf8)
                        self.reportMultiline?(raw == [0xc3, 0xa9, 10, 77] ? "multiline_bytes_nfc" :
                            raw == [0x65, 0xcc, 0x81, 10, 77] ? "multiline_bytes_nfd" : "multiline_bytes_other")
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
        private func findOrdinary(in view: UIView?) -> UITextField? {
            guard let view else { return nil }
            if let field = view as? UITextField, !field.isSecureTextEntry,
               field.accessibilityIdentifier == "ordinary" { return field }
            for child in view.subviews { if let field = findOrdinary(in: child) { return field } }
            return nil
        }
        private func findMultiline(in view: UIView?) -> String? {
            guard let view else { return nil }
            if view.accessibilityIdentifier == "multiline", let text = firstPublicText(in: view) { return text }
            for child in view.subviews { if let text = findMultiline(in: child) { return text } }
            return nil
        }
        private func firstPublicText(in view: UIView) -> String? {
            if let field = view as? UITextField, !field.isSecureTextEntry { return field.text ?? "" }
            if let field = view as? UITextView { return field.text ?? "" }
            for child in view.subviews { if let text = firstPublicText(in: child) { return text } }
            return nil
        }
    }
}
