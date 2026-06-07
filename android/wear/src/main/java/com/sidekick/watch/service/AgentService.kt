package com.sidekick.watch.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.wear.tiles.TileService
import com.sidekick.watch.R
import com.sidekick.watch.data.AgentRequestBus
import com.sidekick.watch.data.AgentSettings
import com.sidekick.watch.data.HttpClientProvider
import com.sidekick.watch.data.OpenAIMessage
import com.sidekick.watch.data.OpenAIRepository
import com.sidekick.watch.data.ResponseNotifier
import com.sidekick.watch.data.SettingsRepository
import com.sidekick.watch.tile.SidekickTileService
import com.sidekick.watch.viewmodel.ChatMessage
import com.sidekick.watch.viewmodel.MessageRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class AgentService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.getStringExtra(EXTRA_ACTION) ?: run {
            stopSelf(); return START_NOT_STICKY
        }

        if (AgentRequestBus.state.value.isActive) {
            Log.w(TAG, "Request already active, ignoring")
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildThinkingNotification())
        acquireWakeLock()

        when (action) {
            ACTION_OPENAI -> launchOpenAI(intent)
            else -> stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun launchOpenAI(intent: Intent) {
        val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID)!!
        val backendConversationId = intent.getStringExtra(EXTRA_BACKEND_CONVERSATION_ID)!!
        val baseUrl = intent.getStringExtra(EXTRA_BASE_URL)!!
        val authToken = intent.getStringExtra(EXTRA_AUTH_TOKEN).orEmpty()
        val model = intent.getStringExtra(EXTRA_MODEL)!!
        val messagesJson = intent.getStringExtra(EXTRA_MESSAGES_JSON)!!

        AgentRequestBus.updateState {
            it.copy(conversationId = conversationId, isActive = true, streamingText = "", finalText = null, error = null)
        }
        requestTileUpdate()

        scope.launch {
            try {
                val messages = deserializeMessages(messagesJson)
                val repo = OpenAIRepository(HttpClientProvider.client)
                val buffer = StringBuilder()

                repo.sendMessageStreaming(baseUrl, authToken, model, messages, backendConversationId)
                    .collect { chunk ->
                        buffer.append(chunk)
                        AgentRequestBus.emitChunk(chunk)
                        AgentRequestBus.updateState { it.copy(streamingText = buffer.toString()) }
                    }

                val finalText = buffer.toString()
                AgentRequestBus.updateState { it.copy(isActive = false, finalText = finalText) }
                onRequestComplete(finalText, conversationId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "OpenAI request failed", e)
                AgentRequestBus.updateState { it.copy(isActive = false, error = e.message ?: "Request failed") }
                onRequestFailed()
            }
        }
    }

    private fun onRequestComplete(responseText: String, conversationId: String) {
        if (responseText.isNotBlank()) {
            vibrateResponse()
            ResponseNotifier(applicationContext).notifyIfInBackground(responseText)
        }
        scope.launch {
            persistResponse(responseText, conversationId)
            requestTileUpdate()
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun onRequestFailed() {
        releaseWakeLock()
        requestTileUpdate()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun requestTileUpdate() {
        TileService.getUpdater(applicationContext).requestUpdate(SidekickTileService::class.java)
    }

    private suspend fun persistResponse(responseText: String, conversationId: String) {
        if (responseText.isBlank()) return
        try {
                val settingsRepo = SettingsRepository(applicationContext)
                val persisted = settingsRepo.loadConversationState() ?: return
                val existingMessages = persisted.messagesByConversation[conversationId].orEmpty()
                val botMessage = com.sidekick.watch.data.PersistedChatMessage(
                    id = java.util.UUID.randomUUID().toString(),
                    role = MessageRole.BOT.name,
                    text = responseText,
                )
                val updatedMessages = persisted.messagesByConversation + (conversationId to (existingMessages + botMessage))
                val now = System.currentTimeMillis()
                val updatedConversations = persisted.conversations.map { conv ->
                    if (conv.id == conversationId) conv.copy(lastUpdatedEpochMs = now) else conv
                }
                settingsRepo.saveConversationState(
                    persisted.copy(
                        messagesByConversation = updatedMessages,
                        conversations = updatedConversations,
                    )
                )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist response", e)
        }
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
        private const val EXTRA_CONVERSATION_ID = "conversation_id"
        private const val EXTRA_BACKEND_CONVERSATION_ID = "backend_conversation_id"
        private const val EXTRA_BASE_URL = "base_url"
        private const val EXTRA_AUTH_TOKEN = "auth_token"
        private const val EXTRA_MODEL = "model"
        private const val EXTRA_MESSAGES_JSON = "messages_json"

        fun startOpenAI(
            context: Context,
            conversationId: String,
            backendConversationId: String,
            settings: AgentSettings,
            messagesJson: String,
        ) {
            val intent = Intent(context, AgentService::class.java).apply {
                putExtra(EXTRA_ACTION, ACTION_OPENAI)
                putExtra(EXTRA_CONVERSATION_ID, conversationId)
                putExtra(EXTRA_BACKEND_CONVERSATION_ID, backendConversationId)
                putExtra(EXTRA_BASE_URL, settings.baseUrl)
                putExtra(EXTRA_AUTH_TOKEN, settings.authToken)
                putExtra(EXTRA_MODEL, settings.model)
                putExtra(EXTRA_MESSAGES_JSON, messagesJson)
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
    }
}
