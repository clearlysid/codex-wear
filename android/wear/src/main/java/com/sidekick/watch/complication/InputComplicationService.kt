package com.sidekick.watch.complication

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.sidekick.watch.R
import com.sidekick.watch.presentation.TileEntryActivity
import com.sidekick.watch.tile.SidekickTileService

abstract class InputComplicationService : SuspendingComplicationDataSourceService() {
    protected abstract val text: String
    protected abstract val title: String
    protected abstract val contentDescription: String
    protected abstract val inputMode: String
    protected abstract val iconResId: Int
    protected abstract val requestCode: Int

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData {
        if (request.complicationType != ComplicationType.SHORT_TEXT) {
            return NoDataComplicationData()
        }
        return complicationData(includeTapAction = true)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.SHORT_TEXT) return null
        return complicationData(includeTapAction = false)
    }

    private fun complicationData(includeTapAction: Boolean): ShortTextComplicationData {
        val builder = ShortTextComplicationData.Builder(
            PlainComplicationText.Builder(text).build(),
            PlainComplicationText.Builder(contentDescription).build(),
        )
            .setTitle(PlainComplicationText.Builder(title).build())
            .setMonochromaticImage(
                MonochromaticImage.Builder(
                    Icon.createWithResource(this, iconResId),
                ).build(),
            )
        if (includeTapAction) {
            builder.setTapAction(tapAction())
        }
        return builder.build()
    }

    private fun tapAction(): PendingIntent {
        val intent = Intent(this, TileEntryActivity::class.java).apply {
            putExtra(SidekickTileService.EXTRA_INPUT_MODE, inputMode)
        }
        return PendingIntent.getActivity(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

class KeyboardComplicationService : InputComplicationService() {
    override val text = "Type"
    override val title = "Sidekick"
    override val contentDescription = "Type to Sidekick"
    override val inputMode = "keyboard"
    override val iconResId = R.drawable.ic_keyboard
    override val requestCode = 1001
}

class VoiceComplicationService : InputComplicationService() {
    override val text = "Talk"
    override val title = "Sidekick"
    override val contentDescription = "Talk to Sidekick"
    override val inputMode = "voice"
    override val iconResId = R.drawable.ic_shortcut_mic
    override val requestCode = 1002
}
