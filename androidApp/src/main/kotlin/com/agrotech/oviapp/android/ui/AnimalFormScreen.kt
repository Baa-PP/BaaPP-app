package com.agrotech.oviapp.android.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agrotech.oviapp.android.AnimalSaveUiState
import com.agrotech.oviapp.android.ui.theme.FieldPalette
import com.agrotech.oviapp.animals.domain.model.AnimalFormState
import com.agrotech.oviapp.animals.domain.repository.AnimalCommandRepository
import com.agrotech.oviapp.domain.model.UuidV7
import com.agrotech.oviapp.domain.model.AnimalCatalogs
import com.agrotech.oviapp.domain.model.ConceptionMethod
import com.agrotech.oviapp.domain.model.EntryReason
import com.agrotech.oviapp.domain.model.LifeStage
import com.agrotech.oviapp.domain.model.ParturitionType
import com.agrotech.oviapp.domain.model.ReproductiveStatus
import com.agrotech.oviapp.domain.model.Sex
import com.agrotech.oviapp.media.ImageCompressor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

private const val MAX_SOURCE_PHOTO_BYTES = 15 * 1024 * 1024
private const val PENDING_PHOTOS_DIR_NAME = "pending_animal_photos"

private val BaappPrimaryGreen = Color(FieldPalette.PASTURE)
private val BaappFemalePink = Color(FieldPalette.FEMALE)
private val BaappFemalePinkBg = Color(FieldPalette.FEMALE_CONTAINER)
private val BaappMaleBlue = Color(FieldPalette.MALE)
private val BaappMaleBlueBg = Color(FieldPalette.MALE_CONTAINER)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimalFormScreen(
    ranchId: String,
    animalIdToEdit: String? = null,
    commandRepository: AnimalCommandRepository,
    saveState: AnimalSaveUiState = AnimalSaveUiState.Idle,
    onSaveAnimal: (String, AnimalFormState) -> Unit = { _, _ -> },
    onResetSaveState: () -> Unit = {},
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val draftKey = rememberSaveable(ranchId, animalIdToEdit) { UuidV7.generate() }

    var folioInterno by rememberSaveable(ranchId, animalIdToEdit) { mutableStateOf("") }
    var siniigaChip by rememberSaveable(ranchId, animalIdToEdit) { mutableStateOf("") }
    var registroUno by rememberSaveable(ranchId, animalIdToEdit) { mutableStateOf("") }
    var adn by rememberSaveable(ranchId, animalIdToEdit) { mutableStateOf("") }
    var breed by rememberSaveable(ranchId, animalIdToEdit) { mutableStateOf("Pelifolk") }
    var color by rememberSaveable(ranchId, animalIdToEdit) { mutableStateOf("") }
    var sex by rememberSaveable(ranchId, animalIdToEdit) { mutableStateOf(Sex.FEMALE) }
    var birthDate by rememberSaveable(ranchId, animalIdToEdit) { mutableStateOf("") }
    var weaningDate by rememberSaveable(ranchId, animalIdToEdit) { mutableStateOf("") }
    var parturitionType by rememberSaveable(ranchId, animalIdToEdit) { mutableStateOf(ParturitionType.SIMPLE) }
    var conceptionMethod by rememberSaveable(ranchId, animalIdToEdit) { mutableStateOf(ConceptionMethod.MN) }
    var entryReason by rememberSaveable(ranchId, animalIdToEdit) { mutableStateOf(EntryReason.BIRTH) }
    var criadorName by rememberSaveable(ranchId, animalIdToEdit) { mutableStateOf("") }
    var sireId by rememberSaveable(ranchId, animalIdToEdit) { mutableStateOf<String?>(null) }
    var damId by rememberSaveable(ranchId, animalIdToEdit) { mutableStateOf<String?>(null) }
    var externalSireFolio by rememberSaveable(ranchId, animalIdToEdit) { mutableStateOf("") }
    var externalDamFolio by rememberSaveable(ranchId, animalIdToEdit) { mutableStateOf("") }
    var lifeStage by rememberSaveable(ranchId, animalIdToEdit) { mutableStateOf(LifeStage.ADULT) }
    var reproductiveStatus by rememberSaveable(ranchId, animalIdToEdit) { mutableStateOf(ReproductiveStatus.EMPTY) }

    var localPhotoPath by rememberSaveable(ranchId, animalIdToEdit) { mutableStateOf<String?>(null) }
    var photoSizeBytes by rememberSaveable(ranchId, animalIdToEdit) { mutableStateOf<Int?>(null) }
    var photoS3Key by rememberSaveable(ranchId, animalIdToEdit) { mutableStateOf<String?>(null) }
    var draftCreatedPhotos by rememberSaveable(
        ranchId,
        animalIdToEdit,
        stateSaver = listSaver(save = { it }, restore = { it })
    ) { mutableStateOf(emptyList<String>()) }
    var committedPhotoPath by rememberSaveable(ranchId, animalIdToEdit) { mutableStateOf<String?>(null) }
    var hasLoadedExisting by rememberSaveable(ranchId, animalIdToEdit) { mutableStateOf(false) }
    var isCompressingPhoto by remember { mutableStateOf(false) }

    var damsList by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var siresList by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    var errorMessage by rememberSaveable(ranchId, animalIdToEdit) { mutableStateOf<String?>(null) }
    val isSaving = saveState is AnimalSaveUiState.Saving && saveState.draftKey == draftKey
    var isLoadingExisting by remember { mutableStateOf(true) }
    var initLoadFailed by remember { mutableStateOf(false) }
    var recordNotFound by remember { mutableStateOf(false) }
    var initRetryTrigger by remember { mutableIntStateOf(0) }

    var breedExpanded by remember { mutableStateOf(false) }
    var lifeStageExpanded by remember { mutableStateOf(false) }
    var reproExpanded by remember { mutableStateOf(false) }
    var reasonExpanded by remember { mutableStateOf(false) }
    var damExpanded by remember { mutableStateOf(false) }
    var sireExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(saveState, draftKey) {
        when (saveState) {
            is AnimalSaveUiState.Success -> {
                if (saveState.draftKey == draftKey) {
                    val previousCommitted = committedPhotoPath
                    committedPhotoPath = saveState.localPhotoPath
                    val obsoleteDrafts = draftCreatedPhotos.filter {
                        it != saveState.localPhotoPath && it != committedPhotoPath
                    }
                    val deletePrev = previousCommitted != null &&
                        previousCommitted != saveState.localPhotoPath &&
                        !commandRepository.isLocalPhotoReferencedByPendingSync(previousCommitted)
                    withContext(NonCancellable + Dispatchers.IO) {
                        obsoleteDrafts.forEach { deletePendingPhotoIfOwned(context.filesDir, it) }
                        if (deletePrev) {
                            deletePendingPhotoIfOwned(context.filesDir, previousCommitted)
                        }
                    }
                    draftCreatedPhotos = emptyList()
                    onResetSaveState()
                    onNavigateBack()
                }
            }
            is AnimalSaveUiState.Error -> {
                if (saveState.draftKey == draftKey) {
                    errorMessage = saveState.message
                    onResetSaveState()
                }
            }
            else -> Unit
        }
    }

    val handleCancelAndCleanup: () -> Unit = {
        val toDelete = draftCreatedPhotos.filter { it != committedPhotoPath }
        draftCreatedPhotos = emptyList()
        if (toDelete.isNotEmpty()) {
            scope.launch(NonCancellable + Dispatchers.IO) {
                toDelete.forEach { deletePendingPhotoIfOwned(context.filesDir, it) }
            }
        }
        onResetSaveState()
        onNavigateBack()
    }

    BackHandler(enabled = true) {
        if (!isSaving) {
            handleCancelAndCleanup()
        }
    }

    // Load parental dropdowns and existing animal if editing
    LaunchedEffect(ranchId, animalIdToEdit, initRetryTrigger) {
        isLoadingExisting = true
        initLoadFailed = false
        recordNotFound = false
        errorMessage = null
        try {
            damsList = commandRepository.getPotentialDams(ranchId)
            siresList = commandRepository.getPotentialSires(ranchId)

            if (animalIdToEdit != null && !hasLoadedExisting) {
                val existing = commandRepository.getAnimalById(animalIdToEdit, ranchId)
                if (existing != null) {
                    folioInterno = existing.folioInterno
                    siniigaChip = existing.siniigaChip ?: ""
                    registroUno = existing.registroUno ?: ""
                    adn = existing.adn ?: ""
                    breed = existing.breed
                    color = existing.color
                    sex = existing.sex
                    birthDate = existing.birthDateIso
                    weaningDate = existing.weaningDateIso ?: ""
                    parturitionType = existing.parturitionType
                    conceptionMethod = existing.conceptionMethod
                    entryReason = try { EntryReason.valueOf(existing.entryReason) } catch (_: Exception) { EntryReason.BIRTH }
                    criadorName = existing.criadorName ?: ""
                    sireId = existing.sireId
                    damId = existing.damId
                    externalSireFolio = existing.externalSireFolio ?: ""
                    externalDamFolio = existing.externalDamFolio ?: ""
                    lifeStage = existing.lifeStage
                    reproductiveStatus = existing.reproductiveStatus
                    photoS3Key = existing.photoS3Key
                    val existingLocalPhoto = commandRepository.getLatestLocalPhotoPath(animalIdToEdit)
                    if (!existingLocalPhoto.isNullOrBlank()) {
                        committedPhotoPath = existingLocalPhoto
                        localPhotoPath = existingLocalPhoto
                    }
                    hasLoadedExisting = true
                } else {
                    recordNotFound = true
                    errorMessage = "No se encontró el ejemplar solicitado."
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            initLoadFailed = true
            errorMessage = e.message ?: "Error al cargar los datos del formulario."
        } finally {
            isLoadingExisting = false
        }
    }

    // Photo picker with bounded read and automatic ~150-200 KB compression
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                isCompressingPhoto = true
                errorMessage = null
                var createdFile: File? = null
                try {
                    val result = withContext(Dispatchers.IO) {
                        val stream = context.contentResolver.openInputStream(uri)
                            ?: throw IllegalStateException("No se pudo abrir la fotografía seleccionada.")
                        val inputBytes = stream.use { readBoundedPhotoBytes(it, MAX_SOURCE_PHOTO_BYTES) }
                        val compressedBytes = ImageCompressor.compressToTarget(inputBytes, 200 * 1024)
                        val photosDir = File(context.filesDir, PENDING_PHOTOS_DIR_NAME).apply { mkdirs() }
                        val photoFile = File(photosDir, "animal_${System.currentTimeMillis()}.jpg")
                        createdFile = photoFile
                        FileOutputStream(photoFile).use { fos ->
                            fos.write(compressedBytes)
                        }
                        photoFile.absolutePath to compressedBytes.size
                    }
                    val (newPath, compressedSize) = result
                    val prevLocal = localPhotoPath
                    if (prevLocal != null && prevLocal != committedPhotoPath) {
                        withContext(NonCancellable + Dispatchers.IO) {
                            deletePendingPhotoIfOwned(context.filesDir, prevLocal)
                        }
                    }
                    draftCreatedPhotos = draftCreatedPhotos.filterNot { it == prevLocal } + newPath
                    localPhotoPath = newPath
                    photoSizeBytes = compressedSize
                    photoS3Key = null
                } catch (e: CancellationException) {
                    createdFile?.let { file ->
                        withContext(NonCancellable + Dispatchers.IO) {
                            deletePendingPhotoIfOwned(context.filesDir, file.absolutePath)
                        }
                    }
                    throw e
                } catch (e: Exception) {
                    createdFile?.let { file ->
                        withContext(NonCancellable + Dispatchers.IO) {
                            deletePendingPhotoIfOwned(context.filesDir, file.absolutePath)
                        }
                    }
                    errorMessage = "Error procesando fotografía: ${e.message}"
                } finally {
                    isCompressingPhoto = false
                }
            }
        }
    }

    val isEditMode = animalIdToEdit != null
    val screenTitle = if (isEditMode) "Editar Ficha de Ejemplar" else "Alta de Ejemplar (Reseña)"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(screenTitle, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    TextButton(onClick = handleCancelAndCleanup, enabled = !isSaving) {
                        Text("← Cancelar", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BaappPrimaryGreen,
                    titleContentColor = Color.White
                )
            )
        },
        modifier = modifier
    ) { innerPadding ->
        if (isLoadingExisting) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else if (recordNotFound) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(innerPadding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = errorMessage ?: "El ejemplar solicitado no está disponible.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(
                        onClick = handleCancelAndCleanup,
                        modifier = Modifier.heightIn(min = 56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("← Volver")
                    }
                }
            }
        } else if (initLoadFailed) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(innerPadding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = errorMessage ?: "Error al cargar los datos del formulario.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(
                        onClick = { initRetryTrigger++ },
                        modifier = Modifier.heightIn(min = 56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("Reintentar")
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(innerPadding)
                    .padding(16.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (!errorMessage.isNullOrBlank()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))
                    ) {
                        Text(
                            text = errorMessage!!,
                            color = Color(FieldPalette.ALERT),
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                // 1. Identificación Principal
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("1. Identificación Principal", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))

                        OutlinedTextField(
                            value = folioInterno,
                            onValueChange = { folioInterno = it },
                            label = { Text("Arete / Folio Interno *") },
                            placeholder = { Text("Ej. AIR-2253-H") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        )

                        OutlinedTextField(
                            value = siniigaChip,
                            onValueChange = { siniigaChip = it },
                            label = { Text("Chip SINIIGA (10-15 dígitos con prefijo 484)") },
                            placeholder = { Text("Ej. 484000123456789 (opcional al nacer)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        )

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = registroUno,
                                onValueChange = { registroUno = it },
                                label = { Text("Registro UNO") },
                                placeholder = { Text("Ej. 268309MM-RP") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp)
                            )
                            OutlinedTextField(
                                value = adn,
                                onValueChange = { adn = it },
                                label = { Text("Código ADN") },
                                placeholder = { Text("Ej. ADN-882") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp)
                            )
                        }
                    }
                }

                // 2. Sexo y Características Zootécnicas
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("2. Sexo y Características", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = sex == Sex.FEMALE,
                                onClick = {
                                    sex = Sex.FEMALE
                                    if (reproductiveStatus == ReproductiveStatus.STUD_RAM) {
                                        reproductiveStatus = ReproductiveStatus.EMPTY
                                    }
                                },
                                label = { Text("♀ Hembra") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = BaappFemalePinkBg,
                                    selectedLabelColor = BaappFemalePink
                                ),
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = sex == Sex.MALE,
                                onClick = {
                                    sex = Sex.MALE
                                    if (lifeStage == LifeStage.EWE_REPLACEMENT) {
                                        lifeStage = LifeStage.ADULT
                                    }
                                    if (reproductiveStatus == ReproductiveStatus.PREGNANT ||
                                        reproductiveStatus == ReproductiveStatus.LACTATING
                                    ) {
                                        reproductiveStatus = ReproductiveStatus.EMPTY
                                    }
                                },
                                label = { Text("♂ Macho (Semental)") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = BaappMaleBlueBg,
                                    selectedLabelColor = BaappMaleBlue
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }

                        // Raza Dropdown
                        ExposedDropdownMenuBox(
                            expanded = breedExpanded,
                            onExpandedChange = { breedExpanded = it },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = breed,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Raza") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = breedExpanded) },
                                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            )
                            ExposedDropdownMenu(
                                expanded = breedExpanded,
                                onDismissRequest = { breedExpanded = false }
                            ) {
                                AnimalCatalogs.BREEDS.forEach { b ->
                                    DropdownMenuItem(
                                        text = { Text(b) },
                                        onClick = {
                                            breed = b
                                            breedExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        OutlinedTextField(
                            value = color,
                            onValueChange = { color = it },
                            label = { Text("Color / Capa *") },
                            placeholder = { Text("Ej. Blanco, Pinto, Cabeza Negra") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        )

                        // Etapa de Vida Dropdown
                        val allowedStages = if (sex == Sex.MALE) {
                            LifeStage.entries.filter { it != LifeStage.EWE_REPLACEMENT }
                        } else {
                            LifeStage.entries
                        }
                        ExposedDropdownMenuBox(
                            expanded = lifeStageExpanded,
                            onExpandedChange = { lifeStageExpanded = it },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = lifeStageLabel(lifeStage),
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Etapa de Vida") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = lifeStageExpanded) },
                                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            )
                            ExposedDropdownMenu(
                                expanded = lifeStageExpanded,
                                onDismissRequest = { lifeStageExpanded = false }
                            ) {
                                allowedStages.forEach { stage ->
                                    DropdownMenuItem(
                                        text = { Text(lifeStageLabel(stage)) },
                                        onClick = {
                                            lifeStage = stage
                                            lifeStageExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        // Estado Reproductivo Dropdown
                        val allowedRepro = if (sex == Sex.MALE) {
                            listOf(ReproductiveStatus.EMPTY, ReproductiveStatus.STUD_RAM)
                        } else {
                            listOf(ReproductiveStatus.EMPTY, ReproductiveStatus.PREGNANT, ReproductiveStatus.LACTATING)
                        }
                        ExposedDropdownMenuBox(
                            expanded = reproExpanded,
                            onExpandedChange = { reproExpanded = it },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = reproductiveStatusLabel(reproductiveStatus),
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Estado Reproductivo") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = reproExpanded) },
                                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            )
                            ExposedDropdownMenu(
                                expanded = reproExpanded,
                                onDismissRequest = { reproExpanded = false }
                            ) {
                                allowedRepro.forEach { status ->
                                    DropdownMenuItem(
                                        text = { Text(reproductiveStatusLabel(status)) },
                                        onClick = {
                                            reproductiveStatus = status
                                            reproExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // 3. Origen y Fechas
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("3. Origen, Fechas y Criador", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = birthDate,
                                onValueChange = { birthDate = it },
                                label = { Text("Fecha Nac. (YYYY-MM-DD) *") },
                                placeholder = { Text("2026-01-01") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp)
                            )
                            OutlinedTextField(
                                value = weaningDate,
                                onValueChange = { weaningDate = it },
                                label = { Text("Fecha Destete") },
                                placeholder = { Text("YYYY-MM-DD") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp)
                            )
                        }

                        // Motivo de ingreso
                        ExposedDropdownMenuBox(
                            expanded = reasonExpanded,
                            onExpandedChange = { reasonExpanded = it },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = entryReasonLabel(entryReason),
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Motivo de Ingreso") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = reasonExpanded) },
                                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            )
                            ExposedDropdownMenu(
                                expanded = reasonExpanded,
                                onDismissRequest = { reasonExpanded = false }
                            ) {
                                AnimalCatalogs.ENTRY_REASONS.forEach { r ->
                                    DropdownMenuItem(
                                        text = { Text(entryReasonLabel(r)) },
                                        onClick = {
                                            entryReason = r
                                            reasonExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        OutlinedTextField(
                            value = criadorName,
                            onValueChange = { criadorName = it },
                            label = { Text("Criador / Rancho de Origen (Criador vs Propietario)") },
                            placeholder = { Text("Ej. Rancho Las Águilas") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                }

                // 4. Genealogía y Tipo de Parto
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("4. Genealogía", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))

                        val selectedDamFolio = damsList.firstOrNull { it.first == damId }?.second
                            ?: if (damId != null) "Madre registrada" else "Ninguna / Externa"
                        ExposedDropdownMenuBox(
                            expanded = damExpanded,
                            onExpandedChange = { damExpanded = it },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = selectedDamFolio,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Madre en Rebaño (Interna)") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = damExpanded) },
                                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            )
                            ExposedDropdownMenu(
                                expanded = damExpanded,
                                onDismissRequest = { damExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Ninguna / Externa") },
                                    onClick = {
                                        damId = null
                                        damExpanded = false
                                    }
                                )
                                damsList
                                    .filter { it.first != animalIdToEdit }
                                    .forEach { (id, folio) ->
                                        DropdownMenuItem(
                                            text = { Text(folio) },
                                            onClick = {
                                                damId = id
                                                externalDamFolio = ""
                                                damExpanded = false
                                            }
                                        )
                                    }
                            }
                        }

                        OutlinedTextField(
                            value = externalDamFolio,
                            onValueChange = {
                                externalDamFolio = it
                                if (it.isNotBlank()) damId = null
                            },
                            label = { Text("Madre Externa (Folio Externo)") },
                            placeholder = { Text("Ej. MADRE-EXT-01") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        )

                        val selectedSireFolio = siresList.firstOrNull { it.first == sireId }?.second
                            ?: if (sireId != null) "Semental registrado" else "Ninguno / Externo"
                        ExposedDropdownMenuBox(
                            expanded = sireExpanded,
                            onExpandedChange = { sireExpanded = it },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = selectedSireFolio,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Semental en Rebaño (Interno)") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sireExpanded) },
                                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            )
                            ExposedDropdownMenu(
                                expanded = sireExpanded,
                                onDismissRequest = { sireExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Ninguno / Externo") },
                                    onClick = {
                                        sireId = null
                                        sireExpanded = false
                                    }
                                )
                                siresList
                                    .filter { it.first != animalIdToEdit }
                                    .forEach { (id, folio) ->
                                        DropdownMenuItem(
                                            text = { Text(folio) },
                                            onClick = {
                                                sireId = id
                                                externalSireFolio = ""
                                                sireExpanded = false
                                            }
                                        )
                                    }
                            }
                        }

                        OutlinedTextField(
                            value = externalSireFolio,
                            onValueChange = {
                                externalSireFolio = it
                                if (it.isNotBlank()) sireId = null
                            },
                            label = { Text("Semental Externo (Folio Externo)") },
                            placeholder = { Text("Ej. SEMENTAL-EXT-01") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                }

                // 5. Fotografía del Ejemplar (~150-200 KB)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("5. Fotografía del Ejemplar", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = { photoPickerLauncher.launch("image/*") },
                                enabled = !isCompressingPhoto && !isSaving,
                                modifier = Modifier.heightIn(min = 56.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(if (localPhotoPath == null && photoS3Key.isNullOrBlank()) "Adjuntar Foto" else "Cambiar Foto")
                            }

                            if (isCompressingPhoto) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            } else if (photoSizeBytes != null) {
                                val kb = photoSizeBytes!! / 1024
                                Text(
                                    text = "Comprimida: ~$kb KB (OK)",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = BaappPrimaryGreen,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            } else if (!photoS3Key.isNullOrBlank()) {
                                Text(
                                    text = "Foto guardada en ficha",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = BaappPrimaryGreen,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }
                        }

                        if (localPhotoPath != null) {
                            Text(
                                text = "Archivo local: ${localPhotoPath!!.substringAfterLast('/')}",
                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                            )
                        }

                        if (localPhotoPath != null || !photoS3Key.isNullOrBlank()) {
                            TextButton(
                                onClick = {
                                    val toRemove = localPhotoPath
                                    if (toRemove != null && toRemove != committedPhotoPath) {
                                        scope.launch(NonCancellable + Dispatchers.IO) {
                                            deletePendingPhotoIfOwned(context.filesDir, toRemove)
                                        }
                                    }
                                    draftCreatedPhotos = draftCreatedPhotos.filterNot { it == toRemove }
                                    localPhotoPath = null
                                    photoSizeBytes = null
                                    photoS3Key = null
                                },
                                enabled = !isCompressingPhoto && !isSaving
                            ) {
                                Text("Quitar Foto", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }

                // Submit Button
                Button(
                    onClick = {
                        errorMessage = null
                        val form = AnimalFormState(
                            id = animalIdToEdit,
                            ranchId = ranchId,
                            folioInterno = folioInterno,
                            siniigaChip = siniigaChip.takeIf { it.isNotBlank() },
                            registroUno = registroUno.takeIf { it.isNotBlank() },
                            adn = adn.takeIf { it.isNotBlank() },
                            breed = breed,
                            color = color,
                            sex = sex,
                            birthDate = birthDate,
                            weaningDate = weaningDate.takeIf { it.isNotBlank() },
                            parturitionType = parturitionType,
                            conceptionMethod = conceptionMethod,
                            sireId = sireId,
                            damId = damId,
                            externalSireFolio = externalSireFolio.takeIf { it.isNotBlank() },
                            externalDamFolio = externalDamFolio.takeIf { it.isNotBlank() },
                            entryReason = entryReason,
                            criadorName = criadorName.takeIf { it.isNotBlank() },
                            localPhotoPath = localPhotoPath,
                            photoS3Key = photoS3Key,
                            lifeStage = lifeStage,
                            reproductiveStatus = reproductiveStatus
                        )
                        onSaveAnimal(draftKey, form)
                    },
                    enabled = !isSaving && !isCompressingPhoto && !isLoadingExisting && !initLoadFailed && !recordNotFound,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BaappPrimaryGreen)
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                    } else {
                        Text(
                            text = if (isEditMode) "Guardar Cambios" else "Registrar Ejemplar en Corral",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                }
            }
        }
    }
}

private fun lifeStageLabel(stage: LifeStage): String = when (stage) {
    LifeStage.ADULT -> "Adulto"
    LifeStage.LAMB_LACTATING -> "Cría Lactante"
    LifeStage.LAMB_FATTENING -> "Cría en Engorda"
    LifeStage.EWE_REPLACEMENT -> "Recría / Reemplazo"
}

private fun reproductiveStatusLabel(status: ReproductiveStatus): String = when (status) {
    ReproductiveStatus.EMPTY -> "Vacía / Inactivo"
    ReproductiveStatus.PREGNANT -> "Gestante"
    ReproductiveStatus.LACTATING -> "Lactando"
    ReproductiveStatus.STUD_RAM -> "Semental Activo"
}

private fun entryReasonLabel(reason: EntryReason): String = when (reason) {
    EntryReason.BIRTH -> "Nacimiento"
    EntryReason.PURCHASE -> "Compra"
    EntryReason.REENTRY -> "Reingreso"
    EntryReason.TRANSFER -> "Traslado"
    EntryReason.OTHER -> "Otro"
}

private fun readBoundedPhotoBytes(inputStream: InputStream, maxBytes: Int): ByteArray {
    val buffer = ByteArray(8 * 1024)
    val output = ByteArrayOutputStream()
    var totalRead = 0
    while (true) {
        val read = inputStream.read(buffer)
        if (read == -1) break
        totalRead += read
        if (totalRead > maxBytes) {
            throw IllegalStateException("La fotografía supera el tamaño máximo permitido (15 MB).")
        }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private fun deletePendingPhotoIfOwned(filesDir: File, path: String?) {
    if (path.isNullOrBlank()) return
    runCatching {
        val ownedDir = File(filesDir, PENDING_PHOTOS_DIR_NAME).canonicalFile
        val target = File(path).canonicalFile
        if (target.parentFile == ownedDir && target.isFile) {
            target.delete()
        }
    }
}
