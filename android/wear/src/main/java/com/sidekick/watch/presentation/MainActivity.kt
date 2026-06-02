package com.sidekick.watch.presentation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.app.RemoteInput
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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
    private var shouldCreateConversationAfterComposer: Boolean = false
    private var isVoiceListening by mutableStateOf(false)
    private var voiceRmsLevel by mutableStateOf(0f)
    private var voicePartialText by mutableStateOf("")
    private var voiceReady by mutableStateOf(false)

    private var speechRecognizer: SpeechRecognizer? = null

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
            if (text.isNotEmpty()) startFreshConversationFromInput(text)
        }

        override fun onError(error: Int) {
            isVoiceListening = false
            voicePartialText = ""
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
        handleLaunchIntent(intent)

        setContent {
            val uiState = viewModel.uiState.collectAsStateWithLifecycle().value
            val pagerState = rememberPagerState(initialPage = HOME_PAGE, pageCount = { PAGE_COUNT })
            val homeNavController = rememberNavController()

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
        handleLaunchIntent(intent)
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
            if (inputMode == "keyboard") {
                requestedHomePage = true
                requestedKeyboardLaunch = true
            } else {
                startVoiceRecognitionWithPermission()
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
    }
}
