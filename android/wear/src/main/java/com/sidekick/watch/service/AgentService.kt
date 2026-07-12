package com.sidekick.watch.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.wear.tiles.TileService
import com.sidekick.watch.R
import com.sidekick.watch.data.AgentBackends
import com.sidekick.watch.data.AgentRequestBus
import com.sidekick.watch.data.AgentSettings
import com.sidekick.watch.data.CodexAppServerRepository
import com.sidekick.watch.data.CodexStreamEvent
import com.sidekick.watch.data.HttpClientProvider
import com.sidekick.watch.data.OpenAIMessage
import com.sidekick.watch.data.OpenAIRepository
import com.sidekick.watch.data.PersistedChatMessage
import com.sidekick.watch.data.ResponseNotifier
import com.sidekick.watch.data.SettingsRepository
import com.sidekick.watch.tile.SidekickTileService
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

class AgentService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var titleDeferred: Deferred<String?>? = null
    private var requestJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        serviceRunning = true
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.getStringExtra(EXTRA_ACTION) ?: run {
            stopSelf(); return START_NOT_STICKY
        }

        if (requestJob?.isActive == true) {
            Log.w(TAG, "Request already active, ignoring")
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildThinkingNotification())
        acquireWakeLock()

        when (action) {
            ACTION_OPENAI -> launchOpenAI(intent)
            ACTION_CODEX -> launchCodex(intent)
            else -> stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun launchCodex(intent: Intent) {
        val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID)!!
        val backendConversationId = intent.getStringExtra(EXTRA_BACKEND_CONVERSATION_ID)
        val baseUrl = intent.getStringExtra(EXTRA_BASE_URL)!!
        val authToken = intent.getStringExtra(EXTRA_AUTH_TOKEN).orEmpty()
        val model = intent.getStringExtra(EXTRA_MODEL).orEmpty()
        val developerInstructions = intent.getStringExtra(EXTRA_DEVELOPER_INSTRUCTIONS).orEmpty()
        val cwd = intent.getStringExtra(EXTRA_CWD)!!
        val input = intent.getStringExtra(EXTRA_INPUT)!!

        AgentRequestBus.updateState {
            it.copy(
                conversationId = conversationId,
                backendId = AgentBackends.codex.id,
                backendConversationId = backendConversationId,
                isActive = true,
                streamingText = "",
                finalText = null,
                generatedTitle = null,
                error = null,
            )
        }
        requestTileUpdate()

        requestJob = scope.launch(Dispatchers.Default) {
            try {
                val repo = CodexAppServerRepository(HttpClientProvider.client)
                val buffer = StringBuilder()
                var lastStreamUpdateMs = 0L

                withTimeout(CODEX_TURN_TIMEOUT_MS) {
                    repo.runTurn(
                        baseUrl,
                        authToken,
                        model,
                        developerInstructions,
                        cwd,
                        backendConversationId,
                        input,
                    )
                        .collect { event ->
                            when (event) {
                                is CodexStreamEvent.ThreadReady -> {
                                    AgentRequestBus.updateState { it.copy(backendConversationId = event.threadId) }
                                    persistBackendConversationId(AgentBackends.codex.id, conversationId, event.threadId)
                                }
                                is CodexStreamEvent.TextDelta -> {
                                    buffer.append(event.text)
                                    val now = SystemClock.elapsedRealtime()
                                    if (now - lastStreamUpdateMs >= STREAM_UPDATE_INTERVAL_MS) {
                                        lastStreamUpdateMs = now
                                        AgentRequestBus.updateState { it.copy(streamingText = buffer.toString()) }
                                    }
                                }
                                CodexStreamEvent.Completed -> Unit
                            }
                        }
                }

                val finalText = buffer.toString()
                Log.i(TAG, "Codex request completed conversation=$conversationId chars=${finalText.length}")
                AgentRequestBus.updateState { it.copy(isActive = false, finalText = finalText) }
                persistResponse(conversationId, finalText, null)
                onRequestComplete(finalText, conversationId)
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Codex request timed out", e)
                AgentRequestBus.updateState { it.copy(isActive = false, error = "Codex request timed out") }
                onRequestFailed()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Codex request failed", e)
                AgentRequestBus.updateState { it.copy(isActive = false, error = e.message ?: "Request failed") }
                onRequestFailed()
            } finally {
                clearInterruptedRequest(conversationId)
            }
        }
    }

    private fun launchOpenAI(intent: Intent) {
        val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID)!!
        val backendConversationId = intent.getStringExtra(EXTRA_BACKEND_CONVERSATION_ID)!!
        val baseUrl = intent.getStringExtra(EXTRA_BASE_URL)!!
        val authToken = intent.getStringExtra(EXTRA_AUTH_TOKEN).orEmpty()
        val model = intent.getStringExtra(EXTRA_MODEL)!!
        val messagesJson = intent.getStringExtra(EXTRA_MESSAGES_JSON)!!
        val titleUserRequest = intent.getStringExtra(EXTRA_TITLE_USER_REQUEST)

        AgentRequestBus.updateState {
            it.copy(
                conversationId = conversationId,
                backendId = intent.getStringExtra(EXTRA_BACKEND_ID),
                isActive = true,
                streamingText = "",
                finalText = null,
                generatedTitle = null,
                error = null,
            )
        }
        requestTileUpdate()

        titleDeferred = generateTitleAsync(
            baseUrl = baseUrl,
            authToken = authToken,
            model = model,
            userRequest = titleUserRequest,
        )

        requestJob = scope.launch(Dispatchers.Default) {
            try {
                val messages = deserializeMessages(messagesJson)
                val repo = OpenAIRepository(HttpClientProvider.client)
                val buffer = StringBuilder()
                var lastStreamUpdateMs = 0L

                repo.sendMessageStreaming(baseUrl, authToken, model, messages, backendConversationId)
                    .collect { chunk ->
                        buffer.append(chunk)
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastStreamUpdateMs >= STREAM_UPDATE_INTERVAL_MS) {
                            lastStreamUpdateMs = now
                            AgentRequestBus.updateState { it.copy(streamingText = buffer.toString()) }
                        }
                    }

                val finalText = buffer.toString()
                AgentRequestBus.updateState { it.copy(isActive = false, finalText = finalText) }
                persistResponse(conversationId, finalText, null)
                onRequestComplete(finalText, conversationId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "OpenAI request failed", e)
                AgentRequestBus.updateState { it.copy(isActive = false, error = e.message ?: "Request failed") }
                onRequestFailed()
            } finally {
                clearInterruptedRequest(conversationId)
            }
        }
    }

    private suspend fun clearInterruptedRequest(conversationId: String) {
        val state = AgentRequestBus.state.value
        if (!state.isActive || state.conversationId != conversationId) return
        Log.w(TAG, "Request interrupted conversation=$conversationId")
        AgentRequestBus.updateState {
            it.copy(isActive = false, error = "Request interrupted")
        }
        withContext(NonCancellable) { finishRequest() }
    }

    private fun onRequestComplete(responseText: String, conversationId: String) {
        if (responseText.isNotBlank()) {
            vibrateResponse()
            ResponseNotifier(applicationContext).notifyIfInBackground(responseText)
        }
        finishAfterTitle(conversationId)
    }

    private fun onRequestFailed() {
        scope.launch {
            titleDeferred?.cancel()
            titleDeferred = null
            finishRequest()
        }
    }

    private suspend fun finishRequest() {
        withContext(Dispatchers.Main.immediate) {
            requestTileUpdate()
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun requestTileUpdate() {
        TileService.getUpdater(applicationContext).requestUpdate(SidekickTileService::class.java)
    }

    private fun finishAfterTitle(conversationId: String) {
        val deferred = titleDeferred
        if (deferred == null) {
            scope.launch { finishRequest() }
            return
        }
        scope.launch(Dispatchers.Default) {
            try {
                val generatedTitle = deferred.await()
                if (!generatedTitle.isNullOrBlank()) {
                    AgentRequestBus.updateState { it.copy(conversationId = conversationId, generatedTitle = generatedTitle) }
                    persistResponse(conversationId, null, generatedTitle)
                    requestTileUpdate()
                }
            } finally {
                titleDeferred = null
                finishRequest()
            }
        }
    }

    private fun generateTitleAsync(
        baseUrl: String,
        authToken: String,
        model: String,
        userRequest: String?,
    ): Deferred<String?>? {
        if (userRequest.isNullOrBlank()) return null
        return scope.async(Dispatchers.Default) {
            runCatching {
                withTimeout(TITLE_REQUEST_TIMEOUT_MS) {
                    OpenAIRepository(HttpClientProvider.client)
                        .generateConversationTitle(
                            baseUrl = baseUrl,
                            authToken = authToken,
                            model = model,
                            userRequest = userRequest,
                        )
                        .getOrThrow()
                }
            }.onFailure { e ->
                Log.w(TAG, "Title generation failed", e)
            }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
        }
    }

    private suspend fun persistResponse(
        conversationId: String,
        responseText: String?,
        generatedTitle: String?,
    ) {
        val text = responseText?.takeIf { it.isNotBlank() }
        val title = generatedTitle?.trim()?.takeIf { it.isNotBlank() }
        if (text == null && title == null) return

        val repository = SettingsRepository(applicationContext)
        val current = repository.loadConversationState() ?: return
        val messages = current.messagesByConversation[conversationId].orEmpty()
        val updatedMessages =
            if (text == null || messages.any { it.role == "BOT" && it.text == text }) {
                messages
            } else {
                messages + PersistedChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = "BOT",
                    text = text,
                )
            }
        val now = System.currentTimeMillis()
        val updatedConversations = current.conversations.map { conversation ->
            if (conversation.id != conversationId) {
                conversation
            } else {
                conversation.copy(
                    title = conversation.title ?: title,
                    lastUpdatedEpochMs = if (text != null) now else conversation.lastUpdatedEpochMs,
                )
            }
        }
        repository.saveConversationState(
            current.copy(
                conversations = updatedConversations,
                messagesByConversation = current.messagesByConversation + (conversationId to updatedMessages),
            ),
        )
    }

    private suspend fun persistBackendConversationId(backendId: String, conversationId: String, threadId: String) {
        val repository = SettingsRepository(applicationContext)
        val current = repository.loadConversationState() ?: return
        repository.saveConversationState(
            current.copy(
                backendConversationIds =
                    current.backendConversationIds + (backendConversationKey(backendId, conversationId) to threadId),
            ),
        )
    }

    private fun vibrateResponse() {
        val vibrator = getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 100, 50, 100), -1))
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Sidekick:AgentRequest").apply {
            acquire(6 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun buildThinkingNotification(): Notification {
        val channel = NotificationChannel(
            CHANNEL_WORKING,
            "Agent Processing",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        return NotificationCompat.Builder(this, CHANNEL_WORKING)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Sidekick")
            .setContentText("Thinking…")
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        val state = AgentRequestBus.state.value
        if (state.isActive) {
            Log.w(TAG, "Service destroyed during active request conversation=${state.conversationId}")
            AgentRequestBus.updateState {
                it.copy(isActive = false, error = "Request interrupted")
            }
        }
        serviceRunning = false
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AgentService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_WORKING = "agent_working"

        private const val EXTRA_ACTION = "action"
        private const val ACTION_OPENAI = "openai"
        private const val ACTION_CODEX = "codex"
        private const val EXTRA_CONVERSATION_ID = "conversation_id"
        private const val EXTRA_BACKEND_ID = "backend_id"
        private const val EXTRA_BACKEND_CONVERSATION_ID = "backend_conversation_id"
        private const val EXTRA_BASE_URL = "base_url"
        private const val EXTRA_AUTH_TOKEN = "auth_token"
        private const val EXTRA_MODEL = "model"
        private const val EXTRA_DEVELOPER_INSTRUCTIONS = "developer_instructions"
        private const val EXTRA_MESSAGES_JSON = "messages_json"
        private const val EXTRA_TITLE_USER_REQUEST = "title_user_request"
        private const val EXTRA_CWD = "cwd"
        private const val EXTRA_INPUT = "input"
        private const val TITLE_REQUEST_TIMEOUT_MS = 10_000L
        private const val CODEX_TURN_TIMEOUT_MS = 5 * 60 * 1000L
        private const val STREAM_UPDATE_INTERVAL_MS = 150L
        @Volatile private var serviceRunning = false

        fun isRunning(): Boolean = serviceRunning

        fun startOpenAI(
            context: Context,
            conversationId: String,
            backendConversationId: String,
            settings: AgentSettings,
            messagesJson: String,
            titleUserRequest: String?,
        ) {
            val intent = Intent(context, AgentService::class.java).apply {
                putExtra(EXTRA_ACTION, ACTION_OPENAI)
                putExtra(EXTRA_CONVERSATION_ID, conversationId)
                putExtra(EXTRA_BACKEND_ID, settings.backendId)
                putExtra(EXTRA_BACKEND_CONVERSATION_ID, backendConversationId)
                putExtra(EXTRA_BASE_URL, settings.baseUrl)
                putExtra(EXTRA_AUTH_TOKEN, settings.authToken)
                putExtra(EXTRA_MODEL, settings.model)
                putExtra(EXTRA_MESSAGES_JSON, messagesJson)
                if (!titleUserRequest.isNullOrBlank()) {
                    putExtra(EXTRA_TITLE_USER_REQUEST, titleUserRequest)
                }
            }
            context.startForegroundService(intent)
        }

        fun startCodex(
            context: Context,
            conversationId: String,
            backendConversationId: String?,
            settings: AgentSettings,
            cwd: String,
            input: String,
        ) {
            val intent = Intent(context, AgentService::class.java).apply {
                putExtra(EXTRA_ACTION, ACTION_CODEX)
                putExtra(EXTRA_CONVERSATION_ID, conversationId)
                putExtra(EXTRA_BACKEND_ID, settings.backendId)
                if (!backendConversationId.isNullOrBlank()) {
                    putExtra(EXTRA_BACKEND_CONVERSATION_ID, backendConversationId)
                }
                putExtra(EXTRA_BASE_URL, settings.baseUrl)
                putExtra(EXTRA_AUTH_TOKEN, settings.authToken)
                putExtra(EXTRA_MODEL, settings.model)
                putExtra(EXTRA_DEVELOPER_INSTRUCTIONS, settings.instructions)
                putExtra(EXTRA_CWD, cwd)
                putExtra(EXTRA_INPUT, input)
            }
            context.startForegroundService(intent)
        }

        private fun deserializeMessages(json: String): List<OpenAIMessage> {
            val array = JSONArray(json)
            return (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                OpenAIMessage(role = obj.getString("role"), content = obj.getString("content"))
            }
        }

        private fun backendConversationKey(backendId: String, conversationId: String): String =
            "$backendId:$conversationId"
    }
}
