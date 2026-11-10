package com.tuntas.app.platform.blocker

import java.util.Locale

/**
 * Pure, deterministic evaluator for package-only window state transitions.
 *
 * Safety invariants:
 * 1. Own application package (`com.tuntas.app`) is always allowed so the user can view tasks,
 *    confirm completion, or manage diagnostics.
 * 2. Emergency calling, telecom/in-call UI, and dialer surfaces are always allowed so emergency
 *    access is preserved even if the user has configured an empty custom allowlist.
 * 3. Essential system surfaces (`com.android.systemui`, permission controllers, package installers,
 *    and active input methods) are allowed so lockscreen emergency buttons, system dialogs, and
 *    keyboard input are never broken.
 * 4. If lock is active but AccessibilityService permission is disabled/revoked, returns
 *    [InterceptionReason.DEGRADED_ACCESSIBILITY_DISABLED] rather than pretending blocking works.
 */
object BlockerPolicyEvaluator {

    /**
     * Mandatory emergency and dialer packages that must never be blocked.
     */
    val MANDATORY_EMERGENCY_AND_DIALER_PACKAGES: Set<String> = setOf(
        "com.android.emergency",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.incallui",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.samsung.android.incallui",
        "com.samsung.android.dialer",
        "com.sec.android.app.emergencylauncher",
        "com.miui.emergency"
    )

    /**
     * Essential Android system UI, permission controller, and keyboard packages required for
     * safe device operation and emergency lockscreen access.
     */
    val MANDATORY_ESSENTIAL_SYSTEM_PACKAGES: Set<String> = setOf(
        "com.android.systemui",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.inputmethod.latin",
        "com.google.android.inputmethod.latin",
        "com.samsung.android.honeyboard"
    )

    /**
     * Combined static safety allowlist exposed for UI transparency and testing.
     */
    val STATIC_MANDATORY_SAFE_PACKAGES: Set<String> =
        MANDATORY_EMERGENCY_AND_DIALER_PACKAGES + MANDATORY_ESSENTIAL_SYSTEM_PACKAGES

    private val EMERGENCY_CLASS_OR_PACKAGE_TOKENS = listOf(
        "emergency",
        "incallui",
        "incallactivity",
        "emergencydialer",
        "emergencycall"
    )

    /**
     * Evaluates whether a foreground window transition to [foregroundPackage] / [eventClassName]
     * should be allowed or intercepted and redirected back to [ownPackageName].
     */
    fun evaluate(
        foregroundPackage: String?,
        eventClassName: String? = null,
        ownPackageName: String,
        lockActive: Boolean,
        accessibilityServiceEnabled: Boolean,
        userAllowlist: Set<String>,
        dynamicSystemSafePackages: Set<String> = emptySet()
    ): InterceptionDecision {
        val normalizedPkg = foregroundPackage?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedClass = eventClassName?.trim()?.takeIf { it.isNotEmpty() }

        if (!lockActive) {
            return InterceptionDecision(
                action = InterceptionAction.ALLOW,
                reason = InterceptionReason.LOCK_INACTIVE,
                evaluatedPackage = normalizedPkg,
                evaluatedClassName = normalizedClass
            )
        }

        if (!accessibilityServiceEnabled) {
            return InterceptionDecision(
                action = InterceptionAction.ALLOW,
                reason = InterceptionReason.DEGRADED_ACCESSIBILITY_DISABLED,
                evaluatedPackage = normalizedPkg,
                evaluatedClassName = normalizedClass
            )
        }

        if (normalizedPkg == null) {
            return InterceptionDecision(
                action = InterceptionAction.ALLOW,
                reason = InterceptionReason.MISSING_PACKAGE_NAME,
                evaluatedPackage = null,
                evaluatedClassName = normalizedClass
            )
        }

        if (normalizedPkg == ownPackageName.trim()) {
            return InterceptionDecision(
                action = InterceptionAction.ALLOW,
                reason = InterceptionReason.OWN_APP,
                evaluatedPackage = normalizedPkg,
                evaluatedClassName = normalizedClass
            )
        }

        if (isEmergencyOrDialerSurface(normalizedPkg, normalizedClass)) {
            return InterceptionDecision(
                action = InterceptionAction.ALLOW,
                reason = InterceptionReason.EMERGENCY_OR_DIALER_SURFACE,
                evaluatedPackage = normalizedPkg,
                evaluatedClassName = normalizedClass
            )
        }

        if (normalizedPkg in MANDATORY_ESSENTIAL_SYSTEM_PACKAGES ||
            normalizedPkg in dynamicSystemSafePackages
        ) {
            return InterceptionDecision(
                action = InterceptionAction.ALLOW,
                reason = InterceptionReason.ESSENTIAL_SYSTEM_SURFACE,
                evaluatedPackage = normalizedPkg,
                evaluatedClassName = normalizedClass
            )
        }

        if (normalizedPkg in userAllowlist) {
            return InterceptionDecision(
                action = InterceptionAction.ALLOW,
                reason = InterceptionReason.USER_ALLOWLISTED_PACKAGE,
                evaluatedPackage = normalizedPkg,
                evaluatedClassName = normalizedClass
            )
        }

        return InterceptionDecision(
            action = InterceptionAction.BLOCK_AND_RETURN_TO_APP,
            reason = InterceptionReason.NON_ALLOWLISTED_FOREGROUND_APP,
            evaluatedPackage = normalizedPkg,
            evaluatedClassName = normalizedClass
        )
    }

    /**
     * Returns true if the package or activity class represents an emergency calling, SOS,
     * telecom, or dialer surface.
     */
    fun isEmergencyOrDialerSurface(packageName: String, className: String? = null): Boolean {
        if (packageName in MANDATORY_EMERGENCY_AND_DIALER_PACKAGES) {
            return true
        }
        val lowerPkg = packageName.lowercase(Locale.ROOT)
        val lowerCls = className?.lowercase(Locale.ROOT).orEmpty()
        return EMERGENCY_CLASS_OR_PACKAGE_TOKENS.any { token ->
            lowerPkg.contains(token) || lowerCls.contains(token)
        }
    }
}
