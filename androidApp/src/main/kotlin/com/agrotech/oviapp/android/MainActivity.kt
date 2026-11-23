package com.agrotech.oviapp.android

import android.app.Application
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.cash.sqldelight.db.SqlDriver
import com.agrotech.oviapp.android.ui.BirthEventScreen
import com.agrotech.oviapp.android.ui.HomeScreen
import com.agrotech.oviapp.android.ui.LoginScreen
import com.agrotech.oviapp.android.ui.theme.OviTheme
import com.agrotech.oviapp.auth.data.AndroidEncryptedSessionStorage
import com.agrotech.oviapp.auth.data.KtorAuthService
import com.agrotech.oviapp.auth.domain.AuthRepository
import com.agrotech.oviapp.auth.domain.AuthSession
import com.agrotech.oviapp.births.data.BirthDatabaseDriverFactory
import com.agrotech.oviapp.births.data.SqlDelightBirthEventRepository
import com.agrotech.oviapp.births.domain.model.BirthEventDraft
import com.agrotech.oviapp.births.domain.model.BirthSaveResult
import com.agrotech.oviapp.births.domain.repository.BirthEventRepository
import com.agrotech.oviapp.db.OviAppDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val BaappPrimaryGreen = Color(0xFF1B5E20)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val viewModel = ViewModelProvider(
            this,
            ViewModelProvider.AndroidViewModelFactory.getInstance(application)
        )[MainAppViewModel::class.java]

        setContent {
            OviTheme {
                Surface {
                    MainApp(viewModel = viewModel)
                }
            }
        }
    }
}

class MainAppViewModel(application: Application) : AndroidViewModel(application) {
    var state by mutableStateOf<InitialState>(InitialState.Loading)
        private set
    var currentSession by mutableStateOf<AuthSession?>(null)
        private set
    var isBusy by mutableStateOf(false)
        private set
    var birthSaveState by mutableStateOf<BirthSaveUiState>(BirthSaveUiState.Idle)
        private set

    private val lifecycleLock = Any()
    private var isCleared = false
    private var cachedDriver: SqlDriver? = null
    private var cachedDatabase: OviAppDatabase? = null
    private var cachedAuthRepository: AuthRepository? = null
    private var cachedBirthEventRepository: BirthEventRepository? = null

    init {
        initialize()
    }

    fun updateSession(newSession: AuthSession?) {
        currentSession = newSession
    }

    fun retryInitialization() {
        if (isBusy) return
        initialize()
    }

    fun resetStorageAndRetry() {
        if (isBusy) return
        isBusy = true
        viewModelScope.launch {
            try {
                val deleted = withContext(Dispatchers.IO) {
                    getApplication<Application>().deleteSharedPreferences("baapp_secure_session_prefs")
                }
                if (deleted) {
                    val toClose = synchronized(lifecycleLock) {
                        val old = cachedAuthRepository
                        cachedAuthRepository = null
                        old
                    }
                    toClose?.close()
                    initializeInternal()
                } else {
                    state = InitialState.Error("No se pudo restablecer el almacenamiento seguro.")
                    isBusy = false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                state = InitialState.Error(e.message ?: "No se pudo restablecer el almacenamiento seguro.")
                isBusy = false
            }
        }
    }

    fun saveBirthEvent(draftKey: String, draft: BirthEventDraft, todayDateIso: String) {
        val repo = synchronized(lifecycleLock) {
            if (isCleared) null else cachedBirthEventRepository
        } ?: return
        if (birthSaveState is BirthSaveUiState.Saving) return

        birthSaveState = BirthSaveUiState.Saving(draftKey)
        viewModelScope.launch {
            try {
                when (val res = repo.saveBirthEventOffline(draft, todayDateIso, draftKey)) {
                    is BirthSaveResult.Success -> {
                        birthSaveState = BirthSaveUiState.Success(draftKey, draft.localPhotoPath)
                    }
                    is BirthSaveResult.DuplicateFolio -> {
                        birthSaveState = BirthSaveUiState.Error(draftKey, res.message)
                    }
                    is BirthSaveResult.ValidationError -> {
                        birthSaveState = BirthSaveUiState.Error(draftKey, res.message)
                    }
                    is BirthSaveResult.Error -> {
                        birthSaveState = BirthSaveUiState.Error(draftKey, res.message)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                birthSaveState = BirthSaveUiState.Error(
                    draftKey,
                    e.message ?: "Error guardando el evento de parto."
                )
            }
        }
    }

    fun resetBirthSaveState() {
        birthSaveState = BirthSaveUiState.Idle
    }

    private fun initialize() {
        isBusy = true
        viewModelScope.launch {
            initializeInternal()
        }
    }

    private suspend fun initializeInternal() {
        state = InitialState.Loading
        try {
            val dependencies = withContext(Dispatchers.IO) {
                val appContext = getApplication<Application>()
                val sessionStorage = AndroidEncryptedSessionStorage(appContext)
                val existingAuth = synchronized(lifecycleLock) {
                    if (isCleared) return@withContext null
                    cachedAuthRepository
                }
                val authRepo = existingAuth ?: AuthRepository(KtorAuthService(), sessionStorage)
                val existingDriver = synchronized(lifecycleLock) {
                    if (isCleared) null else cachedDriver
                }
                val newDriver = if (existingDriver == null) {
                    try {
                        BirthDatabaseDriverFactory(appContext).createDriver()
                    } catch (t: Throwable) {
                        if (existingAuth == null) {
                            authRepo.close()
                        }
                        throw t
                    }
                } else {
                    null
                }
                val driver = existingDriver ?: newDriver!!

                val published = synchronized(lifecycleLock) {
                    if (isCleared) {
                        false
                    } else {
                        if (cachedAuthRepository == null) cachedAuthRepository = authRepo
                        if (cachedDriver == null) cachedDriver = driver
                        true
                    }
                }
                if (!published) {
                    if (existingAuth == null) authRepo.close()
                    newDriver?.close()
                    return@withContext null
                }

                val birthRepo = synchronized(lifecycleLock) {
                    if (isCleared) return@withContext null
                    val db = cachedDatabase ?: OviAppDatabase(driver).also { cachedDatabase = it }
                    cachedBirthEventRepository
                        ?: SqlDelightBirthEventRepository(db).also {
                            cachedBirthEventRepository = it
                        }
                }

                val cachedSession = authRepo.getCachedSession()
                AppDependencies(authRepo, birthRepo) to cachedSession
            } ?: return
            if (synchronized(lifecycleLock) { isCleared }) return
            currentSession = dependencies.second
            state = InitialState.Ready(dependencies.first)
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

    override fun onCleared() {
        super.onCleared()
        val (authToClose, driverToClose) = synchronized(lifecycleLock) {
            isCleared = true
            val auth = cachedAuthRepository
            val driver = cachedDriver
            cachedAuthRepository = null
            cachedDriver = null
            cachedDatabase = null
            cachedBirthEventRepository = null
            auth to driver
        }
        authToClose?.close()
        driverToClose?.close()
    }
}

sealed interface BirthSaveUiState {
    data object Idle : BirthSaveUiState
    data class Saving(val draftKey: String) : BirthSaveUiState
    data class Success(val draftKey: String, val localPhotoPath: String?) : BirthSaveUiState
    data class Error(val draftKey: String, val message: String) : BirthSaveUiState
}

data class AppDependencies(
    val authRepository: AuthRepository,
    val birthEventRepository: BirthEventRepository
)

sealed interface InitialState {
    data object Loading : InitialState
    data class Ready(val dependencies: AppDependencies) : InitialState
    data class Error(val message: String) : InitialState
}

@Composable
private fun MainApp(viewModel: MainAppViewModel) {
    val state = viewModel.state
    val isBusy = viewModel.isBusy

    when (state) {
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
                authRepository = state.dependencies.authRepository,
                birthEventRepository = state.dependencies.birthEventRepository,
                session = viewModel.currentSession,
                onSessionChange = { viewModel.updateSession(it) },
                birthSaveState = viewModel.birthSaveState,
                onSaveBirthEvent = { key, draft, today -> viewModel.saveBirthEvent(key, draft, today) },
                onResetBirthSaveState = { viewModel.resetBirthSaveState() }
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
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium.copy(color = Color.DarkGray),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = { viewModel.retryInitialization() },
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
                            onClick = { viewModel.resetStorageAndRetry() },
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

@Composable
fun AppNavigation(
    authRepository: AuthRepository,
    birthEventRepository: BirthEventRepository,
    session: AuthSession?,
    onSessionChange: (AuthSession?) -> Unit,
    birthSaveState: BirthSaveUiState = BirthSaveUiState.Idle,
    onSaveBirthEvent: (String, BirthEventDraft, String) -> Unit = { _, _, _ -> },
    onResetBirthSaveState: () -> Unit = {}
) {
    var showingBirthScreen by rememberSaveable { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    if (session != null) {
        val currentRanchId = session.ranchId
        val capabilities = session.uiCapabilities()
        if (showingBirthScreen && !currentRanchId.isNullOrBlank() && capabilities.canCapture) {
            BirthEventScreen(
                ranchId = currentRanchId,
                registeredByUsername = session.username,
                birthEventRepository = birthEventRepository,
                saveState = birthSaveState,
                onSaveBirthEvent = onSaveBirthEvent,
                onResetSaveState = onResetBirthSaveState,
                onNavigateBack = { showingBirthScreen = false }
            )
        } else {
            showingBirthScreen = false
            HomeScreen(
                session = session,
                onLogoutClick = {
                    authRepository.logout()
                    onSessionChange(null)
                    showingBirthScreen = false
                    errorMessage = null
                },
                onOpenBirthEventClick = {
                    if (!session.ranchId.isNullOrBlank() && capabilities.canCapture) {
                        showingBirthScreen = true
                    }
                }
            )
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
                            onSessionChange(newSession)
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
