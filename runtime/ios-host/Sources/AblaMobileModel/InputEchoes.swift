/// Delayed model acknowledgements are not application-authored replacements.
/// Values stay private; callers must not log editor contents or pending history.
public struct InputEchoes: Sendable {
    private var seen: String
    private var pending: [String] = []
    private var pendingBytes = 0
    private let maximumBytes: Int
    private let maximumValues: Int
    public private(set) var generation: UInt64 = 0
    public var hasPending: Bool { !pending.isEmpty }
    public init(initial: String, maximumBytes: Int = 512 * 1024, maximumValues: Int = 256) {
        seen = initial
        self.maximumBytes = max(0, maximumBytes)
        self.maximumValues = max(0, maximumValues)
    }
    /// Refusal leaves history/generation untouched; the UI must not send or
    /// accept the new local value if its bounded pending history is full.
    @discardableResult public mutating func changed(_ value: String) -> Bool {
        let bytes = value.utf8.count
        guard pending.count < maximumValues, bytes <= maximumBytes - pendingBytes else { return false }
        pending.append(value)
        pendingBytes += bytes
        generation &+= 1
        return true
    }
    public mutating func acceptModel(_ value: String, focused: Bool,
                                    expiredGeneration: UInt64?) -> String? {
        var replacement: String?
        if !value.utf8.elementsEqual(seen.utf8) {
            seen = value
            if let echo = pending.firstIndex(where: { $0.utf8.elementsEqual(value.utf8) }) {
                pendingBytes -= pending.prefix(echo + 1).reduce(0) { $0 + $1.utf8.count }
                pending.removeFirst(echo + 1)
            } else { pending.removeAll(); pendingBytes = 0; replacement = value }
        }
        if !focused && expiredGeneration == generation { pending.removeAll(); pendingBytes = 0 }
        if pending.isEmpty && !focused { replacement = value }
        return replacement
    }
    public mutating func clear() {
        seen = ""
        pending.removeAll()
        pendingBytes = 0
        generation &+= 1
    }
}
