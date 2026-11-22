package com.agrotech.oviapp.auth.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.agrotech.oviapp.auth.domain.AuthSession
import com.agrotech.oviapp.auth.domain.SessionStorage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AndroidEncryptedSessionStorage(context: Context) : SessionStorage {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    override fun saveSession(session: AuthSession) = synchronized(lock) {
        writeSessionLocked(session)
    }

    override fun getSession(): AuthSession? = synchronized(lock) {
        readSessionLocked()
    }

    override fun clearSession() = synchronized(lock) {
        prefs.edit().clear().apply()
    }

    override fun hasSession(): Boolean = synchronized(lock) {
        !prefs.getString(KEY_ACCESS_TOKEN, null).isNullOrBlank()
    }

    override fun updateSessionIfMatches(
        expectedSessionId: String,
        expectedRefreshToken: String?,
        transform: (AuthSession) -> AuthSession
    ): Boolean = synchronized(lock) {
        val current = readSessionLocked() ?: return false
        if (current.sessionId != expectedSessionId || current.refreshToken != expectedRefreshToken) {
            return false
        }
        writeSessionLocked(transform(current))
        true
    }

    override fun clearSessionIfMatches(
        expectedSessionId: String,
        expectedRefreshToken: String?
    ): Boolean = synchronized(lock) {
        val current = readSessionLocked() ?: return false
        if (current.sessionId != expectedSessionId || current.refreshToken != expectedRefreshToken) {
            return false
        }
        prefs.edit().clear().apply()
        true
    }

    private fun writeSessionLocked(session: AuthSession) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, session.accessToken)
            .putString(KEY_REFRESH_TOKEN, session.refreshToken)
            .putString(KEY_USERNAME, session.username)
            .putString(KEY_ROLE, session.role)
            .putString(KEY_RANCH_ID, session.ranchId)
            .putString(KEY_PERMISSIONS, json.encodeToString(session.permissions))
            .putString(KEY_SESSION_ID, session.sessionId)
            .apply()
    }

    private fun readSessionLocked(): AuthSession? {
        val token = prefs.getString(KEY_ACCESS_TOKEN, null)
        if (token.isNullOrBlank()) return null

        val username = prefs.getString(KEY_USERNAME, "") ?: ""
        val role = prefs.getString(KEY_ROLE, "WORKER_BRANCH") ?: "WORKER_BRANCH"
        val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null)
        val ranchId = prefs.getString(KEY_RANCH_ID, null)
        val permString = prefs.getString(KEY_PERMISSIONS, null)
        val permissions = if (!permString.isNullOrBlank()) {
            try {
                json.decodeFromString<Map<String, Boolean>>(permString)
            } catch (_: Exception) {
                emptyMap()
            }
        } else {
            emptyMap()
        }
        val sessionId = prefs.getString(KEY_SESSION_ID, null) ?: "${username}_${token.hashCode()}"

        return AuthSession(
            accessToken = token,
            refreshToken = refreshToken,
            username = username,
            role = role,
            ranchId = ranchId,
            permissions = permissions,
            sessionId = sessionId
        )
    }

    companion object {
        private const val PREFS_NAME = "baapp_secure_session_prefs"
        private const val KEY_ACCESS_TOKEN = "sec_jwt_token"
        private const val KEY_REFRESH_TOKEN = "sec_refresh_token"
        private const val KEY_USERNAME = "sec_username"
        private const val KEY_ROLE = "sec_role"
        private const val KEY_RANCH_ID = "sec_ranch_id"
        private const val KEY_PERMISSIONS = "sec_permissions_json"
        private const val KEY_SESSION_ID = "sec_session_id"
    }
}
