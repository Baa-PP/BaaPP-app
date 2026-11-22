package com.agrotech.oviapp.android.ui

import android.graphics.BitmapFactory
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agrotech.oviapp.animals.domain.model.AnimalFormState
import com.agrotech.oviapp.animals.domain.model.AnimalSaveResult
import com.agrotech.oviapp.animals.domain.repository.AnimalCommandRepository
import com.agrotech.oviapp.domain.model.AnimalCatalogs
import com.agrotech.oviapp.domain.model.ConceptionMethod
import com.agrotech.oviapp.domain.model.EntryReason
import com.agrotech.oviapp.domain.model.LifeStage
import com.agrotech.oviapp.domain.model.ParturitionType
import com.agrotech.oviapp.domain.model.ReproductiveStatus
import com.agrotech.oviapp.domain.model.Sex
import com.agrotech.oviapp.media.ImageCompressor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

private val BaappPrimaryGreen = Color(0xFF1B5E20)
private val BaappBackground = Color(0xFFF7F9F7)
private val BaappSoftGreen = Color(0xFFE8F5E9)
private val BaappFemalePink = Color(0xFFD81B60)
private val BaappFemalePinkBg = Color(0xFFFCE4EC)
private val BaappMaleBlue = Color(0xFF1565C0)
private val BaappMaleBlueBg = Color(0xFFE3F2FD)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimalFormScreen(
    ranchId: String,
    animalIdToEdit: String? = null,
    commandRepository: AnimalCommandRepository,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var folioInterno by remember { mutableStateOf("") }
    var siniigaChip by remember { mutableStateOf("") }
    var registroUno by remember { mutableStateOf("") }
    var adn by remember { mutableStateOf("") }
    var breed by remember { mutableStateOf("Pelifolk") }
    var color by remember { mutableStateOf("") }
    var sex by remember { mutableStateOf(Sex.FEMALE) }
    var birthDate by remember { mutableStateOf("2026-01-01") }
    var weaningDate by remember { mutableStateOf("") }
    var parturitionType by remember { mutableStateOf(ParturitionType.SIMPLE) }
    var conceptionMethod by remember { mutableStateOf(ConceptionMethod.MN) }
    var entryReason by remember { mutableStateOf(EntryReason.BIRTH) }
    var criadorName by remember { mutableStateOf("") }
    var sireId by remember { mutableStateOf<String?>(null) }
    var damId by remember { mutableStateOf<String?>(null) }
    var externalSireFolio by remember { mutableStateOf("") }
    var externalDamFolio by remember { mutableStateOf("") }
    var lifeStage by remember { mutableStateOf(LifeStage.ADULT) }
    var reproductiveStatus by remember { mutableStateOf(ReproductiveStatus.EMPTY) }

    var localPhotoPath by remember { mutableStateOf<String?>(null) }
    var photoSizeBytes by remember { mutableStateOf<Int?>(null) }
    var isCompressingPhoto by remember { mutableStateOf(false) }

    var damsList by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var siresList by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    var isLoadingExisting by remember { mutableStateOf(false) }

    var breedExpanded by remember { mutableStateOf(false) }
    var reasonExpanded by remember { mutableStateOf(false) }

    // Load parental dropdowns and existing animal if editing
    LaunchedEffect(ranchId, animalIdToEdit) {
        damsList = commandRepository.getPotentialDams(ranchId)
        siresList = commandRepository.getPotentialSires(ranchId)

        if (animalIdToEdit != null) {
            isLoadingExisting = true
            val existing = commandRepository.getAnimalById(animalIdToEdit)
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
            }
            isLoadingExisting = false
        }
    }

    // Photo picker with automatic ~150-200 KB compression
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                isCompressingPhoto = true
                withContext(Dispatchers.IO) {
                    try {
                        val inputBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        if (inputBytes != null) {
                            val compressedBytes = ImageCompressor.compressToTarget(inputBytes, 200 * 1024)
                            val cacheFile = File(context.cacheDir, "animal_${System.currentTimeMillis()}.jpg")
                            FileOutputStream(cacheFile).use { fos ->
                                fos.write(compressedBytes)
                            }
                            localPhotoPath = cacheFile.absolutePath
                            photoSizeBytes = compressedBytes.size
                        }
                    } catch (e: Exception) {
                        errorMessage = "Error procesando fotografía: ${e.message}"
                    }
                }
                isCompressingPhoto = false
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
                    TextButton(onClick = onNavigateBack) {
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
                    .background(BaappBackground)
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = BaappPrimaryGreen)
            }
        } else {
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

                // 1. Identificación Principal
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
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
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("2. Sexo y Características", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = sex == Sex.FEMALE,
                                onClick = { sex = Sex.FEMALE },
                                label = { Text("♀ Hembra") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = BaappFemalePinkBg,
                                    selectedLabelColor = BaappFemalePink
                                ),
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = sex == Sex.MALE,
                                onClick = { sex = Sex.MALE },
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
                                modifier = Modifier.menuAnchor().fillMaxWidth(),
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
                    }
                }

                // 3. Origen y Fechas
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
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
                                value = entryReason.name,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Motivo de Ingreso") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = reasonExpanded) },
                                modifier = Modifier.menuAnchor().fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            )
                            ExposedDropdownMenu(
                                expanded = reasonExpanded,
                                onDismissRequest = { reasonExpanded = false }
                            ) {
                                AnimalCatalogs.ENTRY_REASONS.forEach { r ->
                                    DropdownMenuItem(
                                        text = { Text(r.name) },
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
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("4. Genealogía", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))

                        OutlinedTextField(
                            value = externalDamFolio,
                            onValueChange = { externalDamFolio = it },
                            label = { Text("Madre (Folio o Folio Externo)") },
                            placeholder = { Text("Ej. MADRE-01 o Externa") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        )

                        OutlinedTextField(
                            value = externalSireFolio,
                            onValueChange = { externalSireFolio = it },
                            label = { Text("Semental / Padre (Folio o Folio Externo)") },
                            placeholder = { Text("Ej. SEMENTAL-01 o Externo") },
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
                    colors = CardDefaults.cardColors(containerColor = Color.White)
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
                                enabled = !isCompressingPhoto,
                                modifier = Modifier.heightIn(min = 56.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(if (localPhotoPath == null) "Adjuntar Foto" else "Cambiar Foto")
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

                // Submit Button
                Button(
                    onClick = {
                        errorMessage = null
                        isSaving = true
                        scope.launch {
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
                                lifeStage = lifeStage,
                                reproductiveStatus = reproductiveStatus
                            )
                            val res = commandRepository.saveAnimalOffline(form)
                            isSaving = false
                            when (res) {
                                is AnimalSaveResult.Success -> {
                                    onNavigateBack()
                                }
                                is AnimalSaveResult.DuplicateFolio -> {
                                    errorMessage = res.message
                                }
                                is AnimalSaveResult.ValidationError -> {
                                    errorMessage = res.message
                                }
                                is AnimalSaveResult.Error -> {
                                    errorMessage = res.message
                                }
                            }
                        }
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
