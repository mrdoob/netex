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

    func testPanelSourceEditModeUsesLiveSessionCheckpointAndEvalBridge() {
        let script = AssetLoader.text("panel", ext: "js")
        let shell = AssetLoader.text("panel-shell", ext: "html")

        XCTAssertTrue(shell.contains("sourceEditor"))
        XCTAssertTrue(shell.contains("sourceApply"))
        XCTAssertTrue(shell.contains("sourceRevert"))
        XCTAssertTrue(script.contains("sourceCheckpoint"))
        XCTAssertTrue(script.contains("sourceWriteScript"))
        XCTAssertTrue(script.contains("source-apply"))
        XCTAssertTrue(script.contains("source-revert"))
        XCTAssertTrue(script.contains("postPanelEval"))
        XCTAssertTrue(script.contains("document.open();document.write(html);document.close()"))
    }

    func testSourcePanelUsesDenseIDELikeCodeLayout() {
        let shell = AssetLoader.text("panel-shell", ext: "html")

        XCTAssertTrue(shell.contains("content=\"width=device-width,initial-scale=1,maximum-scale=1,viewport-fit=cover\""))
        XCTAssertTrue(shell.contains("body { margin:0; padding:0; font-family:sans-serif; font-size:12px; color:var(--fg); background:var(--panel-bg);"))
        XCTAssertTrue(shell.contains("#source-tab .source-view { flex:1; min-height:0; overflow:auto; background:var(--panel-bg);"))
        XCTAssertTrue(shell.contains("#source-tab pre { margin:0; padding:6px 8px; font-size:10.5px; line-height:1.22; white-space:pre; word-wrap:normal; overflow-wrap:normal; tab-size:2;"))
        XCTAssertTrue(shell.contains("#source-tab .source-editor-wrap { display:none; position:relative; flex:1; min-height:0; overflow:hidden; background:var(--panel-bg);"))
        XCTAssertTrue(shell.contains(".source-editor-highlight, #sourceEditor { position:absolute; inset:0; box-sizing:border-box; width:100%; height:100%; margin:0; border:0; padding:6px 8px; font:10.5px/1.22 ui-monospace,SFMono-Regular,Menlo,monospace; white-space:pre; overflow:auto; overflow-wrap:normal; tab-size:2;"))
        XCTAssertTrue(shell.contains("-webkit-text-size-adjust:100%; -webkit-appearance:none; caret-color:#93c5fd;"))
    }

    func testSourceEditorRequestsTallInspectorAndProvidesFindControls() {
        let script = AssetLoader.text("panel", ext: "js")
        let shell = AssetLoader.text("panel-shell", ext: "html")

        XCTAssertTrue(shell.contains("sourceFind"))
        XCTAssertTrue(shell.contains("sourceFindPrev"))
        XCTAssertTrue(shell.contains("sourceFindNext"))
        XCTAssertTrue(shell.contains("sourceFindCount"))
        XCTAssertTrue(script.contains("requestInspectorResize('source-edit')"))
        XCTAssertTrue(script.contains("requestInspectorResize('normal')"))
        XCTAssertTrue(script.contains("findInSource"))
        XCTAssertTrue(script.contains("selectSourceMatch"))
        XCTAssertTrue(script.contains("sourceFindMatches"))
    }

    func testSourceEditModeKeepsSyntaxHighlightedOverlay() {
        let script = AssetLoader.text("panel", ext: "js")
        let shell = AssetLoader.text("panel-shell", ext: "html")

        XCTAssertTrue(shell.contains("sourceEditorWrap"))
        XCTAssertTrue(shell.contains("sourceEditorHighlight"))
        XCTAssertTrue(shell.contains("#source-tab.editing .source-editor-wrap { display:block;"))
        XCTAssertTrue(shell.contains("-webkit-text-fill-color:transparent;"))
        XCTAssertTrue(shell.contains(".source-editor-highlight"))
        XCTAssertTrue(script.contains("renderSourceEditorHighlight"))
        XCTAssertTrue(script.contains("syncSourceEditorScroll"))
        XCTAssertTrue(script.contains("sourceEditorHighlight"))
    }

    func testSourceFindMarksMatchesAndScrollsCurrentResult() {
        let script = AssetLoader.text("panel", ext: "js")
        let shell = AssetLoader.text("panel-shell", ext: "html")

        XCTAssertTrue(shell.contains("source-find-match"))
        XCTAssertTrue(shell.contains("source-find-current"))
        XCTAssertTrue(script.contains("markSourceRanges"))
        XCTAssertTrue(script.contains("scrollActiveSourceMatch"))
        XCTAssertTrue(script.contains("renderSourceFindHighlights"))
        XCTAssertTrue(script.contains("scrollEditorToOffset"))
        XCTAssertTrue(script.contains("findInSource(e.shiftKey ? -1 : 1)"))
    }
}
