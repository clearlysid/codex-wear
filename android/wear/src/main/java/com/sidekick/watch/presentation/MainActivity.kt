package com.sidekick.watch.presentation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.app.RemoteInput
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.input.RemoteInputIntentHelper
import com.sidekick.watch.data.SettingsRepository
import com.sidekick.watch.presentation.theme.SidekickTheme
import com.sidekick.watch.tile.SidekickTileService
import com.sidekick.watch.ui.ChatScreen
import com.sidekick.watch.ui.HomeScreen
import com.sidekick.watch.ui.ImageViewerScreen
import com.sidekick.watch.ui.SettingsScreen
import com.sidekick.watch.ui.VoiceListeningScreen
import com.sidekick.watch.viewmodel.ChatViewModel
import com.sidekick.watch.voice.SidekickVoiceInteractionSession
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val settingsRepository by lazy { SettingsRepository(applicationContext) }

    private val viewModel: ChatViewModel by viewModels {
        ChatViewModel.Factory(
            context = applicationContext,
            settingsRepository = settingsRepository,
        )
    }

    private var requestedHomePage by mutableStateOf(false)
    private var requestedConversationPageId by mutableStateOf<String?>(null)
    private var requestedKeyboardLaunch by mutableStateOf(false)
    private var requestedVoiceLaunch by mutableStateOf(false)
    private var shouldCreateConversationAfterComposer: Boolean = false
    private var isVoiceListening by mutableStateOf(false)
    private var voiceRmsLevel by mutableStateOf(0f)
    private var voicePartialText by mutableStateOf("")
    private var voiceReady by mutableStateOf(false)

    private var speechRecognizer: SpeechRecognizer? = null
    private var voiceRetryCount = 0
    private var pendingLaunchIntent: Intent? = null

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { voiceReady = true }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) { voiceRmsLevel = rmsdB }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            voicePartialText = text
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            isVoiceListening = false
            voicePartialText = ""
            voiceRetryCount = 0
            if (text.isNotEmpty()) startFreshConversationFromInput(text)
        }

        override fun onError(error: Int) {
            Log.w(TAG, "Speech recognition failed: ${speechErrorName(error)}")
            if (voiceRetryCount < MAX_VOICE_RETRIES && shouldRetryVoice(error)) {
                voiceRetryCount += 1
                speechRecognizer?.cancel()
                speechRecognizer?.destroy()
                speechRecognizer = null
                window.decorView.postDelayed({ startVoiceRecognition() }, VOICE_RETRY_DELAY_MS)
            } else {
                isVoiceListening = false
                voicePartialText = ""
                Toast.makeText(this@MainActivity, "Mic failed: ${speechErrorName(error)}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val audioPermissionLauncher = registerForActivityResult(RequestPermission()) { granted ->
        if (granted) startVoiceRecognition()
    }

    private val textInputLauncher =
        registerForActivityResult(StartActivityForResult()) { result ->
            val data = result.data ?: return@registerForActivityResult
            val results = RemoteInput.getResultsFromIntent(data) ?: return@registerForActivityResult
            val enteredText = results.getCharSequence(CHAT_TEXT_RESULT_KEY)?.toString().orEmpty().trim()
            if (enteredText.isNotEmpty()) {
                if (shouldCreateConversationAfterComposer) {
                    startFreshConversationFromInput(enteredText)
                } else {
                    viewModel.sendMessage(enteredText)
                }
            }
            shouldCreateConversationAfterComposer = false
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val uiState = viewModel.uiState.collectAsStateWithLifecycle().value
            val pagerState = rememberPagerState(initialPage = HOME_PAGE, pageCount = { PAGE_COUNT })
            val homeNavController = rememberNavController()

            LaunchedEffect(uiState.isConversationStateLoaded) {
                if (uiState.isConversationStateLoaded) {
                    val queuedIntent = pendingLaunchIntent
                    pendingLaunchIntent = null
                    handleLaunchIntent(queuedIntent ?: intent)
                }
            }

            LaunchedEffect(requestedHomePage) {
                if (requestedHomePage) {
                    pagerState.animateScrollToPage(HOME_PAGE)
                    homeNavController.navigate(HOME_LIST_ROUTE) {
                        popUpTo(HOME_LIST_ROUTE) { inclusive = false }
                        launchSingleTop = true
                    }
                    requestedHomePage = false
                }
            }

            LaunchedEffect(requestedKeyboardLaunch) {
                if (requestedKeyboardLaunch) {
                    pagerState.animateScrollToPage(HOME_PAGE)
                    homeNavController.navigate(HOME_LIST_ROUTE) {
                        popUpTo(HOME_LIST_ROUTE) { inclusive = false }
                        launchSingleTop = true
                    }
                    shouldCreateConversationAfterComposer = true
                    launchRemoteTextInput()
                    requestedKeyboardLaunch = false
                }
            }

            LaunchedEffect(requestedVoiceLaunch) {
                if (requestedVoiceLaunch) {
                    delay(VOICE_LAUNCH_DELAY_MS)
                    voiceRetryCount = 0
                    startVoiceRecognitionWithPermission()
                    requestedVoiceLaunch = false
                }
            }

            LaunchedEffect(requestedConversationPageId) {
                val conversationId = requestedConversationPageId ?: return@LaunchedEffect
                pagerState.animateScrollToPage(HOME_PAGE)
                homeNavController.navigate("$HOME_CONVERSATION_ROUTE/$conversationId") {
                    popUpTo(HOME_LIST_ROUTE)
                }
                requestedConversationPageId = null
            }

            SidekickTheme(themeId = uiState.themeId) {
                Box(modifier = Modifier.fillMaxSize()) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                    ) { page ->
                        if (page == HOME_PAGE) {
                            NavHost(
                                navController = homeNavController,
                                startDestination = HOME_LIST_ROUTE,
                            ) {
                                composable(HOME_LIST_ROUTE) {
                                    HomeScreen(
                                        conversations = uiState.conversations,
                                        onNewConversationWithKeyboard = {
                                            shouldCreateConversationAfterComposer = true
                                            launchRemoteTextInput()
                                        },
                                        onNewConversationWithVoice = ::startVoiceRecognitionWithPermission,
                                        onOpenConversation = { conversationId ->
                                            viewModel.openConversation(conversationId)
                                            homeNavController.navigate("$HOME_CONVERSATION_ROUTE/$conversationId")
                                        },
                                        onDeleteConversation = viewModel::deleteConversation,
                                        loadMoreIncrement = HOME_CONVERSATIONS_PAGE_INCREMENT,
                                    )
                                }
                                composable(
                                    route = "$HOME_CONVERSATION_ROUTE/{conversationId}",
                                    arguments = listOf(navArgument("conversationId") { type = NavType.StringType }),
                                ) { backStackEntry ->
                                    val conversationId = backStackEntry.arguments?.getString("conversationId").orEmpty()
                                    LaunchedEffect(conversationId) {
                                        if (conversationId.isNotBlank()) {
                                            viewModel.openConversation(conversationId)
                                        }
                                    }
                                    ChatScreen(
                                        uiState = uiState,
                                        conversationTitle = uiState.currentConversationTitle,
                                        onOpenTextInput = ::launchRemoteTextInput,
                                        onImageClick = { url ->
                                            val encoded = URLEncoder.encode(url, "UTF-8")
                                            homeNavController.navigate("$HOME_IMAGE_ROUTE/$encoded")
                                        },
                                    )
                                }
                                composable(
                                    route = "$HOME_IMAGE_ROUTE/{imageUrl}",
                                    arguments = listOf(navArgument("imageUrl") { type = NavType.StringType }),
                                ) { backStackEntry ->
                                    val imageUrl = URLDecoder.decode(
                                        backStackEntry.arguments?.getString("imageUrl").orEmpty(),
                                        "UTF-8",
                                    )
                                    ImageViewerScreen(imageUrl = imageUrl)
                                }
                            }
                        } else {
                            SettingsScreen(
                                selectedAgentFlavorId = uiState.agentFlavorInput,
                                selectedAgentFlavorName = uiState.selectedAgentFlavorName,
                                baseUrl = uiState.baseUrlInput,
                                model = uiState.modelInput,
                                authToken = uiState.authTokenInput,
                                themeId = uiState.themeId,
                                onSaveAgentFlavor = viewModel::saveAgentFlavor,
                                onSaveBaseUrl = viewModel::saveBaseUrl,
                                onSaveModel = viewModel::saveModel,
                                onSaveAuthToken = viewModel::saveAuthToken,
                                onSaveTheme = viewModel::saveTheme,
                                onResetAll = viewModel::resetAll,
                            )
                        }
                    }
                    if (isVoiceListening) {
                        VoiceListeningScreen(
                            rmsLevel = voiceRmsLevel,
                            partialText = voicePartialText,
                            isReady = voiceReady,
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (viewModel.uiState.value.isConversationStateLoaded) {
            handleLaunchIntent(intent)
        } else {
            pendingLaunchIntent = Intent(intent)
        }
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        super.onDestroy()
    }

    private fun handleLaunchIntent(intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_ASSIST) {
            // Text already captured by VoiceInteractionSession — skip to conversation
            val voiceText = intent.getStringExtra(SidekickVoiceInteractionSession.EXTRA_VOICE_TEXT)
            if (voiceText != null) {
                intent.removeExtra(SidekickVoiceInteractionSession.EXTRA_VOICE_TEXT)
                startFreshConversationFromInput(voiceText)
                return
            }
            val inputMode = intent.getStringExtra(SidekickTileService.EXTRA_INPUT_MODE) ?: "voice"
            when (inputMode) {
                "keyboard" -> {
                    requestedHomePage = true
                    requestedKeyboardLaunch = true
                }
                "chats" -> requestedHomePage = true
                "activity" -> {
                    val conversationId = intent.getStringExtra(SidekickTileService.EXTRA_CONVERSATION_ID)
                    if (conversationId.isNullOrBlank()) {
                        requestedHomePage = true
                    } else {
                        requestedConversationPageId = conversationId
                    }
                }
                else -> requestedVoiceLaunch = true
            }
        }
    }

    private fun launchRemoteTextInput() {
        val remoteInput = RemoteInput.Builder(CHAT_TEXT_RESULT_KEY).setLabel("Type message").build()
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent().apply {
            RemoteInputIntentHelper.putRemoteInputsExtra(this, listOf(remoteInput))
            RemoteInputIntentHelper.putCancelLabelExtra(this, "Cancel")
            RemoteInputIntentHelper.putConfirmLabelExtra(this, "Done")
        }
        textInputLauncher.launch(intent)
    }

    private fun startVoiceRecognitionWithPermission() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startVoiceRecognition()
        } else {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startVoiceRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Speech recognizer unavailable", Toast.LENGTH_SHORT).show()
            Log.w(TAG, "Speech recognizer unavailable")
            return
        }
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(recognitionListener)
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        }
        voiceRmsLevel = 0f
        voicePartialText = ""
        voiceReady = false
        isVoiceListening = true
        speechRecognizer?.startListening(intent)
    }

    private fun startFreshConversationFromInput(inputText: String) {
        val targetConversationId = viewModel.startNewConversation()
        viewModel.openConversation(targetConversationId)
        requestedConversationPageId = targetConversationId
        viewModel.sendMessage(inputText)
    }

    private companion object {
        const val CHAT_TEXT_RESULT_KEY = "chat_text_input"
        const val HOME_PAGE = 0
        const val PAGE_COUNT = 2
        const val HOME_LIST_ROUTE = "home/list"
        const val HOME_CONVERSATION_ROUTE = "home/conversation"
        const val HOME_IMAGE_ROUTE = "home/image"
        const val HOME_CONVERSATIONS_PAGE_INCREMENT = 5
        const val TAG = "SidekickVoice"
        const val VOICE_LAUNCH_DELAY_MS = 250L
        const val VOICE_RETRY_DELAY_MS = 300L
        const val MAX_VOICE_RETRIES = 1
    }
}

private fun shouldRetryVoice(error: Int): Boolean =
    error in setOf(
        SpeechRecognizer.ERROR_CLIENT,
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
        SpeechRecognizer.ERROR_SERVER,
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
    )

private fun speechErrorName(error: Int): String =
    when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "audio"
        SpeechRecognizer.ERROR_CLIENT -> "client"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "permission"
        SpeechRecognizer.ERROR_NETWORK -> "network"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "no match"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "busy"
        SpeechRecognizer.ERROR_SERVER -> "server"
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "server disconnected"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "speech timeout"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "too many requests"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "language not supported"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "language unavailable"
        SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "cannot check support"
        SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS -> "cannot listen to download events"
        else -> "unknown $error"
    }
