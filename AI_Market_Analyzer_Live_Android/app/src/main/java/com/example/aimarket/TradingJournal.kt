package com.example.aimarket

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "trading_journal",
    indices = [
        Index(
            value = ["symbol", "timeframe", "signalCandleTime"],
            unique = true
        ),
        Index(value = ["status"]),
        Index(value = ["signalTime"])
    ]
)
data class TradingJournal(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val symbol: String,
    val timeframe: String,
    val direction: String,
    val signalTime: Long,
    val signalCandleTime: Long,

    val entryPrice: Double,
    val stopLoss: Double,
    val takeProfit1: Double,
    val takeProfit2: Double,
    val takeProfit3: Double?,

    val score: Int,

    val status: String = STATUS_OPEN,
    val tp1Hit: Boolean = false,
    val tp2Hit: Boolean = false,
    val tp3Hit: Boolean = false,

    // Breakeven aktif setelah TP1 tercapai.
    val breakevenActivated: Boolean = false,

    val tp1ClosedFraction: Double = DEFAULT_TP1_FRACTION,
    val tp2ClosedFraction: Double = DEFAULT_TP2_FRACTION,
    val tp3ClosedFraction: Double = DEFAULT_TP3_FRACTION,

    val realizedR: Double = 0.0,
    val remainingFraction: Double = 1.0,

    val exitPrice: Double? = null,
    val exitTime: Long? = null,
    val resultR: Double? = null,

    val lastProcessedCandleTime: Long = signalCandleTime,
    val note: String? = null
) {
    companion object {
        const val STATUS_OPEN = "OPEN"
        const val STATUS_TP3 = "TP3"
        const val STATUS_SL = "SL"
        const val STATUS_TP2_FINAL = "TP2_FINAL"
        const val STATUS_TP1_FINAL = "TP1_FINAL"
        const val STATUS_CANCELLED = "CANCELLED"

        const val DIRECTION_BUY = "BUY"
        const val DIRECTION_SELL = "SELL"

        const val DEFAULT_TP1_FRACTION = 0.50
        const val DEFAULT_TP2_FRACTION = 0.30
        const val DEFAULT_TP3_FRACTION = 0.20
    }
}
