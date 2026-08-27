package com.codex.wear.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class OpenAIMessage(val role: String, val content: String)

class OpenAIRepository(
    private val client: OkHttpClient,
) {
    /** Derived client sharing the same connection pool but with a longer read timeout for streaming. */
    private val streamingClient by lazy {
        client.newBuilder()
            .readTimeout(5, TimeUnit.MINUTES)
            .build()
    }

    fun sendMessageStreaming(
        baseUrl: String,
        authToken: String,
        model: String,
        messages: List<OpenAIMessage>,
        user: String? = null,
    ): Flow<String> = callbackFlow {
        val messagesArray = JSONArray().apply {
            messages.forEach { msg ->
                put(JSONObject().put("role", msg.role).put("content", msg.content))
            }
        }
        val payload = JSONObject()
            .put("model", model)
            .put("messages", messagesArray)
            .put("stream", true)
        if (!user.isNullOrBlank()) payload.put("user", user)

        val url = "${normalizeBaseUrl(baseUrl)}/v1/chat/completions"
        val requestBuilder = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
        if (authToken.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $authToken")
        }

        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") {
                    channel.close()
                    return
                }
                val json = runCatching { JSONObject(data) }.getOrNull() ?: return
                json.optJSONObject("error")?.let { error ->
                    val message = error.optString("message").ifBlank { "Stream error" }
                    channel.close(Exception(message))
                    return
                }
                val choice = json.optJSONArray("choices")?.optJSONObject(0)
                val content = choice
                    ?.optJSONObject("delta")
                    ?.optString("content")
                    ?.takeIf { it.isNotEmpty() }
                    ?: choice
                        ?.optJSONObject("message")
                        ?.optString("content")
                        ?.takeIf { it.isNotEmpty() }
                    ?: choice
                        ?.optString("text")
                        ?.takeIf { it.isNotEmpty() }
                if (!content.isNullOrEmpty()) {
                    trySend(content)
                }
                if (choice?.isNull("finish_reason") == false) {
                    channel.close()
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val error = t
                    ?: if (response != null && !response.isSuccessful) {
                        val body = response.body?.string().orEmpty()
                        Exception("OpenAI request failed (${response.code})${body.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}")
                    } else {
                        null
                    }
                channel.close(error)
            }

            override fun onClosed(eventSource: EventSource) {
                channel.close()
            }
        }

        val eventSource = EventSources.createFactory(streamingClient)
            .newEventSource(requestBuilder.build(), listener)

        awaitClose { eventSource.cancel() }
    }

    suspend fun sendMessage(
        baseUrl: String,
        authToken: String,
        model: String,
        messages: List<OpenAIMessage>,
        user: String? = null,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val messagesArray = JSONArray().apply {
                messages.forEach { msg ->
                    put(JSONObject().put("role", msg.role).put("content", msg.content))
                }
            }
            val payload = JSONObject()
                .put("model", model)
                .put("messages", messagesArray)
                .put("stream", false)
            if (!user.isNullOrBlank()) payload.put("user", user)

            val url = "${normalizeBaseUrl(baseUrl)}/v1/chat/completions"
            val requestBuilder = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            if (authToken.isNotBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $authToken")
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    error("OpenAI request failed (${response.code}): ${response.body?.string().orEmpty()}")
                }
                val body = response.body?.string().orEmpty()
                JSONObject(body)
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    .orEmpty()
            }
        }
    }

    suspend fun generateConversationTitle(
        baseUrl: String,
        authToken: String,
        model: String,
        userRequest: String,
    ): Result<String> =
        sendMessage(
            baseUrl = baseUrl,
            authToken = authToken,
            model = model,
            messages = listOf(
                OpenAIMessage(
                    role = "system",
                    content = "Create a concise 3-5 word chat title from the user request. Return title only.",
                ),
                OpenAIMessage(
                    role = "user",
                    content = userRequest,
                ),
            ),
        ).map { it.trim() }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
