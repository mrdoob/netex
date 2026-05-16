package com.mrdoob.browser

import android.webkit.WebView
import org.json.JSONObject

/**
 * Glues the page WebView (where `background.js` lives) and the Three.js panel
 * WebView (where `panel.js` lives) together. Acts as the persistent piece — in
 * Chrome the MV3 service worker stays alive across page navigations; here the
 * `background.js` instance dies with the page, so the router keeps live panel
 * ports + their `MESSAGE_INIT` cached and replays them against each fresh
 * `background.js` (top-frame load and every same-origin iframe).
 */
class ExtensionRouter {

    private var pageView: WebView? = null
    private var panelView: WebView? = null
    private var pageReady: Boolean = false

    private val livePorts = LinkedHashMap<String, String>()
    // First port-message per port is always MESSAGE_INIT from upstream panel.js;
    // replay it after each fresh port-connect so the new bg.js learns its tabId.
    private val portSetupMessage = HashMap<String, String>()
    private val pendingPanelToPage = ArrayDeque<String>()

    fun bindPageView(view: WebView) { pageView = view }
    fun bindPanelView(view: WebView) { panelView = view }

    fun onPageNavigationStarted() {
        pageReady = false
        // Old queue belongs to the previous bg.js — it'll never see them.
        // panel-side polling will re-issue REQUEST_STATE; INIT replays from cache.
        pendingPanelToPage.clear()
        broadcastCommittedToPanel()
    }

    fun onPageReady(frameId: Int = 0) {
        pageReady = true
        // Sub-frame loads (e.g. an iframe swap on threejs.org/examples) never trigger
        // WebViewClient.onPageStarted, so the panel would otherwise pile up renderers
        // from each navigated example. Mirror onPageNavigationStarted's reset here.
        if (frameId != 0) broadcastCommittedToPanel()
        livePorts.forEach { (portId, name) ->
            dispatchToPage(connectEnvelope(portId, name))
            portSetupMessage[portId]?.let { dispatchToPage(it) }
        }
        while (pendingPanelToPage.isNotEmpty()) dispatchToPage(pendingPanelToPage.removeFirst())
    }

    private fun broadcastCommittedToPanel() {
        livePorts.keys.forEach { dispatchToPanel(committedEnvelope(it)) }
    }

    fun onPagePortMessage(envelope: JSONObject) { dispatchToPanel(envelope.toString()) }
    fun onPagePortDisconnect(envelope: JSONObject) { dispatchToPanel(envelope.toString()) }

    fun onPanelConnect(envelope: JSONObject) {
        val portId = envelope.optString("portId").takeIf { it.isNotEmpty() } ?: return
        val name = envelope.optString("name")
        livePorts[portId] = name
        if (pageReady) {
            dispatchToPage(connectEnvelope(portId, name))
            portSetupMessage[portId]?.let { dispatchToPage(it) }
        }
    }

    fun onPanelPortMessage(envelope: JSONObject) {
        val portId = envelope.optString("portId").takeIf { it.isNotEmpty() }
        if (portId != null) portSetupMessage.putIfAbsent(portId, envelope.toString())
        sendToPage(envelope.toString())
    }

    fun onPanelPortDisconnect(envelope: JSONObject) {
        val portId = envelope.optString("portId").takeIf { it.isNotEmpty() } ?: return
        livePorts.remove(portId)
        portSetupMessage.remove(portId)
        sendToPage(envelope.toString())
    }

    private fun sendToPage(envelopeJson: String) {
        if (pageReady) dispatchToPage(envelopeJson) else pendingPanelToPage.addLast(envelopeJson)
    }

    private fun dispatchToPanel(envelopeJson: String) {
        val view = panelView ?: return
        view.post {
            view.evaluateJavascript("window.__netexExt && window.__netexExt.dispatch($envelopeJson)", null)
        }
    }

    private fun dispatchToPage(envelopeJson: String) {
        val view = pageView ?: return
        view.post {
            // The shim defines dispatchAllFrames at document-start, so this
            // recurses into every same-origin iframe (three.js demos often run
            // inside one).
            view.evaluateJavascript("window.__netexExt && window.__netexExt.dispatchAllFrames($envelopeJson)", null)
        }
    }

    private fun connectEnvelope(portId: String, name: String): String =
        JSONObject().apply {
            put("type", EnvelopeType.PORT_CONNECT)
            put("portId", portId)
            put("name", name)
        }.toString()

    private fun committedEnvelope(portId: String): String =
        JSONObject().apply {
            put("type", EnvelopeType.PORT_MESSAGE)
            put("portId", portId)
            put("message", JSONObject().apply {
                put("id", "three-devtools")
                put("name", "committed")
                put("frameId", 0)
            })
        }.toString()
}
