package com.agrotech.oviapp.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.agrotech.oviapp.android.ui.HomeScreen
import com.agrotech.oviapp.android.ui.LoginScreen
import com.agrotech.oviapp.auth.data.AndroidEncryptedSessionStorage
import com.agrotech.oviapp.auth.data.KtorAuthService
import com.agrotech.oviapp.auth.domain.AuthRepository
import com.agrotech.oviapp.auth.domain.AuthSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val BaappPrimaryGreen = Color(0xFF1B5E20)

class MainActivity : ComponentActivity() {

    private var authRepository: AuthRepository? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface {
                    MainApp(
                        onInitRepository = {
                            val sessionStorage = AndroidEncryptedSessionStorage(applicationContext)
                            val authService = KtorAuthService()
                            val repository = AuthRepository(authService, sessionStorage)
                            authRepository = repository

                            val cachedSession = repository.getCachedSession()
                            repository to cachedSession
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        authRepository?.close()
    }
}

private sealed interface InitialState {
    data object Loading : InitialState
    data class Ready(val repository: AuthRepository, val cachedSession: AuthSession?) : InitialState
}

@Composable
private fun MainApp(
    onInitRepository: suspend () -> Pair<AuthRepository, AuthSession?>
) {
    var state by remember { mutableStateOf<InitialState>(InitialState.Loading) }

    LaunchedEffect(Unit) {
        val (repo, cachedSession) = withContext(Dispatchers.IO) {
            onInitRepository()
        }
        state = InitialState.Ready(repo, cachedSession)
    }

    when (val current = state) {
        is InitialState.Loading -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFFF7F9F7)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    color = BaappPrimaryGreen,
                    strokeWidth = 3.dp
                )
            }
        }
        is InitialState.Ready -> {
            AppNavigation(
                authRepository = current.repository,
                initialSession = current.cachedSession
            )
        }
    }
}

@Composable
fun AppNavigation(
    authRepository: AuthRepository,
    initialSession: AuthSession?
) {
    var session by remember { mutableStateOf<AuthSession?>(initialSession) }
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
