package com.agrotech.oviapp.births.data

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.agrotech.oviapp.db.OviAppDatabase

actual class BirthDatabaseDriverFactory(private val context: Context) {
    actual fun createDriver(): SqlDriver = AndroidSqliteDriver(
        schema = OviAppDatabase.Schema,
        context = context,
        name = "oviapp_database.db"
    )
}
