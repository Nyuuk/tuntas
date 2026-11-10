package com.tuntas.app.platform.blocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockerPolicyAndControllerTest {

    private val ownPackage = "com.tuntas.app"

    private class FakeBlockerEnvironment(
        var accessibilityEnabled: Boolean = true,
        var dynamicSafePackages: Set<String> = setOf("com.oem.customdialer", "com.oem.keyboard"),
        var currentMillis: Long = 1_700_000_000_000L
    ) : BlockerEnvironmentProvider {
        override fun isAccessibilityServiceEnabled(): Boolean = accessibilityEnabled
        override fun resolveDynamicSystemSafePackages(): Set<String> = dynamicSafePackages
        override fun nowEpochMillis(): Long = currentMillis
    }

    @Test
    fun repeatedReconciliationDoesNotRelaunchFromStaleForeground() {
        val env = FakeBlockerEnvironment()
        var launches = 0
        val controller = DefaultBlockerController(ownPackage, InMemoryBlockerStore(), env) { launches++ }
        controller.onServiceConnectionChanged(true)
        controller.activateLock("Pending tasks")
        controller.activateLock("Pending tasks")
        assertEquals(1, launches)
        controller.deactivateLock()
        controller.activateLock("Pending tasks")
        assertEquals(2, launches)
    }

    @Test
    fun ownAppPackage_isAlwaysAllowedWhenLockActive() {
        val decision = BlockerPolicyEvaluator.evaluate(
            foregroundPackage = ownPackage,
            eventClassName = "com.tuntas.app.MainActivity",
            ownPackageName = ownPackage,
            lockActive = true,
            accessibilityServiceEnabled = true,
            userAllowlist = emptySet()
        )
        assertEquals(InterceptionAction.ALLOW, decision.action)
        assertEquals(InterceptionReason.OWN_APP, decision.reason)
        assertFalse(decision.shouldBlock)
    }

    @Test
    fun emergencyAndDialerPackages_areAlwaysAllowedEvenWithEmptyUserAllowlist() {
        val emergencyPackages = listOf(
            "com.android.emergency",
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.incallui",
            "com.android.dialer",
            "com.google.android.dialer",
            "com.samsung.android.incallui"
        )

        for (pkg in emergencyPackages) {
            val decision = BlockerPolicyEvaluator.evaluate(
                foregroundPackage = pkg,
                eventClassName = null,
                ownPackageName = ownPackage,
                lockActive = true,
                accessibilityServiceEnabled = true,
                userAllowlist = emptySet()
            )
            assertEquals("Expected $pkg to be allowed", InterceptionAction.ALLOW, decision.action)
            assertEquals(InterceptionReason.EMERGENCY_OR_DIALER_SURFACE, decision.reason)
        }
    }

    @Test
    fun emergencyClassNameHeuristic_allowsVendorEmergencyDialerActivities() {
        val decision = BlockerPolicyEvaluator.evaluate(
            foregroundPackage = "com.vendor.telephony.ui",
            eventClassName = "com.vendor.telephony.ui.EmergencyDialerActivity",
            ownPackageName = ownPackage,
            lockActive = true,
            accessibilityServiceEnabled = true,
            userAllowlist = emptySet()
        )
        assertEquals(InterceptionAction.ALLOW, decision.action)
        assertEquals(InterceptionReason.EMERGENCY_OR_DIALER_SURFACE, decision.reason)
    }

    @Test
    fun essentialSystemUiAndPermissionController_areAllowedDuringActiveLock() {
        val systemPackages = listOf(
            "com.android.systemui",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.google.android.inputmethod.latin"
        )
        for (pkg in systemPackages) {
            val decision = BlockerPolicyEvaluator.evaluate(
                foregroundPackage = pkg,
                eventClassName = null,
                ownPackageName = ownPackage,
                lockActive = true,
                accessibilityServiceEnabled = true,
                userAllowlist = emptySet()
            )
            assertEquals("Expected $pkg to be allowed", InterceptionAction.ALLOW, decision.action)
            assertEquals(InterceptionReason.ESSENTIAL_SYSTEM_SURFACE, decision.reason)
        }
    }

    @Test
    fun userAllowlistedPackages_areAllowed_whileNonAllowlistedAppsAreBlocked() {
        val allowlist = setOf("com.whatsapp", "com.android.calculator2")

        val allowedDecision = BlockerPolicyEvaluator.evaluate(
            foregroundPackage = "com.android.calculator2",
            eventClassName = "com.android.calculator2.Calculator",
            ownPackageName = ownPackage,
            lockActive = true,
            accessibilityServiceEnabled = true,
            userAllowlist = allowlist
        )
        assertEquals(InterceptionAction.ALLOW, allowedDecision.action)
        assertEquals(InterceptionReason.USER_ALLOWLISTED_PACKAGE, allowedDecision.reason)

        val blockedDecision = BlockerPolicyEvaluator.evaluate(
            foregroundPackage = "com.google.android.youtube",
            eventClassName = "com.google.android.apps.youtube.app.WatchWhileActivity",
            ownPackageName = ownPackage,
            lockActive = true,
            accessibilityServiceEnabled = true,
            userAllowlist = allowlist
        )
        assertEquals(InterceptionAction.BLOCK_AND_RETURN_TO_APP, blockedDecision.action)
        assertEquals(InterceptionReason.NON_ALLOWLISTED_FOREGROUND_APP, blockedDecision.reason)
        assertTrue(blockedDecision.shouldBlock)
    }

    @Test
    fun lockInactive_allowsAllForegroundPackages() {
        val decision = BlockerPolicyEvaluator.evaluate(
            foregroundPackage = "com.google.android.youtube",
            eventClassName = null,
            ownPackageName = ownPackage,
            lockActive = false,
            accessibilityServiceEnabled = true,
            userAllowlist = emptySet()
        )
        assertEquals(InterceptionAction.ALLOW, decision.action)
        assertEquals(InterceptionReason.LOCK_INACTIVE, decision.reason)
    }

    @Test
    fun permissionDeniedOrRevokedOrServiceDisconnected_reportsDegradedStatus_andNeverClaimsActiveOrReady() {
        val store = InMemoryBlockerStore()
        val env = FakeBlockerEnvironment(accessibilityEnabled = false)
        val controller = DefaultBlockerController(
            ownPackageName = ownPackage,
            store = store,
            environmentProvider = env
        )

        // Initially inactive and permission missing
        assertEquals(
            BlockerEffectiveStatus.INACTIVE_PERMISSION_MISSING,
            controller.state.value.effectiveStatus
        )
        assertFalse(controller.state.value.isServiceReady)
        assertFalse(controller.state.value.isDegraded)

        // Setting enabled in Settings.Secure, but service is NOT connected/bound yet ->
        // MUST NOT claim INACTIVE_READY
        env.accessibilityEnabled = true
        controller.refreshPermissionState()
        assertTrue(controller.state.value.accessibilityServiceEnabled)
        assertFalse(controller.state.value.serviceConnectedInProcess)
        assertFalse(controller.state.value.isServiceReady)
        assertEquals(
            BlockerEffectiveStatus.INACTIVE_PERMISSION_MISSING,
            controller.state.value.effectiveStatus
        )

        // Service connects while lock is inactive -> now INACTIVE_READY
        controller.onServiceConnectionChanged(connected = true)
        assertTrue(controller.state.value.serviceConnectedInProcess)
        assertTrue(controller.state.value.isServiceReady)
        assertEquals(
            BlockerEffectiveStatus.INACTIVE_READY,
            controller.state.value.effectiveStatus
        )

        // Service disconnects while lock is inactive -> drops back to INACTIVE_PERMISSION_MISSING
        controller.onServiceConnectionChanged(connected = false)
        assertEquals(
            BlockerEffectiveStatus.INACTIVE_PERMISSION_MISSING,
            controller.state.value.effectiveStatus
        )

        // Activate lock while setting is enabled but service is disconnected ->
        // MUST report DEGRADED_PERMISSION_MISSING, never ACTIVE_PROTECTED
        controller.activateLock("Due Task Lock")
        assertTrue(controller.state.value.lockActive)
        assertTrue(controller.state.value.accessibilityServiceEnabled)
        assertFalse(controller.state.value.serviceConnectedInProcess)
        assertEquals(
            BlockerEffectiveStatus.DEGRADED_PERMISSION_MISSING,
            controller.state.value.effectiveStatus
        )
        assertTrue(controller.state.value.isDegraded)

        // Window event evaluation while service is disconnected cannot claim active block
        val disconnectedDecision = controller.evaluateWindowEvent("com.android.chrome", null)
        assertEquals(InterceptionAction.ALLOW, disconnectedDecision.action)
        assertEquals(InterceptionReason.DEGRADED_ACCESSIBILITY_DISABLED, disconnectedDecision.reason)

        // Service binds/connects while consent is enabled -> transitions to ACTIVE_PROTECTED
        controller.onServiceConnectionChanged(connected = true)
        assertEquals(
            BlockerEffectiveStatus.ACTIVE_PROTECTED,
            controller.state.value.effectiveStatus
        )
        assertFalse(controller.state.value.isDegraded)

        // Window event evaluation while connected + consented blocks non-allowlisted app
        val connectedDecision = controller.evaluateWindowEvent("com.android.chrome", null)
        assertEquals(InterceptionAction.BLOCK_AND_RETURN_TO_APP, connectedDecision.action)
        assertEquals(InterceptionReason.NON_ALLOWLISTED_FOREGROUND_APP, connectedDecision.reason)

        // User revokes AccessibilityService in system settings -> transitions back to DEGRADED_PERMISSION_MISSING
        env.accessibilityEnabled = false
        controller.refreshPermissionState()
        assertFalse(controller.state.value.serviceConnectedInProcess)
        assertEquals(
            BlockerEffectiveStatus.DEGRADED_PERMISSION_MISSING,
            controller.state.value.effectiveStatus
        )
        assertTrue(controller.state.value.isDegraded)
    }

    @Test
    fun blockerStateSnapshot_requiresBothConsentAndConnectionForReadyOrActiveProtected() {
        val unlockedDisconnected = BlockerState(
            ownPackageName = ownPackage,
            lockActive = false,
            accessibilityServiceEnabled = true,
            serviceConnectedInProcess = false
        )
        assertFalse(unlockedDisconnected.isServiceReady)
        assertEquals(
            BlockerEffectiveStatus.INACTIVE_PERMISSION_MISSING,
            unlockedDisconnected.effectiveStatus
        )

        val lockedDisconnected = BlockerState(
            ownPackageName = ownPackage,
            lockActive = true,
            accessibilityServiceEnabled = true,
            serviceConnectedInProcess = false
        )
        assertFalse(lockedDisconnected.isServiceReady)
        assertEquals(
            BlockerEffectiveStatus.DEGRADED_PERMISSION_MISSING,
            lockedDisconnected.effectiveStatus
        )
        assertTrue(lockedDisconnected.isDegraded)

        val lockedConnectedNoConsent = BlockerState(
            ownPackageName = ownPackage,
            lockActive = true,
            accessibilityServiceEnabled = false,
            serviceConnectedInProcess = true
        )
        assertFalse(lockedConnectedNoConsent.isServiceReady)
        assertEquals(
            BlockerEffectiveStatus.DEGRADED_PERMISSION_MISSING,
            lockedConnectedNoConsent.effectiveStatus
        )

        val lockedConnectedAndConsented = BlockerState(
            ownPackageName = ownPackage,
            lockActive = true,
            accessibilityServiceEnabled = true,
            serviceConnectedInProcess = true
        )
        assertTrue(lockedConnectedAndConsented.isServiceReady)
        assertEquals(
            BlockerEffectiveStatus.ACTIVE_PROTECTED,
            lockedConnectedAndConsented.effectiveStatus
        )
    }

    @Test
    fun enabledServicesStringParser_matchesBothFullAndFlattenedShortComponentNames() {
        val expectedPkg = "com.tuntas.app"
        val expectedCls = "com.tuntas.app.platform.blocker.TuntasAccessibilityService"

        assertTrue(
            AndroidBlockerEnvironmentProvider.isServiceComponentPresent(
                enabledServicesSetting = "com.other.app/.Service:com.tuntas.app/com.tuntas.app.platform.blocker.TuntasAccessibilityService",
                expectedPackageName = expectedPkg,
                expectedClassName = expectedCls
            )
        )
        assertTrue(
            AndroidBlockerEnvironmentProvider.isServiceComponentPresent(
                enabledServicesSetting = "com.tuntas.app/.platform.blocker.TuntasAccessibilityService",
                expectedPackageName = expectedPkg,
                expectedClassName = expectedCls
            )
        )
        assertFalse(
            AndroidBlockerEnvironmentProvider.isServiceComponentPresent(
                enabledServicesSetting = "com.other.app/.Service",
                expectedPackageName = expectedPkg,
                expectedClassName = expectedCls
            )
        )
        assertFalse(
            AndroidBlockerEnvironmentProvider.isServiceComponentPresent(
                enabledServicesSetting = null,
                expectedPackageName = expectedPkg,
                expectedClassName = expectedCls
            )
        )
    }

    @Test
    fun controllerPersistence_survivesRestartWithAllowlistAndActiveLock() {
        val sharedStore = InMemoryBlockerStore()
        val env = FakeBlockerEnvironment(accessibilityEnabled = true)

        val controller1 = DefaultBlockerController(
            ownPackageName = ownPackage,
            store = sharedStore,
            environmentProvider = env
        )
        controller1.addAllowlistedPackage("com.android.calculator2")
        controller1.activateLock("Scheduled Demo Lock")
        controller1.recordInterception(
            packageName = "com.android.chrome",
            className = "org.chromium.chrome.browser.ChromeTabbedActivity",
            reason = InterceptionReason.NON_ALLOWLISTED_FOREGROUND_APP
        )

        // Simulate process restart by instantiating a new controller over the same store
        val controller2 = DefaultBlockerController(
            ownPackageName = ownPackage,
            store = sharedStore,
            environmentProvider = env
        )
        val restored = controller2.state.value
        assertTrue(restored.lockActive)
        assertEquals("Scheduled Demo Lock", restored.activeLockReason)
        assertTrue("com.android.calculator2" in restored.userAllowlistedPackages)
        assertEquals(1, restored.interceptionCount)
        assertNotNull(restored.lastInterception)
        assertEquals("com.android.chrome", restored.lastInterception?.packageName)

        controller2.deactivateLock()
        assertFalse(controller2.state.value.lockActive)
        assertNull(controller2.state.value.activeLockReason)
    }
}
