import Foundation

struct NetexScriptPlan {
    struct Script {
        let name: String
        let source: String
    }

    let mode: NetexShimMode

    var scriptNames: [String] {
        scripts.map(\.name)
    }

    var scripts: [Script] {
        var planned: [Script] = [
            Script(name: "performance-shim", source: AssetLoader.text("performance-shim", ext: "js"))
        ]

        if mode.installsConsole {
            planned.append(Script(
                name: "console-shim",
                source: AssetLoader.text("console-shim", ext: "js")
                    .replacingOccurrences(of: "AndroidExtension.postMessage", with: "window.webkit.messageHandlers.netex.postMessage")
            ))
        }

        if mode.installsNetwork {
            planned.append(Script(
                name: "network-shim",
                source: AssetLoader.text("network-shim", ext: "js")
                    .replacingOccurrences(of: "AndroidNetwork.postMessage", with: "window.webkit.messageHandlers.netex.postMessage")
            ))
        }

        if mode.installsExtension {
            planned.append(Script(name: "threejs-devtools", source: threeDevToolsSource))
        }

        if mode != .off {
            planned.append(Script(name: "orientation-shim", source: AssetLoader.text("orientation-shim", ext: "js")))
        }

        return planned.filter { !$0.source.isEmpty }
    }

    private var threeDevToolsSource: String {
        let constants = AssetLoader.text("constants", ext: "js")
        let chrome = AssetLoader.text("chrome-shim-page", ext: "js")
            .replacingOccurrences(of: "AndroidExtension.postMessage", with: "window.webkit.messageHandlers.netex.postMessage")
            .replacingOccurrences(of: "asset:///threejs-devtools/", with: "\(NetexAssetSchemeHandler.scheme)://bundle/NetexAssets/threejs-devtools/")
        let bridge = AssetLoader.text("bridge", ext: "js")
        let highlight = AssetLoader.text("highlight", ext: "js")
        let content = AssetLoader.text("content-script", ext: "js")
        let background = AssetLoader.text("background", ext: "js")
        return [
            constants,
            chrome,
            bridge,
            highlight,
            "(function(chrome){\n\(content)\n})(window.__netexExt.createContentScriptChrome());",
            background
        ].joined(separator: "\n;\n")
    }
}
