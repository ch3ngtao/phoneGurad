package com.phoneguard

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log

/**
 * 用系统的 PhoneLookup 判断号码是否在通讯录。
 * 不扫整本通讯录，避免来电筛选超过系统给出的几秒时限。
 */
object ContactsMatcher {
    private const val TAG = "PhoneGuard"

    fun contains(context: Context, rawNumber: String): Boolean {
        val normalized = CallGuardPolicy.normalizeNumber(rawNumber)
        if (normalized.length < 7) return false

        val candidates = linkedSetOf<String>()
        if (rawNumber.isNotBlank()) candidates.add(rawNumber.trim())
        candidates.add(normalized)
        if (normalized.length == 11 && normalized.startsWith("1")) {
            candidates.add("+86$normalized")
            candidates.add("86$normalized")
        }

        for (candidate in candidates) {
            if (lookup(context, candidate)) return true
        }
        return false
    }

    private fun lookup(context: Context, number: String): Boolean {
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(number),
        )
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup._ID),
                null,
                null,
                null,
            )?.use { cursor -> cursor.moveToFirst() } == true
        } catch (error: Exception) {
            Log.e(TAG, "contacts lookup failed", error)
            throw error
        }
    }
}
