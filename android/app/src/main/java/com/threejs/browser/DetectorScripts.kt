package com.threejs.browser

import android.content.Context

object DetectorScripts {

    private const val DIR = "threejs-devtools"

    @Volatile
    private var cached: String? = null

    fun load(context: Context): String {
        cached?.let { return it }
        val assets = context.assets
        val parts = listOf("constants.js", "bridge.js", "android-relay.js").map { name ->
            assets.open("$DIR/$name").bufferedReader().use { it.readText() }
        }
        val joined = parts.joinToString(separator = ";\n")
        cached = joined
        return joined
    }
}
