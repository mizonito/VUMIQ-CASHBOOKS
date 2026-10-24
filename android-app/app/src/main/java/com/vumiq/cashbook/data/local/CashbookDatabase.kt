package com.vumiq.cashbook.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        BusinessEntity::class,
        AccountEntity::class,
        CategoryEntity::class,
        PartyEntity::class,
        TransactionEntity::class,
        AuditEventEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class CashbookDatabase : RoomDatabase() {

    abstract fun cashbookDao(): CashbookDao

    /**
     * Flushes any pending SQLite Write-Ahead Log frames into the primary `vumiq_cashbook_ledger.db`
     * file so device backups and JSON exports/restores observe a 100% consistent snapshot.
     */
    fun checkpointWalSafely() {
        runCatching {
            if (isOpen) {
                openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getInt(0)
                    }
                }
            }
        }
    }

    companion object {
        /**
         * Non-destructive migration from Room schema v1 to v2:
         * Adds `receiptLocalPath` to `transactions` while preserving every existing ledger row.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE transactions ADD COLUMN receiptLocalPath TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        @Volatile
        private var INSTANCE: CashbookDatabase? = null

        fun getInstance(context: Context): CashbookDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    CashbookDatabase::class.java,
                    "vumiq_cashbook_ledger.db"
                )
                    .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
