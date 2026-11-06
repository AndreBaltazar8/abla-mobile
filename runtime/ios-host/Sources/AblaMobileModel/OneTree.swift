import Foundation
import CoreFoundation

public enum TreeDecodeError: Error, Equatable, Sendable {
    case tooLarge, invalidJSON, invalidEnvelope, unsupportedProtocol
    case invalidNode, unsupportedNode, tooDeep, tooManyNodes
    case invalidProperty, tooManyChildren, duplicateSiblingKey
}

public enum WireValue: Equatable, Sendable {
    case text(String), integer(Int64), decimal(Double), flag(Bool), null
    public var text: String {
        switch self {
        case .text(let value): value
        case .integer(let value): String(value)
        case .decimal(let value): String(value)
        case .flag(let value): value ? "true" : "false"
        case .null: ""
        }
    }
}

public struct OneNode: Equatable, Sendable, Identifiable, CustomStringConvertible {
    public let type: String
    public let key: String
    public let properties: [String: WireValue]
    public let children: [OneNode]
    public var id: String { key }
    public var description: String { "OneNode(type: \(type), children: \(children.count), values: redacted)" }
    public func value(_ name: String, default fallback: String = "") -> String {
        properties[name]?.text ?? fallback
    }
    public func attribute(_ name: String, default fallback: String = "") -> String {
        value("a." + name, default: fallback)
    }
    public func number(_ name: String, default fallback: Double = 0) -> Double {
        guard let number = Double(value(name)), number.isFinite else { return fallback }
        return number
    }
    public func event(_ name: String) -> Int64 {
        let value = Int64(attribute(name)) ?? 0
        return value > 0 ? value : 0
    }
}

public struct OneTree: Equatable, Sendable, CustomStringConvertible {
    public let revision: Int64
    public let title: String
    public let darkTheme: Bool
    public let showTopBar: Bool
    public let background: String
    public let root: OneNode
    /// New route entries/root keys reset ephemeral editor state. This identity
    /// is deliberately independent of revision, so ordinary echoes keep focus.
    public var presentationIdentity: String {
        "\(root.key.utf8.count):\(root.key)|\(root.attribute("one.entry"))"
    }
    public func admitsEvent(revision rendered: Int64, identity: String, identifier: Int64) -> Bool {
        // Keep the actual rendered revision. One's existing recent-event map
        // resolves valid delayed actions; never retag them as the newest tree.
        rendered > 0 && rendered <= revision && identifier > 0 && identity == presentationIdentity
    }
    public var description: String { "OneTree(revision: \(revision), content: redacted)" }

    public static func decode(_ data: Data) throws -> OneTree {
        guard data.count <= 1024 * 1024 else { throw TreeDecodeError.tooLarge }
        let object: Any
        do { object = try JSONSerialization.jsonObject(with: data) }
        catch { throw TreeDecodeError.invalidJSON }
        guard let envelope = object as? [String: Any],
              let protocolNumber = integer(envelope["protocol"]) else {
            throw TreeDecodeError.invalidEnvelope
        }
        guard protocolNumber == 1 else { throw TreeDecodeError.unsupportedProtocol }
        guard let revision = integer(envelope["revision"]), revision > 0,
              let darkTheme = boolean(envelope["darkTheme"]),
              let root = envelope["root"] as? [String: Any] else {
            throw TreeDecodeError.invalidEnvelope
        }
        let title = envelope["title"] as? String ?? "Abla Mobile"
        guard title.utf16.count <= 256 else { throw TreeDecodeError.invalidEnvelope }
        var remaining = 4096
        let node = try decodeNode(root, depth: 0, remaining: &remaining)
        return OneTree(revision: revision, title: title, darkTheme: darkTheme,
                       showTopBar: boolean(envelope["showTopBar"]) ?? true,
                       background: envelope["background"] as? String ?? "", root: node)
    }

    private static let oneTypes: Set<String> = [
        "one.column", "one.row", "one.scrollRow", "one.stack", "one.scroll", "one.feed",
        "one.text", "one.rich", "one.icon", "one.image", "one.bitmap", "one.popover",
        "one.pressable", "one.link", "one.input", "one.spacer", "one.divider", "one.native",
    ]
    private static let mobileTypes: Set<String> = [
        "theme", "input", "appScaffold", "chatList", "richText", "column", "bottomScaffold",
        "row", "text", "button", "textField", "dropdown", "switch", "checkbox", "slider",
        "progress", "divider", "spacer", "card", "chip", "list", "image", "surface", "grid",
        "mediaCard", "actionGrid", "webView", "nativeGame", "pulseBadge",
    ]

    private static func decodeNode(_ source: [String: Any], depth: Int,
                                   remaining: inout Int) throws -> OneNode {
        guard depth <= 64 else { throw TreeDecodeError.tooDeep }
        guard remaining > 0 else { throw TreeDecodeError.tooManyNodes }
        remaining -= 1
        guard let type = source["type"] as? String,
              let key = source["key"] as? String, !key.isEmpty, key.utf16.count <= 128,
              let properties = source["properties"] as? [String: Any],
              let children = source["children"] as? [[String: Any]] else {
            throw TreeDecodeError.invalidNode
        }
        guard oneTypes.contains(type) || mobileTypes.contains(type) else {
            throw TreeDecodeError.unsupportedNode
        }
        guard properties.count <= (oneTypes.contains(type) ? 128 : 32) else {
            throw TreeDecodeError.invalidProperty
        }
        var values: [String: WireValue] = [:]
        for (name, raw) in properties {
            guard name.utf16.count <= 64 else { throw TreeDecodeError.invalidProperty }
            switch raw {
            case let text as String:
                let limit = type == "richText" && name == "parts" ? 65536 : 16384
                guard text.utf16.count <= limit else { throw TreeDecodeError.invalidProperty }
                values[name] = .text(text)
            case is NSNull: values[name] = .null
            case let number as NSNumber:
                if let flag = boolean(number) { values[name] = .flag(flag) }
                else if let whole = integer(number) { values[name] = .integer(whole) }
                else {
                    guard number.doubleValue.isFinite else { throw TreeDecodeError.invalidProperty }
                    values[name] = .decimal(number.doubleValue)
                }
            default: throw TreeDecodeError.invalidProperty
            }
        }
        guard children.count <= 1024 else { throw TreeDecodeError.tooManyChildren }
        var seen: Set<String> = []
        var parsed: [OneNode] = []
        for raw in children {
            let child = try decodeNode(raw, depth: depth + 1, remaining: &remaining)
            guard seen.insert(child.key).inserted else { throw TreeDecodeError.duplicateSiblingKey }
            parsed.append(child)
        }
        return OneNode(type: type, key: key, properties: values, children: parsed)
    }

    private static func boolean(_ raw: Any?) -> Bool? {
        guard let number = raw as? NSNumber,
              CFGetTypeID(number) == CFBooleanGetTypeID() else { return nil }
        return number.boolValue
    }
    private static func integer(_ raw: Any?) -> Int64? {
        guard let number = raw as? NSNumber,
              CFGetTypeID(number) != CFBooleanGetTypeID() else { return nil }
        return Int64(number.stringValue)
    }
}
