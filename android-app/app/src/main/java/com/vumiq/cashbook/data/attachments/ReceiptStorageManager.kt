package com.vumiq.cashbook.data.attachments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.vumiq.cashbook.domain.BlueprintConstraints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

sealed interface StoredReceiptOutcome {
    data class Success(
        val localPath: String,
        val attachmentName: String,
        val attachmentMime: String = "image/jpeg",
        val byteSize: Long
    ) : StoredReceiptOutcome

    data class Failure(val message: String) : StoredReceiptOutcome
}

/**
 * Private internal receipt attachment storage manager (Phase 5).
 *
 * Guarantees:
 * 1. Stores receipt images exclusively inside app-private `files/receipts/` (`android:exported="false"`).
 * 2. Downscales oversized camera photos (`maxDimensionPx = 1600`, JPEG quality `82`) and normalizes
 *    Samsung/Android EXIF orientation so storage stays compact (~120–280 KB/receipt) and OOM-safe.
 * 3. Returns structured `StoredReceiptOutcome` without throwing uncaught exceptions so an image
 *    decode/copy error can NEVER abort or roll back a financial transaction write.
 * 4. Safely deletes orphaned internal receipt files when replaced or removed.
 */
object ReceiptStorageManager {

    const val RECEIPTS_SUBDIR = "receipts"
    const val CAMERA_CAPTURES_SUBDIR = "camera_captures"
    const val MAX_RAW_INPUT_BYTES = 25L * 1024L * 1024L // 25 MB raw input ceiling
    const val MAX_RECEIPT_DIMENSION_PX = 1600
    const val JPEG_QUALITY = 82

    /**
     * Computes power-of-two `BitmapFactory.Options.inSampleSize` so high-megapixel phone camera
     * captures decode within safe heap bounds.
     */
    fun calculateInSampleSize(
        rawWidth: Int,
        rawHeight: Int,
        maxDimensionPx: Int = MAX_RECEIPT_DIMENSION_PX
    ): Int {
        if (rawWidth <= 0 || rawHeight <= 0 || maxDimensionPx <= 0) return 1
        var inSampleSize = 1
        val largestSide = max(rawWidth, rawHeight)
        if (largestSide > maxDimensionPx) {
            val halfSide = largestSide / 2
            while ((halfSide / inSampleSize) >= maxDimensionPx) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }

    /**
     * Produces a safe, blueprint-compliant (`<= 120` chars) attachment display name.
     */
    fun sanitizeAttachmentDisplayName(rawName: String?, fallbackSuffix: String): String {
        val base = rawName
            ?.trim()
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.replace(Regex("[^a-zA-Z0-9._ -]"), "_")
            ?.trim()
            .orEmpty()

        val normalized = if (base.isBlank()) {
            "receipt_${fallbackSuffix.take(16)}.jpg"
        } else if (!base.contains('.')) {
            "$base.jpg"
        } else {
            val stem = base.substringBeforeLast('.').take(100).ifBlank { "receipt" }
            "$stem.jpg"
        }
        return normalized.take(BlueprintConstraints.TX_ATTACHMENT_NAME_MAX)
    }

    /**
     * Canonical path containment check ensuring we ONLY manage/delete files strictly inside
     * `<filesDir>/receipts/` and never touch external paths or `..` traversals.
     */
    fun isManagedInternalReceiptPath(filesDir: File, candidatePath: String): Boolean {
        val trimmed = candidatePath.trim()
        if (trimmed.isEmpty()) return false
        return runCatching {
            val receiptsRoot = File(filesDir, RECEIPTS_SUBDIR).canonicalPath
            val candidateCanonical = File(trimmed).canonicalPath
            candidateCanonical.startsWith("$receiptsRoot${File.separator}")
        }.getOrDefault(false)
    }

    /**
     * Deletes `candidateOldPath` if and only if:
     * 1. It resides strictly inside app-private `<filesDir>/receipts/`.
     * 2. It is NOT referenced by any remaining transaction row in Room (`remainingReferencedPaths`).
     */
    fun deleteManagedReceiptIfOrphaned(
        filesDir: File,
        candidateOldPath: String,
        remainingReferencedPaths: Set<String>
    ): Boolean {
        val clean = candidateOldPath.trim()
        if (clean.isEmpty()) return false
        if (!isManagedInternalReceiptPath(filesDir, clean)) return false
        val canonicalTarget = runCatching { File(clean).canonicalPath }.getOrNull() ?: return false
        val canonicalRemaining = remainingReferencedPaths.mapNotNullTo(HashSet()) { p ->
            p.trim().takeIf { it.isNotEmpty() }?.let { runCatching { File(it).canonicalPath }.getOrNull() }
        }
        if (canonicalTarget in canonicalRemaining) return false
        return runCatching {
            val targetFile = File(canonicalTarget)
            targetFile.exists() && targetFile.isFile && targetFile.delete()
        }.getOrDefault(false)
    }

    /**
     * Sweeps `<filesDir>/receipts/` and deletes any unreferenced orphan files not present in Room.
     */
    fun sweepUnreferencedReceipts(filesDir: File, activeReferencedPaths: Set<String>): Int {
        val receiptsDir = File(filesDir, RECEIPTS_SUBDIR)
        if (!receiptsDir.exists() || !receiptsDir.isDirectory) return 0
        val canonicalActive = activeReferencedPaths.mapNotNullTo(HashSet()) { p ->
            p.trim().takeIf { it.isNotEmpty() }?.let { runCatching { File(it).canonicalPath }.getOrNull() }
        }
        var deletedCount = 0
        receiptsDir.listFiles()?.forEach { file ->
            val canPath = runCatching { file.canonicalPath }.getOrNull()
            if (file.isFile && canPath != null && canPath !in canonicalActive) {
                if (runCatching { file.delete() }.getOrDefault(false)) {
                    deletedCount++
                }
            }
        }
        return deletedCount
    }

    /**
     * Creates a temporary `FileProvider` URI inside `cacheDir/camera_captures/` for
     * `ActivityResultContracts.TakePicture()`, pruning stale captures older than 24h.
     */
    fun createTemporaryCameraCaptureUri(context: Context): Pair<File, Uri> {
        val captureDir = File(context.cacheDir, CAMERA_CAPTURES_SUBDIR).apply { mkdirs() }
        val cutoffMs = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
        captureDir.listFiles()?.forEach { f ->
            if (f.isFile && f.lastModified() < cutoffMs) {
                runCatching { f.delete() }
            }
        }
        val tempFile = File(
            captureDir,
            "capture_${UUID.randomUUID().toString().replace("-", "").take(16)}.jpg"
        )
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            tempFile
        )
        return tempFile to uri
    }

    fun querySourceDisplayName(context: Context, uri: Uri): String? = runCatching {
        if (uri.scheme == "content") {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else {
                    null
                }
            }
        } else {
            uri.lastPathSegment
        }
    }.getOrNull()

    /**
     * Copies `sourceUri` into a temporary file, normalizes EXIF rotation, downscales to
     * `MAX_RECEIPT_DIMENSION_PX`, and writes a compact JPEG into `files/receipts/rcpt_<uuid>.jpg`.
     * Never throws uncaught exceptions.
     */
    suspend fun importAndCompressReceipt(
        context: Context,
        sourceUri: Uri,
        preferredDisplayName: String? = null
    ): StoredReceiptOutcome = withContext(Dispatchers.IO) {
        val tempDir = File(context.cacheDir, CAMERA_CAPTURES_SUBDIR).apply { mkdirs() }
        val scratchInput = File(
            tempDir,
            "stage_${UUID.randomUUID().toString().replace("-", "").take(12)}.tmp"
        )
        try {
            val inputStream = context.contentResolver.openInputStream(sourceUri)
                ?: return@withContext StoredReceiptOutcome.Failure("Unable to open selected receipt image.")

            var copiedBytes = 0L
            inputStream.use { input ->
                FileOutputStream(scratchInput).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        copiedBytes += read
                        if (copiedBytes > MAX_RAW_INPUT_BYTES) {
                            return@withContext StoredReceiptOutcome.Failure(
                                "Receipt image exceeds the 25 MB safety limit."
                            )
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }

            if (copiedBytes <= 0L) {
                return@withContext StoredReceiptOutcome.Failure("Selected receipt photo is empty.")
            }

            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(scratchInput.absolutePath, boundsOpts)
            if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) {
                return@withContext StoredReceiptOutcome.Failure(
                    "Selected file is not a supported image format."
                )
            }

            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(
                    rawWidth = boundsOpts.outWidth,
                    rawHeight = boundsOpts.outHeight,
                    maxDimensionPx = MAX_RECEIPT_DIMENSION_PX
                )
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            val decoded = BitmapFactory.decodeFile(scratchInput.absolutePath, decodeOpts)
                ?: return@withContext StoredReceiptOutcome.Failure("Could not decode receipt photo.")

            val rotated = applyExifRotationIfNeeded(scratchInput, decoded)
            val scaled = scaleDownIfNeeded(rotated, MAX_RECEIPT_DIMENSION_PX)

            val receiptsDir = File(context.filesDir, RECEIPTS_SUBDIR).apply { mkdirs() }
            val uniqueToken = UUID.randomUUID().toString().replace("-", "").take(16)
            val destFile = File(receiptsDir, "rcpt_$uniqueToken.jpg")

            val compressedOk = FileOutputStream(destFile).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            if (scaled !== rotated) scaled.recycle()
            if (rotated !== decoded) rotated.recycle()
            decoded.recycle()

            if (!compressedOk || !destFile.exists() || destFile.length() == 0L) {
                runCatching { destFile.delete() }
                return@withContext StoredReceiptOutcome.Failure("Failed to store compressed receipt photo.")
            }

            // If source came from our own temporary camera_captures URI, clean up the raw capture file
            cleanupTempCameraUriIfOwned(context, sourceUri)

            val resolvedName = sanitizeAttachmentDisplayName(
                rawName = preferredDisplayName?.takeIf { it.isNotBlank() }
                    ?: querySourceDisplayName(context, sourceUri),
                fallbackSuffix = uniqueToken
            )

            StoredReceiptOutcome.Success(
                localPath = destFile.absolutePath,
                attachmentName = resolvedName,
                attachmentMime = "image/jpeg",
                byteSize = destFile.length()
            )
        } catch (t: Throwable) {
            StoredReceiptOutcome.Failure(
                t.localizedMessage ?: "Receipt photo could not be processed."
            )
        } finally {
            runCatching { scratchInput.delete() }
        }
    }

    /**
     * Decodes a memory-safe preview `Bitmap` on `Dispatchers.IO` for Compose UI rendering.
     * Returns `null` cleanly if the file is missing or unreadable.
     */
    suspend fun loadPreviewBitmap(
        localPath: String,
        maxDimensionPx: Int = 1024
    ): Bitmap? = withContext(Dispatchers.IO) {
        val clean = localPath.trim()
        if (clean.isEmpty()) return@withContext null
        runCatching {
            val file = File(clean)
            if (!file.exists() || !file.isFile || file.length() <= 0L) return@runCatching null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            val opts = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxDimensionPx)
            }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        }.getOrNull()
    }

    /**
     * Loads a memory-safe thumbnail preview directly from a staged `Uri` inside `TransactionEntrySheet`
     * before the user taps Save.
     */
    suspend fun loadUriPreviewBitmap(
        context: Context,
        uri: Uri,
        maxDimensionPx: Int = 512
    ): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            val opts = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxDimensionPx)
            }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, opts)
            }
        }.getOrNull()
    }

    private fun applyExifRotationIfNeeded(sourceFile: File, bitmap: Bitmap): Bitmap {
        val degrees = runCatching {
            val exif = ExifInterface(sourceFile.absolutePath)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)

        if (degrees == 0f) return bitmap
        return runCatching {
            val matrix = Matrix().apply { postRotate(degrees) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }

    private fun scaleDownIfNeeded(bitmap: Bitmap, maxDimensionPx: Int): Bitmap {
        val largest = max(bitmap.width, bitmap.height)
        if (largest <= maxDimensionPx) return bitmap
        val ratio = maxDimensionPx.toFloat() / largest.toFloat()
        val targetW = (bitmap.width * ratio).roundToInt().coerceAtLeast(1)
        val targetH = (bitmap.height * ratio).roundToInt().coerceAtLeast(1)
        return runCatching {
            Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        }.getOrDefault(bitmap)
    }

    private fun cleanupTempCameraUriIfOwned(context: Context, uri: Uri) {
        runCatching {
            val seg = uri.lastPathSegment ?: return
            if (!seg.startsWith("capture_")) return
            val candidate = File(File(context.cacheDir, CAMERA_CAPTURES_SUBDIR), seg)
            if (candidate.exists() && candidate.isFile) {
                candidate.delete()
            }
        }
    }
}
