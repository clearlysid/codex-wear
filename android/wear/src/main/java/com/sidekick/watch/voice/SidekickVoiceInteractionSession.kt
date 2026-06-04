package com.sidekick.watch.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.LayoutInflater
import android.view.View
import com.sidekick.watch.R
import com.sidekick.watch.presentation.MainActivity

class SidekickVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        launchActivity()
        hide()
    }

    override fun onCreateContentView(): View {
        return LayoutInflater.from(context).inflate(R.layout.voice_listening, null)
    }

    private fun launchActivity() {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_ASSIST
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(intent)
    }

    companion object {
        const val EXTRA_VOICE_TEXT = "voice_text"
    }
}
