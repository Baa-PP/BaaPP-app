package com.agrotech.oviapp.photos

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
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

        // If the original image already fits within the 150-200 KB window, preserve it.
        if (inputBytes.size in minBytes..maxBytes) {
            return CompressedPhoto(bytes = inputBytes, format = format, sizeBytes = inputBytes.size)
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, MAX_INITIAL_DIMENSION)
        }
        var bitmap = BitmapFactory.decodeByteArray(inputBytes, 0, inputBytes.size, decodeOpts)
            ?: throw IllegalArgumentException("No se pudo decodificar la imagen seleccionada.")

        val compressFormat = resolveAndroidCompressFormat(format)
        try {
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
            bitmap.recycle()
        }
    }

    private fun findBestQuality(
        bitmap: Bitmap,
        compressFormat: Bitmap.CompressFormat,
        minBytes: Int,
        maxBytes: Int
    ): ByteArray {
        var low = 35
        var high = 95
        var bestUnderMax: ByteArray? = null

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
        return bestUnderMax ?: encodeBitmap(bitmap, compressFormat, 35)
    }

    private fun encodeBitmap(
        bitmap: Bitmap,
        compressFormat: Bitmap.CompressFormat,
        quality: Int
    ): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(compressFormat, quality, out)
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
}

actual object LocalPhotoFileOps {
    actual fun writeAtomically(directoryPath: String, fileName: String, bytes: ByteArray): String {
        val dir = File(directoryPath).apply { mkdirs() }
        val tempFile = File(dir, "$fileName.tmp")
        val targetFile = File(dir, fileName)
        FileOutputStream(tempFile).use { fos ->
            fos.write(bytes)
            fos.fd.sync()
        }
        if (!tempFile.renameTo(targetFile)) {
            tempFile.copyTo(targetFile, overwrite = true)
            tempFile.delete()
        }
        return targetFile.absolutePath
    }

    actual fun exists(path: String): Boolean {
        if (path.isBlank()) return false
        val file = File(path)
        return file.exists() && file.isFile && file.canRead()
    }

    actual fun deleteIfExists(path: String): Boolean {
        if (path.isBlank()) return false
        val file = File(path)
        return file.exists() && file.delete()
    }
}
