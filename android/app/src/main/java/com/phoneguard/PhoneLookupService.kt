package com.phoneguard

import android.content.res.AssetManager
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * Phone number location lookup service.
 *
 * Reads phone2region.db from Android assets and performs binary search
 * to look up province, city, carrier for Chinese phone numbers.
 *
 * Database format (phone2region v2):
 * - 4 bytes: CRC32 checksum (signed int32)
 * - 4 bytes: version number
 * - 4 bytes: CRC32 of data from byte 12 onward (signed int32)
 * - 4 bytes: vector2AreaPtr (second-level index pointer)
 * - 4 bytes: vectorAreaPtr (index section pointer)
 * - Data: text records and 5-byte index entries
 *
 * Index entry format (5 bytes):
 * - 1 byte: last digit of (phone_prefix - 1300000)
 * - 4 bytes: record pointer (LE uint32)
 *
 * Record format:
 * - 1 byte: text length
 * - N bytes: UTF-8 text "province|city|zip|area|carrier"
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

    private lateinit var buffer: ByteArray
    private var vector2AreaPtr: Int = 0
    private var vectorAreaPtr: Int = 0
    private var initialized: Boolean = false

    init {
        loadDatabase(assets)
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

    /**
     * Query phone number location.
     * @param phone Full 11-digit phone number or at least first 7 digits
     * @return LocationResult with province, city, carrier
     */
    fun queryLocation(phone: String): LocationResult {
        if (!initialized) return LocationResult.UNKNOWN

        // Extract first 7 digits
        val cleaned = phone.replace("\\D".toRegex(), "")
        if (cleaned.length < 7 || !cleaned.startsWith("1")) {
            return LocationResult.UNKNOWN
        }

        val prefix = cleaned.substring(0, 7).toIntOrNull() ?: return LocationResult.UNKNOWN
        if (prefix < 1300000 || prefix > 1999999) {
            return LocationResult.UNKNOWN
        }

        return innerQuery(prefix - 1300000)
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
