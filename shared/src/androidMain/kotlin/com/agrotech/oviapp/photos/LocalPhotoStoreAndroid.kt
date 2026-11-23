package com.agrotech.oviapp.photos

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Build
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

actual object LocalPhotoCompressor {
    private const val MAX_INITIAL_DIMENSION = 2048

    actual fun compressToTarget(
        inputBytes: ByteArray,
        format: PhotoFormat,
        minBytes: Int,
        maxBytes: Int
    ): CompressedPhoto {
        require(inputBytes.isNotEmpty()) { "La imagen seleccionada está vacía." }
        require(minBytes in 1..maxBytes) { "Rango objetivo de compresión inválido." }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(inputBytes, 0, inputBytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) {
            "El archivo seleccionado no es una imagen válida."
        }

        val orientation = readExifOrientation(inputBytes)
        val hasNormalOrientation = orientation == ExifInterface.ORIENTATION_NORMAL ||
            orientation == ExifInterface.ORIENTATION_UNDEFINED

        // Preserve unchanged bytes when already <= maxBytes, format matches, and no rotation/flip is needed.
        if (inputBytes.size <= maxBytes &&
            mimeMatchesFormat(bounds.outMimeType, format) &&
            hasNormalOrientation
        ) {
            return CompressedPhoto(bytes = inputBytes, format = format, sizeBytes = inputBytes.size)
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, MAX_INITIAL_DIMENSION)
        }
        val decoded = BitmapFactory.decodeByteArray(inputBytes, 0, inputBytes.size, decodeOpts)
            ?: throw IllegalArgumentException("No se pudo decodificar la imagen seleccionada.")
        var bitmap = decoded

        val compressFormat = resolveAndroidCompressFormat(format)
        try {
            bitmap = applyExifOrientation(decoded, orientation)
            var compressed = findBestQuality(bitmap, compressFormat, minBytes, maxBytes)

            while (compressed.size > maxBytes && maxOf(bitmap.width, bitmap.height) > 100) {
                val nextWidth = maxOf(1, (bitmap.width * 0.75f).toInt())
                val nextHeight = maxOf(1, (bitmap.height * 0.75f).toInt())
                val scaled = Bitmap.createScaledBitmap(bitmap, nextWidth, nextHeight, true)
                if (scaled !== bitmap) {
                    bitmap.recycle()
                    bitmap = scaled
                }
                compressed = findBestQuality(bitmap, compressFormat, minBytes, maxBytes)
            }

            if (compressed.size > maxBytes) {
                throw IllegalStateException(
                    "No se pudo comprimir la fotografía por debajo de ${maxBytes / 1024} KB."
                )
            }
            return CompressedPhoto(bytes = compressed, format = format, sizeBytes = compressed.size)
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    private fun findBestQuality(
        bitmap: Bitmap,
        compressFormat: Bitmap.CompressFormat,
        minBytes: Int,
        maxBytes: Int
    ): ByteArray {
        val lowestCandidate = encodeBitmap(bitmap, compressFormat, 35)
        if (lowestCandidate.size > maxBytes) {
            return lowestCandidate
        }

        var low = 36
        var high = 95
        var bestUnderMax = lowestCandidate

        while (low <= high) {
            val mid = (low + high) / 2
            val candidate = encodeBitmap(bitmap, compressFormat, mid)
            if (candidate.size in minBytes..maxBytes) {
                return candidate
            }
            if (candidate.size <= maxBytes) {
                bestUnderMax = candidate
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return bestUnderMax
    }

    private fun encodeBitmap(
        bitmap: Bitmap,
        compressFormat: Bitmap.CompressFormat,
        quality: Int
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val ok = bitmap.compress(compressFormat, quality, out)
        check(ok && out.size() > 0) {
            "No se pudo codificar la fotografía comprimida."
        }
        return out.toByteArray()
    }

    private fun resolveAndroidCompressFormat(format: PhotoFormat): Bitmap.CompressFormat =
        when (format) {
            PhotoFormat.JPEG -> Bitmap.CompressFormat.JPEG
            PhotoFormat.WEBP -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
        }

    private fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (maxOf(w, h) > maxDimension) {
            sample *= 2
            w /= 2
            h /= 2
        }
        return sample
    }

    private fun mimeMatchesFormat(mimeType: String?, format: PhotoFormat): Boolean =
        when (format) {
            PhotoFormat.JPEG -> mimeType.equals("image/jpeg", ignoreCase = true)
            PhotoFormat.WEBP -> mimeType.equals("image/webp", ignoreCase = true)
        }

    private fun readExifOrientation(inputBytes: ByteArray): Int =
        runCatching {
            ByteArrayInputStream(inputBytes).use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            else -> return bitmap
        }
        val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (transformed !== bitmap) {
            bitmap.recycle()
        }
        return transformed
    }
}

actual object LocalPhotoFileOps {
    private val safeFileNameRegex = Regex("^[A-Za-z0-9._-]{1,180}$")

    actual fun writeAtomically(directoryPath: String, fileName: String, bytes: ByteArray): String {
        require(bytes.isNotEmpty()) { "No se puede guardar una fotografía vacía." }
        require(safeFileNameRegex.matches(fileName) && !fileName.contains("..")) {
            "Nombre de archivo inválido."
        }
        val dir = File(directoryPath).apply { mkdirs() }.canonicalFile
        val tempFile = File(dir, "$fileName.tmp").canonicalFile
        val targetFile = File(dir, fileName).canonicalFile
        require(tempFile.parentFile == dir && targetFile.parentFile == dir) {
            "Ruta de destino fuera del directorio permitido."
        }
        try {
            FileOutputStream(tempFile).use { fos ->
                fos.write(bytes)
                fos.fd.sync()
            }
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
            return targetFile.canonicalPath
        } catch (t: Throwable) {
            runCatching { if (tempFile.exists()) tempFile.delete() }
            throw t
        }
    }

    actual fun canonicalize(path: String): String? {
        if (path.isBlank()) return null
        return runCatching { File(path.trim()).canonicalPath }.getOrNull()
    }

    actual fun exists(path: String): Boolean {
        if (path.isBlank()) return false
        val file = File(path.trim())
        return file.exists() && file.isFile && file.canRead()
    }

    actual fun deleteIfExists(path: String, allowedDirectoryPath: String?): Boolean {
        if (path.isBlank()) return false
        val targetFile = runCatching { File(path.trim()).canonicalFile }.getOrNull() ?: return false
        if (allowedDirectoryPath != null) {
            val allowedDir = runCatching { File(allowedDirectoryPath).canonicalFile }.getOrNull() ?: return false
            if (targetFile.parentFile != allowedDir) {
                return false
            }
        }
        return targetFile.exists() && targetFile.isFile && targetFile.delete()
    }
}
