package com.chase.tidetoolvoicelog.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.chase.tidetoolvoicelog.R
import com.chase.tidetoolvoicelog.ui.MainActivity
import com.chase.tidetoolvoicelog.ui.PromptActivity

/**
 * The prompt itself. Deliberately dumb: one high-priority notification that
 * survives the whole call, with a full-screen intent for the locked phone.
 */
object CallPrompt {

    const val CHANNEL_PROMPT = "prompt"
    const val CHANNEL_IMPORT = "import"
    const val NOTIF_ID = 4201

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        val prompt = NotificationChannel(
            CHANNEL_PROMPT,
            context.getString(R.string.channel_prompt_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.channel_prompt_desc)
            setShowBadge(true)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 250, 150, 250)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(false)
        }
        nm.createNotificationChannel(prompt)

        val imports = NotificationChannel(
            CHANNEL_IMPORT,
            context.getString(R.string.channel_import_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = context.getString(R.string.channel_import_desc) }
        nm.createNotificationChannel(imports)
    }

    /** "Business call - tap Record in Samsung Phone", plus who is calling. */
    fun show(context: Context, caller: String) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return

        val fullScreen = PendingIntent.getActivity(
            context, 1,
            Intent(context, PromptActivity::class.java)
                .putExtra(PromptActivity.EXTRA_CALLER, caller)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openPhone = PendingIntent.getBroadcast(
            context, 2,
            Intent(context, PromptReceiver::class.java).setAction(PromptReceiver.ACTION_OPEN_PHONE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dismiss = PendingIntent.getBroadcast(
            context, 3,
            Intent(context, PromptReceiver::class.java).setAction(PromptReceiver.ACTION_DISMISS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openApp = PendingIntent.getActivity(
            context, 4,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val body = if (caller.isBlank()) "Business line is ringing" else caller

        val n = NotificationCompat.Builder(context, CHANNEL_PROMPT)
            .setSmallIcon(R.drawable.ic_stat_prompt)
            .setContentTitle("Business call - tap Record in Samsung Phone")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "$body\n\nAnswer, then tap Record in the Samsung Phone app. Recording is announced to the caller."
            ))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(false)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(openApp)
            .addAction(0, "Open Phone", openPhone)
            .addAction(0, "Dismiss", dismiss)
            .build()

        try {
            nm.notify(NOTIF_ID, n)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted - nothing we can do but keep the log clean
        }
    }

    /**
     * The prompt has to outlive the ring, because Samsung's Record button only
     * exists in the in-call screen - so it is dismissed when the call ends, not
     * when it is answered.
     */
    fun dismiss(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIF_ID)
    }
}
