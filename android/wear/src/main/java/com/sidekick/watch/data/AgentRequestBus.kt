package com.sidekick.watch.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object AgentRequestBus {

    data class RequestState(
        val conversationId: String? = null,
        val backendId: String? = null,
        val backendConversationId: String? = null,
        val isActive: Boolean = false,
        val streamingText: String = "",
        val finalText: String? = null,
        val generatedTitle: String? = null,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(RequestState())
    val state: StateFlow<RequestState> = _state.asStateFlow()

    fun updateState(transform: (RequestState) -> RequestState) {
        _state.update(transform)
    }

    fun reset() {
        _state.value = RequestState()
    }
}
