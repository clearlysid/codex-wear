package com.sidekick.watch.tile

import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.PrimaryLayoutMargins
import androidx.wear.protolayout.material3.ButtonColors
import androidx.wear.protolayout.material3.buttonGroup
import androidx.wear.protolayout.material3.icon
import androidx.wear.protolayout.material3.iconButton
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.material3.textEdgeButton
import androidx.wear.protolayout.types.LayoutColor
import androidx.wear.protolayout.types.LayoutString
import androidx.wear.tiles.Material3TileService
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import com.sidekick.watch.R
import com.sidekick.watch.presentation.TileEntryActivity

class SidekickTileService : Material3TileService() {

    override suspend fun MaterialScope.tileResponse(requestParams: RequestBuilders.TileRequest): TileBuilders.Tile {
        val layout = primaryLayout(
            titleSlot = {
                text(LayoutString("Sidekick"))
            },
            mainSlot = {
                buttonGroup(
                    width = DimensionBuilders.expand(),
                    height = DimensionBuilders.expand(),
                    spacing = 6f,
                ) {
                    buttonGroupItem {
                        actionButton(
                            drawableId = R.drawable.ic_keyboard,
                            inputMode = "keyboard",
                            clickId = "open_keyboard",
                            colors = buttonColors(TYPE_BUTTON_BG),
                        )
                    }
                    buttonGroupItem {
                        actionButton(
                            drawableId = R.drawable.ic_shortcut_mic,
                            inputMode = "voice",
                            clickId = "open_voice",
                            colors = buttonColors(TALK_BUTTON_BG),
                        )
                    }
                }
            },
            bottomSlot = {
                textEdgeButton(
                    onClick = buildClickable("open_chats", "chats"),
                    colors = buttonColors(CHATS_BUTTON_BG),
                ) {
                    text(LayoutString("Chats"))
                }
            },
            margins = PrimaryLayoutMargins.MIN_PRIMARY_LAYOUT_MARGIN,
        )

        return TileBuilders.Tile.Builder()
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout))
            .build()
    }

    private fun MaterialScope.actionButton(
        drawableId: Int,
        inputMode: String,
        clickId: String,
        colors: ButtonColors,
    ): LayoutElementBuilders.LayoutElement =
        iconButton(
            onClick = buildClickable(clickId, inputMode),
            iconContent = { icon(iconResource(drawableId)) },
            width = DimensionBuilders.weight(1f),
            height = DimensionBuilders.expand(),
            colors = colors,
        )

    private fun buildClickable(
        clickId: String,
        inputMode: String,
        conversationId: String? = null,
    ): ModifiersBuilders.Clickable =
        ModifiersBuilders.Clickable.Builder()
            .setId(clickId)
            .setOnClick(buildLaunchAction(inputMode, conversationId))
            .build()

    private fun buildLaunchAction(
        inputMode: String,
        conversationId: String? = null,
    ): androidx.wear.protolayout.ActionBuilders.LaunchAction {
        val activity = androidx.wear.protolayout.ActionBuilders.AndroidActivity.Builder()
            .setPackageName(packageName)
            .setClassName(TileEntryActivity::class.java.name)
            .addKeyToExtraMapping(
                EXTRA_INPUT_MODE,
                androidx.wear.protolayout.ActionBuilders.AndroidStringExtra.Builder().setValue(inputMode).build(),
            )

        if (!conversationId.isNullOrBlank()) {
            activity.addKeyToExtraMapping(
                EXTRA_CONVERSATION_ID,
                androidx.wear.protolayout.ActionBuilders.AndroidStringExtra.Builder().setValue(conversationId).build(),
            )
        }

        return androidx.wear.protolayout.ActionBuilders.LaunchAction.Builder()
            .setAndroidActivity(activity.build())
            .build()
    }

    private fun iconResource(drawableId: Int): ResourceBuilders.ImageResource =
        ResourceBuilders.ImageResource.Builder()
            .setAndroidResourceByResId(
                ResourceBuilders.AndroidImageResourceByResId.Builder()
                    .setResourceId(drawableId)
                    .build(),
            )
            .build()

    private fun buttonColors(containerArgb: Int): ButtonColors =
        ButtonColors(
            LayoutColor(containerArgb),
            LayoutColor(WHITE),
            LayoutColor(WHITE),
            LayoutColor(WHITE),
        )

    companion object {
        const val EXTRA_INPUT_MODE = "input_mode"
        const val EXTRA_CONVERSATION_ID = "conversation_id"
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val TYPE_BUTTON_BG = 0xFF334155.toInt()
        private const val TALK_BUTTON_BG = 0xFF36594A.toInt()
        private const val CHATS_BUTTON_BG = 0xFF2B2B2F.toInt()
    }
}
