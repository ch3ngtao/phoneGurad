package com.phoneguard

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import android.app.Service
import android.content.BroadcastReceiver
import java.util.concurrent.Executors

/**
 * 常驻通知，让老人能看到防护还在，也让进程尽量不被系统立刻杀掉。
 *
 * 真正在进程被杀之后仍能拦截的是 [PhoneGuardCallScreeningService]。
 * 这里的电话状态广播只是进程还活着时的备用。Android 10 起，非默认电话应用
 * 经常拿不到号码，也不能可靠挂断，所以不能只靠它。
 *
 * 类型用 specialUse，不用 dataSync。dataSync 在 Android 15 上最长大约只能跑数小时，
 * 而且 Android 15 起不允许从开机广播拉起。
 */
class CallDetectionService : Service() {
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "phoneguard-call").apply { isDaemon = true }
    }

    private var receiverRegistered = false

    private val phoneStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
            if (state != TelephonyManager.EXTRA_STATE_RINGING) return
            val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER).orEmpty()
            if (number.isBlank()) {
                Log.i(TAG, "ringing without number; screening service handles modern Android")
                return
            }
            worker.execute { handleIncomingCall(number) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        promoteToForeground()
        ensurePhoneStateReceiver()
        runningFlag = true
        CallGuardStore.setLastServiceError(this, "")
        Log.i(TAG, "guard service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> {
                promoteToForeground()
                return START_STICKY
            }
        }
        if (!CallGuardStore.isProtectionEnabled(this)) {
            shutdown()
            return START_NOT_STICKY
        }
        promoteToForeground()
        ensurePhoneStateReceiver()
        runningFlag = true
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (CallGuardStore.isProtectionEnabled(this)) {
            start(this)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        runningFlag = false
        unregisterPhoneReceiver()
        worker.shutdownNow()
        super.onDestroy()
        Log.i(TAG, "guard service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun handleIncomingCall(number: String) {
        val block = try {
            IncomingCallGuard.shouldBlock(this, number)
        } catch (error: Exception) {
            Log.e(TAG, "policy failed, allowing call", error)
            false
        }
        if (!block) return
        if (endCall()) {
            IncomingCallGuard.onBlocked(this, number)
        } else {
            Log.w(TAG, "endCall failed for ${CallGuardPolicy.redact(number)}")
        }
    }

    private fun endCall(): Boolean {
        return try {
            val telecom = getSystemService(TELECOM_SERVICE) as TelecomManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && telecom.endCall()) {
                true
            } else {
                endCallViaITelephony()
            }
        } catch (error: Exception) {
            Log.e(TAG, "endCall error", error)
            false
        }
    }

    private fun endCallViaITelephony(): Boolean {
        return try {
            val telephony = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
            val telephonyClass = Class.forName("android.telephony.TelephonyManager")
            val getITelephony = telephonyClass.getDeclaredMethod("getITelephony")
            getITelephony.isAccessible = true
            val binder = getITelephony.invoke(telephony) ?: return false
            val endCall = binder.javaClass.getMethod("endCall")
            endCall.invoke(binder)
            true
        } catch (error: Exception) {
            Log.e(TAG, "ITelephony endCall failed", error)
            false
        }
    }

    private fun promoteToForeground() {
        val notification = GuardNotifier.buildOngoing(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                GuardNotifier.ONGOING_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(GuardNotifier.ONGOING_NOTIFICATION_ID, notification)
        }
    }

    private fun ensurePhoneStateReceiver() {
        if (receiverRegistered) return
        if (!GuardCapabilities.hasPermission(this, Manifest.permission.READ_PHONE_STATE)) {
            Log.w(TAG, "phone permission missing, skip phone state receiver")
            return
        }
        try {
            val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(phoneStateReceiver, filter, RECEIVER_EXPORTED)
            } else {
                registerReceiver(phoneStateReceiver, filter)
            }
            receiverRegistered = true
        } catch (error: Exception) {
            Log.e(TAG, "register phone state failed", error)
        }
    }

    private fun unregisterPhoneReceiver() {
        if (!receiverRegistered) return
        try {
            unregisterReceiver(phoneStateReceiver)
        } catch (_: Exception) {
            // already unregistered
        }
        receiverRegistered = false
    }

    private fun shutdown() {
        runningFlag = false
        unregisterPhoneReceiver()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val TAG = "PhoneGuard"
        const val ACTION_STOP = "com.phoneguard.ACTION_STOP"
        const val ACTION_REFRESH = "com.phoneguard.ACTION_REFRESH"

        @Volatile
        private var runningFlag = false

        fun isRunning(): Boolean = runningFlag

        fun startIfEnabled(context: Context) {
            if (!CallGuardStore.isProtectionEnabled(context)) return
            start(context)
        }

        fun start(context: Context) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, CallDetectionService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
            } catch (error: Exception) {
                Log.e(TAG, "start failed", error)
                CallGuardStore.setLastServiceError(appContext, GuardNotifier.userVisibleStartError(error))
            }
        }

        fun stop(context: Context) {
            val appContext = context.applicationContext
            try {
                if (!isRunning()) {
                    appContext.stopService(Intent(appContext, CallDetectionService::class.java))
                    return
                }
                val intent = Intent(appContext, CallDetectionService::class.java).apply {
                    action = ACTION_STOP
                }
                appContext.startService(intent)
            } catch (error: Exception) {
                Log.e(TAG, "stop failed", error)
            }
        }

        fun refreshIfRunning(context: Context) {
            if (!isRunning()) return
            try {
                val intent = Intent(context.applicationContext, CallDetectionService::class.java).apply {
                    action = ACTION_REFRESH
                }
                context.applicationContext.startService(intent)
            } catch (error: Exception) {
                Log.w(TAG, "refresh failed", error)
            }
        }
    }
}
