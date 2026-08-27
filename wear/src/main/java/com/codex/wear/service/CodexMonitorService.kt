package com.codex.wear.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.wear.tiles.TileService
import com.codex.wear.R
import com.codex.wear.data.CodexTaskNotifier
import com.codex.wear.data.TaskDeliveryRegistry
import com.codex.wear.data.codex.CodexTaskRepository
import com.codex.wear.data.codex.CodexTaskState
import com.codex.wear.data.codex.CodexTaskSummary
import com.codex.wear.data.codex.TaskSnapshotStore
import com.codex.wear.presentation.MainActivity
import com.codex.wear.tile.SidekickTileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Keeps the shared Codex socket alive while known tasks are active; the server owns execution. */
class CodexMonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository by lazy { CodexTaskRepository.get(applicationContext) }
    private val snapshotStore by lazy { TaskSnapshotStore(applicationContext) }
    private val notifier by lazy { CodexTaskNotifier(applicationContext) }
    private var monitorJob: Job? = null
    private var stopJob: Job? = null
    private var lastStates: Map<String, CodexTaskState> = emptyMap()

    override fun onCreate() {
        super.onCreate()
        running = true
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(ONGOING_NOTIFICATION_ID, buildOngoingNotification(repository.state.value.tasks))
        repository.refresh()
        if (monitorJob?.isActive != true) {
            lastStates = repository.state.value.tasks.associate { it.id to it.state }
            monitorJob =
                scope.launch {
                    repository.state.collect { state ->
                        val tasks = state.tasks
                        handleTransitions(tasks)
                        updateForeground(tasks)
                        requestTileUpdate()
                        scheduleStopIfIdle(tasks)
                    }
                }
        }
        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        super.onDestroy()
    }

    private fun handleTransitions(tasks: List<CodexTaskSummary>) {
        tasks.forEach { task ->
            val previous = lastStates[task.id]
            when {
                task.state == CodexTaskState.NEEDS_ATTENTION && previous != CodexTaskState.NEEDS_ATTENTION ->
                    notifier.notifyTask(task)
                task.state == CodexTaskState.ERROR && previous != CodexTaskState.ERROR ->
                    notifier.notifyTask(task)
                task.state == CodexTaskState.COMPLETE &&
                    previous in setOf(CodexTaskState.WORKING, CodexTaskState.NEEDS_ATTENTION) -> {
                    scope.launch {
                        val delivered =
                            TaskDeliveryRegistry.wasDelivered(task.id) ||
                                task.id in snapshotStore.load().completionNotifiedTaskIds
                        if (!delivered) {
                            notifier.notifyTask(task)
                            snapshotStore.markCompletionNotified(task.id)
                        }
                    }
                }
            }
        }
        lastStates = tasks.associate { it.id to it.state }
    }

    private fun updateForeground(tasks: List<CodexTaskSummary>) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(ONGOING_NOTIFICATION_ID, buildOngoingNotification(tasks))
    }

    private fun buildOngoingNotification(tasks: List<CodexTaskSummary>): Notification {
        val attention = tasks.count { it.state == CodexTaskState.NEEDS_ATTENTION }
        val working = tasks.count { it.state == CodexTaskState.WORKING }
        val text =
            when {
                attention > 0 -> "$attention task${if (attention == 1) "" else "s"} need attention"
                working > 0 -> "$working task${if (working == 1) "" else "s"} working"
                else -> "Checking task status"
            }
        val pendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return NotificationCompat.Builder(this, MONITOR_CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Codex")
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    private fun scheduleStopIfIdle(tasks: List<CodexTaskSummary>) {
        val hasLiveTasks = tasks.any {
            it.state == CodexTaskState.WORKING || it.state == CodexTaskState.NEEDS_ATTENTION
        }
        if (hasLiveTasks) {
            stopJob?.cancel()
            stopJob = null
            return
        }
        if (stopJob?.isActive == true) return
        stopJob =
            scope.launch {
                delay(IDLE_STOP_DELAY_MS)
                val stillIdle = repository.state.value.tasks.none {
                    it.state == CodexTaskState.WORKING || it.state == CodexTaskState.NEEDS_ATTENTION
                }
                if (stillIdle) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
    }

    private fun requestTileUpdate() {
        TileService.getUpdater(applicationContext).requestUpdate(SidekickTileService::class.java)
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(MONITOR_CHANNEL, "Codex monitoring", NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        private const val MONITOR_CHANNEL = "codex_monitor"
        private const val ONGOING_NOTIFICATION_ID = 2_001
        private const val IDLE_STOP_DELAY_MS = 2_500L
        @Volatile private var running = false

        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, CodexMonitorService::class.java))
            }
        }

        fun isRunning(): Boolean = running
    }
}
