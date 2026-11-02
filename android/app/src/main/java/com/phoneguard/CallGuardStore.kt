package com.phoneguard

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * 防护开关、家乡、名单和拦截次数。
 * 使用 commit，避免进程马上被系统拉起筛电话时还没写到磁盘。
 */
object CallGuardStore {
    const val PREFS_NAME = "phoneguard_prefs"

    private const val KEY_LOCAL_PROVINCE = "local_province"
    private const val KEY_AUTO_BLOCK = "auto_block"
    private const val KEY_PROTECTION_ENABLED = "protection_enabled"
    private const val KEY_WHITELIST_JSON = "whitelist_json"
    private const val KEY_BLACKLIST_JSON = "blacklist_json"
    private const val KEY_LEGACY_WHITELIST = "whitelist"
    private const val KEY_LEGACY_BLACKLIST = "blacklist"
    private const val KEY_BLOCKED_COUNT = "blocked_count"
    private const val KEY_LAST_BLOCKED_NUMBER = "last_blocked_number"
    private const val KEY_LAST_BLOCKED_AT = "last_blocked_at"
    private const val KEY_LAST_SERVICE_ERROR = "last_service_error"

    data class Settings(
        val localProvince: String,
        val autoBlock: Boolean,
        val protectionEnabled: Boolean,
        val whitelist: Set<String>,
        val blacklist: Set<String>,
        val blockedCount: Int,
        val lastBlockedNumber: String,
        val lastBlockedAt: Long,
        val lastServiceError: String,
    )

    fun read(context: Context): Settings {
        val prefs = prefs(context)
        return Settings(
            localProvince = prefs.getString(KEY_LOCAL_PROVINCE, "") ?: "",
            autoBlock = prefs.getBoolean(KEY_AUTO_BLOCK, true),
            protectionEnabled = prefs.getBoolean(KEY_PROTECTION_ENABLED, false),
            whitelist = readNumbers(prefs, KEY_WHITELIST_JSON, KEY_LEGACY_WHITELIST),
            blacklist = readNumbers(prefs, KEY_BLACKLIST_JSON, KEY_LEGACY_BLACKLIST),
            blockedCount = prefs.getInt(KEY_BLOCKED_COUNT, 0),
            lastBlockedNumber = prefs.getString(KEY_LAST_BLOCKED_NUMBER, "") ?: "",
            lastBlockedAt = prefs.getLong(KEY_LAST_BLOCKED_AT, 0L),
            lastServiceError = prefs.getString(KEY_LAST_SERVICE_ERROR, "") ?: "",
        )
    }

    fun isProtectionEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_PROTECTION_ENABLED, false)
    }

    fun setProtectionEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_PROTECTION_ENABLED, enabled).commit()
    }

    fun setLastServiceError(context: Context, message: String) {
        prefs(context).edit().putString(KEY_LAST_SERVICE_ERROR, message).commit()
    }

    fun sync(
        context: Context,
        localProvince: String,
        autoBlock: Boolean,
        whitelist: List<String>,
        blacklist: List<String>,
    ) {
        val editor = prefs(context).edit()
        val province = localProvince.trim()
        if (province.isNotEmpty()) {
            editor.putString(KEY_LOCAL_PROVINCE, province)
        }
        editor.putBoolean(KEY_AUTO_BLOCK, autoBlock)
        editor.putString(KEY_WHITELIST_JSON, encode(whitelist))
        editor.putString(KEY_BLACKLIST_JSON, encode(blacklist))
        editor.remove(KEY_LEGACY_WHITELIST)
        editor.remove(KEY_LEGACY_BLACKLIST)
        editor.commit()
        CallDetectionService.refreshIfRunning(context)
    }

    fun recordBlock(context: Context, normalizedNumber: String) {
        val prefs = prefs(context)
        val count = prefs.getInt(KEY_BLOCKED_COUNT, 0) + 1
        prefs.edit()
            .putInt(KEY_BLOCKED_COUNT, count)
            .putString(KEY_LAST_BLOCKED_NUMBER, normalizedNumber)
            .putLong(KEY_LAST_BLOCKED_AT, System.currentTimeMillis())
            .commit()
    }

    private fun prefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun readNumbers(
        prefs: SharedPreferences,
        jsonKey: String,
        legacyKey: String,
    ): Set<String> {
        val json = prefs.getString(jsonKey, null)
        if (!json.isNullOrEmpty()) {
            return decode(json)
        }
        val legacy = prefs.getStringSet(legacyKey, null) ?: return emptySet()
        return legacy.map { CallGuardPolicy.normalizeNumber(it) }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private fun encode(numbers: List<String>): String {
        val array = JSONArray()
        numbers.map { CallGuardPolicy.normalizeNumber(it) }
            .filter { it.isNotEmpty() }
            .distinct()
            .forEach { array.put(it) }
        return array.toString()
    }

    private fun decode(json: String): Set<String> {
        return try {
            val array = JSONArray(json)
            buildSet {
                for (index in 0 until array.length()) {
                    val number = CallGuardPolicy.normalizeNumber(array.optString(index))
                    if (number.isNotEmpty()) add(number)
                }
            }
        } catch (_: Exception) {
            emptySet()
        }
    }
}
