import XCTest
@testable import NetexIOS

final class AssetAndShimTests: XCTestCase {
    func testAssetLoaderFindsBundledPanelShell() {
        XCTAssertFalse(AssetLoader.text("panel-shell", ext: "html").isEmpty)
    }

    func testBundledOfflineAssetsCoverPanelVendorAndThreeDevTools() throws {
        let requiredAssets = [
            "NetexAssets/start.html",
            "NetexAssets/stress.html",
            "NetexAssets/panel-shell.html",
            "NetexAssets/vendor/highlight.min.js",
            "NetexAssets/vendor/beautify.min.js",
            "NetexAssets/vendor/model-viewer.min.js",
            "NetexAssets/threejs-devtools/panel/panel.html",
            "NetexAssets/threejs-devtools/panel/panel.js",
            "NetexAssets/threejs-devtools/bridge.js"
        ]

        for asset in requiredAssets {
            let url = try XCTUnwrap(AssetLoader.fileURL(forAssetPath: asset), asset)
            XCTAssertTrue(FileManager.default.fileExists(atPath: url.path), asset)
        }
    }

    func testFullShimScriptPlanKeepsPerformanceFirstAndThreeBeforeOrientation() {
        let plan = NetexScriptPlan(mode: .full)

        XCTAssertEqual(plan.scriptNames, [
            "performance-shim",
            "console-shim",
            "network-shim",
            "threejs-devtools",
            "orientation-shim"
        ])
    }

    func testConsoleOnlyShimPlanDoesNotInstallNetworkOrThreeScripts() {
        let plan = NetexScriptPlan(mode: .consoleOnly)

        XCTAssertEqual(plan.scriptNames, [
            "performance-shim",
            "console-shim",
            "orientation-shim"
        ])
    }

    func testLaunchOptionsCanResetAndOpenSpecificURL() {
        let options = NetexLaunchOptions(arguments: [
            "NetexIOS",
            "--netex-reset",
            "--netex-url",
            "netex-assets://bundle/NetexAssets/stress.html"
        ])

        XCTAssertTrue(options.reset)
        XCTAssertEqual(options.initialURL?.absoluteString, "netex-assets://bundle/NetexAssets/stress.html")
    }

    func testShimModeParsesKnownValues() {
        XCTAssertEqual(NetexShimMode(rawValue: "off"), .off)
        XCTAssertEqual(NetexShimMode(rawValue: "console"), .consoleOnly)
        XCTAssertEqual(NetexShimMode(rawValue: "network"), .networkOnly)
        XCTAssertEqual(NetexShimMode(rawValue: "full"), .full)
        XCTAssertEqual(NetexShimMode(rawValue: "unknown"), .full)
    }

    func testPanelLetsModelViewerOwnNativePanAndHidesBuiltInTargets() {
        let script = AssetLoader.text("panel", ext: "js")
        let shell = AssetLoader.text("panel-shell", ext: "html")

        XCTAssertFalse(script.contains("installModelViewerPanGestures"))
        XCTAssertFalse(script.contains("touches.length === 2"))
        XCTAssertFalse(script.contains("addEventListener('touchmove'"))
        XCTAssertTrue(script.contains("openModelPreview"))
        XCTAssertTrue(script.contains("overlayModelViewer"))
        XCTAssertTrue(script.contains("ensureOverlayModelViewer"))
        XCTAssertTrue(script.contains("buildModelPreviewTrigger"))
        XCTAssertEqual(script.components(separatedBy: "document.createElement('model-viewer')").count - 1, 1)
        XCTAssertFalse(script.contains(":scope > .body > img, :scope > .body > model-viewer"))
        XCTAssertTrue(script.contains("interaction-prompt"))
        XCTAssertTrue(script.contains("hiddenModelViewerSlot('pan-target')"))
        XCTAssertTrue(script.contains("hiddenModelViewerSlot('interaction-prompt')"))
        XCTAssertTrue(shell.contains("modelOverlay"))
        XCTAssertTrue(shell.contains("modelStage"))
        XCTAssertTrue(shell.contains(".model-overlay-stage"))
        XCTAssertTrue(shell.contains(".model-preview-host"))
        XCTAssertTrue(shell.contains(".model-preview-trigger"))
        XCTAssertTrue(shell.contains(".hidden-model-viewer-slot"))
    }
}
