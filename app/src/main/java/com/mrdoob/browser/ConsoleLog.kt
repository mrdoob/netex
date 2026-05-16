package com.mrdoob.browser

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object ConsoleLog {

    enum class Level { LOG, INFO, WARN, ERROR, DEBUG }

    data class Entry(
        val level: Level,
        val segmentsJson: String,
        val sourceId: String? = null,
        val lineNumber: Int = 0
    )

    sealed interface Event {
        data class Added(val entry: Entry) : Event
        data object Cleared : Event
    }

    private val _events = MutableSharedFlow<Event>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events = _events.asSharedFlow()

    fun add(entry: Entry) {
        _events.tryEmit(Event.Added(entry))
    }

    fun reset() {
        _events.tryEmit(Event.Cleared)
    }
}
