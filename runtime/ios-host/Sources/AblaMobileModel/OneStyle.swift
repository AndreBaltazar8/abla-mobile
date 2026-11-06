import Foundation

public struct OneRGBA: Equatable, Sendable {
    public let red: Double, green: Double, blue: Double, alpha: Double
    /// Android's existing wire convention is #RGB, #RRGGBB or #AARRGGBB.
    public init?(_ text: String) {
        guard text.hasPrefix("#") else { return nil }
        let digits = String(text.dropFirst())
        guard [3, 6, 8].contains(digits.count), digits.utf8.allSatisfy({
            (48...57).contains($0) || (65...70).contains($0) || (97...102).contains($0)
        }), let raw = UInt32(digits, radix: 16) else { return nil }
        if digits.count == 3 {
            red = Double((raw >> 8) & 15) / 15
            green = Double((raw >> 4) & 15) / 15
            blue = Double(raw & 15) / 15
            alpha = 1
        } else {
            red = Double((raw >> 16) & 255) / 255
            green = Double((raw >> 8) & 255) / 255
            blue = Double(raw & 255) / 255
            alpha = digits.count == 8 ? Double(raw >> 24) / 255 : 1
        }
    }
}

public struct OneResolvedStyle: Sendable {
    private let node: OneNode
    public init(_ node: OneNode) { self.node = node }
    public func dimension(_ name: String) -> Double? {
        guard let value = Double(node.value(name)), value.isFinite, value >= 0, value <= 65536 else { return nil }
        return value
    }
    public func bounded(_ name: String, fallback: Double, maximum: Double = 4096) -> Double {
        min(maximum, max(0, node.number(name, default: fallback)))
    }
    public func padding(_ side: String) -> Double {
        bounded("padding" + side, fallback: bounded("padding", fallback: 0))
    }
    public func color(_ name: String, pressed: Bool = false, focused: Bool = false) -> OneRGBA? {
        var names: [String] = []
        if node.attribute("enabled") == "false" { names.append("disabled:" + name) }
        if pressed { names.append("pressed:" + name) }
        if focused { names.append("focus:" + name) }
        if node.attribute("selected") == "true" { names.append("selected:" + name) }
        names.append(name)
        return names.lazy.compactMap { OneRGBA(node.value($0)) }.first
    }
}
