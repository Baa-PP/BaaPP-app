package com.agrotech.oviapp.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.agrotech.oviapp.android.ui.HomeScreen
import com.agrotech.oviapp.android.ui.LoginScreen
import com.agrotech.oviapp.auth.data.AndroidEncryptedSessionStorage
import com.agrotech.oviapp.auth.data.KtorAuthService
import com.agrotech.oviapp.auth.domain.AuthRepository
import com.agrotech.oviapp.auth.domain.AuthSession
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var authRepository: AuthRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize secure storage backed by Android Keystore (EncryptedSharedPreferences)
        val sessionStorage = AndroidEncryptedSessionStorage(applicationContext)
        val authService = KtorAuthService()
        authRepository = AuthRepository(authService, sessionStorage)

        setContent {
            MaterialTheme {
                Surface {
                    AppNavigation(authRepository = authRepository)
                }
            }
        }
    }
}

@Composable
fun AppNavigation(authRepository: AuthRepository) {
    var session by remember { mutableStateOf<AuthSession?>(authRepository.getCachedSession()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    if (session != null) {
        HomeScreen(
            session = session!!,
            onLogoutClick = {
                authRepository.logout()
                session = null
                errorMessage = null
            }
        )
    } else {
        LoginScreen(
            isLoading = isLoading,
            errorMessage = errorMessage,
            onLoginClick = { user, pass ->
                isLoading = true
                errorMessage = null
                scope.launch {
                    val result = authRepository.login(user, pass)
                    isLoading = false
                    result.fold(
                        onSuccess = { newSession ->
                            session = newSession
                        },
                        onFailure = { error ->
                            errorMessage = error.message ?: "No se pudo iniciar sesión. Inténtalo de nuevo."
                        }
                    )
                }
            }
        )
    }
}
