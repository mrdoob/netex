import XCTest
@testable import NetexIOS

final class ExtensionRouterTests: XCTestCase {
    func testRouterReplaysPanelPortsWhenPageBecomesReady() {
        let router = ExtensionRouter()
        router.handlePanelEnvelope(.extensionPortConnect(portID: "panel-1", name: "devtools"))

        let replay = router.replayForPageReady()

        XCTAssertEqual(replay.count, 1)
        XCTAssertEqual(replay.first?.type, .extensionPortConnect)
        XCTAssertEqual(replay.first?.payload["portId"] as? String, "panel-1")
    }

    func testBlobStoreEvictsOldestEntry() {
        let store = BlobStore(limit: 2)
        store.insert("a", dataURL: "one")
        store.insert("b", dataURL: "two")
        store.insert("c", dataURL: "three")

        XCTAssertNil(store.value(for: "a"))
        XCTAssertEqual(store.value(for: "b"), "two")
        XCTAssertEqual(store.value(for: "c"), "three")
    }
}
