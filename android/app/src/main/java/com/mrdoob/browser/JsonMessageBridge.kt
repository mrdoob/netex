package com.mrdoob.browser

import android.net.Uri
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import org.json.JSONObject

abstract class JsonMessageBridge(private val tag: String) : WebViewCompat.WebMessageListener {

    protected abstract fun onMessage(obj: JSONObject)

    final override fun onPostMessage(
        view: WebView,
        message: WebMessageCompat,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        replyProxy: JavaScriptReplyProxy
    ) {
        dispatch(message.data ?: return)
    }

    inner class Fallback {
        @JavascriptInterface
        fun postMessage(json: String) = dispatch(json)
    }

    private fun dispatch(json: String) {
        val obj = try {
            JSONObject(json)
        } catch (e: Exception) {
            Log.w(tag, "Bad JSON from page: $json", e)
            return
        }
        onMessage(obj)
    }
}
