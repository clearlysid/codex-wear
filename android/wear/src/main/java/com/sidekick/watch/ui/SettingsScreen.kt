package com.sidekick.watch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.sidekick.watch.data.AgentBackends
import com.sidekick.watch.data.VoiceInputProviders
import com.sidekick.watch.presentation.theme.appThemes
import com.sidekick.watch.presentation.theme.themeById

@Composable
fun SettingsScreen(
    selectedAgentFlavorId: String,
    selectedAgentFlavorName: String,
    baseUrl: String,
    model: String,
    authToken: String,
    voiceInputProviderId: String,
    sttBaseUrl: String,
    sttModel: String,
    sttLanguageCode: String,
    sttMode: String,
    sttAuthToken: String,
    themeId: String,
    onSaveAgentFlavor: (String) -> Unit,
    onSaveBaseUrl: (String) -> Unit,
    onSaveModel: (String) -> Unit,
    onSaveAuthToken: (String) -> Unit,
    onSaveVoiceInputProvider: (String) -> Unit,
    onSaveSttBaseUrl: (String) -> Unit,
    onSaveSttModel: (String) -> Unit,
    onSaveSttLanguageCode: (String) -> Unit,
    onSaveSttMode: (String) -> Unit,
    onSaveSttAuthToken: (String) -> Unit,
    onSaveTheme: (String) -> Unit,
    onResetAll: () -> Unit,
) {
    var dialog by remember { mutableStateOf<SettingDialog?>(null) }

    AppScaffold {
        val listState = rememberTransformingLazyColumnState()
        val transformationSpec = rememberTransformationSpec()

        ScreenScaffold(scrollState = listState) { contentPadding ->
            TransformingLazyColumn(
                state = listState,
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Settings",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                        )
                    }
                }

                item {
                    Card(
                        onClick = { dialog = SettingDialog.AgentFlavor },
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec),
                    ) {
                        Text("Agent Flavour", style = MaterialTheme.typography.labelSmall)
                        Text(
                            text = selectedAgentFlavorName,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                item {
                    Card(
                        onClick = { dialog = SettingDialog.BaseUrl },
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec),
                    ) {
                        Text("Base URL", style = MaterialTheme.typography.labelSmall)
                        Text(
                            text = baseUrl.ifBlank { "https://..." },
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                item {
                    Card(
                        onClick = { dialog = SettingDialog.Model },
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec),
                    ) {
                        Text("Model", style = MaterialTheme.typography.labelSmall)
                        Text(
                            text = model.ifBlank { "default" },
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                item {
                    Card(
                        onClick = { dialog = SettingDialog.AuthToken },
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec),
                    ) {
                        Text("Auth Token", style = MaterialTheme.typography.labelSmall)
                        Text(
                            text = maskToken(authToken),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                item {
                    Card(
                        onClick = { dialog = SettingDialog.VoiceInputProvider },
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec),
                    ) {
                        Text("Voice Input", style = MaterialTheme.typography.labelSmall)
                        Text(
                            text = voiceProviderName(voiceInputProviderId),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                if (voiceInputProviderId == VoiceInputProviders.SARVAM) {
                    item {
                        Card(
                            onClick = { dialog = SettingDialog.SttBaseUrl },
                            modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                            transformation = SurfaceTransformation(transformationSpec),
                        ) {
                            Text("STT URL", style = MaterialTheme.typography.labelSmall)
                            Text(
                                text = sttBaseUrl.ifBlank { VoiceInputProviders.SARVAM_BASE_URL },
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    item {
                        Card(
                            onClick = { dialog = SettingDialog.SttModel },
                            modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                            transformation = SurfaceTransformation(transformationSpec),
                        ) {
                            Text("STT Model", style = MaterialTheme.typography.labelSmall)
                            Text(sttModel.ifBlank { "saaras:v3" }, style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    item {
                        Card(
                            onClick = { dialog = SettingDialog.SttLanguage },
                            modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                            transformation = SurfaceTransformation(transformationSpec),
                        ) {
                            Text("STT Lang", style = MaterialTheme.typography.labelSmall)
                            Text(sttLanguageCode.ifBlank { "unknown" }, style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    item {
                        Card(
                            onClick = { dialog = SettingDialog.SttMode },
                            modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                            transformation = SurfaceTransformation(transformationSpec),
                        ) {
                            Text("STT Mode", style = MaterialTheme.typography.labelSmall)
                            Text(sttMode.ifBlank { "transcribe" }, style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    item {
                        Card(
                            onClick = { dialog = SettingDialog.SttAuthToken },
                            modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                            transformation = SurfaceTransformation(transformationSpec),
                        ) {
                            Text("STT Token", style = MaterialTheme.typography.labelSmall)
                            Text(maskToken(sttAuthToken), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                item {
                    Card(
                        onClick = { dialog = SettingDialog.Theme },
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec),
                    ) {
                        Text("Color Theme", style = MaterialTheme.typography.labelSmall)
                        Text(
                            text = themeById(themeId).displayName,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }

                item {
                    Card(
                        onClick = { dialog = SettingDialog.ResetAll },
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec),
                    ) {
                        Text(
                            "Reset All",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        when (dialog) {
            SettingDialog.AgentFlavor -> {
                AgentFlavorDialog(
                    initialSelection = selectedAgentFlavorId,
                    onCancel = { dialog = null },
                    onSave = { chosenId ->
                        onSaveAgentFlavor(chosenId)
                        dialog = null
                    },
                )
            }

            SettingDialog.BaseUrl -> {
                TextSettingDialog(
                    title = "Base URL",
                    initialValue = baseUrl,
                    keyboardType = KeyboardType.Uri,
                    placeholder = "https://...",
                    onCancel = { dialog = null },
                    onSave = { value ->
                        onSaveBaseUrl(value)
                        dialog = null
                    },
                )
            }

            SettingDialog.Model -> {
                TextSettingDialog(
                    title = "Model",
                    initialValue = model,
                    keyboardType = KeyboardType.Text,
                    placeholder = "model name",
                    onCancel = { dialog = null },
                    onSave = { value ->
                        onSaveModel(value)
                        dialog = null
                    },
                )
            }

            SettingDialog.AuthToken -> {
                TextSettingDialog(
                    title = "Auth Token",
                    initialValue = authToken,
                    keyboardType = KeyboardType.Text,
                    placeholder = "token",
                    onCancel = { dialog = null },
                    onSave = { value ->
                        onSaveAuthToken(value)
                        dialog = null
                    },
                )
            }

            SettingDialog.VoiceInputProvider -> {
                VoiceInputProviderDialog(
                    initialSelection = voiceInputProviderId,
                    onCancel = { dialog = null },
                    onSave = { chosenId ->
                        onSaveVoiceInputProvider(chosenId)
                        dialog = null
                    },
                )
            }

            SettingDialog.SttBaseUrl -> {
                TextSettingDialog(
                    title = "STT URL",
                    initialValue = sttBaseUrl,
                    keyboardType = KeyboardType.Uri,
                    placeholder = VoiceInputProviders.SARVAM_BASE_URL,
                    onCancel = { dialog = null },
                    onSave = { value ->
                        onSaveSttBaseUrl(value)
                        dialog = null
                    },
                )
            }

            SettingDialog.SttModel -> {
                TextSettingDialog(
                    title = "STT Model",
                    initialValue = sttModel,
                    keyboardType = KeyboardType.Text,
                    placeholder = "saaras:v3",
                    onCancel = { dialog = null },
                    onSave = { value ->
                        onSaveSttModel(value)
                        dialog = null
                    },
                )
            }

            SettingDialog.SttLanguage -> {
                TextSettingDialog(
                    title = "STT Lang",
                    initialValue = sttLanguageCode,
                    keyboardType = KeyboardType.Text,
                    placeholder = "unknown",
                    onCancel = { dialog = null },
                    onSave = { value ->
                        onSaveSttLanguageCode(value)
                        dialog = null
                    },
                )
            }

            SettingDialog.SttMode -> {
                TextSettingDialog(
                    title = "STT Mode",
                    initialValue = sttMode,
                    keyboardType = KeyboardType.Text,
                    placeholder = "transcribe",
                    onCancel = { dialog = null },
                    onSave = { value ->
                        onSaveSttMode(value)
                        dialog = null
                    },
                )
            }

            SettingDialog.SttAuthToken -> {
                TextSettingDialog(
                    title = "STT Token",
                    initialValue = sttAuthToken,
                    keyboardType = KeyboardType.Text,
                    placeholder = "token",
                    onCancel = { dialog = null },
                    onSave = { value ->
                        onSaveSttAuthToken(value)
                        dialog = null
                    },
                )
            }

            SettingDialog.Theme -> {
                ThemePickerDialog(
                    selectedThemeId = themeId,
                    onCancel = { dialog = null },
                    onSave = { chosenId ->
                        onSaveTheme(chosenId)
                        dialog = null
                    },
                )
            }

            SettingDialog.ResetAll -> {
                AlertDialog(
                    visible = true,
                    onDismissRequest = { dialog = null },
                    title = { Text("Reset All?", style = MaterialTheme.typography.titleSmall) },
                    text = {
                        Text(
                            "This will clear all conversations and settings.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    confirmButton = {
                        FilledIconButton(onClick = {
                            onResetAll()
                            dialog = null
                        }) {
                            Icon(Icons.Filled.Check, contentDescription = "Confirm")
                        }
                    },
                    dismissButton = {
                        FilledIconButton(onClick = { dialog = null }) {
                            Icon(Icons.Filled.Close, contentDescription = "Cancel")
                        }
                    },
                )
            }

            null -> Unit
        }
    }
}

@Composable
private fun AgentFlavorDialog(
    initialSelection: String,
    onCancel: () -> Unit,
    onSave: (String) -> Unit,
) {
    var selected by remember(initialSelection) { mutableStateOf(initialSelection) }

    AlertDialog(
        visible = true,
        onDismissRequest = onCancel,
        title = { Text("Agent Flavour", style = MaterialTheme.typography.titleSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AgentBackends.supported.forEach { backend ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { selected = backend.id }
                                .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = if (selected == backend.id) Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked,
                            contentDescription = null,
                        )
                        Text(backend.displayName, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            FilledIconButton(onClick = { onSave(selected) }) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Save",
                )
            }
        },
        dismissButton = {
            FilledIconButton(onClick = onCancel) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Cancel",
                )
            }
        },
    )
}

@Composable
private fun ThemePickerDialog(
    selectedThemeId: String,
    onCancel: () -> Unit,
    onSave: (String) -> Unit,
) {
    var selected by remember(selectedThemeId) { mutableStateOf(selectedThemeId) }

    AlertDialog(
        visible = true,
        onDismissRequest = onCancel,
        title = { Text("Color Theme", style = MaterialTheme.typography.titleSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                appThemes.forEach { theme ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { selected = theme.id }
                                .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(theme.previewColor),
                        )
                        Text(theme.displayName, style = MaterialTheme.typography.bodySmall)
                        if (selected == theme.id) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            FilledIconButton(onClick = { onSave(selected) }) {
                Icon(Icons.Filled.Check, contentDescription = "Save")
            }
        },
        dismissButton = {
            FilledIconButton(onClick = onCancel) {
                Icon(Icons.Filled.Close, contentDescription = "Cancel")
            }
        },
    )
}

@Composable
private fun TextSettingDialog(
    title: String,
    initialValue: String,
    keyboardType: KeyboardType,
    placeholder: String,
    onCancel: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }

    AlertDialog(
        visible = true,
        onDismissRequest = onCancel,
        title = { Text(title, style = MaterialTheme.typography.titleSmall) },
        text = {
            InputField(
                value = value,
                onValueChange = { value = it },
                keyboardType = keyboardType,
                placeholder = placeholder,
            )
        },
        confirmButton = {
            FilledIconButton(onClick = { onSave(value) }) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Save",
                )
            }
        },
        dismissButton = {
            FilledIconButton(onClick = onCancel) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Cancel",
                )
            }
        },
    )
}

@Composable
private fun InputField(
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType,
    placeholder: String,
) {
    val shape = RoundedCornerShape(14.dp)
    val colors = MaterialTheme.colorScheme

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        textStyle = MaterialTheme.typography.bodySmall.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { innerTextField ->
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(colors.surfaceContainer, shape)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                innerTextField()
                if (value.isBlank()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

private fun maskToken(token: String): String {
    if (token.isBlank()) return "token"
    if (token.length <= 4) return "*".repeat(token.length)
    val prefix = token.take(4)
    return prefix + "*".repeat(token.length - 4)
}

@Composable
private fun VoiceInputProviderDialog(
    initialSelection: String,
    onCancel: () -> Unit,
    onSave: (String) -> Unit,
) {
    var selected by remember(initialSelection) { mutableStateOf(initialSelection.ifBlank { VoiceInputProviders.SARVAM }) }
    val providers = listOf(
        VoiceInputProviders.SARVAM to "Sarvam",
        VoiceInputProviders.ANDROID_RECOGNIZER to "Android",
    )

    AlertDialog(
        visible = true,
        onDismissRequest = onCancel,
        title = { Text("Voice Input", style = MaterialTheme.typography.titleSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                providers.forEach { (id, label) ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { selected = id }
                                .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = if (selected == id) Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked,
                            contentDescription = null,
                        )
                        Text(label, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            FilledIconButton(onClick = { onSave(selected) }) {
                Icon(Icons.Filled.Check, contentDescription = "Save")
            }
        },
        dismissButton = {
            FilledIconButton(onClick = onCancel) {
                Icon(Icons.Filled.Close, contentDescription = "Cancel")
            }
        },
    )
}

private fun voiceProviderName(providerId: String): String =
    when (providerId) {
        VoiceInputProviders.ANDROID_RECOGNIZER -> "Android"
        else -> "Sarvam"
    }

private enum class SettingDialog {
    AgentFlavor,
    BaseUrl,
    Model,
    AuthToken,
    VoiceInputProvider,
    SttBaseUrl,
    SttModel,
    SttLanguage,
    SttMode,
    SttAuthToken,
    Theme,
    ResetAll,
}
