package com.agrotech.oviapp.android.notifications

import android.content.Context
import com.agrotech.oviapp.notifications.DeviceTokenStore

class SharedPrefsDeviceTokenStore(context: Context) : DeviceTokenStore {
    private val prefs = context.getSharedPreferences("device_token", Context.MODE_PRIVATE)

    override var pendingToken: String?
        get() = prefs.getString(KEY_PENDING, null)
        set(value) = prefs.edit().putString(KEY_PENDING, value).apply()

    override var registeredToken: String?
        get() = prefs.getString(KEY_REGISTERED, null)
        set(value) = prefs.edit().putString(KEY_REGISTERED, value).apply()

    private companion object {
        const val KEY_PENDING = "pending"
        const val KEY_REGISTERED = "registered"
    }
}
