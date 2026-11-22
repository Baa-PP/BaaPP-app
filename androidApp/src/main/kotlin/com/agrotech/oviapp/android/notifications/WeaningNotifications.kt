package com.agrotech.oviapp.android.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.agrotech.oviapp.android.MainActivity
import com.agrotech.oviapp.android.R
import com.agrotech.oviapp.notifications.WeaningAlert

object WeaningNotifications {
    const val CHANNEL_ID = "destetes"
    private const val NOTIFICATION_ID = 1

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.canal_destetes),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.canal_destetes_descripcion) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun show(context: Context, alert: WeaningAlert, title: String?, body: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val folio = alert.folioInterno
        val text = body ?: if (folio != null) {
            context.getString(R.string.destete_texto_folio, folio)
        } else {
            context.getString(R.string.destete_texto)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notificacion_destete)
            .setContentTitle(title ?: context.getString(R.string.destete_titulo))
            .setContentText(text)
            .setContentIntent(tapIntent(context, alert))
            .setAutoCancel(true)
            .build()
        // La identidad completa rancho/animal es el tag: un segundo aviso del mismo cordero reemplaza al
        // anterior, y dos corderos distintos nunca chocan (un hashCode sí podría repetirse).
        NotificationManagerCompat.from(context).notify(alert.identity(), NOTIFICATION_ID, notification)
    }

    /** Sirve tanto para el intent que arma show() como para el que arma Android al tocar un push en segundo plano. */
    fun alertFrom(intent: Intent?): WeaningAlert? {
        val extras = intent?.extras ?: return null
        return WeaningAlert.fromData(extras.keySet().associateWith { extras.getString(it) })
    }

    private fun WeaningAlert.identity(): String = "$ranchId/$animalId"

    private fun tapIntent(context: Context, alert: WeaningAlert): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            // Los extras no distinguen PendingIntents; la URI sí, así cada cordero conserva el suyo.
            data = Uri.parse("baapp://destete/${alert.identity()}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            alert.toData().forEach { (key, value) -> putExtra(key, value) }
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
