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
import com.agrotech.oviapp.android.ui.AnimalFormScreen
import com.agrotech.oviapp.android.ui.HomeScreen
import com.agrotech.oviapp.android.ui.InventoryScreen
import com.agrotech.oviapp.android.ui.LoginScreen
import com.agrotech.oviapp.animals.data.SqlDelightAnimalCommandRepository
import com.agrotech.oviapp.animals.data.SqlDelightAnimalInventoryRepository
import com.agrotech.oviapp.animals.domain.repository.AnimalCommandRepository
import com.agrotech.oviapp.animals.domain.repository.AnimalInventoryRepository
import com.agrotech.oviapp.auth.data.AndroidEncryptedSessionStorage
import com.agrotech.oviapp.auth.data.KtorAuthService
import com.agrotech.oviapp.auth.domain.AuthRepository
import com.agrotech.oviapp.auth.domain.AuthSession
import com.agrotech.oviapp.db.DatabaseDriverFactory
import com.agrotech.oviapp.db.createDatabase
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
                                val driverFactory = DatabaseDriverFactory(applicationContext)
                                val database = createDatabase(driverFactory)
                                val inventoryRepo = SqlDelightAnimalInventoryRepository(database)
                                val commandRepo = SqlDelightAnimalCommandRepository(database)

                                val cachedSession = repository.getCachedSession()
                                val published = publishOrClose(repository)
                                if (published) {
                                    publicationKept = true
                                    AppDependencies(repository, inventoryRepo, commandRepo, cachedSession)
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
                            applicationContext.deleteSharedPreferences("baapp_secure_session_prefs")
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

private data class AppDependencies(
    val authRepository: AuthRepository,
    val inventoryRepository: AnimalInventoryRepository,
    val commandRepository: AnimalCommandRepository,
    val cachedSession: AuthSession?
)

private sealed interface InitialState {
    data object Loading : InitialState
    data class Ready(
        val dependencies: AppDependencies
    ) : InitialState
    data class Error(val message: String) : InitialState
}

@Composable
private fun MainApp(
    onInitRepository: suspend () -> AppDependencies?,
    onResetStorage: suspend () -> Boolean
) {
    var state by remember { mutableStateOf<InitialState>(InitialState.Loading) }
    var retryKey by remember { mutableStateOf(0) }
    var isBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(retryKey) {
        state = InitialState.Loading
        try {
            val result = withContext(Dispatchers.IO) {
                onInitRepository()
            }
            if (result != null) {
                state = InitialState.Ready(result)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            state = InitialState.Error(
                message = e.message ?: "No se pudo inicializar el almacenamiento seguro."
            )
        } finally {
            isBusy = false
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
                authRepository = current.dependencies.authRepository,
                inventoryRepository = current.dependencies.inventoryRepository,
                commandRepository = current.dependencies.commandRepository,
                initialSession = current.dependencies.cachedSession
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
                            onClick = {
                                if (!isBusy) {
                                    isBusy = true
                                    retryKey++
                                }
                            },
                            enabled = !isBusy,
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
                                if (!isBusy) {
                                    isBusy = true
                                    scope.launch {
                                        try {
                                            val deleted = withContext(Dispatchers.IO) {
                                                onResetStorage()
                                            }
                                            if (deleted) {
                                                retryKey++
                                            } else {
                                                state = InitialState.Error(
                                                    "No se pudo restablecer el almacenamiento seguro."
                                                )
                                                isBusy = false
                                            }
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Throwable) {
                                            state = InitialState.Error(
                                                e.message ?: "No se pudo restablecer el almacenamiento seguro."
                                            )
                                            isBusy = false
                                        }
                                    }
                                }
                            },
                            enabled = !isBusy,
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

private sealed interface AppScreen {
    data object Home : AppScreen
    data object Inventory : AppScreen
    data class AnimalForm(val animalId: String? = null) : AppScreen
}

@Composable
fun AppNavigation(
    authRepository: AuthRepository,
    inventoryRepository: AnimalInventoryRepository,
    commandRepository: AnimalCommandRepository,
    initialSession: AuthSession?
) {
    var session by remember { mutableStateOf<AuthSession?>(initialSession) }
    var currentScreen by remember { mutableStateOf<AppScreen>(AppScreen.Home) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    if (session != null) {
        val currentRanchId = session?.ranchId
        when (val screen = currentScreen) {
            is AppScreen.Inventory -> {
                if (!currentRanchId.isNullOrBlank()) {
                    InventoryScreen(
                        ranchId = currentRanchId,
                        inventoryRepository = inventoryRepository,
                        onBackClick = { currentScreen = AppScreen.Home }
                    )
                } else {
                    currentScreen = AppScreen.Home
                }
            }
            is AppScreen.AnimalForm -> {
                if (!currentRanchId.isNullOrBlank()) {
                    AnimalFormScreen(
                        ranchId = currentRanchId,
                        animalIdToEdit = screen.animalId,
                        commandRepository = commandRepository,
                        onNavigateBack = { currentScreen = AppScreen.Home }
                    )
                } else {
                    currentScreen = AppScreen.Home
                }
            }
            is AppScreen.Home -> {
                HomeScreen(
                    session = session!!,
                    onLogoutClick = {
                        authRepository.logout()
                        session = null
                        currentScreen = AppScreen.Home
                        errorMessage = null
                    },
                    onOpenInventoryClick = {
                        if (!session?.ranchId.isNullOrBlank()) {
                            currentScreen = AppScreen.Inventory
                        }
                    },
                    onOpenRegisterAnimalClick = {
                        if (!session?.ranchId.isNullOrBlank()) {
                            currentScreen = AppScreen.AnimalForm(animalId = null)
                        }
                    }
                )
            }
        }
    } else {
        LoginScreen(
            isLoading = isLoading,
            errorMessage = errorMessage,
            onLoginClick = { user, pass ->
                if (isLoading) return@LoginScreen
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
