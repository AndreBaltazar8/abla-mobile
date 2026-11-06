import XCTest

@MainActor final class HostSmokeUITests: XCTestCase {
    func testEmptyFileFilterOpensSystemPickerAndCancellationCompletes() {
        let app = XCUIApplication(); app.launch()
        XCTAssertTrue(app.staticTexts["timer_ready"].waitForExistence(timeout: 15))
        app.buttons["pick"].tap()
        let cancel = app.buttons["Cancel"]
        XCTAssertTrue(cancel.waitForExistence(timeout: 10))
        cancel.tap()
        XCTAssertTrue(app.staticTexts["picker_cancel_passed"].waitForExistence(timeout: 5))
    }
    // These two require the CI-local HTTPS peer. The ordinary UI step excludes
    // them, then separate steps run before and after installing its private CA
    // in the disposable Simulator trust store. Production trust code is unchanged.
    func testHttpsRefusesUnknownCertificateAuthority() {
        let app = XCUIApplication()
        app.launchEnvironment["ABLA_HOST_TEST_UNTRUSTED"] = "1"
        app.launch()
        XCTAssertTrue(app.staticTexts["timer_ready"].waitForExistence(timeout: 15))
        app.buttons["https"].tap()
        XCTAssertTrue(app.staticTexts["https_untrusted_refused"].waitForExistence(timeout: 20))
    }
    func testHttpsUsesSystemTrustWithoutRedirectOrLostAnswerReplay() {
        let app = XCUIApplication(); app.launch()
        XCTAssertTrue(app.staticTexts["timer_ready"].waitForExistence(timeout: 15))
        app.buttons["https"].tap()
        XCTAssertTrue(app.staticTexts["https_system_trust_roundtrip_passed"].waitForExistence(timeout: 25))
    }
    func testExplicitKeychainStoreReadDeleteRoundtrip() {
        let app = XCUIApplication(); app.launch()
        XCTAssertTrue(app.staticTexts["timer_ready"].waitForExistence(timeout: 15))
        app.buttons["keychain"].tap()
        XCTAssertTrue(app.staticTexts["keychain_roundtrip_passed"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.staticTexts["runtime.failure"].exists)
    }
    func testApplicationReplacementPreservesExactUnicodeBytes() {
        let app = XCUIApplication(); app.launch()
        XCTAssertTrue(app.secureTextFields["secure"].waitForExistence(timeout: 15))
        XCTAssertTrue(app.staticTexts["timer_ready"].waitForExistence(timeout: 5))
        app.buttons["nfc"].tap()
        XCTAssertTrue(app.staticTexts["unicode_nfc"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["editor_bytes_nfc"].waitForExistence(timeout: 5))
        app.buttons["nfd"].tap()
        XCTAssertTrue(app.staticTexts["unicode_nfd"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["editor_bytes_nfd"].waitForExistence(timeout: 5))
    }
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
