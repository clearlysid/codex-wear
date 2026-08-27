package com.codex.wear.presentation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.codex.wear.BuildConfig
import com.codex.wear.presentation.theme.SidekickTheme
import com.codex.wear.tile.SidekickTileService
import com.codex.wear.ui.CodexHomeScreen
import com.codex.wear.ui.ImageViewerScreen
import com.codex.wear.ui.SettingsScreen
import com.codex.wear.ui.TaskDetailScreen
import com.codex.wear.viewmodel.CodexCompanionViewModel
import com.codex.wear.viewmodel.HomeUiState
import com.codex.wear.viewmodel.TaskStatusUi
import com.codex.wear.viewmodel.TaskSummaryUi
import com.codex.wear.viewmodel.TaskDetailUiState
import com.codex.wear.viewmodel.TimelineItemUi

class MainActivity : ComponentActivity() {
    private val viewModel: CodexCompanionViewModel by viewModels { CodexCompanionViewModel.Factory(this) }
    private var requestedTaskId by mutableStateOf<String?>(null)
    private var requestedImageUrl by mutableStateOf<String?>(null)
    private var requestedAssistantLaunch by mutableStateOf(false)

    private val notificationPermissionLauncher = registerForActivityResult(RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.SCREENSHOT_MODE) {
            val screenshotState = intent?.getStringExtra(EXTRA_SCREENSHOT_STATE)
            setContent {
                SidekickTheme {
                    if (screenshotState == SCREENSHOT_TASK_DETAIL ||
                        screenshotState == SCREENSHOT_TASK_DETAIL_RESPONSE
                    ) {
                        TaskDetailScreen(
                            state = screenshotTaskDetailState(
                                showResponse = screenshotState == SCREENSHOT_TASK_DETAIL_RESPONSE,
                            ),
                            onApprove = {},
                            onDecline = {},
                            onStop = {},
                            onRetryItem = {},
                            onReply = {},
                            onImageClick = {},
                            onRetryConnection = {},
                            onOpenSettings = {},
                        )
                    } else {
                        CodexHomeScreen(
                            state = screenshotHomeState(screenshotState != SCREENSHOT_HOME_EMPTY),
                            onAskCodex = {
                                startActivity(Intent(this@MainActivity, AssistantActivity::class.java))
                            },
                            onTaskClick = {},
                            onSettingsClick = {},
                            onRetryConnection = {},
                        )
                    }
                }
            }
            return
        }
        handleLaunchIntent(intent)
        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            val navController = rememberNavController()

            LaunchedEffect(requestedTaskId) {
                val taskId = requestedTaskId ?: return@LaunchedEffect
                navController.navigate("$TASK_ROUTE/$taskId") { launchSingleTop = true }
                requestedTaskId = null
            }
            LaunchedEffect(requestedAssistantLaunch) {
                if (requestedAssistantLaunch) {
                    requestedAssistantLaunch = false
                    launchAssistant()
                }
            }

            SidekickTheme {
                NavHost(navController = navController, startDestination = HOME_ROUTE) {
                    composable(HOME_ROUTE) {
                        CodexHomeScreen(
                            state = state.home,
                            onAskCodex = ::launchAssistant,
                            onTaskClick = { navController.navigate("$TASK_ROUTE/$it") },
                            onSettingsClick = { navController.navigate(SETTINGS_ROUTE) },
                            onRetryConnection = viewModel::refresh,
                        )
                    }
                    composable(SETTINGS_ROUTE) {
                        SettingsScreen(
                            baseUrl = state.settings.baseUrl,
                            authToken = state.settings.authToken,
                            voiceInputProviderId = state.settings.voiceInputProviderId,
                            sttAuthToken = state.settings.sttAuthToken,
                            onSaveBaseUrl = viewModel::saveBaseUrl,
                            onSaveAuthToken = viewModel::saveAuthToken,
                            onSaveVoiceInputProvider = viewModel::saveVoiceInputProvider,
                            onSaveSttAuthToken = viewModel::saveSttAuthToken,
                        )
                    }
                    composable(
                        route = "$TASK_ROUTE/{taskId}",
                        arguments = listOf(navArgument("taskId") { type = NavType.StringType }),
                    ) { entry ->
                        val taskId = entry.arguments?.getString("taskId").orEmpty()
                        LaunchedEffect(taskId) {
                            if (taskId.isNotBlank()) viewModel.openTask(taskId)
                        }
                        val detail = state.taskDetails[taskId] ?: TaskDetailUiState(taskId = taskId)
                        TaskDetailScreen(
                            state = detail,
                            onApprove = { viewModel.approve(taskId, it) },
                            onDecline = { viewModel.decline(taskId, it) },
                            onStop = { viewModel.stopTask(taskId) },
                            onRetryItem = { viewModel.retryTask(taskId) },
                            onReply = { launchAssistant(taskId) },
                            onImageClick = { url ->
                                requestedImageUrl = url
                                navController.navigate(IMAGE_ROUTE)
                            },
                            onRetryConnection = { viewModel.openTask(taskId) },
                            onOpenSettings = { navController.navigate(SETTINGS_ROUTE) },
                        )
                    }
                    composable(IMAGE_ROUTE) {
                        ImageViewerScreen(imageUrl = requestedImageUrl.orEmpty())
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!BuildConfig.SCREENSHOT_MODE) viewModel.refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    private fun handleLaunchIntent(intent: Intent?) {
        val taskId =
            intent?.getStringExtra(EXTRA_TASK_ID)
                ?: intent?.getStringExtra(SidekickTileService.EXTRA_TASK_ID)
                ?: intent?.getStringExtra(LEGACY_CONVERSATION_ID)
        if (!taskId.isNullOrBlank()) {
            requestedTaskId = taskId
            return
        }
        if (intent?.action == Intent.ACTION_ASSIST) {
            val mode = intent.getStringExtra(SidekickTileService.EXTRA_INPUT_MODE).orEmpty()
            if (mode.isBlank() || mode == "voice") requestedAssistantLaunch = true
        }
    }

    private fun launchAssistant(replyToTaskId: String? = null) {
        requestNotificationPermissionIfNeeded()
        startActivity(
            Intent(this, AssistantActivity::class.java).apply {
                replyToTaskId?.let { putExtra(AssistantActivity.EXTRA_TASK_ID, it) }
            },
        )
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_SCREENSHOT_STATE = "screenshot_state"
        const val SCREENSHOT_HOME_EMPTY = "home_empty"
        const val SCREENSHOT_TASK_DETAIL = "task_detail"
        const val SCREENSHOT_TASK_DETAIL_RESPONSE = "task_detail_response"
        private const val HOME_ROUTE = "home"
        private const val SETTINGS_ROUTE = "settings"
        private const val TASK_ROUTE = "task"
        private const val IMAGE_ROUTE = "image"
        private const val LEGACY_CONVERSATION_ID = "conversation_id"
    }
}

private fun screenshotTaskDetailState(showResponse: Boolean): TaskDetailUiState =
    TaskDetailUiState(
        taskId = "design-chat",
        title = "Polish the Wear OS layout",
        projectName = "codex-wear",
        status = TaskStatusUi.IDLE,
        isLoading = false,
        timeline =
            if (showResponse) {
                listOf(
                    TimelineItemUi.UserMessage(
                        id = "user",
                        text = "Make the task view more compact.",
                    ),
                    TimelineItemUi.CodexMessage(
                        id = "codex",
                        text = "Done. Replies now sit directly on the surface to preserve space.",
                    ),
                )
            } else {
                listOf(
                    TimelineItemUi.CodexMessage(
                        id = "codex",
                        text = "I tightened the task timeline for the watch.",
                    ),
                    TimelineItemUi.ToolActivity(
                        id = "tools",
                        title = "2 tool calls",
                        summary = "Read layout · Updated timeline",
                    ),
                    TimelineItemUi.UserMessage(
                        id = "user",
                        text = "Make the latest reply easier to reach.",
                    ),
                )
            },
    )

private fun screenshotHomeState(showActivity: Boolean): HomeUiState {
    val now = System.currentTimeMillis()
    return HomeUiState(
        isLoading = false,
        activity = if (showActivity) {
            listOf(
                TaskSummaryUi(
                    id = "attention",
                    title = "Review deployment permission",
                    projectName = "codex-wear",
                    updatedAtEpochMs = now,
                    status = TaskStatusUi.NEEDS_ATTENTION,
                ),
                TaskSummaryUi(
                    id = "working",
                    title = "Polish the Wear OS layout",
                    projectName = "codex-wear",
                    updatedAtEpochMs = now - 60_000L,
                    status = TaskStatusUi.WORKING,
                ),
            )
        } else {
            emptyList()
        },
        today = listOf(
            TaskSummaryUi(
                id = "complete",
                title = "Build Codex monitoring companion",
                projectName = "codex-wear",
                updatedAtEpochMs = now - 3_600_000L,
                status = TaskStatusUi.COMPLETE,
            ),
        ),
    )
}
