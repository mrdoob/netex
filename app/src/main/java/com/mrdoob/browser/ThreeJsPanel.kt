package com.mrdoob.browser

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

private const val PANEL_URL = "file:///android_asset/threejs-devtools/panel/panel.html"
private const val SHIM_ASSET = "chrome-shim-panel.js"

/**
 * Hosts the vendored three.js DevTools `panel.html` in its own WebView. The
 * panel-side chrome shim is injected at document start, so panel.js's calls
 * to `chrome.runtime.connect` and `chrome.devtools.inspectedWindow.tabId`
 * resolve against our native bridge.
 */
@SuppressLint("SetJavaScriptEnabled")
fun setupThreeJsPanel(context: Context, view: WebView, bridge: PanelExtensionBridge) {
    view.setBackgroundColor(view.materialColor(com.google.android.material.R.attr.colorSurfaceContainer))
    view.settings.javaScriptEnabled = true
    view.settings.domStorageEnabled = true
    view.settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
    view.settings.allowFileAccess = true
    view.webChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            if (BuildConfig.DEBUG) {
                Log.d("ThreeJsPanelConsole", "${message.messageLevel()} ${message.message()} (${message.sourceId()}:${message.lineNumber()})")
            }
            return true
        }
    }
    view.webViewClient = WebViewClient()

    val shim = context.assets.open(SHIM_ASSET).bufferedReader().use { it.readText() }
    val themeInjector = buildThreeJsPanelThemeInjector(view)
    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
        WebViewCompat.addDocumentStartJavaScript(view, "$shim\n;$themeInjector", setOf("*"))
    }
    if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
        WebViewCompat.addWebMessageListener(view, PanelExtensionBridge.JS_OBJECT_NAME, setOf("*"), bridge)
    } else {
        @SuppressLint("AddJavascriptInterface")
        view.addJavascriptInterface(bridge.Fallback(), PanelExtensionBridge.JS_OBJECT_NAME)
    }

    view.loadUrl(PANEL_URL)
}

private fun buildThreeJsPanelThemeInjector(view: WebView): String {
    val bg = view.materialColorHex(com.google.android.material.R.attr.colorSurfaceContainer)
    val fg = view.materialColorHex(com.google.android.material.R.attr.colorOnSurface)
    val css = "body{background:$bg!important;color:$fg!important}"
    return "(function(){var s=document.createElement('style');s.textContent=${JSONObject.quote(css)};document.documentElement.appendChild(s);})();"
}
