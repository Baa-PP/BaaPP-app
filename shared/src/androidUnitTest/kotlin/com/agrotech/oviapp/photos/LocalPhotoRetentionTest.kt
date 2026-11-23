package com.agrotech.oviapp.photos

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.agrotech.oviapp.db.OviAppDatabase
import com.agrotech.oviapp.sync.PendingSyncQueue
import com.agrotech.oviapp.sync.SyncEntityType
import com.agrotech.oviapp.sync.SyncOperation
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalPhotoRetentionTest {

    @Test
    fun photoPersistsLocallyWhileSyncIsPendingAndDeletesAfterS3UploadCompletes() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OviAppDatabase.Schema.create(driver)
        val database = OviAppDatabase(driver)
        val syncQueue = PendingSyncQueue(database)
        val retention = LocalPhotoRetentionManager(database)

        val tempDir = Files.createTempDirectory("baapp_photo_test").toFile()
        try {
            val validCompressed = CompressedPhoto(
                bytes = ByteArray(175 * 1024) { 1 },
                format = PhotoFormat.JPEG
            )
            val stored = retention.persistPendingPhoto(
                basePendingDirPath = tempDir.absolutePath,
                compressed = validCompressed,
                photoId = "test-175kb"
            )
            assertEquals(175 * 1024, stored.sizeBytes)
            assertTrue(LocalPhotoFileOps.exists(stored.localPhotoPath))

            val mutation = syncQueue.writeAndEnqueue(
                entityType = SyncEntityType.BIRTH_EVENT,
                operation = SyncOperation.CREATE,
                payloadJson = """{"birthEventId":"b1"}""",
                localPhotoPath = stored.localPhotoPath
            ) { }

            // Must NOT delete while mutation is still PENDING S3 upload
            assertTrue(retention.isReferencedByPendingUpload(stored.localPhotoPath))
            assertFalse(retention.deleteIfUploadedAndUnreferenced(stored.localPhotoPath))
            assertTrue(LocalPhotoFileOps.exists(stored.localPhotoPath))

            // Once S3 upload finishes and mutation is marked SYNCED, cleanup deletes the local file
            database.oviAppDatabaseQueries.markSyncEventCompleted(mutation.mutationId)
            assertFalse(retention.isReferencedByPendingUpload(stored.localPhotoPath))
            assertTrue(retention.deleteIfUploadedAndUnreferenced(stored.localPhotoPath))
            assertFalse(LocalPhotoFileOps.exists(stored.localPhotoPath))
        } finally {
            tempDir.deleteRecursively()
            driver.close()
        }
    }

    @Test
    fun persistPendingPhotoRejectsBytesAbove200KbLimit() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OviAppDatabase.Schema.create(driver)
        val database = OviAppDatabase(driver)
        val retention = LocalPhotoRetentionManager(database)
        val tempDir = Files.createTempDirectory("baapp_photo_limit_test").toFile()
        try {
            val oversized = CompressedPhoto(
                bytes = ByteArray(PhotoCompressionConfig.MAX_TARGET_BYTES + 1),
                format = PhotoFormat.WEBP
            )
            assertFailsWith<IllegalArgumentException> {
                retention.persistPendingPhoto(tempDir.absolutePath, oversized, "oversized")
            }
        } finally {
            tempDir.deleteRecursively()
            driver.close()
        }
    }
}
