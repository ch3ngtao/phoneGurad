package com.phoneguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机或更新后，如果用户开过防护，把常驻通知拉起来。
 * 来电拦截本身不依赖这个接收器，系统会在来电时绑定来电筛选服务。
 */
class GuardBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        if (!CallGuardStore.isProtectionEnabled(context)) return
        Log.i(TAG, "restart guard after $action")
        CallDetectionService.start(context)
    }

    companion object {
        private const val TAG = "PhoneGuard"
    }
}
