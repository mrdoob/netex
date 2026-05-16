package com.mrdoob.browser

/** Envelope `type` strings shared between the chrome shims and the native bridges. */
object EnvelopeType {
    const val PORT_CONNECT = "port-connect"
    const val PORT_MESSAGE = "port-message"
    const val PORT_DISCONNECT = "port-disconnect"
    const val PAGE_READY = "page-ready"
    const val ACTION_SET_BADGE_TEXT = "action.setBadgeText"
    const val ACTION_SET_BADGE_BACKGROUND_COLOR = "action.setBadgeBackgroundColor"
    const val ORIENTATION_LOCK = "orientation.lock"
    const val ORIENTATION_UNLOCK = "orientation.unlock"
    const val CONSOLE_ENTRY = "console.entry"
}
