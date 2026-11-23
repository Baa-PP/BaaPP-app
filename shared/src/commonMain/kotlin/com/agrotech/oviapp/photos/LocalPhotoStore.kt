package com.agrotech.oviapp.photos

import com.agrotech.oviapp.db.OviAppDatabase
import com.agrotech.oviapp.sync.UuidV7

enum class PhotoFormat(val extension: String) {
    JPEG("jpg"),
    WEBP("webp")
}

object PhotoCompressionConfig {
    const val MIN_TARGET_BYTES: Int = 150 * 1024
    const val MAX_TARGET_BYTES: Int = 200 * 1024
    const val MAX_SOURCE_BYTES: Int = 15 * 1024 * 1024
    const val PENDING_PHOTOS_DIR_NAME: String = "pending_photos"
}

data class CompressedPhoto(
    val bytes: ByteArray,
    val format: PhotoFormat,
    val sizeBytes: Int = bytes.size
)

data class StoredLocalPhoto(
    val localPhotoPath: String,
    val sizeBytes: Int,
    val format: PhotoFormat
)

expect object LocalPhotoCompressor {
    fun compressToTarget(
        inputBytes: ByteArray,
        format: PhotoFormat = PhotoFormat.JPEG,
        minBytes: Int = PhotoCompressionConfig.MIN_TARGET_BYTES,
        maxBytes: Int = PhotoCompressionConfig.MAX_TARGET_BYTES
    ): CompressedPhoto
}

expect object LocalPhotoFileOps {
    fun writeAtomically(directoryPath: String, fileName: String, bytes: ByteArray): String
    fun exists(path: String): Boolean
    fun deleteIfExists(path: String): Boolean
}

/**
 * Manages local photo persistence for the S3 upload queue (APP-14).
 *
 * Photos are written to app-private storage under [PhotoCompressionConfig.PENDING_PHOTOS_DIR_NAME]
 * and must remain on disk until every `PENDING` sync mutation referencing `local_photo_path`
 * has been marked `SYNCED` after S3 upload.
 */
class LocalPhotoRetentionManager(
    private val database: OviAppDatabase
) {
    fun persistPendingPhoto(
        basePendingDirPath: String,
        compressed: CompressedPhoto,
        photoId: String = UuidV7.generate()
    ): StoredLocalPhoto {
        val actualSizeBytes = compressed.bytes.size
        require(actualSizeBytes <= PhotoCompressionConfig.MAX_TARGET_BYTES) {
            "La fotografía comprimida excede el límite de ${PhotoCompressionConfig.MAX_TARGET_BYTES / 1024} KB."
        }
        val fileName = "photo_${photoId}.${compressed.format.extension}"
        val path = LocalPhotoFileOps.writeAtomically(basePendingDirPath, fileName, compressed.bytes)
        return StoredLocalPhoto(
            localPhotoPath = path,
            sizeBytes = actualSizeBytes,
            format = compressed.format
        )
    }

    fun isReferencedByPendingUpload(localPhotoPath: String): Boolean {
        val clean = localPhotoPath.trim()
        if (clean.isEmpty()) return false
        return database.oviAppDatabaseQueries
            .countPendingSyncEventsByLocalPhoto(clean)
            .executeAsOne() > 0L
    }

    /**
     * Deletes [localPhotoPath] only if S3 upload has completed (no `PENDING` row in
     * `pending_sync_events` still references it). Returns true if deleted.
     */
    fun deleteIfUploadedAndUnreferenced(localPhotoPath: String): Boolean {
        if (isReferencedByPendingUpload(localPhotoPath)) {
            return false
        }
        return LocalPhotoFileOps.deleteIfExists(localPhotoPath)
    }
}
