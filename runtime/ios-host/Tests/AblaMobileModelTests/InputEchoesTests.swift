import XCTest
@testable import AblaMobileModel

final class InputEchoesTests: XCTestCase {
    func testCanonicallyEquivalentTextIsNotAnExactByteEcho() {
        var field = InputEchoes(initial: "")
        XCTAssertTrue(field.changed("\u{e9}"))
        let replacement = field.acceptModel("e\u{301}", focused: true, expiredGeneration: nil)
        XCTAssertNotNil(replacement)
        XCTAssertEqual(Array((replacement ?? "").utf8), [0x65, 0xcc, 0x81])
        XCTAssertFalse(field.hasPending)
        var other = InputEchoes(initial: "\u{e9}")
        other.changed("pending")
        let changed = other.acceptModel("e\u{301}", focused: true, expiredGeneration: nil)
        XCTAssertEqual(Array((changed ?? "").utf8), [0x65, 0xcc, 0x81])
    }
    func testDelayedPrefixEchoPreservesNewerFocusedEdits() {
        var field = InputEchoes(initial: "")
        XCTAssertTrue(field.changed("a")); let old = field.generation
        XCTAssertNil(field.acceptModel("", focused: true, expiredGeneration: old))
        XCTAssertTrue(field.changed("an"))
        XCTAssertNil(field.acceptModel("a", focused: true, expiredGeneration: old))
        XCTAssertTrue(field.hasPending)
        XCTAssertTrue(field.changed("androidqa@example.test"))
        XCTAssertNil(field.acceptModel("an", focused: true, expiredGeneration: old))
        XCTAssertNil(field.acceptModel("androidqa@example.test", focused: true, expiredGeneration: old))
        XCTAssertFalse(field.hasPending)
    }
    func testOldExpiryCannotDiscardNewerUnfocusedInput() {
        var field = InputEchoes(initial: "")
        field.changed("a"); let old = field.generation
        field.changed("an")
        XCTAssertNil(field.acceptModel("", focused: false, expiredGeneration: old))
        XCTAssertTrue(field.hasPending)
    }
    func testIgnoredEditRestoresModelOnlyAfterBlurAndCurrentExpiry() {
        var field = InputEchoes(initial: "original")
        field.changed("local edit")
        XCTAssertNil(field.acceptModel("original", focused: true, expiredGeneration: field.generation))
        XCTAssertNil(field.acceptModel("original", focused: false, expiredGeneration: nil))
        XCTAssertEqual(field.acceptModel("original", focused: false, expiredGeneration: field.generation), "original")
        XCTAssertFalse(field.hasPending)
    }
    func testExplicitApplicationReplacementWinsIncludingClear() {
        var field = InputEchoes(initial: "")
        field.changed("a")
        XCTAssertNil(field.acceptModel("a", focused: true, expiredGeneration: nil))
        field.changed("an")
        XCTAssertEqual(field.acceptModel("", focused: true, expiredGeneration: nil), "")
        XCTAssertFalse(field.hasPending)
        field.changed("local")
        XCTAssertEqual(field.acceptModel("replacement", focused: true, expiredGeneration: nil), "replacement")
    }
    func testBackpressureNeverDropsOrMutatesAcceptedHistory() {
        var field = InputEchoes(initial: "", maximumBytes: 3, maximumValues: 2)
        XCTAssertTrue(field.changed("a")); XCTAssertTrue(field.changed("an"))
        let generation = field.generation
        XCTAssertFalse(field.changed("and")); XCTAssertEqual(field.generation, generation)
        XCTAssertNil(field.acceptModel("a", focused: true, expiredGeneration: nil))
        XCTAssertTrue(field.hasPending)
        field.clear(); XCTAssertFalse(field.hasPending)
        XCTAssertTrue(field.changed("abc"))
    }
}
