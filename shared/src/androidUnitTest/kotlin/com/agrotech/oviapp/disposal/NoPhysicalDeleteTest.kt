package com.agrotech.oviapp.disposal

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.agrotech.oviapp.db.OviAppDatabase
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** APP-19: "No existe ninguna acción de borrado físico de animales" verificado sobre el código. */
class NoPhysicalDeleteTest {
    private val deleteAnimals = Regex("""DELETE\s+FROM\s+local_animals\b""", RegexOption.IGNORE_CASE)

    @Test
    fun noQueryOrCodeDeletesAnimalRows() {
        // Gradle corre las pruebas de :shared con el módulo como directorio de trabajo.
        val roots = listOf(File("src"), File("../androidApp/src"))
        val files = roots.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension in setOf("sq", "sqm", "kt") } }
            .filterNot { it.name == "NoPhysicalDeleteTest.kt" }
        assertTrue(files.any { it.extension == "sq" }, "no se encontraron los archivos .sq; revisa el directorio de trabajo")

        val offenders = files.filter { deleteAnimals.containsMatchIn(it.readText()) }.map { it.path }
        assertEquals(emptyList(), offenders)
    }

    @Test
    fun migrationAddsTheStageAtDeathColumnWithoutLosingRows() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        // Tabla tal como quedó en la versión 3 (antes de 3.sqm), con un animal ya guardado.
        driver.execute(null, "CREATE TABLE local_animals (id TEXT NOT NULL PRIMARY KEY, mortality_cause TEXT)", 0)
        driver.execute(null, "INSERT INTO local_animals (id) VALUES ('a1')", 0)

        OviAppDatabase.Schema.migrate(driver, oldVersion = 3, newVersion = 4)
        driver.execute(null, "UPDATE local_animals SET life_stage_at_death = 'ADULT' WHERE id = 'a1'", 0)

        val stage = driver.executeQuery(
            identifier = null,
            sql = "SELECT life_stage_at_death FROM local_animals WHERE id = 'a1'",
            mapper = { cursor -> app.cash.sqldelight.db.QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) },
            parameters = 0,
        ).value
        assertTrue(OviAppDatabase.Schema.version >= 4)
        assertEquals("ADULT", stage)
        driver.close()
    }
}
