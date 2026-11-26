package com.agrotech.oviapp.births.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.agrotech.oviapp.db.OviAppDatabase
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BirthMigrationTest {
    @Test
    fun migrationAddsDraftKeyPreservesRowsAndEnforcesUniqueness() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(
            null,
            """
            CREATE TABLE local_birth_events (
                id TEXT NOT NULL PRIMARY KEY,
                ranch_id TEXT NOT NULL,
                dam_id TEXT NOT NULL,
                sire_id TEXT,
                birth_date TEXT NOT NULL,
                live_births INTEGER NOT NULL DEFAULT 0,
                stillborns INTEGER NOT NULL DEFAULT 0,
                parturition_type TEXT NOT NULL DEFAULT 'SIMPLE',
                conception_method TEXT NOT NULL DEFAULT 'MN',
                photo_s3_key TEXT,
                local_photo_path TEXT,
                registered_by_user_id TEXT,
                notes TEXT,
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """.trimIndent(),
            0
        )
        driver.execute(
            null,
            "INSERT INTO local_birth_events (id, ranch_id, dam_id, birth_date) VALUES ('b-legacy', 'r1', 'd1', '2026-11-01')",
            0
        )

        OviAppDatabase.Schema.migrate(driver, oldVersion = 4, newVersion = 5)

        val db = OviAppDatabase(driver)
        val legacyRow = db.oviAppDatabaseQueries.selectBirthEventById("b-legacy").executeAsOne()
        assertEquals("r1", legacyRow.ranch_id)
        assertEquals(null, legacyRow.draft_key)

        // insertBirthEvent works on upgraded DB with distinct draft_key and multiple NULL draft_keys
        db.oviAppDatabaseQueries.insertBirthEvent(
            id = "b-null-1",
            ranch_id = "r1",
            dam_id = "d1",
            sire_id = null,
            birth_date = "2026-11-20",
            live_births = 1,
            stillborns = 0,
            parturition_type = "SIMPLE",
            conception_method = "MN",
            photo_s3_key = null,
            local_photo_path = "/tmp/p1.jpg",
            registered_by_user_id = null,
            notes = null,
            draft_key = null,
            created_at = "2026-11-20T00:00:00Z",
            updated_at = "2026-11-20T00:00:00Z"
        )
        db.oviAppDatabaseQueries.insertBirthEvent(
            id = "b-key-1",
            ranch_id = "r1",
            dam_id = "d1",
            sire_id = null,
            birth_date = "2026-11-21",
            live_births = 1,
            stillborns = 0,
            parturition_type = "SIMPLE",
            conception_method = "MN",
            photo_s3_key = null,
            local_photo_path = "/tmp/p2.jpg",
            registered_by_user_id = null,
            notes = null,
            draft_key = "dk-1",
            created_at = "2026-11-21T00:00:00Z",
            updated_at = "2026-11-21T00:00:00Z"
        )

        // Duplicate non-null draft_key is rejected by unique index
        assertFailsWith<SQLException> {
            db.oviAppDatabaseQueries.insertBirthEvent(
                id = "b-key-dup",
                ranch_id = "r1",
                dam_id = "d1",
                sire_id = null,
                birth_date = "2026-11-22",
                live_births = 1,
                stillborns = 0,
                parturition_type = "SIMPLE",
                conception_method = "MN",
                photo_s3_key = null,
                local_photo_path = "/tmp/p3.jpg",
                registered_by_user_id = null,
                notes = null,
                draft_key = "dk-1",
                created_at = "2026-11-22T00:00:00Z",
                updated_at = "2026-11-22T00:00:00Z"
            )
        }
        driver.close()
    }
}
