package com.example.aimarket

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
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
        if (direction != TradingJournal.DIRECTION_BUY &&
            direction != TradingJournal.DIRECTION_SELL
        ) return -1L

        if (!entryPrice.isFinite() ||
            !stopLoss.isFinite() ||
            !takeProfit1.isFinite() ||
            !takeProfit2.isFinite() ||
            (takeProfit3 != null && !takeProfit3.isFinite())
        ) return -1L

        if (entryPrice <= 0.0 || stopLoss <= 0.0) return -1L

        val validLevels = if (direction == TradingJournal.DIRECTION_BUY) {
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

        return dao.insert(entry)
    }

    /**
     * Process one fully closed candle for an open virtual trade.
     *
     * If SL and a target are touched in the same candle,
     * SL takes precedence to keep backtest results conservative.
     */
    suspend fun processClosedCandle(
        symbol: String,
        timeframe: String,
        candleTime: Long,
        high: Double,
        low: Double
    ) {
        if (!high.isFinite() || !low.isFinite() || low > high) return

        database.withTransaction {
            val entries = dao.getOpenEntries().filter {
                it.symbol == symbol &&
                    it.timeframe == timeframe &&
                    candleTime > it.signalCandleTime &&
                    candleTime > it.lastProcessedCandleTime
            }

            for (entry in entries) {
                val updated = processCandle(entry, candleTime, high, low)
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
        val isBuy = entry.direction == TradingJournal.DIRECTION_BUY

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

        val tp1Reached = entry.tp1Hit || tp1HitNow
        val tp2Reached = entry.tp2Hit || tp2HitNow
        val tp3Reached = entry.tp3Hit || tp3HitNow

        // Same-candle SL/TP ambiguity is resolved conservatively as SL.
        if (stopHit) {
            return entry.copy(
                status = TradingJournal.STATUS_SL,
                tp1Hit = entry.tp1Hit,
                tp2Hit = entry.tp2Hit,
                tp3Hit = entry.tp3Hit,
                exitPrice = entry.stopLoss,
                exitTime = candleTime,
                resultR = -1.0,
                lastProcessedCandleTime = candleTime,
                note = if (tp1HitNow || tp2HitNow || tp3HitNow) {
                    "SL and target touched in same candle; SL prioritized"
                } else {
                    entry.note
                }
            )
        }

        if (entry.takeProfit3 != null && tp3HitNow) {
            return entry.copy(
                status = TradingJournal.STATUS_TP3,
                tp1Hit = true,
                tp2Hit = true,
                tp3Hit = true,
                exitPrice = entry.takeProfit3,
                exitTime = candleTime,
                resultR = calculateR(entry, entry.takeProfit3),
                lastProcessedCandleTime = candleTime
            )
        }

        // If TP3 does not exist, TP2 is the final target.
        if (entry.takeProfit3 == null && tp2HitNow) {
            return entry.copy(
                status = TradingJournal.STATUS_TP2_FINAL,
                tp1Hit = true,
                tp2Hit = true,
                exitPrice = entry.takeProfit2,
                exitTime = candleTime,
                resultR = calculateR(entry, entry.takeProfit2),
                lastProcessedCandleTime = candleTime
            )
        }

        return entry.copy(
            tp1Hit = tp1Reached,
            tp2Hit = tp2Reached,
            tp3Hit = tp3Reached,
            lastProcessedCandleTime = candleTime
        )
    }

    private fun calculateR(
        entry: TradingJournal,
        exitPrice: Double
    ): Double {
        val risk = max(
            kotlin.math.abs(entry.entryPrice - entry.stopLoss),
            1e-12
        )

        val profit = if (entry.direction == TradingJournal.DIRECTION_BUY) {
            exitPrice - entry.entryPrice
        } else {
            entry.entryPrice - exitPrice
        }

        return profit / risk
    }
}
