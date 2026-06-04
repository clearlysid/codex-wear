package com.sidekick.watch.presentation

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.sidekick.watch.tile.SidekickTileService

class TileEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val inputMode = intent?.getStringExtra(SidekickTileService.EXTRA_INPUT_MODE) ?: "voice"
        val conversationId = intent?.getStringExtra(SidekickTileService.EXTRA_CONVERSATION_ID)

        startActivity(
            Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_ASSIST
                putExtra(SidekickTileService.EXTRA_INPUT_MODE, inputMode)
                if (!conversationId.isNullOrBlank()) {
                    putExtra(SidekickTileService.EXTRA_CONVERSATION_ID, conversationId)
                }
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
        )

        finish()
    }
}
