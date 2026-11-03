package com.example.aimarket

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlin.math.abs
import kotlin.math.max

data class JournalEvent(
    val entryId: Long,
    val symbol: String,
    val timeframe: String,
    val direction: String,
    val type: String,
    val price: Double,
    val resultR: Double? = null
)

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

            if (
                dao.findSignal(
                    symbol,
                    timeframe,
                    signalCandleTime
                ) != null
            ) {
                return@withTransaction -1L
            }

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
     * Proses candle tertutup dan kembalikan event
     * yang baru terjadi pada candle tersebut.
     */
    suspend fun processClosedCandle(
        symbol: String,
        timeframe: String,
        candleTime: Long,
        high: Double,
        low: Double
    ): List<JournalEvent> {

        if (
            symbol.isBlank() ||
            timeframe.isBlank() ||
            candleTime <= 0L ||
            !high.isFinite() ||
            !low.isFinite() ||
            high <= 0.0 ||
            low <= 0.0 ||
            low > high
        ) return emptyList()

        return database.withTransaction {

            val events = mutableListOf<JournalEvent>()

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

                fun addEvent(
                    type: String,
                    price: Double,
                    resultR: Double? = null
                ) {
                    events.add(
                        JournalEvent(
                            entryId = entry.id,
                            symbol = entry.symbol,
                            timeframe = entry.timeframe,
                            direction = entry.direction,
                            type = type,
                            price = price,
                            resultR = resultR
                        )
                    )
                }

                if (!entry.tp1Hit && updated.tp1Hit) {
                    addEvent("TP1", entry.takeProfit1)
                }

                if (!entry.tp2Hit && updated.tp2Hit) {
                    addEvent("TP2", entry.takeProfit2)
                }

                if (!entry.tp3Hit && updated.tp3Hit) {
                    addEvent(
                        "TP3",
                        entry.takeProfit3
                            ?: updated.exitPrice
                            ?: entry.entryPrice,
                        updated.resultR
                    )
                }

                if (
                    entry.status == TradingJournal.STATUS_OPEN &&
                    updated.status == TradingJournal.STATUS_SL
                ) {
                    addEvent(
                        if (entry.breakevenActivated) {
                            "BREAKEVEN"
                        } else {
                            "SL"
                        },
                        updated.exitPrice ?: entry.stopLoss,
                        updated.resultR
                    )
                }

                if (
                    entry.status == TradingJournal.STATUS_OPEN &&
                    updated.status == TradingJournal.STATUS_TP2_FINAL
                ) {
                    addEvent(
                        "TP2_FINAL",
                        updated.exitPrice ?: entry.takeProfit2,
                        updated.resultR
                    )
                }
            }

            events
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

        // SL efektif menjadi Entry setelah TP1.
        // SL awal tetap disimpan untuk perhitungan R.
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

        // SL diprioritaskan pada candle yang sama.
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

        // TP1: tutup 50% dan aktifkan breakeven.
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

        // TP2: tutup 30% posisi awal.
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

        // TP3: tutup seluruh sisa posisi.
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

        // Tanpa TP3, TP2 menutup seluruh sisa posisi.
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
     * Risiko selalu dihitung dari SL awal.
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
