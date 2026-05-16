import XCTest
@testable import NetexIOS

final class AssetAndShimTests: XCTestCase {
    func testAssetLoaderFindsBundledPanelShell() {
        XCTAssertFalse(AssetLoader.text("panel-shell", ext: "html").isEmpty)
    }

    func testShimModeParsesKnownValues() {
        XCTAssertEqual(NetexShimMode(rawValue: "off"), .off)
        XCTAssertEqual(NetexShimMode(rawValue: "console"), .consoleOnly)
        XCTAssertEqual(NetexShimMode(rawValue: "network"), .networkOnly)
        XCTAssertEqual(NetexShimMode(rawValue: "full"), .full)
        XCTAssertEqual(NetexShimMode(rawValue: "unknown"), .full)
    }
}
