package com.sidekick.watch.data

import com.sidekick.watch.BuildConfig

data class AgentBackend(
    val id: String,
    val displayName: String,
    val defaultBaseUrl: String,
    val defaultModel: String? = null,
)

object AgentBackends {
    private val baseUrl = BuildConfig.DEFAULT_BASE_URL

    val hermes =
        AgentBackend(
            id = "hermes",
            displayName = "Hermes",
            defaultBaseUrl = baseUrl,
            defaultModel = "hermes:main",
        )

    val openclaw =
        AgentBackend(
            id = "openclaw",
            displayName = "OpenClaw",
            defaultBaseUrl = baseUrl,
            defaultModel = "openclaw:main",
        )

    val supported: List<AgentBackend> = listOf(hermes, openclaw)

    fun fromId(id: String?): AgentBackend = supported.firstOrNull { it.id == id } ?: hermes
}
