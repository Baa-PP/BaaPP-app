package com.agrotech.oviapp.births.data

import app.cash.sqldelight.db.SqlDriver

expect class BirthDatabaseDriverFactory {
    fun createDriver(): SqlDriver
}
