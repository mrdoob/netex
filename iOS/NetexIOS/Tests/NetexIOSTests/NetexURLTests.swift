import XCTest
@testable import NetexIOS

final class NetexURLTests: XCTestCase {
    func testSearchTextBecomesDuckDuckGoQuery() throws {
        let url = try XCTUnwrap(NetexURL.resolved(from: "three js inspector"))
        XCTAssertEqual(url.absoluteString, "https://duckduckgo.com/?q=three%20js%20inspector")
    }

    func testBareDomainGetsHTTPS() throws {
        let url = try XCTUnwrap(NetexURL.resolved(from: "threejs.org/examples/"))
        XCTAssertEqual(url.absoluteString, "https://threejs.org/examples/")
    }

    func testExistingURLIsPreserved() throws {
        let url = try XCTUnwrap(NetexURL.resolved(from: "http://localhost:5173/demo?x=1"))
        XCTAssertEqual(url.absoluteString, "http://localhost:5173/demo?x=1")
    }
}
