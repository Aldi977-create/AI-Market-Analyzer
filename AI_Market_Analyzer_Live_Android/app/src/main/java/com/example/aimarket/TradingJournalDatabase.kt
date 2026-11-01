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

    // Semua entry, termasuk OPEN dan CANCELLED.
    @Query(
        """
        SELECT COUNT(*) FROM trading_journal
        """
    )
    fun observeTotalCount(): Flow<Int>

    // Hanya transaksi yang benar-benar selesai.
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

    // Jumlah transaksi yang terkena stop loss.
    @Query(
        """
        SELECT COUNT(*) FROM trading_journal
        WHERE status = 'SL'
        """
    )
    fun observeStopLossCount(): Flow<Int>

    // Jumlah transaksi yang mencapai target profit.
    @Query(
        """
        SELECT COUNT(*) FROM trading_journal
        WHERE status IN (
            'TP3',
            'TP2_FINAL',
            'TP1_FINAL'
        )
        """
    )
    fun observeWinningCount(): Flow<Int>
}

@Database(
    entities = [TradingJournal::class],
    version = 1,
    exportSchema = false
)
abstract class TradingJournalDatabase : RoomDatabase() {

    abstract fun journalDao(): TradingJournalDao

    companion object {

        @Volatile
        private var INSTANCE: TradingJournalDatabase? = null

        fun getInstance(context: Context): TradingJournalDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    TradingJournalDatabase::class.java,
                    "trading_journal.db"
                )
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
