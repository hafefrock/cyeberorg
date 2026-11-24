package com.example.terminal

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R

enum class NotificationPermissionState {
    NOT_REQUIRED,
    GRANTED,
    NEEDS_PERMISSION
}

enum class LongCommandNotificationStatus {
    DISABLED,
    READY,
    NEEDS_PERMISSION
}

object LongCommandNotifier {
    const val CHANNEL_ID: String = "long_command_alerts"
    const val EXTRA_TARGET_SESSION_ID: String = "extra_target_session_id"
    const val MAX_COMMAND_PREVIEW_CHARS: Int = 60

    fun shouldNotify(
        durationMs: Long,
        thresholdMs: Long,
        isForeground: Boolean,
        enabled: Boolean
    ): Boolean {
        if (!enabled || isForeground) return false
        if (thresholdMs <= 0L || durationMs <= 0L) return false
        return durationMs > thresholdMs
    }

    fun formatCommandPreview(command: String): String {
        val collapsed = command
            .replace('\n', ' ')
            .replace('\r', ' ')
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        return if (collapsed.length <= MAX_COMMAND_PREVIEW_CHARS) {
            collapsed
        } else {
            collapsed.take(MAX_COMMAND_PREVIEW_CHARS - 1).trimEnd() + "…"
        }
    }

    fun resolvePermissionState(
        sdkInt: Int,
        hasPostNotificationsPermission: Boolean
    ): NotificationPermissionState {
        return when {
            sdkInt < Build.VERSION_CODES.TIRAMISU -> NotificationPermissionState.NOT_REQUIRED
            hasPostNotificationsPermission -> NotificationPermissionState.GRANTED
            else -> NotificationPermissionState.NEEDS_PERMISSION
        }
    }

    fun getPermissionState(context: Context): NotificationPermissionState {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return resolvePermissionState(Build.VERSION.SDK_INT, granted)
    }

    fun resolveSettingStatus(
        enabled: Boolean,
        permissionState: NotificationPermissionState
    ): LongCommandNotificationStatus {
        return when {
            !enabled -> LongCommandNotificationStatus.DISABLED
            permissionState == NotificationPermissionState.NEEDS_PERMISSION ->
                LongCommandNotificationStatus.NEEDS_PERMISSION
            else -> LongCommandNotificationStatus.READY
        }
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val existing = nm.getNotificationChannel(CHANNEL_ID)
        val name = context.getString(R.string.notif_channel_long_cmd_name)
        val descriptionText = context.getString(R.string.notif_channel_long_cmd_desc)
        if (existing != null) {
            existing.name = name
            existing.description = descriptionText
            return
        }
        val channel = NotificationChannel(
            CHANNEL_ID,
            name,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = descriptionText
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    fun notifyCommandCompleted(
        context: Context,
        sessionId: String,
        sessionTitle: String,
        command: String,
        exitCode: Int
    ) {
        if (getPermissionState(context) == NotificationPermissionState.NEEDS_PERMISSION) return
        ensureChannel(context)

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_TARGET_SESSION_ID, sessionId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            sessionId.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = sessionTitle.ifBlank { context.getString(R.string.app_name) }
        val preview = formatCommandPreview(command)
        val body = context.getString(R.string.notif_long_command_body, preview, exitCode)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(sessionId.hashCode(), notification)
        }
    }
}
