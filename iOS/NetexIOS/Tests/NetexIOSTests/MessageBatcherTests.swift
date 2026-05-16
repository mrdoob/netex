import XCTest
@testable import NetexIOS

final class MessageBatcherTests: XCTestCase {
    func testBatcherFlushesWhenMaximumBatchSizeIsReached() {
        var batches: [[Int]] = []
        let batcher = MessageBatcher<Int>(maxBatchSize: 3, delay: 10) { batches.append($0) }

        batcher.append(1)
        batcher.append(2)
        batcher.append(3)

        XCTAssertEqual(batches, [[1, 2, 3]])
    }

    func testManualFlushDrainsPendingValues() {
        var batches: [[String]] = []
        let batcher = MessageBatcher<String>(maxBatchSize: 10, delay: 10) { batches.append($0) }

        batcher.append("a")
        batcher.append("b")
        batcher.flush()

        XCTAssertEqual(batches, [["a", "b"]])
    }
}
