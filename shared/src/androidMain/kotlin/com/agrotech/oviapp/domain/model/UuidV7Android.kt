package com.agrotech.oviapp.domain.model

import java.security.SecureRandom

private val secureRandom = SecureRandom()

internal actual fun currentEpochMillis(): Long = System.currentTimeMillis()

internal actual fun nextRandomBytes(size: Int): ByteArray =
    ByteArray(size).also { secureRandom.nextBytes(it) }
