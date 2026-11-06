import Foundation
import XCTest
@testable import AblaMobileModel
final class OneStyleTests: XCTestCase {
    func testWireColorsUseArgbRatherThanRgba() {
        XCTAssertEqual(OneRGBA("#f00")?.red, 1)
        XCTAssertEqual(OneRGBA("#f00")?.green, 0)
        XCTAssertEqual(OneRGBA("#80ff0000")?.alpha ?? -1, 128.0 / 255, accuracy: 0.0001)
        XCTAssertEqual(OneRGBA("#80ff0000")?.red, 1)
        for bad in ["red", "#12", "#gggggg", "#000000000", "#-00000"] { XCTAssertNil(OneRGBA(bad)) }
    }
    func testStatePrecedenceAndFiniteDimensions() throws {
        let object: [String: Any] = ["protocol": 1, "revision": 1, "darkTheme": false,
            "root": ["type": "one.column", "key": "root", "children": [],
                     "properties": ["a.enabled": "false", "a.selected": "true",
                         "background": "#111111", "selected:background": "#222222",
                         "focus:background": "#333333", "pressed:background": "#444444",
                         "disabled:background": "#555555", "width": "nan", "height": "fill",
                         "maxWidth": "1e999", "minWidth": "-1", "padding": "8", "paddingLeft": "12"]]]
        let node = try OneTree.decode(JSONSerialization.data(withJSONObject: object)).root
        let style = OneResolvedStyle(node)
        XCTAssertEqual(style.color("background", pressed: true, focused: true), OneRGBA("#555555"))
        for key in ["width", "height", "maxWidth", "minWidth"] { XCTAssertNil(style.dimension(key)) }
        XCTAssertEqual(style.padding("Left"), 12)
        XCTAssertEqual(style.padding("Top"), 8)
    }
}
