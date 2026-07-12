package com.sidekick.watch.data

import com.sidekick.watch.BuildConfig

data class AgentBackend(
    val id: String,
    val displayName: String,
    val defaultBaseUrl: String,
    val defaultModel: String? = null,
    val protocol: AgentBackendProtocol = AgentBackendProtocol.OPENAI_CHAT,
    val defaultCwd: String? = null,
    val modelOptions: List<AgentModelOption> = emptyList(),
)

data class AgentModelOption(
    val id: String,
    val displayName: String,
)

enum class AgentBackendProtocol {
    OPENAI_CHAT,
    CODEX_APP_SERVER,
}

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

    val codex =
        AgentBackend(
            id = "codex",
            displayName = "Codex",
            defaultBaseUrl = BuildConfig.DEFAULT_CODEX_BASE_URL,
            protocol = AgentBackendProtocol.CODEX_APP_SERVER,
            defaultCwd = "/home/sid",
            modelOptions =
                listOf(
                    AgentModelOption("", "Default"),
                    AgentModelOption("gpt-5.5", "GPT-5.5"),
                    AgentModelOption("gpt-5.6-sol", "GPT-5.6-Sol"),
                    AgentModelOption("gpt-5.6-terra", "GPT-5.6-Terra"),
                    AgentModelOption("gpt-5.6-luna", "GPT-5.6-Luna"),
                    AgentModelOption("gpt-5.4", "GPT-5.4"),
                    AgentModelOption("gpt-5.4-mini", "GPT-5.4-Mini"),
                    AgentModelOption("gpt-5.3-codex-spark", "GPT-5.3-Codex-Spark"),
                ),
        )

    val supported: List<AgentBackend> = listOf(hermes, openclaw, codex)

    fun fromId(id: String?): AgentBackend = supported.firstOrNull { it.id == id } ?: codex
}
