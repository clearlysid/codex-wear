package com.sidekick.watch.presentation

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
import com.sidekick.watch.presentation.theme.SidekickTheme
import com.sidekick.watch.tile.SidekickTileService
import com.sidekick.watch.ui.AllTasksScreen
import com.sidekick.watch.ui.CodexHomeScreen
import com.sidekick.watch.ui.ImageViewerScreen
import com.sidekick.watch.ui.SettingsScreen
import com.sidekick.watch.ui.TaskDetailScreen
import com.sidekick.watch.viewmodel.CodexCompanionViewModel
import com.sidekick.watch.viewmodel.TaskDetailUiState

class MainActivity : ComponentActivity() {
    private val viewModel: CodexCompanionViewModel by viewModels { CodexCompanionViewModel.Factory(this) }
    private var requestedTaskId by mutableStateOf<String?>(null)
    private var requestedImageUrl by mutableStateOf<String?>(null)
    private var requestedAssistantLaunch by mutableStateOf(false)

    private val notificationPermissionLauncher = registerForActivityResult(RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                            onAllTasksClick = { navController.navigate(ALL_TASKS_ROUTE) },
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
                    composable(ALL_TASKS_ROUTE) {
                        AllTasksScreen(
                            state = state.allTasks,
                            onTaskClick = { navController.navigate("$TASK_ROUTE/$it") },
                            onRetryConnection = viewModel::refresh,
                            onOpenSettings = { navController.navigate(SETTINGS_ROUTE) },
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
        viewModel.refresh()
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
        private const val HOME_ROUTE = "home"
        private const val SETTINGS_ROUTE = "settings"
        private const val ALL_TASKS_ROUTE = "allTasks"
        private const val TASK_ROUTE = "task"
        private const val IMAGE_ROUTE = "image"
        private const val LEGACY_CONVERSATION_ID = "conversation_id"
    }
}
