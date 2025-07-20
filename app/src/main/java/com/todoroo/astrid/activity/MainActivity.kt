/*
 * Copyright (c) 2012 Todoroo Inc
 *
 * See the file "LICENSE" for the full license governing this code.
 */
package com.todoroo.astrid.activity

import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldRole
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat.getParcelableExtra
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.todoroo.astrid.adapter.SubheaderClickHandler
import com.todoroo.astrid.dao.TaskDao
import com.todoroo.astrid.gtasks.auth.GtasksLoginActivity
import com.todoroo.astrid.service.TaskCreator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.tasks.BuildConfig
import org.tasks.R
import org.tasks.analytics.Firebase
import org.tasks.auth.SignInActivity
import org.tasks.billing.Inventory
import org.tasks.caldav.CaldavAccountSettingsActivity
import org.tasks.compose.AddAccountDestination
import org.tasks.compose.HomeDestination
import org.tasks.compose.accounts.AddAccountScreen
import org.tasks.compose.accounts.AddAccountViewModel
import org.tasks.compose.home.HomeScreen
import org.tasks.data.dao.AlarmDao
import org.tasks.data.dao.CaldavDao
import org.tasks.data.dao.LocationDao
import org.tasks.data.dao.TagDataDao
import org.tasks.data.entity.Task
import org.tasks.dialogs.ImportTasksDialog
import org.tasks.dialogs.NewFilterDialog
import org.tasks.etebase.EtebaseAccountSettingsActivity
import org.tasks.extensions.Context.nightMode
import org.tasks.extensions.Context.toast
import org.tasks.extensions.broughtToFront
import org.tasks.extensions.flagsToString
import org.tasks.extensions.isFromHistory
import org.tasks.files.FileHelper
import org.tasks.filters.Filter
import org.tasks.jobs.WorkManager
import org.tasks.preferences.DefaultFilterProvider
import org.tasks.preferences.Preferences
import org.tasks.preferences.fragments.FRAG_TAG_IMPORT_TASKS
import org.tasks.sync.AddAccountDialog
import org.tasks.sync.SyncAdapters
import org.tasks.todoist.TodoistAccountSettingsActivity
import org.tasks.sync.microsoft.MicrosoftSignInViewModel
import org.tasks.themes.ColorProvider
import org.tasks.themes.TasksTheme
import org.tasks.themes.Theme
import timber.log.Timber
import javax.inject.Inject

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var defaultFilterProvider: DefaultFilterProvider
    @Inject lateinit var theme: Theme
    @Inject lateinit var taskDao: TaskDao
    @Inject lateinit var taskCreator: TaskCreator
    @Inject lateinit var inventory: Inventory
    @Inject lateinit var colorProvider: ColorProvider
    @Inject lateinit var locationDao: LocationDao
    @Inject lateinit var tagDataDao: TagDataDao
    @Inject lateinit var alarmDao: AlarmDao
    @Inject lateinit var firebase: Firebase
    @Inject lateinit var caldavDao: CaldavDao
    @Inject lateinit var syncAdapters: SyncAdapters
    @Inject lateinit var workManager: WorkManager

    private val viewModel: MainActivityViewModel by viewModels()
    private var currentNightMode = 0
    private var currentPro = false
    private var actionMode: ActionMode? = null
    private var isReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        theme.themeBase.set(this)
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition { !isReady }
        currentNightMode = nightMode
        currentPro = inventory.hasPro

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                lightScrim = Color.TRANSPARENT,
                darkScrim = Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.auto(
                lightScrim = Color.TRANSPARENT,
                darkScrim = Color.TRANSPARENT
            )
        )

        setContent {
            TasksTheme(
                theme = theme.themeBase.index,
                primary = theme.themeColor.primaryColor,
            ) {
                val navController = rememberNavController()
                val hasAccount = viewModel
                    .accountExists
                    .collectAsStateWithLifecycle(null)
                    .value
                LaunchedEffect(hasAccount) {
                    Timber.d("hasAccount=$hasAccount")
                    if (hasAccount == false) {
                        navController.navigate(AddAccountDestination(showImport = true))
                    }
                    isReady = hasAccount != null
                }
                NavHost(
                    navController = navController,
                    startDestination = HomeDestination,
                ) {
                    composable<AddAccountDestination> {
                        val route = it.toRoute<AddAccountDestination>()
                        LaunchedEffect(hasAccount) {
                            if (route.showImport && hasAccount == true) {
                                navController.popBackStack()
                            }
                        }
                        val addAccountViewModel: AddAccountViewModel = hiltViewModel()
                        val microsoftVM: MicrosoftSignInViewModel = hiltViewModel()
                        val syncLauncher =
                            rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                                if (result.resultCode == RESULT_OK) {
                                    syncAdapters.sync(true)
                                    workManager.updateBackgroundSync()
                                } else {
                                    result.data
                                        ?.getStringExtra(GtasksLoginActivity.EXTRA_ERROR)
                                        ?.let { toast(it) }
                                }
                            }
                        val importBackupLauncher =
                            rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                                if (result.resultCode == RESULT_OK) {
                                    val uri = result.data?.data ?: return@rememberLauncherForActivityResult
                                    ImportTasksDialog.newImportTasksDialog(uri)
                                        .show(supportFragmentManager, FRAG_TAG_IMPORT_TASKS)
                                }
                            }
                        AddAccountScreen(
                            gettingStarted = route.showImport,
                            hasTasksAccount = inventory.hasTasksAccount,
                            hasPro = inventory.hasPro,
                            onBack = { navController.popBackStack() },
                            signIn = { platform ->
                                firebase.logEvent(R.string.event_onboarding_sync, R.string.param_selection to platform)
                                when (platform) {
                                    AddAccountDialog.Platform.TASKS_ORG ->
                                        syncLauncher.launch(
                                            Intent(this@MainActivity, SignInActivity::class.java)
                                        )

                                    AddAccountDialog.Platform.GOOGLE_TASKS ->
                                        syncLauncher.launch(
                                            Intent(this@MainActivity, GtasksLoginActivity::class.java)
                                        )

                                    AddAccountDialog.Platform.MICROSOFT ->
                                        microsoftVM.signIn(this@MainActivity)

                                    AddAccountDialog.Platform.CALDAV ->
                                        syncLauncher.launch(
                                            Intent(this@MainActivity, CaldavAccountSettingsActivity::class.java)
                                        )

                                    AddAccountDialog.Platform.ETESYNC ->
                                        syncLauncher.launch(
                                            Intent(this@MainActivity, EtebaseAccountSettingsActivity::class.java)
                                        )

                                    AddAccountDialog.Platform.LOCAL ->
                                        addAccountViewModel.createLocalAccount()

                                    AddAccountDialog.Platform.TODOIST ->
                                        syncLauncher.launch(
                                            Intent(this@MainActivity, TodoistAccountSettingsActivity::class.java)
                                        )

                                    else -> throw IllegalArgumentException()
                                }
                            },
                            openUrl = { platform ->
                                firebase.logEvent(R.string.event_onboarding_sync, R.string.param_selection to platform.name)
                                addAccountViewModel.openUrl(this@MainActivity, platform)
                            },
                            onImportBackup = {
                                firebase.logEvent(R.string.event_onboarding_sync, R.string.param_selection to "import_backup")
                                importBackupLauncher.launch(
                                    FileHelper.newFilePickerIntent(this@MainActivity, preferences.backupDirectory),
                                )
                            }
                        )
                    }
                    composable<HomeDestination> {
                        if (hasAccount != true) {
                            return@composable
                        }
                        val scope = rememberCoroutineScope()
                        val state = viewModel.state.collectAsStateWithLifecycle().value
                        val drawerState = rememberDrawerState(
                            initialValue = DrawerValue.Closed,
                            confirmStateChange = {
                                viewModel.setDrawerState(it == DrawerValue.Open)
                                true
                            }
                        )
                        val navigator = rememberListDetailPaneScaffoldNavigator(
                            calculatePaneScaffoldDirective(
                                windowAdaptiveInfo = currentWindowAdaptiveInfo(),
                            ).copy(
                                horizontalPartitionSpacerSize = 0.dp,
                                verticalPartitionSpacerSize = 0.dp,
                            )
                        )
                        val keyboard = LocalSoftwareKeyboardController.current
                        LaunchedEffect(state.task) {
                            val pane = if (state.task == null) {
                                ThreePaneScaffoldRole.Secondary
                            } else {
                                ThreePaneScaffoldRole.Primary
                            }
                            Timber.d("Navigating to $pane")
                            navigator.navigateTo(pane = pane)
                        }

                        val isDetailVisible =
                            navigator.scaffoldValue[ListDetailPaneScaffoldRole.Detail] == PaneAdaptedValue.Expanded
                        BackHandler(enabled = state.task == null) {
                            Timber.d("onBackPressed")
                            if (isDetailVisible && navigator.canNavigateBack()) {
                                scope.launch {
                                    navigator.navigateBack()
                                }
                            } else {
                                finish()
                                if (!preferences.getBoolean(R.string.p_open_last_viewed_list, true)) {
                                    runBlocking {
                                        viewModel.resetFilter()
                                    }
                                }
                            }
                        }
                        LaunchedEffect(state.filter, state.task) {
                            actionMode?.finish()
                            actionMode = null
                            if (state.task == null) {
                                keyboard?.hide()
                            }
                            drawerState.close()
                        }
                        HomeScreen(
                            state = state,
                            drawerState = drawerState,
                            navigator = navigator,
                            showNewFilterDialog = {
                                NewFilterDialog.newFilterDialog().show(
                                    supportFragmentManager,
                                    SubheaderClickHandler.FRAG_TAG_NEW_FILTER
                                )
                            },
                        )
                    }
                }
            }
        }
        logIntent("onCreate")
        handleIntent()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        logIntent("onNewIntent")
        handleIntent()
    }

    private suspend fun getTaskToLoad(filter: Filter?): Task? = when {
        intent.isFromHistory -> null
        intent.hasExtra(CREATE_TASK) -> {
            val source = intent.getStringExtra(CREATE_SOURCE)
            firebase.addTask(source ?: "unknown")
            intent.removeExtra(CREATE_TASK)
            intent.removeExtra(CREATE_SOURCE)
            taskCreator.createWithValues(filter, "")
        }

        intent.hasExtra(OPEN_TASK) -> {
            val task = getParcelableExtra(intent, OPEN_TASK, Task::class.java)
            intent.removeExtra(OPEN_TASK)
            task
        }

        else -> null
    }

    private fun logIntent(caller: String) {
        if (BuildConfig.DEBUG) {
            Timber.d("""
                |$caller
                |**********
                |broughtToFront: ${intent.broughtToFront}
                |isFromHistory: ${intent.isFromHistory}
                |flags: ${intent.flagsToString}
                ${intent?.extras?.keySet()?.joinToString("\n") { "|$it: ${intent.extras?.get(it)}" } ?: "|NO EXTRAS"}
                |**********""".trimMargin()
            )
        }
    }

    private fun handleIntent() {
        lifecycleScope.launch {
            val filter = intent.getFilter
                ?: intent.getFilterString?.let { defaultFilterProvider.getFilterFromPreference(it) }
                ?: viewModel.state.value.filter
            val task = getTaskToLoad(filter)
            viewModel.setFilter(filter = filter, task = task)
        }
    }

    override fun onResume() {
        super.onResume()
        Timber.d("onResume")
        if (currentNightMode != nightMode || currentPro != inventory.hasPro) {
            restartActivity()
            return
        }
    }

    override fun onPause() {
        super.onPause()
        Timber.d("onPause")
    }

    override fun onSupportActionModeStarted(mode: ActionMode) {
        super.onSupportActionModeStarted(mode)
        actionMode = mode
    }

    fun restartActivity() {
        finish()
        startActivity(
            Intent(this, MainActivity::class.java),
            ActivityOptions.makeCustomAnimation(
                this@MainActivity,
                android.R.anim.fade_in, android.R.anim.fade_out
            ).toBundle()
        )
    }

    companion object {
        /** For indicating the new list screen should be launched at fragment setup time  */
        const val OPEN_FILTER = "open_filter" // $NON-NLS-1$
        const val LOAD_FILTER = "load_filter"
        const val CREATE_TASK = "open_task" // $NON-NLS-1$
        const val CREATE_SOURCE = "create_source"
        const val OPEN_TASK = "open_new_task" // $NON-NLS-1$
        const val REMOVE_TASK = "remove_task"
        const val FINISH_AFFINITY = "finish_affinity"

        val Intent.getFilter: Filter?
            get() = if (isFromHistory) {
                null
            } else {
                getParcelableExtra(this, OPEN_FILTER, Filter::class.java)?.let {
                    removeExtra(OPEN_FILTER)
                    it
                }
            }

        val Intent.getFilterString: String?
            get() = if (isFromHistory) {
                null
            } else {
                getStringExtra(LOAD_FILTER)?.let {
                    removeExtra(LOAD_FILTER)
                    it
                }
            }

        val Intent.removeTask: Boolean
            get() = try {
                getBooleanExtra(REMOVE_TASK, false) && !isFromHistory && !broughtToFront
            } finally {
                removeExtra(REMOVE_TASK)
            }

        val Intent.finishAffinity: Boolean
            get() = try {
                getBooleanExtra(FINISH_AFFINITY, false) && !isFromHistory && !broughtToFront
            } finally {
                removeExtra(FINISH_AFFINITY)
            }
    }
}
