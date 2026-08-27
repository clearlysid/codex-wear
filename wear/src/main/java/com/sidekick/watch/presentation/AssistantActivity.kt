package com.sidekick.watch.presentation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.viewModels
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.sidekick.watch.BuildConfig
import com.sidekick.watch.data.AgentSettings
import com.sidekick.watch.data.HttpClientProvider
import com.sidekick.watch.data.SarvamRealtimeTranscriber
import com.sidekick.watch.data.SettingsRepository
import com.sidekick.watch.data.TranscriptionEvent
import com.sidekick.watch.data.VoiceInputProviders
import com.sidekick.watch.presentation.theme.SidekickTheme
import com.sidekick.watch.ui.AssistantScreen
import com.sidekick.watch.viewmodel.AssistantPhaseUi
import com.sidekick.watch.viewmodel.AssistantViewModel
import com.sidekick.watch.viewmodel.AssistantUiState
import com.sidekick.watch.viewmodel.ProjectOptionUi
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AssistantActivity : ComponentActivity() {
    private val viewModel: AssistantViewModel by viewModels {
        AssistantViewModel.Factory(this, intent?.getStringExtra(EXTRA_TASK_ID))
    }
    private val settingsRepository by lazy { SettingsRepository(applicationContext) }
    private val transcriber by lazy { SarvamRealtimeTranscriber(HttpClientProvider.client, lifecycleScope) }
    private var settings: AgentSettings = AgentSettings()
    private var speechRecognizer: SpeechRecognizer? = null
    private var recognizerCommitted = ""
    private var showRedoConfirmation by mutableStateOf(false)

    private val audioPermissionLauncher = registerForActivityResult(RequestPermission()) { granted ->
        if (granted) startCapture() else viewModel.fail("Microphone permission is required")
    }
    private val notificationPermissionLauncher = registerForActivityResult(RequestPermission()) { }

    private val recognitionListener =
        object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = viewModel.listening()
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = viewModel.updateLevel(rmsdB)
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults.bestTranscript()
                viewModel.updateTranscript(joinTranscript(recognizerCommitted, partial))
            }

            override fun onResults(results: Bundle?) {
                val segment = results.bestTranscript()
                recognizerCommitted = joinTranscript(recognizerCommitted, segment)
                viewModel.updateTranscript(recognizerCommitted)
                if (viewModel.uiState.value.phase == AssistantPhaseUi.LISTENING) {
                    window.decorView.postDelayed(::startAndroidRecognizer, RECOGNIZER_RESTART_MS)
                }
            }

            override fun onError(error: Int) {
                if (
                    viewModel.uiState.value.phase == AssistantPhaseUi.LISTENING &&
                    error in setOf(SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                ) {
                    window.decorView.postDelayed(::startAndroidRecognizer, RECOGNIZER_RESTART_MS)
                } else {
                    viewModel.fail("Voice input failed")
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.SCREENSHOT_MODE) {
            setContent {
                SidekickTheme {
                    AssistantScreen(
                        state = AssistantUiState(
                            phase = AssistantPhaseUi.LISTENING,
                            rmsLevel = 5.5f,
                            projects = listOf(
                                ProjectOptionUi(null, "No project"),
                                ProjectOptionUi("sidekick", "sidekick"),
                            ),
                        ),
                        onProjectStep = {},
                        onRedo = {},
                        onSend = {},
                        onRetry = {},
                        onApprove = {},
                        onDecline = {},
                        onDone = {},
                    )
                }
            }
            return
        }
        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            SidekickTheme {
                AssistantScreen(
                    state = state,
                    onProjectStep = viewModel::projectStep,
                    onRedo = { showRedoConfirmation = true },
                    onSend = {
                        requestNotificationPermissionIfNeeded()
                        stopCapture()
                        viewModel.submit()
                    },
                    onRetry = {
                        if (state.taskId == null) restartCapture() else viewModel.retry()
                    },
                    onApprove = viewModel::approve,
                    onDecline = viewModel::decline,
                    onDone = ::finish,
                )
                if (showRedoConfirmation) {
                    AlertDialog(
                        visible = true,
                        onDismissRequest = { showRedoConfirmation = false },
                        title = { Text("Start over?", style = MaterialTheme.typography.titleSmall) },
                        text = { Text("This clears the current transcript.") },
                        confirmButton = {
                            FilledIconButton(onClick = {
                                showRedoConfirmation = false
                                restartCapture()
                            }) {
                                Icon(Icons.Filled.Check, contentDescription = "Start over")
                            }
                        },
                        dismissButton = {
                            FilledIconButton(onClick = { showRedoConfirmation = false }) {
                                Icon(Icons.Filled.Close, contentDescription = "Keep transcript")
                            }
                        },
                    )
                }
            }
        }

        lifecycleScope.launch {
            settings = settingsRepository.settingsFlow.first()
            window.decorView.post(::requestAudioAndStart)
        }
    }

    override fun onDestroy() {
        stopCapture()
        speechRecognizer?.destroy()
        speechRecognizer = null
        super.onDestroy()
    }

    private fun requestAudioAndStart() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startCapture()
        } else {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun startCapture() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (settings.voiceInputProviderId == VoiceInputProviders.ANDROID_RECOGNIZER) {
            startAndroidRecognizer()
        } else {
            startSarvam()
        }
    }

    private fun startSarvam() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            viewModel.fail("Microphone permission is required")
            return
        }
        try {
            transcriber.start(settings) { event ->
                lifecycleScope.launch {
                    when (event) {
                        TranscriptionEvent.Connected -> viewModel.listening()
                        is TranscriptionEvent.Level -> viewModel.updateLevel(event.rms)
                        is TranscriptionEvent.Partial -> viewModel.updateTranscript(event.text)
                        is TranscriptionEvent.Final -> viewModel.updateTranscript(event.text)
                        is TranscriptionEvent.Error -> viewModel.fail(event.message)
                        TranscriptionEvent.SpeechStarted, TranscriptionEvent.SpeechEnded -> Unit
                    }
                }
            }
        } catch (_: SecurityException) {
            viewModel.fail("Microphone permission is required")
        }
    }

    private fun startAndroidRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            viewModel.fail("Speech recognizer is unavailable")
            return
        }
        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(recognitionListener)
            }
        }
        val intent =
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            }
        speechRecognizer?.startListening(intent)
    }

    private fun restartCapture() {
        stopCapture()
        recognizerCommitted = ""
        viewModel.redo()
        startCapture()
    }

    private fun stopCapture() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        speechRecognizer?.cancel()
        transcriber.stop()
    }

    companion object {
        const val EXTRA_TASK_ID = "task_id"
        private const val RECOGNIZER_RESTART_MS = 180L
    }
}

private fun Bundle?.bestTranscript(): String =
    this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()

private fun joinTranscript(committed: String, segment: String): String =
    listOf(committed.trim(), segment.trim()).filter(String::isNotBlank).joinToString(" ")
