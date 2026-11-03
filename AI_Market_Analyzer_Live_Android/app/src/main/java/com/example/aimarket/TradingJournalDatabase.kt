package com.example.aimarket

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TradingJournalDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: TradingJournal): Long

    @Update
    suspend fun update(entry: TradingJournal)

    @Query(
        """
        SELECT * FROM trading_journal
        ORDER BY signalTime DESC
        """
    )
    fun observeAll(): Flow<List<TradingJournal>>

    @Query(
        """
        SELECT * FROM trading_journal
        WHERE status = 'OPEN'
        ORDER BY signalTime ASC
        """
    )
    suspend fun getOpenEntries(): List<TradingJournal>

    @Query(
        """
        SELECT * FROM trading_journal
        WHERE symbol = :symbol
          AND timeframe = :timeframe
          AND signalCandleTime = :candleTime
        LIMIT 1
        """
    )
    suspend fun findSignal(
        symbol: String,
        timeframe: String,
        candleTime: Long
    ): TradingJournal?

    @Query("SELECT COUNT(*) FROM trading_journal")
    fun observeTotalCount(): Flow<Int>

    @Query(
        """
        SELECT COUNT(*) FROM trading_journal
        WHERE status IN (
            'SL',
            'TP3',
            'TP2_FINAL',
            'TP1_FINAL'
        )
        """
    )
    fun observeClosedCount(): Flow<Int>

    @Query(
        """
        SELECT COUNT(*) FROM trading_journal
        WHERE status = 'SL'
        """
    )
    fun observeStopLossCount(): Flow<Int>

    @Query(
        """
        SELECT COUNT(*) FROM trading_journal
        WHERE status IN (
            'SL',
            'TP3',
            'TP2_FINAL',
            'TP1_FINAL'
        )
        AND resultR IS NOT NULL
        AND resultR > 0
        """
    )
    fun observeWinningCount(): Flow<Int>
}

@Database(
    entities = [TradingJournal::class],
    version = 3,
    exportSchema = false
)
abstract class TradingJournalDatabase : RoomDatabase() {

    abstract fun journalDao(): TradingJournalDao

    companion object {

        @Volatile
        private var INSTANCE: TradingJournalDatabase? = null

        /**
         * Migrasi database versi 1 ke versi 2.
         * Struktur ini dipertahankan agar pengguna lama
         * tetap dapat memperbarui database.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {

                db.execSQL(
                    """
                    CREATE TABLE trading_journal_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        symbol TEXT NOT NULL,
                        timeframe TEXT NOT NULL,
                        direction TEXT NOT NULL,
                        signalTime INTEGER NOT NULL,
                        signalCandleTime INTEGER NOT NULL,
                        entryPrice REAL NOT NULL,
                        stopLoss REAL NOT NULL,
                        takeProfit1 REAL NOT NULL,
                        takeProfit2 REAL NOT NULL,
                        takeProfit3 REAL,
                        score INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        tp1Hit INTEGER NOT NULL,
                        tp2Hit INTEGER NOT NULL,
                        tp3Hit INTEGER NOT NULL,
                        tp1ClosedFraction REAL NOT NULL,
                        tp2ClosedFraction REAL NOT NULL,
                        tp3ClosedFraction REAL NOT NULL,
                        realizedR REAL NOT NULL,
                        remainingFraction REAL NOT NULL,
                        exitPrice REAL,
                        exitTime INTEGER,
                        resultR REAL,
                        lastProcessedCandleTime INTEGER NOT NULL,
                        note TEXT
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    INSERT INTO trading_journal_new (
                        id,
                        symbol,
                        timeframe,
                        direction,
                        signalTime,
                        signalCandleTime,
                        entryPrice,
                        stopLoss,
                        takeProfit1,
                        takeProfit2,
                        takeProfit3,
                        score,
                        status,
                        tp1Hit,
                        tp2Hit,
                        tp3Hit,
                        tp1ClosedFraction,
                        tp2ClosedFraction,
                        tp3ClosedFraction,
                        realizedR,
                        remainingFraction,
                        exitPrice,
                        exitTime,
                        resultR,
                        lastProcessedCandleTime,
                        note
                    )
                    SELECT
                        id,
                        symbol,
                        timeframe,
                        direction,
                        signalTime,
                        signalCandleTime,
                        entryPrice,
                        stopLoss,
                        takeProfit1,
                        takeProfit2,
                        takeProfit3,
                        score,
                        status,
                        tp1Hit,
                        tp2Hit,
                        tp3Hit,
                        0.50,
                        0.30,
                        0.20,
                        0.0,
                        CASE
                            WHEN status = 'OPEN' THEN 1.0
                            ELSE 0.0
                        END,
                        exitPrice,
                        exitTime,
                        resultR,
                        lastProcessedCandleTime,
                        note
                    FROM trading_journal
                    """.trimIndent()
                )

                db.execSQL("DROP TABLE trading_journal")

                db.execSQL(
                    "ALTER TABLE trading_journal_new RENAME TO trading_journal"
                )

                db.execSQL(
                    """
                    CREATE UNIQUE INDEX
                    index_trading_journal_symbol_timeframe_signalCandleTime
                    ON trading_journal (
                        symbol,
                        timeframe,
                        signalCandleTime
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE INDEX index_trading_journal_status
                    ON trading_journal (status)
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE INDEX index_trading_journal_signalTime
                    ON trading_journal (signalTime)
                    """.trimIndent()
                )
            }
        }

        /**
         * Migrasi versi 2 ke versi 3.
         * Menambahkan penyimpanan status breakeven.
         *
         * DEFAULT 0 berarti jurnal lama belum mengaktifkan
         * breakeven. Data lama tidak dihapus.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    ALTER TABLE trading_journal
                    ADD COLUMN breakevenActivated
                    INTEGER NOT NULL DEFAULT 0
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context): TradingJournalDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    TradingJournalDatabase::class.java,
                    "trading_journal.db"
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3
                    )
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
