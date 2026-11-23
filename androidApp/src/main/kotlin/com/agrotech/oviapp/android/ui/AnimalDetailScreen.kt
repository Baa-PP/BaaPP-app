package com.agrotech.oviapp.android.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agrotech.oviapp.android.ui.theme.FieldPalette
import com.agrotech.oviapp.animals.domain.model.AnimalDetailWithGenealogy
import com.agrotech.oviapp.animals.domain.model.OffspringSummary
import com.agrotech.oviapp.animals.domain.repository.AnimalInventoryRepository
import com.agrotech.oviapp.domain.model.Animal
import com.agrotech.oviapp.domain.model.AnimalAgeCalculator
import com.agrotech.oviapp.domain.model.AnimalStatus
import com.agrotech.oviapp.domain.model.LifeStage
import com.agrotech.oviapp.domain.model.ReproductiveStatus
import com.agrotech.oviapp.domain.model.Sex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.time.LocalDate

private val BaappPrimaryGreen = Color(FieldPalette.PASTURE)
private val BaappSoftGreen = Color(0xFFE8F5E9)
private val BaappFemalePink = Color(FieldPalette.FEMALE)
private val BaappMaleBlue = Color(FieldPalette.MALE)
private const val MAX_REMOTE_PHOTO_BYTES = 5 * 1024 * 1024
private val OWNED_PHOTO_DIRS = listOf("pending_animal_photos", "pending_photos")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimalDetailScreen(
    animalId: String,
    inventoryRepository: AnimalInventoryRepository,
    onBackClick: () -> Unit,
    onNavigateToAnimal: (String) -> Unit,
    ranchId: String? = null,
    onEditAnimalClick: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBackClick)
    val context = LocalContext.current

    var detail by remember(animalId, ranchId) { mutableStateOf<AnimalDetailWithGenealogy?>(null) }
    var isLoading by remember(animalId, ranchId) { mutableStateOf(true) }
    var errorMessage by remember(animalId, ranchId) { mutableStateOf<String?>(null) }
    var retryTrigger by remember { mutableIntStateOf(0) }

    val loadedAnimalId = detail?.animal?.id
    val loadedLocalPhotoPath = detail?.localPhotoPath
    val loadedPhotoS3Key = detail?.animal?.photoS3Key

    var photoBitmap by remember(loadedAnimalId, loadedLocalPhotoPath, loadedPhotoS3Key) { mutableStateOf<ImageBitmap?>(null) }
    var isLoadingPhoto by remember(loadedAnimalId, loadedLocalPhotoPath, loadedPhotoS3Key) { mutableStateOf(false) }
    var photoStatusMessage by remember(loadedAnimalId, loadedLocalPhotoPath, loadedPhotoS3Key) { mutableStateOf<String?>(null) }

    LaunchedEffect(loadedAnimalId, loadedLocalPhotoPath, loadedPhotoS3Key) {
        if (loadedAnimalId == null) return@LaunchedEffect
        photoBitmap = null
        photoStatusMessage = null
        val isAllowedRemoteUrl = !loadedPhotoS3Key.isNullOrBlank() && isTrustedHttpsMediaUrl(loadedPhotoS3Key)

        val localBytes = if (!loadedLocalPhotoPath.isNullOrBlank()) {
            isLoadingPhoto = true
            try {
                withContext(Dispatchers.IO) {
                    readOwnedLocalPhotoBytes(context.filesDir, loadedLocalPhotoPath, MAX_REMOTE_PHOTO_BYTES)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } finally {
                isLoadingPhoto = false
            }
        } else {
            null
        }

        if (localBytes != null) {
            isLoadingPhoto = true
            try {
                val decoded = withContext(Dispatchers.IO) { decodeSampledBitmapFromBytes(localBytes) }
                if (decoded != null) {
                    photoBitmap = decoded.asImageBitmap()
                } else {
                    photoStatusMessage = "No se pudo decodificar la fotografía local."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                photoStatusMessage = "Error al leer fotografía local."
            } finally {
                isLoadingPhoto = false
            }
        } else if (isAllowedRemoteUrl) {
            isLoadingPhoto = true
            try {
                val decoded = withContext(Dispatchers.IO) {
                    loadBoundedBitmapFromUrl(loadedPhotoS3Key!!, MAX_REMOTE_PHOTO_BYTES)
                }
                if (decoded != null) {
                    photoBitmap = decoded.asImageBitmap()
                } else {
                    photoStatusMessage = "No se pudo cargar la fotografía remota."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                photoStatusMessage = "Fotografía en S3 no disponible sin conexión."
            } finally {
                isLoadingPhoto = false
            }
        } else if (!loadedPhotoS3Key.isNullOrBlank()) {
            photoStatusMessage = "Fotografía sincronizada ($loadedPhotoS3Key) · Pendiente de URL pre-firmada."
        } else {
            photoStatusMessage = "Sin fotografía registrada."
        }
    }

    LaunchedEffect(animalId, ranchId, retryTrigger) {
        isLoading = true
        errorMessage = null
        try {
            detail = inventoryRepository.getAnimalGenealogy(animalId, ranchId)
            if (detail == null) {
                errorMessage = "No se encontró la ficha del ejemplar."
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorMessage = e.message ?: "Error al cargar la ficha genealógica."
        } finally {
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val current = detail?.animal
                    Text(
                        text = if (current != null) {
                            val sexTag = if (current.sex == Sex.MALE) "♂ Macho" else "♀ Hembra"
                            "$sexTag · ${current.folioInterno}"
                        } else {
                            "Ficha del Ejemplar"
                        },
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    TextButton(onClick = onBackClick) {
                        Text("← Volver", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                },
                actions = {
                    val currentAnimal = detail?.animal
                    if (onEditAnimalClick != null && currentAnimal != null) {
                        TextButton(onClick = { onEditAnimalClick(currentAnimal.id) }) {
                            Text("Editar", color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = when (detail?.animal?.sex) {
                        Sex.FEMALE -> BaappFemalePink
                        Sex.MALE -> BaappMaleBlue
                        null -> BaappPrimaryGreen
                    },
                    titleContentColor = Color.White
                )
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding)
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                errorMessage != null -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = errorMessage!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Button(
                            onClick = { retryTrigger++ },
                            modifier = Modifier.heightIn(min = 56.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text("Reintentar")
                        }
                    }
                }
                detail != null -> {
                    val data = detail!!
                    val animal = data.animal
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        item(key = "summary") {
                            AnimalSummaryCard(animal = animal)
                        }

                        item(key = "photo") {
                            AnimalPhotoCard(
                                folioInterno = animal.folioInterno,
                                bitmap = photoBitmap,
                                isLoadingPhoto = isLoadingPhoto,
                                photoStatusMessage = photoStatusMessage
                            )
                        }

                        item(key = "parents") {
                            GenealogyParentsCard(
                                animal = animal,
                                dam = data.dam,
                                sire = data.sire,
                                onNavigateToAnimal = onNavigateToAnimal
                            )
                        }

                        item(key = "offspring_header") {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        text = "Descendencia / Crías (${data.offspring.size})",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                    )
                                    if (data.offspring.isEmpty()) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = "No tiene crías registradas en el rebaño.",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        items(data.offspring, key = { it.id }) { child ->
                            OffspringItemRow(
                                child = child,
                                onClick = { onNavigateToAnimal(child.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnimalSummaryCard(animal: Animal) {
    val isMale = animal.sex == Sex.MALE
    val sexLabel = if (isMale) "♂ MACHO (SEMENTAL)" else "♀ HEMBRA"
    val sexHeaderBg = if (isMale) BaappMaleBlue else BaappFemalePink
    val sexIcon = if (isMale) Icons.Filled.Face else Icons.Filled.Info

    val (statusText, statusBg, statusColor) = statusPresentation(animal.status, animal.sex)

    val referenceDateIso = if (animal.status == AnimalStatus.DECEASED && !animal.mortalityDateIso.isNullOrBlank()) {
        animal.mortalityDateIso!!
    } else {
        LocalDate.now().toString()
    }
    val dynamicAge = remember(animal.birthDateIso, referenceDateIso) {
        AnimalAgeCalculator.calculateAge(animal.birthDateIso, referenceDateIso)
    }
    val ageLabelPrefix = if (animal.status == AnimalStatus.DECEASED && !animal.mortalityDateIso.isNullOrBlank()) {
        "Edad al fallecer"
    } else {
        "Edad dinámica"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // High-contrast sex header (color is never the sole indicator: always icon + text)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(sexHeaderBg)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = sexIcon,
                        contentDescription = sexLabel,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = sexLabel,
                        style = MaterialTheme.typography.titleSmall.copy(
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.5.sp
                        )
                    )
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = statusColor,
                        fontWeight = FontWeight.Bold
                    ),
                    modifier = Modifier
                        .background(statusBg, shape = RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }

            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Arete / Folio: ${animal.folioInterno}",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )
                Text(
                    text = "Raza: ${animal.breed} · Color: ${animal.color}",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )
                Text(
                    text = "$ageLabelPrefix: ${dynamicAge?.formatSpanish() ?: "Fecha inválida"}",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                )
                Text(
                    text = "Fecha de nacimiento: ${animal.birthDateIso}",
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface)
                )
                Text(
                    text = "Fecha de destete: ${animal.weaningDateIso ?: "Pendiente / Sin registro"}",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )
                Text(
                    text = "Etapa: ${lifeStageText(animal.lifeStage)} · Estado rep.: ${reproductiveText(animal.reproductiveStatus)}",
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                )
                if (!animal.siniigaChip.isNullOrBlank()) {
                    Text(
                        text = "Chip SINIIGA: ${animal.siniigaChip}",
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                }
                if (!animal.registroUno.isNullOrBlank() || !animal.adn.isNullOrBlank()) {
                    Text(
                        text = listOfNotNull(
                            animal.registroUno?.let { "UNO: $it" },
                            animal.adn?.let { "ADN: $it" }
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                }
                if (!animal.criadorName.isNullOrBlank()) {
                    Text(
                        text = "Criador / Origen: ${animal.criadorName}",
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                }
                if (animal.status == AnimalStatus.DECEASED) {
                    val mortalityInfo = listOfNotNull(
                        animal.mortalityDateIso?.let { "Fecha: $it" },
                        animal.mortalityCause?.let { "Causa: $it" }
                    ).joinToString(" · ")
                    if (mortalityInfo.isNotBlank()) {
                        Text(
                            text = "Baja por fallecimiento ($mortalityInfo)",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AnimalPhotoCard(
    folioInterno: String,
    bitmap: ImageBitmap?,
    isLoadingPhoto: Boolean,
    photoStatusMessage: String?
) {

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Fotografía del Ejemplar",
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            )
            when {
                isLoadingPhoto -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
                bitmap != null -> {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "Fotografía de $folioInterno",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .clip(RoundedCornerShape(10.dp))
                    )
                }
                else -> {
                    Text(
                        text = photoStatusMessage ?: "Sin fotografía registrada.",
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                }
            }
        }
    }
}

private fun isTrustedHttpsMediaUrl(urlString: String): Boolean {
    val uri = runCatching { URI(urlString.trim()) }.getOrNull() ?: return false
    if (!uri.scheme.equals("https", ignoreCase = true)) return false
    val host = uri.host?.lowercase() ?: return false
    return host.endsWith(".amazonaws.com") || host.endsWith(".cloudfront.net")
}

private fun readOwnedLocalPhotoBytes(filesDir: File, path: String, maxBytes: Int): ByteArray? {
    val target = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
    val allowed = OWNED_PHOTO_DIRS.any { dirName ->
        val ownedDir = runCatching { File(filesDir, dirName).canonicalFile }.getOrNull()
        ownedDir != null && target.parentFile == ownedDir
    }
    if (!allowed || !target.isFile) return null
    val size = target.length()
    if (size <= 0L || size > maxBytes.toLong()) return null
    return target.readBytes()
}

private fun loadBoundedBitmapFromUrl(urlString: String, maxBytes: Int): Bitmap? {
    if (!isTrustedHttpsMediaUrl(urlString)) return null
    val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
        connectTimeout = 5000
        readTimeout = 8000
        instanceFollowRedirects = false
    }
    return try {
        if (connection.responseCode !in 200..299) return null
        connection.inputStream.use { stream ->
            val buffer = ByteArray(8 * 1024)
            val output = ByteArrayOutputStream()
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read == -1) break
                total += read
                if (total > maxBytes) {
                    throw IllegalStateException("Remote image exceeds max size")
                }
                output.write(buffer, 0, read)
            }
            decodeSampledBitmapFromBytes(output.toByteArray())
        }
    } finally {
        connection.disconnect()
    }
}

private fun decodeSampledBitmapFromBytes(bytes: ByteArray): Bitmap? {
    if (bytes.isEmpty()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    val maxDim = 1024
    val largest = maxOf(bounds.outWidth, bounds.outHeight)
    while (largest / sampleSize > maxDim) {
        sampleSize *= 2
    }
    val decodeOpts = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
}

@Composable
private fun GenealogyParentsCard(
    animal: Animal,
    dam: Animal?,
    sire: Animal?,
    onNavigateToAnimal: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Genealogía (Ascendencia)",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
            )

            ParentNavigationRow(
                roleLabel = "♀ Madre",
                parent = dam,
                externalFolio = animal.externalDamFolio,
                onClick = { dam?.let { onNavigateToAnimal(it.id) } }
            )

            ParentNavigationRow(
                roleLabel = "♂ Semental",
                parent = sire,
                externalFolio = animal.externalSireFolio,
                onClick = { sire?.let { onNavigateToAnimal(it.id) } }
            )
        }
    }
}

@Composable
private fun ParentNavigationRow(
    roleLabel: String,
    parent: Animal?,
    externalFolio: String?,
    onClick: () -> Unit
) {
    if (parent != null) {
        val (statusText, statusBg, statusColor) = statusPresentation(parent.status, parent.sex)
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            shape = RoundedCornerShape(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "$roleLabel: ${parent.folioInterno}",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    )
                    Text(
                        text = "Raza: ${parent.breed}",
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall.copy(color = statusColor, fontWeight = FontWeight.SemiBold),
                        modifier = Modifier
                            .background(statusBg, shape = RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Ver →", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }
        }
    } else {
        val text = if (!externalFolio.isNullOrBlank()) {
            "$roleLabel (Externo): $externalFolio"
        } else {
            "$roleLabel: Sin registro"
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
            modifier = Modifier.padding(vertical = 4.dp)
        )
    }
}

@Composable
private fun OffspringItemRow(
    child: OffspringSummary,
    onClick: () -> Unit
) {
    val isMale = child.sex == Sex.MALE
    val sexSymbol = if (isMale) "♂" else "♀"
    val (statusText, statusBg, statusColor) = statusPresentation(child.status, child.sex)

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "$sexSymbol Arete: ${child.folioInterno}",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = "Nac: ${child.birthDateIso} · ${child.breed}",
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                )
                if (child.status == AnimalStatus.DECEASED && !child.mortalityDateIso.isNullOrBlank()) {
                    Text(
                        text = "Baja por fallecimiento (${child.mortalityDateIso})",
                        style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.error)
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall.copy(color = statusColor, fontWeight = FontWeight.Bold),
                    modifier = Modifier
                        .background(statusBg, shape = RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("→", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun statusPresentation(status: AnimalStatus, sex: Sex): Triple<String, Color, Color> = when (status) {
    AnimalStatus.ACTIVE -> Triple(
        if (sex == Sex.MALE) "Activo" else "Activa",
        BaappSoftGreen,
        BaappPrimaryGreen
    )
    AnimalStatus.DECEASED -> Triple(
        if (sex == Sex.MALE) "Fallecido" else "Fallecida",
        Color(0xFFFFEBEE),
        Color(0xFFB71C1C)
    )
    AnimalStatus.SOLD -> Triple(
        if (sex == Sex.MALE) "Vendido" else "Vendida",
        Color(0xFFFFF8E1),
        Color(0xFF8D6E00)
    )
}

private fun lifeStageText(stage: LifeStage): String = when (stage) {
    LifeStage.ADULT -> "Adulto"
    LifeStage.LAMB_LACTATING -> "Cría Lactante"
    LifeStage.LAMB_FATTENING -> "Cría Engorda"
    LifeStage.EWE_REPLACEMENT -> "Recría / Reemplazo"
}

private fun reproductiveText(status: ReproductiveStatus): String = when (status) {
    ReproductiveStatus.EMPTY -> "Vacía"
    ReproductiveStatus.PREGNANT -> "Gestante"
    ReproductiveStatus.LACTATING -> "Lactando"
    ReproductiveStatus.STUD_RAM -> "Semental Activo"
}
