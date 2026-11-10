package com.tuntas.app

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tuntas.app.platform.scheduling.ReconciliationReason
import androidx.compose.runtime.collectAsState
import android.widget.Toast
import com.tuntas.app.ui.TuntasScreen
import com.tuntas.app.ui.AppOption

/**
 * Integrated Compose entry point and return target for intercepted apps.
 */
class MainActivity : ComponentActivity() {

    private var interceptionEvent by mutableIntStateOf(0)
    private var latestInterceptedBannerPackage by mutableStateOf<String?>(null)
    private var installedLauncherApps by mutableStateOf<List<AppOption>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        extractInterceptedPackage(intent)
        installedLauncherApps = queryLaunchablePackages()

        val app = TuntasApplication.from(this)

        setContent {
            com.tuntas.app.ui.TuntasTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    val blocker by app.blockerController.state.collectAsState()
                    val scheduling by app.scheduleCoordinator.state.collectAsState()
                    TuntasScreen(
                        controller = app.taskController,
                        blocker = blocker,
                        scheduling = scheduling,
                        apps = installedLauncherApps,
                        interceptedPackage = latestInterceptedBannerPackage,
                        interceptionEvent = interceptionEvent,
                        onAccessibility = ::openAccessibilitySettings,
                        onExactAlarm = ::openExactAlarmSettings,
                        onDialer = ::openEmergencyDialer,
                        onOpenApp = { pkg ->
                            val launch = packageManager.getLaunchIntentForPackage(pkg)
                            if (launch == null) showLaunchError() else launchSafely(launch)
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractInterceptedPackage(intent)
    }

    override fun onResume() {
        super.onResume()
        val app = TuntasApplication.from(this)
        app.taskController.refresh(ReconciliationReason.MANUAL_DIAGNOSTIC)
        installedLauncherApps = queryLaunchablePackages()
    }

    private fun extractInterceptedPackage(intent: Intent?) {
        val pkg = intent?.getStringExtra(EXTRA_INTERCEPTED_PACKAGE)
        if (!pkg.isNullOrBlank()) {
            latestInterceptedBannerPackage = pkg
            interceptionEvent++
        }
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        launchSafely(intent)
    }

    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val exactIntent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.parse("package:$packageName")
            }
            val launched = runCatching { startActivity(exactIntent) }.isSuccess
            if (launched) return
        }
        val fallbackIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        }
        launchSafely(fallbackIntent)
    }

    private fun openEmergencyDialer() {
        val dialIntent = Intent(Intent.ACTION_DIAL).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        launchSafely(dialIntent)
    }

    private fun launchSafely(intent: Intent) {
        runCatching { startActivity(intent) }.onFailure { showLaunchError() }
    }

    private fun showLaunchError() {
        Toast.makeText(this, "Aplikasi tidak dapat dibuka. Gunakan Setelan atau telepon bawaan perangkat.", Toast.LENGTH_LONG).show()
    }

    private fun queryLaunchablePackages(): List<AppOption> {
        return runCatching {
            val mainIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val pm = packageManager
            val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(
                    mainIntent,
                    PackageManager.ResolveInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(mainIntent, 0)
            }
            resolveInfos
                .mapNotNull { info ->
                    val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                    if (pkg == packageName) return@mapNotNull null
                    val label = runCatching { info.loadLabel(pm).toString() }.getOrDefault(pkg)
                    AppOption(packageName = pkg, label = label)
                }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
        }.getOrDefault(emptyList())
    }

    companion object {
        const val EXTRA_INTERCEPTED_PACKAGE = "extra_intercepted_package"
    }
}
