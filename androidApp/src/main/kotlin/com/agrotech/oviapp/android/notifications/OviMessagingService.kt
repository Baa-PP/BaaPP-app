package com.agrotech.oviapp.android.notifications

import com.agrotech.oviapp.notifications.WeaningAlert
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class OviMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        Notifications.registrar(applicationContext).onNewToken(token)
        DeviceTokenWorker.enqueue(applicationContext)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val alert = WeaningAlert.fromData(message.data) ?: return
        WeaningNotifications.show(applicationContext, alert, message.notification?.title, message.notification?.body)
    }
}
