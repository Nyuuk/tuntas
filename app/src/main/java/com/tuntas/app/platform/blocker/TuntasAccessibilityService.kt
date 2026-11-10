package com.tuntas.app.platform.blocker

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.telecom.TelecomManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import com.tuntas.app.MainActivity
import com.tuntas.app.TuntasApplication

/**
 * Android platform implementation of [BlockerEnvironmentProvider].
 *
 * Inspects `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` for user consent and resolves
 * dynamic system-safe packages (default dialer, enabled IME keyboards) so neither emergency
 * dialing nor keyboard entry inside Tuntas is disrupted.
 */
class AndroidBlockerEnvironmentProvider(
    private val context: Context
) : BlockerEnvironmentProvider {

    override fun isAccessibilityServiceEnabled(): Boolean {
        val accessibilityEnabled = runCatching {
            Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0
            ) == 1
        }.getOrDefault(false)
        if (!accessibilityEnabled) return false

        val enabledServicesSetting = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        }.getOrNull() ?: return false

        val expectedComponent = ComponentName(context, TuntasAccessibilityService::class.java)
        return isServiceComponentPresent(
            enabledServicesSetting = enabledServicesSetting,
            expectedPackageName = expectedComponent.packageName,
            expectedClassName = expectedComponent.className
        )
    }

    override fun resolveDynamicSystemSafePackages(): Set<String> {
        val dynamicPackages = mutableSetOf<String>()

        // 1. Default dialer package from TelecomManager
        runCatching {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            telecomManager?.defaultDialerPackage?.takeIf { it.isNotBlank() }?.let {
                dynamicPackages.add(it)
            }
        }

        // 2. Packages resolving Intent.ACTION_DIAL (dialer / emergency phone surfaces)
        runCatching {
            val dialIntent = Intent(Intent.ACTION_DIAL)
            val resolveInfos = context.packageManager.queryIntentActivities(dialIntent, 0)
            for (info in resolveInfos) {
                info.activityInfo?.packageName?.takeIf { it.isNotBlank() }?.let {
                    dynamicPackages.add(it)
                }
            }
        }

        // 3. Enabled Input Method (IME) packages so typing in Tuntas or Dialer never triggers a block
        runCatching {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.enabledInputMethodList?.forEach { imi ->
                imi.packageName?.takeIf { it.isNotBlank() }?.let {
                    dynamicPackages.add(it)
                }
            }
        }

        return dynamicPackages
    }

    override fun nowEpochMillis(): Long = System.currentTimeMillis()

    companion object {
        /**
         * Pure helper for parsing Android's colon-separated `enabled_accessibility_services` string.
         */
        fun isServiceComponentPresent(
            enabledServicesSetting: String?,
            expectedPackageName: String,
            expectedClassName: String
        ): Boolean {
            if (enabledServicesSetting.isNullOrBlank()) return false
            val shortClassName = if (expectedClassName.startsWith("$expectedPackageName.")) {
                expectedClassName.removePrefix(expectedPackageName)
            } else {
                expectedClassName
            }
            val expectedFull = "$expectedPackageName/$expectedClassName"
            val expectedShort = "$expectedPackageName/$shortClassName"

            return enabledServicesSetting
                .split(':')
                .map { it.trim() }
                .any { entry ->
                    entry.equals(expectedFull, ignoreCase = false) ||
                        entry.equals(expectedShort, ignoreCase = false)
                }
        }
    }
}

/**
 * User-consented, best-effort AccessibilityService blocker.
 *
 * Privacy & scope constraints:
 * - Configured with `canRetrieveWindowContent="false"` and `typeWindowStateChanged` only.
 * - Reads ONLY `event.packageName` and `event.className` (never inspects view hierarchies,
 *   text, passwords, or notifications).
 * - Preserves access to own app (`com.tuntas.app`), emergency calling / dialer surfaces,
 *   essential system UI, and user-configured emergency allowlist packages.
 * - Uses AccessibilityService context exemption to launch [MainActivity] when a non-allowlisted
 *   foreground app is opened during an active lock.
 */
class TuntasAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
        val app = TuntasApplication.from(this)
        app.taskController.platformEvent {
            app.blockerController.onServiceConnectionChanged(connected = true)
            enforceIfCurrentlyInBlockedApp()
        }
        app.taskController.refresh(com.tuntas.app.platform.scheduling.ReconciliationReason.MANUAL_DIAGNOSTIC)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString()
        val className = event.className?.toString()

        // Copy package/class before the framework recycles the AccessibilityEvent.
        val app = TuntasApplication.from(this)
        app.taskController.platformEvent {
            val controller = app.blockerController
            val decision = controller.evaluateWindowEvent(packageName, className)
            if (decision.shouldBlock && !packageName.isNullOrBlank()) {
                controller.recordInterception(packageName, className, decision.reason)
                redirectToTaskApp(interceptedPackage = packageName)
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "TuntasAccessibilityService interrupted.")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (activeInstance === this) {
            activeInstance = null
        }
        runCatching {
            val app = TuntasApplication.from(this)
            app.taskController.platformEvent { app.blockerController.onServiceConnectionChanged(connected = false) }
        }
        Log.i(TAG, "TuntasAccessibilityService unbound; blocker status updated.")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (activeInstance === this) {
            activeInstance = null
        }
        runCatching {
            val app = TuntasApplication.from(this)
            app.taskController.platformEvent { app.blockerController.onServiceConnectionChanged(connected = false) }
        }
        super.onDestroy()
    }

    /**
     * When lock activates (e.g., via scheduled alarm while another app is in foreground),
     * immediately redirects to [MainActivity] if the user is not in an allowlisted app.
     */
    fun enforceIfCurrentlyInBlockedApp() {
        val app = TuntasApplication.from(this)
        val controller = app.blockerController
        val state = controller.refreshPermissionState()
        if (!state.lockActive || !state.isServiceReady) return

        val currentPkg = state.lastObservedForegroundPackage
        if (currentPkg == null) {
            // Bring Tuntas lock screen to foreground so user sees lock activation.
            redirectToTaskApp(interceptedPackage = "lock_activation")
            return
        }

        val currentClass = (controller as? DefaultBlockerController)?.getLastObservedForegroundClassName()
        val decision = controller.evaluateWindowEvent(currentPkg, currentClass)
        if (decision.shouldBlock) {
            controller.recordInterception(
                packageName = currentPkg,
                className = currentClass,
                reason = decision.reason
            )
            redirectToTaskApp(interceptedPackage = currentPkg)
        }
    }

    private fun redirectToTaskApp(interceptedPackage: String) {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
            putExtra(MainActivity.EXTRA_INTERCEPTED_PACKAGE, interceptedPackage)
        }
        runCatching {
            startActivity(launchIntent)
        }.onFailure { error ->
            Log.e(TAG, "Failed to launch MainActivity from AccessibilityService", error)
        }
    }

    companion object {
        private const val TAG = "TuntasBlockerService"

        @Volatile
        var activeInstance: TuntasAccessibilityService? = null
            private set
    }
}
