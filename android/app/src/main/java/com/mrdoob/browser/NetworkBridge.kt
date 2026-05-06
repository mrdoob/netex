package com.mrdoob.browser

import org.json.JSONObject

private const val TAG = "NetworkBridge"

class NetworkBridge(
    private val onBlobResponse: (String, String) -> Unit
) : JsonMessageBridge(TAG) {

    companion object {
        const val JS_OBJECT_NAME = "AndroidNetwork"
    }

    override fun onMessage(obj: JSONObject) {
        when (obj.optString("type")) {
            "blob-response" -> onBlobResponse(obj.optString("requestId"), obj.optString("body"))
            "" -> addRecord(obj)
        }
    }

    private fun addRecord(obj: JSONObject) {
        NetworkLog.add(
            NetworkLog.Record(
                method = obj.optString("method"),
                url = obj.optString("url"),
                status = obj.optInt("status"),
                body = obj.optString("body"),
                contentType = obj.optString("contentType").takeIf { it.isNotEmpty() },
                durationMs = obj.optLong("duration"),
                requestId = obj.optString("requestId").takeIf { it.isNotEmpty() }
            )
        )
    }
}
