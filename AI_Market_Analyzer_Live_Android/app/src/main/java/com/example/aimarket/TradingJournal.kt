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

    // Identitas sinyal
    val symbol: String,
    val timeframe: String,
    val direction: String,
    val signalTime: Long,
    val signalCandleTime: Long,

    // Snapshot harga ketika sinyal dibuat
    val entryPrice: Double,
    val stopLoss: Double,
    val takeProfit1: Double,
    val takeProfit2: Double,
    val takeProfit3: Double?,

    // Informasi analisis
    val score: Int,

    // Pemantauan hasil virtual
    val status: String = STATUS_OPEN,
    val tp1Hit: Boolean = false,
    val tp2Hit: Boolean = false,
    val tp3Hit: Boolean = false,
    val exitPrice: Double? = null,
    val exitTime: Long? = null,
    val resultR: Double? = null,

    // Audit dan pemulihan pemantauan
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
    }
}
