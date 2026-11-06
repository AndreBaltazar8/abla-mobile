import XCTest

@MainActor final class HostSmokeUITests: XCTestCase {
    func testNewRouteCannotRetainUnacknowledgedSecureEdit() {
        let app = XCUIApplication(); app.launch()
        let field = app.secureTextFields["secure"]
        XCTAssertTrue(field.waitForExistence(timeout: 15))
        XCTAssertTrue(app.staticTexts["timer_ready"].waitForExistence(timeout: 5))
        app.buttons["ignore"].tap()
        XCTAssertTrue(app.staticTexts["ignore_mode"].waitForExistence(timeout: 5))
        field.tap(); field.typeText("routeSecret")
        XCTAssertTrue(app.staticTexts["editing"].waitForExistence(timeout: 5))
        let pending = field.value as? String ?? ""
        XCTAssertTrue(!pending.isEmpty && pending != field.placeholderValue)
        app.buttons["route"].tap()
        XCTAssertTrue(app.staticTexts["new_route"].waitForExistence(timeout: 5))
        let cleared = app.secureTextFields["secure"].value as? String ?? ""
        XCTAssertTrue(cleared.isEmpty || cleared == field.placeholderValue)
    }
    func testSecureEditingExactActionAndNativeDisabledState() {
        let app = XCUIApplication()
        app.launch()
        let field = app.secureTextFields["secure"]
        XCTAssertTrue(field.waitForExistence(timeout: 15))
        XCTAssertTrue(app.staticTexts["timer_ready"].waitForExistence(timeout: 5))
        XCTAssertTrue(field.isEnabled)
        let blocked = app.buttons["blocked"]
        XCTAssertTrue(blocked.exists)
        XCTAssertFalse(blocked.isEnabled)
        field.tap()
        XCTAssertTrue(app.staticTexts["secure_traits_valid"].waitForExistence(timeout: 5))
        field.typeText("s3crEt")
        XCTAssertTrue(app.staticTexts["editing"].waitForExistence(timeout: 5))
        app.buttons["accept"].tap()
        let accepted = app.staticTexts["accepted_exact"].waitForExistence(timeout: 5)
        if !accepted {
            // This fixture's status is a closed enum, never an editor value.
            let label = app.staticTexts["status"].label
            let safe = ["ready", "editing", "refused", "new_route", "ignore_mode", "timer_ready"].contains(label) ? label : "other"
            XCTFail("Exact action not observed; fixture status=" + safe)
        }
        XCTAssertFalse(app.staticTexts["disabled_button_sent"].exists)
        XCTAssertFalse(app.staticTexts["runtime.failure"].exists)
        // Values are not printed, put in attachments or used as screenshot names.
    }
}
