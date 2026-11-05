package com.phoneguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

object GuardNotifier {
    const val ONGOING_CHANNEL_ID = "phoneguard_ongoing"
    const val BLOCK_CHANNEL_ID = "phoneguard_blocked"
    const val ONGOING_NOTIFICATION_ID = 1001
    private const val BLOCK_NOTIFICATION_ID = 1002

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val ongoing = NotificationChannel(
            ONGOING_CHANNEL_ID,
            "防护状态",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "显示异地禁呼是否还在拦截外地陌生来电"
            setShowBadge(false)
        }
        val blocked = NotificationChannel(
            BLOCK_CHANNEL_ID,
            "拦截记录",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "拦住外地陌生来电时提醒一次"
            setShowBadge(false)
        }
        manager.createNotificationChannel(ongoing)
        manager.createNotificationChannel(blocked)
    }

    fun buildOngoing(context: Context): Notification {
        ensureChannels(context)
        val settings = CallGuardStore.read(context)
        val text = ongoingText(context, settings)
        val builder = NotificationCompat.Builder(context, ONGOING_CHANNEL_ID)
            .setContentTitle("异地禁呼正在防护")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openAppIntent(context))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.foregroundServiceBehavior = NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE
        }
        return builder.build()
    }

    fun onBlocked(context: Context, normalizedNumber: String) {
        if (CallDetectionService.isRunning()) {
            CallDetectionService.refreshIfRunning(context)
            return
        }
        showStandaloneBlock(context, normalizedNumber)
    }

    fun userVisibleStartError(error: Exception): String {
        val name = error.javaClass.simpleName
        return if (name.contains("ForegroundServiceStartNotAllowed")) {
            "系统暂时不允许后台常驻通知。若已允许来电过滤，外地陌生电话仍会拦住。请打开应用后再试一次。"
        } else {
            "常驻通知没有启动。若已允许来电过滤，外地陌生电话仍会拦住。请再点一次「打开防护」。"
        }
    }

    private fun showStandaloneBlock(context: Context, normalizedNumber: String) {
        if (!GuardCapabilities.notificationsEnabled(context)) return
        ensureChannels(context)
        val text = "已拦住一个外地来电，${CallGuardPolicy.redact(normalizedNumber)}。"
        val notification = NotificationCompat.Builder(context, BLOCK_CHANNEL_ID)
            .setContentTitle("已拦住外地来电")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(openAppIntent(context))
            .build()
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.notify(BLOCK_NOTIFICATION_ID, notification)
    }

    private fun ongoingText(context: Context, settings: CallGuardStore.Settings): String {
        if (settings.localProvince.isBlank()) {
            return "请先填写家乡。不填就不会拦截。"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            GuardCapabilities.screeningRoleAvailable(context) &&
            !GuardCapabilities.screeningRoleHeld(context)
        ) {
            return "请允许来电过滤，否则退出后拦不住。"
        }
        val blocked = if (settings.blockedCount > 0) {
            "已经拦住 ${settings.blockedCount} 个。"
        } else {
            "还没有拦住电话。"
        }
        return "没存进通讯录的外地号会被拦住。已存的人，以及同省电话，会打进来。座机按区号算。$blocked"
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(context, 0, intent, flags)
    }
}
