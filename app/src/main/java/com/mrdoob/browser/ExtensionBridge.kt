package com.mrdoob.browser

import org.json.JSONObject

private const val TAG = "ExtensionBridge"

class ExtensionBridge(
    private val router: ExtensionRouter,
    private val onOrientationLock: (String?) -> Unit,
    private val onConsoleEntry: (level: String, segmentsJson: String) -> Unit,
) : JsonMessageBridge(TAG) {

    companion object {
        const val JS_OBJECT_NAME = "AndroidExtension"
    }

    override fun onMessage(obj: JSONObject) {
        when (obj.optString("type")) {
            EnvelopeType.ACTION_SET_BADGE_TEXT -> DetectionStore.setBadgeText(obj.optString("text"))
            EnvelopeType.ACTION_SET_BADGE_BACKGROUND_COLOR ->
                DetectionStore.setBadgeBackgroundColor(obj.optString("color").takeIf { it.isNotEmpty() })
            EnvelopeType.PAGE_READY -> router.onPageReady()
            EnvelopeType.PORT_MESSAGE -> router.onPagePortMessage(obj)
            EnvelopeType.PORT_DISCONNECT -> router.onPagePortDisconnect(obj)
            EnvelopeType.ORIENTATION_LOCK -> onOrientationLock(obj.optString("orientation"))
            EnvelopeType.ORIENTATION_UNLOCK -> onOrientationLock(null)
            EnvelopeType.CONSOLE_ENTRY -> onConsoleEntry(
                obj.optString("level"),
                obj.optJSONArray("segments")?.toString() ?: "[]"
            )
        }
    }
}
