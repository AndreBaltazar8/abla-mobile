#if os(iOS)
import Foundation
import SwiftUI
import AblaMobileModel
import CAblaMobile

/// Main-thread ownership of copied snapshots. The Abla heap and event loop live
/// exclusively on the C bridge's runtime thread. No tree/editor data is logged.
@MainActor public final class MobileRuntimeStore: ObservableObject {
    @Published public private(set) var tree: OneTree?
    @Published public private(set) var failure: String?
    private var sequence: Int64 = 0
    private var polling: Task<Void, Never>?
    private var buffer = [UInt8](repeating: 0, count: Int(ABLA_IOS_TREE_LIMIT))
    public init() {}
    public func start() {
        guard polling == nil else { return }
        guard abla_ios_start_v1() >= 0 else { failure = "runtime_start_refused"; return }
        polling = Task { [weak self] in
            while !Task.isCancelled {
                self?.readSnapshot()
                try? await Task.sleep(for: .milliseconds(16))
            }
        }
    }
    public func stop() {
        polling?.cancel(); polling = nil
        tree = nil; buffer = [UInt8](repeating: 0, count: buffer.count)
        abla_ios_shutdown_v1()
    }
    @discardableResult public func send(_ identifier: Int64, revision: Int64,
                                       payload: String = "", change: Bool = false) -> Bool {
        guard tree?.revision == revision, revision > 0, failure == nil, identifier > 0,
              payload.utf8.count <= Int(ABLA_IOS_EVENT_LIMIT) else { return false }
        return Array(payload.utf8).withUnsafeBufferPointer {
            abla_ios_event_v1(revision, identifier, $0.baseAddress, Int64($0.count), change ? 1 : 0) == 1
        }
    }
    private func readSnapshot() {
        if abla_ios_state_v1() == Int64(ABLA_IOS_FAILED.rawValue) {
            failure = "runtime_failed_\(abla_ios_failure_v1())"; tree = nil; return
        }
        var next: Int64 = sequence
        let count = buffer.withUnsafeMutableBufferPointer {
            abla_ios_tree_copy_v1(sequence, $0.baseAddress, Int64($0.count), &next)
        }
        guard count > 0 else { return }
        defer { _ = buffer.withUnsafeMutableBytes { $0.initializeMemory(as: UInt8.self, repeating: 0) } }
        do {
            let decoded = try OneTree.decode(Data(buffer.prefix(Int(count))))
            guard decoded.revision > (tree?.revision ?? 0) else {
                failure = "tree_revision_refused"; return
            }
            tree = decoded; sequence = next
        } catch { failure = "tree_decode_refused"; tree = nil }
    }
}
#endif
