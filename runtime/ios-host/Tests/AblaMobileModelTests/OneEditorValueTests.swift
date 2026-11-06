import XCTest
@testable import AblaMobileModel
final class OneEditorValueTests: XCTestCase {
    func testTextPreservesCaseAndUnicodeWithoutCorrection() {
        XCTAssertEqual(OneEditorValue.normalized("s3crEt-ação", keyboard: "text", maximumUTF16: 100), "s3crEt-ação")
        XCTAssertEqual(OneEditorValue.normalized("e\u{301}", keyboard: "text", maximumUTF16: 2), "e\u{301}")
        XCTAssertEqual(OneEditorValue.normalized("😀x", keyboard: "text", maximumUTF16: 1), "")
        XCTAssertEqual(OneEditorValue.normalized("😀x", keyboard: "text", maximumUTF16: 2), "😀")
    }
    func testNumericAndDecimalFilteringMatchesExistingHost() {
        XCTAssertEqual(OneEditorValue.normalized("1a٢3-4", keyboard: "number", maximumUTF16: 3), "134")
        XCTAssertEqual(OneEditorValue.normalized(" 1,2.3x", keyboard: "decimal", maximumUTF16: 32), "1,23")
        XCTAssertEqual(OneEditorValue.normalized("1.2,3", keyboard: "decimal", maximumUTF16: 32), "1.23")
        XCTAssertEqual(OneEditorValue.normalized("text", keyboard: "text", maximumUTF16: -1), "")
    }
}
