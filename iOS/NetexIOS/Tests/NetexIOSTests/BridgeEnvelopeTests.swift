import XCTest
@testable import NetexIOS

final class BridgeEnvelopeTests: XCTestCase {
    func testEnvelopeDecodesCanonicalShape() throws {
        let body: [String: Any] = [
            "type": "console-entry",
            "id": "abc",
            "source": "page",
            "timestamp": 42.0,
            "payload": ["level": "log"]
        ]

        let envelope = try XCTUnwrap(BridgeEnvelope(body))

        XCTAssertEqual(envelope.type, .consoleEntry)
        XCTAssertEqual(envelope.id, "abc")
        XCTAssertEqual(envelope.source, "page")
        XCTAssertEqual(envelope.timestamp, 42.0)
        XCTAssertEqual(envelope.payload["level"] as? String, "log")
    }

    func testEnvelopeNormalizesLegacyNetworkBodyIntoPayload() throws {
        let body: [String: Any] = [
            "method": "GET",
            "url": "https://threejs.org/files/main.css",
            "status": 200
        ]

        let envelope = try XCTUnwrap(BridgeEnvelope(body, fallbackType: .networkRecord))

        XCTAssertEqual(envelope.type, .networkRecord)
        XCTAssertEqual(envelope.payload["url"] as? String, "https://threejs.org/files/main.css")
    }

    func testEnvelopeDecodesConsoleBatch() throws {
        let body: [String: Any] = [
            "type": "console.batch",
            "source": "page",
            "payload": [
                "entries": [
                    ["level": "log", "segments": [["text": "hello"]]]
                ]
            ]
        ]

        let envelope = try XCTUnwrap(BridgeEnvelope(body))
        let entries = try XCTUnwrap(envelope.payload["entries"] as? [[String: Any]])

        XCTAssertEqual(envelope.type, .consoleBatch)
        XCTAssertEqual(entries.count, 1)
    }

    func testEnvelopeDecodesNetworkBatch() throws {
        let body: [String: Any] = [
            "type": "network.batch",
            "source": "page",
            "payload": [
                "entries": [
                    ["method": "GET", "url": "https://threejs.org/examples/", "status": 200]
                ]
            ]
        ]

        let envelope = try XCTUnwrap(BridgeEnvelope(body))
        let entries = try XCTUnwrap(envelope.payload["entries"] as? [[String: Any]])

        XCTAssertEqual(envelope.type, .networkBatch)
        XCTAssertEqual(entries.first?["url"] as? String, "https://threejs.org/examples/")
    }
}
