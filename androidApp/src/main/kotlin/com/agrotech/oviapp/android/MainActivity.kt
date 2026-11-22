package com.agrotech.oviapp.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.agrotech.oviapp.android.ui.HomeScreen
import com.agrotech.oviapp.android.ui.LoginScreen
import com.agrotech.oviapp.auth.data.AndroidEncryptedSessionStorage
import com.agrotech.oviapp.auth.data.KtorAuthService
import com.agrotech.oviapp.auth.domain.AuthRepository
import com.agrotech.oviapp.auth.domain.AuthSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val BaappPrimaryGreen = Color(0xFF1B5E20)

class MainActivity : ComponentActivity() {

    private val repositoryLock = Any()
    private var isDestroyed = false
    private var activeRepository: AuthRepository? = null

    /**
     * Synchronizes repository ownership between initialization and destruction.
     * If destruction occurred before publication, closes the repository immediately.
     */
    private fun publishOrClose(repository: AuthRepository): Boolean {
        synchronized(repositoryLock) {
            if (isDestroyed) {
                repository.close()
                return false
            }
            activeRepository = repository
            return true
        }
    }

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

                            var publicationKept = false
                            try {
                                val cachedSession = repository.getCachedSession()
                                val published = publishOrClose(repository)
                                if (published) {
                                    publicationKept = true
                                    repository to cachedSession
                                } else {
                                    null
                                }
                            } finally {
                                if (!publicationKept) {
                                    repository.close()
                                }
                            }
                        },
                        onResetStorage = {
                            try {
                                applicationContext.deleteSharedPreferences("baapp_secure_session_prefs")
                            } catch (_: Exception) {}
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val repositoryToClose = synchronized(repositoryLock) {
            isDestroyed = true
            activeRepository.also { activeRepository = null }
        }
        repositoryToClose?.close()
    }
}

private sealed interface InitialState {
    data object Loading : InitialState
    data class Ready(val repository: AuthRepository, val cachedSession: AuthSession?) : InitialState
    data class Error(val message: String) : InitialState
}

@Composable
private fun MainApp(
    onInitRepository: suspend () -> Pair<AuthRepository, AuthSession?>?,
    onResetStorage: suspend () -> Unit
) {
    var state by remember { mutableStateOf<InitialState>(InitialState.Loading) }
    var retryKey by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(retryKey) {
        state = InitialState.Loading
        try {
            val result = withContext(Dispatchers.IO) {
                onInitRepository()
            }
            if (result != null) {
                state = InitialState.Ready(result.first, result.second)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            state = InitialState.Error(
                message = e.message ?: "No se pudo inicializar el almacenamiento seguro."
            )
        }
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
        is InitialState.Error -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFFF7F9F7))
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Aviso de Almacenamiento",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFB71C1C)
                            )
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = current.message,
                            style = MaterialTheme.typography.bodyMedium.copy(color = Color.DarkGray),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = { retryKey++ },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = BaappPrimaryGreen)
                        ) {
                            Text("Reintentar Inicio", color = Color.White)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        onResetStorage()
                                    }
                                    retryKey++
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Restablecer Sesión", color = Color(0xFFB71C1C))
                        }
                    }
                }
            }
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
