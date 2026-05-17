import UIKit
import WebKit

final class BrowserViewController: UIViewController {
    private enum PanelTab: Int {
        case console = 0
        case source = 1
        case network = 2
        case three = 3
    }

    private let navigationPolicy = NetexNavigationPolicy()
    private let homeButton = UIButton(type: .system)
    private let pageTitleLabel = UILabel()
    private let reloadButton = UIButton(type: .system)
    private let examplesButton = UIButton(type: .system)
    private let hideInspectorButton = UIButton(type: .system)
    private let showInspectorButton = UIButton(type: .system)
    private let tabs = UISegmentedControl(items: ["Console", "Source", "Network", "Three.js"])
    private let pageProgress = UIProgressView(progressViewStyle: .bar)
    private let panelHeader = UIStackView()
    private let panelContainer = UIView()
    private let blockedNavigationLabel = UILabel()
    private var pageView: WKWebView!
    private var panelView: WKWebView!
    private var threePanelView: WKWebView!
    private var panelRenderer: PanelRenderer!
    private var extensionRouter = ExtensionRouter()
    private var blobStore = BlobStore(limit: 64)
    private var panelHeightConstraint: NSLayoutConstraint!
    private var progressObservation: NSKeyValueObservation?
    private var titleObservation: NSKeyValueObservation?
    private var urlObservation: NSKeyValueObservation?
    private var currentPageURL = "about:blank"
    private var customMainFrameHost: String?

    private struct Example {
        let title: String
        let urlString: String
    }

    private let examples: [Example] = [
        Example(title: "Animated City Block", urlString: "https://threejs.org/examples/#webgl_animation_keyframes"),
        Example(title: "glTF Loader", urlString: "https://threejs.org/examples/#webgl_loader_gltf"),
        Example(title: "Examples Gallery", urlString: "https://threejs.org/examples/")
    ]

    private lazy var consoleBatcher = MessageBatcher<ConsoleEntry>(maxBatchSize: 32, delay: 0.05) { [weak self] batch in
        self?.panelRenderer.appendConsole(batch)
    }
    private lazy var networkBatcher = MessageBatcher<NetworkEntry>(maxBatchSize: 32, delay: 0.05) { [weak self] batch in
        guard let self else { return }
        batch.forEach { self.panelRenderer.appendNetwork($0, pageURL: self.currentPageURL) }
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        NetexMetrics.mark("app-view-did-load")
        view.backgroundColor = .systemBackground
        buildWebViews()
        buildChrome()
        panelRenderer = PanelRenderer(panelView: panelView, pageView: pageView)
        panelRenderer.load()
        installObservers()
        loadInitialPage()
    }

    private func buildWebViews() {
        pageView = makePageWebView()
        panelView = makePanelWebView(messageName: "panel")
        threePanelView = makeThreePanelWebView()
        threePanelView.isHidden = true
    }

    private func makePageWebView() -> WKWebView {
        let contentController = WKUserContentController()
        contentController.add(self, name: "netex")
        installPageScripts(into: contentController)

        let configuration = WKWebViewConfiguration()
        configuration.userContentController = contentController
        configuration.setURLSchemeHandler(NetexAssetSchemeHandler(), forURLScheme: NetexAssetSchemeHandler.scheme)
        configuration.defaultWebpagePreferences.allowsContentJavaScript = true
        configuration.preferences.javaScriptCanOpenWindowsAutomatically = true
        configuration.allowsInlineMediaPlayback = true
        configuration.mediaTypesRequiringUserActionForPlayback = []

        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.allowsBackForwardNavigationGestures = true
        webView.customUserAgent = "Netex/0.3.0 iOS"
        webView.accessibilityIdentifier = "netex.page"
        enableInspection(webView)
        return webView
    }

    private func makePanelWebView(messageName: String) -> WKWebView {
        let controller = WKUserContentController()
        controller.add(self, name: messageName)
        let configuration = WKWebViewConfiguration()
        configuration.userContentController = controller
        configuration.setURLSchemeHandler(NetexAssetSchemeHandler(), forURLScheme: NetexAssetSchemeHandler.scheme)
        configuration.defaultWebpagePreferences.allowsContentJavaScript = true
        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.accessibilityIdentifier = messageName == "panel" ? "netex.panel" : "netex.\(messageName)"
        enableInspection(webView)
        return webView
    }

    private func makeThreePanelWebView() -> WKWebView {
        let controller = WKUserContentController()
        controller.add(self, name: "netexPanel")
        let shim = AssetLoader.text("chrome-shim-panel", ext: "js")
            .replacingOccurrences(of: "AndroidExtensionPanel.postMessage", with: "window.webkit.messageHandlers.netexPanel.postMessage")
            .replacingOccurrences(of: "asset:///threejs-devtools/", with: "\(NetexAssetSchemeHandler.scheme)://bundle/NetexAssets/threejs-devtools/")
        controller.addUserScript(WKUserScript(source: shim, injectionTime: .atDocumentStart, forMainFrameOnly: false))

        let configuration = WKWebViewConfiguration()
        configuration.userContentController = controller
        configuration.setURLSchemeHandler(NetexAssetSchemeHandler(), forURLScheme: NetexAssetSchemeHandler.scheme)
        configuration.defaultWebpagePreferences.allowsContentJavaScript = true
        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = self
        webView.accessibilityIdentifier = "netex.threePanel"
        enableInspection(webView)
        webView.load(URLRequest(url: AssetLoader.assetURL("NetexAssets/threejs-devtools/panel/panel.html")))
        return webView
    }

    private func enableInspection(_ webView: WKWebView) {
        #if DEBUG
        if #available(iOS 16.4, *) {
            webView.isInspectable = true
        }
        #endif
    }

    private func buildChrome() {
        homeButton.setImage(UIImage(systemName: "house"), for: .normal)
        homeButton.addTarget(self, action: #selector(homeTapped), for: .touchUpInside)
        homeButton.accessibilityLabel = "Home"
        homeButton.accessibilityIdentifier = "netex.home"
        homeButton.widthAnchor.constraint(equalToConstant: 36).isActive = true

        pageTitleLabel.text = "Netex"
        pageTitleLabel.font = .preferredFont(forTextStyle: .headline)
        pageTitleLabel.adjustsFontForContentSizeCategory = true
        pageTitleLabel.lineBreakMode = .byTruncatingMiddle
        pageTitleLabel.textAlignment = .center
        pageTitleLabel.accessibilityIdentifier = "netex.pageTitle"
        pageTitleLabel.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)

        reloadButton.setImage(UIImage(systemName: "arrow.clockwise"), for: .normal)
        reloadButton.addTarget(self, action: #selector(reloadTapped), for: .touchUpInside)
        reloadButton.accessibilityLabel = "Reload"
        reloadButton.accessibilityIdentifier = "netex.reload"
        reloadButton.widthAnchor.constraint(equalToConstant: 36).isActive = true

        examplesButton.setImage(UIImage(systemName: "ellipsis.circle"), for: .normal)
        examplesButton.accessibilityLabel = "Examples"
        examplesButton.accessibilityIdentifier = "netex.examples"
        examplesButton.showsMenuAsPrimaryAction = true
        examplesButton.menu = makeExamplesMenu()
        examplesButton.widthAnchor.constraint(equalToConstant: 36).isActive = true

        hideInspectorButton.setImage(UIImage(systemName: "chevron.down"), for: .normal)
        hideInspectorButton.addTarget(self, action: #selector(hideInspectorTapped), for: .touchUpInside)
        hideInspectorButton.accessibilityLabel = "Hide Inspector"
        hideInspectorButton.accessibilityIdentifier = "netex.hideInspector"
        hideInspectorButton.widthAnchor.constraint(equalToConstant: 36).isActive = true

        var showConfig = UIButton.Configuration.filled()
        showConfig.image = UIImage(systemName: "chevron.up")
        showConfig.title = "Inspector"
        showConfig.imagePadding = 6
        showConfig.cornerStyle = .capsule
        showInspectorButton.configuration = showConfig
        showInspectorButton.addTarget(self, action: #selector(showInspectorTapped), for: .touchUpInside)
        showInspectorButton.accessibilityLabel = "Show Inspector"
        showInspectorButton.accessibilityIdentifier = "netex.showInspector"
        showInspectorButton.isHidden = true
        showInspectorButton.translatesAutoresizingMaskIntoConstraints = false

        tabs.selectedSegmentIndex = 0
        tabs.addTarget(self, action: #selector(tabChanged), for: .valueChanged)
        tabs.accessibilityIdentifier = "netex.tabs"

        blockedNavigationLabel.textAlignment = .center
        blockedNavigationLabel.font = .preferredFont(forTextStyle: .footnote)
        blockedNavigationLabel.textColor = .secondaryLabel
        blockedNavigationLabel.backgroundColor = .secondarySystemBackground
        blockedNavigationLabel.numberOfLines = 2
        blockedNavigationLabel.isHidden = true
        blockedNavigationLabel.accessibilityIdentifier = "netex.blockedNavigation"

        let toolbar = UIStackView(arrangedSubviews: [homeButton, pageTitleLabel, reloadButton, examplesButton])
        toolbar.axis = .horizontal
        toolbar.spacing = 8
        toolbar.alignment = .center
        toolbar.layoutMargins = UIEdgeInsets(top: 8, left: 12, bottom: 8, right: 12)
        toolbar.isLayoutMarginsRelativeArrangement = true

        panelHeader.addArrangedSubview(tabs)
        panelHeader.addArrangedSubview(hideInspectorButton)
        panelHeader.axis = .horizontal
        panelHeader.spacing = 8
        panelHeader.alignment = .center
        panelHeader.layoutMargins = UIEdgeInsets(top: 8, left: 12, bottom: 8, right: 12)
        panelHeader.isLayoutMarginsRelativeArrangement = true
        panelHeader.addGestureRecognizer(UIPanGestureRecognizer(target: self, action: #selector(panelPanned(_:))))

        [panelView, threePanelView].forEach {
            $0.translatesAutoresizingMaskIntoConstraints = false
            panelContainer.addSubview($0)
            NSLayoutConstraint.activate([
                $0.leadingAnchor.constraint(equalTo: panelContainer.leadingAnchor),
                $0.trailingAnchor.constraint(equalTo: panelContainer.trailingAnchor),
                $0.topAnchor.constraint(equalTo: panelContainer.topAnchor),
                $0.bottomAnchor.constraint(equalTo: panelContainer.bottomAnchor)
            ])
        }

        let stack = UIStackView(arrangedSubviews: [toolbar, blockedNavigationLabel, pageProgress, pageView, panelHeader, panelContainer])
        stack.axis = .vertical
        stack.spacing = 0
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        view.addSubview(showInspectorButton)

        panelHeightConstraint = panelContainer.heightAnchor.constraint(equalToConstant: defaultPanelHeight)
        panelHeightConstraint.isActive = true

        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor),
            stack.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            stack.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor),
            showInspectorButton.centerXAnchor.constraint(equalTo: view.safeAreaLayoutGuide.centerXAnchor),
            showInspectorButton.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -12)
        ])
    }

    private var defaultPanelHeight: CGFloat {
        max(220, view.bounds.height * 0.34)
    }

    private func installPageScripts(into controller: WKUserContentController) {
        NetexScriptPlan(mode: .current).scripts.forEach { script in
            controller.addUserScript(WKUserScript(source: script.source, injectionTime: .atDocumentStart, forMainFrameOnly: false))
        }
    }

    private func installObservers() {
        progressObservation = pageView.observe(\.estimatedProgress, options: [.new]) { [weak self] webView, _ in
            let progress = Float(webView.estimatedProgress)
            self?.pageProgress.setProgress(progress, animated: true)
            self?.pageProgress.isHidden = progress >= 1.0
        }
        urlObservation = pageView.observe(\.url, options: [.new]) { [weak self] webView, _ in
            self?.syncPageTitle(url: webView.url)
        }
        titleObservation = pageView.observe(\.title, options: [.new]) { [weak self] _, _ in
            self?.panelRenderer.refreshSource()
        }
        NotificationCenter.default.addObserver(self, selector: #selector(saveCurrentURL), name: UIApplication.didEnterBackgroundNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(memoryWarning), name: UIApplication.didReceiveMemoryWarningNotification, object: nil)
    }

    private func loadInitialPage() {
        let options = NetexLaunchOptions()
        if options.reset {
            UserDefaults.standard.removeObject(forKey: "lastURL")
        }
        if let url = options.initialURL {
            load(url, persist: false)
        } else if let saved = UserDefaults.standard.string(forKey: "lastURL"), let url = URL(string: saved), !saved.hasPrefix("\(NetexAssetSchemeHandler.scheme):") {
            load(url)
        } else {
            loadStartPage()
        }
    }

    private func loadStartPage() {
        customMainFrameHost = nil
        load(AssetLoader.assetURL("NetexAssets/start.html"), persist: false)
    }

    private func load(_ url: URL, persist: Bool = true) {
        NetexMetrics.mark("navigation-start", metadata: ["url": url.absoluteString])
        currentPageURL = url.absoluteString
        panelRenderer.clearNetwork()
        pageView.load(URLRequest(url: url))
        syncPageTitle(url: url)
        if persist, url.scheme != NetexAssetSchemeHandler.scheme {
            UserDefaults.standard.set(url.absoluteString, forKey: "lastURL")
        }
    }

    private func syncPageTitle(url: URL?) {
        currentPageURL = url?.absoluteString ?? currentPageURL
        pageTitleLabel.text = displayTitle(for: url)
        pageTitleLabel.accessibilityValue = url?.absoluteString
    }

    private func displayTitle(for url: URL?) -> String {
        guard let url else { return "Netex" }
        if url.scheme == NetexAssetSchemeHandler.scheme {
            return "Netex"
        }
        if let host = url.host, host.contains("threejs.org") {
            if let fragment = url.fragment, !fragment.isEmpty {
                return fragment
            }
            if url.path != "/" && !url.path.isEmpty {
                return url.lastPathComponent.replacingOccurrences(of: ".html", with: "")
            }
            return "Three.js Examples"
        }
        return url.host ?? NetexURL.displayURL(url)
    }

    private func makeExamplesMenu() -> UIMenu {
        let exampleActions = examples.compactMap { example -> UIAction? in
            guard let url = URL(string: example.urlString) else { return nil }
            return UIAction(title: example.title) { [weak self] _ in
                self?.customMainFrameHost = nil
                self?.load(url)
            }
        }
        let custom = UIAction(title: "Open Custom URL...", image: UIImage(systemName: "link")) { [weak self] _ in
            self?.presentCustomURLPrompt()
        }
        return UIMenu(title: "Open", children: exampleActions + [custom])
    }

    private func presentCustomURLPrompt() {
        let alert = UIAlertController(title: "Open Custom URL", message: "Advanced: inspect a custom Three.js page.", preferredStyle: .alert)
        alert.addTextField { textField in
            textField.placeholder = "https://example.com"
            textField.keyboardType = .URL
            textField.autocapitalizationType = .none
            textField.autocorrectionType = .no
        }
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        alert.addAction(UIAlertAction(title: "Open", style: .default) { [weak self, weak alert] _ in
            guard let raw = alert?.textFields?.first?.text,
                  let url = NetexURL.resolved(from: raw)
            else { return }
            self?.customMainFrameHost = url.host?.lowercased()
            self?.load(url)
        })
        present(alert, animated: true)
    }

    @objc private func reloadTapped() {
        if pageView.url?.scheme == NetexAssetSchemeHandler.scheme {
            loadStartPage()
        } else {
            pageView.reload()
        }
    }

    @objc private func homeTapped() {
        loadStartPage()
    }

    @objc private func hideInspectorTapped() {
        setInspectorHidden(true)
    }

    @objc private func showInspectorTapped() {
        setInspectorHidden(false)
    }

    private func setInspectorHidden(_ hidden: Bool, animated: Bool = true) {
        let changes = {
            self.panelHeader.isHidden = hidden
            self.panelContainer.isHidden = hidden
            self.showInspectorButton.isHidden = !hidden
            self.view.layoutIfNeeded()
        }

        if animated {
            UIView.animate(withDuration: 0.2, delay: 0, options: [.curveEaseOut], animations: changes)
        } else {
            changes()
        }
    }

    private func showBlockedNavigation(_ url: URL?) {
        let host = url?.host ?? "external page"
        blockedNavigationLabel.text = "Blocked external navigation to \(host). Use Home to return to Netex."
        blockedNavigationLabel.isHidden = false
        UIAccessibility.post(notification: .announcement, argument: blockedNavigationLabel.text)
        DispatchQueue.main.asyncAfter(deadline: .now() + 4) { [weak self] in
            self?.blockedNavigationLabel.isHidden = true
        }
    }

    @objc private func tabChanged() {
        let tab = PanelTab(rawValue: tabs.selectedSegmentIndex) ?? .console
        panelView.isHidden = tab == .three
        threePanelView.isHidden = tab != .three

        switch tab {
        case .console:
            panelRenderer.setTab(.console)
        case .source:
            panelRenderer.setTab(.source)
            panelRenderer.refreshSource()
        case .network:
            panelRenderer.setTab(.network)
        case .three:
            NetexMetrics.mark("three-panel-visible")
        }
    }

    @objc private func panelPanned(_ recognizer: UIPanGestureRecognizer) {
        let translation = recognizer.translation(in: view)
        let minHeight: CGFloat = 120
        let maxHeight = max(minHeight, view.bounds.height - 180)
        switch recognizer.state {
        case .changed:
            panelHeightConstraint.constant = min(max(panelHeightConstraint.constant - translation.y, minHeight), maxHeight)
            recognizer.setTranslation(.zero, in: view)
        case .ended, .cancelled:
            let targets = [minHeight, view.bounds.height * 0.34, view.bounds.height * 0.66, maxHeight]
            let nearest = targets.min(by: { abs($0 - panelHeightConstraint.constant) < abs($1 - panelHeightConstraint.constant) }) ?? defaultPanelHeight
            UIView.animate(withDuration: 0.22, delay: 0, options: [.curveEaseOut]) {
                self.panelHeightConstraint.constant = nearest
                self.view.layoutIfNeeded()
            }
        default:
            break
        }
    }

    @objc private func saveCurrentURL() {
        guard let url = pageView.url, url.scheme != NetexAssetSchemeHandler.scheme else { return }
        UserDefaults.standard.set(url.absoluteString, forKey: "lastURL")
    }

    @objc private func memoryWarning() {
        NetexMetrics.mark("memory-warning")
    }

    private func route(_ envelope: BridgeEnvelope, from handlerName: String) {
        switch envelope.type {
        case .consoleEntry:
            consoleBatcher.append(consoleEntry(from: envelope))
            NetexMetrics.mark("first-console-row", metadata: ["source": envelope.source])
        case .consoleBatch:
            let entries = envelope.payload["entries"] as? [[String: Any]] ?? []
            entries
                .map { BridgeEnvelope(type: .consoleEntry, source: envelope.source, payload: $0) }
                .forEach { consoleBatcher.append(consoleEntry(from: $0)) }
        case .networkRecord:
            networkBatcher.append(networkEntry(from: envelope))
            NetexMetrics.mark("first-network-row", metadata: ["source": envelope.source])
        case .networkBatch:
            let entries = envelope.payload["entries"] as? [[String: Any]] ?? []
            entries
                .map { BridgeEnvelope(type: .networkRecord, source: envelope.source, payload: $0) }
                .forEach { networkBatcher.append(networkEntry(from: $0)) }
        case .blobResponse:
            if let rid = envelope.payload["requestId"] as? String, let body = envelope.payload["body"] as? String {
                blobStore.insert(rid, dataURL: body)
                panelRenderer.deliverBlob(rid: rid, dataURL: body)
            }
        case .fetchBlob:
            if let rid = envelope.payload["requestId"] as? String {
                if let dataURL = blobStore.value(for: rid) {
                    panelRenderer.deliverBlob(rid: rid, dataURL: dataURL)
                } else {
                    pageView.evaluateJavaScript("window.__netex && window.__netex.findBlob(\(JSONBridge.quoted(rid)))")
                }
            }
        case .panelEval:
            handlePanelEval(envelope)
        case .perfMark:
            NetexMetrics.mark(envelope.payload["name"] as? String ?? "perf.mark", metadata: envelope.payload)
        case .pageReady:
            NetexMetrics.mark("three-page-ready", metadata: envelope.payload)
            replayPanelPortsToPage()
        case .extensionPortConnect, .extensionPortDisconnect:
            if handlerName == "netexPanel" {
                extensionRouter.handlePanelEnvelope(envelope)
                dispatchToPage(envelope)
            } else {
                dispatchToThreePanel(envelope)
            }
        case .extensionPortMessage:
            if handlerName == "netexPanel" {
                dispatchToPage(envelope)
            } else {
                dispatchToThreePanel(envelope)
            }
        case .actionSetBadgeText, .actionSetBadgeBackgroundColor, .actionSetIcon:
            dispatchToThreePanel(envelope)
        default:
            if envelope.payload["method"] != nil || envelope.payload["url"] != nil {
                networkBatcher.append(networkEntry(from: envelope))
            }
        }
    }

    private func replayPanelPortsToPage() {
        extensionRouter.replayForPageReady().forEach { dispatchToPage($0) }
    }

    private func dispatchToPage(_ envelope: BridgeEnvelope) {
        pageView.evaluateJavaScript("window.__netexExt && window.__netexExt.dispatchAllFrames(\(envelope.jsonString()))")
    }

    private func dispatchToThreePanel(_ envelope: BridgeEnvelope) {
        threePanelView.evaluateJavaScript("window.__netexExt && window.__netexExt.dispatch(\(envelope.jsonString()))")
    }

    private func handlePanelEval(_ envelope: BridgeEnvelope) {
        let evalID = envelope.id ?? envelope.payload["id"] as? String ?? UUID().uuidString
        guard let source = envelope.payload["source"] as? String else { return }
        pageView.evaluateJavaScript(source) { [weak self] value, error in
            let result: [Any] = {
                if let error { return [false, error.localizedDescription] }
                if let value { return [true, String(describing: value)] }
                return [true, "undefined"]
            }()
            self?.panelRenderer.deliverEvalResult(id: evalID, resultJSON: JSONBridge.jsonString(result))
        }
    }

    private func consoleEntry(from envelope: BridgeEnvelope) -> ConsoleEntry {
        ConsoleEntry(
            level: envelope.payload["level"] as? String ?? "log",
            segments: envelope.payload["segments"] as? [[String: Any]] ?? [["kind": "primitive", "text": envelope.payload["message"] as? String ?? ""]],
            source: envelope.payload["sourceId"] as? String ?? envelope.source,
            line: envelope.payload["line"] as? Int ?? envelope.payload["lineNumber"] as? Int ?? 0
        )
    }

    private func networkEntry(from envelope: BridgeEnvelope) -> NetworkEntry {
        NetworkEntry(
            method: envelope.payload["method"] as? String ?? "GET",
            url: envelope.payload["url"] as? String ?? "",
            status: envelope.payload["status"] as? Int ?? 0,
            body: envelope.payload["body"] as? String ?? "",
            contentType: envelope.payload["contentType"] as? String ?? "",
            durationMs: envelope.payload["durationMs"] as? Int ?? envelope.payload["duration"] as? Int ?? 0,
            size: envelope.payload["size"] as? Int ?? envelope.payload["sizeBytes"] as? Int ?? 0,
            requestID: envelope.payload["requestId"] as? String ?? "",
            pending: envelope.payload["pending"] as? Bool ?? false
        )
    }
}

extension BrowserViewController: WKNavigationDelegate {
    func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        if webView === pageView {
            NetexMetrics.mark("wk-did-start", metadata: ["url": webView.url?.absoluteString ?? ""])
        }
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        if webView === pageView {
            syncPageTitle(url: webView.url)
            panelRenderer.refreshSource()
            NetexMetrics.mark("wk-did-finish", metadata: ["url": webView.url?.absoluteString ?? ""])
        } else if webView === threePanelView {
            NetexMetrics.mark("three-panel-ready")
        }
    }

    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        if webView === pageView {
            let targetIsMainFrame = navigationAction.targetFrame?.isMainFrame ?? true
            if navigationDecision(for: navigationAction.request.url, targetIsMainFrame: targetIsMainFrame) == .block {
                showBlockedNavigation(navigationAction.request.url)
                decisionHandler(.cancel)
                return
            }
        }
        decisionHandler(.allow)
    }
}

extension BrowserViewController: WKUIDelegate {
    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        if navigationAction.targetFrame == nil, let url = navigationAction.request.url {
            if navigationDecision(for: url, targetIsMainFrame: true) == .allow {
                load(url)
            } else {
                showBlockedNavigation(url)
            }
        }
        return nil
    }
}

private extension BrowserViewController {
    func navigationDecision(for url: URL?, targetIsMainFrame: Bool) -> NetexNavigationPolicy.Decision {
        if targetIsMainFrame,
           let host = url?.host?.lowercased(),
           let customMainFrameHost,
           host == customMainFrameHost {
            return .allow
        }
        return navigationPolicy.decision(for: url, targetIsMainFrame: targetIsMainFrame)
    }
}

extension BrowserViewController: WKScriptMessageHandler {
    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        let fallback: BridgeEnvelopeType? = message.name == "panel" ? .panelEval : nil
        guard let envelope = BridgeEnvelope(message.body, fallbackType: fallback, fallbackSource: message.name) else { return }
        route(envelope, from: message.name)
    }
}
