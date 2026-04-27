package com.phoneguard

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule

/**
 * 电话检测原生模块
 *
 * 需要的 Android 权限:
 * - READ_PHONE_STATE: 读取电话状态
 * - READ_CALL_LOG: 读取通话记录
 * - ANSWER_PHONE_CALLS: 接听/挂断电话 (Android 8.0+)
 */
class CallDetectionModule(private val reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {

    override fun getName(): String = "CallDetectionModule"

    private var telecomManager: TelecomManager? = null
    private var telephonyManager: TelephonyManager? = null
    private var isListening = false
    private val phoneLookupService: PhoneLookupService = PhoneLookupService.getInstance(reactContext.assets)
    private val prefs = reactContext.getSharedPreferences("phoneguard_prefs", Context.MODE_PRIVATE)

    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CALL_LOG,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Manifest.permission.ANSWER_PHONE_CALLS
        } else {
            ""
        },
        Manifest.permission.CALL_PHONE
    ).filter { it.isNotEmpty() }.toTypedArray()

    @ReactMethod
    fun startListening(promise: Promise) {
        try {
            if (isListening) {
                promise.resolve("Already listening")
                return
            }

            telecomManager = reactContext.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            telephonyManager = reactContext.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

            // Start the foreground service
            val intent = CallDetectionService.buildStartIntent(reactContext)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                reactContext.startForegroundService(intent)
            } else {
                reactContext.startService(intent)
            }

            isListening = true
            promise.resolve("Listening started")
        } catch (e: Exception) {
            promise.reject("ERROR", e.message)
        }
    }

    @ReactMethod
    fun stopListening(promise: Promise) {
        try {
            val intent = CallDetectionService.buildStopIntent(reactContext)
            reactContext.stopService(intent)
            isListening = false
            promise.resolve("Listening stopped")
        } catch (e: Exception) {
            promise.reject("ERROR", e.message)
        }
    }

    @ReactMethod
    fun endCall(promise: Promise) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Try TelecomManager.endCall first
                val result = telecomManager?.endCall()
                if (result == true) {
                    promise.resolve("Call ended")
                } else {
                    // Fallback: try ITelephony via reflection
                    val rejected = tryEndCallViaITelephony()
                    if (rejected) {
                        promise.resolve("Call ended (fallback)")
                    } else {
                        promise.reject(
                            "ERROR",
                            "无法挂断电话。Android 系统限制，需要将本应用设为默认电话应用才能自动拦截。"
                        )
                    }
                }
            } else {
                promise.reject("ERROR", "需要 Android 9.0+ 才能挂断电话")
            }
        } catch (e: Exception) {
            promise.reject("ERROR", e.message)
        }
    }

    @ReactMethod
    fun rejectCall(promise: Promise) {
        endCall(promise)
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
            Log.e("CallDetectionModule", "ITelephony fallback failed", e)
            false
        }
    }

    @ReactMethod
    fun checkPermissions(promise: Promise) {
        try {
            val permissions = Arguments.createMap().apply {
                putBoolean("hasPhoneStatePermission", hasPermission(Manifest.permission.READ_PHONE_STATE))
                putBoolean("hasCallLogPermission", hasPermission(Manifest.permission.READ_CALL_LOG))
                putBoolean("hasAnswerPhonePermission",
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        hasPermission(Manifest.permission.ANSWER_PHONE_CALLS)
                    } else {
                        true
                    }
                )
                putBoolean("hasCallPhonePermission", hasPermission(Manifest.permission.CALL_PHONE))
            }

            val allGranted = REQUIRED_PERMISSIONS.all { hasPermission(it) }
            permissions.putBoolean("allGranted", allGranted)

            promise.resolve(permissions)
        } catch (e: Exception) {
            promise.reject("ERROR", e.message)
        }
    }

    @ReactMethod
    fun requestPermissions(promise: Promise) {
        try {
            val currentActivity = reactContext.currentActivity
            if (currentActivity == null) {
                promise.reject("ERROR", "No current activity")
                return
            }

            val missingPermissions = REQUIRED_PERMISSIONS.filter { !hasPermission(it) }

            if (missingPermissions.isEmpty()) {
                promise.resolve(true)
                return
            }

            ActivityCompat.requestPermissions(
                currentActivity,
                missingPermissions.toTypedArray(),
                1001
            )

            promise.resolve(false)
        } catch (e: Exception) {
            promise.reject("ERROR", e.message)
        }
    }

    @ReactMethod
    fun queryPhoneLocation(phoneNumber: String, promise: Promise) {
        try {
            val result = phoneLookupService.queryLocation(phoneNumber)
            val map = Arguments.createMap().apply {
                putString("province", result.province)
                putString("city", result.city)
                putString("carrier", result.carrier)
                putBoolean("isKnown", result.isKnown)
            }
            promise.resolve(map)
        } catch (e: Exception) {
            promise.reject("ERROR", e.message)
        }
    }

    @ReactMethod
    fun syncSettingsToNative(
        localProvince: String,
        autoBlock: Boolean,
        whitelist: ReadableArray,
        blacklist: ReadableArray,
    ) {
        try {
            prefs.edit().apply {
                putString("local_province", localProvince)
                putBoolean("auto_block", autoBlock)
                val wl = whitelist.toArrayList().map { it.toString() }.toSet()
                val bl = blacklist.toArrayList().map { it.toString() }.toSet()
                putStringSet("whitelist", wl)
                putStringSet("blacklist", bl)
                apply()
            }
        } catch (e: Exception) {
            Log.e("CallDetectionModule", "Failed to sync settings", e)
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(reactContext, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun sendEvent(eventName: String, params: WritableMap) {
        reactContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(eventName, params)
    }

    override fun onCatalystInstanceDestroy() {
        isListening = false
    }
}
