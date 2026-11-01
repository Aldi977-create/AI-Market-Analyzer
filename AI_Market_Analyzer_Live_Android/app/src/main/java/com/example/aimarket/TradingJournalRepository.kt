package com.example.aimarket

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlin.math.abs
import kotlin.math.max

class TradingJournalRepository(context: Context) {

    private val database =
        TradingJournalDatabase.getInstance(context)

    private val dao = database.journalDao()

    fun observeAll(): Flow<List<TradingJournal>> =
        dao.observeAll()

    fun observeTotalCount(): Flow<Int> =
        dao.observeTotalCount()

    fun observeClosedCount(): Flow<Int> =
        dao.observeClosedCount()

    fun observeStopLossCount(): Flow<Int> =
        dao.observeStopLossCount()

    fun observeWinningCount(): Flow<Int> =
        dao.observeWinningCount()

    suspend fun recordSignal(
        symbol: String,
        timeframe: String,
        direction: String,
        signalTime: Long,
        signalCandleTime: Long,
        entryPrice: Double,
        stopLoss: Double,
        takeProfit1: Double,
        takeProfit2: Double,
        takeProfit3: Double?,
        score: Int
    ): Long {

        if (
            direction != TradingJournal.DIRECTION_BUY &&
            direction != TradingJournal.DIRECTION_SELL
        ) return -1L

        if (
            symbol.isBlank() ||
            timeframe.isBlank() ||
            signalTime <= 0L ||
            signalCandleTime <= 0L ||
            !entryPrice.isFinite() ||
            !stopLoss.isFinite() ||
            !takeProfit1.isFinite() ||
            !takeProfit2.isFinite() ||
            (takeProfit3 != null && !takeProfit3.isFinite())
        ) return -1L

        if (
            entryPrice <= 0.0 ||
            stopLoss <= 0.0 ||
            takeProfit1 <= 0.0 ||
            takeProfit2 <= 0.0 ||
            (takeProfit3 != null && takeProfit3 <= 0.0)
        ) return -1L

        val validLevels =
            if (direction == TradingJournal.DIRECTION_BUY) {
                stopLoss < entryPrice &&
                    takeProfit1 > entryPrice &&
                    takeProfit2 > takeProfit1 &&
                    (takeProfit3 == null || takeProfit3 > takeProfit2)
            } else {
                stopLoss > entryPrice &&
                    takeProfit1 < entryPrice &&
                    takeProfit2 < takeProfit1 &&
                    (takeProfit3 == null || takeProfit3 < takeProfit2)
            }

        if (!validLevels) return -1L

        return database.withTransaction {

            // Cegah sinyal candle yang sama tercatat dua kali.
            if (
                dao.findSignal(
                    symbol,
                    timeframe,
                    signalCandleTime
                ) != null
            ) {
                return@withTransaction -1L
            }

            // Satu entry OPEN untuk setiap simbol dan timeframe.
            val hasOpenEntry = dao.getOpenEntries().any {
                it.symbol == symbol &&
                    it.timeframe == timeframe
            }

            if (hasOpenEntry) {
                return@withTransaction -1L
            }

            val entry = TradingJournal(
                symbol = symbol,
                timeframe = timeframe,
                direction = direction,
                signalTime = signalTime,
                signalCandleTime = signalCandleTime,
                entryPrice = entryPrice,
                stopLoss = stopLoss,
                takeProfit1 = takeProfit1,
                takeProfit2 = takeProfit2,
                takeProfit3 = takeProfit3,
                score = score.coerceIn(0, 100)
            )

            dao.insert(entry)
        }
    }

    /**
     * Memproses candle tertutup secara berurutan.
     *
     * Jika SL dan TP tersentuh pada candle yang sama,
     * SL diprioritaskan untuk bagian posisi yang masih terbuka.
     */
    suspend fun processClosedCandle(
        symbol: String,
        timeframe: String,
        candleTime: Long,
        high: Double,
        low: Double
    ) {
        if (
            symbol.isBlank() ||
            timeframe.isBlank() ||
            candleTime <= 0L ||
            !high.isFinite() ||
            !low.isFinite() ||
            high <= 0.0 ||
            low <= 0.0 ||
            low > high
        ) return

        database.withTransaction {

            val entries = dao.getOpenEntries().filter {
                it.symbol == symbol &&
                    it.timeframe == timeframe &&
                    candleTime > it.signalCandleTime &&
                    candleTime > it.lastProcessedCandleTime
            }

            for (entry in entries) {
                dao.update(
                    processCandle(
                        entry = entry,
                        candleTime = candleTime,
                        high = high,
                        low = low
                    )
                )
            }
        }
    }

    private fun processCandle(
        entry: TradingJournal,
        candleTime: Long,
        high: Double,
        low: Double
    ): TradingJournal {

        val isBuy =
            entry.direction == TradingJournal.DIRECTION_BUY

        val stopHit = if (isBuy) {
            low <= entry.stopLoss
        } else {
            high >= entry.stopLoss
        }

        val tp1HitNow = if (isBuy) {
            high >= entry.takeProfit1
        } else {
            low <= entry.takeProfit1
        }

        val tp2HitNow = if (isBuy) {
            high >= entry.takeProfit2
        } else {
            low <= entry.takeProfit2
        }

        val tp3HitNow = entry.takeProfit3?.let { target ->
            if (isBuy) high >= target else low <= target
        } ?: false

        val risk = max(
            abs(entry.entryPrice - entry.stopLoss),
            1e-12
        )

        var realizedR = entry.realizedR
        var remaining = entry.remainingFraction

        var tp1Hit = entry.tp1Hit
        var tp2Hit = entry.tp2Hit
        var tp3Hit = entry.tp3Hit

        // SL diprioritaskan untuk sisa posisi.
        // TP yang baru tersentuh pada candle yang sama
        // tidak dihitung sebagai profit.
        if (stopHit) {
            val stopResult = realizedR - remaining

            return entry.copy(
                status = TradingJournal.STATUS_SL,
                exitPrice = entry.stopLoss,
                exitTime = candleTime,
                realizedR = stopResult,
                remainingFraction = 0.0,
                resultR = stopResult,
                lastProcessedCandleTime = candleTime,
                note = if (
                    tp1HitNow || tp2HitNow || tp3HitNow
                ) {
                    "SL and TP touched in same candle; SL prioritized for remaining position"
                } else {
                    entry.note
                }
            )
        }

        // TP1: tutup 50% posisi awal.
        if (!tp1Hit && tp1HitNow) {
            val fraction = entry.tp1ClosedFraction

            realizedR += fraction *
                calculateR(entry, entry.takeProfit1)

            remaining -= fraction
            tp1Hit = true
        }

        // TP2: tutup 30% posisi awal.
        if (!tp2Hit && tp2HitNow) {
            val fraction = entry.tp2ClosedFraction

            realizedR += fraction *
                calculateR(entry, entry.takeProfit2)

            remaining -= fraction
            tp2Hit = true
        }

        // TP3: tutup seluruh sisa posisi.
        if (
            entry.takeProfit3 != null &&
            !tp3Hit &&
            tp3HitNow
        ) {
            val target = entry.takeProfit3
            val fraction = remaining

            realizedR += fraction *
                calculateR(entry, target)

            remaining = 0.0
            tp3Hit = true

            return entry.copy(
                status = TradingJournal.STATUS_TP3,
                tp1Hit = true,
                tp2Hit = true,
                tp3Hit = true,
                realizedR = realizedR,
                remainingFraction = 0.0,
                exitPrice = target,
                exitTime = candleTime,
                resultR = realizedR,
                lastProcessedCandleTime = candleTime
            )
        }

        // Jika TP3 tidak tersedia, TP2 menutup
        // seluruh sisa posisi, bukan hanya 30%.
        if (
            entry.takeProfit3 == null &&
            tp2Hit
        ) {
            if (remaining > 0.0) {
                realizedR += remaining *
                    calculateR(entry, entry.takeProfit2)

                remaining = 0.0
            }

            return entry.copy(
                status = TradingJournal.STATUS_TP2_FINAL,
                tp1Hit = true,
                tp2Hit = true,
                realizedR = realizedR,
                remainingFraction = 0.0,
                exitPrice = entry.takeProfit2,
                exitTime = candleTime,
                resultR = realizedR,
                lastProcessedCandleTime = candleTime
            )
        }

        // Simpan perkembangan posisi yang masih berjalan.
        return entry.copy(
            tp1Hit = tp1Hit,
            tp2Hit = tp2Hit,
            tp3Hit = tp3Hit,
            realizedR = realizedR,
            remainingFraction = remaining.coerceAtLeast(0.0),
            lastProcessedCandleTime = candleTime
        )
    }

    private fun calculateR(
        entry: TradingJournal,
        exitPrice: Double
    ): Double {

        val risk = max(
            abs(entry.entryPrice - entry.stopLoss),
            1e-12
        )

        val profit =
            if (entry.direction == TradingJournal.DIRECTION_BUY) {
                exitPrice - entry.entryPrice
            } else {
                entry.entryPrice - exitPrice
            }

        return profit / risk
    }
}
