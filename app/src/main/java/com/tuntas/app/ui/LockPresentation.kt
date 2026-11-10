package com.tuntas.app.ui

/** Navigation requests are data, not authority. No normal destination exists while uncertain/locked. */
enum class PresentationMode { HYDRATING, LOCKED, NORMAL }
data class NormalDestination(val tab: String, val editingId: String?)

fun presentationMode(state: TaskUiState): PresentationMode = when {
    !state.loaded -> PresentationMode.HYDRATING
    state.snapshot.blocking -> PresentationMode.LOCKED
    else -> PresentationMode.NORMAL
}

fun normalDestination(state: TaskUiState, tab: String, editingId: String?): NormalDestination? =
    if (presentationMode(state) == PresentationMode.NORMAL) NormalDestination(tab, editingId) else null

data class LockAppOption(val app: AppOption, val launchable: Boolean)

/** Only explicitly selected apps get buttons, and only if Android found a launcher activity.
 * Mandatory system packages are enforced separately; they are NOT a general-purpose launch menu.
 * AppOption currently supplies labels, not icons. Unknown/uninstalled selections remain read-only.
 */
fun lockAppOptions(apps: List<AppOption>, selected: Set<String>): List<LockAppOption> {
    val known = apps.associateBy { it.packageName }
    return selected.map { pkg -> LockAppOption(known[pkg] ?: AppOption(pkg, pkg), pkg in known) }
        .sortedBy { it.app.label.lowercase() }
}
