package com.tuntas.app.platform.blocker

import kotlinx.coroutines.flow.StateFlow

/**
 * Overall protection status of the blocker primitive.
 *
 * Permission denial, revocation, or an unbound/disconnected service must
 * never falsely present protection as active or ready: [ACTIVE_PROTECTED] and [INACTIVE_READY]
 * require BOTH [BlockerState.accessibilityServiceEnabled] (user consent in settings) AND
 * [BlockerState.serviceConnectedInProcess] (live bound service connection).
 */
enum class BlockerEffectiveStatus {
    /** Lock is active, Accessibility consent is enabled in settings, and service is connected in-process. */
    ACTIVE_PROTECTED,

    /** Lock is active, but Accessibility consent is disabled/revoked or service is disconnected. Protection is degraded. */
    DEGRADED_PERMISSION_MISSING,

    /** Lock is currently inactive, Accessibility consent is enabled in settings, and service is connected in-process. */
    INACTIVE_READY,

    /** Lock is currently inactive, and Accessibility consent is disabled or service is disconnected. */
    INACTIVE_PERMISSION_MISSING
}

/**
 * Action to take when a foreground package window transition is observed.
 */
enum class InterceptionAction {
    ALLOW,
    BLOCK_AND_RETURN_TO_APP
}

/**
 * Reason explaining why a foreground window transition was allowed or intercepted.
 */
enum class InterceptionReason {
    LOCK_INACTIVE,
    DEGRADED_ACCESSIBILITY_DISABLED,
    MISSING_PACKAGE_NAME,
    OWN_APP,
    EMERGENCY_OR_DIALER_SURFACE,
    ESSENTIAL_SYSTEM_SURFACE,
    USER_ALLOWLISTED_PACKAGE,
    NON_ALLOWLISTED_FOREGROUND_APP
}

/**
 * Deterministic policy evaluation result for a window state transition event.
 */
data class InterceptionDecision(
    val action: InterceptionAction,
    val reason: InterceptionReason,
    val evaluatedPackage: String?,
    val evaluatedClassName: String?
) {
    val shouldBlock: Boolean
        get() = action == InterceptionAction.BLOCK_AND_RETURN_TO_APP
}

/**
 * Diagnostic record of the most recent package interception.
 */
data class InterceptionRecord(
    val packageName: String,
    val className: String?,
    val timestampEpochMillis: Long,
    val reason: InterceptionReason
)

/**
 * Snapshot of blocker readiness, active lock state, allowlists, and diagnostic metrics.
 */
data class BlockerState(
    val ownPackageName: String,
    val lockActive: Boolean = false,
    val activeLockReason: String? = null,
    val lockActivatedAtEpochMillis: Long? = null,
    val accessibilityServiceEnabled: Boolean = false,
    val serviceConnectedInProcess: Boolean = false,
    val userAllowlistedPackages: Set<String> = emptySet(),
    val mandatorySystemSafePackages: Set<String> = emptySet(),
    val lastObservedForegroundPackage: String? = null,
    val lastInterception: InterceptionRecord? = null,
    val interceptionCount: Int = 0
) {
    /**
     * True only when system Accessibility setting consent is enabled AND
     * [TuntasAccessibilityService] is currently bound/connected in-process.
     */
    val isServiceReady: Boolean
        get() = accessibilityServiceEnabled && serviceConnectedInProcess

    val effectiveStatus: BlockerEffectiveStatus
        get() = when {
            lockActive && isServiceReady -> BlockerEffectiveStatus.ACTIVE_PROTECTED
            lockActive && !isServiceReady -> BlockerEffectiveStatus.DEGRADED_PERMISSION_MISSING
            !lockActive && isServiceReady -> BlockerEffectiveStatus.INACTIVE_READY
            else -> BlockerEffectiveStatus.INACTIVE_PERMISSION_MISSING
        }

    val isDegraded: Boolean
        get() = effectiveStatus == BlockerEffectiveStatus.DEGRADED_PERMISSION_MISSING
}

/**
 * Abstraction for checking whether the user has enabled Tuntas's AccessibilityService
 * and discovering dynamic system-safe packages (default dialer, enabled IMEs).
 */
interface BlockerEnvironmentProvider {
    fun isAccessibilityServiceEnabled(): Boolean
    fun resolveDynamicSystemSafePackages(): Set<String>
    fun nowEpochMillis(): Long
}

/**
 * Persistence contract for lock state and user-configured emergency package allowlist.
 */
interface BlockerStore {
    fun isLockActive(): Boolean
    fun setLockActive(active: Boolean, reason: String?, activatedAtEpochMillis: Long?)
    fun getActiveLockReason(): String?
    fun getLockActivatedAtEpochMillis(): Long?
    fun getUserAllowlist(): Set<String>
    fun setUserAllowlist(packages: Set<String>)
    fun getLastInterception(): InterceptionRecord?
    fun getInterceptionCount(): Int
    fun recordInterception(record: InterceptionRecord): Int
}

/**
 * Stable platform blocker interface consumed by scheduling primitives, domain slices, and UI.
 */
interface BlockerController {
    val state: StateFlow<BlockerState>

    /** Activates blocking lock mode with a descriptive reason (manual or scheduled). */
    fun activateLock(reason: String)

    /** Deactivates blocking lock mode. */
    fun deactivateLock()

    /** Replaces the persisted user emergency allowlist (normalized, trimmed, non-blank). */
    fun setUserAllowlist(packages: Set<String>)

    /** Adds a single package name to the user emergency allowlist. */
    fun addAllowlistedPackage(packageName: String)

    /** Removes a single package name from the user emergency allowlist. */
    fun removeAllowlistedPackage(packageName: String)

    /** Re-queries system AccessibilityService setting and dynamic safe packages. */
    fun refreshPermissionState(): BlockerState

    /** Updates live AccessibilityService connection status and observed foreground package. */
    fun onServiceConnectionChanged(connected: Boolean)

    /** Evaluates a package-only window state event against the active lock policy. */
    fun evaluateWindowEvent(packageName: String?, className: String? = null): InterceptionDecision

    /** Records an interception and notifies any registered enforcement listener. */
    fun recordInterception(packageName: String, className: String?, reason: InterceptionReason)
}
