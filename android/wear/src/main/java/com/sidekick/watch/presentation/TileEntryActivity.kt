package com.sidekick.watch.presentation

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.sidekick.watch.tile.SidekickTileService

class TileEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val inputMode =
            intent?.getStringExtra(SidekickTileService.EXTRA_INPUT_MODE)
                ?.takeIf { it == "voice" || it == "activity" }
                ?: "voice"
        val taskId =
            intent?.getStringExtra(SidekickTileService.EXTRA_TASK_ID)
                ?: intent?.getStringExtra(LEGACY_CONVERSATION_ID)
        val safeTaskId = taskId?.takeIf { it.isNotBlank() && it.length <= MAX_TASK_ID_LENGTH }

        val destination =
            if (inputMode == "voice" && safeTaskId.isNullOrBlank()) {
                Intent(this, AssistantActivity::class.java)
            } else {
                Intent(this, MainActivity::class.java).apply {
                    action = Intent.ACTION_ASSIST
                    putExtra(SidekickTileService.EXTRA_INPUT_MODE, inputMode)
                    safeTaskId?.let { putExtra(MainActivity.EXTRA_TASK_ID, it) }
                }
            }
        destination.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(destination)

        finish()
    }

    private companion object {
        const val LEGACY_CONVERSATION_ID = "conversation_id"
        const val MAX_TASK_ID_LENGTH = 256
    }
}
