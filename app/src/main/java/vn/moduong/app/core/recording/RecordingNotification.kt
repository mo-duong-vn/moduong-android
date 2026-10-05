package vn.moduong.app.core.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import vn.moduong.app.MainActivity
import vn.moduong.app.R

internal class RecordingNotification(private val context: Context) {
    init {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_description)
            setSound(null, null)
            enableVibration(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun build(sessionId: String, phase: RecordingPhase, detail: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            sessionId.hashCode(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            context,
            sessionId.hashCode() xor STOP_REQUEST_CODE,
            Intent(context, RideRecordingService::class.java)
                .setAction(RideRecordingService.ACTION_STOP)
                .putExtra(RideRecordingService.EXTRA_SESSION_ID, sessionId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = when (phase) {
            RecordingPhase.STARTING -> context.getString(R.string.notification_starting)
            RecordingPhase.FINALIZING -> context.getString(R.string.notification_finalizing)
            else -> context.getString(R.string.notification_recording_title)
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_recording)
            .setContentTitle(title)
            .setContentText(detail)
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(phase in ACTIVE_PHASES)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .apply {
                if (phase in ACTIVE_PHASES) {
                    addAction(R.drawable.ic_stat_recording, context.getString(R.string.notification_stop), stopIntent)
                }
            }
            .build()
    }

    fun update(sessionId: String, phase: RecordingPhase, detail: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            NotificationManagerCompat.from(context).notify(
                NOTIFICATION_ID,
                build(sessionId, phase, detail),
            )
        } catch (error: SecurityException) {
            Log.w(TAG, "Notification permission unavailable during recording")
        }
    }

    companion object {
        const val NOTIFICATION_ID = 4110
        private const val TAG = "MoDuongNotification"
        private const val CHANNEL_ID = "recording_session"
        private const val STOP_REQUEST_CODE = 913
        private val ACTIVE_PHASES = setOf(
            RecordingPhase.STARTING,
            RecordingPhase.RECORDING,
            RecordingPhase.FINALIZING,
        )
    }
}
