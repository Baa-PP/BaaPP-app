package com.agrotech.oviapp.android.photos

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.agrotech.oviapp.photos.LocalPhotoCompressor
import com.agrotech.oviapp.photos.LocalPhotoFileOps
import com.agrotech.oviapp.photos.PhotoCompressionConfig
import com.agrotech.oviapp.photos.PhotoFormat
import com.agrotech.oviapp.photos.StoredLocalPhoto
import com.agrotech.oviapp.sync.UuidV7
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

private val PhotoCardGreen = Color(0xFF1B5E20)
private val PhotoCardSoftGreen = Color(0xFFE8F5E9)

/**
 * Reusable Android Camera + Gallery photo capture component (APP-14).
 *
 * Captures via [ActivityResultContracts.TakePicture] (FileProvider) or gallery picker,
 * compresses to ~150-200 KB (JPEG or WebP), stores under app-private `pending_photos/`,
 * and renders the persisted local file with Coil [AsyncImage].
 */
@Composable
fun PhotoCaptureCard(
    localPhotoPath: String?,
    photoSizeBytes: Int?,
    enabled: Boolean = true,
    preferredFormat: PhotoFormat = PhotoFormat.JPEG,
    onPhotoReady: (StoredLocalPhoto) -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isProcessing by remember { mutableStateOf(false) }
    var pendingCameraFilePath by rememberSaveable { mutableStateOf<String?>(null) }

    fun processSourceUri(sourceUri: Uri, cleanupSourceFile: File? = null) {
        scope.launch {
            isProcessing = true
            try {
                val stored = withContext(Dispatchers.IO) {
                    val stream = context.contentResolver.openInputStream(sourceUri)
                        ?: throw IllegalStateException("No se pudo leer la fotografía capturada.")
                    val rawBytes = stream.use { readBoundedBytes(it, PhotoCompressionConfig.MAX_SOURCE_BYTES) }
                    val compressed = LocalPhotoCompressor.compressToTarget(
                        inputBytes = rawBytes,
                        format = preferredFormat
                    )
                    val pendingDir = File(context.filesDir, PhotoCompressionConfig.PENDING_PHOTOS_DIR_NAME)
                    val fileName = "photo_${UuidV7.generate()}.${compressed.format.extension}"
                    val savedPath = LocalPhotoFileOps.writeAtomically(
                        directoryPath = pendingDir.absolutePath,
                        fileName = fileName,
                        bytes = compressed.bytes
                    )
                    StoredLocalPhoto(
                        localPhotoPath = savedPath,
                        sizeBytes = compressed.sizeBytes,
                        format = compressed.format
                    )
                }
                onPhotoReady(stored)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e.message ?: "Error al comprimir la fotografía.")
            } finally {
                cleanupSourceFile?.delete()
                isProcessing = false
            }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val savedPath = pendingCameraFilePath
        pendingCameraFilePath = null
        val capturedFile = savedPath?.let { File(it) }
        if (success && capturedFile != null && capturedFile.exists()) {
            val uri = Uri.fromFile(capturedFile)
            processSourceUri(uri, cleanupSourceFile = capturedFile)
        } else {
            capturedFile?.delete()
        }
    }

    fun launchCameraCapture() {
        val (file, contentUri) = createCameraTempFileUri(context)
        pendingCameraFilePath = file.absolutePath
        try {
            cameraLauncher.launch(contentUri)
        } catch (e: ActivityNotFoundException) {
            pendingCameraFilePath = null
            file.delete()
            onError("No se encontró una aplicación de cámara disponible. Puedes usar la opción Galería.")
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchCameraCapture()
        } else {
            onError("Se requiere permiso de cámara para tomar la fotografía.")
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            processSourceUri(uri)
        }
    }

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
                text = "Evidencia Fotográfica (~150–200 KB)",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        val hasPermission = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.CAMERA
                        ) == PackageManager.PERMISSION_GRANTED
                        if (hasPermission) {
                            launchCameraCapture()
                        } else {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                    enabled = enabled && !isProcessing,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 52.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PhotoCardGreen)
                ) {
                    Text("Tomar Foto")
                }

                OutlinedButton(
                    onClick = { galleryLauncher.launch("image/*") },
                    enabled = enabled && !isProcessing,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 52.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Galería")
                }
            }

            if (isProcessing) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Comprimiendo fotografía...", style = MaterialTheme.typography.bodySmall)
                }
            }

            if (!localPhotoPath.isNullOrBlank()) {
                AsyncImage(
                    model = File(localPhotoPath),
                    contentDescription = "Fotografía comprimida del evento",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFFECEFF1))
                )

                val kbLabel = photoSizeBytes?.let { "${it / 1024} KB" } ?: "Lista"
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(PhotoCardSoftGreen, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Guardada localmente ($kbLabel) · Pendiente de subida a S3",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = PhotoCardGreen,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }
            }
        }
    }
}

private fun createCameraTempFileUri(context: Context): Pair<File, Uri> {
    val capturesDir = File(context.cacheDir, "camera_captures").apply { mkdirs() }
    val photoFile = File(capturesDir, "capture_${System.currentTimeMillis()}.jpg")
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        photoFile
    )
    return photoFile to uri
}

private fun readBoundedBytes(inputStream: InputStream, maxBytes: Int): ByteArray {
    val buffer = ByteArray(8 * 1024)
    val output = ByteArrayOutputStream()
    var total = 0
    while (true) {
        val read = inputStream.read(buffer)
        if (read == -1) break
        total += read
        if (total > maxBytes) {
            throw IllegalStateException("La fotografía supera el límite de 15 MB.")
        }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}
