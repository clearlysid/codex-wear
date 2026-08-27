package com.sidekick.watch.data

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

sealed interface CodexStreamEvent {
    data class ThreadReady(val threadId: String) : CodexStreamEvent
    data class TextDelta(val text: String) : CodexStreamEvent
    data object Completed : CodexStreamEvent
}

class CodexAppServerRepository(private val client: OkHttpClient) {

    fun runTurn(
        serverUrl: String,
        authToken: String,
        model: String,
        developerInstructions: String,
        cwd: String,
        existingThreadId: String?,
        input: String,
    ): Flow<CodexStreamEvent> = callbackFlow {
        val request =
            Request.Builder()
                .url(normalizeWebSocketUrl(serverUrl))
                .apply {
                    if (authToken.isNotBlank()) header("Authorization", "Bearer ${authToken.trim()}")
                }
                .build()
        val requestIds = AtomicLong(1L)
        val pendingMethods = ConcurrentHashMap<Long, String>()
        val terminal = AtomicBoolean(false)
        var socket: WebSocket? = null

        fun finish(error: Throwable? = null) {
            if (!terminal.compareAndSet(false, true)) return
            if (error == null) close() else close(error)
            socket?.close(1000, null)
        }

        fun sendRequest(method: String, params: JSONObject): Long {
            val id = requestIds.getAndIncrement()
            pendingMethods[id] = method
            checkNotNull(socket).send(
                JSONObject()
                    .put("id", id)
                    .put("method", method)
                    .put("params", params)
                    .toString(),
            )
            return id
        }

        fun sendTurn(threadId: String) {
            sendRequest(
                "turn/start",
                JSONObject()
                    .put("threadId", threadId)
                    .put(
                        "input",
                        JSONArray().put(
                            JSONObject()
                                .put("type", "text")
                                .put("text", input)
                                .put("text_elements", JSONArray()),
                        ),
                    )
                    .apply { if (model.isNotBlank()) put("model", model.trim()) },
            )
        }

        fun sendThreadRequest() {
            val common =
                JSONObject()
                    .put("cwd", cwd)
                    .put("approvalPolicy", "never")
                    .put("sandbox", "workspace-write")
                    .apply { if (model.isNotBlank()) put("model", model.trim()) }
                    .apply {
                        if (developerInstructions.isNotBlank()) {
                            put("developerInstructions", developerInstructions.trim())
                        }
                    }
            if (existingThreadId.isNullOrBlank()) {
                sendRequest("thread/start", common)
            } else {
                common.put("threadId", existingThreadId)
                sendRequest("thread/resume", common)
            }
        }

        fun respondToServerRequest(message: JSONObject) {
            val id = message.opt("id") ?: return
            val method = message.optString("method")
            val result =
                when (method) {
                    "item/commandExecution/requestApproval",
                    "item/fileChange/requestApproval",
                    -> JSONObject().put("decision", "decline")
                    "item/permissions/requestApproval" ->
                        JSONObject()
                            .put("permissions", JSONObject())
                            .put("scope", "turn")
                    "item/tool/requestUserInput" -> JSONObject().put("answers", JSONObject())
                    "mcpServer/elicitation/request" ->
                        JSONObject()
                            .put("action", "decline")
                            .put("content", JSONObject.NULL)
                    else -> JSONObject()
                }
            checkNotNull(socket).send(JSONObject().put("id", id).put("result", result).toString())
        }

        val listener =
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    socket = webSocket
                    Log.i(TAG, "WebSocket opened")
                    sendRequest(
                        "initialize",
                        JSONObject().put(
                            "clientInfo",
                            JSONObject()
                                .put("name", "sidekick-watch")
                                .put("title", "Codex Watch")
                                .put("version", "1.0"),
                        ),
                    )
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val message = JSONObject(text)
                        if (message.has("id") && message.has("method")) {
                            respondToServerRequest(message)
                            return
                        }

                        if (message.has("id")) {
                            val id = message.optLong("id", -1L)
                            val method = pendingMethods.remove(id) ?: return
                            val error = message.optJSONObject("error")
                            if (error != null) {
                                finish(IllegalStateException(error.optString("message", "$method failed")))
                                return
                            }
                            val result = message.optJSONObject("result") ?: JSONObject()
                            when (method) {
                                "initialize" -> {
                                    val userAgent = result.optString("userAgent")
                                    Log.i(TAG, "Initialized server=$userAgent")
                                    if (!userAgent.contains(EXPECTED_CODEX_VERSION)) {
                                        finish(
                                            IllegalStateException(
                                                "Codex $EXPECTED_CODEX_VERSION required; server reported $userAgent",
                                            ),
                                        )
                                        return
                                    }
                                    checkNotNull(socket).send(JSONObject().put("method", "initialized").toString())
                                    sendThreadRequest()
                                }
                                "thread/start", "thread/resume" -> {
                                    val threadId = result.optJSONObject("thread")?.optString("id").orEmpty()
                                    if (threadId.isBlank()) {
                                        finish(IllegalStateException("Codex returned no thread ID"))
                                        return
                                    }
                                    Log.i(TAG, "$method thread=$threadId")
                                    trySend(CodexStreamEvent.ThreadReady(threadId))
                                    sendTurn(threadId)
                                }
                            }
                            return
                        }

                        when (message.optString("method")) {
                            "item/agentMessage/delta" -> {
                                val delta = message.optJSONObject("params")?.optString("delta").orEmpty()
                                if (delta.isNotEmpty()) trySend(CodexStreamEvent.TextDelta(delta))
                            }
                            "turn/completed" -> {
                                val turn = message.optJSONObject("params")?.optJSONObject("turn")
                                val status = turn?.optString("status").orEmpty()
                                Log.i(TAG, "Turn completed status=$status")
                                if (status == "completed") {
                                    trySend(CodexStreamEvent.Completed)
                                    finish()
                                } else {
                                    val detail = turn?.optJSONObject("error")?.optString("message").orEmpty()
                                    finish(IllegalStateException(detail.ifBlank { "Codex turn $status" }))
                                }
                            }
                        }
                    } catch (error: Exception) {
                        finish(error)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "WebSocket failed", t)
                    finish(t)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "WebSocket closed code=$code reason=$reason")
                    if (!terminal.get()) finish(IllegalStateException("Codex connection closed: $code $reason"))
                }
            }

        socket = client.newWebSocket(request, listener)
        awaitClose { socket?.cancel() }
    }

    private fun normalizeWebSocketUrl(url: String): String {
        val trimmed = url.trim()
        return when {
            trimmed.startsWith("https://") -> "wss://${trimmed.removePrefix("https://")}"
            trimmed.startsWith("http://") -> "ws://${trimmed.removePrefix("http://")}"
            else -> trimmed
        }
    }

    private companion object {
        const val TAG = "CodexAppServer"
        const val EXPECTED_CODEX_VERSION = "0.149.1"
    }
}
