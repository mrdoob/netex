import UIKit
import WebKit

final class PanelRenderer: NSObject, WKNavigationDelegate {
    enum Tab: String {
        case console
        case source
        case network
    }

    private weak var panelView: WKWebView?
    private weak var pageView: WKWebView?
    private var shellLoaded = false
    private var pending: [() -> Void] = []

    init(panelView: WKWebView, pageView: WKWebView) {
        self.panelView = panelView
        self.pageView = pageView
        super.init()
        panelView.navigationDelegate = self
    }

    func load() {
        guard let panelView else { return }
        let shell = AssetLoader.text("panel-shell", ext: "html")
        let script = AssetLoader.text("panel", ext: "js")
            .replacingOccurrences(of: "AndroidPanel.postMessage", with: "window.webkit.messageHandlers.panel.postMessage")
        let html = shell.replacingOccurrences(of: "<!--__PANEL_JS__-->", with: "<script>\(script)</script>")
        panelView.loadHTMLString(html, baseURL: AssetLoader.assetURL("NetexAssets/"))
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        guard !shellLoaded else { return }
        shellLoaded = true
        applyTheme()
        pending.forEach { $0() }
        pending.removeAll()
    }

    func setTab(_ tab: Tab) {
        afterLoad { [weak self] in
            self?.evaluate("window.__panel.setActiveTab('\(tab.rawValue)')")
        }
    }

    func refreshSource() {
        pageView?.evaluateJavaScript("document.documentElement.outerHTML") { [weak self] value, _ in
            guard let html = value as? String else { return }
            self?.afterLoad {
                self?.evaluate("window.__panel.setSource(\(JSONBridge.quoted(html)))")
            }
        }
    }

    func clearNetwork() {
        afterLoad { [weak self] in self?.evaluate("window.__panel.clearNetwork()") }
    }

    func appendNetwork(_ entry: NetworkEntry, pageURL: String) {
        let payload: [String: Any] = [
            "method": entry.method,
            "url": entry.url,
            "displayUrl": NetexURL.shortened(entry.url, pageURL: pageURL),
            "status": entry.status,
            "body": entry.body,
            "contentType": entry.contentType,
            "durationMs": entry.durationMs,
            "size": entry.size,
            "requestId": entry.requestID,
            "pending": entry.pending
        ]
        afterLoad { [weak self] in
            self?.evaluate("window.__panel.appendNetworkRow(\(JSONBridge.jsonString(payload)))")
        }
    }

    func appendNetwork(_ entries: [NetworkEntry], pageURL: String) {
        entries.forEach { appendNetwork($0, pageURL: pageURL) }
    }

    func appendConsole(_ entry: ConsoleEntry) {
        let payload: [String: Any] = [
            "level": entry.level,
            "segments": entry.segments,
            "sourceId": entry.source,
            "line": entry.line
        ]
        afterLoad { [weak self] in
            self?.evaluate("window.__panel.appendConsoleEntry(\(JSONBridge.jsonString(payload)))")
        }
    }

    func appendConsole(_ entries: [ConsoleEntry]) {
        entries.forEach { appendConsole($0) }
    }

    func deliverEvalResult(id: String, resultJSON: String) {
        afterLoad { [weak self] in
            self?.evaluate("window.__panel.deliverEvalResult(\(JSONBridge.quoted(id)), \(JSONBridge.quoted(resultJSON)))")
        }
    }

    func deliverBlob(rid: String, dataURL: String) {
        afterLoad { [weak self] in
            self?.evaluate("window.__panel.deliverBlob(\(JSONBridge.quoted(rid)), \(JSONBridge.quoted(dataURL)))")
        }
    }

    private func applyTheme() {
        let dark = UIScreen.main.traitCollection.userInterfaceStyle == .dark
        let fg = dark ? "#cccccc" : "#222222"
        let panelBg = dark ? "#1f1f23" : "#f6f6f7"
        let viewerBg = dark ? "#2c2c30" : "#ffffff"
        evaluate("window.__panel.setTheme({ dark: \(dark), fg: '\(fg)', panelBg: '\(panelBg)', viewerBg: '\(viewerBg)' })")
    }

    private func afterLoad(_ action: @escaping () -> Void) {
        if shellLoaded { action() } else { pending.append(action) }
    }

    private func evaluate(_ script: String) {
        panelView?.evaluateJavaScript(script)
    }
}
