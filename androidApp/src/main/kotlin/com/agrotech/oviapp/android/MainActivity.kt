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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
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
import com.agrotech.oviapp.android.ui.AnimalDetailScreen
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
import com.agrotech.oviapp.db.OviAppDatabase
import com.agrotech.oviapp.domain.model.LifeStage
import com.agrotech.oviapp.domain.model.ReproductiveStatus
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
            MaterialTheme {
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

    private var cachedDriver: SqlDriver? = null
    private var cachedDatabase: OviAppDatabase? = null
    private var cachedAuthRepository: AuthRepository? = null
    private var cachedInventoryRepository: AnimalInventoryRepository? = null
    private var cachedCommandRepository: AnimalCommandRepository? = null

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
                    cachedAuthRepository?.close()
                    cachedAuthRepository = null
                    cachedCommandRepository = null
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
                val authRepo = cachedAuthRepository ?: AuthRepository(
                    KtorAuthService(),
                    sessionStorage
                ).also { cachedAuthRepository = it }

                val driver = cachedDriver ?: DatabaseDriverFactory(appContext).createDriver().also {
                    cachedDriver = it
                }
                val database = cachedDatabase ?: OviAppDatabase(driver).also {
                    cachedDatabase = it
                }
                val inventoryRepo = cachedInventoryRepository
                    ?: SqlDelightAnimalInventoryRepository(database).also {
                        cachedInventoryRepository = it
                    }
                val commandRepo = cachedCommandRepository
                    ?: SqlDelightAnimalCommandRepository(
                        database = database,
                        sessionStorage = sessionStorage
                    ).also {
                        cachedCommandRepository = it
                    }

                val cachedSession = authRepo.getCachedSession()
                AppDependencies(authRepo, inventoryRepo, commandRepo) to cachedSession
            }
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
        cachedAuthRepository?.close()
        cachedAuthRepository = null
        cachedDriver?.close()
        cachedDriver = null
    }
}

data class AppDependencies(
    val authRepository: AuthRepository,
    val inventoryRepository: AnimalInventoryRepository,
    val commandRepository: AnimalCommandRepository
)

sealed interface InitialState {
    data object Loading : InitialState
    data class Ready(
        val dependencies: AppDependencies
    ) : InitialState
    data class Error(val message: String) : InitialState
}

@Composable
private fun MainApp(
    viewModel: MainAppViewModel
) {
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
                inventoryRepository = state.dependencies.inventoryRepository,
                commandRepository = state.dependencies.commandRepository,
                session = viewModel.currentSession,
                onSessionChange = { viewModel.updateSession(it) }
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

private sealed interface AppScreen {
    data object Home : AppScreen
    data object Inventory : AppScreen
    data class AnimalDetail(val animalId: String) : AppScreen
    data class AnimalForm(val animalId: String? = null) : AppScreen
}

private val AppScreenSaver = listSaver<AppScreen, String>(
    save = { screen ->
        when (screen) {
            is AppScreen.Home -> listOf("Home")
            is AppScreen.Inventory -> listOf("Inventory")
            is AppScreen.AnimalDetail -> listOf("AnimalDetail", screen.animalId)
            is AppScreen.AnimalForm -> listOf("AnimalForm", screen.animalId ?: "")
        }
    },
    restore = { saved ->
        when (saved.firstOrNull()) {
            "Inventory" -> AppScreen.Inventory
            "AnimalDetail" -> {
                val id = saved.getOrNull(1)
                if (!id.isNullOrBlank()) AppScreen.AnimalDetail(id) else AppScreen.Inventory
            }
            "AnimalForm" -> AppScreen.AnimalForm(saved.getOrNull(1)?.takeIf { it.isNotEmpty() })
            else -> AppScreen.Home
        }
    }
)

private val StringListSaver = listSaver<List<String>, String>(
    save = { it },
    restore = { it }
)

@Composable
fun AppNavigation(
    authRepository: AuthRepository,
    inventoryRepository: AnimalInventoryRepository,
    commandRepository: AnimalCommandRepository,
    session: AuthSession?,
    onSessionChange: (AuthSession?) -> Unit
) {
    var currentScreen by rememberSaveable(stateSaver = AppScreenSaver) {
        mutableStateOf<AppScreen>(AppScreen.Home)
    }
    var detailBackStack by rememberSaveable(stateSaver = StringListSaver) {
        mutableStateOf(emptyList())
    }
    var returnToDetailId by rememberSaveable { mutableStateOf<String?>(null) }
    var inventoryTabIndex by rememberSaveable { mutableIntStateOf(0) }
    var inventorySearchQuery by rememberSaveable { mutableStateOf("") }
    var inventoryReproductiveStatus by rememberSaveable { mutableStateOf<ReproductiveStatus?>(null) }
    var inventoryLifeStage by rememberSaveable { mutableStateOf<LifeStage?>(null) }
    val inventoryListState = rememberLazyListState()
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    if (session != null) {
        val currentRanchId = session?.ranchId
        val capabilities = session!!.uiCapabilities()
        when (val screen = currentScreen) {
            is AppScreen.Inventory -> {
                if (!currentRanchId.isNullOrBlank() && capabilities.canReadInventory) {
                    InventoryScreen(
                        ranchId = currentRanchId,
                        inventoryRepository = inventoryRepository,
                        onBackClick = { currentScreen = AppScreen.Home },
                        onAnimalClick = { animalId ->
                            detailBackStack = emptyList()
                            currentScreen = AppScreen.AnimalDetail(animalId)
                        },
                        onEditAnimalClick = if (capabilities.canEditGeneralRecords) {
                            { animalId ->
                                returnToDetailId = null
                                currentScreen = AppScreen.AnimalForm(animalId = animalId)
                            }
                        } else {
                            null
                        },
                        selectedTabIndex = inventoryTabIndex,
                        onSelectedTabIndexChange = { inventoryTabIndex = it },
                        searchQuery = inventorySearchQuery,
                        onSearchQueryChange = { inventorySearchQuery = it },
                        selectedReproductiveStatus = inventoryReproductiveStatus,
                        onSelectedReproductiveStatusChange = { inventoryReproductiveStatus = it },
                        selectedLifeStage = inventoryLifeStage,
                        onSelectedLifeStageChange = { inventoryLifeStage = it },
                        listState = inventoryListState
                    )
                } else {
                    currentScreen = AppScreen.Home
                }
            }
            is AppScreen.AnimalDetail -> {
                if (!currentRanchId.isNullOrBlank() && capabilities.canReadInventory) {
                    AnimalDetailScreen(
                        animalId = screen.animalId,
                        inventoryRepository = inventoryRepository,
                        onBackClick = {
                            if (detailBackStack.isNotEmpty()) {
                                val previousId = detailBackStack.last()
                                detailBackStack = detailBackStack.dropLast(1)
                                currentScreen = AppScreen.AnimalDetail(previousId)
                            } else {
                                currentScreen = AppScreen.Inventory
                            }
                        },
                        onNavigateToAnimal = { targetAnimalId ->
                            if (targetAnimalId != screen.animalId) {
                                detailBackStack = detailBackStack + screen.animalId
                                currentScreen = AppScreen.AnimalDetail(targetAnimalId)
                            }
                        },
                        onEditAnimalClick = if (capabilities.canEditGeneralRecords) {
                            { animalId ->
                                returnToDetailId = animalId
                                currentScreen = AppScreen.AnimalForm(animalId = animalId)
                            }
                        } else {
                            null
                        }
                    )
                } else {
                    detailBackStack = emptyList()
                    currentScreen = AppScreen.Home
                }
            }
            is AppScreen.AnimalForm -> {
                val canOpenForm = if (screen.animalId != null) {
                    capabilities.canEditGeneralRecords
                } else {
                    capabilities.canCapture
                }
                if (!currentRanchId.isNullOrBlank() && canOpenForm) {
                    AnimalFormScreen(
                        ranchId = currentRanchId,
                        animalIdToEdit = screen.animalId,
                        commandRepository = commandRepository,
                        onNavigateBack = {
                            val detailTarget = returnToDetailId
                            returnToDetailId = null
                            currentScreen = when {
                                detailTarget != null -> AppScreen.AnimalDetail(detailTarget)
                                screen.animalId != null -> AppScreen.Inventory
                                else -> AppScreen.Home
                            }
                        }
                    )
                } else {
                    returnToDetailId = null
                    currentScreen = if (screen.animalId != null) AppScreen.Inventory else AppScreen.Home
                }
            }
            is AppScreen.Home -> {
                HomeScreen(
                    session = session!!,
                    onLogoutClick = {
                        authRepository.logout()
                        onSessionChange(null)
                        detailBackStack = emptyList()
                        returnToDetailId = null
                        inventoryTabIndex = 0
                        inventorySearchQuery = ""
                        inventoryReproductiveStatus = null
                        inventoryLifeStage = null
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
