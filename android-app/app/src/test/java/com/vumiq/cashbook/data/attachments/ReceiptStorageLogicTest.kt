package com.vumiq.cashbook.data.attachments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ReceiptStorageLogicTest {

    @Test
    fun `calculateInSampleSize downsamples high megapixel camera captures safely`() {
        assertEquals(1, ReceiptStorageManager.calculateInSampleSize(1200, 900, 1600))
        assertEquals(2, ReceiptStorageManager.calculateInSampleSize(4032, 3024, 1600))
        assertEquals(4, ReceiptStorageManager.calculateInSampleSize(8000, 6000, 1600))
    }

    @Test
    fun `sanitizeAttachmentDisplayName enforces 120 char ceiling and strips path segments`() {
        val cleaned = ReceiptStorageManager.sanitizeAttachmentDisplayName(
            "/storage/emulated/0/DCIM/Camera/IMG_20261024_Invoice#12.png",
            "abc12345"
        )
        assertEquals("IMG_20261024_Invoice_12.jpg", cleaned)

        val fallback = ReceiptStorageManager.sanitizeAttachmentDisplayName("   ", "tok998877")
        assertEquals("receipt_tok998877.jpg", fallback)

        val huge = "A".repeat(220) + ".jpeg"
        val bounded = ReceiptStorageManager.sanitizeAttachmentDisplayName(huge, "tok")
        assertTrue(bounded.length <= 120)
        assertTrue(bounded.endsWith(".jpg"))
    }

    @Test
    fun `isManagedInternalReceiptPath blocks path traversal and external directories`() {
        val tempRoot = Files.createTempDirectory("vumiq_files_test").toFile()
        try {
            val receiptsDir = File(tempRoot, ReceiptStorageManager.RECEIPTS_SUBDIR).apply { mkdirs() }
            val validFile = File(receiptsDir, "rcpt_123.jpg").apply { writeText("img") }
            val outsideFile = File(tempRoot, "secret.db").apply { writeText("db") }
            val traversalPath = File(receiptsDir, "../secret.db").path

            assertTrue(ReceiptStorageManager.isManagedInternalReceiptPath(tempRoot, validFile.absolutePath))
            assertFalse(ReceiptStorageManager.isManagedInternalReceiptPath(tempRoot, outsideFile.absolutePath))
            assertFalse(ReceiptStorageManager.isManagedInternalReceiptPath(tempRoot, traversalPath))
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `deleteManagedReceiptIfOrphaned deletes unreferenced internal receipt while preserving active ones`() {
        val tempRoot = Files.createTempDirectory("vumiq_orphan_test").toFile()
        try {
            val receiptsDir = File(tempRoot, ReceiptStorageManager.RECEIPTS_SUBDIR).apply { mkdirs() }
            val orphanFile = File(receiptsDir, "rcpt_orphan.jpg").apply { writeText("old_jpeg") }
            val activeFile = File(receiptsDir, "rcpt_active.jpg").apply { writeText("live_jpeg") }

            val activeSet = setOf(activeFile.absolutePath)

            // Active file must NOT be deleted
            assertFalse(
                ReceiptStorageManager.deleteManagedReceiptIfOrphaned(
                    filesDir = tempRoot,
                    candidateOldPath = activeFile.absolutePath,
                    remainingReferencedPaths = activeSet
                )
            )
            assertTrue(activeFile.exists())

            // Orphaned file MUST be deleted
            assertTrue(
                ReceiptStorageManager.deleteManagedReceiptIfOrphaned(
                    filesDir = tempRoot,
                    candidateOldPath = orphanFile.absolutePath,
                    remainingReferencedPaths = activeSet
                )
            )
            assertFalse(orphanFile.exists())
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
