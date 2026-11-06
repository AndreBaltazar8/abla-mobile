#if os(iOS)
import Foundation
import SwiftUI
import UIKit
import UniformTypeIdentifiers
import AblaMobileModel
import CAblaMobile

/// Main-thread ownership of copied snapshots. The Abla heap and event loop live
/// exclusively on the C bridge's runtime thread. No tree/editor data is logged.
@MainActor public final class MobileRuntimeStore: ObservableObject {
    @Published public private(set) var tree: OneTree?
    @Published public private(set) var failure: String?
    @Published public private(set) var notice: String?
    @Published public private(set) var pickingFile = false
    public private(set) var fileTypes: [UTType] = [.data]
    public private(set) var multipleFiles = false
    private var pickerCompletion: (([URL]?) -> Void)?
    private var sequence: Int64 = 0
    private var polling: Task<Void, Never>?
    private var services: IosPlatformServices?
    private var buffer = [UInt8](repeating: 0, count: Int(ABLA_IOS_TREE_LIMIT))
    public init() {}
    public func start() {
        guard polling == nil else { return }
        let started = abla_ios_start_v1()
        guard started == 1 || (started == 0 && abla_ios_state_v1() == Int64(ABLA_IOS_RUNNING.rawValue)) else {
            failure = "runtime_start_refused"; return
        }
        services = IosPlatformServices(deliver: { data in
            data.withUnsafeBytes {
                abla_ios_completion_v1($0.bindMemory(to: UInt8.self).baseAddress, Int64($0.count)) == 1
            }
        }, pick: { [weak self] mimes, multiple, completion in
            guard let self, self.pickerCompletion == nil else { return false }
            let types = mimes.compactMap { $0 == "*/*" ? UTType.data : UTType(mimeType: $0) }
            guard !types.isEmpty else { return false }
            self.fileTypes = types; self.multipleFiles = multiple
            self.pickerCompletion = completion; self.pickingFile = true
            return true
        })
        polling = Task { [weak self] in
            while !Task.isCancelled {
                self?.readSnapshot()
                try? await Task.sleep(for: .milliseconds(16))
            }
        }
    }
    public func stop() {
        polling?.cancel(); polling = nil
        services?.stop(); services = nil
        pickingFile = false; pickerCompletion = nil
        tree = nil; buffer = [UInt8](repeating: 0, count: buffer.count)
        abla_ios_shutdown_v1()
    }
    public func setFilePickerPresented(_ presented: Bool) { pickingFile = presented }
    public func filesPicked(_ result: Result<[URL], Error>) {
        let completion = pickerCompletion; pickerCompletion = nil; pickingFile = false
        switch result {
        case .success(let urls): completion?(urls)
        case .failure: completion?(nil)
        }
    }
    @discardableResult public func send(_ identifier: Int64, revision: Int64, identity: String,
                                       payload: String = "", change: Bool = false) -> Bool {
        guard tree?.admitsEvent(revision: revision, identity: identity, identifier: identifier) == true,
              failure == nil,
              payload.utf8.count <= Int(ABLA_IOS_EVENT_LIMIT) else { return false }
        let result = Array(payload.utf8).withUnsafeBufferPointer {
            abla_ios_event_v1(revision, identifier, $0.baseAddress, Int64($0.count), change ? 1 : 0)
        }
        if result == 0 { notice = "event_queue_full" }
        else if result == 1 && notice == "event_queue_full" { notice = nil }
        return result == 1
    }
    private func readSnapshot() {
        guard failure == nil else { return }
        if abla_ios_state_v1() == Int64(ABLA_IOS_FAILED.rawValue) {
            fail("runtime_failed_\(abla_ios_failure_v1())"); return
        }
        services?.flush()
        drainMessages()
        guard failure == nil else { return }
        var next: Int64 = sequence
        let count = buffer.withUnsafeMutableBufferPointer {
            abla_ios_tree_copy_v1(sequence, $0.baseAddress, Int64($0.count), &next)
        }
        guard count >= 0 else { fail("tree_copy_refused"); return }
        guard count > 0 else { return }
        defer { _ = buffer.withUnsafeMutableBytes { $0.initializeMemory(as: UInt8.self, repeating: 0) } }
        do {
            let decoded = try OneTree.decode(Data(buffer.prefix(Int(count))))
            guard decoded.revision > (tree?.revision ?? 0) else {
                fail("tree_revision_refused"); return
            }
            tree = decoded; sequence = next
        } catch { fail("tree_decode_refused") }
    }
    private func fail(_ code: String) {
        failure = code; tree = nil
        services?.stop(); services = nil
        pickerCompletion = nil; pickingFile = false
        polling?.cancel(); polling = nil
        abla_ios_shutdown_v1()
    }
    private func drainMessages() {
        for _ in 0..<8 {
            guard services?.canAccept == true else { return }
            var message = AblaIosMessageV1()
            guard abla_ios_message_peek_v1(&message) == 1 else { return }
            guard (0...8).contains(message.method_size), (0...2048).contains(message.url_size),
                  (0...8 * 1024 * 1024).contains(message.body_size) else {
                fail("platform_message_refused"); return
            }
            var method = [UInt8](repeating: 0, count: Int(message.method_size))
            var url = [UInt8](repeating: 0, count: Int(message.url_size))
            var body = [UInt8](repeating: 0, count: Int(message.body_size))
            let result = method.withUnsafeMutableBufferPointer { m in
                url.withUnsafeMutableBufferPointer { u in
                    body.withUnsafeMutableBufferPointer { b in
                        abla_ios_message_copy_pop_v1(message.sequence, m.baseAddress, Int64(m.count),
                            u.baseAddress, Int64(u.count), b.baseAddress, Int64(b.count))
                    }
                }
            }
            guard result == 1 else { fail("platform_copy_refused"); return }
            if message.kind == 2 {
                guard let method = String(bytes: method, encoding: .utf8), let url = String(bytes: url, encoding: .utf8),
                      services?.receive(id: message.identifier, method: method, url: url, body: Data(body)) == true else {
                    fail("platform_admission_refused"); return
                }
            } else if message.kind == 1 {
                guard let text = String(bytes: body, encoding: .utf8) else { fail("effect_decode_refused"); return }
                switch message.identifier {
                case 1: notice = text
                case 2: UIPasteboard.general.string = text
                case 3:
                    if let url = URL(string: text), url.scheme == "https", url.user == nil, url.password == nil {
                        UIApplication.shared.open(url)
                    } else { notice = "external_url_refused" }
                case 4: UIImpactFeedbackGenerator(style: .light).impactOccurred()
                default: notice = "unsupported_effect_\(message.identifier)"
                }
            } else { fail("platform_kind_refused"); return }
        }
    }
}
#endif
