import XCTest

final class NetexIOSUITests: XCTestCase {
    func testLaunchesLocalStartPageAndSwitchesTabs() {
        let app = XCUIApplication()
        app.launchArguments = ["--netex-reset"]
        app.launch()

        XCTAssertTrue(app.staticTexts["Netex"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.textFields["netex.address"].exists)

        let tabs = app.segmentedControls["netex.tabs"]
        XCTAssertTrue(tabs.exists)
        tabs.buttons["Source"].tap()
        tabs.buttons["Network"].tap()
        tabs.buttons["Three.js"].tap()
        tabs.buttons["Console"].tap()
    }
}
