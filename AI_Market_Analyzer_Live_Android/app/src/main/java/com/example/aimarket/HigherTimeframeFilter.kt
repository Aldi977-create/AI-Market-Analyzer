package com.example.aimarket

/**
 * Pemeriksaan arah trend dari timeframe yang lebih tinggi.
 *
 * File ini hanya mengevaluasi candle yang sudah ditutup.
 * Belum terhubung ke AnalysisEngine pada langkah pertama ini.
 */
object HigherTimeframeFilter {

    enum class Bias {
        BULLISH,
        BEARISH,
        NEUTRAL,
        UNAVAILABLE
    }

    data class Result(
        val bias: Bias,
        val timeframe: String,
        val reason: String
    ) {
        val canBuy: Boolean
            get() = bias == Bias.BULLISH

        val canSell: Boolean
            get() = bias == Bias.BEARISH

        val isReady: Boolean
            get() = bias == Bias.BULLISH ||
                bias == Bias.BEARISH
    }

    /**
     * Pasangan timeframe aktif dan timeframe trend.
     */
    fun higherTimeframeFor(timeframe: String): String? {
        return when (timeframe.lowercase()) {
            "1m" -> "15m"
            "5m" -> "15m"
            "15m" -> "1h"
            "1h" -> "4h"
            else -> null
        }
    }

    /**
     * Evaluasi trend menggunakan EMA 9, EMA 21, dan EMA 50.
     *
     * Minimal 52 candle tertutup diperlukan.
     * Candle berjalan tidak pernah digunakan.
     */
    fun evaluate(
        candles: List<Candle>,
        timeframe: String,
        nowMillis: Long = System.currentTimeMillis()
    ): Result {

        val higherTimeframe = higherTimeframeFor(timeframe)
            ?: return unavailable(
                timeframe,
                "Timeframe tidak didukung"
            )

        val intervalMillis = intervalToMillis(higherTimeframe)
            ?: return unavailable(
                higherTimeframe,
                "Interval timeframe tidak valid"
            )

        val closedCandles = candles
            .filter { candle ->
                candle.closed &&
                    candle.time > 0L &&
                    candle.close.isFinite() &&
                    candle.close > 0.0
            }
            .sortedBy { it.time }

        if (closedCandles.size < MIN_CANDLES) {
            return unavailable(
                higherTimeframe,
                "Data candle tertutup belum cukup"
            )
        }

        val latest = closedCandles.last()

        // Menolak data yang terlalu lama atau belum diperbarui.
        val candleAge = nowMillis - (
            latest.time + intervalMillis
        )

        if (candleAge > intervalMillis * MAX_STALE_INTERVALS ||
            candleAge < -intervalMillis
        ) {
            return unavailable(
                higherTimeframe,
                "Data timeframe tinggi sudah kedaluwarsa"
            )
        }

        // Pastikan candle terakhir benar-benar sudah ditutup.
        if (latest.time + intervalMillis > nowMillis) {
            return unavailable(
                higherTimeframe,
                "Candle timeframe tinggi belum ditutup"
            )
        }

        val closes = closedCandles.map { it.close }

        val ema9 = calculateEma(closes, 9)
        val ema21 = calculateEma(closes, 21)
        val ema50 = calculateEma(closes, 50)

        if (ema9 == null || ema21 == null || ema50 == null) {
            return unavailable(
                higherTimeframe,
                "EMA timeframe tinggi belum tersedia"
            )
        }

        val lastClose = closes.last()

        val bullish =
            lastClose > ema50 &&
                ema9 > ema21 &&
                ema21 > ema50

        val bearish =
            lastClose < ema50 &&
                ema9 < ema21 &&
                ema21 < ema50

        return when {
            bullish -> Result(
                Bias.BULLISH,
                higherTimeframe,
                "Trend bullish: harga dan EMA tersusun naik"
            )

            bearish -> Result(
                Bias.BEARISH,
                higherTimeframe,
                "Trend bearish: harga dan EMA tersusun turun"
            )

            else -> Result(
                Bias.NEUTRAL,
                higherTimeframe,
                "Trend timeframe tinggi belum searah"
            )
        }
    }

    private fun calculateEma(
        values: List<Double>,
        period: Int
    ): Double? {
        if (period <= 0 || values.size < period) {
            return null
        }

        val alpha = 2.0 / (period + 1.0)

        var ema = values
            .take(period)
            .average()

        for (index in period until values.size) {
            val value = values[index]

            if (!value.isFinite()) return null

            ema = value * alpha + ema * (1.0 - alpha)
        }

        return ema.takeIf { it.isFinite() }
    }

    private fun intervalToMillis(
        timeframe: String
    ): Long? {
        return when (timeframe.lowercase()) {
            "1m" -> 60_000L
            "5m" -> 300_000L
            "15m" -> 900_000L
            "1h" -> 3_600_000L
            "4h" -> 14_400_000L
            else -> null
        }
    }

    private fun unavailable(
        timeframe: String,
        reason: String
    ) = Result(
        Bias.UNAVAILABLE,
        timeframe,
        reason
    )

    private const val MIN_CANDLES = 52
    private const val MAX_STALE_INTERVALS = 2L
}
