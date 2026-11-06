import SwiftUI
import UIKit
import Security
import AblaMobileHost

@main struct HostSmokeApp: App {
    @StateObject private var runtime = MobileRuntimeStore()
    @State private var traits = "secure_traits_waiting"
    @State private var editorBytes = "editor_bytes_other"
    @State private var ordinaryBytes = "ordinary_bytes_other"
    @State private var multilineBytes = "multiline_bytes_other"
    @State private var systemTrust = "system_trust_not_requested"
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
                        Text(systemTrust).accessibilityIdentifier("fixture.systemTrust")
                    }
                    .allowsHitTesting(false)
                }
                .task {
                    guard ProcessInfo.processInfo.environment["ABLA_HOST_TEST_TRUST_PROBE"] == "1" else { return }
                    guard let url = Bundle.main.url(forResource: "FixtureTrust", withExtension: "json"),
                          let data = try? Data(contentsOf: url), data.count <= 16384 else {
                        systemTrust = "system_trust_fixture_missing"; return
                    }
                    systemTrust = await Task.detached { FixtureTrustProbe.evaluate(data) }.value
                }
        }
    }
}

// Fixture-only OS trust readback. The public chain helps chain construction;
// it is NOT installed as a custom anchor and no trust exception is applied.
// Evaluation stays off the UI thread and cannot fetch missing certificates.
private enum FixtureTrustProbe {
    nonisolated static func evaluate(_ data: Data) -> String {
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: String],
              let leafText = json["leaf"], let rootText = json["root"],
              leafText.utf8.count <= 8192, rootText.utf8.count <= 8192,
              let leafData = Data(base64Encoded: leafText), let rootData = Data(base64Encoded: rootText),
              let leaf = SecCertificateCreateWithData(nil, leafData as CFData),
              let root = SecCertificateCreateWithData(nil, rootData as CFData) else {
            return "system_trust_fixture_invalid"
        }
        var trust: SecTrust?
        let created = SecTrustCreateWithCertificates([leaf, root] as CFArray,
            SecPolicyCreateSSL(true, "localhost" as CFString), &trust)
        guard created == errSecSuccess, let trust else { return "system_trust_setup_\(created)" }
        let configured = SecTrustSetNetworkFetchAllowed(trust, false)
        guard configured == errSecSuccess else { return "system_trust_setup_\(configured)" }
        var error: CFError?
        if SecTrustEvaluateWithError(trust, &error) { return "system_trust_valid" }
        return "system_trust_refused_\(error.map { CFErrorGetCode($0) } ?? 0)"
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
