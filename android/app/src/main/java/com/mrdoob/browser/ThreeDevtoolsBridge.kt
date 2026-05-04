package com.mrdoob.browser

import android.net.Uri
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import org.json.JSONObject

private const val TAG = "ThreeDevtoolsBridge"

class ThreeDevtoolsBridge : WebViewCompat.WebMessageListener {

    companion object {
        const val JS_OBJECT_NAME = "AndroidThreeDevtools"
        const val EVENT_REGISTER = "register"
    }

    override fun onPostMessage(
        view: WebView,
        message: WebMessageCompat,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        replyProxy: JavaScriptReplyProxy
    ) {
        handle(message.data ?: return)
    }

    inner class Fallback {
        @JavascriptInterface
        fun postMessage(json: String) {
            handle(json)
        }
    }

    private fun handle(json: String) {
        val obj = try {
            JSONObject(json)
        } catch (e: Exception) {
            Log.w(TAG, "Bad JSON from page: $json", e)
            return
        }
        val name = obj.optString("name")
        val detail = obj.optJSONObject("detail")
        when (name) {
            EVENT_REGISTER -> {
                val revision = detail?.optString("revision")?.takeIf { it.isNotEmpty() } ?: return
                Log.d(TAG, "register revision=$revision")
                DetectionStore.setRevision(revision)
            }
            else -> Log.v(TAG, "event=$name detail=$detail")
        }
    }
}
