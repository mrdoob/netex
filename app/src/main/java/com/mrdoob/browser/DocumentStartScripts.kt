package com.mrdoob.browser

import android.content.Context

object DocumentStartScripts {

    private val ASSETS = listOf(
        "threejs-devtools/constants.js",
        "threejs-devtools/bridge.js",
        "threejs-devtools/android-relay.js",
        "network-shim.js",
    )

    @Volatile
    private var cached: String? = null

    fun load(context: Context): String {
        cached?.let { return it }
        val joined = ASSETS.joinToString(separator = ";\n") { path ->
            context.assets.open(path).bufferedReader().use { it.readText() }
        }
        cached = joined
        return joined
    }
}
