package com.phoneguard

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.app.role.RoleManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat

object GuardCapabilities {
    fun screeningRoleAvailable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val roleManager = context.getSystemService(RoleManager::class.java) ?: return false
        return roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)
    }

    fun screeningRoleHeld(context: Context): Boolean {
        if (!screeningRoleAvailable(context)) return false
        val roleManager = context.getSystemService(RoleManager::class.java) ?: return false
        return roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
    }

    fun batteryUnrestricted(context: Context): Boolean {
        val powerManager = context.getSystemService(PowerManager::class.java) ?: return false
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun notificationsEnabled(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager != null && !manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= 33) {
            return hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        }
        return true
    }

    fun phonePermissionGranted(context: Context): Boolean {
        val phoneState = hasPermission(context, Manifest.permission.READ_PHONE_STATE)
        val answer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            hasPermission(context, Manifest.permission.ANSWER_PHONE_CALLS)
        } else {
            true
        }
        return phoneState && answer
    }

    fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}
