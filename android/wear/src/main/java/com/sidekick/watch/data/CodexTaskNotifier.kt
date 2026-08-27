package com.sidekick.watch.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.sidekick.watch.R
import com.sidekick.watch.data.codex.CodexTaskState
import com.sidekick.watch.data.codex.CodexTaskSummary
import com.sidekick.watch.presentation.MainActivity
import java.util.concurrent.ConcurrentHashMap

object TaskDeliveryRegistry {
    private val delivered = ConcurrentHashMap.newKeySet<String>()

    fun markDelivered(taskId: String) {
        delivered += taskId
    }

    fun wasDelivered(taskId: String): Boolean = taskId in delivered
}

class CodexTaskNotifier(private val context: Context) {
    init {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ATTENTION, "Codex attention", NotificationManager.IMPORTANCE_HIGH),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULTS, "Codex results", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    fun notifyTask(task: CodexTaskSummary) {
        val channel = if (task.state == CodexTaskState.NEEDS_ATTENTION || task.state == CodexTaskState.ERROR) {
            CHANNEL_ATTENTION
        } else {
            CHANNEL_RESULTS
        }
        val title =
            when (task.state) {
                CodexTaskState.NEEDS_ATTENTION -> "Codex needs attention"
                CodexTaskState.COMPLETE -> "Codex task complete"
                CodexTaskState.ERROR -> "Codex task failed"
                CodexTaskState.WORKING -> "Codex is working"
                CodexTaskState.IDLE -> "Codex"
            }
        val preview =
            when {
                task.errorMessage?.isNotBlank() == true -> task.errorMessage
                task.preview.isNotBlank() -> task.preview
                else -> task.displayTitle
            }.orEmpty().replace(Regex("\\s+"), " ").take(MAX_PREVIEW_CHARS)
        val notification =
            NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(preview)
                .setContentIntent(taskPendingIntent(task.id))
                .setAutoCancel(true)
                .setGroup(TASK_GROUP)
                .build()
        try {
            NotificationManagerCompat.from(context).notify(task.id.stableNotificationId(), notification)
            TaskDeliveryRegistry.markDelivered(task.id)
        } catch (_: SecurityException) {
            // The Tile and Home remain available when notification permission is denied.
        }
    }

    fun cancel(taskId: String) {
        NotificationManagerCompat.from(context).cancel(taskId.stableNotificationId())
    }

    private fun taskPendingIntent(taskId: String): PendingIntent =
        PendingIntent.getActivity(
            context,
            taskId.stableNotificationId(),
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra(MainActivity.EXTRA_TASK_ID, taskId)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private companion object {
        const val CHANNEL_ATTENTION = "codex_attention"
        const val CHANNEL_RESULTS = "codex_results"
        const val TASK_GROUP = "codex_tasks"
        const val MAX_PREVIEW_CHARS = 120
    }
}

private fun String.stableNotificationId(): Int = hashCode() and 0x3fffffff
