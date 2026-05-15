package com.mrdoob.browser

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface DetectionState {
    data object NotDetected : DetectionState
    data class Detected(val badgeText: String, val backgroundColor: String?) : DetectionState
}

object DetectionStore {
    private val _flow = MutableStateFlow<DetectionState>(DetectionState.NotDetected)
    val flow = _flow.asStateFlow()

    fun setBadgeText(text: String) {
        _flow.value = if (text.isEmpty()) DetectionState.NotDetected
        else DetectionState.Detected(text, (_flow.value as? DetectionState.Detected)?.backgroundColor)
    }

    fun setBadgeBackgroundColor(color: String?) {
        val current = _flow.value as? DetectionState.Detected ?: return
        _flow.value = current.copy(backgroundColor = color)
    }

    fun reset() { _flow.value = DetectionState.NotDetected }
}
