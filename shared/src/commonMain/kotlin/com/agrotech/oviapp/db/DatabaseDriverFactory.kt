package com.agrotech.oviapp.db

import app.cash.sqldelight.db.SqlDriver

expect class DatabaseDriverFactory {
    fun createDriver(): SqlDriver
}

fun createDatabase(driverFactory: DatabaseDriverFactory): OviAppDatabase {
    val driver = driverFactory.createDriver()
    return OviAppDatabase(driver)
}
