import Foundation
import XCTest
@testable import AblaMobileModel

final class OneTreeTests: XCTestCase {
    func testRouteIdentityChangesButOrdinaryRevisionDoesNotResetEditors() throws {
        let first = try OneTree.decode(wire(node(properties: ["a.one.entry": "1"])))
        let echo = try OneTree.decode(wire(node(properties: ["a.one.entry": "1"]), revision: 2))
        let moved = try OneTree.decode(wire(node(properties: ["a.one.entry": "2"]), revision: 3))
        XCTAssertEqual(first.presentationIdentity, echo.presentationIdentity)
        XCTAssertNotEqual(first.presentationIdentity, moved.presentationIdentity)
    }
    func testTextNumbersCannotSupplyNonFiniteLayoutValues() throws {
        for raw in ["nan", "inf", "-inf", "1e999"] {
            let tree = try OneTree.decode(wire(node(properties: ["gap": raw])))
            XCTAssertEqual(tree.root.number("gap", default: 8), 8)
        }
    }
    private func node(_ key: String = "root", type: String = "one.column",
                      properties: [String: Any] = [:], children: [[String: Any]] = []) -> [String: Any] {
        ["type": type, "key": key, "properties": properties, "children": children]
    }
    private func wire(_ root: [String: Any], revision: Int64 = 1) throws -> Data {
        try JSONSerialization.data(withJSONObject: ["protocol": 1, "revision": revision,
            "title": "Olá", "darkTheme": false, "root": root])
    }
    func testActualProtocolAndUnicodeProperties() throws {
        let tree = try OneTree.decode(wire(node(children: [node("label", type: "one.text",
            properties: ["a.text": "ação · Hello", "a.onPress": "7", "enabled": true])]), revision: Int64.max))
        XCTAssertEqual(tree.revision, Int64.max)
        XCTAssertEqual(tree.root.children[0].attribute("text"), "ação · Hello")
        XCTAssertEqual(tree.root.children[0].event("onPress"), 7)
        XCTAssertTrue(tree.showTopBar)
        XCTAssertFalse(tree.darkTheme)
    }
    func testBooleanIsNotAnIntegerProtocolOrRevision() throws {
        for field in ["protocol", "revision"] {
            var value: [String: Any] = ["protocol": 1, "revision": 1, "darkTheme": false, "root": node()]
            value[field] = true
            XCTAssertThrowsError(try OneTree.decode(JSONSerialization.data(withJSONObject: value))) {
                XCTAssertEqual($0 as? TreeDecodeError, .invalidEnvelope)
            }
        }
    }
    func testDuplicateSiblingIdentityRefuses() throws {
        XCTAssertThrowsError(try OneTree.decode(wire(node(children: [node("same"), node("same")])))) {
            XCTAssertEqual($0 as? TreeDecodeError, .duplicateSiblingKey)
        }
    }
    func testPropertiesCannotSmuggleNestedObjectsOrArrays() throws {
        let invalid: [Any] = [["nested": "value"], [1, 2]]
        for raw in invalid {
            XCTAssertThrowsError(try OneTree.decode(wire(node(properties: ["a.value": raw])))) {
                XCTAssertEqual($0 as? TreeDecodeError, .invalidProperty)
            }
        }
    }
    func testUnknownNodeAndPropertyBudgetsRefuse() throws {
        XCTAssertThrowsError(try OneTree.decode(wire(node(type: "unknown")))) {
            XCTAssertEqual($0 as? TreeDecodeError, .unsupportedNode)
        }
        XCTAssertThrowsError(try OneTree.decode(wire(node(properties: ["a.text": String(repeating: "x", count: 16385)])))) {
            XCTAssertEqual($0 as? TreeDecodeError, .invalidProperty)
        }
    }
    func testTreeDepthAndChildBoundsRefuse() throws {
        var deep = node("leaf")
        for depth in 0..<65 { deep = node("n\(depth)", children: [deep]) }
        XCTAssertThrowsError(try OneTree.decode(wire(deep))) {
            XCTAssertEqual($0 as? TreeDecodeError, .tooDeep)
        }
        XCTAssertThrowsError(try OneTree.decode(wire(node(children: (0..<1025).map { node("n\($0)") })))) {
            XCTAssertEqual($0 as? TreeDecodeError, .tooManyChildren)
        }
    }
    func testWholeTreeNodeAndByteBudgetsRefuse() throws {
        let groups = (0..<5).map { group in
            node("g\(group)", children: (0..<900).map { node("n\($0)") })
        }
        XCTAssertThrowsError(try OneTree.decode(wire(node(children: groups)))) {
            XCTAssertEqual($0 as? TreeDecodeError, .tooManyNodes)
        }
        XCTAssertThrowsError(try OneTree.decode(Data(repeating: 32, count: 1024 * 1024 + 1))) {
            XCTAssertEqual($0 as? TreeDecodeError, .tooLarge)
        }
    }
    func testDefaultDescriptionsNeverContainInputValues() throws {
        let tree = try OneTree.decode(wire(node(type: "one.input", properties: ["a.value": "fixture-secret", "a.secure": "true"])))
        XCTAssertFalse(String(describing: tree).contains("fixture-secret"))
        XCTAssertFalse(String(describing: tree.root).contains("fixture-secret"))
    }
}
