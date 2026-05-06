package com.mrdoob.browser

import android.util.Log
import org.json.JSONObject

private const val TAG = "ThreeDevtoolsBridge"

class ThreeDevtoolsBridge : JsonMessageBridge(TAG) {

    companion object {
        const val JS_OBJECT_NAME = "AndroidThreeDevtools"
        const val EVENT_REGISTER = "register"
    }

    override fun onMessage(obj: JSONObject) {
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
