package com.phoneguard

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log

/**
 * 系统在来电时绑定此服务，即使界面已退出、进程刚被杀掉也会拉起。
 * 必须在几秒内 respondToCall。失败时放行，避免误伤亲友。
 */
class PhoneGuardCallScreeningService : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        try {
            if (!isIncoming(callDetails)) {
                respondToCall(callDetails, allow())
                return
            }
            val number = callDetails.handle?.schemeSpecificPart ?: ""
            val block = IncomingCallGuard.shouldBlock(this, number)
            respondToCall(callDetails, if (block) reject() else allow())
            if (block) {
                try {
                    IncomingCallGuard.onBlocked(this, number)
                } catch (recordError: Exception) {
                    Log.e(TAG, "record block failed", recordError)
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "screen failed, allowing call", error)
            try {
                respondToCall(callDetails, allow())
            } catch (nested: Exception) {
                Log.e(TAG, "allow response failed", nested)
            }
        }
    }

    private fun isIncoming(callDetails: Call.Details): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return callDetails.callDirection == Call.Details.DIRECTION_INCOMING
    }

    private fun allow(): CallResponse {
        return CallResponse.Builder().build()
    }

    private fun reject(): CallResponse {
        return CallResponse.Builder()
            .setDisallowCall(true)
            .setRejectCall(true)
            .setSkipCallLog(false)
            .setSkipNotification(false)
            .build()
    }

    companion object {
        private const val TAG = "PhoneGuard"
    }
}
