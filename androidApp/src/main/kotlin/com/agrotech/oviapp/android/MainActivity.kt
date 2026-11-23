package com.agrotech.oviapp.android

import android.Manifest
import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.cash.sqldelight.db.SqlDriver
import com.agrotech.oviapp.android.ui.AnimalDetailScreen
import com.agrotech.oviapp.android.ui.AnimalFormScreen
import com.agrotech.oviapp.android.ui.BirthEventScreen
import com.agrotech.oviapp.android.ui.HomeScreen
import com.agrotech.oviapp.android.ui.InventoryScreen
import com.agrotech.oviapp.android.ui.LoginScreen
import com.agrotech.oviapp.android.ui.SavedBirthEventsScreen
import com.agrotech.oviapp.android.notifications.DeviceTokenWorker
import com.agrotech.oviapp.android.notifications.Notifications
import com.agrotech.oviapp.android.notifications.WeaningNotifications
import com.agrotech.oviapp.android.session.SessionProvider
import com.agrotech.oviapp.android.ui.theme.OviTheme
import com.agrotech.oviapp.animals.data.SqlDelightAnimalCommandRepository
import com.agrotech.oviapp.animals.data.SqlDelightAnimalInventoryRepository
import com.agrotech.oviapp.animals.domain.model.AnimalFormState
import com.agrotech.oviapp.animals.domain.model.AnimalSaveResult
import com.agrotech.oviapp.animals.domain.repository.AnimalCommandRepository
import com.agrotech.oviapp.animals.domain.repository.AnimalInventoryRepository
import com.agrotech.oviapp.auth.data.AndroidEncryptedSessionStorage
import com.agrotech.oviapp.auth.data.KtorAuthService
import com.agrotech.oviapp.auth.domain.AuthRepository
import com.agrotech.oviapp.auth.domain.AuthSession
import com.agrotech.oviapp.births.data.SqlDelightBirthEventRepository
import com.agrotech.oviapp.births.domain.model.BirthEventDraft
import com.agrotech.oviapp.births.domain.model.BirthSaveResult
import com.agrotech.oviapp.births.domain.repository.BirthEventRepository
import com.agrotech.oviapp.db.DatabaseDriverFactory
import com.agrotech.oviapp.db.OviAppDatabase
import com.agrotech.oviapp.domain.model.LifeStage
import com.agrotech.oviapp.domain.model.ReproductiveStatus
import com.agrotech.oviapp.notifications.WeaningAlert
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: MainAppViewModel

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* sin permiso no se muestran avisos */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // La fábrica por defecto de ComponentActivity entrega Application y SavedStateHandle al ViewModel.
        viewModel = ViewModelProvider(this)[MainAppViewModel::class.java]

        // Solo en el primer arranque: al recrear la Activity (rotación) el aviso ya se atendió.
        if (savedInstanceState == null) {
            WeaningNotifications.alertFrom(intent)?.let(viewModel::openWeaningAlert)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            OviTheme {
                Surface {
                    MainApp(viewModel = viewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        WeaningNotifications.alertFrom(intent)?.let { alert ->
            setIntent(intent)
            viewModel.openWeaningAlert(alert)
        }
    }
}

class MainAppViewModel(
    application: Application,
    private val savedState: SavedStateHandle,
) : AndroidViewModel(application) {
    var state by mutableStateOf<InitialState>(InitialState.Loading)
        private set
    var currentSession by mutableStateOf<AuthSession?>(null)
        private set
    var isBusy by mutableStateOf(false)
        private set
    var animalSaveState by mutableStateOf<AnimalSaveUiState>(AnimalSaveUiState.Idle)
        private set
    var birthSaveState by mutableStateOf<BirthSaveUiState>(BirthSaveUiState.Idle)
        private set

    /**
     * Aviso de destete tocado (APP-17) que la navegación debe abrir cuando haya sesión. Se guarda también en
     * el SavedStateHandle: si Android mata el proceso antes del login, el aviso sigue pendiente al volver.
     */
    var pendingWeaningAlert by mutableStateOf(restoreWeaningAlert())
        private set

    private val lifecycleLock = Any()
    private var isCleared = false
    private var cachedDriver: SqlDriver? = null
    private var cachedDatabase: OviAppDatabase? = null
    private var cachedAuthRepository: AuthRepository? = null
    private var cachedInventoryRepository: AnimalInventoryRepository? = null
    private var cachedCommandRepository: AnimalCommandRepository? = null
    private var cachedBirthEventRepository: BirthEventRepository? = null

    init {
        // El token FCM se registra con el JWT de la sesión activa (BE-015).
        Notifications.session = object : SessionProvider {
            override suspend fun accessToken(): String? = currentSession?.accessToken
        }
        initialize()
    }

    fun updateSession(newSession: AuthSession?) {
        val previous = currentSession
        currentSession = newSession
        onSessionChanged(previous, newSession)
    }

    fun openWeaningAlert(alert: WeaningAlert) {
        pendingWeaningAlert = alert
        savedState[WEANING_ALERT_KEY] = HashMap(alert.toData())
    }

    /** Ya atendido: se borra también del estado guardado para que no se vuelva a abrir. */
    fun weaningAlertHandled() {
        pendingWeaningAlert = null
        savedState.remove<HashMap<String, String>>(WEANING_ALERT_KEY)
    }

    private fun restoreWeaningAlert(): WeaningAlert? =
        savedState.get<HashMap<String, String>>(WEANING_ALERT_KEY)?.let { WeaningAlert.fromData(it) }

    private var sessionWork: Job? = null

    /**
     * Al entrar se registra el celular; al salir el siguiente usuario debe registrarlo de nuevo. Los cambios
     * de sesión se procesan uno tras otro: el registro del usuario nuevo nunca se encola antes de que termine
     * el logout pendiente del anterior.
     */
    private fun onSessionChanged(previous: AuthSession?, current: AuthSession?) {
        val context = getApplication<Application>()
        val userLeft = previous != null && (current == null || current.username != previous.username)
        val pending = sessionWork
        sessionWork = viewModelScope.launch {
            pending?.join()
            if (userLeft) Notifications.registrar(context).onLogout()
            if (current != null) DeviceTokenWorker.enqueue(context)
        }
    }

    fun saveAnimal(draftKey: String, formState: AnimalFormState) {
        val commandRepo = synchronized(lifecycleLock) {
            if (isCleared) null else cachedCommandRepository
        } ?: return
        if (animalSaveState is AnimalSaveUiState.Saving) return

        animalSaveState = AnimalSaveUiState.Saving(draftKey)
        viewModelScope.launch {
            try {
                when (val res = commandRepo.saveAnimalOffline(formState)) {
                    is AnimalSaveResult.Success -> {
                        animalSaveState = AnimalSaveUiState.Success(
                            draftKey = draftKey,
                            localPhotoPath = formState.localPhotoPath
                        )
                    }
                    is AnimalSaveResult.DuplicateFolio -> {
                        animalSaveState = AnimalSaveUiState.Error(draftKey, res.message)
                    }
                    is AnimalSaveResult.ValidationError -> {
                        animalSaveState = AnimalSaveUiState.Error(draftKey, res.message)
                    }
                    is AnimalSaveResult.Error -> {
                        animalSaveState = AnimalSaveUiState.Error(draftKey, res.message)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                animalSaveState = AnimalSaveUiState.Error(
                    draftKey,
                    e.message ?: "Error guardando el ejemplar localmente."
                )
            }
        }
    }

    fun resetAnimalSaveState() {
        animalSaveState = AnimalSaveUiState.Idle
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
                    val authToClose = synchronized(lifecycleLock) {
                        val auth = cachedAuthRepository
                        cachedAuthRepository = null
                        cachedCommandRepository = null
                        auth
                    }
                    authToClose?.close()
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
                        DatabaseDriverFactory(appContext).createDriver()
                    } catch (t: Throwable) {
                        if (existingAuth == null) authRepo.close()
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

                val deps = synchronized(lifecycleLock) {
                    if (isCleared) return@withContext null
                    val db = cachedDatabase ?: OviAppDatabase(driver).also { cachedDatabase = it }
                    val inv = cachedInventoryRepository
                        ?: SqlDelightAnimalInventoryRepository(
                            database = db,
                            ioDispatcher = Dispatchers.IO
                        ).also { cachedInventoryRepository = it }
                    val cmd = cachedCommandRepository
                        ?: SqlDelightAnimalCommandRepository(
                            database = db,
                            ioDispatcher = Dispatchers.IO,
                            sessionStorage = sessionStorage
                        ).also { cachedCommandRepository = it }
                    val births = cachedBirthEventRepository
                        ?: SqlDelightBirthEventRepository(
                            database = db,
                            ioDispatcher = Dispatchers.IO
                        ).also { cachedBirthEventRepository = it }
                    AppDependencies(authRepo, inv, cmd, births)
                }

                val cachedSession = authRepo.getCachedSession()
                deps to cachedSession
            } ?: return
            if (synchronized(lifecycleLock) { isCleared }) return
            currentSession = dependencies.second
            onSessionChanged(null, dependencies.second)
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
            cachedInventoryRepository = null
            cachedCommandRepository = null
            cachedBirthEventRepository = null
            auth to driver
        }
        authToClose?.close()
        driverToClose?.close()
    }
}

private const val WEANING_ALERT_KEY = "pending_weaning_alert"

sealed interface AnimalSaveUiState {
    data object Idle : AnimalSaveUiState
    data class Saving(val draftKey: String) : AnimalSaveUiState
    data class Success(val draftKey: String, val localPhotoPath: String?) : AnimalSaveUiState
    data class Error(val draftKey: String, val message: String) : AnimalSaveUiState
}

sealed interface BirthSaveUiState {
    data object Idle : BirthSaveUiState
    data class Saving(val draftKey: String) : BirthSaveUiState
    data class Success(val draftKey: String, val localPhotoPath: String?) : BirthSaveUiState
    data class Error(val draftKey: String, val message: String) : BirthSaveUiState
}

data class AppDependencies(
    val authRepository: AuthRepository,
    val inventoryRepository: AnimalInventoryRepository,
    val commandRepository: AnimalCommandRepository,
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
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 3.dp
                )
            }
        }
        is InitialState.Ready -> {
            AppNavigation(
                authRepository = state.dependencies.authRepository,
                inventoryRepository = state.dependencies.inventoryRepository,
                commandRepository = state.dependencies.commandRepository,
                birthEventRepository = state.dependencies.birthEventRepository,
                session = viewModel.currentSession,
                onSessionChange = { viewModel.updateSession(it) },
                animalSaveState = viewModel.animalSaveState,
                onSaveAnimal = { draftKey, form -> viewModel.saveAnimal(draftKey, form) },
                onResetAnimalSaveState = { viewModel.resetAnimalSaveState() },
                birthSaveState = viewModel.birthSaveState,
                onSaveBirthEvent = { key, draft, today -> viewModel.saveBirthEvent(key, draft, today) },
                onResetBirthSaveState = { viewModel.resetBirthSaveState() },
                weaningAlert = viewModel.pendingWeaningAlert,
                onWeaningAlertHandled = { viewModel.weaningAlertHandled() }
            )
        }
        is InitialState.Error -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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
                                color = MaterialTheme.colorScheme.error
                            )
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
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
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Text("Reintentar Inicio")
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
                            Text("Restablecer Sesión", color = MaterialTheme.colorScheme.error)
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
    data object BirthEvent : AppScreen
    data object SavedBirthEvents : AppScreen
}

private val AppScreenSaver = listSaver<AppScreen, String>(
    save = { screen ->
        when (screen) {
            is AppScreen.Home -> listOf("Home")
            is AppScreen.Inventory -> listOf("Inventory")
            is AppScreen.AnimalDetail -> listOf("AnimalDetail", screen.animalId)
            is AppScreen.AnimalForm -> listOf("AnimalForm", screen.animalId ?: "")
            is AppScreen.BirthEvent -> listOf("BirthEvent")
            is AppScreen.SavedBirthEvents -> listOf("SavedBirthEvents")
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
            "BirthEvent" -> AppScreen.BirthEvent
            "SavedBirthEvents" -> AppScreen.SavedBirthEvents
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
    birthEventRepository: BirthEventRepository,
    session: AuthSession?,
    onSessionChange: (AuthSession?) -> Unit,
    animalSaveState: AnimalSaveUiState = AnimalSaveUiState.Idle,
    onSaveAnimal: (String, AnimalFormState) -> Unit = { _, _ -> },
    onResetAnimalSaveState: () -> Unit = {},
    birthSaveState: BirthSaveUiState = BirthSaveUiState.Idle,
    onSaveBirthEvent: (String, BirthEventDraft, String) -> Unit = { _, _, _ -> },
    onResetBirthSaveState: () -> Unit = {},
    weaningAlert: WeaningAlert? = null,
    onWeaningAlertHandled: () -> Unit = {}
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

    // Tocar un aviso de destete abre la ficha del cordero, solo si es del rancho de la sesión activa.
    LaunchedEffect(weaningAlert, session) {
        val alert = weaningAlert ?: return@LaunchedEffect
        val activeSession = session ?: return@LaunchedEffect
        if (alert.ranchId == activeSession.ranchId) {
            // Como al entrar desde el inventario: sin historial previo, "atrás" no repite el mismo cordero.
            detailBackStack = emptyList()
            currentScreen = AppScreen.AnimalDetail(alert.animalId)
        }
        onWeaningAlertHandled()
    }

    if (session != null) {
        val currentRanchId = session.ranchId
        val capabilities = session.uiCapabilities()
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
                    LaunchedEffect(screen, currentRanchId, capabilities.canReadInventory) {
                        currentScreen = AppScreen.Home
                    }
                }
            }
            is AppScreen.AnimalDetail -> {
                if (!currentRanchId.isNullOrBlank() && capabilities.canReadInventory) {
                    AnimalDetailScreen(
                        animalId = screen.animalId,
                        ranchId = currentRanchId,
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
                    LaunchedEffect(screen, currentRanchId, capabilities.canReadInventory) {
                        detailBackStack = emptyList()
                        currentScreen = AppScreen.Home
                    }
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
                        saveState = animalSaveState,
                        onSaveAnimal = onSaveAnimal,
                        onResetSaveState = onResetAnimalSaveState,
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
                    LaunchedEffect(screen, currentRanchId, canOpenForm) {
                        returnToDetailId = null
                        currentScreen = if (screen.animalId != null && capabilities.canReadInventory) {
                            AppScreen.Inventory
                        } else {
                            AppScreen.Home
                        }
                    }
                }
            }
            is AppScreen.BirthEvent -> {
                if (!currentRanchId.isNullOrBlank() && capabilities.canCapture) {
                    BirthEventScreen(
                        ranchId = currentRanchId,
                        registeredByUsername = session.username,
                        birthEventRepository = birthEventRepository,
                        saveState = birthSaveState,
                        onSaveBirthEvent = onSaveBirthEvent,
                        onResetSaveState = onResetBirthSaveState,
                        onNavigateBack = { currentScreen = AppScreen.Home }
                    )
                } else {
                    LaunchedEffect(screen, currentRanchId, capabilities.canCapture) {
                        currentScreen = AppScreen.Home
                    }
                }
            }
            is AppScreen.SavedBirthEvents -> {
                if (!currentRanchId.isNullOrBlank() && capabilities.canReadInventory) {
                    SavedBirthEventsScreen(
                        ranchId = currentRanchId,
                        capabilities = capabilities,
                        birthEventRepository = birthEventRepository,
                        onNavigateBack = { currentScreen = AppScreen.Home }
                    )
                } else {
                    LaunchedEffect(screen, currentRanchId, capabilities.canReadInventory) {
                        currentScreen = AppScreen.Home
                    }
                }
            }
            is AppScreen.Home -> {
                HomeScreen(
                    session = session,
                    onLogoutClick = {
                        authRepository.logout()
                        onResetAnimalSaveState()
                        onResetBirthSaveState()
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
                        if (!session.ranchId.isNullOrBlank() && capabilities.canReadInventory) {
                            currentScreen = AppScreen.Inventory
                        }
                    },
                    onOpenRegisterAnimalClick = {
                        if (!session.ranchId.isNullOrBlank() && capabilities.canCapture) {
                            currentScreen = AppScreen.AnimalForm(animalId = null)
                        }
                    },
                    onOpenBirthEventClick = {
                        if (!session.ranchId.isNullOrBlank() && capabilities.canCapture) {
                            currentScreen = AppScreen.BirthEvent
                        }
                    },
                    onOpenSavedBirthsClick = {
                        if (!session.ranchId.isNullOrBlank() && capabilities.canReadInventory) {
                            currentScreen = AppScreen.SavedBirthEvents
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
