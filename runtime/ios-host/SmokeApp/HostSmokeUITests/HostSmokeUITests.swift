import XCTest

@MainActor final class HostSmokeUITests: XCTestCase {
    func testSecureEditingExactActionAndNativeDisabledState() {
        let app = XCUIApplication()
        app.launch()
        let field = app.secureTextFields["secure"]
        XCTAssertTrue(field.waitForExistence(timeout: 15))
        XCTAssertTrue(field.isEnabled)
        let blocked = app.buttons["blocked"]
        XCTAssertTrue(blocked.exists)
        XCTAssertFalse(blocked.isEnabled)
        field.tap()
        field.typeText("s3crEt")
        XCTAssertTrue(app.staticTexts["editing"].waitForExistence(timeout: 5))
        app.buttons["accept"].tap()
        XCTAssertTrue(app.staticTexts["accepted_exact"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts["disabled_button_sent"].exists)
        XCTAssertFalse(app.staticTexts["runtime.failure"].exists)
        // Values are not printed, put in attachments or used as screenshot names.
    }
}
