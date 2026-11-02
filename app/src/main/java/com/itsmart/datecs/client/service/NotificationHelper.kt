package com.itsmart.datecs.client.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.itsmart.datecs.client.MainActivity
import com.itsmart.datecs.client.R

/**
 * Notificari pentru ITSmart Client:
 *   - "revizie_alerts": comenzi noi din CRM (REVIZIE, etc.) — heads-up cu sunet + vibratie
 *   - "z_required":      atentionare urgenta ca user-ul trebuie sa tipareasca raport Z
 *   - "agent_status":    service-ul de polling in background (low importance, doar foreground)
 */
object NotificationHelper {
    const val CH_ALERTS = "revizie_alerts"
    const val CH_Z_REQUIRED = "z_required"
    const val CH_AGENT = "agent_status"

    const val NOTIF_ID_COMMAND = 1001
    const val NOTIF_ID_Z_WARNING = 1002
    const val NOTIF_ID_FOREGROUND = 1003

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Canal comenzi (sunet default + vibratie + heads-up).
        val alerts = NotificationChannel(CH_ALERTS, "Comenzi CRM", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Notificari pentru comenzi remote (revizie, export, etc.)"
            enableLights(true); lightColor = 0xFF2563EB.toInt()
            enableVibration(true); vibrationPattern = longArrayOf(0, 250, 200, 250)
            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            setSound(soundUri, attrs)
        }
        nm.createNotificationChannel(alerts)

        // Canal Z required (alarm-like — sunet alarm + vibratie agresiva).
        val zWarn = NotificationChannel(CH_Z_REQUIRED, "Tiparire Z necesara", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Atentionari urgente: aparatul are incasari neraportate"
            enableLights(true); lightColor = 0xFFEF4444.toInt()
            enableVibration(true); vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 400)
            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            setSound(soundUri, attrs)
        }
        nm.createNotificationChannel(zWarn)

        // Canal foreground (silent, vizibil dar non-intrusive).
        val agent = NotificationChannel(CH_AGENT, "Agent polling", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Service-ul care verifica periodic comenzi din CRM"
            setSound(null, null); enableVibration(false)
        }
        nm.createNotificationChannel(agent)
    }

    /** Extra pe intentul notificarii: aplicatia se deschide direct pe ecranul Mesaje (revizie / comenzi CRM). */
    const val EXTRA_OPEN_MESSAGES = "open_messages"

    private fun openAppIntent(context: Context, messages: Boolean = false): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (messages) putExtra(EXTRA_OPEN_MESSAGES, true)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getActivity(context, if (messages) 1 else 0, intent, flags)
    }

    /** Notificare heads-up: noua comanda CRM (revizie, export, etc.). */
    fun showCommandAvailable(context: Context, title: String, body: String) {
        ensureChannels(context)
        val n = NotificationCompat.Builder(context, CH_ALERTS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context, messages = true))
            .build()
        try {
            androidx.core.app.NotificationManagerCompat.from(context).notify(NOTIF_ID_COMMAND, n)
        } catch (_: SecurityException) { /* fara permisiune POST_NOTIFICATIONS pe Android 13+ */ }
    }

    /** Notificare urgenta: aparatul are incasari neraportate, trebuie Z. */
    fun showZRequired(context: Context, serial: String, openReceipt: Boolean) {
        ensureChannels(context)
        val title = "Atentie: tipariti raport Z"
        val body = if (openReceipt)
            "Aparatul $serial are un bon deschis. Tipariti raportul Z inainte de revizie."
        else
            "Aparatul $serial are incasari neraportate. Tipariti raportul Z inainte de revizie."
        val n = NotificationCompat.Builder(context, CH_Z_REQUIRED)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()
        try {
            androidx.core.app.NotificationManagerCompat.from(context).notify(NOTIF_ID_Z_WARNING, n)
        } catch (_: SecurityException) {}
    }

    /** Notificare foreground (low) pentru service-ul de polling. */
    fun foregroundNotification(context: Context, statusText: String): android.app.Notification {
        ensureChannels(context)
        return NotificationCompat.Builder(context, CH_AGENT)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("IT Smart Client — agent activ")
            .setContentText(statusText)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openAppIntent(context))
            .build()
    }
}
