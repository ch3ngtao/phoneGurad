package com.phoneguard

import android.content.res.AssetManager
import org.json.JSONObject
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * Phone number location lookup service.
 *
 * Reads phone2region.db and landline_area_codes.json from Android assets.
 * Supports both mobile (11-digit) and landline (area code + local number) lookups.
 */
class PhoneLookupService(assets: AssetManager) {

    data class LocationResult(
        val province: String,
        val city: String,
        val carrier: String,
        val isKnown: Boolean
    ) {
        companion object {
            val UNKNOWN = LocationResult("未知", "未知", "未知", false)
        }
    }

    private data class LandlineInfo(val province: String, val city: String)

    private lateinit var buffer: ByteArray
    private var vector2AreaPtr: Int = 0
    private var vectorAreaPtr: Int = 0
    private var initialized: Boolean = false
    private val landlineCodes = mutableMapOf<String, LandlineInfo>()

    init {
        loadDatabase(assets)
        loadLandlineCodes(assets)
    }

    private fun loadDatabase(assets: AssetManager) {
        try {
            val inputStream = assets.open("phone2region.db")
            buffer = inputStream.readBytes()
            inputStream.close()

            val bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)

            // Verify CRC32
            val storedCrc32 = bb.getInt(0)
            val crc32 = CRC32()
            crc32.update(buffer, 4, buffer.size - 4)
            val computedCrc32 = crc32.value.toInt()
            if (storedCrc32 != computedCrc32) {
                throw IOException("CRC32 mismatch: stored=$storedCrc32, computed=$computedCrc32")
            }

            vector2AreaPtr = bb.getInt(12)
            vectorAreaPtr = bb.getInt(16)
            initialized = true

            android.util.Log.i(TAG, "PhoneLookupService initialized: ${buffer.size} bytes, v${bb.getInt(4)}")
        } catch (e: IOException) {
            android.util.Log.e(TAG, "Failed to load phone2region.db", e)
            initialized = false
        }
    }

    private fun loadLandlineCodes(assets: AssetManager) {
        try {
            val inputStream = assets.open("landline_area_codes.json")
            val json = inputStream.bufferedReader().use { it.readText() }
            inputStream.close()

            val root = JSONObject(json)
            for (areaCode in root.keys()) {
                val obj = root.getJSONObject(areaCode)
                val province = obj.getString("province")
                val city = obj.getString("city")
                landlineCodes[areaCode] = LandlineInfo(province, city)
            }

            android.util.Log.i(TAG, "Loaded ${landlineCodes.size} landline area codes")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to load landline_area_codes.json", e)
        }
    }

    /**
     * Query phone number location.
     * Supports both mobile numbers (starting with 1) and landline numbers (starting with 0).
     * @param phone Full phone number (11-digit mobile or area code + local number)
     * @return LocationResult with province, city, carrier
     */
    fun queryLocation(phone: String): LocationResult {
        if (!initialized) return LocationResult.UNKNOWN

        val cleaned = phone.replace("\\D".toRegex(), "")
        if (cleaned.length < 7) {
            return LocationResult.UNKNOWN
        }

        // Landline: starts with 0
        if (cleaned.startsWith("0")) {
            return queryLandlineLocation(cleaned)
        }

        // Mobile: starts with 1, prefix 1300000-1999999
        if (cleaned.startsWith("1")) {
            val prefix = cleaned.substring(0, 7).toIntOrNull() ?: return LocationResult.UNKNOWN
            if (prefix in 1300000..1999999) {
                return innerQuery(prefix - 1300000)
            }
        }

        return LocationResult.UNKNOWN
    }

    /**
     * Query landline number location by area code.
     * Tries 4-digit area code first, then 3-digit.
     */
    private fun queryLandlineLocation(phone: String): LocationResult {
        if (landlineCodes.isEmpty()) return LocationResult.UNKNOWN

        // Try 4-digit area code first (e.g., 0755 Shenzhen)
        if (phone.length >= 4) {
            val areaCode4 = phone.substring(0, 4)
            landlineCodes[areaCode4]?.let {
                return LocationResult(it.province, it.city, "固话", true)
            }
        }

        // Try 3-digit area code (e.g., 010 Beijing, 021 Shanghai)
        if (phone.length >= 3) {
            val areaCode3 = phone.substring(0, 3)
            landlineCodes[areaCode3]?.let {
                return LocationResult(it.province, it.city, "固话", true)
            }
        }

        return LocationResult.UNKNOWN
    }

    /**
     * Query using full 11-digit phone number.
     */
    fun queryLocation11(phone: Long): LocationResult {
        if (!initialized) return LocationResult.UNKNOWN
        if (phone < 13000000000L || phone > 19999999999L) {
            return LocationResult.UNKNOWN
        }
        return innerQuery((phone / 10000).toInt() - 1300000)
    }

    /**
     * Query using 7-digit prefix.
     */
    fun queryLocation7(prefix: Int): LocationResult {
        if (!initialized) return LocationResult.UNKNOWN
        if (prefix < 1300000 || prefix > 1999999) {
            return LocationResult.UNKNOWN
        }
        return innerQuery(prefix - 1300000)
    }

    private fun innerQuery(index: Int): LocationResult {
        if (!initialized) return LocationResult.UNKNOWN

        val bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)

        // Two-level index lookup
        val pos = vector2AreaPtr + ((index shr 8) shl 2)
        var left = bb.getInt(pos)
        var right = bb.getInt(pos + 4)

        if (left == right) {
            return LocationResult.UNKNOWN
        }

        right -= 5
        val phoneSegments = index and 0xFF

        // Binary search
        var found = false
        var matchPos = left

        var l = left
        var r = right
        while (l <= r) {
            val mid = align((l + r) / 2)
            val num = buffer[mid].toInt() and 0xFF

            when {
                phoneSegments < num -> r = mid - 5
                phoneSegments > num -> l = mid + 5
                else -> {
                    matchPos = mid
                    found = true
                    break
                }
            }
        }

        if (!found) return LocationResult.UNKNOWN

        // Read record pointer (4 bytes after the 1-byte phone digit)
        val recordPtr = bb.getInt(matchPos + 1)
        if (recordPtr <= 0 || recordPtr >= buffer.size) {
            return LocationResult.UNKNOWN
        }

        // Read record: 1 byte length + UTF-8 text
        val recordLen = buffer[recordPtr].toInt() and 0xFF
        if (recordLen <= 0 || recordPtr + 1 + recordLen > buffer.size) {
            return LocationResult.UNKNOWN
        }

        val text = String(buffer, recordPtr + 1, recordLen, Charsets.UTF_8)
        return parseRecord(text)
    }

    /**
     * Parse record text: "province|city|zip|area|carrier"
     */
    private fun parseRecord(text: String): LocationResult {
        val parts = text.split("|")
        return if (parts.size >= 5) {
            LocationResult(parts[0], parts[1], parts[4], true)
        } else if (parts.size >= 3) {
            LocationResult(parts[0], parts[1], "未知", true)
        } else {
            LocationResult.UNKNOWN
        }
    }

    /**
     * Align position to 5-byte boundary relative to vectorAreaPtr.
     */
    private fun align(pos: Int): Int {
        val remain = (pos - vectorAreaPtr) % 5
        return when {
            pos - vectorAreaPtr < 5 -> pos - remain
            remain != 0 -> pos + 5 - remain
            else -> pos
        }
    }

    companion object {
        private const val TAG = "PhoneLookupService"

        @Volatile
        private var instance: PhoneLookupService? = null

        fun getInstance(assets: AssetManager): PhoneLookupService {
            return instance ?: synchronized(this) {
                instance ?: PhoneLookupService(assets).also { instance = it }
            }
        }
    }
}
