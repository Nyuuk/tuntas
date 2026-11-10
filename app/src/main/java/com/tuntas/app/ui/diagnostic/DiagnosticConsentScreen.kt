package com.tuntas.app.ui.diagnostic

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tuntas.app.platform.blocker.BlockerController
import com.tuntas.app.platform.blocker.BlockerEffectiveStatus
import com.tuntas.app.platform.blocker.BlockerState
import com.tuntas.app.platform.scheduling.ScheduleCoordinator
import com.tuntas.app.platform.scheduling.ScheduleReadiness
import com.tuntas.app.platform.scheduling.SchedulingState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class InstalledAppOption(
    val packageName: String,
    val label: String
)

private val TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")

private fun formatEpochMillis(epochMillis: Long?, zoneIdStr: String): String {
    if (epochMillis == null || epochMillis <= 0L) return "None"
    val zone = runCatching { ZoneId.of(zoneIdStr) }.getOrDefault(ZoneId.systemDefault())
    return Instant.ofEpochMilli(epochMillis).atZone(zone).format(TIME_FORMATTER)
}

/**
 * Baseline Diagnostic & Consent UI for Tuntas (Slice 1).
 *
 * Exposes honest permission status (including degraded state when permissions are denied or
 * revoked), manual lock activation/deactivation with R5 confirmation preview, short demo alarm
 * trigger scheduling, package allowlist management, and emergency dialer verification.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiagnosticConsentScreen(
    blockerController: BlockerController,
    scheduleCoordinator: ScheduleCoordinator,
    interceptedBannerPackage: String?,
    installedLauncherApps: List<InstalledAppOption>,
    onDismissInterceptedBanner: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenExactAlarmSettings: () -> Unit,
    onOpenEmergencyDialer: () -> Unit,
    onRefreshAll: () -> Unit,
    onReconcileNow: () -> Unit
) {
    val blockerState by blockerController.state.collectAsState()
    val schedulingState by scheduleCoordinator.state.collectAsState()

    var customPackageInput by rememberSaveable { mutableStateOf("") }
    var showCompletionConfirmDialog by rememberSaveable { mutableStateOf(false) }

    if (showCompletionConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showCompletionConfirmDialog = false },
            title = { Text("Konfirmasi Penyelesaian Task") },
            text = {
                Text("Apakah kamu yakin sudah menyelesaikan task ini?")
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        // Cancel preserves pending/locked state.
                        showCompletionConfirmDialog = false
                    }
                ) {
                    Text("Belum")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCompletionConfirmDialog = false
                        blockerController.deactivateLock()
                    }
                ) {
                    Text("Ya, sudah selesai")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Tuntas — Baseline Diagnostic & Consent",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Honesty-based, user-consented best-effort focus blocker & exact alarm baseline (Not tamper-proof / No Device Owner).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (!interceptedBannerPackage.isNullOrBlank()) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFFFF3E0)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Focus Lock Redirected You Back to Tuntas",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFE65100)
                    )
                    Text(
                        text = "Last intercepted foreground event: $interceptedBannerPackage",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    TextButton(onClick = onDismissInterceptedBanner) {
                        Text("Dismiss Notice")
                    }
                }
            }
        }

        StatusSummaryBanner(
            blockerState = blockerState,
            schedulingState = schedulingState,
            onRefreshAll = onRefreshAll
        )

        LockControlCard(
            blockerState = blockerState,
            onActivateLock = {
                blockerController.activateLock("Manual Diagnostic Lock")
            },
            onRequestConfirmComplete = {
                showCompletionConfirmDialog = true
            },
            onDirectDeactivateLock = {
                blockerController.deactivateLock()
            },
            onOpenEmergencyDialer = onOpenEmergencyDialer
        )

        PermissionsConsentCard(
            blockerState = blockerState,
            schedulingState = schedulingState,
            onOpenAccessibilitySettings = onOpenAccessibilitySettings,
            onOpenExactAlarmSettings = onOpenExactAlarmSettings,
            onRefreshAll = onRefreshAll
        )

        DemoScheduleCard(
            schedulingState = schedulingState,
            onScheduleSeconds = { seconds ->
                scheduleCoordinator.scheduleDemoTriggerAfterSeconds(seconds)
            },
            onCancelTrigger = {
                scheduleCoordinator.cancelPendingTrigger()
            },
            onReconcileNow = onReconcileNow
        )

        AllowlistConfigCard(
            blockerState = blockerState,
            installedLauncherApps = installedLauncherApps,
            customPackageInput = customPackageInput,
            onCustomPackageInputChange = { customPackageInput = it },
            onAddPackage = { pkg ->
                blockerController.addAllowlistedPackage(pkg)
                customPackageInput = ""
            },
            onRemovePackage = { pkg ->
                blockerController.removeAllowlistedPackage(pkg)
            }
        )
    }
}

@Composable
private fun StatusSummaryBanner(
    blockerState: BlockerState,
    schedulingState: SchedulingState,
    onRefreshAll: () -> Unit
) {
    val containerColor = when (blockerState.effectiveStatus) {
        BlockerEffectiveStatus.ACTIVE_PROTECTED -> Color(0xFFE8F5E9)
        BlockerEffectiveStatus.DEGRADED_PERMISSION_MISSING -> Color(0xFFFFEBEE)
        BlockerEffectiveStatus.INACTIVE_READY -> Color(0xFFE3F2FD)
        BlockerEffectiveStatus.INACTIVE_PERMISSION_MISSING -> Color(0xFFF5F5F5)
    }
    val headlineText = when (blockerState.effectiveStatus) {
        BlockerEffectiveStatus.ACTIVE_PROTECTED ->
            "PROTECTION ACTIVE — Consent Enabled & Service Connected"
        BlockerEffectiveStatus.DEGRADED_PERMISSION_MISSING ->
            "DEGRADED (NOT PROTECTED) — Lock Requested, Accessibility Consent Missing or Service Disconnected"
        BlockerEffectiveStatus.INACTIVE_READY ->
            "IDLE (READY) — Accessibility Consent Enabled & Service Connected, Lock Inactive"
        BlockerEffectiveStatus.INACTIVE_PERMISSION_MISSING ->
            "SETUP / BINDING NEEDED — Accessibility Consent Disabled or Service Disconnected, Lock Inactive"
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = headlineText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Blocker Effective Status: ${blockerState.effectiveStatus.name}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "Schedule Readiness: ${schedulingState.readiness.name}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
            if (blockerState.isDegraded) {
                Text(
                    text = "WARNING: Lock state is active, but AccessibilityService consent is disabled/revoked or the service is not connected in-process. Tuntas will NOT claim active protection until the service is both enabled in Settings and bound by Android.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFB71C1C),
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (schedulingState.isDegraded) {
                Text(
                    text = "NOTE: Exact alarm permission is not granted. Scheduled triggers use honest inexact fallback (setAndAllowWhileIdle).",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFE65100)
                )
            }
            OutlinedButton(onClick = onRefreshAll) {
                Text("Refresh Live Status")
            }
        }
    }
}

@Composable
private fun LockControlCard(
    blockerState: BlockerState,
    onActivateLock: () -> Unit,
    onRequestConfirmComplete: () -> Unit,
    onDirectDeactivateLock: () -> Unit,
    onOpenEmergencyDialer: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "1. Focus Lock Control & Emergency Access",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Lock Active: ${blockerState.lockActive} | Reason: ${blockerState.activeLockReason ?: "None"}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Interceptions Recorded: ${blockerState.interceptionCount} " +
                    "(Last: ${blockerState.lastInterception?.packageName ?: "None"})",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = onActivateLock,
                    enabled = !blockerState.lockActive,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Activate Lock")
                }
                Button(
                    onClick = onRequestConfirmComplete,
                    enabled = blockerState.lockActive,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Selesai Task (Confirm)")
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(
                    onClick = onDirectDeactivateLock,
                    enabled = blockerState.lockActive,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Diagnostic Unlock")
                }
                OutlinedButton(
                    onClick = onOpenEmergencyDialer,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Emergency / Dialer")
                }
            }
        }
    }
}

@Composable
private fun PermissionsConsentCard(
    blockerState: BlockerState,
    schedulingState: SchedulingState,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenExactAlarmSettings: () -> Unit,
    onRefreshAll: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "2. Required Permissions & Honest Consent",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            // Accessibility Service Section
            Text(
                text = "Accessibility Service (Tuntas Focus Blocker)",
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Purpose: Detects only foreground package transitions (TYPE_WINDOW_STATE_CHANGED) while a task lock is active to redirect non-allowlisted apps back to Tuntas. Window content retrieval is disabled (canRetrieveWindowContent=false); no screen text or inputs are collected.",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "Setting Enabled: ${blockerState.accessibilityServiceEnabled} | " +
                    "Service Bound: ${blockerState.serviceConnectedInProcess} | " +
                    "Effective Ready: ${blockerState.isServiceReady}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
            Button(onClick = onOpenAccessibilitySettings) {
                Text("Open Accessibility Settings")
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // Exact Alarm Section
            Text(
                text = "Exact Alarm Permission (SCHEDULE_EXACT_ALARM)",
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Purpose: Fires task lock start/lead triggers at exact local times while idle (setExactAndAllowWhileIdle). If denied on Android 12+, Tuntas falls back honestly to setAndAllowWhileIdle and shows degraded readiness.",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "Exact Alarm Granted: ${schedulingState.exactAlarmPermissionGranted} | " +
                    "Readiness: ${schedulingState.readiness}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenExactAlarmSettings) {
                    Text("Open Exact Alarm Settings")
                }
                OutlinedButton(onClick = onRefreshAll) {
                    Text("Re-check")
                }
            }
        }
    }
}

@Composable
private fun DemoScheduleCard(
    schedulingState: SchedulingState,
    onScheduleSeconds: (Long) -> Unit,
    onCancelTrigger: () -> Unit,
    onReconcileNow: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "3. Short Demo Trigger & Boot/Time Reconciliation",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Current Device Timezone: ${schedulingState.currentZoneId}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )

            val pending = schedulingState.pendingTrigger
            if (pending != null) {
                Text(
                    text = "Persisted Next Trigger:\n" +
                        "• ID: ${pending.triggerId}\n" +
                        "• Label: ${pending.label}\n" +
                        "• Target: ${formatEpochMillis(pending.triggerAtEpochMillis, schedulingState.currentZoneId)}\n" +
                        "• Mode: ${pending.deliveryMode.name}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            } else {
                Text(
                    text = "Persisted Next Trigger: None",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }

            val lastFired = schedulingState.lastFiredTrigger
            if (lastFired != null) {
                Text(
                    text = "Last Fired Trigger: ${lastFired.label} (${lastFired.triggerSource}) at " +
                        formatEpochMillis(lastFired.firedAtEpochMillis, schedulingState.currentZoneId),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }

            val lastRecon = schedulingState.lastReconciliation
            if (lastRecon != null) {
                Text(
                    text = "Last Reconciliation: ${lastRecon.reason} -> ${lastRecon.outcome} " +
                        "(mode=${lastRecon.deliveryModeUsed}, zone=${lastRecon.zoneId})",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = { onScheduleSeconds(10L) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("+10s Demo")
                }
                Button(
                    onClick = { onScheduleSeconds(30L) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("+30s Demo")
                }
                Button(
                    onClick = { onScheduleSeconds(60L) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("+60s Demo")
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(
                    onClick = onCancelTrigger,
                    enabled = pending != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Cancel Trigger")
                }
                OutlinedButton(
                    onClick = onReconcileNow,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Reconcile Now")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AllowlistConfigCard(
    blockerState: BlockerState,
    installedLauncherApps: List<InstalledAppOption>,
    customPackageInput: String,
    onCustomPackageInputChange: (String) -> Unit,
    onAddPackage: (String) -> Unit,
    onRemovePackage: (String) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "4. Emergency & User Package Allowlist",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "User-Configured Allowlisted Packages (${blockerState.userAllowlistedPackages.size}):",
                fontWeight = FontWeight.SemiBold
            )
            if (blockerState.userAllowlistedPackages.isEmpty()) {
                Text(
                    text = "None configured. Only Tuntas and mandatory emergency/system safety surfaces are permitted during lock.",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    blockerState.userAllowlistedPackages.forEach { pkg ->
                        AssistChip(
                            onClick = { onRemovePackage(pkg) },
                            label = { Text("$pkg (Tap to remove)") }
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = customPackageInput,
                    onValueChange = onCustomPackageInputChange,
                    label = { Text("Package name (e.g. com.android.calculator2)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = { onAddPackage(customPackageInput) },
                    enabled = customPackageInput.isNotBlank()
                ) {
                    Text("Add")
                }
            }

            if (installedLauncherApps.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Quick Toggle Installed Apps:",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    installedLauncherApps.take(12).forEach { appOption ->
                        val isAllowed = appOption.packageName in blockerState.userAllowlistedPackages
                        AssistChip(
                            onClick = {
                                if (isAllowed) {
                                    onRemovePackage(appOption.packageName)
                                } else {
                                    onAddPackage(appOption.packageName)
                                }
                            },
                            label = {
                                Text(
                                    if (isAllowed) {
                                        "✓ ${appOption.label} (${appOption.packageName})"
                                    } else {
                                        "+ ${appOption.label} (${appOption.packageName})"
                                    }
                                )
                            }
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Text(
                text = "Mandatory Immutable Safety Allowlist (Own App + Emergency/Dialer + System UI):",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = blockerState.mandatorySystemSafePackages.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
