package com.sidekick.watch.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.sidekick.watch.R
import com.sidekick.watch.presentation.MainActivity

class ResponseNotifier(private val context: Context) {

    private val channelId = "agent_response"
    private val notificationId = 1001

    init {
        val channel = NotificationChannel(
            channelId,
            "Agent Responses",
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    fun notifyIfInBackground(responsePreview: String) {
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_IMMUTABLE,
        )

        val preview = if (responsePreview.length > 100) {
            responsePreview.take(100) + "…"
        } else {
            responsePreview
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Codex")
            .setContentText(preview)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted
        }
    }
}
