package com.agrotech.oviapp.auth.domain

internal actual class AuthLock {
    private val lock = Any()

    actual inline fun <T> withLock(block: () -> T): T = synchronized(lock) { block() }
}
