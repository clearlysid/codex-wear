package com.sidekick.watch.tile

import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material3.ButtonColors
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.PrimaryLayoutMargins
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
import com.sidekick.watch.data.AgentRequestBus
import com.sidekick.watch.data.PersistedConversationSummary
import com.sidekick.watch.data.SettingsRepository
import com.sidekick.watch.presentation.TileEntryActivity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class SidekickTileService : Material3TileService() {

    override suspend fun MaterialScope.tileResponse(requestParams: RequestBuilders.TileRequest): TileBuilders.Tile {
        val activeChat = latestActiveChat()
        val layout = primaryLayout(
            titleSlot = {
                text(LayoutString("Assistant"))
            },
            mainSlot = {
                activeChat?.let { latestChatCard(it) }
                    ?: inputButtons()
            },
            bottomSlot = {
                textEdgeButton(
                    onClick = buildClickable("open_chats", "chats"),
                    colors = buttonColors(CHATS_BUTTON_BG, WHITE),
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

    private suspend fun latestActiveChat(): PersistedConversationSummary? {
        val activeConversationId = AgentRequestBus.state.value
            .takeIf { it.isActive }
            ?.conversationId
            ?: return null

        return SettingsRepository(applicationContext)
            .loadConversationState()
            ?.conversations
            ?.firstOrNull { it.id == activeConversationId }
    }

    private fun MaterialScope.inputButtons(): LayoutElementBuilders.LayoutElement =
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
                    colors = buttonColors(KEYBOARD_BUTTON_BG, WHITE),
                )
            }
            buttonGroupItem {
                actionButton(
                    drawableId = R.drawable.ic_shortcut_mic,
                    inputMode = "voice",
                    clickId = "open_voice",
                    colors = buttonColors(MIC_BUTTON_BG, MIC_BUTTON_ICON),
                )
            }
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

    private fun latestChatCard(chat: PersistedConversationSummary): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_START)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(buildClickable("open_active_chat", "activity", chat.id))
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(ColorBuilders.argb(CARD_BG))
                            .setCorner(
                                ModifiersBuilders.Corner.Builder()
                                    .setRadius(DimensionBuilders.dp(18f))
                                    .build(),
                            )
                            .build(),
                    )
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setStart(DimensionBuilders.dp(14f))
                            .setEnd(DimensionBuilders.dp(14f))
                            .setTop(DimensionBuilders.dp(10f))
                            .setBottom(DimensionBuilders.dp(10f))
                            .build(),
                    )
                    .setSemantics(
                        ModifiersBuilders.Semantics.Builder()
                            .setContentDescription(chat.displayText())
                            .build(),
                    )
                    .build(),
            )
            .addContent(
                LayoutElementBuilders.Column.Builder()
                    .setWidth(DimensionBuilders.expand())
                    .setHeight(DimensionBuilders.wrap())
                    .addContent(chatTitle(chat.displayText()))
                    .addContent(spacer(4f))
                    .addContent(chatTime(chat.lastUpdatedEpochMs))
                    .build(),
            )
            .build()

    private fun chatTitle(title: String): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Text.Builder()
            .setText(title)
            .setMaxLines(2)
            .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(DimensionBuilders.sp(14f))
                    .setWeight(LayoutElementBuilders.FONT_WEIGHT_MEDIUM)
                    .setColor(ColorBuilders.argb(WHITE))
                    .build(),
            )
            .build()

    private fun chatTime(epochMs: Long): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Text.Builder()
            .setText(formatLastUpdated(epochMs))
            .setMaxLines(1)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(DimensionBuilders.sp(10f))
                    .setColor(ColorBuilders.argb(SECONDARY_TEXT))
                    .build(),
            )
            .build()

    private fun spacer(heightDp: Float): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Spacer.Builder()
            .setHeight(DimensionBuilders.dp(heightDp))
            .build()

    private fun PersistedConversationSummary.displayText(): String {
        val raw = title?.takeIf { it.isNotBlank() }
            ?: initialPrompt?.takeIf { it.isNotBlank() }
            ?: "Active chat"
        return raw.replace(Regex("\\s+"), " ").trim().take(MAX_CHAT_PILL_CHARS)
    }

    private fun formatLastUpdated(epochMs: Long): String {
        if (epochMs <= 0L) return ""
        return TIME_FORMATTER.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
    }

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

    private fun buttonColors(containerArgb: Int, iconArgb: Int): ButtonColors =
        ButtonColors(
            LayoutColor(containerArgb),
            LayoutColor(iconArgb),
            LayoutColor(iconArgb),
            LayoutColor(iconArgb),
        )

    companion object {
        const val EXTRA_INPUT_MODE = "input_mode"
        const val EXTRA_CONVERSATION_ID = "conversation_id"
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val KEYBOARD_BUTTON_BG = 0xFF36594A.toInt()
        private const val MIC_BUTTON_BG = 0xFFE8B88E.toInt()
        private const val MIC_BUTTON_ICON = 0xFF2A160E.toInt()
        private const val CARD_BG = 0xFF202020.toInt()
        private const val SECONDARY_TEXT = 0xFFB8B8B8.toInt()
        private const val CHATS_BUTTON_BG = 0xFF2B2B2F.toInt()
        private const val MAX_CHAT_PILL_CHARS = 42
        private val TIME_FORMATTER = DateTimeFormatter.ofPattern("h:mm a")
    }
}
