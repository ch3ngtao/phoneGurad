package com.phoneguard

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.facebook.react.bridge.ActivityEventListener
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.BaseActivityEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.modules.core.DeviceEventManagerModule

/**
 * JS 只负责开关、权限和把设置写进原生存储。
 * 来电判断和挂断不走这里，避免界面退出或脚本被回收后失效。
 */
class CallDetectionModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    override fun getName(): String = "CallDetectionModule"

    private var rolePromise: Promise? = null
    private var cleanedUp = false
    private var blockReceiverRegistered = false

    private val activityListener: ActivityEventListener = object : BaseActivityEventListener() {
        override fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?) {
            if (requestCode != REQUEST_CALL_SCREENING_ROLE) return
            val granted = GuardCapabilities.screeningRoleHeld(reactContext)
            rolePromise?.resolve(granted)
            rolePromise = null
        }
    }

    private val blockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != IncomingCallGuard.ACTION_CALL_BLOCKED) return
            val params = Arguments.createMap().apply {
                putString("eventType", "blocked")
                putString("phoneNumber", intent.getStringExtra("number") ?: "")
                putDouble("timestamp", intent.getLongExtra("at", 0L).toDouble())
            }
            sendEvent("onCallEvent", params)
        }
    }

    init {
        reactContext.addActivityEventListener(activityListener)
        registerBlockReceiver()
    }

    @ReactMethod
    fun startListening(promise: Promise) {
        try {
            CallGuardStore.setProtectionEnabled(reactContext, true)
            CallGuardStore.setLastServiceError(reactContext, "")
            CallDetectionService.start(reactContext)
            promise.resolve(true)
        } catch (error: Exception) {
            promise.reject("ERROR", error.message)
        }
    }

    @ReactMethod
    fun stopListening(promise: Promise) {
        try {
            CallGuardStore.setProtectionEnabled(reactContext, false)
            CallDetectionService.stop(reactContext)
            promise.resolve(true)
        } catch (error: Exception) {
            promise.reject("ERROR", error.message)
        }
    }

    @ReactMethod
    fun endCall(promise: Promise) {
        promise.reject("ERROR", "挂断由系统来电过滤完成，界面不再直接挂断。")
    }

    @ReactMethod
    fun rejectCall(promise: Promise) {
        endCall(promise)
    }

    @ReactMethod
    fun shouldBlockNumber(phoneNumber: String, promise: Promise) {
        try {
            promise.resolve(IncomingCallGuard.shouldBlock(reactContext, phoneNumber))
        } catch (error: Exception) {
            Log.e(TAG, "shouldBlock failed", error)
            promise.resolve(false)
        }
    }

    @ReactMethod
    fun checkPermissions(promise: Promise) {
        try {
            val permissions = Arguments.createMap().apply {
                putBoolean("hasPhoneStatePermission", hasPermission(Manifest.permission.READ_PHONE_STATE))
                putBoolean("hasCallLogPermission", hasPermission(Manifest.permission.READ_CALL_LOG))
                putBoolean(
                    "hasAnswerPhonePermission",
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        hasPermission(Manifest.permission.ANSWER_PHONE_CALLS)
                    } else {
                        true
                    },
                )
                putBoolean("allGranted", GuardCapabilities.phonePermissionGranted(reactContext))
            }
            promise.resolve(permissions)
        } catch (error: Exception) {
            promise.reject("ERROR", error.message)
        }
    }

    @ReactMethod
    fun requestPermissions(promise: Promise) {
        promise.resolve(GuardCapabilities.phonePermissionGranted(reactContext))
    }

    @ReactMethod
    fun queryPhoneLocation(phoneNumber: String, promise: Promise) {
        try {
            val lookup = PhoneLookupService.getInstance(reactContext.assets)
            val result = lookup.queryLocation(CallGuardPolicy.normalizeNumber(phoneNumber))
            val map = Arguments.createMap().apply {
                putString("province", result.province)
                putString("city", result.city)
                putString("carrier", result.carrier)
                putBoolean("isKnown", result.isKnown)
            }
            promise.resolve(map)
        } catch (error: Exception) {
            promise.reject("ERROR", error.message)
        }
    }

    @ReactMethod
    fun syncSettingsToNative(
        localProvince: String,
        autoBlock: Boolean,
        whitelist: ReadableArray,
        blacklist: ReadableArray,
        promise: Promise,
    ) {
        try {
            CallGuardStore.sync(
                reactContext,
                localProvince,
                autoBlock,
                whitelist.toStringList(),
                blacklist.toStringList(),
            )
            promise.resolve(true)
        } catch (error: Exception) {
            promise.reject("ERROR", error.message)
        }
    }

    @ReactMethod
    fun saveUserState(
        localProvince: String,
        autoBlock: Boolean,
        protectionEnabled: Boolean,
        enableNotification: Boolean,
        enableLog: Boolean,
        batteryPromptDismissed: Boolean,
        autostartOpened: Boolean,
        whitelist: ReadableArray,
        blacklist: ReadableArray,
        promise: Promise,
    ) {
        try {
            CallGuardStore.saveUserState(
                reactContext,
                localProvince,
                autoBlock,
                protectionEnabled,
                enableNotification,
                enableLog,
                batteryPromptDismissed,
                autostartOpened,
                whitelist.toStringList(),
                blacklist.toStringList(),
            )
            promise.resolve(true)
        } catch (error: Exception) {
            promise.reject("ERROR", error.message)
        }
    }

    @ReactMethod
    fun getProtectionStatus(promise: Promise) {
        try {
            val settings = CallGuardStore.read(reactContext)
            val map = Arguments.createMap().apply {
                putBoolean("protectionEnabled", settings.protectionEnabled)
                putBoolean("serviceRunning", CallDetectionService.isRunning())
                putBoolean("screeningRoleHeld", GuardCapabilities.screeningRoleHeld(reactContext))
                putBoolean("screeningRoleAvailable", GuardCapabilities.screeningRoleAvailable(reactContext))
                putBoolean("batteryUnrestricted", GuardCapabilities.batteryUnrestricted(reactContext))
                putBoolean("notificationsEnabled", GuardCapabilities.notificationsEnabled(reactContext))
                putBoolean("phonePermissionGranted", GuardCapabilities.phonePermissionGranted(reactContext))
                putString("localProvince", settings.localProvince)
                putBoolean("autoBlock", settings.autoBlock)
                putArray("whitelist", stringArray(settings.whitelist))
                putArray("blacklist", stringArray(settings.blacklist))
                putBoolean("enableNotification", settings.enableNotification)
                putBoolean("enableLog", settings.enableLog)
                putBoolean("batteryPromptDismissed", settings.batteryPromptDismissed)
                putBoolean("autostartOpened", settings.autostartOpened)
                putBoolean("hasPersistedProvince", settings.hasPersistedProvince)
                putBoolean("hasPersistedLists", settings.hasPersistedLists)
                putBoolean("hasPersistedAutoBlock", settings.hasPersistedAutoBlock)
                putBoolean("hasPersistedProtection", settings.hasPersistedProtection)
                putBoolean("hasPersistedGuidance", settings.hasPersistedGuidance)
                putInt("blockedCount", settings.blockedCount)
                putString("lastBlockedNumber", settings.lastBlockedNumber)
                putDouble("lastBlockedAt", settings.lastBlockedAt.toDouble())
                putString("lastServiceError", settings.lastServiceError)
                putInt("sdkInt", Build.VERSION.SDK_INT)
            }
            promise.resolve(map)
        } catch (error: Exception) {
            promise.reject("ERROR", error.message)
        }
    }

    @ReactMethod
    fun requestCallScreeningRole(promise: Promise) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                promise.resolve(false)
                return
            }
            if (GuardCapabilities.screeningRoleHeld(reactContext)) {
                promise.resolve(true)
                return
            }
            val activity = reactContext.currentActivity
            if (activity == null) {
                promise.reject("NO_ACTIVITY", "请先打开应用")
                return
            }
            val roleManager = activity.getSystemService(RoleManager::class.java)
            if (roleManager == null || !roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
                promise.resolve(false)
                return
            }
            rolePromise?.resolve(false)
            rolePromise = promise
            val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
            activity.startActivityForResult(intent, REQUEST_CALL_SCREENING_ROLE)
        } catch (error: Exception) {
            rolePromise = null
            promise.reject("ERROR", error.message)
        }
    }

    @ReactMethod
    fun requestIgnoreBatteryOptimizations(promise: Promise) {
        val activity = reactContext.currentActivity
        if (activity == null) {
            promise.reject("NO_ACTIVITY", "请先打开应用")
            return
        }
        if (GuardCapabilities.batteryUnrestricted(activity)) {
            promise.resolve("already")
            return
        }
        promise.resolve(OemSettings.requestIgnoreBatteryOptimizations(activity))
    }

    @ReactMethod
    fun openAutoStartSettings(promise: Promise) {
        val activity = reactContext.currentActivity
        if (activity == null) {
            promise.reject("NO_ACTIVITY", "请先打开应用")
            return
        }
        promise.resolve(OemSettings.openAutoStart(activity))
    }

    @ReactMethod
    fun addListener(eventName: String) {
        // NativeEventEmitter 需要这两个方法存在。
    }

    @ReactMethod
    fun removeListeners(count: Int) {
        // NativeEventEmitter 需要这两个方法存在。
    }

    override fun invalidate() {
        cleanup()
        super.invalidate()
    }

    private fun cleanup() {
        if (cleanedUp) return
        cleanedUp = true
        rolePromise?.resolve(false)
        rolePromise = null
        reactContext.removeActivityEventListener(activityListener)
        unregisterBlockReceiver()
    }

    private fun registerBlockReceiver() {
        if (blockReceiverRegistered) return
        val filter = IntentFilter(IncomingCallGuard.ACTION_CALL_BLOCKED)
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                reactContext.registerReceiver(blockReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                reactContext.registerReceiver(blockReceiver, filter)
            }
            blockReceiverRegistered = true
        } catch (error: Exception) {
            Log.e(TAG, "register block receiver failed", error)
        }
    }

    private fun unregisterBlockReceiver() {
        if (!blockReceiverRegistered) return
        try {
            reactContext.unregisterReceiver(blockReceiver)
        } catch (_: Exception) {
            // already unregistered
        }
        blockReceiverRegistered = false
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(reactContext, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun stringArray(values: Collection<String>): com.facebook.react.bridge.WritableArray {
        val array = Arguments.createArray()
        values.forEach { array.pushString(it) }
        return array
    }

    private fun ReadableArray.toStringList(): List<String> {
        val values = ArrayList<String>(size())
        for (index in 0 until size()) {
            val value = getString(index)
            if (!value.isNullOrBlank()) values.add(value)
        }
        return values
    }

    private fun sendEvent(eventName: String, params: com.facebook.react.bridge.WritableMap) {
        if (!reactContext.hasActiveReactInstance()) return
        try {
            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit(eventName, params)
        } catch (error: Exception) {
            Log.w(TAG, "send event failed", error)
        }
    }

    companion object {
        private const val TAG = "PhoneGuard"
        private const val REQUEST_CALL_SCREENING_ROLE = 4101
    }
}
