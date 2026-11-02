package com.phoneguard

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 原生拦截入口。来电筛选和电话状态监听都走这里，不经过 React Native。
 */
object IncomingCallGuard {
    private const val TAG = "PhoneGuard"
    const val ACTION_CALL_BLOCKED = "com.phoneguard.ACTION_CALL_BLOCKED"

    private var lastBlockedKey = ""
    private var lastBlockedAt = 0L

    fun shouldBlock(context: Context, rawNumber: String): Boolean {
        val settings = CallGuardStore.read(context)
        val normalized = CallGuardPolicy.normalizeNumber(rawNumber)
        if (!settings.protectionEnabled || normalized.isEmpty()) {
            Log.i(TAG, "allow ${CallGuardPolicy.redact(rawNumber)} protection=${settings.protectionEnabled}")
            return false
        }
        val location = if (normalized.length >= 7) {
            PhoneLookupService.getInstance(context.assets).queryLocation(normalized)
        } else {
            PhoneLookupService.LocationResult.UNKNOWN
        }

        val contactsGranted = GuardCapabilities.contactsPermissionGranted(context)
        var contactsReadable = contactsGranted
        val inContacts = if (contactsGranted) {
            try {
                ContactsMatcher.contains(context, rawNumber)
            } catch (error: Exception) {
                Log.e(TAG, "contacts unreadable, fail open", error)
                contactsReadable = false
                false
            }
        } else {
            false
        }

        val decision = CallGuardPolicy.decide(
            CallGuardPolicy.Facts(
                normalizedNumber = normalized,
                protectionEnabled = settings.protectionEnabled,
                autoBlock = settings.autoBlock,
                localRegion = settings.localProvince,
                numberProvince = location.province,
                numberCity = location.city,
                locationKnown = location.isKnown,
                inContacts = inContacts,
                contactsReadable = contactsReadable,
                inWhitelist = CallGuardPolicy.matchesList(settings.whitelist, rawNumber),
                inBlacklist = CallGuardPolicy.matchesList(settings.blacklist, rawNumber),
            ),
        )
        Log.i(
            TAG,
            "incoming ${CallGuardPolicy.redact(rawNumber)} " +
                "known=${location.isKnown} province=${location.province} city=${location.city} " +
                "contacts=$inContacts decision=$decision",
        )
        return decision == CallGuardPolicy.Decision.BLOCK
    }

    fun onBlocked(context: Context, rawNumber: String) {
        val key = CallGuardPolicy.normalizeNumber(rawNumber).ifEmpty { rawNumber }
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (key == lastBlockedKey && now - lastBlockedAt < 15_000L) {
                return
            }
            lastBlockedKey = key
            lastBlockedAt = now
        }
        CallGuardStore.recordBlock(context, key)
        GuardNotifier.onBlocked(context, key)
        val intent = Intent(ACTION_CALL_BLOCKED).apply {
            setPackage(context.packageName)
            putExtra("number", key)
            putExtra("at", now)
        }
        context.sendBroadcast(intent)
    }
}
