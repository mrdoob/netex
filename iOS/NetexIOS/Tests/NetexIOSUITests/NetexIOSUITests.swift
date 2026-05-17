import XCTest

final class NetexIOSUITests: XCTestCase {
    func testLaunchesLocalStartPageAndSwitchesTabs() {
        let app = XCUIApplication()
        app.launchArguments = ["--netex-reset"]
        app.launch()

        XCTAssertTrue(app.staticTexts["Netex"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.textFields["netex.address"].exists)
        XCTAssertTrue(app.staticTexts["netex.pageTitle"].exists)
        XCTAssertTrue(app.buttons["netex.examples"].exists)

        let tabs = app.segmentedControls["netex.tabs"]
        XCTAssertTrue(tabs.exists)
        tabs.buttons["Source"].tap()
        tabs.buttons["Network"].tap()
        tabs.buttons["Three.js"].tap()
        tabs.buttons["Console"].tap()
    }

    func testExamplesMenuKeepsCustomURLAdvanced() {
        let app = XCUIApplication()
        app.launchArguments = ["--netex-reset"]
        app.launch()

        XCTAssertTrue(app.staticTexts["Netex"].waitForExistence(timeout: 5))
        app.buttons["netex.examples"].tap()

        XCTAssertTrue(app.buttons["Examples"].waitForExistence(timeout: 2))
        XCTAssertTrue(app.descendants(matching: .any)["Animated Model"].exists)
        XCTAssertTrue(app.descendants(matching: .any)["glTF Loader"].exists)
        XCTAssertTrue(app.buttons["Open Custom URL..."].exists)
        XCTAssertFalse(app.textFields["netex.address"].exists)
    }

    func testThreeTabExposesPanelSurface() {
        let app = XCUIApplication()
        app.launchArguments = ["--netex-reset"]
        app.launch()

        XCTAssertTrue(app.staticTexts["Netex"].waitForExistence(timeout: 5))
        app.segmentedControls["netex.tabs"].buttons["Three.js"].tap()

        XCTAssertTrue(app.webViews["netex.threePanel"].waitForExistence(timeout: 5))
    }

    func testInspectorCanHideAndRestore() {
        let app = XCUIApplication()
        app.launchArguments = ["--netex-reset"]
        app.launch()

        XCTAssertTrue(app.staticTexts["Netex"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.segmentedControls["netex.tabs"].exists)

        app.buttons["Hide Inspector"].tap()
        XCTAssertFalse(app.segmentedControls["netex.tabs"].waitForExistence(timeout: 1))

        app.buttons["Show Inspector"].tap()
        XCTAssertTrue(app.segmentedControls["netex.tabs"].waitForExistence(timeout: 2))
    }
}
