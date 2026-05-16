import XCTest
@testable import NetexIOS

final class NavigationPolicyTests: XCTestCase {
    func testAllowsNetexAssetsAndThreeExamplesAsMainFrameDestinations() throws {
        let policy = NetexNavigationPolicy()

        XCTAssertEqual(policy.decision(for: try XCTUnwrap(URL(string: "netex-assets://bundle/NetexAssets/start.html")), targetIsMainFrame: true), .allow)
        XCTAssertEqual(policy.decision(for: try XCTUnwrap(URL(string: "https://threejs.org/examples/#webgl_animation_keyframes")), targetIsMainFrame: true), .allow)
        XCTAssertEqual(policy.decision(for: try XCTUnwrap(URL(string: "https://www.threejs.org/examples/webgl_loader_gltf.html")), targetIsMainFrame: true), .allow)
    }

    func testBlocksExternalMainFrameDestinationsButAllowsSubframes() throws {
        let policy = NetexNavigationPolicy()
        let github = try XCTUnwrap(URL(string: "https://github.com/mrdoob/three.js/blob/master/examples/webgl_animation_skinning_additive_blending.html"))

        XCTAssertEqual(policy.decision(for: github, targetIsMainFrame: true), .block)
        XCTAssertEqual(policy.decision(for: github, targetIsMainFrame: false), .allow)
    }
}
