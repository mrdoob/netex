package com.threejs.browser

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface DetectionState {
    data object NotDetected : DetectionState
    data class Detected(val revision: String) : DetectionState
}

object DetectionStore {
    private val _flow = MutableStateFlow<DetectionState>(DetectionState.NotDetected)
    val flow = _flow.asStateFlow()

    fun setRevision(revision: String) {
        val current = _flow.value
        if (current is DetectionState.Detected && current.revision == revision) return
        _flow.value = DetectionState.Detected(revision)
    }

    fun reset() {
        _flow.value = DetectionState.NotDetected
    }
}
