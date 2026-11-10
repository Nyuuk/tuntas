package com.tuntas.app.platform.blocker

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Default implementation of [BlockerController].
 *
 * Coordinates persisted lock/allowlist state ([BlockerStore]), live Accessibility permission
 * state ([BlockerEnvironmentProvider]), and deterministic package interception policy
 * ([BlockerPolicyEvaluator]).
 */
class DefaultBlockerController(
    private val ownPackageName: String,
    private val store: BlockerStore,
    private val environmentProvider: BlockerEnvironmentProvider,
    private val onLockActivatedEnforcement: ((BlockerState) -> Unit)? = null
) : BlockerController {

    private var serviceConnected: Boolean = false
    private var lastObservedPackage: String? = null
    private var lastObservedClassName: String? = null

    private val _state = MutableStateFlow(buildCurrentState())
    override val state: StateFlow<BlockerState> = _state.asStateFlow()

    override fun activateLock(reason: String) {
        // Reconciliation is idempotent. Relaunching on every foreground refresh
        // can loop on the last observed blocked package before the own-app event arrives.
        val wasActive = store.isLockActive()
        val normalizedReason = reason.trim().ifEmpty { "Manual Lock" }
        val now = environmentProvider.nowEpochMillis()
        store.setLockActive(
            active = true,
            reason = normalizedReason,
            activatedAtEpochMillis = now
        )
        val updated = refreshPermissionState()
        if (!wasActive) onLockActivatedEnforcement?.invoke(updated)
    }

    override fun deactivateLock() {
        store.setLockActive(active = false, reason = null, activatedAtEpochMillis = null)
        refreshPermissionState()
    }

    override fun setUserAllowlist(packages: Set<String>) {
        val sanitized = packages
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != ownPackageName }
            .toSortedSet()
        store.setUserAllowlist(sanitized)
        refreshPermissionState()
    }

    override fun addAllowlistedPackage(packageName: String) {
        val trimmed = packageName.trim()
        if (trimmed.isEmpty() || trimmed == ownPackageName) return
        val updated = store.getUserAllowlist().toMutableSet().apply { add(trimmed) }
        store.setUserAllowlist(updated)
        refreshPermissionState()
    }

    override fun removeAllowlistedPackage(packageName: String) {
        val trimmed = packageName.trim()
        if (trimmed.isEmpty()) return
        val updated = store.getUserAllowlist().toMutableSet().apply { remove(trimmed) }
        store.setUserAllowlist(updated)
        refreshPermissionState()
    }

    override fun refreshPermissionState(): BlockerState {
        val next = buildCurrentState()
        _state.value = next
        return next
    }

    override fun onServiceConnectionChanged(connected: Boolean) {
        serviceConnected = connected
        refreshPermissionState()
    }

    override fun evaluateWindowEvent(packageName: String?, className: String?): InterceptionDecision {
        val normalizedPkg = packageName?.trim()?.takeIf { it.isNotEmpty() }
        if (normalizedPkg != null) {
            lastObservedPackage = normalizedPkg
            lastObservedClassName = className?.trim()?.takeIf { it.isNotEmpty() }
        }
        val current = refreshPermissionState()
        val dynamicSafe = environmentProvider.resolveDynamicSystemSafePackages()
        return BlockerPolicyEvaluator.evaluate(
            foregroundPackage = normalizedPkg,
            eventClassName = className,
            ownPackageName = ownPackageName,
            lockActive = current.lockActive,
            accessibilityServiceEnabled = current.isServiceReady,
            userAllowlist = current.userAllowlistedPackages,
            dynamicSystemSafePackages = dynamicSafe
        )
    }

    override fun recordInterception(packageName: String, className: String?, reason: InterceptionReason) {
        val record = InterceptionRecord(
            packageName = packageName.trim(),
            className = className?.trim()?.takeIf { it.isNotEmpty() },
            timestampEpochMillis = environmentProvider.nowEpochMillis(),
            reason = reason
        )
        store.recordInterception(record)
        refreshPermissionState()
    }

    fun getLastObservedForegroundClassName(): String? = lastObservedClassName

    private fun buildCurrentState(): BlockerState {
        val dynamicSafe = environmentProvider.resolveDynamicSystemSafePackages()
        val combinedMandatorySafe =
            (BlockerPolicyEvaluator.STATIC_MANDATORY_SAFE_PACKAGES + dynamicSafe + ownPackageName)
                .toSortedSet()
        val accessibilityEnabled = environmentProvider.isAccessibilityServiceEnabled()
        return BlockerState(
            ownPackageName = ownPackageName,
            lockActive = store.isLockActive(),
            activeLockReason = store.getActiveLockReason(),
            lockActivatedAtEpochMillis = store.getLockActivatedAtEpochMillis(),
            accessibilityServiceEnabled = accessibilityEnabled,
            serviceConnectedInProcess = serviceConnected && accessibilityEnabled,
            userAllowlistedPackages = store.getUserAllowlist(),
            mandatorySystemSafePackages = combinedMandatorySafe,
            lastObservedForegroundPackage = lastObservedPackage,
            lastInterception = store.getLastInterception(),
            interceptionCount = store.getInterceptionCount()
        )
    }
}
