package com.agrotech.oviapp.media

expect object ImageCompressor {
    /**
     * Compresses the input image bytes into JPEG format targeting ~150-200 KB (at most 200 KB if possible).
     */
    fun compressToTarget(inputBytes: ByteArray, maxBytes: Int = 200 * 1024): ByteArray
}
