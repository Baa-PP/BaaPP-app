package com.agrotech.oviapp.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

actual object ImageCompressor {
    actual fun compressToTarget(inputBytes: ByteArray, maxBytes: Int): ByteArray {
        if (inputBytes.isEmpty()) {
            throw IllegalArgumentException("La fotografía está vacía.")
        }

        // First decode dimensions only
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(inputBytes, 0, inputBytes.size, options)

        val srcWidth = options.outWidth
        val srcHeight = options.outHeight

        if (srcWidth <= 0 || srcHeight <= 0) {
            throw IllegalArgumentException("No se pudo decodificar la fotografía seleccionada.")
        }

        // Scale down if resolution is larger than full HD (1920x1080) to conserve memory in field conditions
        val maxDim = 1920
        var sampleSize = 1
        val largestDim = maxOf(srcWidth, srcHeight)
        while (largestDim / sampleSize > maxDim) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565 // 2 bytes per pixel
        }

        val decodedBitmap = BitmapFactory.decodeByteArray(inputBytes, 0, inputBytes.size, decodeOptions)
            ?: throw IllegalArgumentException("No se pudo decodificar la fotografía seleccionada.")

        val originalBitmap = applyExifOrientation(decodedBitmap, inputBytes)

        var quality = 85
        var outputStream = ByteArrayOutputStream()
        originalBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        var compressed = outputStream.toByteArray()

        // Iteratively lower quality or resize if still exceeding maxBytes (~150-200 KB target)
        var currentBitmap = originalBitmap
        while (compressed.size > maxBytes && quality > 40) {
            quality -= 15
            outputStream = ByteArrayOutputStream()
            currentBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            compressed = outputStream.toByteArray()
        }

        // If still over budget after quality 40, downscale dimensions
        while (compressed.size > maxBytes && currentBitmap.width > 400 && currentBitmap.height > 400) {
            val scaleFactor = 0.75f
            val newWidth = (currentBitmap.width * scaleFactor).roundToInt()
            val newHeight = (currentBitmap.height * scaleFactor).roundToInt()
            val scaledBitmap = Bitmap.createScaledBitmap(currentBitmap, newWidth, newHeight, true)
            if (currentBitmap != originalBitmap) {
                currentBitmap.recycle()
            }
            currentBitmap = scaledBitmap
            outputStream = ByteArrayOutputStream()
            currentBitmap.compress(Bitmap.CompressFormat.JPEG, 75, outputStream)
            compressed = outputStream.toByteArray()
        }

        if (currentBitmap != originalBitmap) {
            currentBitmap.recycle()
        }
        originalBitmap.recycle()

        return compressed
    }

    private fun applyExifOrientation(bitmap: Bitmap, inputBytes: ByteArray): Bitmap {
        val orientation = try {
            ByteArrayInputStream(inputBytes).use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        if (orientation == ExifInterface.ORIENTATION_NORMAL ||
            orientation == ExifInterface.ORIENTATION_UNDEFINED
        ) {
            return bitmap
        }

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
            else -> return bitmap
        }

        val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (oriented != bitmap) {
            bitmap.recycle()
        }
        return oriented
    }
}
