package com.agrotech.oviapp.android

import android.app.Application
import com.agrotech.oviapp.android.notifications.DeviceTokenWorker
import com.agrotech.oviapp.android.notifications.Notifications
import com.agrotech.oviapp.android.notifications.WeaningNotifications
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging

class OviApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        WeaningNotifications.createChannel(this)
        // Sin google-services.json Firebase no se inicializa; la app sigue funcionando sin pushes.
        if (FirebaseApp.getApps(this).isEmpty()) return
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            Notifications.registrar(this).onNewToken(token)
            DeviceTokenWorker.enqueue(this)
        }
    }
}
