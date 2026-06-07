package com.sidekick.watch.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

object AgentRequestBus {

    data class RequestState(
        val conversationId: String? = null,
        val isActive: Boolean = false,
        val streamingText: String = "",
        val finalText: String? = null,
        val generatedTitle: String? = null,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(RequestState())
    val state: StateFlow<RequestState> = _state.asStateFlow()

    private val _streamChunks = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val streamChunks: SharedFlow<String> = _streamChunks.asSharedFlow()

    fun updateState(transform: (RequestState) -> RequestState) {
        _state.value = transform(_state.value)
    }

    fun emitChunk(chunk: String) {
        _streamChunks.tryEmit(chunk)
    }

    fun reset() {
        _state.value = RequestState()
    }
}
