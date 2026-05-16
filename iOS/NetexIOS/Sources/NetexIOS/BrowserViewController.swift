import UIKit
import WebKit

final class BrowserViewController: UIViewController {
    private let addressField = UITextField()
    private let reloadButton = UIButton(type: .system)
    private let tabs = UISegmentedControl(items: ["Console", "Source", "Network"])
    private let pageProgress = UIProgressView(progressViewStyle: .bar)
    private var pageView: WKWebView!
    private var panelView: WKWebView!
    private var panelRenderer: PanelRenderer!
    private var progressObservation: NSKeyValueObservation?
    private var titleObservation: NSKeyValueObservation?
    private var urlObservation: NSKeyValueObservation?
    private var currentPageURL = "about:blank"

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        buildWebViews()
        buildChrome()
        panelRenderer = PanelRenderer(panelView: panelView, pageView: pageView)
        panelRenderer.load()
        installObservers()
        load(URL(string: "https://threejs.org/examples/")!)
    }

    private func buildWebViews() {
        let contentController = WKUserContentController()
        contentController.add(self, name: "netex")
        contentController.add(self, name: "network")
        installPageScripts(into: contentController)

        let configuration = WKWebViewConfiguration()
        configuration.userContentController = contentController
        configuration.defaultWebpagePreferences.allowsContentJavaScript = true
        configuration.preferences.javaScriptCanOpenWindowsAutomatically = true
        configuration.allowsInlineMediaPlayback = true
        configuration.mediaTypesRequiringUserActionForPlayback = []

        pageView = WKWebView(frame: .zero, configuration: configuration)
        pageView.navigationDelegate = self
        pageView.uiDelegate = self
        pageView.allowsBackForwardNavigationGestures = true
        pageView.customUserAgent = "Netex/0.3.0 iOS"

        let panelController = WKUserContentController()
        panelController.add(self, name: "panel")
        let panelConfiguration = WKWebViewConfiguration()
        panelConfiguration.userContentController = panelController
        panelConfiguration.defaultWebpagePreferences.allowsContentJavaScript = true
        panelView = WKWebView(frame: .zero, configuration: panelConfiguration)
    }

    private func buildChrome() {
        addressField.borderStyle = .roundedRect
        addressField.autocapitalizationType = .none
        addressField.autocorrectionType = .no
        addressField.keyboardType = .URL
        addressField.returnKeyType = .go
        addressField.clearButtonMode = .whileEditing
        addressField.delegate = self
        addressField.placeholder = "Search or enter website"

        reloadButton.setImage(UIImage(systemName: "arrow.clockwise"), for: .normal)
        reloadButton.addTarget(self, action: #selector(reloadTapped), for: .touchUpInside)

        tabs.selectedSegmentIndex = 0
        tabs.addTarget(self, action: #selector(tabChanged), for: .valueChanged)

        let toolbar = UIStackView(arrangedSubviews: [addressField, reloadButton])
        toolbar.axis = .horizontal
        toolbar.spacing = 8
        toolbar.alignment = .center
        reloadButton.widthAnchor.constraint(equalToConstant: 36).isActive = true

        let panelHeader = UIStackView(arrangedSubviews: [tabs])
        panelHeader.axis = .horizontal
        panelHeader.layoutMargins = UIEdgeInsets(top: 8, left: 12, bottom: 8, right: 12)
        panelHeader.isLayoutMarginsRelativeArrangement = true

        let stack = UIStackView(arrangedSubviews: [toolbar, pageProgress, pageView, panelHeader, panelView])
        stack.axis = .vertical
        stack.spacing = 0
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)

        toolbar.layoutMargins = UIEdgeInsets(top: 8, left: 12, bottom: 8, right: 12)
        toolbar.isLayoutMarginsRelativeArrangement = true
        pageView.heightAnchor.constraint(equalTo: view.heightAnchor, multiplier: 0.58).isActive = true

        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor),
            stack.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            stack.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor)
        ])
    }

    private func installPageScripts(into controller: WKUserContentController) {
        let consoleShim = AssetLoader.text("console-shim", ext: "js")
            .replacingOccurrences(of: "AndroidBridge.postMessage", with: "window.webkit.messageHandlers.netex.postMessage")
            .replacingOccurrences(of: "AndroidExtension.postMessage", with: "window.webkit.messageHandlers.netex.postMessage")
        let networkShim = AssetLoader.text("network-shim", ext: "js")
            .replacingOccurrences(of: "AndroidNetwork.postMessage", with: "window.webkit.messageHandlers.network.postMessage")
        let orientationShim = AssetLoader.text("orientation-shim", ext: "js")
        [consoleShim, networkShim, orientationShim].forEach {
            controller.addUserScript(WKUserScript(source: $0, injectionTime: .atDocumentStart, forMainFrameOnly: false))
        }
    }

    private func installObservers() {
        progressObservation = pageView.observe(\.estimatedProgress, options: [.new]) { [weak self] webView, _ in
            let progress = Float(webView.estimatedProgress)
            self?.pageProgress.setProgress(progress, animated: true)
            self?.pageProgress.isHidden = progress >= 1.0
        }
        urlObservation = pageView.observe(\.url, options: [.new]) { [weak self] webView, _ in
            self?.syncAddress(webView.url)
        }
        titleObservation = pageView.observe(\.title, options: [.new]) { [weak self] _, _ in
            self?.panelRenderer.refreshSource()
        }
    }

    private func load(_ url: URL) {
        currentPageURL = url.absoluteString
        panelRenderer.clearNetwork()
        pageView.load(URLRequest(url: url))
        syncAddress(url)
    }

    private func syncAddress(_ url: URL?) {
        currentPageURL = url?.absoluteString ?? currentPageURL
        if !addressField.isFirstResponder {
            addressField.text = NetexURL.displayURL(url)
        }
    }

    @objc private func reloadTapped() {
        pageView.reload()
    }

    @objc private func tabChanged() {
        let tab: PanelRenderer.Tab = switch tabs.selectedSegmentIndex {
        case 1: .source
        case 2: .network
        default: .console
        }
        panelRenderer.setTab(tab)
        if tab == .source { panelRenderer.refreshSource() }
    }
}

extension BrowserViewController: UITextFieldDelegate {
    func textFieldShouldReturn(_ textField: UITextField) -> Bool {
        textField.resignFirstResponder()
        guard let url = NetexURL.resolved(from: textField.text ?? "") else { return false }
        load(url)
        return true
    }
}

extension BrowserViewController: WKNavigationDelegate {
    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        syncAddress(webView.url)
        panelRenderer.refreshSource()
    }

    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        decisionHandler(.allow)
    }
}

extension BrowserViewController: WKUIDelegate {
    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        if navigationAction.targetFrame == nil, let url = navigationAction.request.url {
            load(url)
        }
        return nil
    }
}

extension BrowserViewController: WKScriptMessageHandler {
    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        switch message.name {
        case "network":
            handleNetwork(message.body)
        case "panel":
            handlePanel(message.body)
        default:
            handleConsole(message.body)
        }
    }

    private func handleConsole(_ body: Any) {
        guard let object = JSONBridge.parseObject(body) else { return }
        let type = object["type"] as? String
        guard type == "console-entry" || object["level"] != nil else { return }
        let entry = ConsoleEntry(
            level: object["level"] as? String ?? "log",
            segments: object["segments"] as? [[String: Any]] ?? [["kind": "primitive", "text": object["message"] as? String ?? ""]],
            source: object["sourceId"] as? String ?? "",
            line: object["line"] as? Int ?? object["lineNumber"] as? Int ?? 0
        )
        panelRenderer.appendConsole(entry)
    }

    private func handleNetwork(_ body: Any) {
        guard let object = JSONBridge.parseObject(body) else { return }
        let entry = NetworkEntry(
            method: object["method"] as? String ?? "GET",
            url: object["url"] as? String ?? "",
            status: object["status"] as? Int ?? 0,
            body: object["body"] as? String ?? "",
            contentType: object["contentType"] as? String ?? "",
            durationMs: object["durationMs"] as? Int ?? 0,
            size: object["size"] as? Int ?? object["sizeBytes"] as? Int ?? 0,
            requestID: object["requestId"] as? String ?? "",
            pending: object["pending"] as? Bool ?? false
        )
        panelRenderer.appendNetwork(entry, pageURL: currentPageURL)
    }

    private func handlePanel(_ body: Any) {
        guard
            let object = JSONBridge.parseObject(body),
            object["type"] as? String == "eval",
            let id = object["id"] as? String,
            let source = object["source"] as? String
        else { return }

        pageView.evaluateJavaScript(source) { [weak self] value, error in
            let result: [Any] = {
                if let error { return [false, error.localizedDescription] }
                if let value { return [true, String(describing: value)] }
                return [true, "undefined"]
            }()
            self?.panelRenderer.deliverEvalResult(id: id, resultJSON: JSONBridge.jsonString(result))
        }
    }
}
