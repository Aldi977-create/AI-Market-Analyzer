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

    /**
     * BUY tidak menghalangi SELL dan sebaliknya.
     * Posisi searah yang masih OPEN akan menghalangi sinyal baru.
     */
    suspend fun hasOpenEntry(
        symbol: String,
        timeframe: String,
        direction: String
    ): Boolean {
        if (
            symbol.isBlank() ||
            timeframe.isBlank() ||
            (
                direction != TradingJournal.DIRECTION_BUY &&
                direction != TradingJournal.DIRECTION_SELL
            )
        ) return false

        return dao.getOpenEntries().any {
            it.symbol.equals(symbol, ignoreCase = true) &&
                it.timeframe.equals(timeframe, ignoreCase = true) &&
                it.direction.equals(direction, ignoreCase = true)
        }
    }

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

        val fractionsValid =
            TradingJournal.DEFAULT_TP1_FRACTION > 0.0 &&
                TradingJournal.DEFAULT_TP2_FRACTION > 0.0 &&
                TradingJournal.DEFAULT_TP3_FRACTION > 0.0 &&
                abs(
                    TradingJournal.DEFAULT_TP1_FRACTION +
                        TradingJournal.DEFAULT_TP2_FRACTION +
                        TradingJournal.DEFAULT_TP3_FRACTION - 1.0
                ) < 1e-9

        if (!fractionsValid) return -1L

        return database.withTransaction {

            // Anti-duplikat berdasarkan candle sinyal.
            if (
                dao.findSignal(
                    symbol,
                    timeframe,
                    signalCandleTime
                ) != null
            ) {
                return@withTransaction -1L
            }

            // Hanya posisi searah yang menghalangi sinyal baru.
            val alreadyOpen = dao.getOpenEntries().any {
                it.symbol.equals(symbol, ignoreCase = true) &&
                    it.timeframe.equals(timeframe, ignoreCase = true) &&
                    it.direction.equals(direction, ignoreCase = true)
            }

            if (alreadyOpen) {
                return@withTransaction -1L
            }

            dao.insert(
                TradingJournal(
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
            )
        }
    }

    /**
     * Memproses candle yang sudah tertutup.
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

            val entries = dao.getOpenEntries().filter { entry ->
                entry.symbol.equals(symbol, ignoreCase = true) &&
                    entry.timeframe.equals(timeframe, ignoreCase = true) &&
                    candleTime > entry.signalCandleTime &&
                    candleTime > entry.lastProcessedCandleTime
            }

            for (entry in entries) {

                val updated = processCandle(
                    entry = entry,
                    candleTime = candleTime,
                    high = high,
                    low = low
                )

                dao.update(updated)
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

        /*
         * Setelah TP1, gunakan Entry sebagai SL efektif.
         * SL awal di database tidak diubah agar perhitungan
         * risiko awal tetap akurat.
         */
        val effectiveStopLoss =
            if (entry.breakevenActivated) {
                entry.entryPrice
            } else {
                entry.stopLoss
            }

        val stopHit = if (isBuy) {
            low <= effectiveStopLoss
        } else {
            high >= effectiveStopLoss
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

        var realizedR = entry.realizedR

        var remaining =
            entry.remainingFraction.coerceIn(0.0, 1.0)

        var tp1Hit = entry.tp1Hit
        var tp2Hit = entry.tp2Hit
        var tp3Hit = entry.tp3Hit

        var breakevenActivated =
            entry.breakevenActivated

        /*
         * SL diprioritaskan jika SL dan TP tersentuh
         * pada candle yang sama.
         *
         * Jika breakeven sudah aktif, penutupan sisa posisi
         * di Entry menghasilkan 0R untuk bagian tersebut.
         */
        if (stopHit) {

            val remainingResult =
                remaining * calculateR(
                    entry,
                    effectiveStopLoss
                )

            val stopResult =
                realizedR + remainingResult

            val simultaneousTarget =
                (!tp1Hit && tp1HitNow) ||
                    (!tp2Hit && tp2HitNow) ||
                    (
                        entry.takeProfit3 != null &&
                            !tp3Hit &&
                            tp3HitNow
                    )

            val stopNote = when {
                breakevenActivated ->
                    "Breakeven aktif: sisa posisi ditutup pada harga Entry"

                simultaneousTarget ->
                    "SL dan TP tersentuh pada candle yang sama; SL diprioritaskan"

                else -> entry.note
            }

            return entry.copy(
                status = TradingJournal.STATUS_SL,
                exitPrice = effectiveStopLoss,
                exitTime = candleTime,
                realizedR = stopResult,
                remainingFraction = 0.0,
                resultR = stopResult,
                breakevenActivated = breakevenActivated,
                lastProcessedCandleTime = candleTime,
                note = stopNote
            )
        }

        /*
         * TP1:
         * Realisasi 50% posisi awal.
         * Setelah TP1, aktifkan breakeven.
         */
        if (!tp1Hit && tp1HitNow) {

            val fraction = minOf(
                entry.tp1ClosedFraction.coerceIn(0.0, 1.0),
                remaining
            )

            realizedR += fraction *
                calculateR(entry, entry.takeProfit1)

            remaining =
                (remaining - fraction).coerceAtLeast(0.0)

            tp1Hit = true
            breakevenActivated = true
        }

        /*
         * TP2:
         * Realisasi 30% posisi awal.
         */
        if (!tp2Hit && tp2HitNow) {

            val fraction = minOf(
                entry.tp2ClosedFraction.coerceIn(0.0, 1.0),
                remaining
            )

            realizedR += fraction *
                calculateR(entry, entry.takeProfit2)

            remaining =
                (remaining - fraction).coerceAtLeast(0.0)

            tp2Hit = true
        }

        /*
         * TP3:
         * Tutup seluruh sisa posisi.
         */
        if (
            entry.takeProfit3 != null &&
            !tp3Hit &&
            tp3HitNow
        ) {

            val target = entry.takeProfit3

            realizedR += remaining *
                calculateR(entry, target)

            return entry.copy(
                status = TradingJournal.STATUS_TP3,
                tp1Hit = tp1Hit,
                tp2Hit = tp2Hit,
                tp3Hit = true,
                breakevenActivated = breakevenActivated,
                realizedR = realizedR,
                remainingFraction = 0.0,
                exitPrice = target,
                exitTime = candleTime,
                resultR = realizedR,
                lastProcessedCandleTime = candleTime
            )
        }

        /*
         * Jika TP3 tidak tersedia:
         * TP2 menutup seluruh sisa posisi.
         */
        if (entry.takeProfit3 == null && tp2Hit) {

            if (remaining > 0.0) {
                realizedR += remaining *
                    calculateR(entry, entry.takeProfit2)
            }

            return entry.copy(
                status = TradingJournal.STATUS_TP2_FINAL,
                tp1Hit = tp1Hit,
                tp2Hit = true,
                breakevenActivated = breakevenActivated,
                realizedR = realizedR,
                remainingFraction = 0.0,
                exitPrice = entry.takeProfit2,
                exitTime = candleTime,
                resultR = realizedR,
                lastProcessedCandleTime = candleTime
            )
        }

        /*
         * Simpan status TP dan breakeven.
         * Status ini tersimpan melalui Room Database.
         */
        return entry.copy(
            tp1Hit = tp1Hit,
            tp2Hit = tp2Hit,
            tp3Hit = tp3Hit,
            breakevenActivated = breakevenActivated,
            realizedR = realizedR,
            remainingFraction = remaining.coerceIn(0.0, 1.0),
            lastProcessedCandleTime = candleTime
        )
    }

    /**
     * Perhitungan R selalu menggunakan SL AWAL.
     * Jangan gunakan effectiveStopLoss di fungsi ini,
     * karena SL efektif bisa berubah menjadi harga Entry.
     */
    private fun calculateR(
        entry: TradingJournal,
        exitPrice: Double
    ): Double {

        val originalRisk = max(
            abs(entry.entryPrice - entry.stopLoss),
            1e-12
        )

        val profit =
            if (entry.direction == TradingJournal.DIRECTION_BUY) {
                exitPrice - entry.entryPrice
            } else {
                entry.entryPrice - exitPrice
            }

        return profit / originalRisk
    }
}
