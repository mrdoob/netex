package com.mrdoob.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import org.json.JSONTokener

class PanelRenderer(
    private val activity: MainActivity,
    private val panelView: WebView,
    private val mainView: WebView
) {
    enum class Tab { SOURCE, NETWORK, THREEJS }

    private var shellLoaded = false
    private val pending = ArrayDeque<() -> Unit>()

    @SuppressLint("SetJavaScriptEnabled")
    fun init() {
        panelView.settings.javaScriptEnabled = true
        panelView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        panelView.setBackgroundColor(panelView.materialColor(com.google.android.material.R.attr.colorSurfaceContainer))
        panelView.webChromeClient = WebChromeClient()
        panelView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                if (shellLoaded) return
                shellLoaded = true
                applyTheme()
                while (pending.isNotEmpty()) pending.removeFirst().invoke()
            }
        }
        val html = buildShellHtml()
        panelView.loadDataWithBaseURL(
            "https://cdn.jsdelivr.net/", html, "text/html", "UTF-8", null
        )
    }

    fun setTab(tab: Tab) {
        afterShellLoaded {
            val name = if (tab == Tab.SOURCE) "source" else "network"
            evalPanel("window.__panel.setActiveTab(${q(name)})")
        }
    }

    fun refreshSource() {
        mainView.evaluateJavascript("document.documentElement.outerHTML") { json ->
            val raw = try {
                JSONTokener(json).nextValue() as? String
            } catch (_: Exception) { null } ?: return@evaluateJavascript
            afterShellLoaded {
                evalPanel("window.__panel.setSource(${q(raw)})")
            }
        }
    }

    fun appendNetworkRow(record: NetworkLog.Record, displayUrl: String) {
        val payload = JSONObject().apply {
            put("method", record.method)
            put("url", record.url)
            put("displayUrl", displayUrl)
            put("status", record.status)
            put("body", record.body)
            put("contentType", record.contentType ?: "")
            put("durationMs", record.durationMs)
            put("size", record.sizeBytes)
            put("requestId", record.requestId ?: "")
        }.toString()
        afterShellLoaded {
            evalPanel("window.__panel.appendNetworkRow($payload)")
        }
    }

    fun clearNetwork() {
        afterShellLoaded {
            evalPanel("window.__panel.clearNetwork()")
        }
    }

    fun deliverBlob(rid: String, dataUrl: String) {
        afterShellLoaded {
            evalPanel("window.__panel.deliverBlob(${q(rid)}, ${q(dataUrl)})")
        }
    }

    private fun applyTheme() {
        val dark = isNightMode()
        val fg = if (dark) "#cccccc" else "#222222"
        val panelBg = panelView.materialColorHex(com.google.android.material.R.attr.colorSurfaceContainer)
        val viewerBg = panelView.materialColorHex(com.google.android.material.R.attr.colorSurfaceContainerHighest)
        evalPanel(
            "window.__panel.setTheme({ dark: $dark, fg: ${q(fg)}, panelBg: ${q(panelBg)}, viewerBg: ${q(viewerBg)} })"
        )
    }

    private fun afterShellLoaded(action: () -> Unit) {
        if (shellLoaded) action() else pending.addLast(action)
    }

    private fun evalPanel(js: String) {
        panelView.evaluateJavascript(js, null)
    }

    private fun q(s: String): String = JSONObject.quote(s)

    private fun isNightMode(): Boolean = (activity.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun buildShellHtml(): String {
        val context: Context = activity
        val shell = context.assets.open("panel-shell.html").bufferedReader().use { it.readText() }
        val js = context.assets.open("panel.js").bufferedReader().use { it.readText() }
        return shell.replace("<!--__PANEL_JS__-->", "<script>$js</script>")
    }
}
