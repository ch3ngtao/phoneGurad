package com.phoneguard

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * 来电检测前台服务
 *
 * 使用前台服务保证应用在后台时也能检测来电并执行拦截。
 * 服务通过 BroadcastReceiver 监听 PHONE_STATE_CHANGED，
 * 在收到来电事件后使用 PhoneLookupService 判断归属地并执行拦截。
 */
class CallDetectionService : Service() {

    private val phoneStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
            val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: ""

            when (state) {
                TelephonyManager.EXTRA_STATE_RINGING -> {
                    handleIncomingCall(incomingNumber)
                }
            }
        }
    }

    private var telecomManager: TelecomManager? = null
    private var telephonyManager: TelephonyManager? = null
    private var phoneLookupService: PhoneLookupService? = null
    private lateinit var prefs: SharedPreferences

    override fun onCreate() {
        super.onCreate()
        telecomManager = getSystemService(TELECOM_SERVICE) as TelecomManager
        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        phoneLookupService = PhoneLookupService.getInstance(assets)
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        registerReceiver(phoneStateReceiver, filter)

        Log.i(TAG, "CallDetectionService started")
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(phoneStateReceiver)
        } catch (_: Exception) {
            // already unregistered
        }
        super.onDestroy()
        Log.i(TAG, "CallDetectionService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle action for stopping
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    /**
     * Handle incoming call: check settings and block if needed.
     */
    private fun handleIncomingCall(phoneNumber: String) {
        Log.i(TAG, "Incoming call: $phoneNumber")

        val localProvince = prefs.getString(KEY_LOCAL_PROVINCE, null)
        if (localProvince.isNullOrEmpty()) {
            Log.i(TAG, "No local province set, skipping block")
            return
        }

        val autoBlock = prefs.getBoolean(KEY_AUTO_BLOCK, false)
        val whitelist = prefs.getStringSet(KEY_WHITELIST, emptySet()) ?: emptySet()
        val blacklist = prefs.getStringSet(KEY_BLACKLIST, emptySet()) ?: emptySet()

        // Blacklist: always block
        if (blacklist.contains(phoneNumber)) {
            Log.i(TAG, "Blacklisted number, blocking: $phoneNumber")
            endCall()
            return
        }

        // Whitelist: never block
        if (whitelist.contains(phoneNumber)) {
            Log.i(TAG, "Whitelisted number, skipping: $phoneNumber")
            return
        }

        // Contacts whitelist: never block
        if (isNumberInContacts(phoneNumber)) {
            Log.i(TAG, "Number in contacts, skipping: $phoneNumber")
            return
        }

        // Auto-block non-local numbers
        if (autoBlock && phoneLookupService != null) {
            val location = phoneLookupService!!.queryLocation(phoneNumber)
            if (location.isKnown && location.province != localProvince) {
                Log.i(TAG, "Non-local number (${location.province}), blocking: $phoneNumber")
                endCall()
                return
            }
        }
    }

    /**
     * Check if the phone number exists in contacts.
     * Matches by last 10 digits to handle different formatting (+86, 0 prefix, etc).
     */
    private fun isNumberInContacts(phoneNumber: String): Boolean {
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) {
            return false
        }

        try {
            val cleaned = phoneNumber.replace(Regex("[^0-9]"), "")
            if (cleaned.length < 7) return false

            // Take last 10 digits for matching (handles +86 prefix differences)
            val suffix = cleaned.takeLast(10)

            // Query all contacts and match by suffix
            val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val cursor: Cursor? = contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null, null, null
            )
            cursor?.use {
                while (it.moveToNext()) {
                    val stored = it.getString(0)?.replace(Regex("[^0-9]"), "") ?: continue
                    if (stored.length >= 10 && stored.takeLast(10) == suffix) {
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking contacts", e)
        }
        return false
    }

    private fun hasPermission(permission: String): Boolean {
        return android.content.pm.PackageManager.PERMISSION_GRANTED ==
            checkSelfPermission(permission)
    }

    /**
     * End the current call.
     */
    private fun endCall() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val result = telecomManager?.endCall()
                if (result == true) {
                    Log.i(TAG, "Call ended via TelecomManager")
                    return
                }
                // Fallback: ITelephony reflection
                if (tryEndCallViaITelephony()) {
                    Log.i(TAG, "Call ended via ITelephony")
                    return
                }
            }
            Log.w(TAG, "Failed to end call")
        } catch (e: Exception) {
            Log.e(TAG, "Error ending call", e)
        }
    }

    /**
     * Use ITelephony reflection to end call.
     */
    private fun tryEndCallViaITelephony(): Boolean {
        return try {
            val telephonyClass = Class.forName("android.telephony.TelephonyManager")
            val getTelecomMethod = telephonyClass.getDeclaredMethod("getITelephony")
            getTelecomMethod.isAccessible = true
            val telephony = getTelecomMethod.invoke(telephonyManager)
            val endCallMethod = telephony?.javaClass?.getMethod("endCall")
            endCallMethod?.invoke(telephony)
            true
        } catch (e: Exception) {
            Log.e(TAG, "ITelephony fallback failed", e)
            false
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "来电检测服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "PhoneGuard 来电检测服务，用于在后台监控和拦截骚扰电话"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PhoneGuard 正在运行")
            .setContentText("正在监控来电...")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "CallDetectionService"
        private const val CHANNEL_ID = "call_detection_channel"
        private const val NOTIFICATION_ID = 1001
        private const val PREFS_NAME = "phoneguard_prefs"

        const val ACTION_START = "com.phoneguard.ACTION_START"
        const val ACTION_STOP = "com.phoneguard.ACTION_STOP"

        const val KEY_LOCAL_PROVINCE = "local_province"
        const val KEY_AUTO_BLOCK = "auto_block"
        const val KEY_WHITELIST = "whitelist"
        const val KEY_BLACKLIST = "blacklist"

        fun buildStartIntent(context: Context): Intent {
            return Intent(context, CallDetectionService::class.java).apply {
                action = ACTION_START
            }
        }

        fun buildStopIntent(context: Context): Intent {
            return Intent(context, CallDetectionService::class.java).apply {
                action = ACTION_STOP
            }
        }
    }
}
