package com.mrdoob.browser

import org.json.JSONObject

private const val TAG = "PanelExtensionBridge"

class PanelExtensionBridge(private val router: ExtensionRouter) : JsonMessageBridge(TAG) {

    companion object {
        const val JS_OBJECT_NAME = "AndroidExtensionPanel"
    }

    override fun onMessage(obj: JSONObject) {
        when (obj.optString("type")) {
            EnvelopeType.PORT_CONNECT -> router.onPanelConnect(obj)
            EnvelopeType.PORT_MESSAGE -> router.onPanelPortMessage(obj)
            EnvelopeType.PORT_DISCONNECT -> router.onPanelPortDisconnect(obj)
        }
    }
}
