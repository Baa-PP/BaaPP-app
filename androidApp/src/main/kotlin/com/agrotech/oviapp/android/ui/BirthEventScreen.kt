package com.agrotech.oviapp.android.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agrotech.oviapp.android.BirthSaveUiState
import com.agrotech.oviapp.births.domain.model.BirthEventDraft
import com.agrotech.oviapp.births.domain.model.LiveOffspringDraft
import com.agrotech.oviapp.births.domain.repository.BirthEventRepository
import com.agrotech.oviapp.births.media.BirthPhotoCompressor
import com.agrotech.oviapp.domain.model.ConceptionMethod
import com.agrotech.oviapp.domain.model.ParturitionType
import com.agrotech.oviapp.domain.model.Sex
import com.agrotech.oviapp.domain.model.UuidV7
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.time.LocalDate

private val BaappPrimaryGreen = Color(0xFF1B5E20)
private val BaappBackground = Color(0xFFF7F9F7)
private val BaappSoftGreen = Color(0xFFE8F5E9)
private val BaappFemalePink = Color(0xFFAD1457)
private val BaappFemalePinkBg = Color(0xFFFCE4EC)
private val BaappMaleBlue = Color(0xFF0D47A1)
private val BaappMaleBlueBg = Color(0xFFE3F2FD)
private const val MAX_SOURCE_BIRTH_PHOTO_BYTES = 15 * 1024 * 1024
private const val PENDING_BIRTH_PHOTOS_DIR = "pending_birth_photos"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BirthEventScreen(
    ranchId: String,
    registeredByUsername: String,
    birthEventRepository: BirthEventRepository,
    saveState: BirthSaveUiState = BirthSaveUiState.Idle,
    onSaveBirthEvent: (String, BirthEventDraft, String) -> Unit = { _, _, _ -> },
    onResetSaveState: () -> Unit = {},
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val draftKey = rememberSaveable(ranchId) { UuidV7.generate() }

    var damId by rememberSaveable { mutableStateOf("") }
    var sireId by rememberSaveable { mutableStateOf<String?>(null) }
    var conceptionMethod by rememberSaveable { mutableStateOf(ConceptionMethod.MN) }
    var birthDateIso by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var stillborns by rememberSaveable { mutableIntStateOf(0) }
    var liveOffspring by rememberSaveable(
        stateSaver = listSaver<List<LiveOffspringDraft>, String>(
            save = { list ->
                list.flatMap { listOf(it.folioInterno, it.sex.name, it.color, it.birthWeightKg) }
            },
            restore = { flat ->
                flat.chunked(4).mapNotNull { chunk ->
                    if (chunk.size == 4) {
                        LiveOffspringDraft(
                            folioInterno = chunk[0],
                            sex = try { Sex.valueOf(chunk[1]) } catch (_: Exception) { Sex.FEMALE },
                            color = chunk[2],
                            birthWeightKg = chunk[3]
                        )
                    } else null
                }.ifEmpty { listOf(LiveOffspringDraft()) }
            }
        )
    ) { mutableStateOf(listOf(LiveOffspringDraft())) }

    var localPhotoPath by rememberSaveable { mutableStateOf<String?>(null) }
    var photoSizeBytes by rememberSaveable { mutableStateOf<Int?>(null) }
    var draftCreatedPhotos by rememberSaveable(
        stateSaver = listSaver(save = { it }, restore = { it })
    ) { mutableStateOf(emptyList<String>()) }
    var committedPhotoPath by rememberSaveable { mutableStateOf<String?>(null) }
    var notes by rememberSaveable { mutableStateOf("") }

    var damsList by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var siresList by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var isLoadingParents by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var retryTrigger by remember { mutableIntStateOf(0) }
    var isCompressingPhoto by remember { mutableStateOf(false) }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }

    var damExpanded by remember { mutableStateOf(false) }
    var sireExpanded by remember { mutableStateOf(false) }

    val isSaving = saveState is BirthSaveUiState.Saving && saveState.draftKey == draftKey

    LaunchedEffect(saveState, draftKey) {
        when (saveState) {
            is BirthSaveUiState.Success -> {
                if (saveState.draftKey == draftKey) {
                    committedPhotoPath = saveState.localPhotoPath
                    draftCreatedPhotos
                        .filter { it != saveState.localPhotoPath && it != committedPhotoPath }
                        .forEach { obsolete -> deleteBirthPhotoIfOwned(context.filesDir, obsolete) }
                    draftCreatedPhotos = emptyList()
                    onResetSaveState()
                    onNavigateBack()
                }
            }
            is BirthSaveUiState.Error -> {
                if (saveState.draftKey == draftKey) {
                    errorMessage = saveState.message
                    onResetSaveState()
                }
            }
            else -> Unit
        }
    }

    val handleCancelAndCleanup: () -> Unit = {
        draftCreatedPhotos
            .filter { it != committedPhotoPath }
            .forEach { path -> deleteBirthPhotoIfOwned(context.filesDir, path) }
        draftCreatedPhotos = emptyList()
        onResetSaveState()
        onNavigateBack()
    }

    BackHandler(enabled = true) {
        if (!isSaving) {
            handleCancelAndCleanup()
        }
    }

    LaunchedEffect(ranchId, retryTrigger) {
        isLoadingParents = true
        loadFailed = false
        errorMessage = null
        try {
            damsList = birthEventRepository.getPotentialDams(ranchId)
            siresList = birthEventRepository.getPotentialSires(ranchId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadFailed = true
            errorMessage = e.message ?: "No se pudieron cargar los progenitores del rancho."
        } finally {
            isLoadingParents = false
        }
    }

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
                        val inputBytes = stream.use { readBoundedBirthPhotoBytes(it, MAX_SOURCE_BIRTH_PHOTO_BYTES) }
                        val compressed = BirthPhotoCompressor.compressToTarget(inputBytes, 200 * 1024)
                        val photosDir = File(context.filesDir, PENDING_BIRTH_PHOTOS_DIR).apply { mkdirs() }
                        val photoFile = File(photosDir, "birth_${System.currentTimeMillis()}.jpg")
                        createdFile = photoFile
                        FileOutputStream(photoFile).use { fos -> fos.write(compressed) }
                        photoFile.absolutePath to compressed.size
                    }
                    val (newPath, compressedSize) = result
                    if (localPhotoPath != committedPhotoPath) {
                        deleteBirthPhotoIfOwned(context.filesDir, localPhotoPath)
                    }
                    draftCreatedPhotos = draftCreatedPhotos.filterNot { it == localPhotoPath } + newPath
                    localPhotoPath = newPath
                    photoSizeBytes = compressedSize
                } catch (e: CancellationException) {
                    createdFile?.let { deleteBirthPhotoIfOwned(context.filesDir, it.absolutePath) }
                    throw e
                } catch (e: Exception) {
                    createdFile?.let { deleteBirthPhotoIfOwned(context.filesDir, it.absolutePath) }
                    errorMessage = "Error procesando fotografía: ${e.message}"
                } finally {
                    isCompressingPhoto = false
                }
            }
        }
    }

    val previewDraft = BirthEventDraft(
        ranchId = ranchId,
        damId = damId,
        sireId = sireId,
        conceptionMethod = conceptionMethod,
        birthDateIso = birthDateIso,
        stillborns = stillborns,
        liveOffspring = liveOffspring,
        localPhotoPath = localPhotoPath
    )
    val deducedType = previewDraft.deducedParturitionType()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Registro de Parto (≤ 30 días)", fontWeight = FontWeight.Bold) },
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
        when {
            isLoadingParents -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(BaappBackground)
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = BaappPrimaryGreen)
                }
            }
            loadFailed -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(BaappBackground)
                        .padding(innerPadding)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = errorMessage ?: "Error cargando progenitores.",
                        color = Color(0xFFB71C1C),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { retryTrigger++ },
                        modifier = Modifier.heightIn(min = 56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BaappPrimaryGreen)
                    ) {
                        Text("Reintentar")
                    }
                }
            }
            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(BaappBackground)
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
                                color = Color(0xFFB71C1C),
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }

                    // 1. Progenitores, Concepción y Fecha
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                "1. Madre, Semental y Fecha",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                            )

                            val selectedDamLabel = damsList.firstOrNull { it.first == damId }?.second
                                ?: "Seleccionar madre *"
                            ExposedDropdownMenuBox(
                                expanded = damExpanded,
                                onExpandedChange = { damExpanded = it },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                OutlinedTextField(
                                    value = selectedDamLabel,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("Madre (Hembra del rancho) *") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = damExpanded) },
                                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                ExposedDropdownMenu(
                                    expanded = damExpanded,
                                    onDismissRequest = { damExpanded = false }
                                ) {
                                    damsList.forEach { (id, folio) ->
                                        DropdownMenuItem(
                                            text = { Text(folio) },
                                            onClick = {
                                                damId = id
                                                damExpanded = false
                                            }
                                        )
                                    }
                                }
                            }

                            val selectedSireLabel = siresList.firstOrNull { it.first == sireId }?.second
                                ?: "Ninguno / No especificado"
                            ExposedDropdownMenuBox(
                                expanded = sireExpanded,
                                onExpandedChange = { sireExpanded = it },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                OutlinedTextField(
                                    value = selectedSireLabel,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("Semental (Opcional)") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sireExpanded) },
                                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                ExposedDropdownMenu(
                                    expanded = sireExpanded,
                                    onDismissRequest = { sireExpanded = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Ninguno / No especificado") },
                                        onClick = {
                                            sireId = null
                                            sireExpanded = false
                                        }
                                    )
                                    siresList.forEach { (id, folio) ->
                                        DropdownMenuItem(
                                            text = { Text(folio) },
                                            onClick = {
                                                sireId = id
                                                sireExpanded = false
                                            }
                                        )
                                    }
                                }
                            }

                            Text(
                                "Método de Concepción",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ConceptionMethod.entries.forEach { method ->
                                    FilterChip(
                                        selected = conceptionMethod == method,
                                        onClick = { conceptionMethod = method },
                                        label = { Text(method.name) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = BaappSoftGreen
                                        ),
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }

                            OutlinedTextField(
                                value = birthDateIso,
                                onValueChange = { birthDateIso = it },
                                label = { Text("Fecha del Parto (YYYY-MM-DD) *") },
                                placeholder = { Text("2026-11-22") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            )
                        }
                    }

                    // 2. Nacidos Vivos, Muertos y Tipo de Parto Deducido
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "2. Crías del Parto",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = "Tipo: ${parturitionLabel(deducedType)}",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = BaappPrimaryGreen,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    modifier = Modifier
                                        .background(BaappSoftGreen, shape = RoundedCornerShape(4.dp))
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }

                            StepperRow(
                                label = "Nacidos vivos",
                                value = liveOffspring.size,
                                onDecrement = {
                                    if (liveOffspring.size > 0 && (liveOffspring.size - 1 + stillborns) >= 1) {
                                        liveOffspring = liveOffspring.dropLast(1)
                                    }
                                },
                                onIncrement = {
                                    if ((liveOffspring.size + stillborns) < 5) {
                                        liveOffspring = liveOffspring + LiveOffspringDraft()
                                    }
                                }
                            )

                            StepperRow(
                                label = "Nacidos muertos",
                                value = stillborns,
                                onDecrement = {
                                    if (stillborns > 0 && (liveOffspring.size + stillborns - 1) >= 1) {
                                        stillborns--
                                    }
                                },
                                onIncrement = {
                                    if ((liveOffspring.size + stillborns) < 5) {
                                        stillborns++
                                    }
                                }
                            )
                        }
                    }

                    // 3. Datos por Cría Viva
                    liveOffspring.forEachIndexed { index, child ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = "Cría Viva #${index + 1}",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                )

                                OutlinedTextField(
                                    value = child.folioInterno,
                                    onValueChange = { updated ->
                                        liveOffspring = liveOffspring.toMutableList().also {
                                            it[index] = child.copy(folioInterno = updated)
                                        }
                                    },
                                    label = { Text("Arete / Folio Interno *") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp)
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    FilterChip(
                                        selected = child.sex == Sex.FEMALE,
                                        onClick = {
                                            liveOffspring = liveOffspring.toMutableList().also {
                                                it[index] = child.copy(sex = Sex.FEMALE)
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
                                        selected = child.sex == Sex.MALE,
                                        onClick = {
                                            liveOffspring = liveOffspring.toMutableList().also {
                                                it[index] = child.copy(sex = Sex.MALE)
                                            }
                                        },
                                        label = { Text("♂ Macho") },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = BaappMaleBlueBg,
                                            selectedLabelColor = BaappMaleBlue
                                        ),
                                        modifier = Modifier.weight(1f)
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedTextField(
                                        value = child.color,
                                        onValueChange = { updated ->
                                            liveOffspring = liveOffspring.toMutableList().also {
                                                it[index] = child.copy(color = updated)
                                            }
                                        },
                                        label = { Text("Color *") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    OutlinedTextField(
                                        value = child.birthWeightKg,
                                        onValueChange = { updated ->
                                            liveOffspring = liveOffspring.toMutableList().also {
                                                it[index] = child.copy(birthWeightKg = updated)
                                            }
                                        },
                                        label = { Text("Peso nac. (kg) *") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                }
                            }
                        }
                    }

                    // 4. Fotografía Obligatoria (~150-200 KB)
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                "4. Fotografía del Parto (Obligatoria)",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                            )

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
                                    Text(if (localPhotoPath == null) "Adjuntar Foto *" else "Cambiar Foto")
                                }

                                if (isCompressingPhoto) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                } else if (photoSizeBytes != null) {
                                    Text(
                                        text = "Comprimida: ~${photoSizeBytes!! / 1024} KB (OK)",
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
                                    style = MaterialTheme.typography.bodySmall.copy(color = Color.Gray)
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text("Notas del parto (opcional)") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )

                    Button(
                        onClick = {
                            errorMessage = null
                            val draft = BirthEventDraft(
                                ranchId = ranchId,
                                damId = damId,
                                sireId = sireId,
                                conceptionMethod = conceptionMethod,
                                birthDateIso = birthDateIso,
                                stillborns = stillborns,
                                liveOffspring = liveOffspring,
                                localPhotoPath = localPhotoPath,
                                registeredByUserId = registeredByUsername,
                                notes = notes.takeIf { it.isNotBlank() }
                            )
                            onSaveBirthEvent(draftKey, draft, LocalDate.now().toString())
                        },
                        enabled = !isSaving && !isCompressingPhoto,
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
                                text = "Registrar Parto en Corral",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepperRow(
    label: String,
    value: Int,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onDecrement,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text("-", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
            OutlinedButton(
                onClick = onIncrement,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text("+", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        }
    }
}

private fun parturitionLabel(type: ParturitionType): String = when (type) {
    ParturitionType.SIMPLE -> "SIMPLE (1)"
    ParturitionType.TWIN -> "CUATE / DOBLE (2)"
    ParturitionType.TRIPLET -> "TRILLIZOS (3)"
    ParturitionType.QUADRUPLET -> "CUATRILLIZOS (4)"
    ParturitionType.QUINTUPLET -> "QUINTILLIZOS (5)"
}

private fun readBoundedBirthPhotoBytes(inputStream: InputStream, maxBytes: Int): ByteArray {
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

private fun deleteBirthPhotoIfOwned(filesDir: File, path: String?) {
    if (path.isNullOrBlank()) return
    val ownedDir = File(filesDir, PENDING_BIRTH_PHOTOS_DIR).canonicalFile
    val target = File(path).canonicalFile
    if (target.parentFile == ownedDir && target.exists()) {
        target.delete()
    }
}
