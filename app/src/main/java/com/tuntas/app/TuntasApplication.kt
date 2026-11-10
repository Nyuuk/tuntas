package com.tuntas.app

import android.app.Application
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.tuntas.app.domain.DomainScheduleAdapter
import com.tuntas.app.domain.FileTaskRepository
import com.tuntas.app.domain.TaskEngine
import com.tuntas.app.ui.TaskController
import java.io.File
import java.util.concurrent.Executors
import com.tuntas.app.platform.blocker.AndroidBlockerEnvironmentProvider
import com.tuntas.app.platform.blocker.BlockerController
import com.tuntas.app.platform.blocker.DefaultBlockerController
import com.tuntas.app.platform.blocker.SharedPreferencesBlockerStore
import com.tuntas.app.platform.blocker.TuntasAccessibilityService
import com.tuntas.app.platform.scheduling.AndroidPlatformAlarmGateway
import com.tuntas.app.platform.scheduling.DefaultScheduleCoordinator
import com.tuntas.app.platform.scheduling.ReconciliationReason
import com.tuntas.app.platform.scheduling.ScheduleCoordinator
import com.tuntas.app.platform.scheduling.SharedPreferencesTriggerStore
import com.tuntas.app.platform.scheduling.SystemClockProvider

/**
 * Wires the durable domain to platform effects and a serialized off-main command executor.
 */
class TuntasApplication : Application() {

    lateinit var blockerController: BlockerController
        private set

    lateinit var scheduleCoordinator: ScheduleCoordinator
        private set

    lateinit var taskController: TaskController
        private set
    private val domainExecutor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "tuntas-domain") }
    private lateinit var domainAdapter: DomainScheduleAdapter
    private var accessibilitySettingsObserver: ContentObserver? = null

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val blockerStore = SharedPreferencesBlockerStore(prefs)
        val environmentProvider = AndroidBlockerEnvironmentProvider(this)

        blockerController = DefaultBlockerController(
            ownPackageName = packageName,
            store = blockerStore,
            environmentProvider = environmentProvider,
            onLockActivatedEnforcement = { state ->
                if (state.lockActive && state.isServiceReady) {
                    TuntasAccessibilityService.activeInstance?.enforceIfCurrentlyInBlockedApp()
                }
            }
        )

        val triggerStore = SharedPreferencesTriggerStore(prefs)
        val alarmGateway = AndroidPlatformAlarmGateway(this)
        val clockProvider = SystemClockProvider()

        scheduleCoordinator = DefaultScheduleCoordinator(
            store = triggerStore,
            alarmGateway = alarmGateway,
            clockProvider = clockProvider,
            onTriggerFired = { domainAdapter.onAlarmFired() }
        )
        val engine = TaskEngine(FileTaskRepository(File(filesDir, "tasks-v1.bin")), clockProvider)
        domainAdapter = DomainScheduleAdapter(engine, scheduleCoordinator, blockerController)
        taskController = TaskController(engine, domainAdapter, scheduleCoordinator, domainExecutor)
        // Provider deliberately remains unset; domain reconciles all due rows before scheduling.
        registerAccessibilitySettingsObserver()
        taskController.refresh(ReconciliationReason.APP_STARTUP)
    }

    /**
     * Best-effort in-process observer for `Settings.Secure` accessibility changes while the app
     * process is alive. Not a guaranteed or instant background revocation mechanism; live state
     * also requires [TuntasAccessibilityService] connection callbacks (`onServiceConnected`/`onUnbind`)
     * and foreground refreshes (`MainActivity.onResume`).
     */
    private fun registerAccessibilitySettingsObserver() {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                taskController.refresh(ReconciliationReason.MANUAL_DIAGNOSTIC)
            }
        }
        runCatching {
            contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                false,
                observer
            )
            contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED),
                false,
                observer
            )
            accessibilitySettingsObserver = observer
        }.onFailure { error ->
            Log.w(TAG, "Unable to register accessibility ContentObserver", error)
        }
    }

    companion object {
        private const val TAG = "TuntasApplication"
        const val PREFS_NAME = "tuntas_baseline_prefs"

        fun from(context: Context): TuntasApplication =
            context.applicationContext as TuntasApplication
    }
}
