package com.agrotech.oviapp.births.media

expect object BirthPhotoCompressor {
    fun compressToTarget(inputBytes: ByteArray, maxBytes: Int = 200 * 1024): ByteArray
}
