package com.agrotech.oviapp.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import kotlin.math.sqrt

actual object ImageCompressor {
    actual fun compressToTarget(inputBytes: ByteArray, maxBytes: Int): ByteArray {
        if (inputBytes.isEmpty()) return inputBytes

        // First decode dimensions only
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(inputBytes, 0, inputBytes.size, options)

        val srcWidth = options.outWidth
        val srcHeight = options.outHeight

        if (srcWidth <= 0 || srcHeight <= 0) return inputBytes

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

        val originalBitmap = BitmapFactory.decodeByteArray(inputBytes, 0, inputBytes.size, decodeOptions)
            ?: return inputBytes

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
}
