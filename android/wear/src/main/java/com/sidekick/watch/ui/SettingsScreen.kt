package com.sidekick.watch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
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

@Composable
fun SettingsScreen(
    selectedAgentFlavorId: String,
    selectedAgentFlavorName: String,
    baseUrl: String,
    model: String,
    authToken: String,
    voiceInputProviderId: String,
    sttAuthToken: String,
    onSaveAgentFlavor: (String) -> Unit,
    onSaveBaseUrl: (String) -> Unit,
    onSaveModel: (String) -> Unit,
    onSaveAuthToken: (String) -> Unit,
    onSaveVoiceInputProvider: (String) -> Unit,
    onSaveSttAuthToken: (String) -> Unit,
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
                    SectionTitle(
                        title = "Agent",
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                    )
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
                            style = MaterialTheme.typography.bodyExtraSmall,
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
                            style = MaterialTheme.typography.bodyExtraSmall,
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
                            style = MaterialTheme.typography.bodyExtraSmall,
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
                            style = MaterialTheme.typography.bodyExtraSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                item {
                    SectionTitle(
                        title = "Voice",
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                    )
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
                            style = MaterialTheme.typography.bodyExtraSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                if (voiceInputProviderId == VoiceInputProviders.SARVAM) {
                    item {
                        Card(
                            onClick = { dialog = SettingDialog.SttAuthToken },
                            modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                            transformation = SurfaceTransformation(transformationSpec),
                        ) {
                            Text("STT Token", style = MaterialTheme.typography.labelSmall)
                            Text(maskToken(sttAuthToken), style = MaterialTheme.typography.bodyExtraSmall)
                        }
                    }
                }

                item {
                    SectionTitle(
                        title = "Misc",
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                    )
                }

                item {
                    Card(
                        onClick = { dialog = SettingDialog.ResetAll },
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) {
                        Text(
                            "Reset All",
                            style = MaterialTheme.typography.labelSmall,
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
private fun SectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
        modifier = modifier.padding(top = 10.dp, bottom = 2.dp),
    )
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
    SttAuthToken,
    ResetAll,
}
