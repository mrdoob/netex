package com.mrdoob.browser

import android.content.Context

object DocumentStartScripts {

    // Order matters: constants → chrome shim → bridge → highlight → content-script →
    // background, then network-shim. content-script gets a scoped `chrome` so
    // its listeners don't observe its own sendMessage calls (self-loop OOM).
    private val ASSETS = listOf(
        Asset("threejs-devtools/constants.js"),
        Asset("chrome-shim-page.js"),
        Asset("threejs-devtools/bridge.js"),
        Asset("threejs-devtools/highlight.js"),
        Asset("threejs-devtools/content-script.js", wrap = Wrap.CONTENT_SCRIPT),
        Asset("threejs-devtools/background.js", wrap = Wrap.IIFE),
        Asset("network-shim.js"),
    )

    private enum class Wrap { NONE, IIFE, CONTENT_SCRIPT }
    private data class Asset(val path: String, val wrap: Wrap = Wrap.NONE)

    @Volatile
    private var cached: String? = null

    fun load(context: Context): String {
        cached?.let { return it }
        val joined = ASSETS.joinToString(separator = ";\n") { asset ->
            val text = context.assets.open(asset.path).bufferedReader().use { it.readText() }
            when (asset.wrap) {
                Wrap.NONE -> text
                Wrap.IIFE -> "(function(){\n$text\n})();"
                Wrap.CONTENT_SCRIPT ->
                    "(function(chrome){\n$text\n})(window.__netexExt.createContentScriptChrome());"
            }
        }
        cached = joined
        return joined
    }
}
