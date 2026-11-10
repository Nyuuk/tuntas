package com.tuntas.app.platform.blocker

import android.content.SharedPreferences

/**
 * SharedPreferences-backed implementation of [BlockerStore] so active lock state and
 * user-configured emergency allowlist survive process restart.
 */
class SharedPreferencesBlockerStore(
    private val prefs: SharedPreferences
) : BlockerStore {

    override fun isLockActive(): Boolean =
        prefs.getBoolean(KEY_LOCK_ACTIVE, false)

    override fun setLockActive(active: Boolean, reason: String?, activatedAtEpochMillis: Long?) {
        prefs.edit().apply {
            putBoolean(KEY_LOCK_ACTIVE, active)
            if (active && reason != null) {
                putString(KEY_LOCK_REASON, reason)
            } else if (!active) {
                remove(KEY_LOCK_REASON)
            }
            if (active && activatedAtEpochMillis != null) {
                putLong(KEY_LOCK_ACTIVATED_AT, activatedAtEpochMillis)
            } else if (!active) {
                remove(KEY_LOCK_ACTIVATED_AT)
            }
        }.apply()
    }

    override fun getActiveLockReason(): String? =
        prefs.getString(KEY_LOCK_REASON, null)

    override fun getLockActivatedAtEpochMillis(): Long? =
        if (prefs.contains(KEY_LOCK_ACTIVATED_AT)) {
            prefs.getLong(KEY_LOCK_ACTIVATED_AT, 0L)
        } else {
            null
        }

    override fun getUserAllowlist(): Set<String> =
        prefs.getStringSet(KEY_USER_ALLOWLIST, emptySet())
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSortedSet()
            ?: emptySet()

    override fun setUserAllowlist(packages: Set<String>) {
        val sanitized = packages.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        prefs.edit()
            .putStringSet(KEY_USER_ALLOWLIST, sanitized)
            .apply()
    }

    override fun getLastInterception(): InterceptionRecord? {
        val pkg = prefs.getString(KEY_LAST_INTERCEPTED_PKG, null) ?: return null
        val cls = prefs.getString(KEY_LAST_INTERCEPTED_CLS, null)
        val timestamp = prefs.getLong(KEY_LAST_INTERCEPTED_AT, 0L)
        val reasonName = prefs.getString(KEY_LAST_INTERCEPTED_REASON, null)
        val reason = reasonName?.let {
            runCatching { InterceptionReason.valueOf(it) }.getOrNull()
        } ?: InterceptionReason.NON_ALLOWLISTED_FOREGROUND_APP
        return InterceptionRecord(
            packageName = pkg,
            className = cls,
            timestampEpochMillis = timestamp,
            reason = reason
        )
    }

    override fun getInterceptionCount(): Int =
        prefs.getInt(KEY_INTERCEPTION_COUNT, 0)

    override fun recordInterception(record: InterceptionRecord): Int {
        val nextCount = getInterceptionCount() + 1
        prefs.edit()
            .putString(KEY_LAST_INTERCEPTED_PKG, record.packageName)
            .putString(KEY_LAST_INTERCEPTED_CLS, record.className)
            .putLong(KEY_LAST_INTERCEPTED_AT, record.timestampEpochMillis)
            .putString(KEY_LAST_INTERCEPTED_REASON, record.reason.name)
            .putInt(KEY_INTERCEPTION_COUNT, nextCount)
            .apply()
        return nextCount
    }

    companion object {
        private const val KEY_LOCK_ACTIVE = "blocker_lock_active"
        private const val KEY_LOCK_REASON = "blocker_lock_reason"
        private const val KEY_LOCK_ACTIVATED_AT = "blocker_lock_activated_at"
        private const val KEY_USER_ALLOWLIST = "blocker_user_allowlist"
        private const val KEY_LAST_INTERCEPTED_PKG = "blocker_last_intercepted_pkg"
        private const val KEY_LAST_INTERCEPTED_CLS = "blocker_last_intercepted_cls"
        private const val KEY_LAST_INTERCEPTED_AT = "blocker_last_intercepted_at"
        private const val KEY_LAST_INTERCEPTED_REASON = "blocker_last_intercepted_reason"
        private const val KEY_INTERCEPTION_COUNT = "blocker_interception_count"
    }
}

/**
 * In-memory implementation of [BlockerStore] for fast deterministic unit tests.
 */
class InMemoryBlockerStore(
    initialLockActive: Boolean = false,
    initialReason: String? = null,
    initialAllowlist: Set<String> = emptySet()
) : BlockerStore {
    private var lockActive: Boolean = initialLockActive
    private var lockReason: String? = initialReason
    private var lockActivatedAt: Long? = null
    private var userAllowlist: Set<String> = initialAllowlist.map { it.trim() }.filter { it.isNotEmpty() }.toSortedSet()
    private var lastInterception: InterceptionRecord? = null
    private var interceptionCount: Int = 0

    override fun isLockActive(): Boolean = lockActive

    override fun setLockActive(active: Boolean, reason: String?, activatedAtEpochMillis: Long?) {
        lockActive = active
        lockReason = if (active) reason else null
        lockActivatedAt = if (active) activatedAtEpochMillis else null
    }

    override fun getActiveLockReason(): String? = lockReason

    override fun getLockActivatedAtEpochMillis(): Long? = lockActivatedAt

    override fun getUserAllowlist(): Set<String> = userAllowlist

    override fun setUserAllowlist(packages: Set<String>) {
        userAllowlist = packages.map { it.trim() }.filter { it.isNotEmpty() }.toSortedSet()
    }

    override fun getLastInterception(): InterceptionRecord? = lastInterception

    override fun getInterceptionCount(): Int = interceptionCount

    override fun recordInterception(record: InterceptionRecord): Int {
        interceptionCount += 1
        lastInterception = record
        return interceptionCount
    }
}
